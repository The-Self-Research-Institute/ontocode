import type { LlmProvider } from "./LlmInsightsService";
import { ProviderProtocolError } from "./codeAssistantProviderTypes";

type Json = Record<string, any>;

export interface StreamAssembler {
  accept(event: string, data: string): string | null;
  result(): Json;
}

export async function readSseEvents(
  res: Response,
  onEvent: (event: string, data: string) => void,
): Promise<void> {
  if (!res.body) throw new ProviderProtocolError("The provider did not send a response stream.");
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  for (;;) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done });
    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = done ? "" : (blocks.pop() ?? "");
    for (const block of blocks) dispatchBlock(block, onEvent);
    if (done) return;
  }
}

function dispatchBlock(block: string, onEvent: (event: string, data: string) => void): void {
  let event = "message";
  const data: string[] = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith("event:")) event = line.slice(6).trim();
    else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
  }
  if (data.length > 0) onEvent(event, data.join("\n"));
}

function parse(data: string): Json | null {
  if (data === "[DONE]") return null;
  try {
    return JSON.parse(data);
  } catch {
    throw new ProviderProtocolError("The provider sent a stream event that could not be read.");
  }
}

function claudeAssembler(): StreamAssembler {
  const message: Json = { type: "message", role: "assistant", content: [], stop_reason: null, usage: {} };
  const partialJson = new Map<number, string>();
  return {
    accept(_event, data) {
      const chunk = parse(data);
      if (!chunk) return null;
      if (chunk.type === "error") throw new ProviderProtocolError(chunk.error?.message ?? "The provider reported an error.");
      if (chunk.type === "message_start") Object.assign(message.usage, chunk.message?.usage ?? {});
      if (chunk.type === "content_block_start") message.content[chunk.index] = { ...chunk.content_block };
      if (chunk.type === "content_block_stop" && partialJson.has(chunk.index)) {
        message.content[chunk.index].input = JSON.parse(partialJson.get(chunk.index) || "{}");
      }
      if (chunk.type === "message_delta") {
        message.stop_reason = chunk.delta?.stop_reason ?? message.stop_reason;
        Object.assign(message.usage, chunk.usage ?? {});
      }
      if (chunk.type !== "content_block_delta") return null;
      const block = message.content[chunk.index];
      const delta = chunk.delta ?? {};
      if (delta.type === "text_delta") {
        block.text = (block.text ?? "") + delta.text;
        return delta.text;
      }
      if (delta.type === "input_json_delta") partialJson.set(chunk.index, (partialJson.get(chunk.index) ?? "") + delta.partial_json);
      if (delta.type === "thinking_delta") block.thinking = (block.thinking ?? "") + delta.thinking;
      if (delta.type === "signature_delta") block.signature = delta.signature;
      return null;
    },
    result: () => message,
  };
}

function openAiAssembler(): StreamAssembler {
  let content = "";
  let finishReason: string | null = null;
  let usage: Json | undefined;
  const toolCalls: Json[] = [];
  return {
    accept(_event, data) {
      const chunk = parse(data);
      if (!chunk) return null;
      if (chunk.error) throw new ProviderProtocolError(chunk.error.message ?? "The provider reported an error.");
      if (chunk.usage) usage = chunk.usage;
      const choice = chunk.choices?.[0];
      if (!choice) return null;
      finishReason = choice.finish_reason ?? finishReason;
      for (const call of choice.delta?.tool_calls ?? []) {
        const slot = (toolCalls[call.index] ??= { id: "", type: "function", function: { name: "", arguments: "" } });
        if (call.id) slot.id = call.id;
        if (call.function?.name) slot.function.name += call.function.name;
        if (call.function?.arguments) slot.function.arguments += call.function.arguments;
      }
      const text = choice.delta?.content;
      if (typeof text !== "string" || !text) return null;
      content += text;
      return text;
    },
    result: () => ({
      choices: [{
        index: 0,
        finish_reason: finishReason,
        message: { role: "assistant", content: content || null, ...(toolCalls.length > 0 ? { tool_calls: toolCalls } : {}) },
      }],
      ...(usage ? { usage } : {}),
    }),
  };
}

function geminiAssembler(): StreamAssembler {
  const parts: Json[] = [];
  let finishReason: string | undefined;
  let usageMetadata: Json | undefined;
  const plainText = (part: Json | undefined) => !!part && Object.keys(part).length === 1 && typeof part.text === "string";
  return {
    accept(_event, data) {
      const chunk = parse(data);
      if (!chunk) return null;
      if (chunk.error) throw new ProviderProtocolError(chunk.error.message ?? "The provider reported an error.");
      if (chunk.usageMetadata) usageMetadata = chunk.usageMetadata;
      const candidate = chunk.candidates?.[0];
      finishReason = candidate?.finishReason ?? finishReason;
      let delta = "";
      for (const part of candidate?.content?.parts ?? []) {
        const last = parts[parts.length - 1];
        if (plainText(part) && plainText(last)) last.text += part.text;
        else parts.push({ ...part });
        if (typeof part.text === "string" && !part.thought) delta += part.text;
      }
      return delta || null;
    },
    result: () => ({
      candidates: [{ content: { role: "model", parts }, ...(finishReason ? { finishReason } : {}) }],
      ...(usageMetadata ? { usageMetadata } : {}),
    }),
  };
}

export function createStreamAssembler(provider: LlmProvider): StreamAssembler {
  if (provider === "claude") return claudeAssembler();
  if (provider === "openai") return openAiAssembler();
  return geminiAssembler();
}

export function withStreamFlags(provider: LlmProvider, body: Json): Json {
  if (provider === "claude") return { ...body, stream: true };
  if (provider === "openai") return { ...body, stream: true, stream_options: { include_usage: true } };
  return body;
}

export function streamingUrl(provider: LlmProvider, url: string): string {
  return provider === "gemini" ? url.replace(/:generateContent$/, ":streamGenerateContent?alt=sse") : url;
}

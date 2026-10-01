import { LlmProvider } from "./LlmInsightsService";
import {
  ProviderProtocolError,
  type AssistantTurn,
  type ConversationState,
  type NextTurn,
  type ProviderUsage,
  type ToolCallRequest,
  type ToolResultForModel,
} from "./codeAssistantProviderTypes";

function joinedText(values: Array<string | undefined>): string | undefined {
  const text = values.filter((v): v is string => typeof v === "string").join("\n").trim();
  return text ? text : undefined;
}

function toolResultPayload(r: ToolResultForModel): unknown {
  const inner = r.isError ? { error: r.result } : r.result;
  return r.provenance ? { provenance: r.provenance, result: inner } : inner;
}

function geminiToolResponse(r: ToolResultForModel): unknown {
  if (r.provenance) return toolResultPayload(r);
  return r.isError ? { error: r.result } : { result: r.result };
}

function tokenCount(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : undefined;
}

type UsageCounts = Pick<ProviderUsage, "inputTokens" | "outputTokens" | "cacheReadTokens" | "cacheWriteTokens">;

export function parseProviderUsage(provider: LlmProvider, raw: unknown): UsageCounts {
  const r = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>;
  let counts: UsageCounts;
  if (provider === "claude") {
    const u = (r.usage ?? {}) as Record<string, unknown>;
    counts = {
      inputTokens: tokenCount(u.input_tokens),
      outputTokens: tokenCount(u.output_tokens),
      cacheReadTokens: tokenCount(u.cache_read_input_tokens),
      cacheWriteTokens: tokenCount(u.cache_creation_input_tokens),
    };
  } else if (provider === "openai") {
    const u = (r.usage ?? {}) as Record<string, unknown>;
    const details = (u.prompt_tokens_details ?? {}) as Record<string, unknown>;
    counts = {
      inputTokens: tokenCount(u.prompt_tokens),
      outputTokens: tokenCount(u.completion_tokens),
      cacheReadTokens: tokenCount(details.cached_tokens),
    };
  } else {
    const u = (r.usageMetadata ?? {}) as Record<string, unknown>;
    counts = {
      inputTokens: tokenCount(u.promptTokenCount),
      outputTokens: tokenCount(u.candidatesTokenCount),
      cacheReadTokens: tokenCount(u.cachedContentTokenCount),
    };
  }
  return Object.fromEntries(Object.entries(counts).filter(([, v]) => v !== undefined)) as UsageCounts;
}

function newToolCallId(prefix: string): string {
  return `${prefix}_${Math.random().toString(36).slice(2, 10)}${Date.now().toString(36)}`;
}

interface OpenAiToolCall {
  id?: string;
  function?: { name?: string; arguments?: string };
}
interface OpenAiResponse {
  choices?: Array<{ finish_reason?: string; message?: { content?: string | null; tool_calls?: OpenAiToolCall[] } }>;
}

function parseOpenAiResponse(raw: OpenAiResponse): { turn: AssistantTurn; nativeAssistantMessage: unknown } {
  const choice = raw?.choices?.[0];
  const message = choice?.message;
  if (!message) throw new ProviderProtocolError("OpenAI response missing choices[0].message.");
  if (choice?.finish_reason === "length") {
    throw new ProviderProtocolError("OpenAI's response was cut off (hit the token limit) before it finished. Try a narrower request.");
  }

  const toolCalls = Array.isArray(message.tool_calls) ? message.tool_calls : [];
  if (toolCalls.length > 0) {
    const calls: ToolCallRequest[] = toolCalls.map((tc) => {
      let args: Record<string, unknown> = {};
      try {
        args = JSON.parse(tc.function?.arguments ?? "{}");
      } catch {
        throw new ProviderProtocolError(`OpenAI returned malformed tool-call arguments for "${tc.function?.name}".`);
      }
      return { toolCallId: String(tc.id ?? newToolCallId("call")), name: String(tc.function?.name ?? ""), args };
    });
    const text = typeof message.content === "string" ? joinedText([message.content]) : undefined;
    return { turn: { kind: "tool_calls", calls, text }, nativeAssistantMessage: message };
  }

  const text = typeof message.content === "string" ? message.content : "";
  if (!text.trim()) throw new ProviderProtocolError("OpenAI returned neither a tool call nor text content.");
  return { turn: { kind: "answer", text: text.trim() }, nativeAssistantMessage: message };
}

function appendOpenAiToolResults(conversation: ConversationState, nativeAssistantMessage: unknown, results: ToolResultForModel[]): ConversationState {
  const toolMessages = results.map((r) => ({
    role: "tool",
    tool_call_id: r.toolCallId,
    content: JSON.stringify(toolResultPayload(r)),
  }));
  return { ...conversation, nativeMessages: [...conversation.nativeMessages, nativeAssistantMessage, ...toolMessages] };
}

interface ClaudeContentBlock {
  type?: string;
  text?: string;
  id?: string;
  name?: string;
  input?: Record<string, unknown>;
}
interface ClaudeResponse {
  content?: ClaudeContentBlock[];
  stop_reason?: string;
}

function parseClaudeResponse(raw: ClaudeResponse): { turn: AssistantTurn; nativeAssistantContent: unknown } {
  const content = raw?.content;
  if (!Array.isArray(content)) throw new ProviderProtocolError("Claude response missing content array.");
  if (raw?.stop_reason === "max_tokens") {
    throw new ProviderProtocolError("Claude's response was cut off (hit the token limit) before it finished. Try a narrower request.");
  }

  const toolUses = content.filter((b) => b.type === "tool_use");
  if (toolUses.length > 0) {
    const calls: ToolCallRequest[] = toolUses.map((b) => ({
      toolCallId: String(b.id ?? newToolCallId("toolu")),
      name: String(b.name ?? ""),
      args: b.input ?? {},
    }));
    const text = joinedText(content.filter((b) => b.type === "text").map((b) => b.text));
    return { turn: { kind: "tool_calls", calls, text }, nativeAssistantContent: content };
  }

  const textBlock = content.find((b) => b.type === "text");
  const text = typeof textBlock?.text === "string" ? textBlock.text : "";
  if (!text.trim()) throw new ProviderProtocolError("Claude returned neither a tool_use block nor text content.");
  return { turn: { kind: "answer", text: text.trim() }, nativeAssistantContent: content };
}

function appendClaudeToolResults(conversation: ConversationState, nativeAssistantContent: unknown, results: ToolResultForModel[]): ConversationState {
  const toolResultContent = results.map((r) => ({
    type: "tool_result",
    tool_use_id: r.toolCallId,
    content: JSON.stringify(toolResultPayload(r)),
    is_error: r.isError,
  }));
  return {
    ...conversation,
    nativeMessages: [
      ...conversation.nativeMessages,
      { role: "assistant", content: nativeAssistantContent },
      { role: "user", content: toolResultContent },
    ],
  };
}

interface GeminiPart {
  text?: string;
  functionCall?: { name?: string; args?: Record<string, unknown> };
}
interface GeminiResponse {
  candidates?: Array<{ content?: { parts?: GeminiPart[] }; finishReason?: string }>;
}

function parseGeminiResponse(raw: GeminiResponse): { turn: AssistantTurn; nativeAssistantParts: unknown } {
  const candidate = raw?.candidates?.[0];
  const parts = candidate?.content?.parts;
  if (!Array.isArray(parts)) throw new ProviderProtocolError("Gemini response missing candidates[0].content.parts.");
  if (candidate?.finishReason === "MAX_TOKENS") {
    throw new ProviderProtocolError("Gemini's response was cut off (hit the token limit) before it finished. Try a narrower request.");
  }

  const functionCalls = parts.filter((p) => p.functionCall);
  if (functionCalls.length > 0) {
    const calls: ToolCallRequest[] = functionCalls.map((p) => ({
      toolCallId: newToolCallId("gfn"),
      name: String(p.functionCall?.name ?? ""),
      args: p.functionCall?.args ?? {},
    }));
    const text = joinedText(parts.filter((p) => !p.functionCall).map((p) => p.text));
    return { turn: { kind: "tool_calls", calls, text }, nativeAssistantParts: parts };
  }

  const text = parts.map((p) => p.text ?? "").join("").trim();
  if (!text) throw new ProviderProtocolError("Gemini returned neither a functionCall nor text content.");
  return { turn: { kind: "answer", text }, nativeAssistantParts: parts };
}

function appendGeminiToolResults(conversation: ConversationState, nativeAssistantParts: unknown, results: ToolResultForModel[]): ConversationState {
  const functionResponseParts = results.map((r) => ({
    functionResponse: { name: r.name, response: geminiToolResponse(r) },
  }));
  return {
    ...conversation,
    nativeMessages: [
      ...conversation.nativeMessages,
      { role: "model", parts: nativeAssistantParts },
      { role: "user", parts: functionResponseParts },
    ],
  };
}

export function parseTurn(conversation: ConversationState, raw: unknown, usage: ProviderUsage): NextTurn {
  if (conversation.provider === "openai") {
    const { turn, nativeAssistantMessage } = parseOpenAiResponse(raw as OpenAiResponse);
    return { turn, usage, advance: (results) => appendOpenAiToolResults(conversation, nativeAssistantMessage, results) };
  }
  if (conversation.provider === "claude") {
    const { turn, nativeAssistantContent } = parseClaudeResponse(raw as ClaudeResponse);
    return { turn, usage, advance: (results) => appendClaudeToolResults(conversation, nativeAssistantContent, results) };
  }
  const { turn, nativeAssistantParts } = parseGeminiResponse(raw as GeminiResponse);
  return { turn, usage, advance: (results) => appendGeminiToolResults(conversation, nativeAssistantParts, results) };
}

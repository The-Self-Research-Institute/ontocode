import { describe, expect, it } from "vitest";
import { createStreamAssembler, readSseEvents, streamingUrl, withStreamFlags } from "../services/codeAssistantStream";
import { parseProviderUsage } from "../services/codeAssistantProviderResponse";

function sseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    },
  });
  return new Response(body, { headers: { "content-type": "text/event-stream" } });
}

function feed(provider: "claude" | "openai" | "gemini", events: unknown[]) {
  const assembler = createStreamAssembler(provider);
  const deltas: string[] = [];
  for (const event of events) {
    const delta = assembler.accept("message", typeof event === "string" ? event : JSON.stringify(event));
    if (delta) deltas.push(delta);
  }
  return { deltas, result: assembler.result() };
}

describe("readSseEvents", () => {
  it("joins events split across chunks and keeps event names", async () => {
    const seen: Array<[string, string]> = [];
    await readSseEvents(sseResponse(["event: a\ndata: {\"x\"", ":1}\n\n", "data: two\n\n"]), (event, data) => seen.push([event, data]));

    expect(seen).toEqual([["a", "{\"x\":1}"], ["message", "two"]]);
  });
});

describe("stream assemblers", () => {
  it("rebuilds a Claude reply with text, a tool call and usage", () => {
    const { deltas, result } = feed("claude", [
      { type: "message_start", message: { usage: { input_tokens: 12 } } },
      { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } },
      { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "Dog is " } },
      { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "a class." } },
      { type: "content_block_start", index: 1, content_block: { type: "tool_use", id: "tu1", name: "read_context", input: {} } },
      { type: "content_block_delta", index: 1, delta: { type: "input_json_delta", partial_json: "{\"target\":" } },
      { type: "content_block_delta", index: 1, delta: { type: "input_json_delta", partial_json: "\"turtle:0-10\"}" } },
      { type: "content_block_stop", index: 1 },
      { type: "message_delta", delta: { stop_reason: "tool_use" }, usage: { output_tokens: 7 } },
    ]);

    expect(deltas.join("")).toBe("Dog is a class.");
    expect(result.content[0].text).toBe("Dog is a class.");
    expect(result.content[1].input).toEqual({ target: "turtle:0-10" });
    expect(result.stop_reason).toBe("tool_use");
    expect(parseProviderUsage("claude", result)).toMatchObject({ inputTokens: 12, outputTokens: 7 });
  });

  it("rebuilds an OpenAI reply with text, tool call arguments and usage", () => {
    const { deltas, result } = feed("openai", [
      { choices: [{ index: 0, delta: { role: "assistant", content: "Hi" } }] },
      { choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: "c1", function: { name: "run_sparql", arguments: "{\"q\"" } }] } }] },
      { choices: [{ index: 0, delta: { tool_calls: [{ index: 0, function: { arguments: ":1}" } }] }, finish_reason: "tool_calls" }] },
      { choices: [], usage: { prompt_tokens: 5, completion_tokens: 2, total_tokens: 7 } },
      "[DONE]",
    ]);

    expect(deltas).toEqual(["Hi"]);
    expect(result.choices[0].message.tool_calls[0]).toEqual({ id: "c1", type: "function", function: { name: "run_sparql", arguments: "{\"q\":1}" } });
    expect(result.choices[0].finish_reason).toBe("tool_calls");
    expect(result.usage.total_tokens).toBe(7);
  });

  it("rebuilds a Gemini reply, merging text parts and keeping thought signatures", () => {
    const { deltas, result } = feed("gemini", [
      { candidates: [{ content: { role: "model", parts: [{ text: "Part " }] } }] },
      { candidates: [{ content: { role: "model", parts: [{ text: "one." }] } }] },
      { candidates: [{ content: { role: "model", parts: [{ functionCall: { name: "read_context", args: {} }, thoughtSignature: "ts" }] }, finishReason: "STOP" }], usageMetadata: { totalTokenCount: 9 } },
    ]);

    expect(deltas.join("")).toBe("Part one.");
    expect(result.candidates[0].content.parts).toEqual([
      { text: "Part one." },
      { functionCall: { name: "read_context", args: {} }, thoughtSignature: "ts" },
    ]);
    expect(result.usageMetadata.totalTokenCount).toBe(9);
  });

  it("turns a provider error event into an error", () => {
    expect(() => feed("claude", [{ type: "error", error: { message: "overloaded" } }])).toThrow("overloaded");
  });
});

describe("stream request flags", () => {
  it("asks each provider to stream", () => {
    expect(withStreamFlags("claude", { a: 1 })).toEqual({ a: 1, stream: true });
    expect(withStreamFlags("openai", {})).toEqual({ stream: true, stream_options: { include_usage: true } });
    expect(streamingUrl("gemini", "https://x/models/m:generateContent")).toBe("https://x/models/m:streamGenerateContent?alt=sse");
    expect(streamingUrl("claude", "https://api.anthropic.com/v1/messages")).toBe("https://api.anthropic.com/v1/messages");
  });
});

import { beforeEach, describe, expect, it, vi } from "vitest";
import { requestNextTurn, startAssistantConversation, type ToolDefinition } from "../services/codeAssistantProviders";
import { AssistantApiError } from "../services/codeAssistantSession";
import * as llmInsights from "../services/LlmInsightsService";

const TOOL: ToolDefinition = { name: "read_context", description: "t", parameters: { type: "object", properties: {} } };

function sse(events: Array<{ event?: string; data: unknown }>): Response {
  const text = events
    .map((e) => `${e.event ? `event: ${e.event}\n` : ""}data: ${typeof e.data === "string" ? e.data : JSON.stringify(e.data)}\n\n`)
    .join("");
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(text));
      controller.close();
    },
  });
  return new Response(body, { status: 200, headers: { "content-type": "text/event-stream" } });
}

beforeEach(() => {
  vi.restoreAllMocks();
  vi.spyOn(llmInsights, "getStoredApiKey").mockReturnValue("test-key");
  vi.spyOn(llmInsights, "getStoredModel").mockReturnValue("test-model");
});

describe("requestNextTurn streaming", () => {
  it("streams a Claude answer, reporting text as it arrives", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("claude");
    const fetchSpy = vi.fn().mockResolvedValue(sse([
      { data: { type: "message_start", message: { usage: { input_tokens: 3 } } } },
      { data: { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } } },
      { data: { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "Hello " } } },
      { data: { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "there" } } },
      { data: { type: "message_delta", delta: { stop_reason: "end_turn" }, usage: { output_tokens: 2 } } },
    ]));
    vi.stubGlobal("fetch", fetchSpy);
    const deltas: string[] = [];

    const conversation = await startAssistantConversation("system", "hi");
    const { turn } = await requestNextTurn(conversation, [TOOL], undefined, undefined, undefined, (d) => deltas.push(d));

    expect(turn).toEqual({ kind: "answer", text: "Hello there" });
    expect(deltas).toEqual(["Hello ", "there"]);
    expect(JSON.parse(fetchSpy.mock.calls[0][1].body).stream).toBe(true);
  });

  it("uses Gemini's streaming endpoint", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("gemini");
    const fetchSpy = vi.fn().mockResolvedValue(sse([{ data: { candidates: [{ content: { parts: [{ text: "ok" }] }, finishReason: "STOP" }] } }]));
    vi.stubGlobal("fetch", fetchSpy);

    const conversation = await startAssistantConversation("system", "hi");
    const { turn } = await requestNextTurn(conversation, [TOOL], undefined, undefined, undefined, () => {});

    expect(turn).toEqual({ kind: "answer", text: "ok" });
    expect(fetchSpy.mock.calls[0][0]).toContain(":streamGenerateContent?alt=sse");
  });

  it("falls back to a plain JSON reply when the server does not stream", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ choices: [{ message: { content: "plain" } }] }), { headers: { "content-type": "application/json" } }),
    ));

    const conversation = await startAssistantConversation("system", "hi");
    const { turn } = await requestNextTurn(conversation, [TOOL], undefined, undefined, undefined, () => {});

    expect(turn).toEqual({ kind: "answer", text: "plain" });
  });

  it("uses a whole reply the managed proxy sends when the provider did not stream", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(sse([
      { event: "complete", data: { type: "message", content: [{ type: "text", text: "whole reply" }], stop_reason: "end_turn" } },
    ])));
    const managed = { apiBaseUrl: "http://api", token: "t", sessionId: "s1", model: "claude-x" };

    const conversation = await startAssistantConversation("system", "hi", [], "claude");
    const { turn } = await requestNextTurn(conversation, [TOOL], undefined, undefined, managed, () => {});

    expect(turn).toEqual({ kind: "answer", text: "whole reply" });
  });

  it("turns a managed proxy error event into the same error a normal call raises", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(sse([
      { event: "proxy_error", data: { status: 429, errorCode: "RATE_LIMITED", message: "Too many provider calls" } },
    ]));
    vi.stubGlobal("fetch", fetchSpy);
    const managed = { apiBaseUrl: "http://api", token: "t", sessionId: "s1", model: "claude-x" };

    const conversation = await startAssistantConversation("system", "hi", [], "claude");
    const failure = requestNextTurn(conversation, [TOOL], undefined, undefined, managed, () => {});

    await expect(failure).rejects.toBeInstanceOf(AssistantApiError);
    await expect(failure).rejects.toMatchObject({ errorCode: "RATE_LIMITED" });
    expect(fetchSpy.mock.calls[0][0]).toBe("http://api/api/v1/code-assistant/sessions/s1/provider-call/stream");
  });
});

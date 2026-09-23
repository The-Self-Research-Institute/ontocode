import { describe, it, expect, vi, beforeEach } from "vitest";
import { startAssistantConversation, requestNextTurn, ProviderProtocolError, type ToolDefinition } from "../services/codeAssistantProviders";
import * as llmInsights from "../services/LlmInsightsService";

const TOOL: ToolDefinition = {
  name: "read_context",
  description: "test tool",
  parameters: { type: "object", properties: {} },
};

function mockFetchOnce(status: number, body: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      json: async () => body,
    }),
  );
}

beforeEach(() => {
  vi.restoreAllMocks();
  vi.spyOn(llmInsights, "getStoredApiKey").mockReturnValue("test-key");
  vi.spyOn(llmInsights, "getStoredModel").mockReturnValue("test-model");
});

describe("requestNextTurn — OpenAI", () => {
  it("parses a text answer", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    mockFetchOnce(200, { choices: [{ message: { content: "hello there" } }] });

    const conversation = await startAssistantConversation("system prompt", "hi");
    const { turn } = await requestNextTurn(conversation, [TOOL]);

    expect(turn).toEqual({ kind: "answer", text: "hello there" });
  });

  it("parses a tool call and its JSON arguments", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    mockFetchOnce(200, {
      choices: [
        {
          message: {
            tool_calls: [{ id: "call_1", function: { name: "read_context", arguments: '{"kind":"definitions"}' } }],
          },
        },
      ],
    });

    const conversation = await startAssistantConversation("system prompt", "hi");
    const { turn } = await requestNextTurn(conversation, [TOOL]);

    expect(turn.kind).toBe("tool_calls");
    if (turn.kind === "tool_calls") {
      expect(turn.calls).toEqual([{ toolCallId: "call_1", name: "read_context", args: { kind: "definitions" } }]);
    }
  });

  it("throws a protocol error on malformed tool-call arguments instead of crashing silently", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    mockFetchOnce(200, {
      choices: [{ message: { tool_calls: [{ id: "call_1", function: { name: "x", arguments: "{not json" } }] } }],
    });

    const conversation = await startAssistantConversation("system prompt", "hi");
    await expect(requestNextTurn(conversation, [TOOL])).rejects.toBeInstanceOf(ProviderProtocolError);
  });

  it("fails closed when the response was truncated by the token limit", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    mockFetchOnce(200, { choices: [{ finish_reason: "length", message: { content: "cut off ha" } }] });

    const conversation = await startAssistantConversation("system prompt", "hi");
    await expect(requestNextTurn(conversation, [TOOL])).rejects.toThrow(/cut off/);
  });

  it("never adds Claude-only cache_control fields to the OpenAI request body", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ choices: [{ message: { content: "ok" } }] }) });
    vi.stubGlobal("fetch", fetchMock);

    const conversation = await startAssistantConversation("system prompt", "hi");
    await requestNextTurn(conversation, [TOOL]);

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(JSON.stringify(body)).not.toContain("cache_control");
    expect(fetchMock.mock.calls[0][1].headers["anthropic-beta"]).toBeUndefined();
  });
});

describe("requestNextTurn — Claude", () => {
  it("parses a tool_use block", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("claude");
    mockFetchOnce(200, {
      content: [{ type: "tool_use", id: "toolu_1", name: "read_context", input: { kind: "definitions" } }],
    });

    const conversation = await startAssistantConversation("system prompt", "hi");
    const { turn } = await requestNextTurn(conversation, [TOOL]);

    expect(turn.kind).toBe("tool_calls");
    if (turn.kind === "tool_calls") {
      expect(turn.calls[0]).toEqual({ toolCallId: "toolu_1", name: "read_context", args: { kind: "definitions" } });
    }
  });

  it("fails closed on max_tokens stop_reason", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("claude");
    mockFetchOnce(200, { content: [{ type: "text", text: "partial" }], stop_reason: "max_tokens" });

    const conversation = await startAssistantConversation("system prompt", "hi");
    await expect(requestNextTurn(conversation, [TOOL])).rejects.toThrow(/cut off/);
  });

  it("marks the system prompt and the last tool definition as cacheable", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("claude");
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ content: [{ type: "text", text: "ok" }] }) });
    vi.stubGlobal("fetch", fetchMock);

    const conversation = await startAssistantConversation("system prompt", "hi");
    await requestNextTurn(conversation, [TOOL]);

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers["anthropic-beta"]).toBe("prompt-caching-2024-07-31");
    const body = JSON.parse(init.body);
    expect(body.system).toEqual([{ type: "text", text: "system prompt", cache_control: { type: "ephemeral" } }]);
    expect(body.tools.at(-1).cache_control).toEqual({ type: "ephemeral" });
  });

  it("marks the last message as the conversation's cache breakpoint, whether it's plain text or a tool_result block", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("claude");
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ content: [{ type: "text", text: "ok" }] }) });
    vi.stubGlobal("fetch", fetchMock);

    const conversation = await startAssistantConversation("system prompt", "hi");
    await requestNextTurn(conversation, [TOOL]);
    const firstBody = JSON.parse(fetchMock.mock.calls[0][1].body);
    const lastPlainMessage = firstBody.messages.at(-1);
    expect(lastPlainMessage.content).toEqual([{ type: "text", text: "hi", cache_control: { type: "ephemeral" } }]);

    mockFetchOnce(200, { content: [{ type: "tool_use", id: "toolu_1", name: "read_context", input: {} }] });
    const { advance } = await requestNextTurn(conversation, [TOOL]);
    const afterToolCall = advance([{ toolCallId: "toolu_1", name: "read_context", result: { ok: true }, isError: false }]);

    const secondFetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ content: [{ type: "text", text: "done" }] }) });
    vi.stubGlobal("fetch", secondFetchMock);
    await requestNextTurn(afterToolCall, [TOOL]);
    const secondBody = JSON.parse(secondFetchMock.mock.calls[0][1].body);
    const lastToolResultMessage = secondBody.messages.at(-1);
    expect(lastToolResultMessage.content.at(-1).cache_control).toEqual({ type: "ephemeral" });
    expect(lastToolResultMessage.content.at(-1).type).toBe("tool_result");
  });
});

describe("requestNextTurn — Gemini", () => {
  it("parses a functionCall part", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("gemini");
    mockFetchOnce(200, {
      candidates: [{ content: { parts: [{ functionCall: { name: "read_context", args: { kind: "definitions" } } }] } }],
    });

    const conversation = await startAssistantConversation("system prompt", "hi");
    const { turn } = await requestNextTurn(conversation, [TOOL]);

    expect(turn.kind).toBe("tool_calls");
    if (turn.kind === "tool_calls") {
      expect(turn.calls[0].name).toBe("read_context");
    }
  });

  it("never adds Claude-only cache_control fields to the Gemini request body", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("gemini");
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ candidates: [{ content: { parts: [{ text: "ok" }] } }] }) });
    vi.stubGlobal("fetch", fetchMock);

    const conversation = await startAssistantConversation("system prompt", "hi");
    await requestNextTurn(conversation, [TOOL]);

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(JSON.stringify(body)).not.toContain("cache_control");
  });
});

describe("requestNextTurn — HTTP error mapping", () => {
  it("maps 401 to an unauthorized message", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    mockFetchOnce(401, {});

    const conversation = await startAssistantConversation("system prompt", "hi");
    await expect(requestNextTurn(conversation, [TOOL])).rejects.toThrow(/[Uu]nauthorized/);
  });

  it("maps a bare 429 to a generic rate-limit message without retrying", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 429, json: async () => ({}) });
    vi.stubGlobal("fetch", fetchMock);

    const conversation = await startAssistantConversation("system prompt", "hi");
    await expect(requestNextTurn(conversation, [TOOL])).rejects.toThrow(/Rate limit reached\. Try again shortly\./);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("surfaces the provider's own quota message on a 429 instead of retrying blind", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 429,
      json: async () => ({ error: { message: "You exceeded your current daily request quota." } }),
    });
    vi.stubGlobal("fetch", fetchMock);

    const conversation = await startAssistantConversation("system prompt", "hi");
    await expect(requestNextTurn(conversation, [TOOL])).rejects.toThrow(/daily request quota/);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("condenses a long Gemini quota dump into one short, actionable line", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("gemini");
    const rawQuotaMessage =
      "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. " +
      "* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 0, model: gemini-3.1-pro " +
      "* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 0, model: gemini-3.1-pro " +
      "Please retry in 29.568204s.";
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 429,
      json: async () => ({ error: { message: rawQuotaMessage } }),
    });
    vi.stubGlobal("fetch", fetchMock);

    const conversation = await startAssistantConversation("system prompt", "hi");
    await expect(requestNextTurn(conversation, [TOOL])).rejects.toThrow(
      "Rate limit reached: You exceeded your current quota, please check your plan and billing details for gemini-3.1-pro. Try again in about 30s.",
    );
  });

  it("retries a 503 and succeeds once the provider recovers", async () => {
    vi.useFakeTimers();
    try {
      vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce({ ok: false, status: 503, json: async () => ({}) })
        .mockResolvedValueOnce({
          ok: true,
          status: 200,
          json: async () => ({ choices: [{ message: { content: "recovered" } }] }),
        });
      vi.stubGlobal("fetch", fetchMock);

      const conversation = await startAssistantConversation("system prompt", "hi");
      const resultPromise = requestNextTurn(conversation, [TOOL]);
      await vi.runAllTimersAsync();
      const { turn } = await resultPromise;

      expect(turn).toEqual({ kind: "answer", text: "recovered" });
      expect(fetchMock).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });
});

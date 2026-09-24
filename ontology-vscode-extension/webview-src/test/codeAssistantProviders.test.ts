import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  startAssistantConversation,
  requestNextTurn,
  parseProviderUsage,
  ProviderProtocolError,
  type ToolDefinition,
} from "../services/codeAssistantProviders";
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

describe("requestNextTurn — whole-request size guard", () => {
  it("fails closed before ever calling fetch when the built request is too large to send", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const hugeHistory = Array.from({ length: 20 }, (_, i) => ({
      role: i % 2 === 0 ? ("user" as const) : ("assistant" as const),
      text: "x".repeat(100_000),
    }));
    const conversation = await startAssistantConversation("system prompt", "hi", hugeHistory);

    await expect(requestNextTurn(conversation, [TOOL])).rejects.toThrow(ProviderProtocolError);
    await expect(requestNextTurn(conversation, [TOOL])).rejects.toThrow(/too large/);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects a request that is under the character guard but over an unknown model's 128k window", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    vi.spyOn(llmInsights, "getStoredMaxResponseTokens").mockReturnValue(8192);
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const conversation = await startAssistantConversation("system prompt", "y".repeat(460_000));

    await expect(requestNextTurn(conversation, [TOOL])).rejects.toThrow(/128000-token context/);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("sends the same request through for a model with a larger window", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    vi.spyOn(llmInsights, "getStoredModel").mockReturnValue("gpt-4.1-mini");
    vi.spyOn(llmInsights, "getStoredMaxResponseTokens").mockReturnValue(8192);
    mockFetchOnce(200, { choices: [{ message: { content: "fits" } }] });

    const conversation = await startAssistantConversation("system prompt", "y".repeat(460_000));
    const { turn } = await requestNextTurn(conversation, [TOOL]);

    expect(turn).toEqual({ kind: "answer", text: "fits" });
  });

  it("counts the response-token reserve against the window", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("claude");
    vi.spyOn(llmInsights, "getStoredModel").mockReturnValue("claude-sonnet-4-5");
    const conversation = await startAssistantConversation("system prompt", "z".repeat(600_000));

    vi.spyOn(llmInsights, "getStoredMaxResponseTokens").mockReturnValue(8192);
    mockFetchOnce(200, { content: [{ type: "text", text: "ok" }] });
    await expect(requestNextTurn(conversation, [TOOL])).resolves.toMatchObject({ turn: { kind: "answer", text: "ok" } });

    vi.spyOn(llmInsights, "getStoredMaxResponseTokens").mockReturnValue(40_000);
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    await expect(requestNextTurn(conversation, [TOOL])).rejects.toThrow(/reserving 40000/);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("sends a normal-sized conversation through without tripping the guard", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    mockFetchOnce(200, { choices: [{ message: { content: "fine" } }] });

    const conversation = await startAssistantConversation("system prompt", "hi");
    const { turn } = await requestNextTurn(conversation, [TOOL]);

    expect(turn).toEqual({ kind: "answer", text: "fine" });
  });
});

describe("requestNextTurn — usage", () => {
  it("parses Claude usage including cache reads and writes", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("claude");
    mockFetchOnce(200, {
      content: [{ type: "text", text: "ok" }],
      usage: { input_tokens: 120, output_tokens: 30, cache_read_input_tokens: 900, cache_creation_input_tokens: 45 },
    });

    const { usage } = await requestNextTurn(await startAssistantConversation("s", "hi"), [TOOL]);

    expect(usage).toMatchObject({
      provider: "claude",
      model: "test-model",
      inputTokens: 120,
      outputTokens: 30,
      cacheReadTokens: 900,
      cacheWriteTokens: 45,
    });
    expect(usage!.latencyMs).toBeGreaterThanOrEqual(0);
  });

  it("parses OpenAI usage with cached prompt tokens", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    mockFetchOnce(200, {
      choices: [{ message: { content: "ok" } }],
      usage: { prompt_tokens: 500, completion_tokens: 20, prompt_tokens_details: { cached_tokens: 256 } },
    });

    const { usage } = await requestNextTurn(await startAssistantConversation("s", "hi"), [TOOL]);

    expect(usage).toMatchObject({ provider: "openai", inputTokens: 500, outputTokens: 20, cacheReadTokens: 256 });
    expect(usage).not.toHaveProperty("cacheWriteTokens");
  });

  it("parses Gemini usageMetadata", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("gemini");
    mockFetchOnce(200, {
      candidates: [{ content: { parts: [{ text: "ok" }] } }],
      usageMetadata: { promptTokenCount: 77, candidatesTokenCount: 8, cachedContentTokenCount: 40 },
    });

    const { usage } = await requestNextTurn(await startAssistantConversation("s", "hi"), [TOOL]);

    expect(usage).toMatchObject({ provider: "gemini", inputTokens: 77, outputTokens: 8, cacheReadTokens: 40 });
  });

  it("still reports latency when the provider sends no usage block", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    mockFetchOnce(200, { choices: [{ message: { content: "ok" } }] });

    const { usage } = await requestNextTurn(await startAssistantConversation("s", "hi"), [TOOL]);

    expect(Object.keys(usage!).sort()).toEqual(["latencyMs", "model", "provider"]);
  });

  it("ignores junk values in the usage block", () => {
    expect(parseProviderUsage("claude", { usage: { input_tokens: "12", output_tokens: -1, cache_read_input_tokens: 5 } })).toEqual({
      cacheReadTokens: 5,
    });
    expect(parseProviderUsage("gemini", null)).toEqual({});
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

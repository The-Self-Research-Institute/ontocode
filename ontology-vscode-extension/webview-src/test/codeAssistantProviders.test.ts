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
});

describe("requestNextTurn — HTTP error mapping", () => {
  it("maps 401 to an unauthorized message", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    mockFetchOnce(401, {});

    const conversation = await startAssistantConversation("system prompt", "hi");
    await expect(requestNextTurn(conversation, [TOOL])).rejects.toThrow(/[Uu]nauthorized/);
  });

  it("maps 429 to a rate-limit message", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    mockFetchOnce(429, {});

    const conversation = await startAssistantConversation("system prompt", "hi");
    await expect(requestNextTurn(conversation, [TOOL])).rejects.toThrow(/Rate limit/);
  });
});

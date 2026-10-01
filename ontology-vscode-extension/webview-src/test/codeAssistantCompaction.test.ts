import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  compactHistory,
  compactOlderToolResults,
  COMPACTED_TOOL_RESULT_NOTE,
  EARLIER_CONVERSATION_ACK,
  EARLIER_CONVERSATION_HEADER,
} from "../services/codeAssistantCompaction";
import {
  startAssistantConversation,
  requestNextTurn,
  ProviderProtocolError,
  type ConversationState,
  type HistoryTurn,
  type ToolDefinition,
  type ToolResultProvenance,
} from "../services/codeAssistantProviders";
import * as llmInsights from "../services/LlmInsightsService";
import type { LlmProvider } from "../services/LlmInsightsService";

const TOOL: ToolDefinition = { name: "read_context", description: "t", parameters: { type: "object", properties: {} } };

beforeEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.spyOn(llmInsights, "getStoredApiKey").mockReturnValue("k");
  vi.spyOn(llmInsights, "getStoredModel").mockReturnValue("test-model");
  vi.spyOn(llmInsights, "getStoredMaxResponseTokens").mockReturnValue(8192);
});

function turns(count: number, text: (i: number) => string = (i) => `turn ${i}`): HistoryTurn[] {
  return Array.from({ length: count }, (_, i) => ({ role: i % 2 === 0 ? ("user" as const) : ("assistant" as const), text: text(i) }));
}

describe("compactHistory", () => {
  it("leaves six or fewer turns untouched", () => {
    const history = turns(6);
    expect(compactHistory(history)).toBe(history);
  });

  it("keeps the last six turns verbatim and folds older ones into one marked note", () => {
    const history = turns(10, (i) => `turn ${i} ` + "w".repeat(i === 1 ? 1000 : 10));
    const compacted = compactHistory(history);

    expect(compacted.slice(-6)).toEqual(history.slice(-6));
    const note = compacted[0];
    expect(note.role).toBe("user");
    expect(note.text.startsWith(EARLIER_CONVERSATION_HEADER)).toBe(true);
    expect(note.text).toMatch(/may be outdated/);
    for (let i = 0; i < 4; i++) expect(note.text).toContain(`turn ${i}`);
    expect(note.text).not.toContain("turn 4");
    const longLine = note.text.split("\n").find((l) => l.includes("turn 1"))!;
    expect(longLine.length).toBeLessThan(230);
    expect(longLine.endsWith("...")).toBe(true);
  });

  it("adds a short assistant bridge only when needed to keep roles alternating", () => {
    const startsWithUser = compactHistory(turns(10));
    expect(startsWithUser[1]).toEqual({ role: "assistant", text: EARLIER_CONVERSATION_ACK });
    expect(startsWithUser).toHaveLength(8);

    const startsWithAssistant = compactHistory(turns(9));
    expect(startsWithAssistant[1]).toEqual(turns(9)[3]);
    expect(startsWithAssistant).toHaveLength(7);
  });

  it("is applied when a conversation starts", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("claude");
    const conversation = await startAssistantConversation("sys", "now", turns(12));
    expect(conversation.nativeMessages).toHaveLength(1 + 1 + 6 + 1);
    expect(JSON.stringify(conversation.nativeMessages[0])).toContain("Earlier conversation");
  });
});

function toolCallResponse(provider: LlmProvider, id: string) {
  if (provider === "openai") {
    return { choices: [{ message: { content: null, tool_calls: [{ id, type: "function", function: { name: "read_context", arguments: "{}" } }] } }] };
  }
  if (provider === "claude") return { content: [{ type: "tool_use", id, name: "read_context", input: {} }] };
  return { candidates: [{ content: { parts: [{ functionCall: { name: "read_context", args: {} } }] } }] };
}

function answerResponse(provider: LlmProvider) {
  if (provider === "openai") return { choices: [{ message: { content: "done" } }] };
  if (provider === "claude") return { content: [{ type: "text", text: "done" }] };
  return { candidates: [{ content: { parts: [{ text: "done" }] } }] };
}

function provenance(step: number): ToolResultProvenance {
  return { tool: "read_context", format: "turtle", range: `${step * 10}-10`, revision: 7, reason: `definitions step ${step}`, step };
}

function respondWith(body: unknown) {
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

async function conversationWithRounds(provider: LlmProvider, rounds: number, charsPerRound: number): Promise<{ conversation: ConversationState; ids: string[] }> {
  let conversation = await startAssistantConversation("system prompt", "hi");
  const ids: string[] = [];
  for (let step = 1; step <= rounds; step++) {
    const id = `call_${step}`;
    respondWith(toolCallResponse(provider, id));
    const { turn, advance } = await requestNextTurn(conversation, [TOOL]);
    if (turn.kind !== "tool_calls") throw new Error("expected a tool call");
    const toolCallId = turn.calls[0].toolCallId;
    ids.push(toolCallId);
    conversation = advance([
      {
        toolCallId,
        name: "read_context",
        result: { items: [{ text: `round ${step} ` + "x".repeat(charsPerRound) }] },
        isError: false,
        provenance: provenance(step),
      },
    ]);
  }
  return { conversation, ids };
}

interface ExtractedToolResult {
  id?: string;
  name?: string;
  payload: Record<string, unknown>;
}

function toolResultsFrom(provider: LlmProvider, body: Record<string, unknown>): ExtractedToolResult[] {
  if (provider === "openai") {
    return (body.messages as Array<Record<string, unknown>>)
      .filter((m) => m.role === "tool")
      .map((m) => ({ id: String(m.tool_call_id), payload: JSON.parse(String(m.content)) }));
  }
  if (provider === "claude") {
    return (body.messages as Array<Record<string, unknown>>)
      .filter((m) => Array.isArray(m.content) && (m.content as Array<Record<string, unknown>>).every((b) => b.type === "tool_result"))
      .flatMap((m) => (m.content as Array<Record<string, unknown>>).map((b) => ({ id: String(b.tool_use_id), payload: JSON.parse(String(b.content)) })));
  }
  return (body.contents as Array<Record<string, unknown>>)
    .filter((m) => Array.isArray(m.parts) && (m.parts as Array<Record<string, unknown>>).every((p) => p.functionResponse))
    .flatMap((m) =>
      (m.parts as Array<{ functionResponse: { name: string; response: Record<string, unknown> } }>).map((p) => ({
        name: p.functionResponse.name,
        payload: p.functionResponse.response,
      })),
    );
}

function toolCallIdsFrom(provider: LlmProvider, body: Record<string, unknown>): string[] {
  if (provider === "openai") {
    return (body.messages as Array<Record<string, unknown>>)
      .flatMap((m) => (Array.isArray(m.tool_calls) ? (m.tool_calls as Array<{ id: string }>).map((c) => c.id) : []));
  }
  if (provider === "claude") {
    return (body.messages as Array<Record<string, unknown>>)
      .filter((m) => m.role === "assistant" && Array.isArray(m.content))
      .flatMap((m) => (m.content as Array<Record<string, unknown>>).filter((b) => b.type === "tool_use").map((b) => String(b.id)));
  }
  return [];
}

function messageCount(provider: LlmProvider, body: Record<string, unknown>): number {
  return ((provider === "gemini" ? body.contents : body.messages) as unknown[]).length;
}

const SIZES: Record<LlmProvider, number> = { openai: 120_000, claude: 200_000, gemini: 120_000 };

describe.each(["openai", "claude", "gemini"] as const)("tool-result compaction — %s", (provider) => {
  beforeEach(() => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue(provider);
  });

  it("wraps every tool result with its provenance", async () => {
    const { conversation } = await conversationWithRounds(provider, 1, 10);
    const fetchMock = respondWith(answerResponse(provider));
    await requestNextTurn(conversation, [TOOL]);

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    const [only] = toolResultsFrom(provider, body);
    expect(only.payload.provenance).toEqual(provenance(1));
    expect(JSON.stringify(only.payload.result)).toContain("round 1");
  });

  it("does not compact anything while the request fits", async () => {
    const { conversation } = await conversationWithRounds(provider, 4, 100);
    const fetchMock = respondWith(answerResponse(provider));
    await requestNextTurn(conversation, [TOOL]);

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(toolResultsFrom(provider, body).every((r) => !r.payload.compacted)).toBe(true);
  });

  it("stubs all but the two most recent rounds when over budget, keeping every message and pairing intact", async () => {
    const { conversation, ids } = await conversationWithRounds(provider, 4, SIZES[provider]);
    const messagesBefore = conversation.nativeMessages.length;
    const fetchMock = respondWith(answerResponse(provider));

    const { turn } = await requestNextTurn(conversation, [TOOL]);
    expect(turn).toEqual({ kind: "answer", text: "done" });

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(messageCount(provider, body)).toBe(messagesBefore);
    const results = toolResultsFrom(provider, body);
    expect(results).toHaveLength(4);

    for (const [idx, older] of results.slice(0, 2).entries()) {
      expect(older.payload).toEqual({ compacted: true, tool: "read_context", provenance: provenance(idx + 1), note: COMPACTED_TOOL_RESULT_NOTE });
    }
    for (const [idx, recent] of results.slice(2).entries()) {
      expect(recent.payload.compacted).toBeUndefined();
      expect(JSON.stringify(recent.payload.result)).toContain(`round ${idx + 3}`);
    }

    if (provider === "gemini") {
      expect(results.every((r) => r.name === "read_context")).toBe(true);
    } else {
      expect(results.map((r) => r.id)).toEqual(ids);
      expect(toolCallIdsFrom(provider, body)).toEqual(ids);
    }
  });

  it("keeps the compacted history when the conversation advances", async () => {
    const { conversation } = await conversationWithRounds(provider, 4, SIZES[provider]);
    respondWith(toolCallResponse(provider, "call_5"));
    const { turn, advance } = await requestNextTurn(conversation, [TOOL]);
    if (turn.kind !== "tool_calls") throw new Error("expected a tool call");
    const next = advance([{ toolCallId: turn.calls[0].toolCallId, name: "read_context", result: { items: [] }, isError: false, provenance: provenance(5) }]);

    const fetchMock = respondWith(answerResponse(provider));
    await requestNextTurn(next, [TOOL]);
    const results = toolResultsFrom(provider, JSON.parse(fetchMock.mock.calls[0][1].body));
    expect(results).toHaveLength(5);
    expect(results[0].payload.compacted).toBe(true);
    expect(results[1].payload.compacted).toBe(true);
  });

  it("still fails closed when even the compacted request is over budget", async () => {
    const { conversation } = await conversationWithRounds(provider, 2, SIZES[provider]);
    vi.spyOn(llmInsights, "getStoredMaxResponseTokens").mockReturnValue(provider === "claude" ? 150_000 : 90_000);
    const fetchMock = respondWith(answerResponse(provider));

    await expect(requestNextTurn(conversation, [TOOL])).rejects.toBeInstanceOf(ProviderProtocolError);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("compactOlderToolResults", () => {
  it("names the tool from the matching call when the stored result had no provenance", () => {
    const messages = [
      { role: "user", content: "hi" },
      { role: "assistant", content: null, tool_calls: [{ id: "a", function: { name: "run_sparql", arguments: "{}" } }] },
      { role: "tool", tool_call_id: "a", content: JSON.stringify({ rows: [1, 2, 3] }) },
      { role: "assistant", content: null, tool_calls: [{ id: "b", function: { name: "read_context", arguments: "{}" } }] },
      { role: "tool", tool_call_id: "b", content: "{}" },
      { role: "assistant", content: null, tool_calls: [{ id: "c", function: { name: "read_context", arguments: "{}" } }] },
      { role: "tool", tool_call_id: "c", content: "{}" },
    ];
    const { messages: out, compactedRounds } = compactOlderToolResults("openai", messages);
    expect(compactedRounds).toBe(1);
    expect(JSON.parse((out[2] as { content: string }).content)).toEqual({ compacted: true, tool: "run_sparql", note: COMPACTED_TOOL_RESULT_NOTE });
    expect(out[4]).toBe(messages[4]);
    expect(out[6]).toBe(messages[6]);
  });

  it("treats a group of parallel OpenAI tool messages as one round", () => {
    const messages = [
      { role: "assistant", tool_calls: [{ id: "a1" }, { id: "a2" }] },
      { role: "tool", tool_call_id: "a1", content: "{}" },
      { role: "tool", tool_call_id: "a2", content: "{}" },
      { role: "assistant", tool_calls: [{ id: "b" }] },
      { role: "tool", tool_call_id: "b", content: "{}" },
      { role: "assistant", tool_calls: [{ id: "c" }] },
      { role: "tool", tool_call_id: "c", content: "{}" },
    ];
    const { messages: out, compactedRounds } = compactOlderToolResults("openai", messages);
    expect(compactedRounds).toBe(1);
    expect(JSON.parse((out[1] as { content: string }).content).compacted).toBe(true);
    expect(JSON.parse((out[2] as { content: string }).content).compacted).toBe(true);
    expect(out[4]).toBe(messages[4]);
  });

  it("never touches plain history messages", () => {
    const messages = [
      { role: "user", content: [{ type: "text", text: "a" }] },
      { role: "assistant", content: "b" },
      { role: "user", content: "c" },
    ];
    expect(compactOlderToolResults("claude", messages, 0)).toEqual({ messages, compactedRounds: 0 });
  });
});

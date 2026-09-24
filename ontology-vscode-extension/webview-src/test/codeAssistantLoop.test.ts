import { describe, it, expect, vi, beforeEach } from "vitest";
import { runAssistantLoop, type LoopContext } from "../services/codeAssistantLoop";
import * as providers from "../services/codeAssistantProviders";
import type { AssistantSession } from "../services/codeAssistantSession";

function baseSession(): AssistantSession {
  return {
    sessionId: "s1",
    snapshot: { projectId: "proj-1", documentPath: "turtle", revision: 1, actionType: "ask" },
    budget: { retrievalCallsRemaining: 5, maxRetrievalCalls: 5 },
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
  };
}

function baseCtx(): LoopContext {
  return { apiBaseUrl: "http://localhost:8083", token: "t", session: baseSession() };
}

beforeEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function stubConversation() {
  vi.spyOn(providers, "startAssistantConversation").mockResolvedValue({
    provider: "claude",
    systemPrompt: "s",
    nativeMessages: [],
  });
}

function sparqlCall(id: string) {
  return { toolCallId: id, name: "run_sparql", args: { query: "SELECT * WHERE { ?s ?p ?o }" } };
}

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, headers: new Headers(), json: async () => body };
}

describe("runAssistantLoop — bounded dispatch per turn", () => {
  it("rejects a turn with more tool calls than the cap without dispatching any of them", async () => {
    const manyCalls = Array.from({ length: 9 }, (_, i) => ({
      toolCallId: `call_${i}`,
      name: "read_context",
      args: { targets: [], kind: "definitions" },
    }));

    const advanceSpy = vi.fn().mockReturnValue({ provider: "claude", systemPrompt: "s", nativeMessages: [] });
    vi.spyOn(providers, "startAssistantConversation").mockResolvedValue({
      provider: "claude",
      systemPrompt: "s",
      nativeMessages: [],
    });
    vi.spyOn(providers, "requestNextTurn")
      .mockResolvedValueOnce({ turn: { kind: "tool_calls", calls: manyCalls }, advance: advanceSpy })
      .mockResolvedValueOnce({ turn: { kind: "answer", text: "done" }, advance: advanceSpy });

    const onStage = vi.fn();
    const outcome = await runAssistantLoop(baseCtx(), "system", "hi", onStage);

    expect(outcome).toEqual({ kind: "answer", text: "done" });
    expect(advanceSpy).toHaveBeenCalledTimes(1);
    const [results] = advanceSpy.mock.calls[0] as [Array<{ isError: boolean }>];
    expect(results).toHaveLength(9);
    expect(results.every((r) => r.isError)).toBe(true);
    expect(onStage).toHaveBeenCalledWith(expect.objectContaining({ stage: "stopped" }));
  });

  it("allows a turn at exactly the cap to dispatch normally", async () => {
    const eightCalls = Array.from({ length: 8 }, (_, i) => ({
      toolCallId: `call_${i}`,
      name: "read_context",
      args: { targets: [{ type: "identifier", value: "http://ex.org/A" }], kind: "definitions" },
    }));

    vi.spyOn(providers, "startAssistantConversation").mockResolvedValue({
      provider: "claude",
      systemPrompt: "s",
      nativeMessages: [],
    });
    const advanceSpy = vi.fn().mockReturnValue({ provider: "claude", systemPrompt: "s", nativeMessages: [] });
    vi.spyOn(providers, "requestNextTurn")
      .mockResolvedValueOnce({ turn: { kind: "tool_calls", calls: eightCalls }, advance: advanceSpy })
      .mockResolvedValueOnce({ turn: { kind: "answer", text: "done" }, advance: advanceSpy });

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ ok: true, items: [], coverage: "complete", revision: 1 }),
      }),
    );

    const outcome = await runAssistantLoop(baseCtx(), "system", "hi", vi.fn());

    expect(outcome).toEqual({ kind: "answer", text: "done" });
    const [results] = advanceSpy.mock.calls[0] as [Array<{ isError: boolean }>];
    expect(results).toHaveLength(8);
    expect(results.every((r) => !r.isError)).toBe(true);
  });
});

describe("runAssistantLoop — dead-end tool errors", () => {
  const deadEnds: Array<[string, number, unknown]> = [
    ["REVISION_STALE", 409, { ok: false, errorCode: "REVISION_STALE", message: "the project changed" }],
    ["RECOVERY_REQUIRED", 409, { ok: false, errorCode: "RECOVERY_REQUIRED", message: "recovery needed" }],
    ["PROJECT_RECOVERY_LOCKED", 423, { error: "locked", errorCode: "PROJECT_RECOVERY_LOCKED", recoveryLocked: true }],
    ["SESSION_NOT_FOUND", 404, null],
    ["UNAUTHORIZED", 401, null],
    ["FORBIDDEN", 403, { error: "view only", viewOnly: true }],
    ["RATE_LIMITED", 429, { ok: false, errorCode: "RATE_LIMITED", message: "slow down", retryAfterSeconds: 3 }],
  ];

  for (const [code, status, body] of deadEnds) {
    it(`stops immediately with ${code} instead of handing it back to the model`, async () => {
      stubConversation();
      const advanceSpy = vi.fn();
      const nextTurn = vi
        .spyOn(providers, "requestNextTurn")
        .mockResolvedValueOnce({ turn: { kind: "tool_calls", calls: [sparqlCall("c1")] }, advance: advanceSpy })
        .mockResolvedValueOnce({ turn: { kind: "answer", text: "should not get here" }, advance: advanceSpy });
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(status, body)));

      const outcome = await runAssistantLoop(baseCtx(), "system", "hi", vi.fn());

      expect(outcome.kind).toBe("stopped");
      if (outcome.kind === "stopped") {
        expect(outcome.errorCode).toBe(code);
        expect(outcome.reason.length).toBeGreaterThan(0);
      }
      expect(advanceSpy).not.toHaveBeenCalled();
      expect(nextTurn).toHaveBeenCalledTimes(1);
    });
  }

  it("stops the loop with the dead-end code when propose_edit hits one", async () => {
    stubConversation();
    vi.spyOn(providers, "requestNextTurn").mockResolvedValueOnce({
      turn: {
        kind: "tool_calls",
        calls: [
          {
            toolCallId: "p1",
            name: "propose_edit",
            args: { groups: [{ edits: [{ targetPath: "turtle", range: { startLine: 0, lineCount: 1 }, originalText: "a", newText: "b" }] }] },
          },
        ],
      },
      advance: vi.fn(),
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(409, { ok: false, errorCode: "REVISION_STALE", message: "moved on" })));

    const outcome = await runAssistantLoop(baseCtx(), "system", "hi", vi.fn());

    expect(outcome).toEqual({ kind: "stopped", reason: "moved on", errorCode: "REVISION_STALE" });
  });

  const recoverable: Array<[string, unknown]> = [
    ["ROW_CAP_EXCEEDED", { ok: false, errorCode: "ROW_CAP_EXCEEDED", message: "too many rows" }],
    ["BYTE_CAP_EXCEEDED", { ok: false, errorCode: "BYTE_CAP_EXCEEDED", message: "too many bytes" }],
    ["TIMEOUT", { ok: false, errorCode: "TIMEOUT", message: "took too long" }],
    ["QUERY_ERROR", { ok: false, errorCode: "QUERY_ERROR", message: "bad query" }],
    ["NOT_SELECT_ONLY", { ok: false, errorCode: "NOT_SELECT_ONLY", message: "select only" }],
    ["BUDGET_EXHAUSTED", { ok: false, errorCode: "BUDGET_EXHAUSTED", message: "no budget left" }],
  ];

  for (const [code, body] of recoverable) {
    it(`hands ${code} back to the model so it can adjust`, async () => {
      stubConversation();
      const advanceSpy = vi.fn().mockReturnValue({ provider: "claude", systemPrompt: "s", nativeMessages: [] });
      vi.spyOn(providers, "requestNextTurn")
        .mockResolvedValueOnce({ turn: { kind: "tool_calls", calls: [sparqlCall("c1")] }, advance: advanceSpy })
        .mockResolvedValueOnce({ turn: { kind: "answer", text: "adjusted" }, advance: advanceSpy });
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(400, body)));

      const outcome = await runAssistantLoop(baseCtx(), "system", "hi", vi.fn());

      expect(outcome).toEqual({ kind: "answer", text: "adjusted" });
      expect(advanceSpy).toHaveBeenCalledTimes(1);
      const [results] = advanceSpy.mock.calls[0] as [Array<{ isError: boolean; result: unknown }>];
      expect(results[0].isError).toBe(true);
      expect(JSON.stringify(results[0].result)).toContain(code);
    });
  }

  it("hands argument validation failures back to the model without calling the backend", async () => {
    stubConversation();
    const advanceSpy = vi.fn().mockReturnValue({ provider: "claude", systemPrompt: "s", nativeMessages: [] });
    vi.spyOn(providers, "requestNextTurn")
      .mockResolvedValueOnce({ turn: { kind: "tool_calls", calls: [{ toolCallId: "c1", name: "run_sparql", args: {} }] }, advance: advanceSpy })
      .mockResolvedValueOnce({ turn: { kind: "answer", text: "fixed" }, advance: advanceSpy });
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const outcome = await runAssistantLoop(baseCtx(), "system", "hi", vi.fn());

    expect(outcome).toEqual({ kind: "answer", text: "fixed" });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

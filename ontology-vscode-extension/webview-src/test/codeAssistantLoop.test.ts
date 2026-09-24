import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  runAssistantLoop,
  ASSISTANT_TOOLS,
  PROPOSE_RENAME_TOOL,
  READ_CONTEXT_TOOL,
  type LoopContext,
} from "../services/codeAssistantLoop";
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

describe("runAssistantLoop — usage reporting", () => {
  const usage = { provider: "claude" as const, model: "claude-sonnet-4-5", latencyMs: 812, inputTokens: 100, outputTokens: 12, cacheReadTokens: 50 };

  it("calls onUsage after every provider call and posts the same numbers to the backend", async () => {
    stubConversation();
    const advanceSpy = vi.fn().mockReturnValue({ provider: "claude", systemPrompt: "s", nativeMessages: [] });
    vi.spyOn(providers, "requestNextTurn")
      .mockResolvedValueOnce({ turn: { kind: "tool_calls", calls: [sparqlCall("c1")] }, advance: advanceSpy, usage })
      .mockResolvedValueOnce({ turn: { kind: "answer", text: "done" }, advance: advanceSpy, usage: { ...usage, latencyMs: 90 } });
    const fetchMock = vi.fn(async (url: string) =>
      url.endsWith("/usage")
        ? { ok: true, status: 204, headers: new Headers(), json: async () => null }
        : jsonResponse(200, { ok: true, result: { rows: [], truncated: false, rowCount: 0 }, provenance: { revision: 1 } }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const onUsage = vi.fn();

    await runAssistantLoop(baseCtx(), "system", "hi", vi.fn(), undefined, [], undefined, onUsage);

    expect(onUsage.mock.calls.map((c) => c[0].latencyMs)).toEqual([812, 90]);
    const usageCalls = fetchMock.mock.calls.filter((c) => String(c[0]).endsWith("/usage")) as unknown as Array<[string, RequestInit]>;
    expect(usageCalls).toHaveLength(2);
    expect(usageCalls[0][0]).toBe("http://localhost:8083/api/v1/code-assistant/sessions/s1/usage");
    expect(JSON.parse(String(usageCalls[0][1].body))).toEqual({
      provider: "claude",
      model: "claude-sonnet-4-5",
      latencyMs: 812,
      inputTokens: 100,
      outputTokens: 12,
      cacheReadTokens: 50,
    });
    expect((usageCalls[0][1].headers as Record<string, string>).Authorization).toBe("Bearer t");
  });

  it("never lets a failing usage post or callback break the loop", async () => {
    stubConversation();
    vi.spyOn(providers, "requestNextTurn").mockResolvedValueOnce({ turn: { kind: "answer", text: "done" }, advance: vi.fn(), usage });
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network down")));
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);

    const outcome = await runAssistantLoop(baseCtx(), "system", "hi", vi.fn(), undefined, [], undefined, () => {
      throw new Error("callback blew up");
    });

    expect(outcome).toEqual({ kind: "answer", text: "done" });
    expect(warn).toHaveBeenCalled();
  });

  it("does not wait for the usage post to finish", async () => {
    stubConversation();
    vi.spyOn(providers, "requestNextTurn").mockResolvedValueOnce({ turn: { kind: "answer", text: "done" }, advance: vi.fn(), usage });
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => undefined)));

    const outcome = await runAssistantLoop(baseCtx(), "system", "hi", vi.fn());

    expect(outcome).toEqual({ kind: "answer", text: "done" });
  });
});

describe("propose_rename and read_context tool surface", () => {
  const renameArgs = { targetPath: "turtle", targetIdentifier: "ex:Piza", replacementIdentifier: "ex:Pizza" };
  const proposeResult = {
    ok: true,
    groups: [{ clientGroupId: "x", serverGroupId: "srv_1", validation: { passed: true, checks: [] }, diff: [] }],
  };

  it("exports propose_rename in ASSISTANT_TOOLS and points propose_edit at it", () => {
    const names = ASSISTANT_TOOLS.map((t) => t.name);
    expect(names).toEqual(["read_context", "run_sparql", "propose_edit", "propose_rename"]);
    expect(PROPOSE_RENAME_TOOL.parameters.required).toEqual(["targetPath", "targetIdentifier", "replacementIdentifier"]);
    expect(ASSISTANT_TOOLS.find((t) => t.name === "propose_edit")!.description).toMatch(/propose_rename/);
  });

  it("offers a statement target and describes diagnostics as real parse issues", () => {
    const targetType = READ_CONTEXT_TOOL.parameters.properties!.targets.items!.properties!.type;
    expect(targetType.enum).toContain("statement");
    expect(targetType.description).toMatch(/statement/);
    expect(READ_CONTEXT_TOOL.parameters.properties!.kind.description).toMatch(/parse errors and warnings/);
  });

  it("sends a rename as one operation group with no edits and ends the loop with the proposal", async () => {
    stubConversation();
    vi.spyOn(providers, "requestNextTurn").mockResolvedValueOnce({
      turn: { kind: "tool_calls", calls: [{ toolCallId: "r1", name: "propose_rename", args: renameArgs }] },
      advance: vi.fn(),
    });
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, proposeResult));
    vi.stubGlobal("fetch", fetchMock);

    const outcome = await runAssistantLoop(baseCtx(), "system", "rename it", vi.fn());

    expect(outcome).toEqual({ kind: "propose", result: proposeResult });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://localhost:8083/api/v1/code-assistant/sessions/s1/propose");
    const body = JSON.parse(init.body);
    expect(body.groups).toHaveLength(1);
    expect(body.groups[0].edits).toBeUndefined();
    expect(body.groups[0].clientGroupId).toMatch(/^grp_/);
    expect(body.groups[0].operation).toEqual({ type: "rename_identifier", ...renameArgs });
  });

  it("rejects propose_rename mixed with other calls without dispatching anything", async () => {
    stubConversation();
    const advanceSpy = vi.fn().mockReturnValue({ provider: "claude", systemPrompt: "s", nativeMessages: [] });
    vi.spyOn(providers, "requestNextTurn")
      .mockResolvedValueOnce({
        turn: { kind: "tool_calls", calls: [{ toolCallId: "r1", name: "propose_rename", args: renameArgs }, sparqlCall("c2")] },
        advance: advanceSpy,
      })
      .mockResolvedValueOnce({ turn: { kind: "answer", text: "ok" }, advance: advanceSpy });
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const outcome = await runAssistantLoop(baseCtx(), "system", "rename it", vi.fn());

    expect(outcome).toEqual({ kind: "answer", text: "ok" });
    expect(fetchMock).not.toHaveBeenCalled();
    const [results] = advanceSpy.mock.calls[0] as [Array<{ isError: boolean; result: { error: string } }>];
    expect(results.every((r) => r.isError)).toBe(true);
    expect(results[0].result.error).toMatch(/propose_rename must be the only tool call/);
  });

  it("rejects propose_edit and propose_rename in the same turn", async () => {
    stubConversation();
    const advanceSpy = vi.fn().mockReturnValue({ provider: "claude", systemPrompt: "s", nativeMessages: [] });
    vi.spyOn(providers, "requestNextTurn")
      .mockResolvedValueOnce({
        turn: {
          kind: "tool_calls",
          calls: [
            { toolCallId: "r1", name: "propose_rename", args: renameArgs },
            { toolCallId: "e1", name: "propose_edit", args: { groups: [] } },
          ],
        },
        advance: advanceSpy,
      })
      .mockResolvedValueOnce({ turn: { kind: "answer", text: "ok" }, advance: advanceSpy });
    vi.stubGlobal("fetch", vi.fn());

    await runAssistantLoop(baseCtx(), "system", "x", vi.fn());

    const [results] = advanceSpy.mock.calls[0] as [Array<{ result: { error: string } }>];
    expect(results[0].result.error).toMatch(/propose_rename and propose_edit must be the only tool call/);
  });

  it("stops without calling the backend when the rename is a no-op", async () => {
    stubConversation();
    vi.spyOn(providers, "requestNextTurn").mockResolvedValueOnce({
      turn: {
        kind: "tool_calls",
        calls: [{ toolCallId: "r1", name: "propose_rename", args: { ...renameArgs, replacementIdentifier: "ex:Piza" } }],
      },
      advance: vi.fn(),
    });
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const outcome = await runAssistantLoop(baseCtx(), "system", "x", vi.fn());

    expect(outcome.kind).toBe("stopped");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("passes a statement target through to the backend", async () => {
    stubConversation();
    const advanceSpy = vi.fn().mockReturnValue({ provider: "claude", systemPrompt: "s", nativeMessages: [] });
    vi.spyOn(providers, "requestNextTurn")
      .mockResolvedValueOnce({
        turn: {
          kind: "tool_calls",
          calls: [{ toolCallId: "c1", name: "read_context", args: { targets: [{ type: "statement", value: "ex:Pizza" }], kind: "definitions" } }],
        },
        advance: advanceSpy,
      })
      .mockResolvedValueOnce({ turn: { kind: "answer", text: "ok" }, advance: advanceSpy });
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(200, {
        ok: true,
        result: { items: [{ source: "turtle", range: "10-4", text: "ex:Pizza a owl:Class .", kind: "statement" }] },
        provenance: { revision: 3, coverage: "complete" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await runAssistantLoop(baseCtx(), "system", "x", vi.fn());

    expect(JSON.parse(fetchMock.mock.calls[0][1].body).targets).toEqual([{ type: "statement", value: "ex:Pizza" }]);
    const [results] = advanceSpy.mock.calls[0] as [Array<{ isError: boolean; result: { items: Array<{ kind: string }> } }>];
    expect(results[0].isError).toBe(false);
    expect(results[0].result.items[0].kind).toBe("statement");
  });
});

describe("runAssistantLoop — tool result provenance", () => {
  it("attaches provenance with the result's revision, the range, a short reason and the step", async () => {
    stubConversation();
    const advanceSpy = vi.fn().mockReturnValue({ provider: "claude", systemPrompt: "s", nativeMessages: [] });
    vi.spyOn(providers, "requestNextTurn")
      .mockResolvedValueOnce({ turn: { kind: "tool_calls", calls: [sparqlCall("c0")] }, advance: advanceSpy })
      .mockResolvedValueOnce({
        turn: {
          kind: "tool_calls",
          calls: [
            { toolCallId: "c1", name: "read_context", args: { targets: [{ type: "range", value: "turtle:100-50" }], kind: "definitions" } },
            { toolCallId: "c2", name: "run_sparql", args: { query: "SELECT ?s\n WHERE { ?s ?p ?o }" } },
          ],
        },
        advance: advanceSpy,
      })
      .mockResolvedValueOnce({ turn: { kind: "answer", text: "done" }, advance: advanceSpy });
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) =>
        url.endsWith("/read_context")
          ? jsonResponse(200, { ok: true, result: { items: [] }, provenance: { revision: 9, coverage: "complete" } })
          : jsonResponse(200, { ok: true, result: { rows: [], truncated: false, rowCount: 0 }, provenance: { revision: 9 } }),
      ),
    );

    await runAssistantLoop(baseCtx(), "system", "hi", vi.fn());

    const [secondRound] = advanceSpy.mock.calls[1] as [Array<{ provenance: Record<string, unknown> }>];
    expect(secondRound[0].provenance).toEqual({
      tool: "read_context",
      format: "turtle",
      range: "100-50",
      revision: 9,
      reason: "definitions for range turtle:100-50",
      step: 2,
    });
    expect(secondRound[1].provenance).toEqual({
      tool: "run_sparql",
      format: "sparql",
      revision: 9,
      reason: "query: SELECT ?s WHERE { ?s ?p ?o }",
      step: 2,
    });
  });

  it("falls back to the session snapshot revision and document path when the result has none", async () => {
    stubConversation();
    const advanceSpy = vi.fn().mockReturnValue({ provider: "claude", systemPrompt: "s", nativeMessages: [] });
    vi.spyOn(providers, "requestNextTurn")
      .mockResolvedValueOnce({
        turn: { kind: "tool_calls", calls: [{ toolCallId: "c1", name: "read_context", args: { targets: [{ type: "identifier", value: "http://ex.org/A" }], kind: "references" } }] },
        advance: advanceSpy,
      })
      .mockResolvedValueOnce({ turn: { kind: "answer", text: "done" }, advance: advanceSpy });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(400, { ok: false, errorCode: "BUDGET_EXHAUSTED", message: "no more" })));

    await runAssistantLoop(baseCtx(), "system", "hi", vi.fn());

    const [results] = advanceSpy.mock.calls[0] as [Array<{ provenance: Record<string, unknown> }>];
    expect(results[0].provenance).toEqual({
      tool: "read_context",
      targetPath: "turtle",
      revision: 1,
      reason: "references for identifier http://ex.org/A",
      step: 1,
    });
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

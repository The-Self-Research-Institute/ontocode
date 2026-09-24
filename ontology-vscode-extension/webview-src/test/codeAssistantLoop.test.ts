import { describe, it, expect, vi } from "vitest";
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

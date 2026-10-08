import { describe, it, expect, vi, beforeEach } from "vitest";
import { pollConsistencyCheck } from "../services/assistantConsistencyCheckPoll";

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

beforeEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("pollConsistencyCheck", () => {
  it("resolves immediately when the first poll already has a terminal state", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(200, { ok: true, name: "consistency_preserved", passed: false, detail: "Inconsistent" }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const onResolved = vi.fn();

    await pollConsistencyCheck("http://localhost:8083", "tok", "s1", "g1", onResolved);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe(
      "http://localhost:8083/api/v1/code-assistant/sessions/s1/groups/g1/consistency-check",
    );
    expect(onResolved).toHaveBeenCalledWith({
      name: "consistency_preserved",
      passed: false,
      detail: "Inconsistent",
      status: undefined,
    });
  });

  it("keeps polling while the result is pending, then resolves", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, { ok: true, name: "consistency_preserved", passed: true, status: "pending" }))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true, name: "consistency_preserved", passed: true, status: "pending" }))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true, name: "consistency_preserved", passed: true }));
    vi.stubGlobal("fetch", fetchMock);
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const onResolved = vi.fn();

    const promise = pollConsistencyCheck("http://localhost:8083", "tok", "s1", "g1", onResolved);
    await vi.advanceTimersByTimeAsync(1500);
    await vi.advanceTimersByTimeAsync(1500);
    await promise;

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(onResolved).toHaveBeenCalledTimes(1);
    expect(onResolved).toHaveBeenCalledWith({ name: "consistency_preserved", passed: true, detail: undefined, status: undefined });
    vi.useRealTimers();
  });

  it("stops without calling onResolved when the group is gone (404)", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(404, { ok: false }));
    vi.stubGlobal("fetch", fetchMock);
    const onResolved = vi.fn();

    await pollConsistencyCheck("http://localhost:8083", "tok", "s1", "g1", onResolved);

    expect(onResolved).not.toHaveBeenCalled();
  });

  it("stops when the signal is already aborted", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const controller = new AbortController();
    controller.abort();

    await pollConsistencyCheck("http://localhost:8083", "tok", "s1", "g1", vi.fn(), controller.signal);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("sends the bearer token when provided", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(200, { ok: true, name: "consistency_preserved", passed: true }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await pollConsistencyCheck("http://localhost:8083", "tok", "s1", "g1", vi.fn());

    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe("Bearer tok");
  });
});

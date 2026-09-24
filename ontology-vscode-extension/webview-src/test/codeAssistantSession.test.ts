import { describe, it, expect, vi, beforeEach } from "vitest";
import * as llmInsights from "../services/LlmInsightsService";
import {
  createAssistantSession,
  runSparql,
  readContext,
  proposeEditGroups,
  AssistantApiError,
  isDeadEndErrorCode,
} from "../services/codeAssistantSession";

function mockFetchOnce(status: number, body: unknown, headers: Record<string, string> = {}) {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      headers: new Headers(headers),
      json: async () => body,
    }),
  );
}

async function captureError(promise: Promise<unknown>): Promise<AssistantApiError> {
  try {
    await promise;
  } catch (e) {
    expect(e).toBeInstanceOf(AssistantApiError);
    return e as AssistantApiError;
  }
  throw new Error("expected the request to fail");
}

const SPARQL = "SELECT * WHERE {?s ?p ?o}";

describe("codeAssistantSession error envelope handling", () => {
  it("resolves with the parsed body on success", async () => {
    mockFetchOnce(200, {
      sessionId: "s1",
      snapshot: { projectId: "p", documentPath: "d", revision: 42, actionType: "ask" },
      budget: { retrievalCallsRemaining: 8, maxRetrievalCalls: 8 },
      expiresAt: "2026-01-01T00:00:00Z",
    });

    const session = await createAssistantSession("http://api", "token", {
      projectId: "p",
      documentPath: "d",
      actionType: "ask",
      actionContext: "{}",
    });

    expect(session.sessionId).toBe("s1");
    expect(session.snapshot.revision).toBe(42);
  });

  it("throws AssistantApiError with the errorCode from an ok:false envelope even on HTTP 200", async () => {
    mockFetchOnce(200, { ok: false, errorCode: "ROW_CAP_EXCEEDED", message: "too many rows" });

    await expect(runSparql("http://api", "token", "s1", "SELECT * WHERE {?s ?p ?o}")).rejects.toMatchObject({
      errorCode: "ROW_CAP_EXCEEDED",
      message: "too many rows",
    });
  });

  it("throws AssistantApiError on a plain HTTP error with no envelope", async () => {
    mockFetchOnce(500, null);

    await expect(runSparql("http://api", "token", "s1", "SELECT * WHERE {?s ?p ?o}")).rejects.toBeInstanceOf(
      AssistantApiError,
    );
  });

  it("sends the Authorization header only when a token is provided", async () => {
    const fetchSpy = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ ok: true }) });
    vi.stubGlobal("fetch", fetchSpy);

    await runSparql("http://api", undefined, "s1", "SELECT * WHERE {?s ?p ?o}");

    const headers = fetchSpy.mock.calls[0][1].headers;
    expect(headers.Authorization).toBeUndefined();
  });
});

describe("codeAssistantSession idempotency and session metadata", () => {
  const sessionInput = { projectId: "p", documentPath: "d", actionType: "ask" as const, actionContext: "{}" };
  const sessionBody = {
    sessionId: "s1",
    snapshot: { projectId: "p", documentPath: "d", revision: 1, actionType: "ask" },
    budget: { retrievalCallsRemaining: 8, maxRetrievalCalls: 8 },
    expiresAt: "2026-01-01T00:00:00Z",
  };

  beforeEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  function response(status: number, body: unknown) {
    return { ok: status >= 200 && status < 300, status, headers: new Headers(), json: async () => body };
  }

  it("sends an Idempotency-Key and the stored provider and model when creating a session", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    vi.spyOn(llmInsights, "getStoredModel").mockReturnValue("gpt-4o-mini");
    const fetchSpy = vi.fn().mockResolvedValue(response(200, sessionBody));
    vi.stubGlobal("fetch", fetchSpy);

    await createAssistantSession("http://api", "token", sessionInput);

    const init = fetchSpy.mock.calls[0][1];
    expect(init.headers["Idempotency-Key"]).toMatch(/.{16,}/);
    expect(JSON.parse(init.body)).toMatchObject({ provider: "openai", model: "gpt-4o-mini", projectId: "p" });
  });

  it("uses an explicitly given provider and model over the stored ones", async () => {
    vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("openai");
    const fetchSpy = vi.fn().mockResolvedValue(response(200, sessionBody));
    vi.stubGlobal("fetch", fetchSpy);

    await createAssistantSession("http://api", "token", { ...sessionInput, provider: "claude", model: "claude-sonnet-4-5" });

    expect(JSON.parse(fetchSpy.mock.calls[0][1].body)).toMatchObject({ provider: "claude", model: "claude-sonnet-4-5" });
  });

  it("generates a fresh key for each logical call", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(response(200, { ok: true, groups: [] }));
    vi.stubGlobal("fetch", fetchSpy);

    await proposeEditGroups("http://api", "token", "s1", []);
    await proposeEditGroups("http://api", "token", "s1", []);

    const first = fetchSpy.mock.calls[0][1].headers["Idempotency-Key"];
    const second = fetchSpy.mock.calls[1][1].headers["Idempotency-Key"];
    expect(first).toBeTruthy();
    expect(second).toBeTruthy();
    expect(first).not.toBe(second);
  });

  it("reuses the same key and body when retrying a call after a network failure and a 503", async () => {
    vi.useFakeTimers();
    try {
      const fetchSpy = vi
        .fn()
        .mockRejectedValueOnce(new TypeError("Failed to fetch"))
        .mockResolvedValueOnce(response(503, null))
        .mockResolvedValueOnce(response(200, { ok: true, groups: [] }));
      vi.stubGlobal("fetch", fetchSpy);

      const pending = proposeEditGroups("http://api", "token", "s1", []);
      await vi.runAllTimersAsync();
      await expect(pending).resolves.toEqual({ ok: true, groups: [] });

      expect(fetchSpy).toHaveBeenCalledTimes(3);
      const keys = fetchSpy.mock.calls.map((c) => c[1].headers["Idempotency-Key"]);
      expect(new Set(keys).size).toBe(1);
      const bodies = fetchSpy.mock.calls.map((c) => c[1].body);
      expect(new Set(bodies).size).toBe(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it("waits and retries with the same key while the first request is still in flight", async () => {
    vi.useFakeTimers();
    try {
      const fetchSpy = vi
        .fn()
        .mockResolvedValueOnce(response(409, { ok: false, errorCode: "IDEMPOTENCY_KEY_REUSED", message: "in flight" }))
        .mockResolvedValueOnce(response(200, sessionBody));
      vi.stubGlobal("fetch", fetchSpy);

      const pending = createAssistantSession("http://api", "token", sessionInput, undefined, { idempotencyKey: "fixed-key" });
      await vi.runAllTimersAsync();
      const session = await pending;

      expect(session.sessionId).toBe("s1");
      expect(fetchSpy.mock.calls.map((c) => c[1].headers["Idempotency-Key"])).toEqual(["fixed-key", "fixed-key"]);
    } finally {
      vi.useRealTimers();
    }
  });

  it("does not retry a key reused with a different body", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(response(422, { ok: false, errorCode: "IDEMPOTENCY_KEY_REUSED", message: "different body" }));
    vi.stubGlobal("fetch", fetchSpy);

    const err = await captureError(proposeEditGroups("http://api", "token", "s1", [], undefined, { idempotencyKey: "k" }));

    expect(err.errorCode).toBe("IDEMPOTENCY_KEY_REUSED");
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it("gives up after a bounded number of retries", async () => {
    vi.useFakeTimers();
    try {
      const fetchSpy = vi.fn().mockResolvedValue(response(503, null));
      vi.stubGlobal("fetch", fetchSpy);

      const pending = proposeEditGroups("http://api", "token", "s1", []);
      const assertion = expect(pending).rejects.toBeInstanceOf(AssistantApiError);
      await vi.runAllTimersAsync();
      await assertion;
      expect(fetchSpy).toHaveBeenCalledTimes(3);
    } finally {
      vi.useRealTimers();
    }
  });

  it("never retries or sends a key on the read tools", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(response(503, null));
    vi.stubGlobal("fetch", fetchSpy);

    await captureError(runSparql("http://api", "token", "s1", SPARQL));

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(fetchSpy.mock.calls[0][1].headers["Idempotency-Key"]).toBeUndefined();
  });
});

describe("codeAssistantSession HTTP status mapping", () => {
  it("maps 401 to UNAUTHORIZED even without a body", async () => {
    mockFetchOnce(401, null);
    const err = await captureError(runSparql("http://api", "token", "s1", SPARQL));
    expect(err.errorCode).toBe("UNAUTHORIZED");
    expect(err.status).toBe(401);
  });

  it("maps a 403 from the view-only interceptor to FORBIDDEN and keeps its message", async () => {
    mockFetchOnce(403, { error: "This project is view-only for you.", viewOnly: true });
    const err = await captureError(readContext("http://api", "token", "s1", { targets: [], kind: "definitions" }));
    expect(err.errorCode).toBe("FORBIDDEN");
    expect(err.message).toBe("This project is view-only for you.");
  });

  it("keeps a more specific errorCode that a 403 body carries", async () => {
    mockFetchOnce(403, { ok: false, errorCode: "VALIDATION_FAILED", message: "not your group" });
    const err = await captureError(runSparql("http://api", "token", "s1", SPARQL));
    expect(err.errorCode).toBe("VALIDATION_FAILED");
  });

  it("maps a 404 on a session endpoint to SESSION_NOT_FOUND", async () => {
    mockFetchOnce(404, null);
    const err = await captureError(runSparql("http://api", "token", "gone", SPARQL));
    expect(err.errorCode).toBe("SESSION_NOT_FOUND");
  });

  it("does not invent SESSION_NOT_FOUND for a 404 on session creation", async () => {
    mockFetchOnce(404, null);
    const err = await captureError(
      createAssistantSession("http://api", "token", { projectId: "p", documentPath: "d", actionType: "ask", actionContext: "{}" }),
    );
    expect(err.errorCode).toBeUndefined();
    expect(err.status).toBe(404);
  });

  it("maps 423 to PROJECT_RECOVERY_LOCKED and flags the recovery lock", async () => {
    mockFetchOnce(423, { error: "locked", errorCode: "PROJECT_RECOVERY_LOCKED", recoveryLocked: true });
    const err = await captureError(proposeEditGroups("http://api", "token", "s1", []));
    expect(err.errorCode).toBe("PROJECT_RECOVERY_LOCKED");
    expect(err.recoveryLocked).toBe(true);
  });

  it("maps 429 to RATE_LIMITED and keeps the Retry-After seconds from the header", async () => {
    mockFetchOnce(429, null, { "Retry-After": "17" });
    const err = await captureError(runSparql("http://api", "token", "s1", SPARQL));
    expect(err.errorCode).toBe("RATE_LIMITED");
    expect(err.retryAfterSeconds).toBe(17);
    expect(err.message).toMatch(/17s/);
  });

  it("prefers the body's retryAfterSeconds on a 429", async () => {
    mockFetchOnce(429, { ok: false, errorCode: "RATE_LIMITED", message: "slow down", retryAfterSeconds: 5 }, { "Retry-After": "30" });
    const err = await captureError(runSparql("http://api", "token", "s1", SPARQL));
    expect(err.retryAfterSeconds).toBe(5);
    expect(err.message).toBe("slow down");
  });

  it("classifies only the loop-ending codes as dead ends", () => {
    for (const code of ["REVISION_STALE", "RECOVERY_REQUIRED", "PROJECT_RECOVERY_LOCKED", "SESSION_NOT_FOUND", "UNAUTHORIZED", "FORBIDDEN", "RATE_LIMITED"] as const) {
      expect(isDeadEndErrorCode(code)).toBe(true);
    }
    for (const code of ["ROW_CAP_EXCEEDED", "BYTE_CAP_EXCEEDED", "TIMEOUT", "QUERY_ERROR", "NOT_SELECT_ONLY", "BUDGET_EXHAUSTED", "VALIDATION_FAILED"] as const) {
      expect(isDeadEndErrorCode(code)).toBe(false);
    }
    expect(isDeadEndErrorCode(undefined)).toBe(false);
  });
});

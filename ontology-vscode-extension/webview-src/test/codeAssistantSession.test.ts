import { describe, it, expect, vi } from "vitest";
import { createAssistantSession, runSparql, AssistantApiError } from "../services/codeAssistantSession";

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

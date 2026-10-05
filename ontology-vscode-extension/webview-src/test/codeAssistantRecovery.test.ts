import { afterEach, describe, expect, it, vi } from "vitest";
import {
  clearRecoveryLock,
  describeLockedAt,
  fetchRecoveryState,
  normalizeRecoveryState,
  RecoveryApiError,
  restorePreviousVersion,
} from "../services/codeAssistantRecovery";

function stubFetch(status: number, body: unknown) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("normalizeRecoveryState", () => {
  it("keeps the documented fields of a locked project", () => {
    expect(
      normalizeRecoveryState({
        locked: true,
        reason: "reimport failed",
        lockedAt: "2026-09-24T10:00:00Z",
        operationId: "op-1",
        canRestore: true,
      }),
    ).toEqual({ locked: true, reason: "reimport failed", lockedAt: "2026-09-24T10:00:00Z", operationId: "op-1", canRestore: true });
  });

  it("never offers a restore for a project that isn't locked", () => {
    expect(normalizeRecoveryState({ locked: false, canRestore: true })).toEqual({
      locked: false,
      reason: undefined,
      lockedAt: undefined,
      operationId: undefined,
      canRestore: false,
    });
  });

  it("treats junk as unlocked", () => {
    expect(normalizeRecoveryState(null).locked).toBe(false);
    expect(normalizeRecoveryState("locked").locked).toBe(false);
    expect(normalizeRecoveryState({ locked: "true" }).locked).toBe(false);
  });
});

describe("recovery endpoints", () => {
  it("reads the state with a GET carrying the JWT", async () => {
    const fetchMock = stubFetch(200, { locked: true, canRestore: false, reason: "partial write" });
    const state = await fetchRecoveryState("http://api", "jwt", "proj/1");
    expect(state).toMatchObject({ locked: true, canRestore: false, reason: "partial write" });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://api/api/v1/code-assistant/projects/proj%2F1/recovery");
    expect(init.method).toBe("GET");
    expect(init.body).toBeUndefined();
    expect(init.headers.Authorization).toBe("Bearer jwt");
  });

  it("posts restore and clear to their own paths", async () => {
    const fetchMock = stubFetch(200, { ok: true });
    await restorePreviousVersion("http://api", "jwt", "proj-1");
    await clearRecoveryLock("http://api", undefined, "proj-1");
    expect(fetchMock.mock.calls[0][0]).toBe("http://api/api/v1/code-assistant/projects/proj-1/recovery/restore");
    expect(fetchMock.mock.calls[0][1].method).toBe("POST");
    expect(fetchMock.mock.calls[1][0]).toBe("http://api/api/v1/code-assistant/projects/proj-1/recovery/clear");
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBeUndefined();
  });

  it("turns a 409 RECOVERY_REQUIRED restore into an error with the server's message", async () => {
    stubFetch(409, { ok: false, errorCode: "RECOVERY_REQUIRED", message: "The snapshot is gone." });
    const error = await restorePreviousVersion("http://api", "jwt", "proj-1").catch((e) => e);
    expect(error).toBeInstanceOf(RecoveryApiError);
    expect(error.status).toBe(409);
    expect(error.errorCode).toBe("RECOVERY_REQUIRED");
    expect(error.message).toBe("The snapshot is gone.");
  });

  it("treats a 200 with ok:false as a failure", async () => {
    stubFetch(200, { ok: false, errorCode: "RECOVERY_REQUIRED", message: "Not yet" });
    await expect(clearRecoveryLock("http://api", "jwt", "proj-1")).rejects.toBeInstanceOf(RecoveryApiError);
  });

  it("falls back to a readable message when the body isn't JSON", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: false, status: 502, json: async () => Promise.reject(new Error("html")) }),
    );
    await expect(fetchRecoveryState("http://api", "jwt", "proj-1")).rejects.toThrow("Recovery request failed (HTTP 502).");
  });
});

describe("describeLockedAt", () => {
  it("formats an ISO timestamp and ignores anything unparseable", () => {
    expect(describeLockedAt("2026-09-24T10:00:00Z")).toBe(new Date("2026-09-24T10:00:00Z").toLocaleString());
    expect(describeLockedAt("yesterday")).toBeNull();
    expect(describeLockedAt(undefined)).toBeNull();
  });
});

import { describe, it, expect } from "vitest";
import {
  errorSignalFrom,
  isRecoverySignal,
  parseHttpStatus,
  parseRetryAfterSeconds,
  permissionMessage,
  retryButtonLabel,
  secondsUntil,
  toDeadEnd,
} from "../services/codeAssistantDeadEnd";
import { AssistantApiError } from "../services/codeAssistantSession";

describe("toDeadEnd", () => {
  it("offers to ask again with the latest version when the revision moved", () => {
    const dead = toDeadEnd(errorSignalFrom({ kind: "stopped", reason: "Project changed", errorCode: "REVISION_STALE" }, ""));
    expect(dead?.code).toBe("REVISION_STALE");
    expect(dead?.action).toEqual({ kind: "resubmit", label: "Ask again with the latest version" });
  });

  it("offers a plain ask-again when the session is gone", () => {
    const dead = toDeadEnd(errorSignalFrom(new AssistantApiError("Session not found", "SESSION_NOT_FOUND"), ""));
    expect(dead?.action).toEqual({ kind: "resubmit", label: "Ask again" });
  });

  it("uses the server's retry-after seconds for a rate limit", () => {
    const dead = toDeadEnd(errorSignalFrom({ errorCode: "RATE_LIMITED", retryAfterSeconds: 12.2, message: "slow down" }, ""));
    expect(dead?.action).toEqual({ kind: "retry-after", seconds: 13 });
  });

  it("falls back to seconds mentioned in the message, and to none", () => {
    expect(toDeadEnd({ errorCode: "RATE_LIMITED", message: "Rate limited, retry after 30 seconds" })?.action).toEqual({
      kind: "retry-after",
      seconds: 30,
    });
    expect(toDeadEnd({ errorCode: "RATE_LIMITED", message: "Rate limited" })?.action).toEqual({ kind: "retry-after", seconds: null });
  });

  it("recognises a provider rate limit that carries no error code", () => {
    expect(toDeadEnd({ message: "Rate limit reached for this model." })?.code).toBe("RATE_LIMITED");
  });

  it("maps HTTP statuses when there is no error code", () => {
    expect(toDeadEnd(errorSignalFrom(new AssistantApiError("Code assistant request failed (HTTP 401)."), ""))?.action).toEqual({
      kind: "sign-in",
      label: "Sign in again",
    });
    expect(toDeadEnd({ status: 403, message: "nope" })?.action).toEqual({ kind: "explain" });
    expect(toDeadEnd({ status: 423, message: "locked" })?.action).toEqual({ kind: "recovery" });
    expect(toDeadEnd({ status: 429, message: "busy" })?.code).toBe("RATE_LIMITED");
  });

  it("sends both recovery codes to the recovery banner", () => {
    expect(toDeadEnd({ errorCode: "RECOVERY_REQUIRED", message: "" })?.action).toEqual({ kind: "recovery" });
    expect(toDeadEnd({ errorCode: "PROJECT_RECOVERY_LOCKED", message: "" })?.action).toEqual({ kind: "recovery" });
    expect(isRecoverySignal({ status: 423, message: "" })).toBe(true);
    expect(isRecoverySignal({ errorCode: "CONFLICT", message: "" })).toBe(false);
  });

  it("returns nothing for errors without a dedicated action", () => {
    expect(toDeadEnd({ errorCode: "BUDGET_EXHAUSTED", status: 429, message: "" })).toBeNull();
    expect(toDeadEnd({ message: "Something odd happened" })).toBeNull();
    expect(toDeadEnd(errorSignalFrom(null, "fallback"))).toBeNull();
  });
});

describe("permissionMessage", () => {
  it("explains the specific permission problem", () => {
    expect(permissionMessage({ viewOnly: true })).toMatch(/view-only/);
    expect(permissionMessage({ draftAllowed: true, viewOnly: true })).toMatch(/draft/);
    expect(permissionMessage({ requiresUpgrade: true })).toMatch(/paid plan/);
    expect(permissionMessage({})).toMatch(/permission/);
  });

  it("reads the interceptor's flags from a 403 body", () => {
    const dead = toDeadEnd(errorSignalFrom({ status: 403, viewOnly: true, error: "View only" }, ""));
    expect(dead?.message).toMatch(/view-only/);
  });
});

describe("helpers", () => {
  it("parses statuses and retry hints out of messages", () => {
    expect(parseHttpStatus("Code assistant request failed (HTTP 423).")).toBe(423);
    expect(parseHttpStatus("no status")).toBeUndefined();
    expect(parseRetryAfterSeconds("Please try again in 4s")).toBe(4);
    expect(parseRetryAfterSeconds("retry after 99999 seconds")).toBe(3600);
    expect(parseRetryAfterSeconds("try again later")).toBeNull();
  });

  it("counts down to the retry time and labels the button", () => {
    expect(secondsUntil(10_500, 10_000)).toBe(1);
    expect(secondsUntil(10_000, 12_000)).toBe(0);
    expect(retryButtonLabel(7)).toBe("Try again in 7 s");
    expect(retryButtonLabel(0)).toBe("Try again");
  });
});

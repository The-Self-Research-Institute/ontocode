export type DeadEndCode =
  | "REVISION_STALE"
  | "RATE_LIMITED"
  | "UNAUTHORIZED"
  | "FORBIDDEN"
  | "SESSION_NOT_FOUND"
  | "RECOVERY_REQUIRED"
  | "PROJECT_RECOVERY_LOCKED";

export type DeadEndAction =
  | { kind: "resubmit"; label: string }
  | { kind: "retry-after"; seconds: number | null }
  | { kind: "sign-in"; label: string }
  | { kind: "explain" }
  | { kind: "recovery" };

export interface DeadEnd {
  code: DeadEndCode;
  message: string;
  action: DeadEndAction;
}

export interface ErrorSignal {
  errorCode?: string;
  status?: number;
  retryAfterSeconds?: number;
  message: string;
  viewOnly?: boolean;
  draftAllowed?: boolean;
  requiresUpgrade?: boolean;
}

const DEAD_END_CODES: ReadonlySet<string> = new Set<DeadEndCode>([
  "REVISION_STALE",
  "RATE_LIMITED",
  "UNAUTHORIZED",
  "FORBIDDEN",
  "SESSION_NOT_FOUND",
  "RECOVERY_REQUIRED",
  "PROJECT_RECOVERY_LOCKED",
]);

const MAX_RETRY_AFTER_SECONDS = 3600;

function readFiniteNumber(value: unknown): number | undefined {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim() && Number.isFinite(Number(value))) return Number(value);
  return undefined;
}

function readText(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value : undefined;
}

export function errorSignalFrom(value: unknown, fallbackMessage: string): ErrorSignal {
  if (!value || typeof value !== "object") return { message: fallbackMessage };
  const rec = value as Record<string, unknown>;
  return {
    errorCode: readText(rec.errorCode),
    status: readFiniteNumber(rec.status) ?? readFiniteNumber(rec.httpStatus),
    retryAfterSeconds: readFiniteNumber(rec.retryAfterSeconds),
    message: readText(rec.message) ?? readText(rec.reason) ?? readText(rec.error) ?? fallbackMessage,
    viewOnly: rec.viewOnly === true,
    draftAllowed: rec.draftAllowed === true,
    requiresUpgrade: rec.requiresUpgrade === true,
  };
}

export function parseHttpStatus(message: string): number | undefined {
  const match = message.match(/\(HTTP (\d{3})\)/);
  return match ? Number(match[1]) : undefined;
}

export function parseRetryAfterSeconds(message: string): number | null {
  const match = message.match(/(?:retry|try again)\D{0,20}?(\d+(?:\.\d+)?)\s*(?:s\b|secs?\b|seconds?\b)/i);
  if (!match) return null;
  const seconds = Math.ceil(Number(match[1]));
  return seconds > 0 ? Math.min(seconds, MAX_RETRY_AFTER_SECONDS) : null;
}

export function resolveDeadEndCode(signal: ErrorSignal): DeadEndCode | null {
  if (signal.errorCode) {
    return DEAD_END_CODES.has(signal.errorCode) ? (signal.errorCode as DeadEndCode) : null;
  }
  const status = signal.status ?? parseHttpStatus(signal.message);
  if (status === 401) return "UNAUTHORIZED";
  if (status === 403) return "FORBIDDEN";
  if (status === 423) return "PROJECT_RECOVERY_LOCKED";
  if (status === 429 || /^rate limit reached/i.test(signal.message.trim())) return "RATE_LIMITED";
  return null;
}

function retryAfterFor(signal: ErrorSignal): number | null {
  if (signal.retryAfterSeconds !== undefined && signal.retryAfterSeconds > 0) {
    return Math.min(Math.ceil(signal.retryAfterSeconds), MAX_RETRY_AFTER_SECONDS);
  }
  return parseRetryAfterSeconds(signal.message);
}

export function permissionMessage(signal: Pick<ErrorSignal, "viewOnly" | "draftAllowed" | "requiresUpgrade">): string {
  if (signal.draftAllowed) {
    return "In this project your changes go through a draft. Switch to your draft copy, ask again there, and raise a pull request when you're done.";
  }
  if (signal.viewOnly) {
    return "You have view-only access to this project, so the assistant can't propose or apply changes here. Ask the project owner for edit access.";
  }
  if (signal.requiresUpgrade) {
    return "Making changes in this project needs a paid plan. Upgrade to Pro, or ask the project owner to make the change.";
  }
  return "You don't have permission to do this in this project. If you think you should, ask the project owner to check your role or plan.";
}

export function toDeadEnd(signal: ErrorSignal): DeadEnd | null {
  const code = resolveDeadEndCode(signal);
  if (code === null) return null;
  switch (code) {
    case "REVISION_STALE":
      return {
        code,
        message: "The project changed while the assistant was working, so this answer could be based on an older version.",
        action: { kind: "resubmit", label: "Ask again with the latest version" },
      };
    case "RATE_LIMITED":
      return {
        code,
        message: "Too many assistant requests in a short time. Wait a moment, then try again.",
        action: { kind: "retry-after", seconds: retryAfterFor(signal) },
      };
    case "UNAUTHORIZED":
      return {
        code,
        message: "Your sign-in has expired or is no longer valid, so the assistant can't reach this project.",
        action: { kind: "sign-in", label: "Sign in again" },
      };
    case "FORBIDDEN":
      return { code, message: permissionMessage(signal), action: { kind: "explain" } };
    case "SESSION_NOT_FOUND":
      return {
        code,
        message: "This assistant session expired or couldn't be found.",
        action: { kind: "resubmit", label: "Ask again" },
      };
    case "RECOVERY_REQUIRED":
      return {
        code,
        message: "A change didn't finish cleanly, so the project may be inconsistent. Check the notice above before continuing.",
        action: { kind: "recovery" },
      };
    case "PROJECT_RECOVERY_LOCKED":
      return {
        code,
        message: "The project is locked until an unfinished change is checked. See the notice above.",
        action: { kind: "recovery" },
      };
  }
}

export function isRecoverySignal(signal: ErrorSignal): boolean {
  const code = resolveDeadEndCode(signal);
  return code === "RECOVERY_REQUIRED" || code === "PROJECT_RECOVERY_LOCKED";
}

export function secondsUntil(retryAt: number, now: number): number {
  return Math.max(0, Math.ceil((retryAt - now) / 1000));
}

export function retryButtonLabel(secondsLeft: number): string {
  return secondsLeft > 0 ? `Try again in ${secondsLeft} s` : "Try again";
}

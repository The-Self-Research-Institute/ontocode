import type { AssistantBudget } from "./codeAssistantSessionTypes";

export type AssistantErrorCode =
  | "BUDGET_EXHAUSTED"
  | "NOT_SELECT_ONLY"
  | "ROW_CAP_EXCEEDED"
  | "BYTE_CAP_EXCEEDED"
  | "TIMEOUT"
  | "QUERY_ERROR"
  | "STALE_GROUP"
  | "CONFLICT"
  | "VALIDATION_FAILED"
  | "APPLY_FAILED"
  | "REVISION_STALE"
  | "RECOVERY_REQUIRED"
  | "SESSION_NOT_FOUND"
  | "PROJECT_RECOVERY_LOCKED"
  | "RATE_LIMITED"
  | "IDEMPOTENCY_KEY_REUSED"
  | "PROVIDER_UNAVAILABLE"
  | "UNAUTHORIZED"
  | "FORBIDDEN"
  | "PROPOSAL_NOT_FOUND";

export interface AssistantApiErrorDetails {
  status?: number;
  retryAfterSeconds?: number;
  recoveryLocked?: boolean;
  viewOnly?: boolean;
  draftAllowed?: boolean;
  requiresUpgrade?: boolean;
}

export class AssistantApiError extends Error {
  readonly status?: number;
  readonly retryAfterSeconds?: number;
  readonly recoveryLocked?: boolean;
  readonly viewOnly?: boolean;
  readonly draftAllowed?: boolean;
  readonly requiresUpgrade?: boolean;

  constructor(
    message: string,
    readonly errorCode?: AssistantErrorCode,
    readonly budget?: AssistantBudget,
    details: AssistantApiErrorDetails = {},
  ) {
    super(message);
    this.name = "AssistantApiError";
    this.status = details.status;
    this.retryAfterSeconds = details.retryAfterSeconds;
    this.recoveryLocked = details.recoveryLocked;
    this.viewOnly = details.viewOnly;
    this.draftAllowed = details.draftAllowed;
    this.requiresUpgrade = details.requiresUpgrade;
  }
}

export const DEAD_END_ERROR_CODES: ReadonlySet<AssistantErrorCode> = new Set<AssistantErrorCode>([
  "REVISION_STALE",
  "RECOVERY_REQUIRED",
  "PROJECT_RECOVERY_LOCKED",
  "SESSION_NOT_FOUND",
  "UNAUTHORIZED",
  "FORBIDDEN",
  "RATE_LIMITED",
]);

export function isDeadEndErrorCode(code: AssistantErrorCode | undefined): boolean {
  return code !== undefined && DEAD_END_ERROR_CODES.has(code);
}

export interface ErrorEnvelope {
  ok?: false;
  errorCode?: AssistantErrorCode;
  message?: string;
  error?: string;
  budget?: AssistantBudget;
  retryAfterSeconds?: number;
  recoveryLocked?: boolean;
  viewOnly?: boolean;
  draftAllowed?: boolean;
  requiresUpgrade?: boolean;
}

function parseRetryAfter(res: Response, envelope: ErrorEnvelope | null): number | undefined {
  const fromBody = envelope?.retryAfterSeconds;
  if (typeof fromBody === "number" && Number.isFinite(fromBody) && fromBody >= 0) return Math.ceil(fromBody);
  const header = typeof res.headers?.get === "function" ? res.headers.get("Retry-After") : null;
  if (!header) return undefined;
  const trimmed = header.trim();
  if (/^\d+(\.\d+)?$/.test(trimmed)) return Math.ceil(Number(trimmed));
  const date = Date.parse(trimmed);
  if (Number.isNaN(date)) return undefined;
  return Math.max(0, Math.ceil((date - Date.now()) / 1000));
}

function isSessionScopedPath(path: string): boolean {
  return /\/api\/v1\/code-assistant\/sessions\/[^/]+/.test(path);
}

function envelopeMessage(envelope: ErrorEnvelope | null): string | undefined {
  if (typeof envelope?.message === "string" && envelope.message.trim()) return envelope.message.trim();
  if (typeof envelope?.error === "string" && envelope.error.trim()) return envelope.error.trim();
  return undefined;
}

export function toAssistantApiError(res: Response, data: unknown, path: string): AssistantApiError {
  const envelope = data && typeof data === "object" ? (data as ErrorEnvelope) : null;
  const status = res.status;
  const bodyCode = envelope?.errorCode;
  const bodyMessage = envelopeMessage(envelope);
  const details: AssistantApiErrorDetails = { status };

  if (status === 401) {
    return new AssistantApiError(
      bodyMessage ?? "Your sign-in is missing or has expired. Sign in again to use the assistant.",
      "UNAUTHORIZED",
      envelope?.budget,
      details,
    );
  }
  if (status === 403) {
    return new AssistantApiError(
      bodyMessage ?? "You don't have permission to do this in this project.",
      bodyCode ?? "FORBIDDEN",
      envelope?.budget,
      {
        ...details,
        viewOnly: envelope?.viewOnly === true,
        draftAllowed: envelope?.draftAllowed === true,
        requiresUpgrade: envelope?.requiresUpgrade === true,
      },
    );
  }
  if (status === 404 && isSessionScopedPath(path)) {
    return new AssistantApiError(
      bodyMessage ?? "This assistant session no longer exists. Start a new request.",
      bodyCode ?? "SESSION_NOT_FOUND",
      envelope?.budget,
      details,
    );
  }
  if (status === 423) {
    return new AssistantApiError(
      bodyMessage ?? "This project is locked for recovery after a failed apply. Restore or clear it before making changes.",
      "PROJECT_RECOVERY_LOCKED",
      envelope?.budget,
      { ...details, recoveryLocked: true },
    );
  }
  if (status === 429) {
    const retryAfterSeconds = parseRetryAfter(res, envelope);
    const wait = retryAfterSeconds !== undefined ? ` Try again in about ${retryAfterSeconds}s.` : " Try again shortly.";
    return new AssistantApiError(
      bodyMessage ?? `Too many assistant requests.${wait}`,
      "RATE_LIMITED",
      envelope?.budget,
      { ...details, retryAfterSeconds },
    );
  }
  return new AssistantApiError(
    bodyMessage ?? `Code assistant request failed (HTTP ${status}).`,
    bodyCode,
    envelope?.budget,
    { ...details, recoveryLocked: envelope?.recoveryLocked === true ? true : undefined },
  );
}

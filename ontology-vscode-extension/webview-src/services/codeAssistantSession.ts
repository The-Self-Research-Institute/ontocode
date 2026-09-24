import type { CodeAssistantAction } from "../components/CodeAssistantPanel";

export interface AssistantSnapshot {
  projectId: string;
  documentPath: string;
  revision: number;
  actionType: CodeAssistantAction;
}

export interface AssistantBudget {
  retrievalCallsRemaining: number;
  maxRetrievalCalls: number;
}

export interface AssistantSession {
  sessionId: string;
  snapshot: AssistantSnapshot;
  budget: AssistantBudget;
  expiresAt: string;
}

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
  | "FORBIDDEN";

export interface AssistantApiErrorDetails {
  status?: number;
  retryAfterSeconds?: number;
  recoveryLocked?: boolean;
}

export class AssistantApiError extends Error {
  readonly status?: number;
  readonly retryAfterSeconds?: number;
  readonly recoveryLocked?: boolean;

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

export interface ReadContextTarget {
  type: "identifier" | "range";
  value: string;
}

export interface ReadContextResultItem {
  source: string;
  range?: unknown;
  text: string;
  kind: string;
}

export interface ReadContextResult {
  ok: true;
  result: { items: ReadContextResultItem[] };
  provenance: { revision: number; coverage: "partial" | "complete" };
  budget: AssistantBudget;
}

export interface SparqlResult {
  ok: true;
  result: { rows: unknown[]; truncated: boolean; rowCount: number };
  provenance: { revision: number; coverage?: "partial" | "complete" };
}

export interface ProposedEditGroupInput {
  clientGroupId: string;
  edits: Array<{ targetPath: string; range: unknown; originalText: string; newText: string }>;
}

export interface ProposedEditGroupResult {
  clientGroupId: string;
  serverGroupId: string;
  validation: { passed: boolean; checks: Array<{ name: string; passed: boolean }> };
  diff: Array<{ targetPath: string; before: string; after: string }>;
}

export interface ProposeResult {
  ok: true;
  groups: ProposedEditGroupResult[];
}

export interface ApplyResult {
  ok: true;
  applied: true;
  newRevision: number;
  remappedPendingGroups: Array<{ serverGroupId: string; remapped: boolean }>;
}

interface ErrorEnvelope {
  ok?: false;
  errorCode?: AssistantErrorCode;
  message?: string;
  error?: string;
  budget?: AssistantBudget;
  retryAfterSeconds?: number;
  recoveryLocked?: boolean;
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
      details,
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

async function postJson<T>(
  apiBaseUrl: string,
  path: string,
  token: string | undefined,
  body: unknown,
  signal?: AbortSignal,
): Promise<T> {
  const res = await fetch(`${apiBaseUrl}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    signal,
  });

  const data = await res.json().catch(() => null);
  if (!res.ok || (data && (data as ErrorEnvelope).ok === false)) {
    throw toAssistantApiError(res, data, path);
  }
  return data as T;
}

export async function createAssistantSession(
  apiBaseUrl: string,
  token: string | undefined,
  input: { projectId: string; documentPath: string; actionType: CodeAssistantAction; actionContext: string },
  signal?: AbortSignal,
): Promise<AssistantSession> {
  return postJson<AssistantSession>(apiBaseUrl, "/api/v1/code-assistant/sessions", token, input, signal);
}

export async function readContext(
  apiBaseUrl: string,
  token: string | undefined,
  sessionId: string,
  input: { targets: ReadContextTarget[]; kind: "definitions" | "diagnostics" | "references" },
  signal?: AbortSignal,
): Promise<ReadContextResult> {
  return postJson<ReadContextResult>(
    apiBaseUrl,
    `/api/v1/code-assistant/sessions/${encodeURIComponent(sessionId)}/tools/read_context`,
    token,
    input,
    signal,
  );
}

export async function runSparql(
  apiBaseUrl: string,
  token: string | undefined,
  sessionId: string,
  query: string,
  signal?: AbortSignal,
): Promise<SparqlResult> {
  return postJson<SparqlResult>(
    apiBaseUrl,
    `/api/v1/code-assistant/sessions/${encodeURIComponent(sessionId)}/tools/run_sparql`,
    token,
    { query },
    signal,
  );
}

export async function proposeEditGroups(
  apiBaseUrl: string,
  token: string | undefined,
  sessionId: string,
  groups: ProposedEditGroupInput[],
  signal?: AbortSignal,
): Promise<ProposeResult> {
  return postJson<ProposeResult>(
    apiBaseUrl,
    `/api/v1/code-assistant/sessions/${encodeURIComponent(sessionId)}/propose`,
    token,
    { groups },
    signal,
  );
}

export async function applyEditGroup(
  apiBaseUrl: string,
  token: string | undefined,
  sessionId: string,
  serverGroupId: string,
  signal?: AbortSignal,
): Promise<ApplyResult> {
  return postJson<ApplyResult>(
    apiBaseUrl,
    `/api/v1/code-assistant/sessions/${encodeURIComponent(sessionId)}/groups/${encodeURIComponent(serverGroupId)}/apply`,
    token,
    {},
    signal,
  );
}

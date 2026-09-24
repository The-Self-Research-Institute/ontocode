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
  | "RECOVERY_REQUIRED";

export class AssistantApiError extends Error {
  constructor(message: string, readonly errorCode?: AssistantErrorCode, readonly budget?: AssistantBudget) {
    super(message);
  }
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
  ok: false;
  errorCode?: AssistantErrorCode;
  message?: string;
  budget?: AssistantBudget;
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
    const err = data as ErrorEnvelope | null;
    throw new AssistantApiError(
      err?.message ?? `Code assistant request failed (HTTP ${res.status}).`,
      err?.errorCode,
      err?.budget,
    );
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

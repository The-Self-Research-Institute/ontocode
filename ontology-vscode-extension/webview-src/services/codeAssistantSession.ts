import { assistantAuthHeaders } from "./codeAssistantAuthHeaders";
import { getStoredModel, getStoredProvider } from "./LlmInsightsService";
import type { CodeAssistantAction } from "../components/CodeAssistantPanel";
import type {
  AssistantSession,
  ApplyResult,
  ProposedEditGroupInput,
  ProposeResult,
  ReadContextResult,
  ReadContextTarget,
  SparqlResult,
} from "./codeAssistantSessionTypes";
import { newIdempotencyKey, postJson, type IdempotentCallOptions } from "./codeAssistantSessionHttp";
import { AssistantApiError } from "./codeAssistantSessionErrors";
import { delay } from "./codeAssistantProviderHttp";

export type {
  AssistantSnapshot,
  AssistantBudget,
  AssistantSession,
  ReadContextTarget,
  ReadContextResultItem,
  ReadContextResult,
  SparqlResult,
  ProposedEdit,
  RenameIdentifierOperation,
  ProposedEditGroupInput,
  ProposedDiffEntry,
  ProposedEditGroupResult,
  ProposeResult,
  AppliedRange,
  ApplyResult,
} from "./codeAssistantSessionTypes";
export type { AssistantErrorCode, AssistantApiErrorDetails } from "./codeAssistantSessionErrors";
export { AssistantApiError, DEAD_END_ERROR_CODES, isDeadEndErrorCode, toAssistantApiError } from "./codeAssistantSessionErrors";
export type { IdempotentCallOptions } from "./codeAssistantSessionHttp";
export { newIdempotencyKey } from "./codeAssistantSessionHttp";

export interface CreateAssistantSessionInput {
  projectId: string;
  documentPath: string;
  actionType: CodeAssistantAction;
  actionContext: string;
  provider?: string;
  model?: string;
}

export async function createAssistantSession(
  apiBaseUrl: string,
  token: string | undefined,
  input: CreateAssistantSessionInput,
  signal?: AbortSignal,
  options: IdempotentCallOptions = {},
): Promise<AssistantSession> {
  const body: CreateAssistantSessionInput = {
    ...input,
    provider: input.provider ?? getStoredProvider(),
    model: input.model ?? getStoredModel(),
  };
  return postJson<AssistantSession>(
    apiBaseUrl,
    "/api/v1/code-assistant/sessions",
    token,
    body,
    signal,
    options.idempotencyKey ?? newIdempotencyKey(),
  );
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
  options: IdempotentCallOptions = {},
): Promise<ProposeResult> {
  return postJson<ProposeResult>(
    apiBaseUrl,
    `/api/v1/code-assistant/sessions/${encodeURIComponent(sessionId)}/propose`,
    token,
    { groups },
    signal,
    options.idempotencyKey ?? newIdempotencyKey(),
  );
}

export interface AssistantUsageReport {
  provider: string;
  model: string;
  latencyMs: number;
  inputTokens?: number;
  outputTokens?: number;
  cacheReadTokens?: number;
  cacheWriteTokens?: number;
}

export function reportAssistantUsage(
  apiBaseUrl: string,
  token: string | undefined,
  sessionId: string,
  usage: AssistantUsageReport,
): void {
  try {
    const body: AssistantUsageReport = { provider: usage.provider, model: usage.model, latencyMs: usage.latencyMs };
    for (const key of ["inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens"] as const) {
      if (typeof usage[key] === "number") body[key] = usage[key];
    }
    const pending = fetch(`${apiBaseUrl}/api/v1/code-assistant/sessions/${encodeURIComponent(sessionId)}/usage`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...assistantAuthHeaders(token) },
      body: JSON.stringify(body),
      keepalive: true,
    });
    Promise.resolve(pending).catch(() => undefined);
  } catch {
    return;
  }
}

const APPLY_IN_FLIGHT_POLL_MS = 2_000;
const APPLY_IN_FLIGHT_MAX_WAIT_MS = 120_000;

function isApplyStillRunning(e: unknown): boolean {
  return e instanceof AssistantApiError && e.errorCode === "IDEMPOTENCY_KEY_REUSED" && e.status === 409;
}

export async function applyEditGroup(
  apiBaseUrl: string,
  token: string | undefined,
  sessionId: string,
  serverGroupId: string,
  signal?: AbortSignal,
  options: IdempotentCallOptions & { summary?: string } = {},
): Promise<ApplyResult> {
  const path = `/api/v1/code-assistant/sessions/${encodeURIComponent(sessionId)}/groups/${encodeURIComponent(serverGroupId)}/apply`;
  const key = options.idempotencyKey ?? newIdempotencyKey();
  const body = options.summary ? { summary: options.summary } : {};
  const deadline = Date.now() + APPLY_IN_FLIGHT_MAX_WAIT_MS;
  for (;;) {
    try {
      return await postJson<ApplyResult>(apiBaseUrl, path, token, body, signal, key);
    } catch (e) {
      if (!isApplyStillRunning(e) || Date.now() >= deadline || signal?.aborted) throw e;
      await delay(APPLY_IN_FLIGHT_POLL_MS, signal);
    }
  }
}

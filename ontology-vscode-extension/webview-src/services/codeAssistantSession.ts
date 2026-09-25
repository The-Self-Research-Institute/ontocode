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
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(body),
      keepalive: true,
    });
    Promise.resolve(pending).catch(() => undefined);
  } catch {
    return;
  }
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

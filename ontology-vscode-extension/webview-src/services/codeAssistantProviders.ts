import {
  getStoredApiKey,
  getStoredMaxResponseTokens,
  getStoredModel,
  getStoredProvider,
  LlmConfigError,
  LlmProvider,
} from "./LlmInsightsService";
import { estimateTokensFromChars, requestBudgetFor, type RequestBudget } from "./codeAssistantBudget";
import { compactOlderToolResults } from "./codeAssistantCompaction";
import {
  ProviderProtocolError,
  type ConversationState,
  type HistoryTurn,
  type NextTurn,
  type ToolDefinition,
} from "./codeAssistantProviderTypes";
import { buildRequestBody, providerEndpoint, startConversation } from "./codeAssistantProviderRequest";
import { parseProviderUsage, parseTurn } from "./codeAssistantProviderResponse";
import {
  delay,
  managedTarget,
  mapHttpError,
  mapManagedHttpError,
  nowMs,
  type ManagedProviderCall,
  type ProviderTarget,
} from "./codeAssistantProviderHttp";

export type {
  JsonSchema,
  ToolDefinition,
  ToolCallRequest,
  AssistantTurn,
  ToolResultProvenance,
  ToolResultForModel,
  ConversationState,
  HistoryTurn,
  ProviderUsage,
  NextTurn,
} from "./codeAssistantProviderTypes";
export { ProviderProtocolError } from "./codeAssistantProviderTypes";
export { parseProviderUsage } from "./codeAssistantProviderResponse";
export type { ManagedProviderCall } from "./codeAssistantProviderHttp";
export { managedProviderCallPath } from "./codeAssistantProviderHttp";

const RETRYABLE_STATUSES = new Set([503]);
const MAX_TRANSIENT_RETRIES = 2;
const RETRY_BASE_DELAY_MS = 1000;
const MAX_REQUEST_CHARS = 1_200_000;

interface RequestSize {
  chars: number;
  estimatedTokens: number;
}

function measureRequest(body: unknown): RequestSize {
  const chars = JSON.stringify(body).length;
  return { chars, estimatedTokens: estimateTokensFromChars(chars) };
}

function fitsRequestBudget(size: RequestSize, budget: RequestBudget): boolean {
  return size.chars <= MAX_REQUEST_CHARS && size.estimatedTokens <= budget.inputLimit;
}

function requestTooLargeError(provider: LlmProvider, model: string, size: RequestSize, budget: RequestBudget): ProviderProtocolError {
  return new ProviderProtocolError(
    `This conversation has grown too large to send to ${provider} (${model}): about ${size.estimatedTokens} tokens estimated, ` +
      `but the model's ${budget.contextWindow}-token context leaves room for about ${budget.inputLimit} after reserving ` +
      `${budget.outputReserve} for the response. Start a new request for a fresh, smaller context.`,
  );
}

export interface FittedRequest {
  conversation: ConversationState;
  body: Record<string, unknown>;
  compactedRounds: number;
}

export function fitConversationToBudget(conversation: ConversationState, model: string, tools: ToolDefinition[]): FittedRequest {
  const budget = requestBudgetFor(conversation.provider, model, getStoredMaxResponseTokens());
  const body = buildRequestBody(conversation, model, tools);
  if (fitsRequestBudget(measureRequest(body), budget)) return { conversation, body, compactedRounds: 0 };

  const { messages, compactedRounds } = compactOlderToolResults(conversation.provider, conversation.nativeMessages);
  const compacted: ConversationState = compactedRounds > 0 ? { ...conversation, nativeMessages: messages } : conversation;
  const compactedBody = compactedRounds > 0 ? buildRequestBody(compacted, model, tools) : body;
  const size = measureRequest(compactedBody);
  if (!fitsRequestBudget(size, budget)) throw requestTooLargeError(conversation.provider, model, size, budget);
  return { conversation: compacted, body: compactedBody, compactedRounds };
}

export async function startAssistantConversation(
  systemPrompt: string,
  userMessage: string,
  history: HistoryTurn[] = [],
  provider?: LlmProvider,
): Promise<ConversationState> {
  return startConversation(provider ?? getStoredProvider(), systemPrompt, history, userMessage);
}

export async function requestNextTurn(
  inputConversation: ConversationState,
  tools: ToolDefinition[],
  signal?: AbortSignal,
  onRetry?: (attempt: number, maxAttempts: number, status: number) => void,
  managed?: ManagedProviderCall,
): Promise<NextTurn> {
  let key = "";
  if (!managed) {
    key = getStoredApiKey();
    if (!key) throw new LlmConfigError("No API key configured. Configure an AI provider to use the assistant.");
  }
  const model = managed ? managed.model : getStoredModel();

  const { conversation, body } = fitConversationToBudget(inputConversation, model, tools);
  const target: ProviderTarget = managed
    ? managedTarget(managed, body)
    : { ...providerEndpoint(conversation.provider, model, key), payload: JSON.stringify(body) };

  let res: Response;
  let attempt = 0;
  let startedAt = nowMs();
  while (true) {
    startedAt = nowMs();
    res = await fetch(target.url, { method: "POST", headers: target.headers, body: target.payload, signal });
    if (res.ok || !RETRYABLE_STATUSES.has(res.status) || attempt >= MAX_TRANSIENT_RETRIES) break;
    attempt += 1;
    onRetry?.(attempt, MAX_TRANSIENT_RETRIES, res.status);
    await delay(RETRY_BASE_DELAY_MS * attempt, signal);
  }
  if (!res.ok) {
    throw target.path !== undefined
      ? await mapManagedHttpError(conversation.provider, res, target.path)
      : await mapHttpError(conversation.provider, res);
  }

  const raw = await res.json().catch(() => {
    throw new ProviderProtocolError(`${conversation.provider} returned a response that could not be parsed as JSON.`);
  });

  return parseTurn(conversation, raw, {
    provider: conversation.provider,
    model,
    latencyMs: Math.max(0, Math.round(nowMs() - startedAt)),
    ...parseProviderUsage(conversation.provider, raw),
  });
}

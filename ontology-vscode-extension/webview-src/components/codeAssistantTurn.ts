import { createAssistantSession } from "../services/codeAssistantSession";
import { runAssistantLoop, type ContextEvent, type LoopOutcome, type LoopStageEvent } from "../services/codeAssistantLoop";
import { getProviderConfig, type ProviderConfig } from "../services/codeAssistantProviderConfig";
import type { ProviderUsage } from "../services/codeAssistantProviders";
import type { DeadEnd } from "../services/codeAssistantDeadEnd";
import type { GroupDecision } from "./CodeAssistantReviewGroups";
import { buildSystemPrompt, getApiBaseUrl, type CodeAssistantAction } from "./codeAssistantPanelHelpers";
import { nextEntryId, type ChatEntry, type PromptToRetry } from "./codeAssistantChatEntries";

export interface TurnRequest {
  token?: string;
  projectId: string;
  documentPath?: string;
  action: CodeAssistantAction;
  actionContext: Parameters<typeof createAssistantSession>[2]["actionContext"];
  loopText: string;
  history: Parameters<typeof runAssistantLoop>[5];
  signal: AbortSignal;
  onStage: (event: LoopStageEvent) => void;
  onProviderConfig: (config: ProviderConfig) => void;
  onDraft?: (text: string) => void;
}

export interface TurnResult {
  outcome: LoopOutcome;
  sessionId: string;
  contextUsed: ContextEvent[];
  usage: ProviderUsage[];
}

export async function runAssistantTurn(request: TurnRequest): Promise<TurnResult | null> {
  const apiBaseUrl = getApiBaseUrl();
  const { token, signal } = request;
  const config = await getProviderConfig(apiBaseUrl, token);
  if (signal.aborted) return null;
  request.onProviderConfig(config);
  const session = await createAssistantSession(
    apiBaseUrl,
    token,
    {
      projectId: request.projectId,
      documentPath: request.documentPath ?? "",
      actionType: request.action,
      actionContext: request.actionContext,
      ...(config.managed ? { provider: config.provider, model: config.model } : {}),
    },
    signal,
  );
  const contextUsed: ContextEvent[] = [];
  const usage: ProviderUsage[] = [];
  const outcome = await runAssistantLoop(
    { apiBaseUrl, token, session, providerConfig: config },
    buildSystemPrompt(request.action, request.documentPath),
    request.loopText,
    request.onStage,
    signal,
    request.history,
    (event) => contextUsed.push(event),
    (event) => usage.push(event),
    request.onDraft,
  );
  return { outcome, sessionId: session.sessionId, contextUsed, usage };
}

export function outcomeToEntry(turn: TurnResult, runProjectId: string): ChatEntry | null {
  const { outcome, contextUsed, usage } = turn;
  if (outcome.kind === "answer") {
    return { id: nextEntryId(), role: "assistant", kind: "answer", text: outcome.text, contextUsed, usage };
  }
  if (outcome.kind !== "propose") return null;
  const decisions: Record<string, GroupDecision> = {};
  outcome.result.groups.forEach((g) => {
    decisions[g.serverGroupId] = g.validation.passed ? "pending" : "failed";
  });
  return {
    id: nextEntryId(),
    role: "assistant",
    kind: "review",
    sessionId: turn.sessionId,
    projectId: runProjectId,
    createdAt: Date.now(),
    explanation: outcome.explanation,
    groups: outcome.result.groups,
    decisions,
    errors: {},
    contextUsed,
    usage,
  };
}

export function deadEndEntry(deadEnd: DeadEnd, prompt: PromptToRetry, startedAt: number): ChatEntry {
  const seconds = deadEnd.action.kind === "retry-after" ? deadEnd.action.seconds : null;
  return {
    id: nextEntryId(),
    role: "assistant",
    kind: "error",
    text: deadEnd.message,
    deadEnd,
    retry: prompt,
    retryAt: seconds ? startedAt + seconds * 1000 : null,
  };
}

export function stoppedAnswerEntry(partial: string): ChatEntry {
  return { id: nextEntryId(), role: "assistant", kind: "answer", text: `${partial}\n\n*Stopped.*`, contextUsed: [], usage: [] };
}

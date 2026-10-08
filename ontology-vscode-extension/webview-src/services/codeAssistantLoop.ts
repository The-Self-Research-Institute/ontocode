import type {
  AssistantTurn,
  ConversationState,
  ToolResultForModel,
  ToolCallRequest,
  HistoryTurn,
  ProviderUsage,
  ManagedProviderCall,
} from "./codeAssistantProviders";
import { startAssistantConversation, requestNextTurn } from "./codeAssistantProviders";
import {
  isDeadEndErrorCode,
  reportAssistantUsage,
  type AssistantErrorCode,
  type ProposeResult,
} from "./codeAssistantSession";
import { ASSISTANT_TOOLS, PROPOSAL_TOOLS } from "./codeAssistantLoopTools";
import { buildToolProvenance } from "./codeAssistantLoopProvenance";
import { describeToolFailure, dispatchToolCall, lowestBudget, type DispatchBudget, type DispatchOutcome, type LoopContext } from "./codeAssistantLoopDispatch";
import { delay } from "./codeAssistantProviderHttp";

export type { HistoryTurn };
export type { LoopContext } from "./codeAssistantLoopDispatch";
export {
  READ_CONTEXT_TOOL,
  RUN_SPARQL_TOOL,
  PROPOSE_EDIT_TOOL,
  PROPOSE_RENAME_TOOL,
  ASSISTANT_TOOLS,
} from "./codeAssistantLoopTools";
export { buildToolProvenance } from "./codeAssistantLoopProvenance";

const MAX_LOOP_ITERATIONS = 12;
const MAX_CALLS_PER_TURN = 8;
const TOOL_CALL_STAGGER_MS = 150;

function managedCallFor(ctx: LoopContext): ManagedProviderCall | undefined {
  const config = ctx.providerConfig;
  if (!config?.managed) return undefined;
  return { apiBaseUrl: ctx.apiBaseUrl, token: ctx.token, sessionId: ctx.session.sessionId, model: config.model };
}

export type LoopOutcome =
  | { kind: "answer"; text: string }
  | { kind: "propose"; result: ProposeResult; explanation?: string }
  | { kind: "stopped"; reason: string; errorCode?: AssistantErrorCode; retryAfterSeconds?: number };

export interface LoopStageEvent {
  stage: "calling-provider" | "calling-tool" | "tool-result" | "answer" | "propose" | "stopped";
  detail?: string;
  step?: number;
  maxSteps?: number;
  budget?: DispatchBudget;
}

export interface ContextEvent {
  tool: string;
  args: Record<string, unknown>;
  result: unknown;
  isError: boolean;
}

const MAX_ARGUMENT_RETRIES = 1;
const MAX_PROPOSAL_VALIDATION_RETRIES = 1;

interface ArgumentRetries {
  left: number;
  retried?: boolean;
  firstExplanation?: string;
}

interface ProposalValidationRetries {
  left: number;
}

function stoppedByDeadEnd(reason: string, outcome: DispatchOutcome): LoopOutcome {
  return {
    kind: "stopped",
    reason,
    errorCode: outcome.errorCode,
    ...(outcome.retryAfterSeconds !== undefined ? { retryAfterSeconds: outcome.retryAfterSeconds } : {}),
  };
}

function notifyUsage(onUsage: ((usage: ProviderUsage) => void) | undefined, usage: ProviderUsage): void {
  if (!onUsage) return;
  try {
    onUsage(usage);
  } catch (e) {
    console.warn("Code assistant usage callback failed", e);
  }
}

interface StepScope {
  ctx: LoopContext;
  signal?: AbortSignal;
  onContext?: (event: ContextEvent) => void;
  emit: (event: LoopStageEvent) => void;
  argumentRetries: ArgumentRetries;
  proposalValidationRetries: ProposalValidationRetries;
  resultFor: (call: ToolCallRequest, result: unknown, isError: boolean, revision?: number) => ToolResultForModel;
}

type StepResult = { outcome: LoopOutcome } | { results: ToolResultForModel[] };

type ToolCallsTurn = Extract<AssistantTurn, { kind: "tool_calls" }>;

function stepScope(
  ctx: LoopContext,
  step: number,
  onStage: (event: LoopStageEvent) => void,
  signal?: AbortSignal,
  onContext?: (event: ContextEvent) => void,
  argumentRetries: ArgumentRetries = { left: 0 },
  proposalValidationRetries: ProposalValidationRetries = { left: 0 },
): StepScope {
  return {
    ctx,
    signal,
    onContext,
    argumentRetries,
    proposalValidationRetries,
    emit: (event) => onStage({ ...event, step, maxSteps: MAX_LOOP_ITERATIONS }),
    resultFor: (call, result, isError, revision) => ({
      toolCallId: call.toolCallId,
      name: call.name,
      result,
      isError,
      provenance: buildToolProvenance(ctx.session, call.name, call.args, revision, step),
    }),
  };
}

function isInvalidArguments(result: unknown): boolean {
  return typeof result === "object" && result !== null && (result as { error?: unknown }).error === "Invalid arguments";
}

async function runProposal(scope: StepScope, proposeCall: ToolCallRequest, explanation?: string): Promise<StepResult> {
  scope.emit({ stage: "calling-tool", detail: proposeCall.name });
  const outcome = await dispatchToolCall(scope.ctx, proposeCall.name, proposeCall.args, scope.signal);
  if (outcome.isError && isInvalidArguments(outcome.result) && scope.argumentRetries.left > 0) {
    scope.argumentRetries.left--;
    if (!scope.argumentRetries.retried) {
      scope.argumentRetries.retried = true;
      scope.argumentRetries.firstExplanation = explanation;
    }
    return { results: [scope.resultFor(proposeCall, outcome.result, true)] };
  }
  const resolved = proposalOutcome(scope, proposeCall, outcome, scope.argumentRetries.retried ? scope.argumentRetries.firstExplanation : explanation);
  return "results" in resolved ? resolved : { outcome: resolved };
}

function failedGroupsSummary(result: ProposeResult): Array<{ clientGroupId: string; failedChecks: Array<{ name: string; detail?: string }> }> {
  return result.groups
    .filter((g) => !g.validation.passed)
    .map((g) => ({
      clientGroupId: g.clientGroupId,
      failedChecks: g.validation.checks.filter((c) => !c.passed).map((c) => ({ name: c.name, detail: c.detail })),
    }));
}

function proposalOutcome(scope: StepScope, proposeCall: ToolCallRequest, outcome: DispatchOutcome, explanation?: string): LoopOutcome | { results: ToolResultForModel[] } {
  if (outcome.isError || !outcome.proposeResult) {
    const detail = describeToolFailure(outcome.result);
    scope.emit({ stage: "stopped", detail: `${proposeCall.name} failed` });
    if (isDeadEndErrorCode(outcome.errorCode)) {
      return stoppedByDeadEnd(detail, outcome);
    }
    return { kind: "stopped", reason: `The proposed edit couldn't be prepared for review: ${detail}`, errorCode: outcome.errorCode };
  }

  const failedGroups = failedGroupsSummary(outcome.proposeResult);
  if (failedGroups.length > 0 && scope.proposalValidationRetries.left > 0) {
    scope.proposalValidationRetries.left--;
    const retryResult = {
      error: "validation_failed",
      groups: failedGroups,
      message: "Fix these edit groups and call propose again — include every occurrence this would leave " +
        "dangling, or explicitly say it should stay.",
    };
    return { results: [scope.resultFor(proposeCall, retryResult, true)] };
  }

  scope.emit({ stage: "propose" });
  return { kind: "propose", result: outcome.proposeResult, explanation };
}

async function runToolCalls(scope: StepScope, calls: ToolCallRequest[]): Promise<StepResult> {
  scope.emit({ stage: "calling-tool", detail: calls.map((c) => c.name).join(", ") });
  const outcomes = await Promise.all(calls.map(async (call, idx) => {
    if (idx > 0) await delay(idx * TOOL_CALL_STAGGER_MS, scope.signal);
    return dispatchToolCall(scope.ctx, call.name, call.args, scope.signal);
  }));
  scope.emit({ stage: "tool-result", detail: calls.map((c) => c.name).join(", "), budget: lowestBudget(outcomes) });
  calls.forEach((call, idx) => {
    scope.onContext?.({ tool: call.name, args: call.args, result: outcomes[idx].result, isError: outcomes[idx].isError });
  });
  const deadEnd = outcomes.find((o) => isDeadEndErrorCode(o.errorCode));
  if (deadEnd) {
    scope.emit({ stage: "stopped", detail: deadEnd.errorCode });
    return { outcome: stoppedByDeadEnd(deadEnd.errorMessage ?? describeToolFailure(deadEnd.result), deadEnd) };
  }
  return {
    results: calls.map((call, idx) => scope.resultFor(call, outcomes[idx].result, outcomes[idx].isError, outcomes[idx].revision)),
  };
}

async function handleToolCallsTurn(scope: StepScope, turn: ToolCallsTurn): Promise<StepResult> {
  const proposalNames = [...new Set(turn.calls.map((c) => c.name).filter((n) => PROPOSAL_TOOLS.has(n)))];
  const hasPropose = proposalNames.length > 0;
  if (hasPropose && turn.calls.length > 1) {
    const label = proposalNames.join(" and ");
    scope.emit({ stage: "stopped", detail: `${label} mixed with other calls` });
    const error = `${label} must be the only tool call in a turn. Call it alone once you're ready to propose changes.`;
    return { results: turn.calls.map((c) => scope.resultFor(c, { error }, true)) };
  }

  if (hasPropose) {
    return runProposal(scope, turn.calls[0], turn.text);
  }

  if (turn.calls.length > MAX_CALLS_PER_TURN) {
    scope.emit({ stage: "stopped", detail: `too many tool calls in one turn (${turn.calls.length})` });
    const error = `Too many tool calls in one turn (${turn.calls.length}). Call at most ${MAX_CALLS_PER_TURN} tools per turn.`;
    return { results: turn.calls.map((c) => scope.resultFor(c, { error }, true)) };
  }

  return runToolCalls(scope, turn.calls);
}

export async function runAssistantLoop(
  ctx: LoopContext,
  systemPrompt: string,
  userMessage: string,
  onStage: (event: LoopStageEvent) => void,
  signal?: AbortSignal,
  history: HistoryTurn[] = [],
  onContext?: (event: ContextEvent) => void,
  onUsage?: (usage: ProviderUsage) => void,
  onDraft?: (text: string) => void,
): Promise<LoopOutcome> {
  const managedCall = managedCallFor(ctx);
  const managedProvider = ctx.providerConfig?.managed ? ctx.providerConfig.provider : undefined;
  let conversation: ConversationState = await startAssistantConversation(systemPrompt, userMessage, history, managedProvider);
  const argumentRetries: ArgumentRetries = { left: MAX_ARGUMENT_RETRIES };
  const proposalValidationRetries: ProposalValidationRetries = { left: MAX_PROPOSAL_VALIDATION_RETRIES };

  for (let i = 0; i < MAX_LOOP_ITERATIONS; i++) {
    const scope = stepScope(ctx, i + 1, onStage, signal, onContext, argumentRetries, proposalValidationRetries);
    scope.emit({ stage: "calling-provider" });
    const onRetry = (attempt: number, maxAttempts: number, status: number) => {
      scope.emit({ stage: "calling-provider", detail: `Provider busy (HTTP ${status}) — retrying ${attempt}/${maxAttempts}...` });
    };
    let draft = "";
    onDraft?.("");
    const onTextDelta = onDraft
      ? (delta: string) => {
          draft += delta;
          onDraft(draft);
        }
      : undefined;
    const { turn, advance, usage } = await requestNextTurn(conversation, ASSISTANT_TOOLS, signal, onRetry, managedCall, onTextDelta);
    if (usage) {
      notifyUsage(onUsage, usage);
      reportAssistantUsage(ctx.apiBaseUrl, ctx.token, ctx.session.sessionId, usage);
    }

    if (turn.kind === "answer") {
      scope.emit({ stage: "answer" });
      return { kind: "answer", text: turn.text };
    }

    const step = await handleToolCallsTurn(scope, turn);
    if ("outcome" in step) return step.outcome;
    conversation = advance(step.results);
  }

  onStage({ stage: "stopped", detail: "max iterations", step: MAX_LOOP_ITERATIONS, maxSteps: MAX_LOOP_ITERATIONS });
  return { kind: "stopped", reason: "The assistant took too many steps without reaching an answer or a proposal." };
}

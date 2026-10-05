import type { MutableRefObject } from "react";
import type { ProviderConfig } from "../services/codeAssistantProviderConfig";
import { errorSignalFrom, toDeadEnd } from "../services/codeAssistantDeadEnd";
import {
  buildConversationHistory,
  describeLoopStage,
  toFriendlyErrorMessage,
  type CodeAssistantAction,
} from "../components/codeAssistantPanelHelpers";
import { withSelection } from "../components/codeSelection";
import { nextEntryId, type PromptToRetry } from "../components/codeAssistantChatEntries";
import { deadEndEntry, outcomeToEntry, runAssistantTurn, type TurnResult } from "../components/codeAssistantTurn";
import type { PanelEditorSelection } from "../components/CodeAssistantPanel";
import type { CodeAssistantEntries } from "./useCodeAssistantEntries";

export interface RunOptions {
  chat: CodeAssistantEntries;
  projectIdRef: MutableRefObject<string | undefined>;
  mountedRef: MutableRefObject<boolean>;
  recoveryLockedRef: MutableRefObject<boolean>;
  noteRecoveryProblem: () => void;
  setInput: (update: (current: string) => string) => void;
  setAction: (action: CodeAssistantAction) => void;
  setProviderConfig: (config: ProviderConfig) => void;
}

export interface TurnInput {
  text: string;
  action: CodeAssistantAction;
  projectId?: string;
  documentPath?: string;
  token?: string;
  selection: PanelEditorSelection | null;
  fromRetry: boolean;
  onSelectionUsed?: () => void;
}

export interface RunContext {
  chat: CodeAssistantEntries;
  optionsRef: MutableRefObject<RunOptions>;
  busyRef: MutableRefObject<boolean>;
  abortControllerRef: MutableRefObject<AbortController | null>;
  chatGenerationRef: MutableRefObject<number>;
  runProjectRef: MutableRefObject<string | undefined>;
  setBusyNow: (value: boolean) => void;
  setStatusText: (text: string) => void;
  setDraftNow: (text: string) => void;
  resetRunUi: () => void;
}

export function reportFailure(ctx: RunContext, source: unknown, fallbackMessage: string, prompt: PromptToRetry) {
  const { chat } = ctx;
  const signal = errorSignalFrom(source, fallbackMessage);
  const deadEnd = toDeadEnd(signal);
  if (!deadEnd) {
    chat.appendError(toFriendlyErrorMessage(signal.message));
    return;
  }
  if (deadEnd.action.kind === "recovery") ctx.optionsRef.current.noteRecoveryProblem();
  const startedAt = Date.now();
  chat.setNow(startedAt);
  chat.commitEntries((prev) => [...prev, deadEndEntry(deadEnd, prompt, startedAt)]);
  ctx.optionsRef.current.setInput((current) => (current.trim() ? current : prompt.text));
  ctx.optionsRef.current.setAction(prompt.action);
}

export function appendOutcome(ctx: RunContext, turn: TurnResult, runProjectId: string, prompt: PromptToRetry) {
  const entry = outcomeToEntry(turn, runProjectId);
  if (entry) {
    ctx.chat.commitEntries((prev) => [...prev, entry]);
    return;
  }
  const { outcome } = turn;
  if (outcome.kind !== "stopped") return;
  if (outcome.errorCode) reportFailure(ctx, outcome, outcome.reason, prompt);
  else ctx.chat.appendError(toFriendlyErrorMessage(outcome.reason));
}

function prepareTurn(ctx: RunContext, turn: TurnInput, runProjectId: string) {
  const { chat } = ctx;
  const { projectIdRef, mountedRef } = ctx.optionsRef.current;
  const prompt: PromptToRetry = { text: turn.text, action: turn.action };
  const { loopText, actionContext } = withSelection(turn.text, turn.selection);
  if (turn.selection) turn.onSelectionUsed?.();
  const runGeneration = ctx.chatGenerationRef.current;
  ctx.runProjectRef.current = runProjectId;
  const stillCurrent = () =>
    projectIdRef.current === runProjectId && ctx.chatGenerationRef.current === runGeneration && mountedRef.current;
  const history = buildConversationHistory(chat.entriesRef.current);
  chat.commitEntries((prev) => [...prev, { id: nextEntryId(), role: "user", text: turn.text, action: turn.action }]);
  ctx.optionsRef.current.setInput((current) => (!turn.fromRetry || current.trim() === turn.text ? "" : current));
  ctx.setBusyNow(true);
  ctx.setStatusText("Starting...");
  return { prompt, loopText, actionContext, stillCurrent, history };
}

export function makeSubmit(ctx: RunContext, mountedRef: MutableRefObject<boolean>) {
  return async (turn: TurnInput) => {
    if (!turn.text || ctx.busyRef.current || ctx.optionsRef.current.recoveryLockedRef.current) return;
    if (!turn.projectId) {
      ctx.chat.appendError("No project is open. Open a project, then try again.");
      return;
    }
    const runProjectId = turn.projectId;
    const { prompt, loopText, actionContext, stillCurrent, history } = prepareTurn(ctx, turn, runProjectId);
    const controller = new AbortController();
    ctx.abortControllerRef.current = controller;
    try {
      const result = await runAssistantTurn({
        token: turn.token,
        projectId: runProjectId,
        documentPath: turn.documentPath,
        action: turn.action,
        actionContext,
        loopText,
        history,
        signal: controller.signal,
        onStage: (event) => ctx.setStatusText(describeLoopStage(event)),
        onDraft: (text) => stillCurrent() && ctx.setDraftNow(text),
        onProviderConfig: (config) => mountedRef.current && ctx.optionsRef.current.setProviderConfig(config),
      });
      if (!result || controller.signal.aborted || !stillCurrent()) return;
      appendOutcome(ctx, result, runProjectId, prompt);
    } catch (e) {
      if (controller.signal.aborted || !stillCurrent()) return;
      reportFailure(ctx, e, "Something went wrong talking to the assistant.", prompt);
    } finally {
      if (ctx.abortControllerRef.current === controller) ctx.resetRunUi();
    }
  };
}

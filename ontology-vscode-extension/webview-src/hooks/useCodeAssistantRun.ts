import { useRef, useState, type MutableRefObject } from "react";
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

interface RunOptions {
  chat: CodeAssistantEntries;
  projectIdRef: MutableRefObject<string | undefined>;
  mountedRef: MutableRefObject<boolean>;
  recoveryLockedRef: MutableRefObject<boolean>;
  noteRecoveryProblem: () => void;
  setInput: (update: (current: string) => string) => void;
  setAction: (action: CodeAssistantAction) => void;
  setProviderConfig: (config: ProviderConfig) => void;
}

interface TurnInput {
  text: string;
  action: CodeAssistantAction;
  projectId?: string;
  documentPath?: string;
  token?: string;
  selection: PanelEditorSelection | null;
  fromRetry: boolean;
  onSelectionUsed?: () => void;
}

export function useCodeAssistantRun(options: RunOptions) {
  const { chat, projectIdRef, mountedRef } = options;
  const [busy, setBusy] = useState(false);
  const [statusText, setStatusText] = useState("");
  const busyRef = useRef(false);
  const abortControllerRef = useRef<AbortController | null>(null);
  const chatGenerationRef = useRef(0);
  const runProjectRef = useRef<string | undefined>(undefined);
  const optionsRef = useRef(options);
  optionsRef.current = options;

  const setBusyNow = (value: boolean) => {
    busyRef.current = value;
    setBusy(value);
  };

  const reportFailure = (source: unknown, fallbackMessage: string, prompt: PromptToRetry) => {
    const signal = errorSignalFrom(source, fallbackMessage);
    const deadEnd = toDeadEnd(signal);
    if (!deadEnd) {
      chat.appendError(toFriendlyErrorMessage(signal.message));
      return;
    }
    if (deadEnd.action.kind === "recovery") optionsRef.current.noteRecoveryProblem();
    const startedAt = Date.now();
    chat.setNow(startedAt);
    chat.commitEntries((prev) => [...prev, deadEndEntry(deadEnd, prompt, startedAt)]);
    optionsRef.current.setInput((current) => (current.trim() ? current : prompt.text));
    optionsRef.current.setAction(prompt.action);
  };

  const appendOutcome = (turn: TurnResult, runProjectId: string, prompt: PromptToRetry) => {
    const entry = outcomeToEntry(turn, runProjectId);
    if (entry) {
      chat.commitEntries((prev) => [...prev, entry]);
      return;
    }
    const { outcome } = turn;
    if (outcome.kind !== "stopped") return;
    if (outcome.errorCode) reportFailure(outcome, outcome.reason, prompt);
    else chat.appendError(toFriendlyErrorMessage(outcome.reason));
  };

  const submit = async (turn: TurnInput) => {
    if (!turn.text || busyRef.current || optionsRef.current.recoveryLockedRef.current) return;
    if (!turn.projectId) {
      chat.appendError("No project is open. Open a project, then try again.");
      return;
    }
    const prompt: PromptToRetry = { text: turn.text, action: turn.action };
    const { loopText, actionContext } = withSelection(turn.text, turn.selection);
    if (turn.selection) turn.onSelectionUsed?.();
    const runProjectId = turn.projectId;
    const runGeneration = chatGenerationRef.current;
    runProjectRef.current = runProjectId;
    const stillCurrent = () =>
      projectIdRef.current === runProjectId && chatGenerationRef.current === runGeneration && mountedRef.current;
    const history = buildConversationHistory(chat.entriesRef.current);
    chat.commitEntries((prev) => [...prev, { id: nextEntryId(), role: "user", text: turn.text, action: turn.action }]);
    optionsRef.current.setInput((current) => (!turn.fromRetry || current.trim() === turn.text ? "" : current));
    setBusyNow(true);
    setStatusText("Starting...");
    const controller = new AbortController();
    abortControllerRef.current = controller;
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
        onStage: (event) => setStatusText(describeLoopStage(event)),
        onProviderConfig: (config) => mountedRef.current && optionsRef.current.setProviderConfig(config),
      });
      if (!result || controller.signal.aborted || !stillCurrent()) return;
      appendOutcome(result, runProjectId, prompt);
    } catch (e) {
      if (controller.signal.aborted || !stillCurrent()) return;
      reportFailure(e, "Something went wrong talking to the assistant.", prompt);
    } finally {
      if (abortControllerRef.current === controller) {
        setBusyNow(false);
        setStatusText("");
      }
    }
  };

  const cancelRun = () => {
    abortControllerRef.current?.abort();
    setBusyNow(false);
    setStatusText("");
  };

  const abandonRun = (): boolean => {
    chatGenerationRef.current += 1;
    const wasRunning = busyRef.current;
    if (wasRunning) {
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
      busyRef.current = false;
      setBusy(false);
      setStatusText("");
    }
    return wasRunning;
  };

  const abandonRunFor = (previousProjectId: string | undefined) =>
    abandonRun() && runProjectRef.current === previousProjectId;

  const abortOnUnmount = () => abortControllerRef.current?.abort();

  return { busy, statusText, submit, cancelRun, abandonRun, abandonRunFor, abortOnUnmount };
}

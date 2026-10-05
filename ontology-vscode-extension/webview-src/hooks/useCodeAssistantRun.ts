import { useRef, useState } from "react";
import { stoppedAnswerEntry } from "../components/codeAssistantTurn";
import { makeSubmit, type RunContext, type RunOptions } from "./codeAssistantRunSteps";

function useRunUiState() {
  const [busy, setBusy] = useState(false);
  const [statusText, setStatusTextRaw] = useState("");
  const [draft, setDraft] = useState("");
  const draftRef = useRef("");
  const busyRef = useRef(false);
  const stageStartedAtRef = useRef<number | null>(null);

  const setBusyNow = (value: boolean) => {
    busyRef.current = value;
    setBusy(value);
  };

  // Each new stage (a new tool call, a new "thinking" round) resets the clock, so the
  // elapsed-time counter next to the status text reflects how long the *current* stage
  // has been running, not the whole turn.
  const setStatusText = (text: string) => {
    stageStartedAtRef.current = text ? Date.now() : null;
    setStatusTextRaw(text);
  };

  const setDraftNow = (text: string) => {
    draftRef.current = text;
    setDraft(text);
  };

  const resetRunUi = () => {
    setBusyNow(false);
    setStatusText("");
    setDraftNow("");
  };

  return {
    busy, statusText, draft, draftRef, busyRef, stageStartedAtRef,
    setBusyNow, setStatusText, setDraftNow, resetRunUi,
  };
}

export function useCodeAssistantRun(options: RunOptions) {
  const { chat, mountedRef } = options;
  const ui = useRunUiState();
  const abortControllerRef = useRef<AbortController | null>(null);
  const chatGenerationRef = useRef(0);
  const runProjectRef = useRef<string | undefined>(undefined);
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const { busyRef, resetRunUi } = ui;

  const ctx: RunContext = {
    chat,
    optionsRef,
    busyRef,
    abortControllerRef,
    chatGenerationRef,
    runProjectRef,
    setBusyNow: ui.setBusyNow,
    setStatusText: ui.setStatusText,
    setDraftNow: ui.setDraftNow,
    resetRunUi,
  };
  const submit = makeSubmit(ctx, mountedRef);

  const cancelRun = () => {
    const partial = ui.draftRef.current.trim();
    abortControllerRef.current?.abort();
    if (partial) chat.commitEntries((prev) => [...prev, stoppedAnswerEntry(partial)]);
    resetRunUi();
  };

  const abandonRun = (): boolean => {
    chatGenerationRef.current += 1;
    const wasRunning = busyRef.current;
    if (wasRunning) {
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
      resetRunUi();
    }
    return wasRunning;
  };

  const abandonRunFor = (previousProjectId: string | undefined) =>
    abandonRun() && runProjectRef.current === previousProjectId;

  const abortOnUnmount = () => abortControllerRef.current?.abort();

  return {
    busy: ui.busy, statusText: ui.statusText, stageStartedAt: ui.stageStartedAtRef.current, draft: ui.draft,
    submit, cancelRun, abandonRun, abandonRunFor, abortOnUnmount,
  };
}

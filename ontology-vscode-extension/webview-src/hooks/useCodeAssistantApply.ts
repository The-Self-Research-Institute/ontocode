import { useRef, useState } from "react";
import type { AppliedChanges } from "../components/codeAssistantChatEntries";
import { applyReviewGroup } from "../components/codeAssistantApplyGroup";
import { makeApplyAllPending, type ApplyOptions } from "./codeAssistantApplyAll";

export function useCodeAssistantApply(options: ApplyOptions) {
  const { chat } = options;
  const [applyBusy, setApplyBusy] = useState(false);
  const applyBusyRef = useRef(false);
  const cancelApplyAllRef = useRef<Set<string>>(new Set());
  const optionsRef = useRef(options);
  optionsRef.current = options;

  const setApplyBusyNow = (value: boolean) => {
    applyBusyRef.current = value;
    setApplyBusy(value);
  };

  const refreshCodeViewIfSameProject = (entryProjectId: string | undefined, changes: AppliedChanges) => {
    if (changes.texts.length === 0 && changes.ranges.length === 0) return;
    if (entryProjectId && entryProjectId !== optionsRef.current.projectIdRef.current) return;
    optionsRef.current.onApplySuccess?.(changes.texts, changes.ranges);
  };

  const applyGroupOnce = (entryId: string, sessionId: string, serverGroupId: string, deferredChanges?: AppliedChanges) =>
    applyReviewGroup(
      {
        chat,
        token: optionsRef.current.tokenRef.current,
        onApplied: refreshCodeViewIfSameProject,
        noteRecoveryProblem: optionsRef.current.noteRecoveryProblem,
      },
      { entryId, sessionId, serverGroupId },
      deferredChanges,
    );

  const applyGroup = async (entryId: string, sessionId: string, serverGroupId: string) => {
    if (applyBusyRef.current || optionsRef.current.blockedReason()) return;
    const entry = chat.findReviewEntry(entryId);
    if (!entry || (entry.decisions[serverGroupId] ?? "pending") !== "pending") return;
    setApplyBusyNow(true);
    try {
      await applyGroupOnce(entryId, sessionId, serverGroupId);
    } finally {
      setApplyBusyNow(false);
    }
  };

  const skipGroup = (entryId: string, serverGroupId: string) => {
    chat.updateReviewEntry(entryId, (e) => ({ ...e, decisions: { ...e.decisions, [serverGroupId]: "skipped" } }));
  };

  const applyAllPending = makeApplyAllPending({
    chat, optionsRef, applyBusyRef, cancelApplyAllRef, setApplyBusyNow, applyGroupOnce, refreshCodeViewIfSameProject,
  });

  const cancelApplyAll = (entryId: string) => {
    cancelApplyAllRef.current.add(entryId);
    chat.updateReviewEntry(entryId, (e) =>
      e.applyAllRun ? { ...e, applyAllRun: { ...e.applyAllRun, cancelRequested: true } } : e,
    );
  };

  return { applyBusy, applyGroup, skipGroup, applyAllPending, cancelApplyAll };
}

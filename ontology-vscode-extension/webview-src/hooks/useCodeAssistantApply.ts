import { useRef, useState, type MutableRefObject } from "react";
import type { AppliedRange } from "../services/codeAssistantSession";
import { buildApplyAllQueue, formatApplyAllSummary, groupLabel, runApplyAll } from "../services/codeAssistantApplyQueue";
import type { AppliedChanges } from "../components/codeAssistantChatEntries";
import { applyReviewGroup } from "../components/codeAssistantApplyGroup";
import type { CodeAssistantEntries } from "./useCodeAssistantEntries";

interface ApplyOptions {
  chat: CodeAssistantEntries;
  tokenRef: MutableRefObject<string | undefined>;
  projectIdRef: MutableRefObject<string | undefined>;
  mountedRef: MutableRefObject<boolean>;
  blockedReason: () => string | null;
  noteRecoveryProblem: () => void;
  onApplySuccess?: (changedTexts: string[], appliedRanges: AppliedRange[]) => void;
}

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

  const applyAllPending = async (entryId: string, sessionId: string) => {
    if (applyBusyRef.current || optionsRef.current.blockedReason()) return;
    const entry = chat.findReviewEntry(entryId);
    if (!entry) return;
    const queue = buildApplyAllQueue(entry.groups, entry.decisions);
    if (queue.length === 0) return;
    const deferredChanges: AppliedChanges = { texts: [], ranges: [] };
    cancelApplyAllRef.current.delete(entryId);
    setApplyBusyNow(true);
    chat.updateReviewEntry(entryId, (e) => ({
      ...e,
      applyAllRun: { running: true, position: 0, total: queue.length, cancelRequested: false },
      applyAllSummary: null,
    }));
    try {
      const report = await runApplyAll(queue, {
        readDecisions: () => chat.findReviewEntry(entryId)?.decisions ?? {},
        applyOne: (serverGroupId) => applyGroupOnce(entryId, sessionId, serverGroupId, deferredChanges),
        isCancelRequested: () => cancelApplyAllRef.current.has(entryId),
        blockedReason: () => {
          if (!optionsRef.current.mountedRef.current) return "the assistant panel was closed";
          if (!chat.findReviewEntry(entryId)) return "the conversation was cleared";
          return optionsRef.current.blockedReason();
        },
        onProgress: (progress) =>
          chat.updateReviewEntry(entryId, (e) => ({
            ...e,
            applyAllRun: { running: true, position: progress.position, total: progress.total, cancelRequested: e.applyAllRun?.cancelRequested ?? false },
          })),
      });
      chat.updateReviewEntry(entryId, (e) => ({
        ...e,
        applyAllRun: null,
        applyAllSummary: formatApplyAllSummary(report, (id) => groupLabel(e.groups, id)),
      }));
    } finally {
      cancelApplyAllRef.current.delete(entryId);
      setApplyBusyNow(false);
      refreshCodeViewIfSameProject(entry.projectId, deferredChanges);
    }
  };

  const cancelApplyAll = (entryId: string) => {
    cancelApplyAllRef.current.add(entryId);
    chat.updateReviewEntry(entryId, (e) =>
      e.applyAllRun ? { ...e, applyAllRun: { ...e.applyAllRun, cancelRequested: true } } : e,
    );
  };

  return { applyBusy, applyGroup, skipGroup, applyAllPending, cancelApplyAll };
}

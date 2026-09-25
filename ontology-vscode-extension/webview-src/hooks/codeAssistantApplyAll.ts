import type { MutableRefObject } from "react";
import type { AppliedRange } from "../services/codeAssistantSession";
import { buildApplyAllQueue, formatApplyAllSummary, groupLabel, runApplyAll, type ApplyOneResult } from "../services/codeAssistantApplyQueue";
import type { AppliedChanges } from "../components/codeAssistantChatEntries";
import type { CodeAssistantEntries } from "./useCodeAssistantEntries";

export interface ApplyOptions {
  chat: CodeAssistantEntries;
  tokenRef: MutableRefObject<string | undefined>;
  projectIdRef: MutableRefObject<string | undefined>;
  mountedRef: MutableRefObject<boolean>;
  blockedReason: () => string | null;
  noteRecoveryProblem: () => void;
  onApplySuccess?: (changedTexts: string[], appliedRanges: AppliedRange[]) => void;
}

export interface ApplyContext {
  chat: CodeAssistantEntries;
  optionsRef: MutableRefObject<ApplyOptions>;
  applyBusyRef: MutableRefObject<boolean>;
  cancelApplyAllRef: MutableRefObject<Set<string>>;
  setApplyBusyNow: (value: boolean) => void;
  applyGroupOnce: (entryId: string, sessionId: string, serverGroupId: string, deferredChanges?: AppliedChanges) => Promise<ApplyOneResult>;
  refreshCodeViewIfSameProject: (entryProjectId: string | undefined, changes: AppliedChanges) => void;
}

function runQueue(ctx: ApplyContext, entryId: string, sessionId: string, queue: string[], deferredChanges: AppliedChanges) {
  const { chat, optionsRef } = ctx;
  return runApplyAll(queue, {
    readDecisions: () => chat.findReviewEntry(entryId)?.decisions ?? {},
    applyOne: (serverGroupId) => ctx.applyGroupOnce(entryId, sessionId, serverGroupId, deferredChanges),
    isCancelRequested: () => ctx.cancelApplyAllRef.current.has(entryId),
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
}

export function makeApplyAllPending(ctx: ApplyContext) {
  const { chat } = ctx;
  return async (entryId: string, sessionId: string) => {
    if (ctx.applyBusyRef.current || ctx.optionsRef.current.blockedReason()) return;
    const entry = chat.findReviewEntry(entryId);
    if (!entry) return;
    const queue = buildApplyAllQueue(entry.groups, entry.decisions);
    if (queue.length === 0) return;
    const deferredChanges: AppliedChanges = { texts: [], ranges: [] };
    ctx.cancelApplyAllRef.current.delete(entryId);
    ctx.setApplyBusyNow(true);
    chat.updateReviewEntry(entryId, (e) => ({
      ...e,
      applyAllRun: { running: true, position: 0, total: queue.length, cancelRequested: false },
      applyAllSummary: null,
    }));
    try {
      const report = await runQueue(ctx, entryId, sessionId, queue, deferredChanges);
      chat.updateReviewEntry(entryId, (e) => ({
        ...e,
        applyAllRun: null,
        applyAllSummary: formatApplyAllSummary(report, (id) => groupLabel(e.groups, id)),
      }));
    } finally {
      ctx.cancelApplyAllRef.current.delete(entryId);
      ctx.setApplyBusyNow(false);
      ctx.refreshCodeViewIfSameProject(entry.projectId, deferredChanges);
    }
  };
}

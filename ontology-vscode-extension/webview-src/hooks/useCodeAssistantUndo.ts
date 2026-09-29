import { useRef, type MutableRefObject } from "react";
import { changeTrackingService, type ChangeSetDirection } from "../services/changeTrackingService";
import { commitOutcome, previewOutcome, type GroupUndoState, type UndoOutcome } from "../services/codeAssistantUndo";
import type { AppliedRange } from "../services/codeAssistantSession";
import type { ReviewEntry } from "../components/codeAssistantChatEntries";
import type { CodeAssistantEntries } from "./useCodeAssistantEntries";

export interface UndoOptions {
  chat: CodeAssistantEntries;
  projectIdRef: MutableRefObject<string | undefined>;
  onApplySuccess?: (changedTexts: string[], appliedRanges: AppliedRange[]) => void;
}

function callChangeSet(projectId: string, changeSetId: string, direction: ChangeSetDirection, dryRun: boolean) {
  return direction === "UNDO"
    ? changeTrackingService.undoChangeSet(projectId, changeSetId, { dryRun })
    : changeTrackingService.redoChangeSet(projectId, changeSetId, { dryRun });
}

function directionFor(state: GroupUndoState | undefined, dryRun: boolean): ChangeSetDirection {
  if (!dryRun && state?.preview) return state.preview.direction;
  return state?.undone ? "REDO" : "UNDO";
}

function announceRevert(projectId: string, changeSetId: string, direction: ChangeSetDirection, auditId?: string | null) {
  window.dispatchEvent(
    new CustomEvent("ontologyRollback", {
      detail: { projectId, changeSetId, direction, auditId, source: "AI", username: "You", originalAuthor: "Ask AI", success: true },
    }),
  );
}

function revertedTexts(entry: ReviewEntry, groupId: string, direction: ChangeSetDirection): string[] {
  const group = entry.groups.find((g) => g.serverGroupId === groupId);
  return (group?.diff ?? []).map((d) => (direction === "UNDO" ? d.before : d.after)).filter((t) => t.trim().length > 0);
}

function statePatch(outcome: UndoOutcome, direction: ChangeSetDirection): Partial<GroupUndoState> {
  if (outcome.kind === "preview") return { busy: false, preview: outcome.preview, error: null };
  if (outcome.kind === "error") return { busy: false, preview: null, error: outcome.message };
  return { undone: direction === "UNDO", busy: false, preview: null, error: null };
}

function afterRevert(options: UndoOptions, entry: ReviewEntry, groupId: string, direction: ChangeSetDirection, auditId?: string | null) {
  const currentProjectId = options.projectIdRef.current;
  const projectId = entry.projectId ?? currentProjectId;
  if (!projectId) return;
  announceRevert(projectId, groupId, direction, auditId);
  if (projectId === currentProjectId) options.onApplySuccess?.(revertedTexts(entry, groupId, direction), []);
}

export function useCodeAssistantUndo(options: UndoOptions) {
  const { chat } = options;
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const inFlightRef = useRef<Set<string>>(new Set());

  const patchState = (entryId: string, groupId: string, patch: Partial<GroupUndoState>) =>
    chat.updateReviewEntry(entryId, (e) => ({
      ...e,
      undo: { ...e.undo, [groupId]: { ...(e.undo?.[groupId] ?? { undone: false }), ...patch } },
    }));

  const finish = (entry: ReviewEntry, groupId: string, direction: ChangeSetDirection, outcome: UndoOutcome, auditId?: string | null) => {
    patchState(entry.id, groupId, statePatch(outcome, direction));
    if (outcome.kind === "done" && outcome.changed) afterRevert(optionsRef.current, entry, groupId, direction, auditId);
  };

  const run = async (entryId: string, groupId: string, dryRun: boolean) => {
    const entry = chat.findReviewEntry(entryId);
    const projectId = entry?.projectId ?? optionsRef.current.projectIdRef.current;
    const key = `${entryId}:${groupId}`;
    if (!entry || !projectId || inFlightRef.current.has(key)) return;
    const state = entry.undo?.[groupId];
    if (!dryRun && !state?.preview) return;
    const direction = directionFor(state, dryRun);
    inFlightRef.current.add(key);
    patchState(entryId, groupId, { busy: true, error: null });
    try {
      const result = await callChangeSet(projectId, groupId, direction, dryRun);
      const outcome = dryRun ? previewOutcome(result, direction) : commitOutcome(result, direction);
      finish(entry, groupId, direction, outcome, result.auditId);
    } catch {
      const verb = direction === "UNDO" ? "undo" : "redo";
      finish(entry, groupId, direction, { kind: "error", message: `Couldn't ${verb} this edit. Try again.` });
    } finally {
      inFlightRef.current.delete(key);
    }
  };

  const cancel = (entryId: string, groupId: string) => patchState(entryId, groupId, { preview: null, error: null });

  return {
    requestPreview: (entryId: string, groupId: string) => run(entryId, groupId, true),
    confirm: (entryId: string, groupId: string) => run(entryId, groupId, false),
    cancel,
  };
}

export type CodeAssistantUndo = ReturnType<typeof useCodeAssistantUndo>;

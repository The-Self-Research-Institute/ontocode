import type { ChangeSetDirection, ChangeSetItem, ChangeSetResult } from "./changeSetService";

export interface UndoPreview {
  direction: ChangeSetDirection;
  count: number;
  skipped: string | null;
}

export interface GroupUndoState {
  undone: boolean;
  busy?: boolean;
  preview?: UndoPreview | null;
  error?: string | null;
}

export type UndoOutcome =
  | { kind: "preview"; preview: UndoPreview }
  | { kind: "done"; changed: boolean }
  | { kind: "error"; message: string };

const FALLBACK_REASON = "It couldn't be reverted";

export function changeCountLabel(count: number): string {
  return `${count} ${count === 1 ? "change" : "changes"}`;
}

export function describeSkipped(items: ChangeSetItem[]): string | null {
  if (items.length === 0) return null;
  const reasons = new Set(items.map((item) => item.reason?.trim() || FALLBACK_REASON));
  return `${items.length} skipped: ${Array.from(reasons).join("; ")}`;
}

function verbs(direction: ChangeSetDirection) {
  return direction === "UNDO" ? { base: "undo", past: "undone" } : { base: "redo", past: "redone" };
}

function failureMessage(result: ChangeSetResult, direction: ChangeSetDirection): string {
  const { base, past } = verbs(direction);
  if (result.status === 403) return `You don't have permission to ${base} this edit.`;
  if (result.status === 404) return "This edit is no longer in history.";
  if (result.status === 409 || (result.status === 200 && result.applied.length === 0)) {
    const skipped = describeSkipped(result.skipped);
    return skipped ? `Nothing can be ${past}. ${skipped}.` : `Nothing can be ${past}.`;
  }
  return `Couldn't ${base} this edit. Try again.`;
}

export function previewOutcome(result: ChangeSetResult, direction: ChangeSetDirection): UndoOutcome {
  if (result.success && result.alreadyReverted) return { kind: "done", changed: false };
  if (!result.success || result.applied.length === 0) return { kind: "error", message: failureMessage(result, direction) };
  return {
    kind: "preview",
    preview: { direction, count: result.applied.length, skipped: describeSkipped(result.skipped) },
  };
}

export function commitOutcome(result: ChangeSetResult, direction: ChangeSetDirection): UndoOutcome {
  if (result.success && result.alreadyReverted) return { kind: "done", changed: false };
  if (!result.success || result.applied.length === 0) return { kind: "error", message: failureMessage(result, direction) };
  return { kind: "done", changed: true };
}

export function sanitizeUndoStates(
  states: Record<string, GroupUndoState> | undefined,
): Record<string, GroupUndoState> | undefined {
  if (!states) return states;
  const transient = Object.values(states).some((s) => s.busy || s.preview || s.error);
  if (!transient) return states;
  return Object.fromEntries(Object.entries(states).map(([id, s]) => [id, { undone: s.undone }]));
}

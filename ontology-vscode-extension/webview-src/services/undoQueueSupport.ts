import undoRedoService, { type QueueResponse, type QueueStep, type StepOutcome, type UndoState, type UndoStep } from './undoRedoService';

export const MAX_WAITING = 25;
export const REQUEST_TIMEOUT_MS = 30000;
export const CHECK_DELAY_MS = 3000;
export const MAX_CHECKS = 10;
export const FINISHED_VISIBLE_MS = 5000;
export const INFLIGHT_WAIT_MS = 1000;
export const MAX_FINISHED_ITEMS = 5;

export type UndoKind = 'undo' | 'redo';
export type UndoRunPhase = 'idle' | 'running' | 'paused' | 'checking' | 'finished';
export type PauseReason = 'Conflict' | 'Result unknown';

export interface FinishedItem {
  label: string;
  changes: number;
}

export interface UndoRunSnapshot {
  phase: UndoRunPhase;
  kind: UndoKind;
  runId: number;
  startedAt: number;
  completed: number;
  changeCount: number;
  running: boolean;
  pending: number;
  current?: string;
  currentDetail?: StepDetail;
  reason?: PauseReason;
  message?: string;
  item?: string;
  summary?: string;
  finishedItems: FinishedItem[];
  lastState?: UndoState;
}

export interface StepDetail {
  source?: string;
  affected?: string;
  restoring?: string;
  entityIRI?: string;
}

export function detailOf(step: QueueStep | undefined): StepDetail | undefined {
  if (!step || !(step.source || step.affected || step.restoring)) return undefined;
  return { source: step.source, affected: step.affected, restoring: step.restoring, entityIRI: step.entityIRI };
}

export interface PressResult {
  accepted: boolean;
  message?: string;
}

export interface QueueApi {
  fetchQueue(projectId: string, count: number): Promise<QueueResponse>;
  send(projectId: string, kind: UndoKind, step: QueueStep, opId: string): Promise<StepOutcome>;
  announce(projectId: string, step: QueueStep, kind: UndoKind, auditId?: string): void;
  refused(projectId: string): void;
}

export interface Entry {
  step: QueueStep;
  label: string;
  opId: string;
  unknown: boolean;
}

export const TIMED_OUT = Symbol('timed-out');

export function withTimeout<T>(work: Promise<T>, ms: number): Promise<T | typeof TIMED_OUT> {
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(TIMED_OUT), ms);
    work.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      () => {
        clearTimeout(timer);
        resolve(TIMED_OUT);
      },
    );
  });
}

export function pause(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function summaryOf(kind: UndoKind, sets: number, changes: number): string {
  const verb = kind === 'undo' ? 'undone' : 'redone';
  return `${sets} change ${sets === 1 ? 'set' : 'sets'} ${verb} · ${changes} ${changes === 1 ? 'change' : 'changes'}`;
}

export const defaultQueueApi: QueueApi = {
  fetchQueue: (projectId, count) => undoRedoService.fetchQueue(projectId, count),
  send: (projectId, kind, step, opId) =>
    undoRedoService.sendStep(projectId, kind, { changeId: step.changeId, auditId: step.auditId }, opId),
  announce: (projectId, step, kind, auditId) => undoRedoService.announceStep(projectId, step, kind, auditId),
  refused: (projectId) => undoRedoService.notifyRefusal(projectId),
};

export function stepFromHint(hint: UndoStep | undefined, kind: UndoKind): QueueStep | undefined {
  const id = kind === 'undo' ? hint?.changeId : hint?.auditId;
  if (!hint?.available || !id) return undefined;
  return {
    changeId: hint.changeId || id,
    changeSetId: hint.changeSetId,
    auditId: hint.auditId,
    entityIRI: hint.entityIRI,
    label: hint.label,
  };
}


export function blockedText(blocked: QueueResponse['blocked']): string {
  return blocked
    ? `Can't undo ${blocked.label || 'the next change'}: ${blocked.reason || 'it was built on'}`
    : 'Some of these changes can no longer be undone.';
}

export async function revalidate(
  api: QueueApi,
  projectId: string,
  entries: Entry[],
): Promise<{ message?: string; item?: string }> {
  let response: QueueResponse;
  try {
    response = await api.fetchQueue(projectId, entries.length);
  } catch {
    return { message: 'Could not revalidate. Check your connection and try again.' };
  }
  if (response.steps.length < entries.length) {
    return { message: blockedText(response.blocked), item: response.blocked?.label };
  }
  if (entries.some((entry, index) => response?.steps[index]?.changeId !== entry.step.changeId)) {
    return { message: 'History changed since these were queued. Stop pending and try again.' };
  }
  return {};
}

import { CHECK_DELAY_MS, FINISHED_VISIBLE_MS, INFLIGHT_WAIT_MS, MAX_CHECKS, MAX_FINISHED_ITEMS, MAX_WAITING, REQUEST_TIMEOUT_MS } from './undoQueueSupport';
import { TIMED_OUT, blockedText, defaultQueueApi, detailOf, pause, revalidate, stepFromHint, summaryOf, withTimeout } from './undoQueueSupport';
import { newOpId } from '../utils/opId';
import type { Entry, FinishedItem, PauseReason, PressResult, QueueApi, UndoKind, UndoRunPhase, UndoRunSnapshot } from './undoQueueSupport';
import type { ForeignTarget, OtherChange, RevertPrHint, QueueResponse, QueueStep, StepOutcome, UndoState, UndoStep } from './undoRedoService';

export type { UndoRunSnapshot } from './undoQueueSupport';

export function createUndoQueue(api: QueueApi = defaultQueueApi) {
  let epoch = 0;
  let bindEpoch = 0;
  let stopRequested = false;
  let runId = 0;
  let phase: UndoRunPhase = 'idle';
  let kind: UndoKind = 'undo';
  let projectId = '';
  let entries: Entry[] = [];
  let current: Entry | null = null;
  let binding = 0;
  let redoWaiting = 0;
  let draining = false;
  let bindChain: Promise<unknown> = Promise.resolve();
  let inFlight: Promise<unknown> | null = null;
  let completed = 0;
  let changeCount = 0;
  let finishedItems: FinishedItem[] = [];
  let startedAt = 0;
  let reason: PauseReason | undefined;
  let message: string | undefined;
  let item: string | undefined;
  let summary: string | undefined;
  let lastState: UndoState | undefined;
  let others: OtherChange[] = [];
  let revertPr: RevertPrHint | undefined;
  let foreignTarget: ForeignTarget | undefined;
  let finishTimer: ReturnType<typeof setTimeout> | undefined;
  const listeners = new Set<() => void>();

  const build = (): UndoRunSnapshot => ({
    phase,
    kind,
    runId,
    startedAt,
    completed,
    changeCount,
    running: !!current && phase !== 'paused',
    pending: entries.length + binding + redoWaiting,
    current: current?.label,
    currentDetail: detailOf((current ?? (phase === 'paused' ? entries[0] : undefined))?.step),
    reason,
    message,
    item,
    summary,
    finishedItems,
    lastState,
    othersOnTop: others.length ? others : undefined,
    revertPr,
    foreignTarget,
  });
  let snapshot = build();

  function publish(): void {
    snapshot = build();
    listeners.forEach((listener) => listener());
  }

  function beginRun(pid: string, k: UndoKind): void {
    clearTimeout(finishTimer);
    runId += 1;
    projectId = pid;
    kind = k;
    completed = 0;
    changeCount = 0;
    redoWaiting = 0;
    finishedItems = [];
    startedAt = Date.now();
    reason = undefined;
    message = undefined;
    item = undefined;
    summary = undefined;
    phase = 'running';
  }

  function conclude(): void {
    if (phase !== 'running') return;
    if (completed === 0) {
      phase = 'idle';
    } else {
      phase = 'finished';
      summary = summaryOf(kind, completed, changeCount);
      const finishedRun = runId;
      finishTimer = setTimeout(() => {
        if (phase === 'finished' && runId === finishedRun) {
          phase = 'idle';
          publish();
        }
      }, FINISHED_VISIBLE_MS);
    }
    publish();
  }

  function haltWith(why: PauseReason, text: string, entry: Entry): void {
    reason = why;
    message = text;
    item = entry.label;
    phase = 'paused';
    entries.unshift(entry);
    current = null;
    if (why === 'Conflict') api.refused(projectId);
    publish();
  }

  async function attempt(entry: Entry): Promise<StepOutcome | { kind: 'unresolved' }> {
    const first = await withTimeout(api.send(projectId, kind, entry.step, entry.opId, entry.confirmOther), REQUEST_TIMEOUT_MS);
    if (first !== TIMED_OUT && (first.kind === 'done' || first.kind === 'refused')) return first;
    entry.unknown = true;
    phase = 'checking';
    publish();
    for (let check = 0; check < MAX_CHECKS; check += 1) {
      await pause(CHECK_DELAY_MS);
      const again = await withTimeout(api.send(projectId, kind, entry.step, entry.opId, entry.confirmOther), REQUEST_TIMEOUT_MS);
      if (again !== TIMED_OUT && again.kind !== 'unknown' && again.kind !== 'running') {
        phase = 'running';
        entry.unknown = false;
        return again;
      }
    }
    return { kind: 'unresolved' };
  }

  async function drain(): Promise<void> {
    if (draining) return;
    draining = true;
    const myEpoch = epoch;
    try {
      while (entries.length && phase === 'running' && epoch === myEpoch) {
        const entry = entries.shift() as Entry;
        current = entry;
        publish();
        inFlight = attempt(entry);
        const outcome = await (inFlight as ReturnType<typeof attempt>);
        inFlight = null;
        if (epoch !== myEpoch) return;
        if (outcome.kind === 'done') {
          current = null;
          completed += 1;
          changeCount += entry.step.changeCount ?? 1;
          finishedItems = [...finishedItems, { label: entry.label, changes: entry.step.changeCount ?? 1 }].slice(-MAX_FINISHED_ITEMS);
          if (outcome.state) lastState = outcome.state;
          api.announce(projectId, entry.step, kind, outcome.auditId);
          if (kind === 'redo' && redoWaiting > 0) {
            redoWaiting -= 1;
            const next = stepFromHint(outcome.state?.redo, 'redo');
            if (next) entries.push({ step: next, label: next.label || 'change', opId: newOpId(), unknown: false });
            else redoWaiting = 0;
          }
          publish();
        } else if (stopRequested) {
          current = null;
          phase = 'running';
        } else if (outcome.kind === 'refused') {
          if (outcome.state) lastState = outcome.state;
          others = outcome.othersOnTop ?? [];
          revertPr = outcome.revertPr;
          foreignTarget = outcome.foreignTarget;
          haltWith('Conflict', outcome.message, entry);
        } else {
          haltWith('Result unknown', 'The server did not confirm whether this change was undone.', entry);
        }
      }
    } finally {
      if (epoch === myEpoch) {
        draining = false;
        stopRequested = false;
      }
    }
    if (epoch === myEpoch && !entries.length && binding === 0) conclude();
  }

  function settle(): void {
    if (phase !== 'running' || draining) {
      publish();
      return;
    }
    if (entries.length) void drain();
    else if (binding === 0) conclude();
    else publish();
  }

  async function bind(pid: string, k: UndoKind, hint: UndoStep | undefined, myEpoch: number): Promise<PressResult> {
    let step: QueueStep | undefined;
    let failure: string | undefined;
    if (k === 'redo') {
      step = stepFromHint(hint, k);
      if (!step) failure = 'Nothing to redo';
    } else {
      try {
        for (let round = 0; round < 2 && !step && !failure; round += 1) {
          const known = [...(current ? [current] : []), ...entries];
          const response = await api.fetchQueue(pid, known.length + 1);
          if (known.some((entry, index) => response.steps[index]?.changeId !== entry.step.changeId)) {
            if (round === 0) {
              if (inFlight) await Promise.race([inFlight, pause(INFLIGHT_WAIT_MS)]);
              continue;
            }
            failure = 'Your history changed. Try again.';
          } else if (response.steps.length > known.length) {
            step = response.steps[known.length];
          } else {
            failure = response.blocked ? blockedText(response.blocked) : 'Nothing to undo';
          }
        }
      } catch {
        failure = 'Could not check what to undo';
      }
    }
    if (bindEpoch !== myEpoch) return { accepted: false };
    binding -= 1;
    if (!step) {
      if (k === 'redo') redoWaiting = 0;
      settle();
      return { accepted: false, message: failure };
    }
    entries.push({ step, label: step.label || 'change', opId: newOpId(), unknown: false });
    settle();
    return { accepted: true };
  }

  function reject(text: string): Promise<PressResult> {
    return Promise.resolve({ accepted: false, message: text });
  }

  return {
    subscribe(listener: () => void): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    getSnapshot: (): UndoRunSnapshot => snapshot,

    press(pid: string, k: UndoKind, hint?: UndoStep): Promise<PressResult> {
      if (phase === 'paused' || phase === 'checking') return reject('Undo is paused. Continue or stop it first.');
      const active = phase === 'running';
      if (active && (k !== kind || pid !== projectId)) return reject('Wait for the current run to finish.');
      if (entries.length + binding + redoWaiting >= MAX_WAITING) {
        return reject(`At most ${MAX_WAITING} ${k === 'redo' ? 'redos' : 'undos'} can wait at once.`);
      }
      if (active && k === 'redo') {
        redoWaiting += 1;
        publish();
        return Promise.resolve({ accepted: true });
      }
      if (!active) beginRun(pid, k);
      binding += 1;
      publish();
      const task = bindChain.then(() => bind(pid, k, hint, bindEpoch));
      bindChain = task;
      return task;
    },

    stopPending(): void {
      entries = [];
      bindEpoch += 1;
      binding = 0;
      redoWaiting = 0;
      bindChain = Promise.resolve();
      if (draining) {
        stopRequested = true;
        publish();
        return;
      }
      if (phase === 'paused' || phase === 'running') {
        phase = 'running';
        conclude();
      }
    },

    async resume(): Promise<void> {
      if (phase !== 'paused') return;
      const head = entries[0];
      if (reason === 'Conflict' && head) {
        const verdict = await revalidate(api, projectId, entries);
        if (verdict.message) {
          message = verdict.message;
          item = verdict.item || item;
          publish();
          return;
        }
      }
      if (head && !head.unknown) head.opId = newOpId();
      reason = undefined;
      message = undefined;
      item = undefined;
      phase = 'running';
      publish();
      void drain();
    },

    async confirmOthers(): Promise<void> {
      if (phase !== 'paused' || reason !== 'Conflict' || !others.length) return;
      const head = entries[0];
      if (head) {
        head.opId = newOpId();
        head.confirmOther = foreignTarget ? 'target' : true;
      }
      others = [];
      revertPr = undefined;
      foreignTarget = undefined;
      reason = undefined;
      message = undefined;
      item = undefined;
      phase = 'running';
      publish();
      void drain();
    },

    reset(): void {
      epoch += 1;
      bindEpoch += 1;
      stopRequested = false;
      entries = [];
      current = null;
      binding = 0;
      redoWaiting = 0;
      draining = false;
      bindChain = Promise.resolve();
      clearTimeout(finishTimer);
      phase = 'idle';
      reason = undefined;
      message = undefined;
      item = undefined;
      summary = undefined;
      lastState = undefined;
      others = [];
      revertPr = undefined;
      foreignTarget = undefined;
      publish();
    },
  };
}

export const undoQueue = createUndoQueue();

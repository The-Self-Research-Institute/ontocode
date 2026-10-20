import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CHECK_DELAY_MS, FINISHED_VISIBLE_MS, MAX_WAITING, REQUEST_TIMEOUT_MS, stepFromHint, type QueueApi } from '../services/undoQueueSupport';
import { createUndoQueue } from '../services/undoQueue';
import type { QueueResponse, QueueStep, StepOutcome } from '../services/undoRedoService';

const step = (id: string, extra: Partial<QueueStep> = {}): QueueStep => ({ changeId: id, label: `L${id}`, changeCount: 2, ...extra });

function setup(queue: QueueStep[], blocked: QueueResponse['blocked'] = null) {
  const sends: Array<{ id: string; opId: string }> = [];
  const outcomes: Array<StepOutcome | Promise<StepOutcome>> = [];
  const api: QueueApi = {
    fetchQueue: vi.fn(async (_p: string, count: number) => ({ steps: queue.slice(0, count), blocked })),
    send: vi.fn((_p, _k, s, opId) => {
      sends.push({ id: s.changeId, opId });
      const outcome = outcomes.length ? (outcomes.shift() as StepOutcome) : ({ kind: 'done' } as StepOutcome);
      return Promise.resolve(outcome).then((resolved) => {
        const index = queue.findIndex((q) => q.changeId === s.changeId);
        if (resolved.kind === 'done' && index >= 0) queue.splice(index, 1);
        return resolved;
      });
    }),
    announce: vi.fn(),
    refused: vi.fn(),
  };
  return { api, sends, outcomes, queue: createUndoQueue(api) };
}

describe('undoQueue', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('asks the server for the current target on the first press instead of trusting a stale hint', async () => {
    const { queue, sends } = setup([step('current')]);
    await queue.press('p', 'undo', { available: true, direction: 'UNDO', changeId: 'stale', dependents: [] });
    await vi.advanceTimersByTimeAsync(50);
    expect(sends.map((s) => s.id)).toEqual(['current']);
  });

  it('binds each press to its own change set and sends them one at a time', async () => {
    const { queue, sends, api } = setup([step('a'), step('b'), step('c')]);
    const first = queue.press('p', 'undo', { available: true, direction: 'UNDO', changeId: 'a', dependents: [] });
    const second = queue.press('p', 'undo');
    const third = queue.press('p', 'undo');
    await vi.advanceTimersByTimeAsync(50);
    expect(await Promise.all([first, second, third])).toEqual([{ accepted: true }, { accepted: true }, { accepted: true }]);
    expect(sends.map((s) => s.id)).toEqual(['a', 'b', 'c']);
    expect(new Set(sends.map((s) => s.opId)).size).toBe(3);
    expect(api.announce).toHaveBeenCalledTimes(3);
    expect(queue.getSnapshot().phase).toBe('finished');
    expect(queue.getSnapshot().summary).toBe('3 change sets undone · 6 changes');
  });

  it('exposes the running step detail and keeps it while paused', async () => {
    const detailed = step('a', { source: 'Entities → Classes', affected: 'Dog', restoring: 'A → B', entityIRI: 'http://x#Dog' });
    const { queue, outcomes } = setup([detailed]);
    outcomes.push(new Promise<StepOutcome>(() => undefined));
    void queue.press('p', 'undo');
    await vi.advanceTimersByTimeAsync(50);
    expect(queue.getSnapshot().currentDetail).toEqual({ source: 'Entities → Classes', affected: 'Dog', restoring: 'A → B', entityIRI: 'http://x#Dog' });
  });

  it('has no detail when the server sent none', async () => {
    const { queue, outcomes } = setup([step('a')]);
    outcomes.push(new Promise<StepOutcome>(() => undefined));
    void queue.press('p', 'undo');
    await vi.advanceTimersByTimeAsync(50);
    expect(queue.getSnapshot().currentDetail).toBeUndefined();
  });

  it('clears the finished summary after a few seconds', async () => {
    const { queue } = setup([step('a')]);
    await queue.press('p', 'undo', { available: true, direction: 'UNDO', changeId: 'a', dependents: [] });
    await vi.advanceTimersByTimeAsync(10);
    expect(queue.getSnapshot().phase).toBe('finished');
    await vi.advanceTimersByTimeAsync(FINISHED_VISIBLE_MS);
    expect(queue.getSnapshot().phase).toBe('idle');
  });

  it('rejects presses beyond the waiting limit', async () => {
    const { queue, outcomes } = setup(Array.from({ length: 40 }, (_, i) => step(`s${i}`)));
    outcomes.push(new Promise<StepOutcome>(() => undefined));
    const results = await Promise.all(Array.from({ length: MAX_WAITING + 3 }, () => queue.press('p', 'undo')));
    expect(results.filter((r) => r.accepted).length).toBe(MAX_WAITING);
    expect(results.some((r) => r.message?.includes('At most'))).toBe(true);
  });

  it('pauses on a refusal, keeps the failed entry first and continues after revalidating', async () => {
    const { queue, sends, outcomes, api } = setup([step('a'), step('b')]);
    outcomes.push({ kind: 'refused', message: 'Sam changed it' });
    const one = queue.press('p', 'undo');
    const two = queue.press('p', 'undo');
    await Promise.all([one, two]);
    await vi.runAllTimersAsync();
    const paused = queue.getSnapshot();
    expect(paused.phase).toBe('paused');
    expect(paused.reason).toBe('Conflict');
    expect(paused.message).toBe('Sam changed it');
    expect(paused.item).toBe('La');
    expect(paused.pending).toBe(2);
    expect(api.refused).toHaveBeenCalledWith('p');
    expect(sends.map((s) => s.id)).toEqual(['a']);
    await queue.resume();
    await vi.advanceTimersByTimeAsync(50);
    expect(sends.map((s) => s.id)).toEqual(['a', 'a', 'b']);
    expect(sends[1].opId).not.toBe(sends[0].opId);
    expect(queue.getSnapshot().phase).toBe('finished');
  });

  it('stays paused when history changed under the queued entries', async () => {
    const live = [step('a'), step('b')];
    const { queue, api } = setup(live);
    const outcomes = vi.mocked(api.send);
    outcomes.mockResolvedValueOnce({ kind: 'refused', message: 'no' });
    await Promise.all([queue.press('p', 'undo'), queue.press('p', 'undo')]);
    await vi.runAllTimersAsync();
    live[0] = step('x');
    await queue.resume();
    expect(queue.getSnapshot().phase).toBe('paused');
    expect(queue.getSnapshot().message).toMatch(/History changed/);
    expect(queue.getSnapshot().pending).toBe(2);
  });

  it('stop pending drops waiting entries at once', async () => {
    const { queue, sends, outcomes } = setup([step('a'), step('b'), step('c')]);
    outcomes.push(new Promise<StepOutcome>((resolve) => setTimeout(() => resolve({ kind: 'done' }), 1000)));
    const presses = [queue.press('p', 'undo'), queue.press('p', 'undo'), queue.press('p', 'undo')];
    await vi.advanceTimersByTimeAsync(5);
    queue.stopPending();
    expect(queue.getSnapshot().pending).toBe(0);
    await vi.advanceTimersByTimeAsync(1500);
    await Promise.all(presses);
    expect(sends.map((s) => s.id)).toEqual(['a']);
    expect(queue.getSnapshot().completed).toBe(1);
    expect(queue.getSnapshot().phase).toBe('finished');
  });

  it('stop pending after a pause returns to idle without sending anything', async () => {
    const { queue, outcomes } = setup([step('a'), step('b')]);
    outcomes.push({ kind: 'refused', message: 'no' });
    await Promise.all([queue.press('p', 'undo'), queue.press('p', 'undo')]);
    await vi.runAllTimersAsync();
    queue.stopPending();
    expect(queue.getSnapshot().phase).toBe('idle');
    expect(queue.getSnapshot().pending).toBe(0);
  });

  it('checks the same operation id after a timeout and settles on the stored result', async () => {
    const { queue, sends, outcomes, api } = setup([step('a')]);
    outcomes.push(new Promise<StepOutcome>(() => undefined));
    outcomes.push({ kind: 'running' });
    outcomes.push({ kind: 'done', auditId: 'au' });
    const pressed = queue.press('p', 'undo');
    await vi.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS + 1);
    expect(queue.getSnapshot().phase).toBe('checking');
    await vi.advanceTimersByTimeAsync(CHECK_DELAY_MS);
    await vi.advanceTimersByTimeAsync(CHECK_DELAY_MS);
    await pressed;
    expect(new Set(sends.map((s) => s.opId)).size).toBe(1);
    expect(sends.length).toBe(3);
    expect(api.announce).toHaveBeenCalledTimes(1);
    expect(queue.getSnapshot().phase).toBe('finished');
  });

  it('pauses as result unknown when checks never settle and resumes with the same operation id', async () => {
    const { queue, sends, outcomes } = setup([step('a')]);
    for (let i = 0; i < 20; i += 1) outcomes.push({ kind: 'unknown', message: 'down' });
    void queue.press('p', 'undo');
    await vi.advanceTimersByTimeAsync(CHECK_DELAY_MS * 12);
    const snap = queue.getSnapshot();
    expect(snap.phase).toBe('paused');
    expect(snap.reason).toBe('Result unknown');
    const opIds = new Set(sends.map((s) => s.opId));
    expect(opIds.size).toBe(1);
    outcomes.length = 0;
    outcomes.push({ kind: 'done' });
    await queue.resume();
    await vi.advanceTimersByTimeAsync(50);
    expect(new Set(sends.map((s) => s.opId)).size).toBe(1);
    expect(queue.getSnapshot().phase).toBe('finished');
  });

  it('refuses a press that would target a different change after history shifted', async () => {
    const live = [step('a'), step('b')];
    const { queue, outcomes } = setup(live);
    outcomes.push(new Promise<StepOutcome>(() => undefined));
    void queue.press('p', 'undo');
    await vi.advanceTimersByTimeAsync(5);
    live[0] = step('z');
    const pending = queue.press('p', 'undo');
    await vi.advanceTimersByTimeAsync(2000);
    const second = await pending;
    expect(second).toEqual({ accepted: false, message: 'Your history changed. Try again.' });
  });

  it('reports a blocked next change instead of queueing it', async () => {
    const { queue } = setup([], { label: 'Cat', reason: 'Sam built on it' });
    const result = await queue.press('p', 'undo');
    expect(result.message).toBe("Can't undo Cat: Sam built on it");
    expect(queue.getSnapshot().phase).toBe('idle');
  });

  it('rejects redo while an undo run is active and any press while paused', async () => {
    const { queue, outcomes } = setup([step('a'), step('b')]);
    outcomes.push({ kind: 'refused', message: 'no' });
    void queue.press('p', 'undo');
    const redo = await queue.press('p', 'redo');
    expect(redo.accepted).toBe(false);
    await vi.runAllTimersAsync();
    expect((await queue.press('p', 'undo')).accepted).toBe(false);
  });

  it('reset drops everything', async () => {
    const { queue, outcomes } = setup([step('a'), step('b')]);
    outcomes.push(new Promise<StepOutcome>(() => undefined));
    void queue.press('p', 'undo');
    void queue.press('p', 'undo');
    await vi.advanceTimersByTimeAsync(5);
    queue.reset();
    expect(queue.getSnapshot().phase).toBe('idle');
    expect(queue.getSnapshot().pending).toBe(0);
  });

  it('runs a redo from the shown step without the queue endpoint', async () => {
    const { queue, api, sends } = setup([]);
    const hint = { available: true, direction: 'REDO' as const, changeId: 'c', auditId: 'au', label: 'R', dependents: [] };
    await queue.press('p', 'redo', hint);
    await vi.runAllTimersAsync();
    expect(api.fetchQueue).not.toHaveBeenCalled();
    expect(sends[0].id).toBe('c');
    expect(queue.getSnapshot().summary).toBe('1 change set redone · 1 change');
  });

  describe('redo bursts', () => {
    const redoHint = (id: string) => ({ available: true, direction: 'REDO' as const, changeId: id, auditId: `au${id}`, label: `R${id}`, dependents: [] });
    const unavailable = { available: false, direction: 'REDO' as const, dependents: [] };

    function chain(ids: string[]) {
      const { api, sends, queue } = setup([]);
      let sent = 0;
      vi.mocked(api.send).mockImplementation((_p, _k, s, opId) => {
        sends.push({ id: s.changeId, opId });
        sent += 1;
        const next = ids[sent];
        return Promise.resolve({
          kind: 'done',
          auditId: `done${sent}`,
          state: { undo: unavailable, redo: next ? redoHint(next) : unavailable },
        } as StepOutcome);
      });
      return { api, sends, queue };
    }

    it('runs every extra redo press in order, taking each next step from the server reply', async () => {
      const { queue, sends } = chain(['a', 'b', 'c', 'd']);
      const presses = [queue.press('p', 'redo', redoHint('a'))];
      for (let i = 0; i < 3; i += 1) presses.push(queue.press('p', 'redo', redoHint('a')));
      await vi.runAllTimersAsync();
      expect((await Promise.all(presses)).every((p) => p.accepted)).toBe(true);
      expect(sends.map((s) => s.id)).toEqual(['a', 'b', 'c', 'd']);
      expect(new Set(sends.map((s) => s.opId)).size).toBe(4);
      expect(queue.getSnapshot().summary).toBe('4 change sets redone · 4 changes');
      expect(queue.getSnapshot().pending).toBe(0);
    });

    it('drops presses beyond the available redos and still finishes', async () => {
      const { queue, sends } = chain(['a', 'b']);
      for (let i = 0; i < 6; i += 1) void queue.press('p', 'redo', redoHint('a'));
      await vi.advanceTimersByTimeAsync(100);
      expect(sends.map((s) => s.id)).toEqual(['a', 'b']);
      expect(queue.getSnapshot().phase).toBe('finished');
      expect(queue.getSnapshot().pending).toBe(0);
    });

    it('shows extra redo presses as pending and clears them on stop', async () => {
      const { queue, api } = chain(['a', 'b', 'c']);
      vi.mocked(api.send).mockImplementationOnce(() => new Promise<StepOutcome>(() => undefined));
      void queue.press('p', 'redo', redoHint('a'));
      void queue.press('p', 'redo', redoHint('a'));
      void queue.press('p', 'redo', redoHint('a'));
      await vi.advanceTimersByTimeAsync(5);
      expect(queue.getSnapshot().pending).toBe(2);
      queue.stopPending();
      expect(queue.getSnapshot().pending).toBe(0);
    });

    it('still limits how many redos can wait', async () => {
      const { queue } = chain(['a']);
      const results = [];
      for (let i = 0; i < MAX_WAITING + 3; i += 1) results.push(queue.press('p', 'redo', redoHint('a')));
      const settled = await Promise.all(results);
      expect(settled.filter((r) => !r.accepted).length).toBeGreaterThan(0);
    });

    it('does not accept an undo while a redo run is active', async () => {
      const { queue, api } = chain(['a', 'b']);
      vi.mocked(api.send).mockImplementationOnce(() => new Promise<StepOutcome>(() => undefined));
      void queue.press('p', 'redo', redoHint('a'));
      expect((await queue.press('p', 'undo')).accepted).toBe(false);
    });
  });

  it('only builds steps from available hints', () => {
    expect(stepFromHint(undefined, 'undo')).toBeUndefined();
    expect(stepFromHint({ available: false, direction: 'UNDO', changeId: 'a', dependents: [] }, 'undo')).toBeUndefined();
    expect(stepFromHint({ available: true, direction: 'REDO', changeId: 'a', dependents: [] }, 'redo')).toBeUndefined();
  });
});

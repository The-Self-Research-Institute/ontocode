import React, { act } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flush, renderHook } from './hookHarness';
import { createUndoQueue } from '../services/undoQueue';

const holder = vi.hoisted(() => ({ queue: undefined as unknown }));
vi.mock('../services/undoQueue', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/undoQueue')>();
  holder.queue = actual.createUndoQueue({
    fetchQueue: async () => ({ steps: [{ changeId: 'a', label: 'A', changeCount: 1 }], blocked: null }),
    send: () => new Promise(() => undefined),
    announce: () => undefined,
    refused: () => undefined,
  });
  return { ...actual, undoQueue: holder.queue };
});

import { useUndoRunStatus } from '../hooks/useUndoRunStatus';

type Q = ReturnType<typeof createUndoQueue>;
const hint = { available: true, direction: 'UNDO' as const, changeId: 'a', dependents: [] };

describe('useUndoRunStatus', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    (holder.queue as Q).reset();
    vi.useRealTimers();
  });

  it('shows the chip only after a short delay and opens the panel once when slow', async () => {
    const { result } = renderHook(() => useUndoRunStatus(), undefined);
    expect(result.current.chipVisible).toBe(false);
    act(() => void (holder.queue as Q).press('p', 'undo', hint));
    await flush();
    expect(result.current.phase).toBe('running');
    expect(result.current.chipVisible).toBe(false);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });
    expect(result.current.chipVisible).toBe(true);
    expect(result.current.slow).toBe(false);
    expect(result.current.panelOpen).toBe(false);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(8000);
    });
    expect(result.current.slow).toBe(true);
    expect(result.current.panelOpen).toBe(true);
    act(() => result.current.closePanel());
    expect(result.current.panelOpen).toBe(false);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(result.current.panelOpen).toBe(false);
  });
});

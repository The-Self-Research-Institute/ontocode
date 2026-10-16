import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook } from './hookHarness';
import { emitCommittedChange } from '../utils/committedChange';
import { useCommittedChangeRefresh } from '../hooks/useCommittedChangeRefresh';

describe('useCommittedChangeRefresh', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('refreshes once, shortly after an undo or redo of this project', async () => {
    const refresh = vi.fn();
    renderHook(() => useCommittedChangeRefresh('p', refresh), undefined);
    act(() => {
      emitCommittedChange({ projectId: 'p', direction: 'UNDO', opId: 'a', draft: false });
      emitCommittedChange({ projectId: 'p', direction: 'REDO', opId: 'b', draft: false });
    });
    expect(refresh).not.toHaveBeenCalled();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('ignores other projects and plain applied changes', async () => {
    const refresh = vi.fn();
    renderHook(() => useCommittedChangeRefresh('p', refresh), undefined);
    act(() => {
      emitCommittedChange({ projectId: 'other', direction: 'UNDO', opId: 'c', draft: false });
      emitCommittedChange({ projectId: 'p', direction: 'APPLY', opId: 'd', draft: false });
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(refresh).not.toHaveBeenCalled();
  });
});

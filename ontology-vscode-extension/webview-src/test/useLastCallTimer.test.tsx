import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook } from './hookHarness';
import { useLastCallTimer } from '../hooks/useLastCallTimer';

describe('useLastCallTimer', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('runs only the last of several quick calls, after its own delay', async () => {
    const first = vi.fn();
    const last = vi.fn();
    const { result } = renderHook(() => useLastCallTimer(), undefined);
    act(() => {
      result.current(first, 400);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });
    act(() => {
      result.current(last, 400);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(399);
    });
    expect(first).not.toHaveBeenCalled();
    expect(last).not.toHaveBeenCalled();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(first).not.toHaveBeenCalled();
    expect(last).toHaveBeenCalledTimes(1);
  });

  it('drops a pending call when the component goes away', async () => {
    const run = vi.fn();
    const { result, unmount } = renderHook(() => useLastCallTimer(), undefined);
    act(() => {
      result.current(run, 400);
    });
    unmount();
    await vi.advanceTimersByTimeAsync(1000);
    expect(run).not.toHaveBeenCalled();
  });
});

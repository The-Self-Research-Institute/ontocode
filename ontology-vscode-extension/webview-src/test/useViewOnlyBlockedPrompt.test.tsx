import { act } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { renderHook } from './hookHarness';
import { useViewOnlyBlockedPrompt, VIEW_ONLY_BLOCKED_EVENT } from '../hooks/useViewOnlyBlockedPrompt';

const announce = () => act(() => { window.dispatchEvent(new CustomEvent(VIEW_ONLY_BLOCKED_EVENT)); });

describe('useViewOnlyBlockedPrompt', () => {
  it('opens the edit prompt for an account on the free plan', () => {
    const show = vi.fn();
    const view = renderHook(() => useViewOnlyBlockedPrompt(show, false), undefined);
    announce();
    view.unmount();
    expect(show).toHaveBeenCalledTimes(1);
    expect(show).toHaveBeenCalledWith('edit');
  });

  it('opens the viewer prompt for a project viewer', () => {
    const show = vi.fn();
    const view = renderHook(() => useViewOnlyBlockedPrompt(show, true), undefined);
    announce();
    view.unmount();
    expect(show).toHaveBeenCalledWith('viewer');
  });

  it('stops listening once the page is gone', () => {
    const show = vi.fn();
    renderHook(() => useViewOnlyBlockedPrompt(show, false), undefined).unmount();
    announce();
    expect(show).not.toHaveBeenCalled();
  });
});

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  clearGraphSessionCache,
  installGraphCacheInvalidation,
  subscribeGraphRefresh,
} from '../../../plugins/graph-view-plugin/src/services/mutationRefresh';

const committed = (direction: string, projectId = 'p1') =>
  window.dispatchEvent(new CustomEvent('ontocode:committed-change', { detail: { projectId, direction } }));

describe('graph refresh after changes', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    sessionStorage.clear();
  });
  afterEach(() => vi.useRealTimers());

  it('refreshes after an undo or a redo, which only announce a committed change', () => {
    const refresh = vi.fn();
    const stop = subscribeGraphRefresh(refresh);
    committed('UNDO');
    vi.advanceTimersByTime(600);
    committed('REDO');
    vi.advanceTimersByTime(600);
    expect(refresh).toHaveBeenCalledTimes(2);
    stop();
  });

  it('refreshes once when an edit announces itself in two ways at the same time', () => {
    const refresh = vi.fn();
    const stop = subscribeGraphRefresh(refresh);
    window.dispatchEvent(new CustomEvent('ontology:mutated', { detail: { projectId: 'p1' } }));
    committed('APPLY');
    vi.advanceTimersByTime(600);
    expect(refresh).toHaveBeenCalledTimes(1);
    stop();
  });

  it('still refreshes for edits made in the graph itself', () => {
    const refresh = vi.fn();
    const stop = subscribeGraphRefresh(refresh);
    window.dispatchEvent(new CustomEvent('graph-view:class-created'));
    vi.advanceTimersByTime(600);
    expect(refresh).toHaveBeenCalledTimes(1);
    stop();
  });

  it('stops refreshing once unsubscribed', () => {
    const refresh = vi.fn();
    subscribeGraphRefresh(refresh)();
    committed('UNDO');
    vi.advanceTimersByTime(600);
    expect(refresh).not.toHaveBeenCalled();
  });

  it('drops the remembered graph for the project after an undo', () => {
    sessionStorage.setItem('ontocode:graphView:p1', 'old');
    sessionStorage.setItem('ontocode:graphView:p1:draft', 'old');
    sessionStorage.setItem('ontocode:graphView:p2', 'other');
    const stop = installGraphCacheInvalidation();
    committed('UNDO', 'p1');
    expect(sessionStorage.getItem('ontocode:graphView:p1')).toBeNull();
    expect(sessionStorage.getItem('ontocode:graphView:p1:draft')).toBeNull();
    expect(sessionStorage.getItem('ontocode:graphView:p2')).toBe('other');
    stop();
  });

  it('drops every remembered graph when no project is named', () => {
    sessionStorage.setItem('ontocode:graphView:p1', 'a');
    sessionStorage.setItem('ontocode:graphView:p2', 'b');
    sessionStorage.setItem('unrelated', 'keep');
    clearGraphSessionCache();
    expect(sessionStorage.getItem('ontocode:graphView:p1')).toBeNull();
    expect(sessionStorage.getItem('ontocode:graphView:p2')).toBeNull();
    expect(sessionStorage.getItem('unrelated')).toBe('keep');
  });
});

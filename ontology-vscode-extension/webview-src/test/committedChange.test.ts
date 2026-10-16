import { beforeEach, describe, expect, it, vi } from 'vitest';
import { emitCommittedChange, subscribeCommittedChange } from '../utils/committedChange';

describe('committed change event', () => {
  let received: unknown[];
  let unsubscribe: () => void;

  beforeEach(() => {
    received = [];
    unsubscribe?.();
    unsubscribe = subscribeCommittedChange((change) => received.push(change));
  });

  it('delivers a change once with a stable op id', () => {
    const change = emitCommittedChange({ projectId: 'p', direction: 'APPLY', changeSetId: 'cs-1', entityIRIs: ['e:A', 'e:A', ''], draft: false });
    expect(received).toHaveLength(1);
    expect(change?.opId).toBe('cs-1');
    expect(change?.entityIRIs).toEqual(['e:A']);
  });

  it('drops a duplicate of the same operation and direction', () => {
    emitCommittedChange({ projectId: 'p', direction: 'UNDO', auditId: 'au-1', draft: false });
    const again = emitCommittedChange({ projectId: 'p', direction: 'UNDO', auditId: 'au-1', draft: false });
    expect(again).toBeNull();
    expect(received).toHaveLength(1);
  });

  it('treats the redo of an undo as a separate event', () => {
    emitCommittedChange({ projectId: 'p', direction: 'UNDO', auditId: 'au-2', draft: false });
    emitCommittedChange({ projectId: 'p', direction: 'REDO', auditId: 'au-2', draft: false });
    expect(received).toHaveLength(2);
  });

  it('gives every change without an id its own op id', () => {
    emitCommittedChange({ projectId: 'p', direction: 'APPLY', draft: false });
    emitCommittedChange({ projectId: 'p', direction: 'APPLY', draft: false });
    expect(received).toHaveLength(2);
  });

  it('stops delivering after unsubscribe', () => {
    const handler = vi.fn();
    const off = subscribeCommittedChange(handler);
    off();
    emitCommittedChange({ projectId: 'p', direction: 'APPLY', changeSetId: 'cs-9', draft: false });
    expect(handler).not.toHaveBeenCalled();
  });
});

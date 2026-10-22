import { beforeEach, describe, expect, it, vi } from 'vitest';
import { affectedLists, emitCommittedChange, subscribeCommittedChange } from '../utils/committedChange';

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

  describe('affectedLists', () => {
    const change = (direction: 'APPLY' | 'UNDO', opTypes: string[]) => ({
      opId: 'o', projectId: 'p', direction, entityIRIs: [], opTypes, draft: false, at: 0,
    });

    it('names the class tree for a parent, class or label change', () => {
      expect(affectedLists(change('APPLY', ['addSubClassOf']))).toEqual(['classes']);
      expect(affectedLists(change('APPLY', ['deleteSubClassOf', 'addSubClassOf']))).toEqual(['classes']);
      expect(affectedLists(change('APPLY', ['createClass']))).toEqual(['classes']);
    });

    it('names the other lists for their own changes', () => {
      expect(affectedLists(change('APPLY', ['addSubPropertyOf']))).toEqual(['properties']);
      expect(affectedLists(change('APPLY', ['createObjectProperty']))).toEqual(['properties']);
      expect(affectedLists(change('APPLY', ['createAnnotationProperty']))).toEqual(['annotationProperties']);
      expect(affectedLists(change('APPLY', ['createIndividual']))).toEqual(['individuals']);
      expect(affectedLists(change('APPLY', ['addClassAssertion']))).toEqual(['individuals']);
      expect(affectedLists(change('APPLY', ['createDatatype']))).toEqual(['datatypes']);
    });

    it('names every list for a merge, a publish or a pull', () => {
      for (const type of ['ontologyMerge', 'draftPublish', 'draftPull']) {
        expect(affectedLists(change('APPLY', [type])).sort()).toEqual(
          ['annotationProperties', 'classes', 'datatypes', 'individuals', 'properties'],
        );
      }
    });

    it('names several lists when one change touches several kinds', () => {
      expect(affectedLists(change('APPLY', ['createClass', 'createIndividual'])).sort()).toEqual(['classes', 'individuals']);
    });

    it('names nothing for edits that do not move anything in a list', () => {
      expect(affectedLists(change('APPLY', ['addAnnotation']))).toEqual([]);
      expect(affectedLists(change('APPLY', ['addDisjointWith']))).toEqual([]);
    });

    it('names nothing for undo and redo, which refresh through their own path', () => {
      expect(affectedLists(change('UNDO', ['addSubClassOf']))).toEqual([]);
    });
  });
});

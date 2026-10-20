import { describe, expect, it } from 'vitest';
import { entityBadge } from '../../../plugins/change-assistant-plugin/src/changeSets/entityRows';

const entry = (operationType: string, type: string, action: string): any => ({
  id: '1', timestamp: new Date(), author: 'a', type, action, entityUri: 'http://x#A', entityLabel: 'A', description: '', operationType,
});

describe('change row badge', () => {
  it('says Created only for creating an entity', () => {
    expect(entityBadge(entry('createClass', 'class', 'added'))).toBe('Created class');
    expect(entityBadge(entry('createIndividual', 'individual', 'added'))).toBe('Created individual');
  });

  it('does not call a parent change a created class', () => {
    expect(entityBadge(entry('addSubClassOf', 'class', 'added'))).toBe('Added');
    expect(entityBadge(entry('removeSubClassOf', 'class', 'deleted'))).toBe('Removed');
  });

  it('does not call a disjoint or statement change a created class', () => {
    expect(entityBadge(entry('addDisjointWith', 'class', 'added'))).toBe('Added');
    expect(entityBadge(entry('addStatement', 'class', 'added'))).toBe('Added');
  });

  it('keeps Deleted for deleting an entity and Modified for edits', () => {
    expect(entityBadge(entry('deleteClass', 'class', 'deleted'))).toBe('Deleted class');
    expect(entityBadge(entry('updateSubClassOf', 'class', 'modified'))).toBe('Modified');
  });

  it('keeps the legacy generic operation names working', () => {
    expect(entityBadge(entry('ADD', 'class', 'added'))).toBe('Created class');
  });
});

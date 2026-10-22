import { describe, expect, it } from 'vitest';
import { formatSkippedImports, formatUpdatedImports } from '../utils/importWarnings';

describe('import warnings after an ontology IRI change', () => {
  it('says nothing when every importer was updated', () => {
    expect(formatSkippedImports([])).toBeNull();
    expect(formatSkippedImports(undefined)).toBeNull();
  });

  it('names each ontology that was left alone and who owns it', () => {
    const message = formatSkippedImports([
      { projectId: 'p1', name: 'Restaurant', owner: 'sam@x.org', reason: "You don't have edit rights in this project" },
      { projectId: 'p2', name: 'Menu', owner: 'ann@x.org', reason: "You don't have edit rights in this project" },
    ]);
    expect(message).toContain('2 ontologies still import the old IRI');
    expect(message).toContain("you can't edit them");
    expect(message).toContain('Restaurant (owner: sam@x.org)');
    expect(message).toContain('Menu (owner: ann@x.org)');
  });

  it('uses the singular for one ontology and falls back to the project id', () => {
    const message = formatSkippedImports([{ projectId: 'p1', reason: "You don't have edit rights in this project" }]);
    expect(message).toContain('1 ontology still imports the old IRI and was not updated');
    expect(message).toContain('p1');
  });

  it('tells a failed update apart from missing rights', () => {
    expect(formatSkippedImports([{ projectId: 'p1', name: 'A', reason: 'The update failed' }])).toContain('the update failed');
    expect(formatSkippedImports([
      { projectId: 'p1', name: 'A', reason: 'The update failed' },
      { projectId: 'p2', name: 'B', reason: "You don't have edit rights in this project" },
    ])).toContain("you can't edit some of them and the update failed for the others");
  });

  it('lists the ontologies that were updated', () => {
    expect(formatUpdatedImports([{ projectId: 'p1', name: 'Restaurant' }, { projectId: 'p2' }])).toBe('Imports updated in: Restaurant, p2.');
    expect(formatUpdatedImports([])).toBeNull();
  });
});

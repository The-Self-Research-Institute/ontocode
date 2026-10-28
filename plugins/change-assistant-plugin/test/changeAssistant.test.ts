import { describe, expect, it } from 'vitest';
import { authorActivity, timelineItems, weekdayActivity } from '../src/assistant/activityData';
import {
  ALL_TYPES,
  bucketFromOperation,
  authorKey,
  fallbackLabel,
  isRollbackChange,
  normaliseAuthor,
  resolveChangeType,
  TYPE_LABELS
} from '../src/assistant/changeKinds';
import { computeStats, countEntries, countableChanges, extractLabelFromIRI, parseChange } from '../src/assistant/changeParsing';
import { buildEntityRow, canUndoEntry, manualHeading, propertyRows } from '../src/changeSets/entityRows';
import { entityTarget, propertyTarget, setTarget } from '../src/changeSets/targets';
import { groupChangeSets, ontologyIriChange } from '../src/changeSets/groupChangeSets';
import { inScope } from '../src/assistant/hooks/useChangeFilters';
import { nextFeedOffset } from '../src/assistant/feedPaging';
import { STATUS_FILTERS } from '../src/assistant/statusFilters';
import { mergedPrOf, prReversalOf } from '../src/changeSets/prReversal';
import { parseServerTime } from '../src/changeSets/timeFormat';
import type { ChangeEntry } from '../src/changeSets/types';

const base = {
  id: 'c1',
  timestamp: '2026-10-13T10:00:00Z',
  username: 'sam@x.com',
  userId: 'sam@x.com'
};

describe('change kinds', () => {
  it('buckets every operation the server can write', () => {
    const table: Record<string, string> = {
      createClass: 'class',
      deleteObjectProperty: 'property',
      addClassAssertion: 'individual',
      addObjectPropertyAssertion: 'individual',
      createIndividual: 'individual',
      updateAnnotation: 'annotation',
      addAxiom: 'axiom',
      prefixAdded: 'prefix',
      prefixModified: 'prefix',
      renameEntity: 'rename',
      addImport: 'import',
      projectImported: 'import',
      addSwrlRule: 'rule',
      addDatatypeDefinition: 'datatype',
      createDatatype: 'datatype',
      insertCitation: 'citation',
      addEquivalentClass: 'class',
      addPropertyDomain: 'property'
    };
    Object.entries(table).forEach(([op, bucket]) => expect(bucketFromOperation(op), op).toBe(bucket));
  });

  it('prefers the operation over a stale server bucket and falls back to the server bucket', () => {
    expect(resolveChangeType('addClassAssertion', 'CLASS')).toBe('individual');
    expect(resolveChangeType('somethingNew', 'DATATYPE')).toBe('datatype');
    expect(resolveChangeType(undefined, undefined)).toBe('axiom');
  });

  it('has a label for every type', () => {
    ALL_TYPES.forEach(type => expect(TYPE_LABELS[type]).toBeTruthy());
  });

  it('recognises undo and redo rows', () => {
    expect(isRollbackChange({ source: 'ROLLBACK' })).toBe(true);
    expect(isRollbackChange({ operationType: 'ROLLBACK_CHANGESET' })).toBe(true);
    expect(isRollbackChange({ operationType: 'REDO_ENTRY' })).toBe(true);
    expect(isRollbackChange({ operationType: 'createClass', source: 'MANUAL' })).toBe(false);
  });

  it('never labels a change Unknown', () => {
    expect(fallbackLabel({ entityLabel: 'Pizza' })).toBe('Pizza');
    expect(fallbackLabel({ entityUri: 'http://ex/onto#Pizza' })).toBe('Pizza');
    expect(fallbackLabel({ description: 'Code View save modified 3 structural axiom(s)' }))
      .toBe('Code View save modified 3 structural axiom(s)');
    expect(fallbackLabel({ operationType: 'codeViewStructuralEdit' })).toBe('Code view structural edit');
    expect(fallbackLabel({})).toBe('Ontology change');
    expect(extractLabelFromIRI('')).toBe('');
  });

  it('shows the local desktop user and keeps real names', () => {
    expect(normaliseAuthor('desktop-user-local', undefined)).toBe('Local user');
    expect(normaliseAuthor('', 'system')).toBe('System');
    expect(normaliseAuthor('Sam', 'sam@x.com')).toBe('Sam');
    expect(normaliseAuthor('', 'sam@x.com')).toBe('sam@x.com');
  });
});

describe('parsing a change from the server', () => {
  it('uses the server entity bucket and keeps the undo flags', () => {
    const parsed = parseChange({
      ...base,
      operationType: 'prefixAdded',
      entityType: 'PREFIX',
      entityLabel: 'ex',
      undoable: true,
      undoReason: null,
      draft: false
    });
    expect(parsed.type).toBe('prefix');
    expect(parsed.undoable).toBe(true);
    expect(parsed.entityLabel).toBe('ex');
  });

  it('labels an entity-less row from its description instead of Unknown', () => {
    const parsed = parseChange({
      ...base,
      operationType: 'bulkPopulation',
      description: 'Code View save added/changed 120 statements'
    });
    expect(parsed.entityLabel).toBe('Code View save added/changed 120 statements');
  });

  it('classifies assertions as individuals', () => {
    expect(parseChange({ ...base, operationType: 'addObjectPropertyAssertion', entityType: 'PROPERTY' }).type)
      .toBe('individual');
  });

  it('leaves undoable undefined when an older server does not send it', () => {
    expect(parseChange({ ...base, operationType: 'createClass' }).undoable).toBeUndefined();
  });
});

describe('statistics ignore undo and redo rows', () => {
  const changes = [
    parseChange({ ...base, id: 'a', operationType: 'createClass', entityLabel: 'A' }),
    parseChange({ ...base, id: 'b', operationType: 'ROLLBACK_CHANGESET', source: 'ROLLBACK', username: 'kim@x.com', userId: 'kim@x.com' }),
    parseChange({ ...base, id: 'c', operationType: 'deleteClass', entityLabel: 'B', username: 'Sam', userId: 'SAM@x.com' })
  ];

  it('does not count them as changes or authors', () => {
    const stats = computeStats(changes, []);
    expect(stats.totalChanges).toBe(2);
    expect(stats.activeAuthors).toBe(1);
  });

  it('counts only changes that are still in effect and reports the undone ones separately', () => {
    const undone = parseChange({ ...base, id: 'u', operationType: 'createClass', entityLabel: 'U', reverted: true });
    const partly = parseChange({ ...base, id: 'p', operationType: 'updateClass', entityLabel: 'P', subChanges: [
      { id: 's1', predicate: 'http://www.w3.org/2000/01/rdf-schema#label', addition: true, newValue: 'x', reverted: true },
      { id: 's2', predicate: 'http://www.w3.org/2000/01/rdf-schema#comment', addition: true, newValue: 'y' }] });
    const stats = computeStats([...changes, undone, partly], []);
    expect(stats.totalChanges).toBe(3);
    expect(stats.undoneChanges).toBe(1);
  });

  it('keeps them out of the timeline, weekday chart and author chart', () => {
    expect(timelineItems(changes).map(c => c.id)).toEqual(['a', 'c']);
    const weekdayTotal = weekdayActivity(changes, []);
    expect(weekdayTotal.additions.concat(weekdayTotal.deletions, weekdayTotal.modifications).reduce((a, b) => a + b, 0))
      .toBe(2);
    expect(authorActivity(changes)).toHaveLength(1);
    expect(authorActivity(changes)[0].total).toBe(2);
    expect(countableChanges(changes)).toHaveLength(2);
  });
});

describe('undo availability in the list', () => {
  function entry(overrides: Partial<ChangeEntry>): ChangeEntry {
    return {
      id: 'e1',
      timestamp: new Date('2026-10-13T10:00:00Z'),
      author: 'sam',
      type: 'class',
      action: 'added',
      entityUri: 'http://ex/A',
      entityLabel: 'A',
      description: '',
      operationType: 'createClass',
      ...overrides
    } as ChangeEntry;
  }

  it('follows the server flag when it is present', () => {
    expect(canUndoEntry(entry({ undoable: false, undoReason: 'Others changed it' }))).toBe(false);
    expect(canUndoEntry(entry({ undoable: true, entityUri: '' }))).toBe(true);
  });

  it('keeps showing an undone entry as undoable-state so its redo stays reachable', () => {
    expect(canUndoEntry(entry({ undoable: false, reverted: true }))).toBe(true);
  });

  it('falls back to the old rule for an older server', () => {
    expect(canUndoEntry(entry({}))).toBe(true);
    expect(canUndoEntry(entry({ entityUri: '' }))).toBe(false);
    expect(canUndoEntry(entry({ operationType: 'codeViewStructuralEdit' }))).toBe(false);
    expect(canUndoEntry(entry({ id: '' }))).toBe(false);
    expect(canUndoEntry(entry({ operationType: 'ROLLBACK_CHANGESET' }))).toBe(false);
    expect(canUndoEntry(entry({ operationType: 'REDO_CHANGESET' }))).toBe(false);
    expect(canUndoEntry(entry({ operationType: 'projectImported' }))).toBe(false);
  });

  it('explains why a row cannot be undone', () => {
    const row = buildEntityRow(entry({ undoable: false, undoReason: 'Others changed A since' }));
    expect(row.badge).toBe("Can't undo: Others changed A since");
    expect(buildEntityRow(entry({ undoable: false })).badge).toBe("Can't undo");
  });

  it('shows prefix changes as undoable when the server says so', () => {
    const row = buildEntityRow(entry({
      operationType: 'prefixAdded',
      entityUri: '',
      entityLabel: 'ex',
      newValue: 'http://ex/',
      undoable: true
    }));
    expect(row.undoable).toBe(true);
    expect(row.label).toBe('ex');
  });
});

describe('server time', () => {
  it('reads a timestamp without a zone as UTC', () => {
    expect(parseServerTime('2026-10-14T23:37:51.416').toISOString()).toBe('2026-10-14T23:37:51.416Z');
    expect(parseServerTime('2026-10-14T23:37:51').toISOString()).toBe('2026-10-14T23:37:51.000Z');
  });

  it('leaves timestamps that carry a zone or an offset alone', () => {
    expect(parseServerTime('2026-10-14T23:37:51Z').toISOString()).toBe('2026-10-14T23:37:51.000Z');
    expect(parseServerTime('2026-10-15T04:37:51+05:00').toISOString()).toBe('2026-10-14T23:37:51.000Z');
    expect(parseServerTime(Date.UTC(2026, 9, 14, 23, 37, 51)).toISOString()).toBe('2026-10-14T23:37:51.000Z');
  });

  it('puts an undo after the edit it undoes whatever zone the browser is in', () => {
    const edited = parseServerTime('2026-10-14T23:37:51');
    const undone = parseServerTime('2026-10-14T23:40:00');
    expect(undone.getTime()).toBeGreaterThan(edited.getTime());
  });
});

describe('prefixes saved together', () => {
  const group = {
    id: 'g1', changeSetId: 's1', operationType: 'prefixesChanged', entityLabel: '3 prefixes', undoable: true,
    description: 'Code View save changed 3 prefixes',
    subChanges: [
      { id: 'a', predicate: 'a', annotationProperty: 'prefixAdded', newValue: 'http://a/', addition: true },
      { id: 'b', predicate: 'b', annotationProperty: 'prefixModified', oldValue: 'http://o/', newValue: 'http://n/', addition: true },
      { id: 'c', predicate: 'c', annotationProperty: 'prefixDeleted', oldValue: 'http://c/', addition: false }
    ]
  } as unknown as ChangeEntry;

  it('shows one readable row per prefix and no per-row undo target', () => {
    const rows = propertyRows(group);
    expect(rows.map(r => r.label)).toEqual(['Prefix a', 'Prefix b', 'Prefix c']);
    expect(rows.every(r => r.subChangeId === null)).toBe(true);
  });

  it('is headed as a prefix change and counts every prefix as remaining', () => {
    expect(manualHeading(group).verb).toBe('Changed');
    expect(buildEntityRow(group).remaining).toBe(3);
  });
});

describe('author key', () => {
  it('groups one person once whether the entry stored their email or their user id', () => {
    const byEmail = authorKey('sam@x.com', 'sam@x.com');
    const byId = authorKey('sam@x.com', '6ab0aa07dd61926a63117ae4');
    expect(byId).toBe(byEmail);
  });

  it('keeps people with different emails apart and falls back to the name or id', () => {
    expect(authorKey('a@x.com', 'a@x.com')).not.toBe(authorKey('b@x.com', 'b@x.com'));
    expect(authorKey('Local user', '')).toBe('local user');
    expect(authorKey('', '6ab0')).toBe('6ab0');
  });
});

describe('one item of a merge shown like an AI edit', () => {
  const item = parseChange({
    ...base,
    id: 'm1#0',
    operationType: 'mergeAddStatements',
    entityIRI: 'http://x#Hawaiian',
    entityLabel: 'Hawaiian',
    undoable: true,
    setOnly: true,
    changeSetId: 'set-1',
    subChanges: [{ id: '0', predicate: 'http://x#hasBase', newValue: 'http://x#DeepPan', addition: true }]
  }) as unknown as ChangeEntry;

  it('shows the item name, an Added badge and its property rows', () => {
    const row = buildEntityRow(item);
    expect(row.label).toBe('Hawaiian');
    expect(row.badge).toBe('Added');
    expect(row.properties).toHaveLength(1);
    expect(row.properties[0].addition).toBe(true);
  });

  it('offers no undo of its own because the merge is undone as a whole', () => {
    const row = buildEntityRow(item);
    expect(entityTarget(row)).toBeNull();
    expect(propertyTarget(row, row.properties[0])).toBeNull();
  });
});

describe('a merged pull request shown as one card', () => {
  const entries = ['r1', 'r2'].map(
    (id, i) =>
      parseChange({
        ...base,
        id: `pr1#${i}`,
        operationType: 'createClass',
        entityIRI: `http://x#${id}`,
        entityLabel: id,
        setOnly: true,
        changeSetId: 'pr:7',
        description: 'Merged PR: Pizza toppings'
      }) as unknown as ChangeEntry
  );

  it('groups the items into one set titled with the pull request and offers no undo', () => {
    const items = groupChangeSets(entries);
    expect(items).toHaveLength(1);
    const set = (items[0] as any).set;
    expect(set.title).toBe('Merged PR: Pizza toppings');
    expect(setTarget(set, 'UNDO')).toBeNull();
    expect(setTarget(set, 'REDO')).toBeNull();
  });
});

describe('merged pull request items', () => {
  it('show what was done and no undo-refusal badge', () => {
    const entry = parseChange({
      ...base,
      id: 'pr1#0',
      operationType: 'createClass',
      entityIRI: 'http://x#ok',
      entityLabel: 'ok',
      setOnly: true,
      undoable: false,
      undoReason: 'Part of a merged pull request. Revert the pull request instead',
      changeSetId: 'pr:7'
    }) as unknown as ChangeEntry;
    expect(buildEntityRow(entry).badge).not.toMatch(/Can't undo/);
  });
});

describe('which changes the Change Assistant shows by default', () => {
  const draft = parseChange({ ...base, id: 'd1', draft: true }) as any;
  const publicChange = parseChange({ ...base, id: 'p1', draft: false }) as any;

  it('shows only draft changes while in draft mode and only public changes otherwise', () => {
    expect(inScope(draft, 'mode', true)).toBe(true);
    expect(inScope(publicChange, 'mode', true)).toBe(false);
    expect(inScope(draft, 'mode', false)).toBe(false);
    expect(inScope(publicChange, 'mode', false)).toBe(true);
  });

  it('shows everything when all changes are chosen', () => {
    for (const mode of [true, false]) {
      expect(inScope(draft, 'all', mode)).toBe(true);
      expect(inScope(publicChange, 'all', mode)).toBe(true);
    }
  });
});

describe('the saved count while drafting', () => {
  it('does not count draft changes as saved', () => {
    const published = parseChange({ ...base, id: 'p1', draft: false, operationType: 'createClass' }) as any;
    const drafted = parseChange({ ...base, id: 'd1', draft: true, operationType: 'createClass' }) as any;
    expect(computeStats([published, drafted], []).totalChanges).toBe(1);
    expect(computeStats([drafted], []).totalChanges).toBe(0);
  });
});

describe('loading older changes after a merged pull request', () => {
  it('continues from the position the server reports, not from the number of rows shown', () => {
    const expandedRows = Array.from({ length: 139 }, (_, i) => ({ id: `r${i}` }));
    expect(nextFeedOffset({ nextOffset: 100, changes: expandedRows }, 0)).toBe(100);
    expect(nextFeedOffset({ nextOffset: 200, changes: expandedRows }, 100)).toBe(200);
  });

  it('falls back to counting the rows when the server sends no position', () => {
    expect(nextFeedOffset({ changes: [{}, {}, {}] }, 100)).toBe(103);
    expect(nextFeedOffset({}, 5)).toBe(5);
  });
});

describe('the saved count follows what the history shows', () => {
  it('counts a merged pull request once however many items it holds', () => {
    const items = ['a', 'b', 'c'].map((name, i) => parseChange({
      ...base, id: `pr1#${i}`, operationType: 'createClass', entityIRI: `http://x#${name}`, changeSetId: 'pr:7', draft: false
    }) as any);
    const single = parseChange({ ...base, id: 's1', operationType: 'createClass', draft: false }) as any;
    expect(computeStats([...items, single], []).totalChanges).toBe(2);
    expect(countEntries(items)).toBe(1);
  });
});

describe('the status filter', () => {
  it('only offers states the scope choice cannot express', () => {
    expect(STATUS_FILTERS.map(option => option.value)).toEqual(['all', 'reverted', 'conflicted']);
  });
});

describe('a reverted pull request entry', () => {
  const entryOf = (overrides: Record<string, unknown>) => parseChange({
    ...base, id: 'rev1', operationType: 'prReverted', entityIRI: 'urn:ontocode:pr:7', entityLabel: 'Reverted PR: Pizza toppings', ...overrides
  }) as unknown as ChangeEntry;

  it('offers one redo that names the pull request', () => {
    const set = (groupChangeSets([entryOf({})])[0] as any).set;
    expect(prReversalOf(set)).toEqual({ prId: '7', title: 'Pizza toppings' });
  });

  it('offers nothing once it was applied again or for other entries', () => {
    expect(prReversalOf((groupChangeSets([entryOf({ reverted: true })])[0] as any).set)).toBeNull();
    expect(prReversalOf((groupChangeSets([entryOf({ operationType: 'createClass' })])[0] as any).set)).toBeNull();
  });
});

describe('a merged pull request card', () => {
  const item = (overrides: Record<string, unknown>) => parseChange({
    ...base, id: 'pr1#0', operationType: 'createClass', entityIRI: 'http://x#ok', setOnly: true, changeSetId: 'pr:7',
    description: 'Merged PR: Pizza toppings', ...overrides
  }) as unknown as ChangeEntry;

  it('offers Revert while the pull request is live and Redo once it is reverted', () => {
    const live = (groupChangeSets([item({})])[0] as any).set;
    expect(mergedPrOf(live)).toEqual({ prId: '7', title: 'Pizza toppings', reverted: false });
    const reverted = (groupChangeSets([item({ mergedPrReverted: true })])[0] as any).set;
    expect(mergedPrOf(reverted)?.reverted).toBe(true);
  });

  it('is not a merged pull request card for ordinary change sets', () => {
    expect(mergedPrOf((groupChangeSets([item({ changeSetId: 'set-1' })])[0] as any).set)).toBeNull();
  });
});

describe('ontology IRI change', () => {
  const header = (op: string, uri: string, id: string): ChangeEntry => ({
    id, timestamp: new Date('2026-10-25T08:00:00Z'), author: 'Local user', type: 'axiom', action: op.startsWith('add') ? 'added' : 'removed',
    entityUri: uri, entityLabel: 'ontology header', description: 'Ontology header: type=Ontology', operationType: op,
    changeSetId: 'cs-iri', source: 'MANUAL'
  });

  it('is named by the old and the new IRI instead of a generic Code View save', () => {
    const entries = [header('removeStatement', 'http://x/pizza', 'a'), header('addStatement', 'http://x/pizzaas', 'b')];
    expect(ontologyIriChange(entries)).toEqual({ from: 'http://x/pizza', to: 'http://x/pizzaas' });
    const items = groupChangeSets(entries);
    expect((items[0] as any).set.title).toBe('Ontology IRI changed: http://x/pizza → http://x/pizzaas');
  });

  it('leaves other header edits alone', () => {
    expect(ontologyIriChange([header('addStatement', 'http://x/pizza', 'a')])).toBeNull();
    expect(ontologyIriChange([header('removeStatement', 'http://x/pizza', 'a'), header('addStatement', 'http://x/pizza', 'b')])).toBeNull();
  });
});

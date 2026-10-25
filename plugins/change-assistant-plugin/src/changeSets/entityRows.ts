import { ChangeEntry, EntityRow, PropertyRow, SetHeading, SubChange } from './types';
import { formatValue, predicateLabel } from './predicateLabels';
import { toDate } from './timeFormat';

const ENTITY_NOUNS: Record<string, string> = {
  class: 'class',
  property: 'property',
  individual: 'individual'
};

const AXIOM_OPERATION = /subclassof|subpropertyof|disjoint|equivalent|statement|assertion|annotation|domain|range|inverse|characteristic/i;

export function entityBadge(entry: ChangeEntry): string {
  if (entry.operationType === 'SPREADSHEET_IMPORT') return 'Imported';
  const noun = AXIOM_OPERATION.test(entry.operationType || '') ? undefined : ENTITY_NOUNS[entry.type];
  if (entry.action === 'added') return noun ? `Created ${noun}` : 'Added';
  if (entry.action === 'deleted') return noun ? `Deleted ${noun}` : 'Removed';
  return 'Modified';
}

function subChangeRow(entry: ChangeEntry, sc: SubChange): PropertyRow {
  const raw = (sc.addition ? sc.newValue ?? sc.oldValue : sc.oldValue ?? sc.newValue) ?? '';
  const undone = !!(sc.reverted || entry.reverted);
  return {
    key: `${entry.id}:${sc.id}`,
    changeId: entry.id,
    subChangeId: sc.id,
    label: predicateLabel(sc.predicate || sc.annotationProperty),
    value: formatValue(raw),
    rawValue: raw,
    addition: sc.addition,
    undone,
    undoneBy: sc.revertedBy || entry.revertedBy,
    undoneAt: toDate(sc.revertedAt || entry.revertedAt),
    redoAuditId: sc.reverted && !entry.reverted && !sc.revertedWithSet ? sc.revertedAuditId : undefined
  };
}

function valueRow(entry: ChangeEntry, raw: string, addition: boolean): PropertyRow {
  return {
    key: `${entry.id}:${addition ? 'new' : 'old'}`,
    changeId: entry.id,
    subChangeId: null,
    label: 'Value',
    value: formatValue(raw),
    rawValue: raw,
    addition,
    undone: !!entry.reverted,
    undoneBy: entry.revertedBy,
    undoneAt: toDate(entry.revertedAt)
  };
}

function prefixRow(entry: ChangeEntry, raw: string, addition: boolean): PropertyRow {
  return {
    ...valueRow(entry, raw, addition),
    label: `Prefix ${entry.entityLabel || ''}`.trim(),
    value: raw
  };
}

const PREFIX_GROUP = 'prefixesChanged';

function prefixGroupRows(entry: ChangeEntry): PropertyRow[] {
  return (entry.subChanges || []).map(sc => {
    const raw = (sc.addition ? sc.newValue ?? sc.oldValue : sc.oldValue ?? sc.newValue) ?? '';
    return {
      ...valueRow(entry, raw, !!sc.addition),
      key: `${entry.id}:${sc.id}`,
      label: `Prefix ${sc.predicate || ''}`.trim(),
      value: raw
    };
  });
}

export function propertyRows(entry: ChangeEntry): PropertyRow[] {
  if (entry.operationType === PREFIX_GROUP && (entry.subChanges || []).length > 0) return prefixGroupRows(entry);
  if (PREFIX_VERBS[entry.operationType || '']) {
    const prefixRows: PropertyRow[] = [];
    if (entry.oldValue) prefixRows.push(prefixRow(entry, entry.oldValue, false));
    if (entry.newValue) prefixRows.push(prefixRow(entry, entry.newValue, true));
    return prefixRows;
  }
  const subs = entry.subChanges || [];
  if (subs.length > 0) return subs.map(sc => subChangeRow(entry, sc));
  const rows: PropertyRow[] = [];
  if (entry.oldValue) rows.push(valueRow(entry, entry.oldValue, false));
  if (entry.newValue) rows.push(valueRow(entry, entry.newValue, true));
  return rows;
}

function latestUndo(rows: PropertyRow[]): PropertyRow | undefined {
  return rows
    .filter(r => r.undoneAt)
    .sort((a, b) => (b.undoneAt as Date).getTime() - (a.undoneAt as Date).getTime())[0];
}

export function canUndoEntry(entry: ChangeEntry): boolean {
  if (typeof entry.undoable === 'boolean') return entry.undoable || !!entry.reverted;
  const operation = entry.operationType || '';
  return !!entry.entityUri && !!entry.id && !/structural/i.test(operation) && !/^(ROLLBACK_|REDO_)/i.test(operation)
    && operation !== 'projectImported';
}

function otherChangesLabel(entry: ChangeEntry): string {
  if (entry.operationType === 'projectImported') return 'File imported';
  const count = (entry.description || '').match(/modified (\d+) structural/i);
  if (count) return `${count[1]} other axioms`;
  return /structural/i.test(entry.operationType || '') ? 'Other axioms' : 'Other changes';
}

export function buildEntityRow(entry: ChangeEntry): EntityRow {
  const properties = propertyRows(entry);
  const subs = entry.subChanges || [];
  const undoable = canUndoEntry(entry);
  const allSubsUndone = subs.length > 0 && subs.every(sc => sc.reverted);
  const undone = !!entry.reverted || allSubsUndone;
  const remaining = !undoable || undone ? 0 : subs.length > 0 ? subs.filter(sc => !sc.reverted).length : 1;
  const last = latestUndo(properties);
  return {
    entry,
    undoable,
    label: entry.entityLabel || (undoable ? 'Unnamed entity' : otherChangesLabel(entry)),
    badge: undoable || entry.setOnly ? entityBadge(entry) : entry.undoReason ? `Can't undo: ${entry.undoReason}` : "Can't undo",
    properties,
    undone,
    undoneBy: entry.revertedBy || last?.undoneBy,
    undoneAt: toDate(entry.revertedAt) || last?.undoneAt,
    redoAuditId: entry.reverted && !entry.revertedWithSet ? entry.revertedAuditId : undefined,
    remaining
  };
}

const PREFIX_VERBS: Record<string, string> = {
  prefixAdded: 'Added',
  prefixModified: 'Changed',
  prefixDeleted: 'Removed'
};

export function manualHeading(entry: ChangeEntry): SetHeading {
  if (entry.operationType === 'projectImported') {
    return { verb: 'Imported', name: entry.entityLabel || (entry.description || '').replace(/^Imported\s+/, '') || 'file' };
  }
  if (entry.operationType === 'SPREADSHEET_IMPORT') {
    const counts = (entry.description || '').match(/Imported ([\d,]+ axioms?) from .* \(([\d,]+ rows?)\)$/);
    return {
      verb: 'Imported',
      name: entry.entityLabel || 'spreadsheet',
      ...(counts ? { detail: `${counts[1]} from ${counts[2]}` } : {})
    };
  }
  if (entry.operationType === PREFIX_GROUP) {
    return { verb: 'Changed', name: entry.entityLabel || 'prefixes', ...(entry.description ? { detail: entry.description } : {}) };
  }
  const prefixVerb = PREFIX_VERBS[entry.operationType || ''];
  if (prefixVerb) {
    return {
      verb: prefixVerb,
      name: `prefix ${entry.entityLabel || ''}`.trim(),
      ...(entry.description ? { detail: entry.description } : {})
    };
  }
  const badge = entityBadge(entry);
  const parent = entry.reverted ? undefined
    : (entry.subChanges || []).find(sc => sc.addition && !sc.reverted && sc.newValue && /subClassOf$/.test(sc.predicate || ''));
  return {
    verb: badge === 'Modified' ? 'Edited' : badge,
    name: entry.entityLabel || 'Unnamed entity',
    ...(parent ? { detail: `Subclass of ${formatValue(parent.newValue)}` } : {})
  };
}

export function changeCount(entry: ChangeEntry): number {
  return Math.max(1, (entry.subChanges || []).length);
}

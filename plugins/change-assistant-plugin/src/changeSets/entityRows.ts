import { ChangeEntry, EntityRow, PropertyRow, SetHeading, SubChange } from './types';
import { formatValue, predicateLabel } from './predicateLabels';
import { toDate } from './timeFormat';

const ENTITY_NOUNS: Record<string, string> = {
  class: 'class',
  property: 'property',
  individual: 'individual'
};

export function entityBadge(entry: ChangeEntry): string {
  const noun = ENTITY_NOUNS[entry.type];
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

export function propertyRows(entry: ChangeEntry): PropertyRow[] {
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
  return !!entry.entityUri && !/structural/i.test(entry.operationType || '');
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
    badge: undoable ? entityBadge(entry) : "Can't undo",
    properties,
    undone,
    undoneBy: entry.revertedBy || last?.undoneBy,
    undoneAt: toDate(entry.revertedAt) || last?.undoneAt,
    redoAuditId: entry.reverted && !entry.revertedWithSet ? entry.revertedAuditId : undefined,
    remaining
  };
}

export function manualHeading(entry: ChangeEntry): SetHeading {
  if (entry.operationType === 'projectImported') {
    return { verb: 'Imported', name: entry.entityLabel || (entry.description || '').replace(/^Imported\s+/, '') || 'file' };
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

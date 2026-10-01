import { ChangeEntry, ChangeSet, DaySection, Direction, EntityRow, ListItem, RollbackEvent } from './types';
import { buildEntityRow, canUndoEntry, changeCount, manualHeading } from './entityRows';
import { dayLabel, formatTime, plural } from './timeFormat';

export const ENTITY_CAP = 20;

export function isRollbackEntry(entry: ChangeEntry): boolean {
  const op = (entry.operationType || '').toUpperCase();
  return entry.source === 'ROLLBACK' || op.startsWith('ROLLBACK_') || op.startsWith('REDO_');
}

export function rollbackDirection(entry: ChangeEntry): Direction {
  return (entry.operationType || '').toUpperCase().startsWith('REDO_') ? 'REDO' : 'UNDO';
}

function setKey(entry: ChangeEntry): string {
  return entry.changeSetId ? `set:${entry.changeSetId}` : `entry:${entry.id}`;
}

function bucket(entries: ChangeEntry[]): Map<string, ChangeEntry[]> {
  const groups = new Map<string, ChangeEntry[]>();
  for (const entry of entries) {
    const key = setKey(entry);
    const list = groups.get(key);
    if (list) list.push(entry);
    else groups.set(key, [entry]);
  }
  return groups;
}

function toRollbackEvent(key: string, entries: ChangeEntry[]): RollbackEvent {
  const first = entries[0];
  return {
    id: key,
    direction: rollbackDirection(first),
    author: first.author,
    timestamp: newest(entries),
    description: first.description,
    targetChangeSetId: first.revertsChangeSetId || null,
    auditId: first.rollbackAuditId || null,
    entityUri: first.entityUri || undefined
  };
}

function newest(entries: ChangeEntry[]): Date {
  return entries.reduce((max, e) => (e.timestamp > max ? e.timestamp : max), entries[0].timestamp);
}

export function setTitle(source: 'AI' | 'MANUAL', entries: ChangeEntry[], entityCount: number, summary?: string): string {
  if (source === 'AI') return summary?.trim() || 'AI edit';
  if (entityCount > 1) return 'Code View save';
  return entries[0].description || entries[0].entityLabel || 'Manual edit';
}

function undoInfo(entities: EntityRow[]): { undoneBy?: string; undoneAt?: Date } {
  const latest = entities
    .filter(e => e.undoneAt)
    .sort((a, b) => (b.undoneAt as Date).getTime() - (a.undoneAt as Date).getTime())[0];
  const withUser = latest || entities.find(e => e.undoneBy);
  return { undoneBy: withUser?.undoneBy, undoneAt: latest?.undoneAt };
}

export function buildChangeSet(key: string, entries: ChangeEntry[]): ChangeSet {
  const sorted = [...entries].sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime());
  const entities = sorted.map(buildEntityRow);
  const aiEntry = sorted.find(e => e.source === 'AI');
  const source = aiEntry ? 'AI' : 'MANUAL';
  const ai = aiEntry?.ai || null;
  const entityCount = new Set(sorted.filter(e => e.entityUri).map(e => e.entityUri)).size || 1;
  const remaining = entities.reduce((sum, e) => sum + e.remaining, 0);
  const undoable = entities.filter(e => e.undoable);
  const fullyUndone = undoable.length > 0 && undoable.every(e => e.undone);
  const heading = source === 'MANUAL' && entityCount === 1 ? manualHeading(sorted[0]) : undefined;
  return {
    key,
    changeSetId: sorted[0].changeSetId || null,
    source,
    ai,
    entries: sorted,
    entities,
    timestamp: newest(sorted),
    author: sorted[0].author,
    title: heading ? `${heading.verb} ${heading.name}` : setTitle(source, sorted, entityCount, ai?.summary),
    heading,
    entityCount,
    changeCount: sorted.reduce((sum, e) => sum + changeCount(e), 0),
    remaining,
    fullyUndone,
    ...(fullyUndone ? undoInfo(undoable) : {}),
    history: []
  };
}

function attachHistory(sets: Map<string, ChangeSet>, events: RollbackEvent[]): void {
  for (const event of events) {
    const target = event.targetChangeSetId ? sets.get(`set:${event.targetChangeSetId}`) : undefined;
    if (target) {
      target.history.push(event);
      event.targetTitle = target.title;
    }
  }
  sets.forEach(set => set.history.sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime()));
}

export function groupChangeSets(entries: ChangeEntry[]): ListItem[] {
  const sets = new Map<string, ChangeSet>();
  const events: RollbackEvent[] = [];
  bucket(entries.filter(e => !isRollbackEntry(e))).forEach((list, key) => sets.set(key, buildChangeSet(key, list)));
  bucket(entries.filter(isRollbackEntry)).forEach((list, key) => events.push(toRollbackEvent(key, list)));
  attachHistory(sets, events);
  const items: ListItem[] = [
    ...Array.from(sets.values()).map(set => ({ kind: 'set' as const, key: set.key, timestamp: set.timestamp, set })),
    ...events.map(event => ({ kind: 'rollback' as const, key: event.id, timestamp: event.timestamp, event }))
  ];
  return items.sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime());
}

export interface TargetIndex {
  byAudit: Map<string, string>;
  setKeys: Set<string>;
  byEntity: Map<string, ChangeSet[]>;
}

export function buildTargetIndex(items: ListItem[]): TargetIndex {
  const byAudit = new Map<string, string>();
  const setKeys = new Set<string>();
  const byEntity = new Map<string, ChangeSet[]>();
  for (const item of items) {
    if (item.kind !== 'set') continue;
    const set = item.set;
    setKeys.add(set.key);
    for (const e of set.entries) {
      if (e.revertedAuditId && !byAudit.has(e.revertedAuditId)) byAudit.set(e.revertedAuditId, set.key);
      for (const sc of e.subChanges || []) {
        if (sc.revertedAuditId && !byAudit.has(sc.revertedAuditId)) byAudit.set(sc.revertedAuditId, set.key);
      }
      if (e.entityUri) {
        const list = byEntity.get(e.entityUri);
        if (!list) byEntity.set(e.entityUri, [set]);
        else if (list[list.length - 1] !== set) list.push(set);
      }
    }
  }
  byEntity.forEach(list => list.sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime()));
  return { byAudit, setKeys, byEntity };
}

export function targetSetKey(event: RollbackEvent, index: TargetIndex): string | null {
  const byAudit = event.auditId ? index.byAudit.get(event.auditId) : undefined;
  if (byAudit) return byAudit;
  if (event.targetChangeSetId) {
    const key = `set:${event.targetChangeSetId}`;
    if (index.setKeys.has(key)) return key;
  }
  if (event.entityUri) {
    const hit = (index.byEntity.get(event.entityUri) || []).find(s => s.timestamp <= event.timestamp);
    if (hit) return hit.key;
  }
  return null;
}

export function cardDomId(key: string): string {
  return `cs-card-${key.replace(/[^a-zA-Z0-9_-]/g, '_')}`;
}

export function groupByDay(items: ListItem[], now: Date = new Date()): DaySection[] {
  const sections: DaySection[] = [];
  for (const item of items) {
    const label = dayLabel(item.timestamp, now);
    const last = sections[sections.length - 1];
    if (last && last.label === label) last.items.push(item);
    else sections.push({ label, items: [item] });
  }
  return sections;
}

export function setSubtitle(set: ChangeSet, now: Date = new Date()): string {
  const counts = `${plural(set.entityCount, 'entity', 'entities')}, ${plural(set.changeCount, 'change', 'changes')}`;
  const time = formatTime(set.timestamp, now);
  if (set.source !== 'AI') return `${set.author} · ${time} · ${counts}`;
  const via = set.ai?.model ? `${set.ai.model} via Ask AI` : 'Ask AI';
  return `${via} · applied by ${set.author} · ${time} · ${counts}`;
}

export function undoneNote(by?: string, at?: Date, now: Date = new Date()): string {
  const parts = ['Undone'];
  if (by) parts[0] = `Undone by ${by}`;
  if (at) parts.push(formatTime(at, now));
  return parts.join(' · ');
}

export function flattensEntity(set: ChangeSet): boolean {
  return set.entities.length === 1 && set.entities[0].properties.length > 0;
}

export function isSingleLine(entity: EntityRow): boolean {
  return entity.properties.length === 1;
}

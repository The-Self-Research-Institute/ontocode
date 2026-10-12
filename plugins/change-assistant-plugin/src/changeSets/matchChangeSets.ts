import { ChangeSet, ListItem, RollbackEvent, SourceFilter } from './types';

function includes(text: string | undefined | null, query: string): boolean {
  return !!text && text.toLowerCase().includes(query);
}

export function setMatches(set: ChangeSet, query: string): boolean {
  if (includes(set.title, query) || includes(set.ai?.summary, query) || includes(set.author, query)) return true;
  return set.entities.some(entity =>
    includes(entity.label, query) ||
    includes(entity.entry.description, query) ||
    entity.properties.some(p => includes(p.value, query) || includes(p.rawValue, query) || includes(p.label, query))
  );
}

function rollbackMatches(event: RollbackEvent, query: string): boolean {
  return includes(event.description, query) || includes(event.author, query);
}

function sourceAllows(item: ListItem, source: SourceFilter): boolean {
  if (source === 'all') return true;
  return item.kind === 'set' && item.set.source === source;
}

export interface FilterResult {
  items: ListItem[];
  matchedKeys: Set<string>;
}

export function filterItems(items: ListItem[], rawQuery: string, source: SourceFilter): FilterResult {
  const query = rawQuery.trim().toLowerCase();
  const matchedKeys = new Set<string>();
  const visible = items.filter(item => {
    if (!sourceAllows(item, source)) return false;
    if (!query) return true;
    const hit = item.kind === 'set' ? setMatches(item.set, query) : rollbackMatches(item.event, query);
    if (hit) matchedKeys.add(item.key);
    return hit;
  });
  return { items: visible, matchedKeys };
}

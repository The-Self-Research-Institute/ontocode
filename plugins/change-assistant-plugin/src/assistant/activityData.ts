import { authorKey } from './changeKinds';
import { countableChanges } from './changeParsing';
import { OntologyChange } from './types';

type Tally = { additions: number; deletions: number; modifications: number };

export function weekdayActivity(changes: OntologyChange[], draftChanges: OntologyChange[]) {
  const dayNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const dayStats: { [key: string]: Tally } = {};
  dayNames.forEach(day => {
    dayStats[day] = { additions: 0, deletions: 0, modifications: 0 };
  });
  [...countableChanges(changes), ...draftChanges].forEach(change => {
    const dayOfWeek = dayNames[change.timestamp.getDay()];
    if (change.action === 'added') dayStats[dayOfWeek].additions++;
    else if (change.action === 'deleted') dayStats[dayOfWeek].deletions++;
    else dayStats[dayOfWeek].modifications++;
  });
  const orderedDays = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  return {
    labels: orderedDays,
    additions: orderedDays.map(day => dayStats[day].additions),
    deletions: orderedDays.map(day => dayStats[day].deletions),
    modifications: orderedDays.map(day => dayStats[day].modifications)
  };
}

export function authorActivity(changes: OntologyChange[]) {
  const authorMap = new Map<string, { name: string; tally: Tally }>();
  countableChanges(changes).forEach(change => {
    const key = authorKey(change.author, change.authorEmail);
    const existing = authorMap.get(key) || { name: change.author, tally: { additions: 0, deletions: 0, modifications: 0 } };
    if (change.action === 'added') existing.tally.additions++;
    else if (change.action === 'deleted') existing.tally.deletions++;
    else if (change.action === 'modified') existing.tally.modifications++;
    authorMap.set(key, existing);
  });
  return Array.from(authorMap.values()).map(({ name, tally }) => ({
    author: name,
    ...tally,
    total: tally.additions + tally.deletions + tally.modifications
  })).sort((a, b) => b.total - a.total);
}

export function timelineItems(changes: OntologyChange[]) {
  return countableChanges(changes).map(c => ({
    id: c.id,
    timestamp: c.timestamp,
    author: c.author,
    type: c.type,
    action: c.action,
    entityLabel: c.entityLabel,
    description: c.description,
    status: c.status,
    hasConflict: (c.conflicts?.length || 0) > 0
  }));
}

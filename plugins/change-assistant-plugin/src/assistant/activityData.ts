import { OntologyChange } from './types';

type Tally = { additions: number; deletions: number; modifications: number };

export function weekdayActivity(changes: OntologyChange[], draftChanges: OntologyChange[]) {
  const dayNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const dayStats: { [key: string]: Tally } = {};
  dayNames.forEach(day => {
    dayStats[day] = { additions: 0, deletions: 0, modifications: 0 };
  });
  [...changes, ...draftChanges].forEach(change => {
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
  const authorMap = new Map<string, Tally>();
  changes.forEach(change => {
    const existing = authorMap.get(change.author) || { additions: 0, deletions: 0, modifications: 0 };
    if (change.action === 'added') existing.additions++;
    else if (change.action === 'deleted') existing.deletions++;
    else if (change.action === 'modified') existing.modifications++;
    authorMap.set(change.author, existing);
  });
  return Array.from(authorMap.entries()).map(([author, stats]) => ({
    author,
    ...stats,
    total: stats.additions + stats.deletions + stats.modifications
  })).sort((a, b) => b.total - a.total);
}

export function timelineItems(changes: OntologyChange[]) {
  return changes.map(c => ({
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

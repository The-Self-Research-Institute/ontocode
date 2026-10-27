import { useEffect, useMemo, useState } from 'react';
import { SourceFilter } from '../../changeSets/types';
import { changeMatchesType } from '../changeParsing';
import { ChangeStatus, ChangeType, OntologyChange } from '../types';

export function changeMatchesSearch(change: OntologyChange, rawQuery: string): boolean {
  const query = rawQuery.trim().toLowerCase();
  if (!query) return true;
  return [change.entityLabel, change.description, change.entityUri, change.oldValue, change.newValue, change.author]
    .some(text => !!text && text.toLowerCase().includes(query));
}

export type ViewScope = 'mode' | 'all';

export function inScope(change: OntologyChange, scope: ViewScope, isDraftActive: boolean): boolean {
  if (scope === 'all') return true;
  return isDraftActive ? !!change.draft : !change.draft;
}

export function useChangeFilters(changes: OntologyChange[], drafts: OntologyChange[] = [], isDraftActive = false) {
  const [viewScope, setViewScope] = useState<ViewScope>('mode');
  useEffect(() => setViewScope('mode'), [isDraftActive]);
  const [filterType, setFilterType] = useState<ChangeType | 'all'>('all');
  const [filterStatus, setFilterStatus] = useState<ChangeStatus | 'all'>('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>('all');

  const filteredChanges = changes.filter(change => {
    if (!inScope(change, viewScope, isDraftActive)) return false;
    if (filterType !== 'all' && !changeMatchesType(change, filterType)) return false;
    if (filterStatus !== 'all' && change.status !== filterStatus) return false;
    return changeMatchesSearch(change, searchQuery);
  });

  const filteredDrafts = useMemo(() => drafts.filter(draft =>
    (filterType === 'all' || changeMatchesType(draft, filterType)) &&
    (filterStatus === 'all' || filterStatus === 'draft') &&
    changeMatchesSearch(draft, searchQuery)
  ), [drafts, filterType, filterStatus, searchQuery]);

  const typeFilteredChanges = useMemo(() => changes.filter(change =>
    inScope(change, viewScope, isDraftActive) &&
    (filterType === 'all' || changeMatchesType(change, filterType)) &&
    (filterStatus === 'all' || change.status === filterStatus)
  ), [changes, filterType, filterStatus, viewScope, isDraftActive]);

  return {
    filterType, setFilterType, filterStatus, setFilterStatus,
    searchQuery, setSearchQuery, sourceFilter, setSourceFilter, viewScope, setViewScope, isDraftActive,
    filteredChanges, typeFilteredChanges, filteredDrafts
  };
}

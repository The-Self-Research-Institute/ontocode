import { useMemo, useState } from 'react';
import { SourceFilter } from '../../changeSets/types';
import { changeMatchesType } from '../changeParsing';
import { ChangeStatus, ChangeType, OntologyChange } from '../types';

export function changeMatchesSearch(change: OntologyChange, rawQuery: string): boolean {
  const query = rawQuery.trim().toLowerCase();
  if (!query) return true;
  return [change.entityLabel, change.description, change.entityUri, change.oldValue, change.newValue, change.author]
    .some(text => !!text && text.toLowerCase().includes(query));
}

export function useChangeFilters(changes: OntologyChange[], drafts: OntologyChange[] = []) {
  const [filterType, setFilterType] = useState<ChangeType | 'all'>('all');
  const [filterStatus, setFilterStatus] = useState<ChangeStatus | 'all'>('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>('all');

  const filteredChanges = changes.filter(change => {
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
    (filterType === 'all' || changeMatchesType(change, filterType)) &&
    (filterStatus === 'all' || change.status === filterStatus)
  ), [changes, filterType, filterStatus]);

  return {
    filterType, setFilterType, filterStatus, setFilterStatus,
    searchQuery, setSearchQuery, sourceFilter, setSourceFilter,
    filteredChanges, typeFilteredChanges, filteredDrafts
  };
}

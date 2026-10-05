import { useMemo, useState } from 'react';
import { SourceFilter } from '../../changeSets/types';
import { ChangeStatus, ChangeType, OntologyChange } from '../types';

export function useChangeFilters(changes: OntologyChange[]) {
  const [filterType, setFilterType] = useState<ChangeType | 'all'>('all');
  const [filterStatus, setFilterStatus] = useState<ChangeStatus | 'all'>('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>('all');

  const filteredChanges = changes.filter(change => {
    if (filterType !== 'all' && change.type !== filterType) return false;
    if (filterStatus !== 'all' && change.status !== filterStatus) return false;
    if (searchQuery && !change.entityLabel.toLowerCase().includes(searchQuery.toLowerCase()) &&
        !change.description.toLowerCase().includes(searchQuery.toLowerCase())) return false;
    return true;
  });

  const typeFilteredChanges = useMemo(() => changes.filter(change =>
    (filterType === 'all' || change.type === filterType) &&
    (filterStatus === 'all' || change.status === filterStatus)
  ), [changes, filterType, filterStatus]);

  return {
    filterType, setFilterType, filterStatus, setFilterStatus,
    searchQuery, setSearchQuery, sourceFilter, setSourceFilter,
    filteredChanges, typeFilteredChanges
  };
}

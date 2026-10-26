import React from 'react';
import { Search } from 'lucide-react';
import { SourceFilter } from '../changeSets/types';
import type { ViewScope } from './hooks/useChangeFilters';
import { ChangeStatus, ChangeType } from './types';
import { STATUS_FILTERS } from './statusFilters';

interface FilterBarProps {
  searchQuery: string;
  setSearchQuery: (value: string) => void;
  filterType: ChangeType | 'all';
  setFilterType: (value: ChangeType | 'all') => void;
  filterStatus: ChangeStatus | 'all';
  setFilterStatus: (value: ChangeStatus | 'all') => void;
  sourceFilter: SourceFilter;
  setSourceFilter: (value: SourceFilter) => void;
  viewScope: ViewScope;
  setViewScope: (value: ViewScope) => void;
  isDraftActive: boolean;
}

const FilterBar: React.FC<FilterBarProps> = (props) => (
  <div className="space-y-2">
    <div className="relative">
      <Search className="absolute left-3 top-2.5 w-4 h-4 text-gray-400" />
      <input
        type="text"
        placeholder="Search changes..."
        value={props.searchQuery}
        onChange={(e) => props.setSearchQuery(e.target.value)}
        className="w-full pl-9 pr-3 py-2 border rounded text-sm"
      />
    </div>
    <div className="flex flex-wrap gap-2">
      <select
        value={props.viewScope}
        onChange={(e) => props.setViewScope(e.target.value as ViewScope)}
        className="flex-1 px-2 py-1 border rounded text-sm"
        aria-label="Scope"
      >
        <option value="mode">{props.isDraftActive ? 'Draft changes' : 'Public changes'}</option>
        <option value="all">All changes</option>
      </select>
      <select
        value={props.filterType}
        onChange={(e) => props.setFilterType(e.target.value as ChangeType | 'all')}
        className="flex-1 px-2 py-1 border rounded text-sm"
      >
        <option value="all">All Types</option>
        <option value="class">Classes</option>
        <option value="property">Properties</option>
        <option value="individual">Individuals</option>
        <option value="axiom">Axioms</option>
        <option value="annotation">Annotations</option>
        <option value="import">Imports</option>
        <option value="datatype">Datatypes</option>
        <option value="prefix">Prefixes</option>
        <option value="rename">Renames</option>
        <option value="rule">Rules</option>
        <option value="citation">Citations</option>
      </select>
      <select
        value={props.filterStatus}
        onChange={(e) => props.setFilterStatus(e.target.value as ChangeStatus | 'all')}
        className="flex-1 px-2 py-1 border rounded text-sm"
      >
        {STATUS_FILTERS.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
      </select>
      <select
        value={props.sourceFilter}
        onChange={(e) => props.setSourceFilter(e.target.value as SourceFilter)}
        className="flex-1 px-2 py-1 border rounded text-sm"
        aria-label="Source"
      >
        <option value="all">All sources</option>
        <option value="AI">AI edits</option>
        <option value="MANUAL">Manual edits</option>
      </select>
    </div>
  </div>
);

export default FilterBar;

import React from 'react';
import { GitBranch, RefreshCw } from 'lucide-react';
import { getRelativeTime } from './display';
import FilterBar from './FilterBar';
import StatCards from './StatCards';
import { ChangeStats } from './types';

interface AssistantHeaderProps {
  isLoading: boolean;
  lastRefresh: Date;
  stats: ChangeStats;
  onRefresh: () => void;
  filters: React.ComponentProps<typeof FilterBar>;
  isDraftActive: boolean;
}

const AssistantHeader: React.FC<AssistantHeaderProps> = ({ isLoading, lastRefresh, stats, onRefresh, filters, isDraftActive }) => (
  <div className="border-b p-4">
    <div className="flex items-center justify-between mb-4">
      <div className="flex items-center gap-2">
        <GitBranch className="w-5 h-5 text-purple-600" />
        <h2 className="text-lg font-semibold">Change Assistant</h2>
        {isLoading && <RefreshCw className="w-4 h-4 text-gray-400 animate-spin" />}
      </div>
      <div className="flex items-center gap-2">
        <span className="text-xs text-gray-500">
          Updated {getRelativeTime(lastRefresh)}
        </span>
        <button
          onClick={() => onRefresh()}
          disabled={isLoading}
          className="px-3 py-1 text-sm bg-purple-600 text-white rounded hover:bg-purple-700 disabled:opacity-50"
        >
          Refresh
        </button>
      </div>
    </div>
    <StatCards stats={stats} isDraftActive={isDraftActive} />
    <FilterBar {...filters} />
  </div>
);

export default AssistantHeader;

import React from 'react';
import { Activity, AlertTriangle, BarChart3, Edit3, GitCommit, History } from 'lucide-react';
import { AssistantTab, ChangeStats } from './types';

interface TabBarProps {
  activeTab: AssistantTab;
  onSelect: (tab: AssistantTab) => void;
  liveCount: number;
  stats: ChangeStats;
}

function badgeClass(id: string): string {
  return id === 'drafts' ? 'bg-yellow-100 text-yellow-600' :
    id === 'conflicts' ? 'bg-red-100 text-red-600' :
    'bg-purple-100 text-purple-600';
}

const TabBar: React.FC<TabBarProps> = ({ activeTab, onSelect, liveCount, stats }) => (
  <div className="border-b">
    <div className="flex overflow-x-auto">
      {[
        { id: 'live', label: 'Activity', icon: Activity, count: liveCount > 0 ? liveCount : undefined },
        { id: 'drafts', label: 'Drafts', icon: Edit3, count: stats.draftChanges > 0 ? stats.draftChanges : undefined },
        { id: 'changes', label: 'Saved', icon: GitCommit, count: stats.totalChanges },
        { id: 'conflicts', label: 'Conflicts', icon: AlertTriangle, count: stats.conflicts > 0 ? stats.conflicts : undefined },
        { id: 'history', label: 'Timeline', icon: History },
        { id: 'stats', label: 'Stats', icon: BarChart3 }
      ].map(tab => (
        <button
          key={tab.id}
          onClick={() => onSelect(tab.id as AssistantTab)}
          className={`flex items-center gap-2 px-4 py-2 border-b-2 transition-colors whitespace-nowrap ${
            activeTab === tab.id
              ? 'border-purple-600 text-purple-600'
              : 'border-transparent text-gray-600 hover:text-purple-600'
          }`}
        >
          <tab.icon className="w-4 h-4" />
          {tab.label}
          {tab.count !== undefined && tab.count > 0 && (
            <span className={`px-1.5 py-0.5 text-xs rounded-full ${badgeClass(tab.id)}`}>
              {tab.count}
            </span>
          )}
        </button>
      ))}
    </div>
  </div>
);

export default TabBar;

import React from 'react';
import AuthorActivityChart from '../components/AuthorActivityChart';
import ChangeGraph from '../components/ChangeGraph';
import { authorActivity, weekdayActivity } from './activityData';
import { ChangeStats, OntologyChange } from './types';

interface StatsTabProps {
  stats: ChangeStats;
  changes: OntologyChange[];
  draftChanges: OntologyChange[];
}

const StatTile: React.FC<{ label: string; value: number; color: string }> = ({ label, value, color }) => (
  <div className="border rounded-lg p-3">
    <div className="text-sm text-gray-600 mb-1">{label}</div>
    <div className={`text-2xl font-bold ${color}`}>{value}</div>
  </div>
);

const Distribution: React.FC<{ stats: ChangeStats; changes: OntologyChange[] }> = ({ stats, changes }) => (
  <div className="border rounded-lg p-3">
    <h3 className="font-medium mb-2">Change Distribution</h3>
    <div className="space-y-2">
      {['class', 'property', 'individual', 'axiom', 'annotation', 'import'].map(type => {
        const count = changes.filter(c => c.type === type).length;
        const percentage = stats.totalChanges > 0 ? (count / stats.totalChanges * 100) : 0;
        return (
          <div key={type}>
            <div className="flex justify-between text-sm mb-1">
              <span className="capitalize">{type}</span>
              <span className="text-gray-600">{count}</span>
            </div>
            <div className="h-2 bg-gray-200 rounded-full overflow-hidden">
              <div
                className="h-full bg-purple-600"
                style={{ width: `${percentage}%` }}
              ></div>
            </div>
          </div>
        );
      })}
    </div>
  </div>
);

const StatsTab: React.FC<StatsTabProps> = ({ stats, changes, draftChanges }) => (
  <div className="space-y-4">
    <div className="grid grid-cols-2 gap-3">
      <StatTile label="Saved Changes" value={stats.totalChanges} color="text-purple-600" />
      <StatTile label="Draft Changes" value={stats.draftChanges} color="text-yellow-600" />
      <StatTile label="Active Authors" value={stats.activeAuthors} color="text-blue-600" />
      <StatTile label="Warnings" value={stats.warnings} color="text-orange-600" />
    </div>
    <Distribution stats={stats} changes={changes} />
    <ChangeGraph data={weekdayActivity(changes, draftChanges)} />
    <AuthorActivityChart data={authorActivity(changes)} />
  </div>
);

export default StatsTab;

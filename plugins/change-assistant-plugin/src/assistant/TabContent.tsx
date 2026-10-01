import React from 'react';
import ChangeSetList from '../changeSets/ChangeSetList';
import { SourceFilter } from '../changeSets/types';
import ChangeTimeline from '../components/ChangeTimeline';
import { timelineItems } from './activityData';
import ConflictsTab from './ConflictsTab';
import DraftsTab from './DraftsTab';
import LiveActivityTab from './LiveActivityTab';
import StatsTab from './StatsTab';
import { AssistantTab, ChangeStats, LiveActivity, OntologyChange } from './types';

interface TabContentProps {
  projectId: string;
  activeTab: AssistantTab;
  liveActivity: LiveActivity[];
  changes: OntologyChange[];
  draftChanges: OntologyChange[];
  stats: ChangeStats;
  filteredChanges: OntologyChange[];
  typeFilteredChanges: OntologyChange[];
  searchQuery: string;
  sourceFilter: SourceFilter;
  onRollback: () => void;
  onOpenDetails: (changeId: string) => void;
  onResolveClick: (change: OntologyChange) => void;
  onAutoResolve: (changeId: string, resolution: string) => void;
  onSelectTimeline: (changeId: string) => void;
}

const TabContent: React.FC<TabContentProps> = (props) => (
  <div className="flex-1 overflow-auto p-4">
    {props.activeTab === 'live' && <LiveActivityTab activities={props.liveActivity} />}
    {props.activeTab === 'drafts' && <DraftsTab drafts={props.draftChanges} />}
    {props.activeTab === 'changes' && (
      <ChangeSetList
        projectId={props.projectId}
        entries={props.typeFilteredChanges}
        searchQuery={props.searchQuery}
        sourceFilter={props.sourceFilter}
        onChanged={props.onRollback}
        onOpenDetails={props.onOpenDetails}
      />
    )}
    {props.activeTab === 'conflicts' && (
      <ConflictsTab changes={props.filteredChanges} onResolveClick={props.onResolveClick} onAutoResolve={props.onAutoResolve} />
    )}
    {props.activeTab === 'history' && (
      <ChangeTimeline changes={timelineItems(props.changes)} onSelectChange={props.onSelectTimeline} />
    )}
    {props.activeTab === 'stats' && (
      <StatsTab stats={props.stats} changes={props.changes} draftChanges={props.draftChanges} />
    )}
  </div>
);

export default TabContent;

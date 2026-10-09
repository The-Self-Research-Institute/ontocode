import React, { useState } from 'react';
import ConflictResolver from './components/ConflictResolver';
import AssistantHeader from './assistant/AssistantHeader';
import CommentDialog from './assistant/CommentDialog';
import DetailsDialog from './assistant/DetailsDialog';
import NotificationToast from './assistant/NotificationToast';
import TabBar from './assistant/TabBar';
import TabContent from './assistant/TabContent';
import { useChangeDetails } from './assistant/hooks/useChangeDetails';
import { useChangeFeed } from './assistant/hooks/useChangeFeed';
import { useChangeFilters } from './assistant/hooks/useChangeFilters';
import { useCommentDialog } from './assistant/hooks/useCommentDialog';
import { useConflictResolution } from './assistant/hooks/useConflictResolution';
import { useLiveActivity } from './assistant/hooks/useLiveActivity';
import { useNotification } from './assistant/hooks/useNotification';
import { AssistantTab, OntologyChange } from './assistant/types';

interface ChangeAssistantProps {
  projectId: string;
}

const ChangeAssistant: React.FC<ChangeAssistantProps> = ({ projectId }) => {
  const [activeTab, setActiveTab] = useState<AssistantTab>('live');
  const [selectedChange, setSelectedChange] = useState<OntologyChange | null>(null);
  const { notification, showNotification } = useNotification();
  const feed = useChangeFeed(projectId);
  const liveActivity = useLiveActivity(projectId, feed.loadChanges);
  const filters = useChangeFilters(feed.changes);
  const details = useChangeDetails(projectId, feed.changes, showNotification);
  const conflicts = useConflictResolution(projectId, feed.loadChanges);
  const comments = useCommentDialog(projectId, selectedChange, feed.loadChanges);

  React.useEffect(() => {
    if (!feed.isDraftActive && activeTab === 'drafts') {
      setActiveTab('live');
    }
  }, [feed.isDraftActive, activeTab]);

  const openChangeDetails = (changeId: string) => {
    const change = feed.changes.find(c => c.id === changeId);
    if (change) setSelectedChange(change);
    details.loadChangeDetails(changeId);
  };

  const selectTimelineChange = (id: string) => {
    const change = feed.changes.find(c => c.id === id);
    if (change) {
      setSelectedChange(change);
      comments.setShowCommentDialog(false);
    }
  };

  return (
    <div className="h-full flex flex-col bg-white">
      <AssistantHeader isLoading={feed.isLoading} lastRefresh={feed.lastRefresh} stats={feed.stats} onRefresh={feed.refreshAll} filters={filters} isDraftActive={feed.isDraftActive} />
      <TabBar activeTab={activeTab} onSelect={setActiveTab} liveCount={liveActivity.length} stats={feed.stats} isDraftActive={feed.isDraftActive} />
      <TabContent
        projectId={projectId} activeTab={activeTab} liveActivity={liveActivity}
        changes={feed.changes} draftChanges={feed.draftChanges} stats={feed.stats}
        filteredChanges={filters.filteredChanges} typeFilteredChanges={filters.typeFilteredChanges}
        searchQuery={filters.searchQuery} sourceFilter={filters.sourceFilter}
        onRollback={feed.refreshAfterRollback} onOpenDetails={openChangeDetails}
        onResolveClick={conflicts.handleConflictClick} onAutoResolve={conflicts.resolveConflict}
        onSelectTimeline={selectTimelineChange}
      />
      {conflicts.showConflictResolver && conflicts.selectedConflict && (
        <ConflictResolver
          conflict={conflicts.selectedConflict}
          onResolve={(resolution) => conflicts.resolveConflict(conflicts.selectedConflict.id, resolution)}
          onCancel={conflicts.cancelConflict}
        />
      )}
      {notification && <NotificationToast notification={notification} />}
      {details.showDetailsDialog && (
        <DetailsDialog
          loading={details.detailsLoading} details={details.changeDetails}
          newComment={comments.newComment} setNewComment={comments.setNewComment}
          onAddComment={details.addCommentToChange} onClose={details.closeDetails}
        />
      )}
      {comments.showCommentDialog && (
        <CommentDialog
          newComment={comments.newComment} setNewComment={comments.setNewComment}
          onAdd={comments.addComment} onCancel={comments.cancelComment}
        />
      )}
    </div>
  );
};

export default ChangeAssistant;

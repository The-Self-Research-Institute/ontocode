import { useState } from 'react';
import { fetchChangeDetails, postComment } from '../assistantApi';
import { AssistantNotification, OntologyChange } from '../types';

type Notify = (message: string, type?: AssistantNotification['type']) => void;

export function useChangeDetails(projectId: string, changes: OntologyChange[], showNotification: Notify) {
  const [showDetailsDialog, setShowDetailsDialog] = useState(false);
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [changeDetails, setChangeDetails] = useState<any>(null);

  const showLocalCopy = (changeId: string) => {
    const localChange = changes.find(c => c.id === changeId);
    if (localChange) {
      setChangeDetails({
        ...localChange,
        comments: localChange.comments || []
      });
    }
  };

  const loadChangeDetails = async (changeId: string) => {
    setDetailsLoading(true);
    setShowDetailsDialog(true);
    try {
      const data = await fetchChangeDetails(projectId, changeId);
      if (data.success && data.change) {
        setChangeDetails(data.change);
      } else {
        showLocalCopy(changeId);
      }
    } catch (error) {
      console.error('Failed to load change details:', error);
      showLocalCopy(changeId);
    } finally {
      setDetailsLoading(false);
    }
  };

  const addCommentToChange = async (changeId: string, text: string) => {
    if (!text.trim()) return;
    try {
      const currentUser = (window as any).vscodeUser || JSON.parse(localStorage.getItem('user') || '{}');
      const userId = currentUser?.id || currentUser?.email || 'anonymous';
      const username = currentUser?.username || 'Anonymous';
      const response = await postComment(projectId, changeId, { text, userId: userId, username: username });
      const data = await response.json();
      if (data.success) {
        showNotification('Comment added successfully', 'success');
        loadChangeDetails(changeId);
      } else {
        showNotification('Failed to add comment: ' + (data.error || 'Unknown error'), 'error');
      }
    } catch (error) {
      console.error('Failed to add comment:', error);
      showNotification('Failed to add comment', 'error');
    }
  };

  const closeDetails = () => {
    setShowDetailsDialog(false);
    setChangeDetails(null);
  };

  return { showDetailsDialog, detailsLoading, changeDetails, loadChangeDetails, addCommentToChange, closeDetails };
}

import { useState } from 'react';
import { postComment } from '../assistantApi';
import { AssistantNotification, OntologyChange } from '../types';

type Notify = (message: string, type?: AssistantNotification['type']) => void;

export function useCommentDialog(
  projectId: string,
  selectedChange: OntologyChange | null,
  loadChanges: () => void,
  showNotification: Notify,
) {
  const [showCommentDialog, setShowCommentDialog] = useState(false);
  const [newComment, setNewComment] = useState('');

  const addComment = async () => {
    if (!selectedChange || !newComment.trim()) return;
    try {
      const response = await postComment(projectId, selectedChange.id, { text: newComment });
      const data = await response.json();
      if (!response.ok || !data.success) {
        showNotification('Failed to add comment: ' + (data.error || 'Unknown error'), 'error');
        return;
      }
      setNewComment('');
      setShowCommentDialog(false);
      loadChanges();
    } catch (error) {
      console.error('Failed to add comment:', error);
      showNotification('Failed to add comment', 'error');
    }
  };

  const cancelComment = () => {
    setShowCommentDialog(false);
    setNewComment('');
  };

  return { showCommentDialog, setShowCommentDialog, newComment, setNewComment, addComment, cancelComment };
}

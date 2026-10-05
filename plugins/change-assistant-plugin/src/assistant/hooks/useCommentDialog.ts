import { useState } from 'react';
import { postComment } from '../assistantApi';
import { OntologyChange } from '../types';

export function useCommentDialog(projectId: string, selectedChange: OntologyChange | null, loadChanges: () => void) {
  const [showCommentDialog, setShowCommentDialog] = useState(false);
  const [newComment, setNewComment] = useState('');

  const addComment = async () => {
    if (!selectedChange || !newComment.trim()) return;
    try {
      await postComment(projectId, selectedChange.id, { text: newComment });
      setNewComment('');
      setShowCommentDialog(false);
      loadChanges();
    } catch (error) {
      console.error('Failed to add comment:', error);
    }
  };

  const cancelComment = () => {
    setShowCommentDialog(false);
    setNewComment('');
  };

  return { showCommentDialog, setShowCommentDialog, newComment, setNewComment, addComment, cancelComment };
}

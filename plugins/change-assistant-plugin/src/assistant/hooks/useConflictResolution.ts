import { useState } from 'react';
import { postConflictResolution } from '../assistantApi';
import { AssistantNotification, OntologyChange } from '../types';

type Notify = (message: string, type?: AssistantNotification['type']) => void;

export function useConflictResolution(projectId: string, loadChanges: () => void, showNotification: Notify) {
  const [selectedConflict, setSelectedConflict] = useState<any>(null);
  const [showConflictResolver, setShowConflictResolver] = useState(false);
  const [isResolving, setIsResolving] = useState(false);

  const resolveConflict = async (changeId: string, resolution: string, mergedValue?: string) => {
    if (isResolving) return;
    setIsResolving(true);
    try {
      const response = await postConflictResolution(projectId, changeId, resolution, mergedValue);
      const data = await response.json();
      if (!response.ok || !data.success) {
        showNotification('Failed to resolve conflict: ' + (data.error || 'Unknown error'), 'error');
        return;
      }
      setShowConflictResolver(false);
      setSelectedConflict(null);
      loadChanges();
    } catch (error) {
      console.error('Failed to resolve conflict:', error);
      showNotification('Failed to resolve conflict', 'error');
    } finally {
      setIsResolving(false);
    }
  };

  const handleConflictClick = (change: OntologyChange, conflictIndex: number = 0) => {
    const conflict = change.conflicts?.[conflictIndex];
    if (conflict) {
      setSelectedConflict({
        id: change.id,
        type: conflict.conflictType,
        description: conflict.description,
        localChange: change.newValue || '',
        remoteChange: conflict.theirValue || change.oldValue || '',
        baseValue: change.oldValue,
        remoteAuthor: conflict.theirUsername,
        remoteTimestamp: conflict.theirTimestamp
      });
      setShowConflictResolver(true);
    }
  };

  const cancelConflict = () => {
    setShowConflictResolver(false);
    setSelectedConflict(null);
  };

  return { selectedConflict, showConflictResolver, isResolving, resolveConflict, handleConflictClick, cancelConflict };
}

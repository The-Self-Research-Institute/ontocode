import { useState } from 'react';
import { postConflictResolution } from '../assistantApi';
import { OntologyChange } from '../types';

export function useConflictResolution(projectId: string, loadChanges: () => void) {
  const [selectedConflict, setSelectedConflict] = useState<any>(null);
  const [showConflictResolver, setShowConflictResolver] = useState(false);

  const resolveConflict = async (changeId: string, resolution: string) => {
    try {
      await postConflictResolution(projectId, changeId, resolution);
      setShowConflictResolver(false);
      setSelectedConflict(null);
      loadChanges();
    } catch (error) {
      console.error('Failed to resolve conflict:', error);
    }
  };

  const handleConflictClick = (change: OntologyChange) => {
    if (change.conflicts && change.conflicts.length > 0) {
      setSelectedConflict({
        id: change.id,
        type: change.conflicts[0].conflictType,
        description: change.conflicts[0].description,
        localChange: change.newValue || '',
        remoteChange: change.oldValue || '',
        baseValue: change.oldValue
      });
      setShowConflictResolver(true);
    }
  };

  const cancelConflict = () => {
    setShowConflictResolver(false);
    setSelectedConflict(null);
  };

  return { selectedConflict, showConflictResolver, resolveConflict, handleConflictClick, cancelConflict };
}

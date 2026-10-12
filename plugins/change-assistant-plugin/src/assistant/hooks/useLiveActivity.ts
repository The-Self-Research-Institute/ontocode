import { useEffect, useState } from 'react';
import { describeLive } from '../display';
import { LiveActivity } from '../types';

export function useLiveActivity(projectId: string, loadChanges: () => void) {
  const [liveActivity, setLiveActivity] = useState<LiveActivity[]>([]);

  useEffect(() => {
    const handleEdit = (event: CustomEvent, own: boolean) => {
      const detail = event.detail;
      if (detail && detail.projectId === projectId) {
        const activity: LiveActivity = {
          id: `live-${Date.now()}`,
          userId: detail.userId || 'unknown',
          username: own ? 'You' : detail.username || 'Someone',
          ...describeLive(detail),
          timestamp: new Date(),
          isCurrentUser: own
        };
        setLiveActivity(prev => [activity, ...prev.slice(0, 19)]);
        setTimeout(() => loadChanges(), 500);
      }
    };

    const handleRemoteEdit = (event: Event) => handleEdit(event as CustomEvent, false);
    const handleOwnEdit = (event: Event) => handleEdit(event as CustomEvent, true);
    window.addEventListener('remoteEditReceived', handleRemoteEdit);
    window.addEventListener('ownEditReceived', handleOwnEdit);
    return () => {
      window.removeEventListener('remoteEditReceived', handleRemoteEdit);
      window.removeEventListener('ownEditReceived', handleOwnEdit);
    };
  }, [projectId]);

  return liveActivity;
}

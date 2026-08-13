import React, { useEffect, useState } from 'react';
import { Clock, Users, Loader2 } from 'lucide-react';
import apiClient from '../services/apiClient';
import { formatQueueWait } from '../utils/importStatusText';

interface QueueStatus {
  projectId: string;
  queuePosition: number;
  totalInQueue: number;
  estimatedWaitTimeMs: number;
  status: 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  message: string;
  timestamp: number;
}

interface QueueStatusIndicatorProps {
  projectId: string;
  visible: boolean;
}

export const QueueStatusIndicator: React.FC<QueueStatusIndicatorProps> = ({
  projectId,
  visible
}) => {
  const [queueStatus, setQueueStatus] = useState<QueueStatus | null>(null);

  useEffect(() => {
    if (!projectId || !visible) {
      return;
    }

    const handleMessage = (event: MessageEvent) => {
      const message = event.data;
      if (message.type === 'queueStatusUpdate' && message.status?.projectId === projectId) {
        setQueueStatus(message.status);
      }
    };

    const handleQueueCustomEvent = (e: Event) => {
      const status = (e as CustomEvent).detail;
      if (status?.projectId === projectId) {
        setQueueStatus(status);
      }
    };

    window.addEventListener('message', handleMessage);
    window.addEventListener('queueStatusUpdate', handleQueueCustomEvent);

    const requestQueueStatus = () => {
      if (window.vscode) {
        window.vscode.postMessage({
          type: 'getQueueStatus',
          projectId
        });
      }
    };

    const pollQueueStatus = async () => {
      try {
        const positionData: any = await apiClient.get(`/api/import-queue/position/${projectId}`);
        if (!positionData?.inQueue) {
          return;
        }
        setQueueStatus({
          projectId,
          status: positionData.status === 'PROCESSING' ? 'PROCESSING' : 'QUEUED',
          queuePosition: positionData.position ?? 0,
          totalInQueue: positionData.totalInQueue ?? 0,
          estimatedWaitTimeMs: positionData.estimatedWaitMs ?? 0,
          message: positionData.message ?? '',
          timestamp: Date.now(),
        });
      } catch {
        // Queue endpoint may be unavailable during startup
      }
    };

    requestQueueStatus();
    pollQueueStatus();
    const intervalId = setInterval(() => {
      requestQueueStatus();
      pollQueueStatus();
    }, 3000);

    return () => {
      window.removeEventListener('message', handleMessage);
      window.removeEventListener('queueStatusUpdate', handleQueueCustomEvent);
      clearInterval(intervalId);
    };
  }, [projectId, visible]);

  if (!visible || !queueStatus || queueStatus.status === 'COMPLETED') {
    return null;
  }

  const formatWaitTime = (ms: number): string => formatQueueWait(ms) ?? 'Less than 1 minute';

  const getStatusColor = () => {
    switch (queueStatus.status) {
      case 'PROCESSING':
        return 'bg-blue-50 border-blue-200';
      case 'QUEUED':
        return 'bg-purple-50 border-purple-200';
      case 'FAILED':
        return 'bg-red-50 border-red-200';
      default:
        return 'bg-gray-50 border-gray-200';
    }
  };

  const getStatusIcon = () => {
    switch (queueStatus.status) {
      case 'PROCESSING':
        return <Loader2 size={18} className="animate-spin text-blue-600" />;
      case 'QUEUED':
        return <Clock size={18} className="text-purple-600" />;
      default:
        return <Users size={18} className="text-gray-600" />;
    }
  };

  return (
    <div className={`fixed bottom-4 right-4 max-w-md rounded-lg border-2 shadow-lg p-4 ${getStatusColor()} z-50 animate-slide-up`}>
      <div className="flex items-start gap-3">
        <div className="flex-shrink-0 mt-0.5">
          {getStatusIcon()}
        </div>

        <div className="flex-1 min-w-0">
          {queueStatus.status === 'PROCESSING' ? (
            <>
              <div className="font-semibold text-blue-900 text-sm mb-1">
                Processing Now
              </div>
              <div className="text-blue-700 text-xs">
                Your file is being imported...
              </div>
            </>
          ) : (
            <>
              <div className="font-semibold text-purple-900 text-sm mb-1">
                Position #{queueStatus.queuePosition} in Queue
              </div>
              <div className="text-purple-700 text-xs space-y-1">
                <div className="flex items-center gap-2">
                  <Users size={12} />
                  <span>{queueStatus.queuePosition - 1} file{queueStatus.queuePosition - 1 !== 1 ? 's' : ''} ahead of you</span>
                </div>
                <div className="flex items-center gap-2">
                  <Clock size={12} />
                  <span>Estimated wait: {formatWaitTime(queueStatus.estimatedWaitTimeMs)}</span>
                </div>
              </div>
            </>
          )}

          {queueStatus.totalInQueue > 0 && queueStatus.status === 'QUEUED' && (
            <div className="mt-2 text-xs text-purple-600">
              Total in queue: {queueStatus.totalInQueue}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

interface GlobalQueueStatsProps {
  visible?: boolean;
}

export const GlobalQueueStats: React.FC<GlobalQueueStatsProps> = ({ visible = true }) => {
  const [stats, setStats] = useState<{
    activeImports: number;
    queuedImports: number;
    averageProcessingTimeMs: number;
  } | null>(null);

  useEffect(() => {
    if (!visible) {
      return;
    }

    const handleMessage = (event: MessageEvent) => {
      const message = event.data;
      if (message.type === 'queueStats') {
        setStats(message.stats);
      }
    };

    const handleStatsCustomEvent = (e: Event) => {
      setStats((e as CustomEvent).detail);
    };

    window.addEventListener('message', handleMessage);
    window.addEventListener('queueStatsUpdate', handleStatsCustomEvent);

    let intervalId: ReturnType<typeof setInterval> | undefined;
    let backoffMs = 0;
    let cancelled = false;

    const scheduleNext = (delayMs: number) => {
      if (cancelled) return;
      if (intervalId) clearTimeout(intervalId);
      intervalId = setTimeout(() => { void pollStats(); }, delayMs);
    };

    const pollStats = async () => {
      if (cancelled) return;
      try {
        const data: any = await apiClient.get('/api/import-queue/stats');
        backoffMs = 0;
        if (data && typeof data.activeImports === 'number') {
          const next = {
            activeImports: data.activeImports,
            queuedImports: data.queuedImports ?? 0,
            averageProcessingTimeMs: data.averageProcessingTimeMs ?? 0,
          };
          setStats(next);
          window.dispatchEvent(new CustomEvent('queueStatsUpdate', { detail: next }));
          const busy = next.activeImports > 0 || next.queuedImports > 0;
          scheduleNext(busy ? 3000 : 30000);
          return;
        }
        scheduleNext(30000);
      } catch {

        backoffMs = Math.min(backoffMs > 0 ? backoffMs * 2 : 10000, 60000);
        scheduleNext(backoffMs);
      }
    };

    pollStats();

    return () => {
      cancelled = true;
      window.removeEventListener('message', handleMessage);
      window.removeEventListener('queueStatsUpdate', handleStatsCustomEvent);
      if (intervalId) clearTimeout(intervalId);
    };
  }, [visible]);

  if (!visible || !stats || (stats.activeImports === 0 && stats.queuedImports === 0)) {
    return null;
  }

  return (
    <div className="fixed top-4 right-4 bg-white border border-gray-200 rounded-lg shadow-sm px-3 py-2 text-xs z-40">
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-1.5">
          <Loader2 size={12} className={stats.activeImports > 0 ? "animate-spin text-blue-600" : "text-gray-400"} />
          <span className="text-gray-600">{stats.activeImports} processing</span>
        </div>
        {stats.queuedImports > 0 && (
          <>
            <div className="w-px h-3 bg-gray-300" />
            <div className="flex items-center gap-1.5">
              <Clock size={12} className="text-purple-600" />
              <span className="text-gray-600">{stats.queuedImports} queued</span>
            </div>
          </>
        )}
      </div>
    </div>
  );
};

export default QueueStatusIndicator;

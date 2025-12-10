import React, { useEffect, useState } from 'react';
import { Clock, Users, Loader2 } from 'lucide-react';

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

    // Subscribe to queue status updates via WebSocket
    const handleMessage = (event: MessageEvent) => {
      const message = event.data;
      if (message.type === 'queueStatusUpdate' && message.status?.projectId === projectId) {
        setQueueStatus(message.status);
      }
    };

    window.addEventListener('message', handleMessage);

    // Request current queue status
    if (window.vscode) {
      window.vscode.postMessage({
        type: 'getQueueStatus',
        projectId
      });
    }

    return () => {
      window.removeEventListener('message', handleMessage);
    };
  }, [projectId, visible]);

  if (!visible || !queueStatus || queueStatus.status === 'COMPLETED') {
    return null;
  }

  const formatWaitTime = (ms: number): string => {
    const minutes = Math.ceil(ms / 60000);
    if (minutes < 1) return 'Less than 1 minute';
    if (minutes === 1) return '1 minute';
    return `${minutes} minutes`;
  };

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

    window.addEventListener('message', handleMessage);

    return () => {
      window.removeEventListener('message', handleMessage);
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

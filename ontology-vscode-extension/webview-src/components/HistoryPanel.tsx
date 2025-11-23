import React, { useState, useEffect, useCallback } from 'react';
import { Clock, User, FileEdit, Trash2, Plus, Edit, Tag, RefreshCw } from 'lucide-react';
import apiClient from '../services/apiClient';

interface EditOperation {
  type: string;
  projectId: string;
  nodeId: string;
  property?: string;
  value?: any;
  previousValue?: any;
  userId: string;
  username: string;
  sessionId: string;
  timestamp: number;
  serverTimestamp: number;
  metadata?: Record<string, any>;
}

interface HistoryPanelProps {
  projectId: string | null;
  isOpen: boolean;
  onClose: () => void;
}

const HistoryPanel: React.FC<HistoryPanelProps> = ({ projectId, isOpen, onClose }) => {
  const [history, setHistory] = useState<EditOperation[]>([]);
  const [loading, setLoading] = useState(false);
  const [limit, setLimit] = useState(50);

  const fetchHistory = useCallback(async () => {
    if (!projectId) return;
    
    setLoading(true);
    try {
      const response = await apiClient.get<EditOperation[]>(`/api/collaboration/history/${projectId}?limit=${limit}`);
      setHistory(response || []);
    } catch (error) {
      console.error('[History] Failed to fetch history:', error);
    } finally {
      setLoading(false);
    }
  }, [projectId, limit]);

  useEffect(() => {
    if (isOpen && projectId) {
      fetchHistory();
    }
  }, [isOpen, projectId, fetchHistory]);

  const getOperationIcon = (type: string) => {
    switch (type) {
      case 'CREATE':
      case 'ADD':
        return <Plus size={16} className="text-green-600" />;
      case 'DELETE':
      case 'REMOVE':
        return <Trash2 size={16} className="text-red-600" />;
      case 'UPDATE':
      case 'MODIFY':
        return <Edit size={16} className="text-blue-600" />;
      case 'ANNOTATION':
        return <Tag size={16} className="text-purple-600" />;
      default:
        return <FileEdit size={16} className="text-gray-600" />;
    }
  };

  const formatTimestamp = (timestamp: number) => {
    const date = new Date(timestamp);
    const now = new Date();
    const diff = now.getTime() - date.getTime();
    
    // Less than 1 minute
    if (diff < 60000) {
      return 'Just now';
    }
    // Less than 1 hour
    if (diff < 3600000) {
      const minutes = Math.floor(diff / 60000);
      return `${minutes} minute${minutes > 1 ? 's' : ''} ago`;
    }
    // Less than 24 hours
    if (diff < 86400000) {
      const hours = Math.floor(diff / 3600000);
      return `${hours} hour${hours > 1 ? 's' : ''} ago`;
    }
    // More than 24 hours
    const days = Math.floor(diff / 86400000);
    if (days < 7) {
      return `${days} day${days > 1 ? 's' : ''} ago`;
    }
    
    // Format as date
    return date.toLocaleDateString('en-US', { 
      month: 'short', 
      day: 'numeric', 
      hour: '2-digit', 
      minute: '2-digit' 
    });
  };

  const getOperationDescription = (op: EditOperation) => {
    const entityName = op.nodeId?.split('#').pop() || op.nodeId?.split('/').pop() || 'Entity';
    
    switch (op.type) {
      case 'CREATE':
        return `Created ${entityName}`;
      case 'DELETE':
        return `Deleted ${entityName}`;
      case 'UPDATE':
        if (op.property === 'label') {
          return `Changed label from "${op.previousValue}" to "${op.value}"`;
        }
        return `Updated ${op.property} of ${entityName}`;
      case 'ADD':
        if (op.property) {
          return `Added ${op.property} to ${entityName}`;
        }
        return `Added ${entityName}`;
      case 'REMOVE':
        if (op.property) {
          return `Removed ${op.property} from ${entityName}`;
        }
        return `Removed ${entityName}`;
      case 'ANNOTATION':
        return `Modified annotation on ${entityName}`;
      default:
        return `Modified ${entityName}`;
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-2xl w-full max-w-4xl h-[80vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b border-gray-200 bg-gradient-to-r from-purple-50 to-blue-50">
          <div className="flex items-center gap-2">
            <Clock size={24} className="text-purple-600" />
            <h2 className="text-xl font-bold text-gray-800">Edit History</h2>
            <span className="text-sm text-gray-500">({history.length} operations)</span>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={fetchHistory}
              disabled={loading}
              className="px-3 py-1 text-sm bg-purple-100 text-purple-700 rounded hover:bg-purple-200 disabled:opacity-50 flex items-center gap-1"
            >
              <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
              Refresh
            </button>
            <select
              value={limit}
              onChange={(e) => setLimit(Number(e.target.value))}
              className="px-2 py-1 text-sm border border-gray-300 rounded"
            >
              <option value={25}>Last 25</option>
              <option value={50}>Last 50</option>
              <option value={100}>Last 100</option>
              <option value={200}>Last 200</option>
            </select>
            <button
              onClick={onClose}
              className="px-4 py-2 text-gray-600 hover:text-gray-800 font-medium"
            >
              ✕
            </button>
          </div>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-4">
          {loading ? (
            <div className="flex items-center justify-center h-full">
              <div className="text-center">
                <RefreshCw size={48} className="animate-spin text-purple-600 mx-auto mb-4" />
                <p className="text-gray-600">Loading history...</p>
              </div>
            </div>
          ) : history.length === 0 ? (
            <div className="flex items-center justify-center h-full">
              <div className="text-center text-gray-500">
                <Clock size={64} className="mx-auto mb-4 text-gray-300" />
                <p className="text-lg font-medium">No history available</p>
                <p className="text-sm">Start making changes to see the history</p>
              </div>
            </div>
          ) : (
            <div className="space-y-3">
              {history.map((op, index) => (
                <div
                  key={`${op.timestamp}-${index}`}
                  className="bg-white border border-gray-200 rounded-lg p-4 hover:shadow-md transition-shadow"
                >
                  <div className="flex items-start gap-3">
                    <div className="mt-1">{getOperationIcon(op.type)}</div>
                    
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-1">
                        <span className="font-medium text-gray-900">
                          {getOperationDescription(op)}
                        </span>
                        <span className="px-2 py-0.5 text-xs font-medium rounded bg-gray-100 text-gray-700">
                          {op.type}
                        </span>
                      </div>
                      
                      <div className="flex items-center gap-4 text-sm text-gray-600">
                        <div className="flex items-center gap-1">
                          <User size={14} />
                          <span>{op.username || op.userId}</span>
                        </div>
                        <div className="flex items-center gap-1">
                          <Clock size={14} />
                          <span>{formatTimestamp(op.serverTimestamp || op.timestamp)}</span>
                        </div>
                      </div>

                      {/* Show entity IRI if available */}
                      {op.nodeId && (
                        <div className="mt-2 text-xs text-gray-500 font-mono bg-gray-50 px-2 py-1 rounded truncate">
                          {op.nodeId}
                        </div>
                      )}

                      {/* Show value changes if available */}
                      {(op.value !== undefined || op.previousValue !== undefined) && (
                        <div className="mt-2 text-xs">
                          {op.previousValue !== undefined && (
                            <div className="text-red-600">
                              <span className="font-medium">Old:</span> {String(op.previousValue)}
                            </div>
                          )}
                          {op.value !== undefined && (
                            <div className="text-green-600">
                              <span className="font-medium">New:</span> {String(op.value)}
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default HistoryPanel;

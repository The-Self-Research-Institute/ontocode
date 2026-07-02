import React, { useState, useEffect, useRef, useImperativeHandle, forwardRef, useCallback } from 'react';
import {
  Users, Wifi, WifiOff, Circle, ChevronRight, ChevronDown, Activity, GripVertical,
  CheckCircle, XCircle, Undo2, MessageSquare, ExternalLink, Loader2, X, AlertCircle,
} from 'lucide-react';
import { useCollaboration } from '../contexts/CollaborationContext';
import { changeTrackingService, OntologyChange } from '../services/changeTrackingService';
import { dispatchCollaborationNavigate } from '../utils/collaborationNavigation';

interface CollaborationPanelProps {
  projectId?: string;
}

export interface CollaborationPanelRef {
  refreshChanges: () => void;
}

function statusBadge(status?: string) {
  const s = (status || 'PENDING').toUpperCase();
  if (s === 'APPROVED') {
    return (
      <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] font-medium bg-green-100 text-green-700">
        <CheckCircle size={10} /> Approved
      </span>
    );
  }
  if (s === 'REJECTED') {
    return (
      <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] font-medium bg-red-100 text-red-700">
        <XCircle size={10} /> Rejected
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] font-medium bg-amber-100 text-amber-700">
      <AlertCircle size={10} /> Pending
    </span>
  );
}

function changeActionIcon(changeType: string) {
  const t = changeType.toLowerCase();
  if (t.includes('add') || t.includes('create')) return '+ ';
  if (t.includes('delete') || t.includes('remove')) return '− ';
  if (t.includes('update') || t.includes('edit') || t.includes('rename') || t.includes('modify')) return '✎ ';
  return '• ';
}

function changeActionColor(changeType: string) {
  const t = changeType.toLowerCase();
  if (t.includes('add') || t.includes('create')) return 'text-green-600';
  if (t.includes('delete') || t.includes('remove')) return 'text-red-600';
  if (t.includes('update') || t.includes('edit') || t.includes('rename') || t.includes('modify')) return 'text-blue-600';
  return 'text-amber-600';
}

const CollaborationPanel = forwardRef<CollaborationPanelRef, CollaborationPanelProps>(({ projectId }, ref) => {
  const { state } = useCollaboration();
  const [isExpanded, setIsExpanded] = useState(false);
  const [isDragging, setIsDragging] = useState(false);
  const [dragStart, setDragStart] = useState({ x: 0, y: 0 });
  const panelRef = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<{ x: number; y: number } | null>(null);
  const [collapsedPosition, setCollapsedPosition] = useState<{ x: number; y: number } | null>(null);

  const [recentChanges, setRecentChanges] = useState<OntologyChange[]>([]);
  const [showAllChanges, setShowAllChanges] = useState(false);
  const [selectedChange, setSelectedChange] = useState<OntologyChange | null>(null);
  const [modificationNote, setModificationNote] = useState('');
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [actionMessage, setActionMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  useEffect(() => {
    const savedPosition = localStorage.getItem('collaborationPanelPosition');
    if (savedPosition) {
      try {
        const pos = JSON.parse(savedPosition);
        setPosition({ x: pos.x, y: window.innerHeight - 70 });
        setCollapsedPosition({ x: pos.x, y: window.innerHeight - 70 });
      } catch {
        const defaultX = window.innerWidth - 420;
        setPosition({ x: defaultX, y: window.innerHeight - 70 });
        setCollapsedPosition({ x: defaultX, y: window.innerHeight - 70 });
      }
    } else {
      const defaultX = window.innerWidth - 420;
      setPosition({ x: defaultX, y: window.innerHeight - 70 });
      setCollapsedPosition({ x: defaultX, y: window.innerHeight - 70 });
    }
  }, []);

  useEffect(() => {
    if (position && panelRef.current) {
      const expandedHeight = selectedChange ? 520 : 400;
      const collapsedHeight = 52;
      const panelWidth = isExpanded ? (selectedChange ? 420 : 360) : 220;

      if (isExpanded) {
        const bottomY = window.innerHeight - 70;
        const newY = bottomY - expandedHeight + collapsedHeight;
        const newX = Math.min(position.x, window.innerWidth - panelWidth - 20);
        setPosition({ x: Math.max(20, newX), y: Math.max(20, newY) });
      } else {
        const newX = Math.min(position.x, window.innerWidth - panelWidth - 20);
        setPosition({ x: Math.max(20, newX), y: window.innerHeight - 70 });
      }
    }
  }, [isExpanded, selectedChange]);

  useEffect(() => {
    if (position && panelRef.current) {
      const handleResize = () => {
        const panelWidth = panelRef.current?.offsetWidth || 220;
        const panelHeight = panelRef.current?.offsetHeight || 60;
        const maxX = window.innerWidth - panelWidth - 20;
        const maxY = window.innerHeight - panelHeight - 20;
        setPosition((prev) =>
          prev
            ? { x: Math.max(20, Math.min(prev.x, maxX)), y: Math.max(20, Math.min(prev.y, maxY)) }
            : null,
        );
      };
      window.addEventListener('resize', handleResize);
      return () => window.removeEventListener('resize', handleResize);
    }
  }, [position]);

  useEffect(() => {
    if (position && (position.x !== 0 || position.y !== 0)) {
      localStorage.setItem('collaborationPanelPosition', JSON.stringify(position));
    }
    if (collapsedPosition && (collapsedPosition.x !== 0 || collapsedPosition.y !== 0)) {
      localStorage.setItem('collaborationPanelCollapsedPosition', JSON.stringify(collapsedPosition));
    }
  }, [position, collapsedPosition]);

  const handleMouseDown = (e: React.MouseEvent) => {
    if (!position) return;
    const target = e.target as HTMLElement;
    if (!target.closest('button') && !target.closest('input') && !target.closest('textarea')) {
      setIsDragging(true);
      setDragStart({ x: e.clientX - position.x, y: e.clientY - position.y });
      e.preventDefault();
    }
  };

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (isDragging) {
        const newX = e.clientX - dragStart.x;
        const newY = e.clientY - dragStart.y;
        const maxX = window.innerWidth - (panelRef.current?.offsetWidth || 360);
        const maxY = window.innerHeight - (panelRef.current?.offsetHeight || 600);
        setPosition({ x: Math.max(0, Math.min(newX, maxX)), y: Math.max(0, Math.min(newY, maxY)) });
      }
    };
    const handleMouseUp = () => setIsDragging(false);
    if (isDragging) {
      document.addEventListener('mousemove', handleMouseMove);
      document.addEventListener('mouseup', handleMouseUp);
    }
    return () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };
  }, [isDragging, dragStart, position]);

  const fetchRecentChanges = useCallback(async () => {
    if (projectId && isExpanded) {
      const changes = await changeTrackingService.getRecentChanges(projectId, 50);
      setRecentChanges(changes);
      if (selectedChange) {
        const updated = changes.find((c) => c.id === selectedChange.id);
        if (updated) setSelectedChange(updated);
      }
    }
  }, [projectId, isExpanded, selectedChange]);

  useImperativeHandle(ref, () => ({
    refreshChanges: () => {
      fetchRecentChanges();
    },
  }));

  useEffect(() => {
    fetchRecentChanges();
    if (isExpanded) {
      const interval = setInterval(fetchRecentChanges, 30000);
      return () => clearInterval(interval);
    }
  }, [fetchRecentChanges, isExpanded]);

  const formatTime = (timestamp: string | number) => {
    const date = new Date(timestamp);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  };

  const getTimeAgo = (timestamp: number) => {
    const seconds = Math.floor((Date.now() - timestamp) / 1000);
    if (seconds < 60) return 'Just now';
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    return `${hours}h ago`;
  };

  const navigateToEntity = (change: OntologyChange) => {
    if (!change.entityIRI) {
      setActionMessage({ type: 'error', text: 'No entity IRI for this change' });
      return;
    }
    dispatchCollaborationNavigate({
      projectId,
      entityIRI: change.entityIRI,
      entityLabel: change.entityLabel,
      entityType: change.entityType || change.changeCategory,
      changeType: change.changeType || change.operationType,
    });
  };

  const runAction = async (action: string, fn: () => Promise<{ success: boolean; error?: string; message?: string }>) => {
    if (!projectId) return;
    setActionLoading(action);
    setActionMessage(null);
    try {
      const result = await fn();
      if (result.success) {
        setActionMessage({ type: 'success', text: result.message || `${action} succeeded` });
        await fetchRecentChanges();
      } else {
        setActionMessage({ type: 'error', text: result.error || `${action} failed` });
      }
    } finally {
      setActionLoading(null);
    }
  };

  const handleApprove = (change: OntologyChange) =>
    runAction('approve', () => changeTrackingService.approveChange(projectId!, change.id));

  const handleReject = (change: OntologyChange) =>
    runAction('reject', () => changeTrackingService.rejectChange(projectId!, change.id));

  const handleRevert = (change: OntologyChange) => {
    if (!confirm(`Revert this change on "${change.entityLabel || change.entityIRI}"?`)) return;
    runAction('revert', () => changeTrackingService.revertChange(projectId!, change.id));
  };

  const handleRollback = (change: OntologyChange) => {
    if (!change.entityIRI) {
      setActionMessage({ type: 'error', text: 'Cannot rollback without entity IRI' });
      return;
    }
    if (!confirm(`Rollback this change in the ontology? This applies an inverse edit.`)) return;
    const action = (() => {
      const t = (change.changeType || change.operationType || '').toLowerCase();
      if (t.includes('add') || t.includes('create')) return 'added';
      if (t.includes('delete') || t.includes('remove')) return 'deleted';
      return 'modified';
    })();
    runAction('rollback', async () => {
      const result = await changeTrackingService.rollbackChange(projectId!, {
        changeId: change.id,
        changeType: change.operationType || change.changeType,
        action,
        entityIRI: change.entityIRI!,
        entityLabel: change.entityLabel,
        oldValue: change.oldValue,
        newValue: change.newValue,
      });
      if (result.success) {
        window.dispatchEvent(
          new CustomEvent('ontologyRollback', {
            detail: {
              projectId,
              changeId: change.id,
              entityIRI: change.entityIRI,
              entityLabel: change.entityLabel,
              success: true,
            },
          }),
        );
      }
      return result;
    });
  };

  const handleRequestModification = async (change: OntologyChange) => {
    if (!projectId || !modificationNote.trim()) return;
    setActionLoading('modification');
    setActionMessage(null);
    try {
      const text = `[Modification requested] ${modificationNote.trim()}`;
      const result = await changeTrackingService.addComment(projectId, change.id, text);
      if (result.success) {
        setModificationNote('');
        setActionMessage({ type: 'success', text: 'Modification request sent' });
        await fetchRecentChanges();
      } else {
        setActionMessage({ type: 'error', text: result.error || 'Failed to send request' });
      }
    } finally {
      setActionLoading(null);
    }
  };

  const allUsers = Array.from(state.activeUsers.values());
  const activeUsers = allUsers.filter(
    (user) => !state.currentProjectId || user.projectId === state.currentProjectId,
  );

  if (!position) return null;

  const panelWidth = isExpanded ? (selectedChange ? 420 : 360) : 220;
  const panelMaxHeight = isExpanded ? (selectedChange ? 520 : 400) : 'auto';

  return (
    <>
      <style>{`
        .minimal-scrollbar::-webkit-scrollbar { width: 4px; }
        .minimal-scrollbar::-webkit-scrollbar-track { background: transparent; }
        .minimal-scrollbar::-webkit-scrollbar-thumb { background: rgba(203, 213, 225, 0.3); border-radius: 2px; }
        .minimal-scrollbar::-webkit-scrollbar-thumb:hover { background: rgba(203, 213, 225, 0.5); }
      `}</style>
      <div
        ref={panelRef}
        className="fixed bg-white rounded-lg shadow-2xl border border-gray-300 overflow-hidden z-50"
        style={{
          width: panelWidth,
          maxHeight: panelMaxHeight,
          left: `${position.x}px`,
          top: `${position.y}px`,
          cursor: isDragging ? 'grabbing' : 'default',
        }}
      >
        <div
          className="bg-gradient-to-r from-purple-600 to-purple-700 text-white shadow-md relative cursor-pointer"
          onClick={(e) => {
            const target = e.target as HTMLElement;
            if (!target.closest('button') && !target.closest('[data-drag-handle]')) {
              setIsExpanded(!isExpanded);
            }
          }}
        >
          <div
            data-drag-handle
            className="absolute left-1/2 transform -translate-x-1/2 top-0 cursor-move hover:bg-purple-800 rounded-b px-2 pb-1"
            onMouseDown={handleMouseDown}
            title="Drag to move panel"
            style={{ paddingTop: '2px' }}
          >
            <GripVertical size={14} className="text-purple-300" />
          </div>
          <div className="px-4 py-3 flex items-center justify-between">
            <div className="flex items-center gap-2 flex-1">
              <Users size={18} />
              <span className="font-semibold text-sm">Collaboration</span>
              <div
                className={`flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${
                  state.connected ? 'bg-green-500 bg-opacity-90' : 'bg-gray-500 bg-opacity-90'
                }`}
              >
                {state.connected ? (
                  <>
                    <Wifi size={10} />
                    <span>Live</span>
                  </>
                ) : (
                  <>
                    <WifiOff size={10} />
                    <span>Offline</span>
                  </>
                )}
              </div>
            </div>
            <button
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                setIsExpanded(!isExpanded);
              }}
              className="hover:bg-purple-800 rounded-md p-1.5 transition-all"
            >
              {isExpanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
            </button>
          </div>
        </div>

        {isExpanded && (
          <div className="overflow-y-auto minimal-scrollbar" style={{ maxHeight: selectedChange ? 460 : 340 }}>
            {/* Active Users */}
            <div className="border-b border-gray-200">
              <div className="bg-gray-50 px-4 py-2 flex items-center gap-2 border-b border-gray-200">
                <Users size={14} className="text-purple-600" />
                <span className="text-xs font-semibold text-gray-700">Active Users ({activeUsers.length})</span>
              </div>
              {activeUsers.length === 0 ? (
                <div className="px-4 py-4 text-center text-xs text-gray-400">No active users</div>
              ) : (
                <div className="p-2 space-y-1 max-h-24 overflow-y-auto minimal-scrollbar">
                  {activeUsers.map((user) => (
                    <div key={user.userId} className="flex items-center gap-2 px-2 py-1.5 rounded hover:bg-gray-50">
                      <div
                        className="w-7 h-7 rounded-full flex items-center justify-center text-white text-xs font-semibold"
                        style={{ backgroundColor: user.color }}
                      >
                        {user.username.charAt(0).toUpperCase()}
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="text-xs font-medium text-gray-800 truncate">{user.username}</div>
                        <div className="flex items-center gap-1 text-[10px] text-gray-500">
                          <Circle size={5} className="text-green-500 fill-current" />
                          {getTimeAgo(user.lastActivity)}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Activity log */}
            <div>
              <div className="bg-gray-50 px-4 py-2 flex items-center justify-between border-b border-gray-200">
                <div className="flex items-center gap-2">
                  <Activity size={14} className="text-blue-600" />
                  <span className="text-xs font-semibold text-gray-700">Activity & Review</span>
                </div>
                <button
                  onClick={() => fetchRecentChanges()}
                  className="text-[10px] text-purple-600 hover:text-purple-800 font-medium"
                >
                  Refresh
                </button>
              </div>

              {actionMessage && (
                <div
                  className={`mx-2 mt-2 px-2 py-1.5 rounded text-xs ${
                    actionMessage.type === 'success' ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'
                  }`}
                >
                  {actionMessage.text}
                </div>
              )}

              {recentChanges.length === 0 ? (
                <div className="px-4 py-6 text-center">
                  <Activity size={28} className="mx-auto text-gray-300 mb-2" />
                  <p className="text-xs text-gray-400">No recent activity</p>
                  <p className="text-[10px] text-gray-500 mt-1">Changes appear after save</p>
                </div>
              ) : (
                <>
                  <div className="p-2 space-y-1 max-h-36 overflow-y-auto minimal-scrollbar">
                    {(showAllChanges ? recentChanges : recentChanges.slice(0, 5)).map((change) => (
                      <button
                        key={change.id}
                        type="button"
                        onClick={() => {
                          setSelectedChange(change);
                          setActionMessage(null);
                          setModificationNote('');
                        }}
                        className={`w-full text-left px-2 py-2 rounded-md transition-colors border ${
                          selectedChange?.id === change.id
                            ? 'bg-purple-50 border-purple-200'
                            : 'hover:bg-gray-50 border-transparent'
                        }`}
                      >
                        <div className="flex items-start gap-2">
                          <span className={`text-xs font-semibold mt-0.5 ${changeActionColor(change.changeType)}`}>
                            {changeActionIcon(change.changeType)}
                          </span>
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-1 flex-wrap">
                              {change.entityLabel && (
                                <span className="text-xs font-medium text-purple-700 truncate max-w-[140px]">
                                  {change.entityLabel}
                                </span>
                              )}
                              {statusBadge(change.status)}
                            </div>
                            <div className="text-[10px] text-gray-500 truncate">
                              {change.changeType} · {change.username} · {formatTime(change.timestamp)}
                            </div>
                          </div>
                          {change.entityIRI && (
                            <ExternalLink size={12} className="text-gray-400 flex-shrink-0 mt-0.5" />
                          )}
                        </div>
                      </button>
                    ))}
                  </div>
                  {recentChanges.length > 5 && (
                    <div className="px-3 py-1.5 border-t border-gray-200">
                      <button
                        onClick={() => setShowAllChanges(!showAllChanges)}
                        className="w-full text-xs text-purple-600 hover:text-purple-700 font-medium"
                      >
                        {showAllChanges ? 'Show less' : `Show ${recentChanges.length - 5} more`}
                      </button>
                    </div>
                  )}
                </>
              )}

              {/* Selected change review panel */}
              {selectedChange && (
                <div className="border-t border-gray-200 bg-gray-50 p-3 space-y-2">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <div className="text-xs font-semibold text-gray-800 truncate">
                        {selectedChange.entityLabel || 'Unknown entity'}
                      </div>
                      <div className="text-[10px] text-gray-500 font-mono truncate" title={selectedChange.entityIRI}>
                        {selectedChange.entityIRI || 'No IRI'}
                      </div>
                      <div className="mt-1 flex items-center gap-1 flex-wrap">
                        {statusBadge(selectedChange.status)}
                        <span className="text-[10px] text-gray-500">
                          by {selectedChange.username} · {formatTime(selectedChange.timestamp)}
                        </span>
                      </div>
                    </div>
                    <button
                      onClick={() => setSelectedChange(null)}
                      className="text-gray-400 hover:text-gray-600 p-0.5"
                      title="Close"
                    >
                      <X size={14} />
                    </button>
                  </div>

                  <div className="text-xs text-gray-700 bg-white rounded border p-2">
                    <span className="font-medium">{selectedChange.changeType}</span>
                    {selectedChange.oldValue && selectedChange.newValue && (
                      <div className="mt-1 text-[10px]">
                        <span className="text-red-600 line-through">{selectedChange.oldValue}</span>
                        {' → '}
                        <span className="text-green-600">{selectedChange.newValue}</span>
                      </div>
                    )}
                  </div>

                  <div className="flex flex-wrap gap-1">
                    {selectedChange.entityIRI && (
                      <button
                        onClick={() => navigateToEntity(selectedChange)}
                        className="flex items-center gap-1 px-2 py-1 text-[10px] font-medium bg-purple-600 text-white rounded hover:bg-purple-700"
                      >
                        <ExternalLink size={10} /> Go to entity
                      </button>
                    )}
                    {(selectedChange.status || 'PENDING').toUpperCase() === 'PENDING' && (
                      <>
                        <button
                          onClick={() => handleApprove(selectedChange)}
                          disabled={!!actionLoading}
                          className="flex items-center gap-1 px-2 py-1 text-[10px] font-medium bg-green-600 text-white rounded hover:bg-green-700 disabled:opacity-50"
                        >
                          {actionLoading === 'approve' ? <Loader2 size={10} className="animate-spin" /> : <CheckCircle size={10} />}
                          Approve
                        </button>
                        <button
                          onClick={() => handleReject(selectedChange)}
                          disabled={!!actionLoading}
                          className="flex items-center gap-1 px-2 py-1 text-[10px] font-medium bg-red-600 text-white rounded hover:bg-red-700 disabled:opacity-50"
                        >
                          {actionLoading === 'reject' ? <Loader2 size={10} className="animate-spin" /> : <XCircle size={10} />}
                          Reject
                        </button>
                      </>
                    )}
                    <button
                      onClick={() => handleRollback(selectedChange)}
                      disabled={!!actionLoading || !selectedChange.entityIRI}
                      className="flex items-center gap-1 px-2 py-1 text-[10px] font-medium border border-orange-500 text-orange-600 rounded hover:bg-orange-50 disabled:opacity-50"
                    >
                      {actionLoading === 'rollback' ? <Loader2 size={10} className="animate-spin" /> : <Undo2 size={10} />}
                      Rollback
                    </button>
                    <button
                      onClick={() => handleRevert(selectedChange)}
                      disabled={!!actionLoading || selectedChange.reverted}
                      className="flex items-center gap-1 px-2 py-1 text-[10px] font-medium border border-gray-300 text-gray-600 rounded hover:bg-gray-100 disabled:opacity-50"
                    >
                      {actionLoading === 'revert' ? <Loader2 size={10} className="animate-spin" /> : <Undo2 size={10} />}
                      Mark reverted
                    </button>
                  </div>

                  <div className="space-y-1">
                    <label className="text-[10px] font-medium text-gray-600 flex items-center gap-1">
                      <MessageSquare size={10} /> Request modification
                    </label>
                    <textarea
                      value={modificationNote}
                      onChange={(e) => setModificationNote(e.target.value)}
                      placeholder="Describe what should be changed..."
                      className="w-full text-xs border rounded p-1.5 resize-none h-14 bg-white"
                    />
                    <button
                      onClick={() => handleRequestModification(selectedChange)}
                      disabled={!!actionLoading || !modificationNote.trim()}
                      className="w-full px-2 py-1 text-[10px] font-medium bg-amber-100 text-amber-800 rounded hover:bg-amber-200 disabled:opacity-50"
                    >
                      Send modification request
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </>
  );
});

CollaborationPanel.displayName = 'CollaborationPanel';

export default CollaborationPanel;

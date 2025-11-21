import React, { useState, useEffect, useRef, useImperativeHandle, forwardRef } from 'react';
import { Users, Wifi, WifiOff, Circle, Lock, ChevronRight, ChevronDown, Activity, GripVertical } from 'lucide-react';
import { useCollaboration } from '../contexts/CollaborationContext';
import { changeTrackingService, OntologyChange } from '../services/changeTrackingService';

interface CollaborationPanelProps {
  projectId?: string;
}

export interface CollaborationPanelRef {
  refreshChanges: () => void;
}

const CollaborationPanel = forwardRef<CollaborationPanelRef, CollaborationPanelProps>(({ projectId }, ref) => {
    const { state } = useCollaboration();
    const [isExpanded, setIsExpanded] = useState(false);
    const [isDragging, setIsDragging] = useState(false);
    const [dragStart, setDragStart] = useState({ x: 0, y: 0 });
    const panelRef = useRef<HTMLDivElement>(null);
    const [position, setPosition] = useState<{ x: number; y: number } | null>(null);
    const [collapsedPosition, setCollapsedPosition] = useState<{ x: number; y: number } | null>(null);

    // Initialize position from localStorage or default to bottom-right
    useEffect(() => {
        const savedPosition = localStorage.getItem('collaborationPanelPosition');
        
        if (savedPosition) {
            try {
                const pos = JSON.parse(savedPosition);
                // Always position at bottom-right, ignore saved Y position for collapsed state
                setPosition({ x: pos.x, y: window.innerHeight - 70 });
                setCollapsedPosition({ x: pos.x, y: window.innerHeight - 70 });
            } catch (e) {
                const defaultX = window.innerWidth - 380;
                setPosition({ x: defaultX, y: window.innerHeight - 70 });
                setCollapsedPosition({ x: defaultX, y: window.innerHeight - 70 });
            }
        } else {
            const defaultX = window.innerWidth - 380;
            setPosition({ x: defaultX, y: window.innerHeight - 70 });
            setCollapsedPosition({ x: defaultX, y: window.innerHeight - 70 });
        }
    }, []);
    
    // Adjust position when expanding/collapsing - chatbot style
    useEffect(() => {
        if (position && panelRef.current) {
            const expandedHeight = 400;
            const collapsedHeight = 52; // approximate header height
            const panelWidth = isExpanded ? 340 : 220;
            
            if (isExpanded) {
                // When expanding, move panel UP to accommodate height while keeping bottom fixed
                const bottomY = window.innerHeight - 70;
                const newY = bottomY - expandedHeight + collapsedHeight;
                const newX = Math.min(position.x, window.innerWidth - panelWidth - 20);
                
                setPosition({ x: Math.max(20, newX), y: Math.max(20, newY) });
            } else {
                // When collapsing, return to bottom
                const newX = Math.min(position.x, window.innerWidth - panelWidth - 20);
                setPosition({ x: Math.max(20, newX), y: window.innerHeight - 70 });
            }
        }
    }, [isExpanded]);
    
    // Update position constraints when window resizes
    useEffect(() => {
        if (position && panelRef.current) {
            const handleResize = () => {
                const panelWidth = panelRef.current?.offsetWidth || 220;
                const panelHeight = panelRef.current?.offsetHeight || 60;
                const maxX = window.innerWidth - panelWidth - 20;
                const maxY = window.innerHeight - panelHeight - 20;
                
                setPosition(prev => prev ? ({
                    x: Math.max(20, Math.min(prev.x, maxX)),
                    y: Math.max(20, Math.min(prev.y, maxY))
                }) : null);
            };
            
            window.addEventListener('resize', handleResize);
            return () => window.removeEventListener('resize', handleResize);
        }
    }, [position]);

    // Save position to localStorage when it changes
    useEffect(() => {
        if (position && (position.x !== 0 || position.y !== 0)) {
            localStorage.setItem('collaborationPanelPosition', JSON.stringify(position));
        }
        if (collapsedPosition && (collapsedPosition.x !== 0 || collapsedPosition.y !== 0)) {
            localStorage.setItem('collaborationPanelCollapsedPosition', JSON.stringify(collapsedPosition));
        }
    }, [position, collapsedPosition]);

    const handleMouseDown = (e: React.MouseEvent) => {
        // Only start drag if not clicking on the expand/collapse button
        if (!position) return;
        const target = e.target as HTMLElement;
        if (!target.closest('button')) {
            setIsDragging(true);
            setDragStart({
                x: e.clientX - position.x,
                y: e.clientY - position.y
            });
            e.preventDefault();
        }
    };
    
    const handleDoubleClick = (e: React.MouseEvent) => {
        // Toggle expand/collapse on double-click
        const target = e.target as HTMLElement;
        if (!target.closest('button')) {
            setIsExpanded(!isExpanded);
        }
    };

    useEffect(() => {
        const handleMouseMove = (e: MouseEvent) => {
            if (isDragging) {
                const newX = e.clientX - dragStart.x;
                const newY = e.clientY - dragStart.y;
                
                // Constrain to viewport
                const maxX = window.innerWidth - (panelRef.current?.offsetWidth || 320);
                const maxY = window.innerHeight - (panelRef.current?.offsetHeight || 600);
                
                setPosition({
                    x: Math.max(0, Math.min(newX, maxX)),
                    y: Math.max(0, Math.min(newY, maxY))
                });
            }
        };

        const handleMouseUp = () => {
            setIsDragging(false);
        };

        if (isDragging) {
            document.addEventListener('mousemove', handleMouseMove);
            document.addEventListener('mouseup', handleMouseUp);
        }

        return () => {
            document.removeEventListener('mousemove', handleMouseMove);
            document.removeEventListener('mouseup', handleMouseUp);
        };
    }, [isDragging, dragStart, position]);
    const [recentChanges, setRecentChanges] = useState<OntologyChange[]>([]);
    const [showAllChanges, setShowAllChanges] = useState(false);
    
    // Fetch recent changes when panel opens or projectId changes
    const fetchRecentChanges = async () => {
        if (projectId && isExpanded) {
            const changes = await changeTrackingService.getRecentChanges(projectId, 50);
            setRecentChanges(changes);
        }
    };
    
    // Expose refresh method to parent
    useImperativeHandle(ref, () => ({
        refreshChanges: () => {
            fetchRecentChanges();
        }
    }));
    
    useEffect(() => {
        fetchRecentChanges();
        
        // Refresh every 30 seconds if expanded
        if (isExpanded) {
            const interval = setInterval(fetchRecentChanges, 30000);
            return () => clearInterval(interval);
        }
    }, [projectId, isExpanded]);

    const formatTime = (timestamp: number) => {
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

    // Convert Map to Array and filter by current project
    const allUsers = Array.from(state.activeUsers.values());
    const activeUsers = allUsers.filter(user => 
        !state.currentProjectId || user.projectId === state.currentProjectId
    );
    const locks = Array.from(state.locks.values());

    // Don't render until position is initialized
    if (!position) {
        return null;
    }

    return (
        <>
            <style>{`
                .minimal-scrollbar::-webkit-scrollbar {
                    width: 4px;
                }
                .minimal-scrollbar::-webkit-scrollbar-track {
                    background: transparent;
                }
                .minimal-scrollbar::-webkit-scrollbar-thumb {
                    background: rgba(203, 213, 225, 0.3);
                    border-radius: 2px;
                }
                .minimal-scrollbar::-webkit-scrollbar-thumb:hover {
                    background: rgba(203, 213, 225, 0.5);
                }
            `}</style>
            <div 
                ref={panelRef}
                className="fixed bg-white rounded-lg shadow-2xl border border-gray-300 overflow-hidden z-50" 
                style={{ 
                    width: isExpanded ? '340px' : '220px', 
                    maxHeight: isExpanded ? '400px' : 'auto',
                    left: `${position.x}px`,
                    top: `${position.y}px`,
                    cursor: isDragging ? 'grabbing' : 'default'
                }}
            >
            {/* Header */}
            <div 
                className="bg-gradient-to-r from-purple-600 to-purple-700 text-white shadow-md relative cursor-pointer"
                onClick={(e) => {
                    const target = e.target as HTMLElement;
                    if (!target.closest('button') && !target.closest('[data-drag-handle]')) {
                        setIsExpanded(!isExpanded);
                    }
                }}
            >
                {/* Drag Handle */}
                <div 
                    data-drag-handle
                    className="absolute left-1/2 transform -translate-x-1/2 top-0 cursor-move hover:bg-purple-800 rounded-b px-2 pb-1"
                    onMouseDown={handleMouseDown}
                    title="Drag to move panel"
                    style={{ paddingTop: '2px' }}
                >
                    <GripVertical size={14} className="text-purple-300" />
                </div>
                
                <div 
                    className="px-4 py-3 flex items-center justify-between"
                    style={{ marginTop: '4px' }}
                >
                    <div 
                        className="flex items-center gap-2 flex-1"
                    >
                        <Users size={18} />
                        <span className="font-semibold text-sm">Collaboration</span>
                        <div 
                            className={`flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${
                                state.connected 
                                    ? 'bg-green-500 bg-opacity-90' 
                                    : 'bg-gray-500 bg-opacity-90'
                            }`}
                            title={state.connected 
                                ? `Connected to collaboration server\n${activeUsers.length} active user(s)` 
                                : 'Not connected to collaboration server'}
                        >
                            {state.connected ? (
                                <>
                                    <Wifi size={10} />
                                    <span className="text-xs">Live</span>
                                </>
                            ) : (
                                <>
                                    <WifiOff size={10} />
                                    <span className="text-xs">Offline</span>
                                </>
                            )}
                        </div>
                    </div>
                    <div className="flex items-center">
                        <button
                            onClick={(e) => {
                                e.preventDefault();
                                e.stopPropagation();
                                setIsExpanded(!isExpanded);
                            }}
                            className="hover:bg-purple-800 active:bg-purple-900 rounded-md p-1.5 transition-all duration-200"
                            title={isExpanded ? "Collapse panel" : "Expand panel"}
                        >
                            {isExpanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                        </button>
                    </div>
                </div>
            </div>

            {/* Expanded Content */}
            {isExpanded && (
                <div className="overflow-y-auto" style={{ maxHeight: '320px' }}>
                    {/* Active Users Section */}
                    <div className="border-b border-gray-200">
                        <div className="bg-gray-50 px-4 py-2 flex items-center gap-2 border-b border-gray-200">
                            <Users size={14} className="text-purple-600" />
                            <span className="text-xs font-semibold text-gray-700">
                                Active Users ({activeUsers.length})
                            </span>
                        </div>
                        {activeUsers.length === 0 ? (
                            <div className="px-4 py-6 text-center">
                                <Users size={32} className="mx-auto text-gray-300 mb-2" />
                                <p className="text-xs text-gray-400">No active users</p>
                            </div>
                        ) : (
                            <div className="p-2 space-y-1">
                                {activeUsers.map(user => (
                                    <div 
                                        key={user.userId} 
                                        className="flex items-center gap-3 px-3 py-2 rounded-md hover:bg-gray-50 transition-colors"
                                    >
                                        <div 
                                            className="w-8 h-8 rounded-full flex items-center justify-center text-white font-semibold text-sm shadow-sm flex-shrink-0"
                                            style={{ backgroundColor: user.color }}
                                        >
                                            {user.username.charAt(0).toUpperCase()}
                                        </div>
                                        <div className="flex-1 min-w-0">
                                            <div className="text-sm font-medium text-gray-800 truncate">
                                                {user.username}
                                            </div>
                                            <div className="flex items-center gap-1.5 text-xs text-gray-500">
                                                <Circle size={6} className="text-green-500 fill-current animate-pulse" />
                                                <span>{getTimeAgo(user.lastActivity)}</span>
                                            </div>
                                            {user.cursorPosition && (
                                                <div className="text-xs text-gray-400 truncate mt-0.5" title={user.cursorPosition}>
                                                    📍 {user.cursorPosition.split('#').pop()?.substring(0, 20)}...
                                                </div>
                                            )}
                                        </div>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>

                    {/* Locked Nodes Section */}
                    <div className="border-b border-gray-200">
                        <div className="bg-gray-50 px-4 py-2 flex items-center gap-2 border-b border-gray-200">
                            <Lock size={14} className="text-amber-600" />
                            <span className="text-xs font-semibold text-gray-700">
                                Locked Nodes ({locks.length})
                            </span>
                        </div>
                        {locks.length === 0 ? (
                            <div className="px-4 py-6 text-center">
                                <Lock size={32} className="mx-auto text-gray-300 mb-2" />
                                <p className="text-xs text-gray-400">No locked nodes</p>
                            </div>
                        ) : (
                            <div className="p-2 space-y-1">
                                {locks.map(lock => (
                                    <div 
                                        key={lock.nodeId}
                                        className="flex items-start gap-2 px-3 py-2 rounded-md hover:bg-gray-50 transition-colors"
                                    >
                                        <Lock size={14} className="text-amber-600 mt-0.5 flex-shrink-0" />
                                        <div className="flex-1 min-w-0">
                                            <div className="text-xs font-medium text-gray-800 truncate">
                                                {lock.nodeId}
                                            </div>
                                            <div className="text-xs text-gray-500">
                                                by <span className="font-medium">{lock.username}</span>
                                            </div>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>

                    {/* Recent Activity Section */}
                    <div>
                        <div className="bg-gray-50 px-4 py-2 flex items-center gap-2 border-b border-gray-200">
                            <Activity size={14} className="text-blue-600" />
                            <span className="text-xs font-semibold text-gray-700">
                                Recent Activity
                            </span>
                        </div>
                        {recentChanges.length === 0 ? (
                            <div className="px-4 py-6 text-center">
                                <Activity size={32} className="mx-auto text-gray-300 mb-2" />
                                <p className="text-xs text-gray-400 mb-1">No recent activity</p>
                                <p className="text-xs text-gray-500">Activity will appear when you save changes</p>
                            </div>
                        ) : (
                            <>
                                <div 
                                    className="p-2 space-y-1 overflow-y-auto minimal-scrollbar" 
                                    style={{ 
                                        maxHeight: '200px',
                                        scrollbarWidth: 'thin',
                                        scrollbarColor: 'rgba(203, 213, 225, 0.3) transparent'
                                    }}
                                >
                                    {(showAllChanges ? recentChanges : recentChanges.slice(0, 4)).map(change => (
                                        <div 
                                            key={change.id}
                                            className="px-3 py-2 rounded-md hover:bg-gray-50 transition-colors"
                                        >
                                            <div className="flex items-start gap-2">
                                                <span className={`text-xs font-semibold mt-0.5 ${
                                                    change.changeType.startsWith('ADD') ? 'text-green-600' :
                                                    change.changeType.startsWith('DELETE') || change.changeType.startsWith('REMOVE') ? 'text-red-600' :
                                                    change.changeType.startsWith('RENAME') ? 'text-blue-600' :
                                                    'text-amber-600'
                                                }`}>
                                                    {change.changeType.startsWith('ADD') ? '+ ' :
                                                     change.changeType.startsWith('DELETE') || change.changeType.startsWith('REMOVE') ? '− ' :
                                                     change.changeType.startsWith('RENAME') ? '✎ ' : '• '}
                                                </span>
                                                <div className="flex-1">
                                                    <div className="text-xs text-gray-700 mb-1">
                                                        {change.description}
                                                    </div>
                                                    <div className="text-xs text-gray-500">
                                                        by <span className="font-medium">{change.username}</span>
                                                        <span className="mx-1">•</span>
                                                        {formatTime(change.timestamp)}
                                                    </div>
                                                </div>
                                            </div>
                                        </div>
                                    ))}
                                </div>
                                {recentChanges.length > 4 && (
                                    <div className="px-4 py-2 border-t border-gray-200">
                                        <button
                                            onClick={() => setShowAllChanges(!showAllChanges)}
                                            className="w-full text-xs text-purple-600 hover:text-purple-700 font-medium py-1"
                                        >
                                            {showAllChanges ? 'Show less' : `Show ${recentChanges.length - 4} more`}
                                        </button>
                                    </div>
                                )}
                            </>
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

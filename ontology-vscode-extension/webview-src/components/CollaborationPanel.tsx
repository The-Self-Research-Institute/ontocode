import React, { useState, useRef, useEffect } from 'react';
import { Users, Wifi, WifiOff, Circle, Lock, ChevronRight, ChevronDown, Activity, Move } from 'lucide-react';
import { useCollaboration } from '../contexts/CollaborationContext';

const CollaborationPanel: React.FC = () => {
    const { state } = useCollaboration();
    const [isExpanded, setIsExpanded] = useState(true);
    const [position, setPosition] = useState({ x: 0, y: 0 });
    const [isDragging, setIsDragging] = useState(false);
    const [dragStart, setDragStart] = useState({ x: 0, y: 0 });
    const panelRef = useRef<HTMLDivElement>(null);

    // Initialize position from localStorage or default to bottom-right
    useEffect(() => {
        const savedPosition = localStorage.getItem('collaborationPanelPosition');
        if (savedPosition) {
            setPosition(JSON.parse(savedPosition));
        } else {
            // Default position: bottom-right with some margin
            setPosition({ x: window.innerWidth - 340, y: window.innerHeight - 620 });
        }
    }, []);

    // Save position to localStorage when it changes
    useEffect(() => {
        if (position.x !== 0 || position.y !== 0) {
            localStorage.setItem('collaborationPanelPosition', JSON.stringify(position));
        }
    }, [position]);

    const handleMouseDown = (e: React.MouseEvent) => {
        // Only start drag if clicking on the drag handle area (not the expand/collapse button)
        const target = e.target as HTMLElement;
        if (target.closest('.drag-handle') && !target.closest('button')) {
            setIsDragging(true);
            setDragStart({
                x: e.clientX - position.x,
                y: e.clientY - position.y
            });
            e.preventDefault();
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

    return (
        <div 
            ref={panelRef}
            className="fixed bg-white rounded-lg shadow-2xl border border-gray-300 overflow-hidden z-50" 
            style={{ 
                width: isExpanded ? '400px' : '300px', 
                maxHeight: '600px',
                left: `${position.x}px`,
                top: `${position.y}px`,
                cursor: isDragging ? 'grabbing' : 'default'
            }}
        >
            {/* Header */}
            <div className="bg-gradient-to-r from-purple-600 to-purple-700 text-white shadow-md">
                <div 
                    className="px-4 py-3 flex items-center justify-between"
                >
                    <div 
                        className="flex items-center gap-2 drag-handle cursor-move flex-1" 
                        title="Drag to move panel"
                        onMouseDown={handleMouseDown}
                    >
                        <Move size={14} className="text-purple-200" />
                        <Users size={18} />
                        <span className="font-semibold text-sm">Collaboration</span>
                    </div>
                    <div className="flex items-center gap-2">
                        <div 
                            className={`flex items-center gap-1.5 px-2 py-1 rounded-full text-xs font-medium ${
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
                                    <Wifi size={12} />
                                    <span>Live</span>
                                </>
                            ) : (
                                <>
                                    <WifiOff size={12} />
                                    <span>Offline</span>
                                </>
                            )}
                        </div>
                        <button
                            onClick={(e) => {
                                e.preventDefault();
                                e.stopPropagation();
                                setIsExpanded(!isExpanded);
                            }}
                            className="hover:bg-purple-800 active:bg-purple-900 rounded-md p-1.5 transition-all duration-200 ml-1"
                            title={isExpanded ? "Collapse panel" : "Expand panel"}
                        >
                            {isExpanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                        </button>
                    </div>
                </div>
            </div>

            {/* Expanded Content */}
            {isExpanded && (
                <div className="overflow-y-auto" style={{ maxHeight: '520px' }}>
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
                        {state.notifications.length === 0 ? (
                            <div className="px-4 py-6 text-center">
                                <Activity size={32} className="mx-auto text-gray-300 mb-2" />
                                <p className="text-xs text-gray-400 mb-1">No recent activity</p>
                                <p className="text-xs text-gray-500">Activity will appear when you or others make edits</p>
                            </div>
                        ) : (
                            <div className="p-2 space-y-1">
                                {state.notifications.slice(0, 5).map(notif => (
                                    <div 
                                        key={notif.id}
                                        className="px-3 py-2 rounded-md hover:bg-gray-50 transition-colors"
                                    >
                                        <div className="text-xs text-gray-700 mb-1">
                                            {notif.message}
                                        </div>
                                        <div className="text-xs text-gray-400">
                                            {formatTime(notif.timestamp)}
                                        </div>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
};

export default CollaborationPanel;

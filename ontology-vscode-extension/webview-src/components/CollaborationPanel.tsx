import React, { useState } from 'react';
import { Users, Wifi, WifiOff, Circle, Lock, ChevronRight, ChevronDown, Activity } from 'lucide-react';
import { useCollaboration } from '../contexts/CollaborationContext';

const CollaborationPanel: React.FC = () => {
    const { state } = useCollaboration();
    const [isExpanded, setIsExpanded] = useState(true);

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

    // Convert Map to Array for rendering
    const activeUsers = Array.from(state.activeUsers.values());
    const locks = Array.from(state.locks.values());

    return (
        <div className="fixed bottom-4 right-4 bg-white rounded-lg shadow-2xl border border-gray-300 overflow-hidden z-50" 
             style={{ width: isExpanded ? '320px' : '180px', maxHeight: '600px' }}>
            {/* Header */}
            <div 
                className="bg-gradient-to-r from-purple-600 to-purple-700 text-white px-4 py-3 flex items-center justify-between cursor-pointer hover:from-purple-700 hover:to-purple-800 transition-all"
                onClick={() => setIsExpanded(!isExpanded)}
            >
                <div className="flex items-center gap-2">
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
                    {isExpanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
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

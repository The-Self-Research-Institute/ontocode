import React, { createContext, useState, useEffect, useCallback, ReactNode } from 'react';

// Types matching the collaboration types from extension
export interface ActiveUser {
    userId: string;
    username: string;
    color: string;
    lastActivity: number;
    projectId?: string;  // Track which project/file the user is viewing
    cursorPosition?: string;
    selectedNodes?: string[];
}

export interface NodeLock {
    nodeId: string;
    userId: string;
    username: string;
    expiresAt: number;
    timestamp: number;
}

export interface EditNotification {
    id: string;
    type: 'success' | 'error' | 'info' | 'warning';
    message: string;
    userId: string;
    username: string;
    userColor: string;
    timestamp: number;
}

export interface CollaborationState {
    connected: boolean;
    currentProjectId: string | null;  // Track the current project being viewed
    activeUsers: Map<string, ActiveUser>;
    locks: Map<string, NodeLock>;
    notifications: EditNotification[];
}

interface CollaborationContextType {
    state: CollaborationState;
    setCurrentProject: (projectId: string | null) => void;
    addNotification: (notification: Omit<EditNotification, 'id'>) => void;
    removeNotification: (id: string) => void;
    clearNotifications: () => void;
}

export const CollaborationContext = createContext<CollaborationContextType | undefined>(undefined);

export const CollaborationProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
    const [state, setState] = useState<CollaborationState>({
        connected: false,
        currentProjectId: null,
        activeUsers: new Map(),
        locks: new Map(),
        notifications: [],
    });

    // Listen to messages from the VS Code extension
    useEffect(() => {
        const handleMessage = (event: MessageEvent) => {
            const message = event.data;
            
            console.log('[CollaborationContext] 📨 Received message:', message.type, message);
            
            switch (message.type) {
                case 'collaborationStatus':
                    console.log('[CollaborationContext] ✅ Updating connection status to:', message.connected);
                    setState(prev => {
                        const wasDisconnected = !prev.connected;
                        const isNowConnected = message.connected;
                        
                        // If reconnecting after a disconnection, dispatch event to refresh data
                        if (wasDisconnected && isNowConnected) {
                            console.log('[CollaborationContext] 🔄 Reconnected! Dispatching refresh event...');
                            const reconnectEvent = new CustomEvent('collaborationReconnected', {
                                detail: { timestamp: Date.now() }
                            });
                            window.dispatchEvent(reconnectEvent);
                        }
                        
                        return {
                            ...prev,
                            connected: message.connected,
                        };
                    });
                    break;

                case 'presenceUpdate':
                    handlePresenceUpdate(message.presence);
                    break;

                case 'lockUpdate':
                    handleLockUpdate(message.lock);
                    break;

                case 'remoteEdit':
                    handleRemoteEdit(message.edit);
                    break;

                case 'ROLLBACK':
                    console.log('[CollaborationContext] 🔄 Rollback event received:', message);
                    // Dispatch a custom event that Dashboard can listen to
                    const rollbackEvent = new CustomEvent('ontologyRollback', {
                        detail: message
                    });
                    window.dispatchEvent(rollbackEvent);
                    break;

                case 'shareNotification':
                    console.log('[CollaborationContext] 📨 Share notification received:', message.notification);
                    // Dispatch a custom event that Dashboard can listen to refresh file list
                    const shareEvent = new CustomEvent('fileShared', {
                        detail: message.notification
                    });
                    window.dispatchEvent(shareEvent);
                    break;
            }
        };

        window.addEventListener('message', handleMessage);
        
        console.log('[CollaborationContext] 🚀 Component mounted, requesting collaboration status...');
        
        // Request current collaboration status when component mounts
        if (window.vscode) {
            window.vscode.postMessage({ type: 'requestCollaborationStatus' });
        }
        
        return () => {
            window.removeEventListener('message', handleMessage);
        };
    }, []);

    const handlePresenceUpdate = useCallback((presence: any) => {
        setState(prev => {
            const newUsers = new Map(prev.activeUsers);
            
            switch (presence.type) {
                case 'USER_JOINED':
                case 'USER_ACTIVE':
                case 'CURSOR_MOVED':
                case 'SELECTION_CHANGED':
                    newUsers.set(presence.userId, {
                        userId: presence.userId,
                        username: presence.username,
                        color: presence.color || '#888888',
                        lastActivity: presence.timestamp,
                        projectId: presence.projectId,  // Track which project user is viewing
                        cursorPosition: presence.cursorPosition,
                        selectedNodes: presence.selectedNodes,
                    });
                    break;

                case 'USER_LEFT':
                    newUsers.delete(presence.userId);
                    break;
            }

            return {
                ...prev,
                activeUsers: newUsers,
            };
        });
    }, []);

    const handleLockUpdate = useCallback((lock: any) => {
        setState(prev => {
            const newLocks = new Map(prev.locks);
            
            switch (lock.type) {
                case 'LOCK_ACQUIRED':
                    newLocks.set(lock.nodeId, {
                        nodeId: lock.nodeId,
                        userId: lock.userId,
                        username: lock.username,
                        expiresAt: lock.expiresAt,
                        timestamp: lock.timestamp,
                    });
                    break;

                case 'LOCK_RELEASED':
                case 'LOCK_EXPIRED':
                    newLocks.delete(lock.nodeId);
                    break;
            }

            return {
                ...prev,
                locks: newLocks,
            };
        });
    }, []);

   

    const getEditActionDescription = (operationType: string): string => {
        const actionMap: Record<string, string> = {
            CLASS_ADDED: 'added a class',
            CLASS_MODIFIED: 'modified a class',
            CLASS_DELETED: 'deleted a class',
            CLASS_RENAMED: 'renamed a class',
            PROPERTY_ADDED: 'added a property',
            PROPERTY_MODIFIED: 'modified a property',
            PROPERTY_DELETED: 'deleted a property',
            ANNOTATION_ADDED: 'added an annotation',
            ANNOTATION_MODIFIED: 'modified an annotation',
            ANNOTATION_DELETED: 'deleted an annotation',
            SUBCLASS_ADDED: 'added a subclass relationship',
            SUBCLASS_REMOVED: 'removed a subclass relationship',
            INDIVIDUAL_ADDED: 'added an individual',
            INDIVIDUAL_MODIFIED: 'modified an individual',
            INDIVIDUAL_DELETED: 'deleted an individual',
            // New types for axioms
            DISJOINT_ADDED: 'made classes disjoint',
            DISJOINT_REMOVED: 'removed disjoint axiom',
            EQUIVALENT_ADDED: 'added equivalent class',
            EQUIVALENT_REMOVED: 'removed equivalent class',
            // SPARQL and revert notifications
            SPARQL_UPDATE: 'executed a SPARQL update',
            CHANGE_REVERTED: 'reverted a change',
            PROJECT_SAVED: 'saved the project',
            // SWRL rule notifications
            SWRL_RULE_ADDED: 'added a SWRL rule',
            SWRL_RULE_MODIFIED: 'modified a SWRL rule',
            SWRL_RULE_DELETED: 'deleted a SWRL rule',
        };
        return actionMap[operationType] || 'made a change';
    };

    const addNotification = useCallback((notification: Omit<EditNotification, 'id'>) => {
        const id = `notif-${Date.now()}-${Math.random()}`;
        setState(prev => {
            // Get user color from activeUsers
            const user = prev.activeUsers.get(notification.userId);
            const userColor = user?.color || notification.userColor;

            return {
                ...prev,
                notifications: [
                    ...prev.notifications,
                    { ...notification, id, userColor },
                ],
            };
        });

        // Auto-remove after 5 seconds
        setTimeout(() => {
            removeNotification(id);
        }, 5000);
    }, []);

    const removeNotification = useCallback((id: string) => {
        setState(prev => ({
            ...prev,
            notifications: prev.notifications.filter(n => n.id !== id),
        }));
    }, []);

    const clearNotifications = useCallback(() => {
        setState(prev => ({
            ...prev,
            notifications: [],
        }));
    }, []);

     const handleRemoteEdit = useCallback((edit: any) => {
        console.log('[CollaborationContext] 📝 Processing remote edit:', edit);
        
        // CRITICAL: Dispatch a custom event that the Dashboard/ontology tree can listen to
        // This ensures the UI updates instantly when receiving annotations or other changes
        const remoteEditEvent = new CustomEvent('remoteEditReceived', {
            detail: edit,
        });
        
        window.dispatchEvent(remoteEditEvent);
        console.log('[CollaborationContext] ✅ Dispatched remoteEditReceived event');
        
        // Add notification for remote edits and remove user from active users
        setState(prev => {
            const id = `notif-${Date.now()}-${Math.random()}`;
            const notification: Omit<EditNotification, 'id'> = {
                type: 'info',
                message: `${edit.username} ${getEditActionDescription(edit.type)}`,
                userId: edit.userId,
                username: edit.username,
                userColor: '#888888',
                timestamp: edit.timestamp,
            };
            
            // Get user color from activeUsers
            const user = prev.activeUsers.get(notification.userId);
            const userColor = user?.color || notification.userColor;

            // Remove the user who made the edit from active users
            const newUsers = new Map(prev.activeUsers);
            newUsers.delete(edit.userId);
            console.log('[CollaborationContext] 👤 Removed user from active users:', edit.userId, edit.username);

            const newState = {
                ...prev,
                activeUsers: newUsers,
                notifications: [
                    ...prev.notifications,
                    { ...notification, id, userColor },
                ],
            };

            // Auto-remove after 5 seconds
            setTimeout(() => {
                setState(s => ({
                    ...s,
                    notifications: s.notifications.filter(n => n.id !== id),
                }));
            }, 5000);

            return newState;
        });
        
        console.log('[CollaborationContext] 📢 Added notification for remote edit');
    }, []);

    const setCurrentProject = useCallback((projectId: string | null) => {
        setState(prev => ({
            ...prev,
            currentProjectId: projectId,
        }));
    }, []);

    const value: CollaborationContextType = {
        state,
        setCurrentProject,
        addNotification,
        removeNotification,
        clearNotifications,
    };

    return (
        <CollaborationContext.Provider value={value}>
            {children}
        </CollaborationContext.Provider>
    );
};

// Custom hook for using collaboration context
export const useCollaboration = (): CollaborationContextType => {
    const context = React.useContext(CollaborationContext);
    if (!context) {
        throw new Error('useCollaboration must be used within a CollaborationProvider');
    }
    return context;
};

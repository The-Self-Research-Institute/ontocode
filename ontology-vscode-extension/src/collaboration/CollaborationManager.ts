import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import {
    EditOperation,
    PresenceMessage,
    LockMessage,
    ActiveUser,
    CollaborationState,
    PresenceType,
    LockType,
    ICollaborationManager
} from './types';

/**
 * Manages WebSocket connection for collaborative editing.
 * Handles connection lifecycle, reconnection, and message routing.
 */
export class CollaborationManager implements ICollaborationManager {
    private client: Client | null = null;
    private subscriptions: Map<string, StompSubscription> = new Map();
    private state: CollaborationState;
    private reconnectAttempts = 0;
    private maxReconnectAttempts = 10;
    private reconnectDelay = 1000; // Start with 1 second
    
    // Event handlers
    private onEditReceived?: (edit: EditOperation) => void;
    private onPresenceUpdate?: (presence: PresenceMessage) => void;
    private onLockUpdate?: (lock: LockMessage) => void;
    private onImportStatusUpdate?: (status: any) => void;
    private onConnectionChange?: (connected: boolean) => void;
    private onError?: (error: string) => void;
    private onShareNotification?: (notification: any) => void;

    constructor(
        private serverUrl: string,
        private userId: string,
        private username: string
    ) {
        this.state = {
            connected: false,
            projectId: null,
            activeUsers: new Map(),
            locks: new Map(),
            pendingEdits: []
        };
    }

    /**
     * Connect to the WebSocket server.
     */
    connect(): Promise<void> {
        return new Promise((resolve, reject) => {
            try {
                // Create STOMP client with SockJS
                this.client = new Client({
                    webSocketFactory: () => new SockJS(`${this.serverUrl}/ws`) as any,
                    
                    connectHeaders: {
                        // Add authentication headers here if needed
                    },
                    
                    // Disable verbose STOMP debug logging
                    debug: () => {},
                    
                    reconnectDelay: this.reconnectDelay,
                    
                    heartbeatIncoming: 4000,
                    heartbeatOutgoing: 4000,
                    
                    onConnect: () => {
                        console.log('[CollaborationManager] ✅ WebSocket connected successfully');
                        this.state.connected = true;
                        this.reconnectAttempts = 0;
                        this.reconnectDelay = 1000;
                        
                        console.log('[CollaborationManager] Connection state updated to:', this.state.connected);
                        
                        if (this.onConnectionChange) {
                            console.log('[CollaborationManager] Calling onConnectionChange(true) callback');
                            this.onConnectionChange(true);
                        } else {
                            console.warn('[CollaborationManager] ⚠️  No onConnectionChange callback registered!');
                        }
                        
                        // Process any pending edits
                        this.processPendingEdits();
                        
                        resolve();
                    },
                    
                    onStompError: (frame: any) => {
                        console.error('STOMP error:', frame);
                        const error = `STOMP error: ${frame.headers['message'] || 'Unknown error'}`;
                        if (this.onError) {
                            this.onError(error);
                        }
                        reject(new Error(error));
                    },
                    
                    onWebSocketError: (event: any) => {
                        console.error('WebSocket error:', event);
                        const error = 'WebSocket connection error';
                        if (this.onError) {
                            this.onError(error);
                        }
                    },
                    
                    onDisconnect: () => {
                        console.log('WebSocket disconnected');
                        this.state.connected = false;
                        
                        if (this.onConnectionChange) {
                            this.onConnectionChange(false);
                        }
                        
                        // Attempt reconnection with exponential backoff
                        this.attemptReconnect();
                    }
                });
                
                this.client.activate();
                
            } catch (error) {
                console.error('Failed to create WebSocket client:', error);
                reject(error);
            }
        });
    }

    /**
     * Disconnect from the WebSocket server.
     */
    async disconnect(): Promise<void> {
        if (this.state.projectId) {
            // Send USER_LEFT presence message
            await this.sendPresence(PresenceType.USER_LEFT);
        }
        
        // Unsubscribe from all topics
        this.subscriptions.forEach(sub => sub.unsubscribe());
        this.subscriptions.clear();
        
        // Deactivate client
        if (this.client) {
            await this.client.deactivate();
            this.client = null;
        }
        
        this.state.connected = false;
        this.state.projectId = null;
        this.state.activeUsers.clear();
        this.state.locks.clear();
    }

    /**
     * Join a project for collaborative editing.
     */
    async joinProject(projectId: string): Promise<void> {
        if (!this.client || !this.state.connected) {
            throw new Error('Not connected to server');
        }
        
        // Leave current project if any
        if (this.state.projectId && this.state.projectId !== projectId) {
            await this.leaveProject();
        }
        
        this.state.projectId = projectId;
        
        // Subscribe to project topics FIRST (before sending join message)
        this.subscribeToEdit(projectId);
        this.subscribeToPresence(projectId);
        this.subscribeToLocks(projectId);
        this.subscribeToImportStatus(projectId);
        
        // Wait a bit for subscriptions to be established
        await new Promise(resolve => setTimeout(resolve, 100));
        
        // Send USER_JOINED presence (will be broadcast back to us)
        await this.sendPresence(PresenceType.USER_JOINED);
        
        console.log(`Joined project: ${projectId}`);
    }

    /**
     * Leave the current project.
     */
    async leaveProject(): Promise<void> {
        if (!this.state.projectId) return;
        
        // Send USER_LEFT presence
        await this.sendPresence(PresenceType.USER_LEFT);
        
        // Unsubscribe from all topics
        this.subscriptions.forEach(sub => sub.unsubscribe());
        this.subscriptions.clear();
        
        this.state.projectId = null;
        this.state.activeUsers.clear();
        this.state.locks.clear();
        
        console.log('Left project');
    }

    /**
     * Send an edit operation.
     */
    async sendEdit(edit: Omit<EditOperation, 'userId' | 'username' | 'timestamp'>): Promise<void> {
        if (!this.client || !this.state.connected) {
            // Queue for later if disconnected
            this.state.pendingEdits.push({
                ...edit,
                userId: this.userId,
                username: this.username,
                timestamp: Date.now()
            } as EditOperation);
            console.warn('Queued edit for later (disconnected)');
            return;
        }
        
        if (!this.state.projectId) {
            throw new Error('Not in a project');
        }
        
        const operation: EditOperation = {
            ...edit,
            userId: this.userId,
            username: this.username,
            timestamp: Date.now()
        };
        
        this.client.publish({
            destination: `/app/collab/${this.state.projectId}/edit`,
            body: JSON.stringify(operation)
        });
    }

    /**
     * Send a presence update.
     */
    async sendPresence(type: PresenceType, data?: Partial<PresenceMessage>): Promise<void> {
        if (!this.client || !this.state.connected || !this.state.projectId) {
            return;
        }
        
        const message: PresenceMessage = {
            type,
            projectId: this.state.projectId,
            userId: this.userId,
            username: this.username,
            timestamp: Date.now(),
            ...data
        };
        
        this.client.publish({
            destination: `/app/collab/${this.state.projectId}/presence`,
            body: JSON.stringify(message)
        });
    }

    /**
     * Request a lock on a node.
     */
    async requestLock(nodeId: string): Promise<void> {
        if (!this.client || !this.state.connected || !this.state.projectId) {
            throw new Error('Not connected or not in a project');
        }
        
        const message: LockMessage = {
            type: LockType.LOCK_REQUEST,
            projectId: this.state.projectId,
            nodeId,
            userId: this.userId,
            username: this.username,
            timestamp: Date.now()
        };
        
        this.client.publish({
            destination: `/app/collab/${this.state.projectId}/lock`,
            body: JSON.stringify(message)
        });
    }

    /**
     * Release a lock on a node.
     */
    async releaseLock(nodeId: string): Promise<void> {
        if (!this.client || !this.state.connected || !this.state.projectId) {
            return;
        }
        
        const message: LockMessage = {
            type: LockType.LOCK_RELEASED,
            projectId: this.state.projectId,
            nodeId,
            userId: this.userId,
            username: this.username,
            timestamp: Date.now()
        };
        
        this.client.publish({
            destination: `/app/collab/${this.state.projectId}/lock`,
            body: JSON.stringify(message)
        });
        
        // Remove from local state
        this.state.locks.delete(nodeId);
    }

    /**
     * Get current collaboration state.
     */
    getState(): Readonly<CollaborationState> {
        return { ...this.state };
    }

    /**
     * Set event handlers.
     */
    setHandlers(handlers: {
        onEditReceived?: (edit: EditOperation) => void;
        onPresenceUpdate?: (presence: PresenceMessage) => void;
        onLockUpdate?: (lock: LockMessage) => void;
        onImportStatusUpdate?: (status: any) => void;
        onConnectionChange?: (connected: boolean) => void;
        onError?: (error: string) => void;
        onShareNotification?: (notification: any) => void;
    }): void {
        this.onEditReceived = handlers.onEditReceived;
        this.onPresenceUpdate = handlers.onPresenceUpdate;
        this.onLockUpdate = handlers.onLockUpdate;
        this.onImportStatusUpdate = handlers.onImportStatusUpdate;
        this.onConnectionChange = handlers.onConnectionChange;
        this.onError = handlers.onError;
        this.onShareNotification = handlers.onShareNotification;
    }

    /**
     * Check if currently connected to the server.
     */
    isConnected(): boolean {
        return this.state.connected;
    }

    // Private methods

    private subscribeToEdit(projectId: string): void {
        if (!this.client) return;
        
        const subscription = this.client.subscribe(
            `/topic/ontology/${projectId}`,
            (message: IMessage) => {
                try {
                    const edit: EditOperation = JSON.parse(message.body);
                    
                    // Ignore our own edits
                    if (edit.userId === this.userId) return;
                    
                    console.log('Received edit:', edit);
                    
                    if (this.onEditReceived) {
                        this.onEditReceived(edit);
                    }
                } catch (error) {
                    console.error('Failed to parse edit message:', error);
                }
            }
        );
        
        this.subscriptions.set('edit', subscription);
    }

    private subscribeToPresence(projectId: string): void {
        if (!this.client) return;
        
        const subscription = this.client.subscribe(
            `/topic/presence/${projectId}`,
            (message: IMessage) => {
                try {
                    const presence: PresenceMessage = JSON.parse(message.body);
                    
                    console.log('Presence update:', presence);
                    
                    // Update active users
                    if (presence.type === PresenceType.USER_JOINED) {
                        this.state.activeUsers.set(presence.userId, {
                            userId: presence.userId,
                            username: presence.username,
                            sessionId: presence.sessionId || '',
                            color: presence.color || '#999999',
                            lastActivity: presence.timestamp,
                            cursorPosition: presence.cursorPosition,
                            selectedNodes: presence.selectedNodes
                        });
                    } else if (presence.type === PresenceType.USER_LEFT) {
                        this.state.activeUsers.delete(presence.userId);
                    } else {
                        // Update existing user
                        const user = this.state.activeUsers.get(presence.userId);
                        if (user) {
                            user.lastActivity = presence.timestamp;
                            user.cursorPosition = presence.cursorPosition;
                            user.selectedNodes = presence.selectedNodes;
                        }
                    }
                    
                    if (this.onPresenceUpdate) {
                        this.onPresenceUpdate(presence);
                    }
                } catch (error) {
                    console.error('Failed to parse presence message:', error);
                }
            }
        );
        
        this.subscriptions.set('presence', subscription);
    }

    private subscribeToLocks(projectId: string): void {
        if (!this.client) return;
        
        const subscription = this.client.subscribe(
            `/topic/locks/${projectId}`,
            (message: IMessage) => {
                try {
                    const lock: LockMessage = JSON.parse(message.body);
                    
                    console.log('Lock update:', lock);
                    
                    // Update locks state
                    if (lock.type === LockType.LOCK_ACQUIRED) {
                        this.state.locks.set(lock.nodeId, lock);
                    } else if (lock.type === LockType.LOCK_RELEASED || lock.type === LockType.LOCK_EXPIRED) {
                        this.state.locks.delete(lock.nodeId);
                    }
                    
                    if (this.onLockUpdate) {
                        this.onLockUpdate(lock);
                    }
                } catch (error) {
                    console.error('Failed to parse lock message:', error);
                }
            }
        );
        
        this.subscriptions.set('locks', subscription);
    }

    /**
     * Subscribe to import status updates for a project.
     */
    private subscribeToImportStatus(projectId: string): void {
        if (!this.client) {
            console.error('[CollaborationManager] ❌ Cannot subscribe to import status - no client');
            return;
        }

        console.log(`[CollaborationManager] 📡 Subscribing to /topic/import/${projectId}`);

        const subscription = this.client.subscribe(
            `/topic/import/${projectId}`,
            (message: IMessage) => {
                console.log('[CollaborationManager] 📨 Received import status message:', message.body);
                try {
                    const importStatus = JSON.parse(message.body);

                    console.log('[CollaborationManager] ✅ Parsed import status:', importStatus);

                    if (this.onImportStatusUpdate) {
                        console.log('[CollaborationManager] 📤 Calling onImportStatusUpdate handler');
                        this.onImportStatusUpdate(importStatus);
                    } else {
                        console.warn('[CollaborationManager] ⚠️  No onImportStatusUpdate handler registered!');
                    }
                } catch (error) {
                    console.error('[CollaborationManager] ❌ Error parsing import status:', error);
                }
            }
        );

        this.subscriptions.set(`import-${projectId}`, subscription);
        console.log(`[CollaborationManager] ✅ Subscribed to import status for project: ${projectId}`);
    }

    /**
     * Subscribe to share notifications for the current user.
     * Receives instant notifications when files are shared with this user.
     */
    subscribeToShareNotifications(userEmail: string): void {
        if (!this.client) {
            console.error('[CollaborationManager] ❌ Cannot subscribe to share notifications - no client');
            return;
        }

        console.log(`[CollaborationManager] 📡 Subscribing to /topic/shares/${userEmail}`);

        const subscription = this.client.subscribe(
            `/topic/shares/${userEmail}`,
            (message: IMessage) => {
                console.log('[CollaborationManager] 📨 Received share notification:', message.body);
                try {
                    const shareNotification = JSON.parse(message.body);

                    console.log('[CollaborationManager] ✅ Parsed share notification:', shareNotification);

                    if (this.onShareNotification) {
                        console.log('[CollaborationManager] 📤 Calling onShareNotification handler');
                        this.onShareNotification(shareNotification);
                    } else {
                        console.warn('[CollaborationManager] ⚠️  No onShareNotification handler registered!');
                    }
                } catch (error) {
                    console.error('[CollaborationManager] ❌ Error parsing share notification:', error);
                }
            }
        );

        this.subscriptions.set(`shares-${userEmail}`, subscription);
        console.log(`[CollaborationManager] ✅ Subscribed to share notifications for: ${userEmail}`);
    }

    private processPendingEdits(): void {
        if (this.state.pendingEdits.length === 0) return;
        
        console.log(`Processing ${this.state.pendingEdits.length} pending edits`);
        
        const edits = [...this.state.pendingEdits];
        this.state.pendingEdits = [];
        
        edits.forEach(edit => {
            this.sendEdit(edit).catch(error => {
                console.error('Failed to send pending edit:', error);
            });
        });
    }

    private attemptReconnect(): void {
        if (this.reconnectAttempts >= this.maxReconnectAttempts) {
            console.error('Max reconnection attempts reached');
            if (this.onError) {
                this.onError('Failed to reconnect after multiple attempts');
            }
            return;
        }
        
        this.reconnectAttempts++;
        
        // Exponential backoff
        this.reconnectDelay = Math.min(this.reconnectDelay * 2, 30000); // Max 30 seconds
        
        console.log(`Reconnecting in ${this.reconnectDelay}ms (attempt ${this.reconnectAttempts})`);
        
        setTimeout(() => {
            if (this.client) {
                this.client.activate();
            }
        }, this.reconnectDelay);
    }

    /**
     * Broadcast cursor position to other users in the project (Node.js version - stub)
     */
    broadcastCursorPosition(projectId: string, userId: string, userName: string, position: { x: number; y: number }): void {
        // Node.js version stub - functionality is in web version
        console.log('[CollaborationManager] broadcastCursorPosition called (Node.js stub)');
    }
}

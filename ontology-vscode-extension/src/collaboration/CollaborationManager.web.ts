import { Client, StompSubscription } from '@stomp/stompjs';
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
 * Browser-compatible WebSocket manager for collaborative editing.
 * Uses native WebSocket instead of SockJS for VS Code web compatibility.
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
    private onCursorUpdate?: (cursor: { userId: string; userName: string; position: { x: number; y: number }; timestamp: number }) => void;

    constructor(
        private serverUrl: string,
        private userId: string,
        private username: string,
        private getAuthToken?: () => string | null | Promise<string | null>
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
     * Connect to the WebSocket server using native WebSocket.
     */
    connect(): Promise<void> {
        return new Promise((resolve, reject) => {
            try {
                // Convert http/https URL to ws/wss
                const wsUrl = new URL('/ws/websocket', this.serverUrl).toString().replace(/^http/, 'ws');

                console.log('[CollaborationManager] Connecting to WebSocket:', wsUrl);

                // In Node.js (VS Code desktop extension host), there is no global WebSocket.
                // Use the 'ws' package as the WebSocket implementation.
                let webSocketFactory: (() => any) | undefined;
                if (typeof globalThis.WebSocket === 'undefined') {
                    try {
                        // eslint-disable-next-line @typescript-eslint/no-var-requires
                        const WS = require('ws');
                        webSocketFactory = () => new WS(wsUrl);
                        console.log('[CollaborationManager] Using ws package for Node.js WebSocket');
                    } catch {
                        console.error('[CollaborationManager] No WebSocket implementation available');
                    }
                }

                // Create STOMP client with native WebSocket
                this.client = new Client({
                    // Use webSocketFactory for Node.js, brokerURL for browsers
                    ...(webSocketFactory ? { webSocketFactory } : { brokerURL: wsUrl }),

                    connectHeaders: {
                        // Add authentication headers here if needed
                    },

                    // Disable verbose STOMP debug logging
                    debug: () => { },

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
        this.subscribeToCursors(projectId);

        // Wait a bit for subscriptions to be established
        await new Promise(resolve => setTimeout(resolve, 100));

        // Send USER_JOINED presence (will be broadcast back to us)
        await this.sendPresence(PresenceType.USER_JOINED);

        // Fetch currently active users in this project (requires JWT on production gateway)
        try {
            const headers: Record<string, string> = {};
            if (this.getAuthToken) {
                const token = await Promise.resolve(this.getAuthToken());
                if (token) headers['Authorization'] = `Bearer ${token}`;
            }
            const response = await fetch(`${this.serverUrl}/api/collab-graph/${projectId}/active-users`, { headers });
            if (response.ok) {
                const data = await response.json();
                if (data.users && Array.isArray(data.users)) {
                    // Add existing users to activeUsers map
                    data.users.forEach((user: any) => {
                        // Don't add ourselves (we'll get our own USER_JOINED broadcast)
                        if (user.userId !== this.userId) {
                            this.state.activeUsers.set(user.userId, {
                                userId: user.userId,
                                username: user.username,
                                sessionId: user.sessionId,
                                color: user.color,
                                lastActivity: user.lastActivity,
                                cursorPosition: user.cursorPosition,
                                selectedNodes: user.selectedNodes
                            });
                        }
                    });
                    console.log(`Loaded ${data.users.length - 1} existing active users`);

                    // Notify handler of the initial user list
                    if (this.onPresenceUpdate) {
                        data.users.forEach((user: any) => {
                            if (user.userId !== this.userId) {
                                this.onPresenceUpdate!({
                                    type: PresenceType.USER_ACTIVE,
                                    projectId: projectId,
                                    userId: user.userId,
                                    username: user.username,
                                    sessionId: user.sessionId,
                                    color: user.color,
                                    timestamp: user.lastActivity
                                });
                            }
                        });
                    }
                }
            }
        } catch (error) {
            console.error('Failed to fetch active users:', error);
        }

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
        onCursorUpdate?: (cursor: { userId: string; userName: string; position: { x: number; y: number }; timestamp: number }) => void;
    }): void {
        this.onEditReceived = handlers.onEditReceived;
        this.onPresenceUpdate = handlers.onPresenceUpdate;
        this.onLockUpdate = handlers.onLockUpdate;
        this.onImportStatusUpdate = handlers.onImportStatusUpdate;
        this.onConnectionChange = handlers.onConnectionChange;
        this.onError = handlers.onError;
        this.onShareNotification = handlers.onShareNotification;
        this.onCursorUpdate = handlers.onCursorUpdate;
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
            (message: any) => {
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
            (message: any) => {
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
            (message: any) => {
                try {
                    const lock: LockMessage = JSON.parse(message.body);

                    console.log('Lock update:', lock);

                    // LOCK_DENIED is broadcast to the whole project (same channel as
                    // everything else here) but it's only meaningful to whoever asked —
                    // don't touch shared state, and don't notify anyone else's UI.
                    if (lock.type === LockType.LOCK_DENIED) {
                        if (lock.userId === this.userId && this.onLockUpdate) {
                            this.onLockUpdate(lock);
                        }
                        return;
                    }

                    // Update locks state
                    if (lock.type === LockType.LOCK_ACQUIRED) {
                        this.state.locks.set(lock.nodeId, lock);
                    } else if (
                        lock.type === LockType.LOCK_RELEASED ||
                        lock.type === LockType.LOCK_EXPIRED ||
                        lock.type === LockType.LOCK_FORCE_RELEASE
                    ) {
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
            (message: any) => {
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
     * Subscribe to cursor position updates for a project.
     */
    private subscribeToCursors(projectId: string): void {
        if (!this.client) {
            console.error('[CollaborationManager] ❌ Cannot subscribe to cursors - no client');
            return;
        }

        console.log(`[CollaborationManager] 📡 Subscribing to /topic/cursor/${projectId}`);

        const subscription = this.client.subscribe(
            `/topic/cursor/${projectId}`,
            (message: any) => {
                try {
                    const cursorData = JSON.parse(message.body);

                    // Ignore our own cursor
                    if (cursorData.userId === this.userId) return;

                    console.log('[CollaborationManager] 🖱️  Received cursor update:', cursorData);

                    if (this.onCursorUpdate) {
                        this.onCursorUpdate(cursorData);
                    }
                } catch (error) {
                    console.error('[CollaborationManager] ❌ Error parsing cursor update:', error);
                }
            }
        );

        this.subscriptions.set(`cursor-${projectId}`, subscription);
        console.log(`[CollaborationManager] ✅ Subscribed to cursors for project: ${projectId}`);
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
            (message: any) => {
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
     * Broadcast cursor position to other users in the project
     */
    broadcastCursorPosition(projectId: string, userId: string, userName: string, position: { x: number; y: number }): void {
        if (!this.client || !this.state.connected || !this.state.projectId) {
            console.warn('[CollaborationManager] Cannot broadcast cursor: not connected or no active project');
            return;
        }

        if (projectId !== this.state.projectId) {
            console.warn('[CollaborationManager] Cannot broadcast cursor: project mismatch');
            return;
        }

        // Broadcast cursor via STOMP
        this.client.publish({
            destination: `/app/cursor/${projectId}`,
            body: JSON.stringify({
                userId,
                userName,
                position,
                timestamp: Date.now()
            })
        });
    }
}

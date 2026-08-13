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

export class CollaborationManager implements ICollaborationManager {
    private client: Client | null = null;
    private subscriptions: Map<string, StompSubscription> = new Map();
    private state: CollaborationState;
    private reconnectAttempts = 0;
    private maxReconnectAttempts = 10;
    private reconnectDelay = 1000; // Start with 1 second

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

    connect(): Promise<void> {
        return new Promise((resolve, reject) => {
            try {

                const wsUrl = new URL('/ws/websocket', this.serverUrl).toString().replace(/^http/, 'ws');

                console.log('[CollaborationManager] Connecting to WebSocket:', wsUrl);

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

                this.client = new Client({

                    ...(webSocketFactory ? { webSocketFactory } : { brokerURL: wsUrl }),

                    connectHeaders: {
                        // Add authentication headers here if needed
                    },

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

    async disconnect(): Promise<void> {
        if (this.state.projectId) {

            await this.sendPresence(PresenceType.USER_LEFT);
        }

        this.subscriptions.forEach(sub => sub.unsubscribe());
        this.subscriptions.clear();

        if (this.client) {
            await this.client.deactivate();
            this.client = null;
        }

        this.state.connected = false;
        this.state.projectId = null;
        this.state.activeUsers.clear();
        this.state.locks.clear();
    }

    async joinProject(projectId: string): Promise<void> {
        if (!this.client || !this.state.connected) {
            throw new Error('Not connected to server');
        }

        if (this.state.projectId && this.state.projectId !== projectId) {
            await this.leaveProject();
        }

        this.state.projectId = projectId;

        this.subscribeToEdit(projectId);
        this.subscribeToPresence(projectId);
        this.subscribeToLocks(projectId);
        this.subscribeToImportStatus(projectId);
        this.subscribeToCursors(projectId);

        await new Promise(resolve => setTimeout(resolve, 100));

        await this.sendPresence(PresenceType.USER_JOINED);

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

                    data.users.forEach((user: any) => {

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

    async leaveProject(): Promise<void> {
        if (!this.state.projectId) return;

        await this.sendPresence(PresenceType.USER_LEFT);

        this.subscriptions.forEach(sub => sub.unsubscribe());
        this.subscriptions.clear();

        this.state.projectId = null;
        this.state.activeUsers.clear();
        this.state.locks.clear();

        console.log('Left project');
    }

    async sendEdit(edit: Omit<EditOperation, 'userId' | 'username' | 'timestamp'>): Promise<void> {
        if (!this.client || !this.state.connected) {

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

        this.state.locks.delete(nodeId);
    }

    getState(): Readonly<CollaborationState> {
        return { ...this.state };
    }

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

    isConnected(): boolean {
        return this.state.connected;
    }

    private subscribeToEdit(projectId: string): void {
        if (!this.client) return;

        const subscription = this.client.subscribe(
            `/topic/ontology/${projectId}`,
            (message: any) => {
                try {
                    const edit: EditOperation = JSON.parse(message.body);

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

        this.reconnectDelay = Math.min(this.reconnectDelay * 2, 30000); // Max 30 seconds

        console.log(`Reconnecting in ${this.reconnectDelay}ms (attempt ${this.reconnectAttempts})`);

        setTimeout(() => {
            if (this.client) {
                this.client.activate();
            }
        }, this.reconnectDelay);
    }

    broadcastCursorPosition(projectId: string, userId: string, userName: string, position: { x: number; y: number }): void {
        if (!this.client || !this.state.connected || !this.state.projectId) {
            console.warn('[CollaborationManager] Cannot broadcast cursor: not connected or no active project');
            return;
        }

        if (projectId !== this.state.projectId) {
            console.warn('[CollaborationManager] Cannot broadcast cursor: project mismatch');
            return;
        }

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

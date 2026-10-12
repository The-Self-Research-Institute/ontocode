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
import { backoffDelay, createShareDeduper, stompErrorAction } from './socketPolicy';

const CONNECT_TIMEOUT_MS = 15_000;
const PROJECT_TOPICS = ['edit', 'presence', 'locks', 'import', 'cursor'];
const TOKEN_POLL_MS = 1000;
const TOKEN_POLL_TRIES = 10;
const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

export class CollaborationManager implements ICollaborationManager {
    private client: Client | null = null;
    private subscriptions: Map<string, StompSubscription> = new Map();
    private state: CollaborationState;
    private attempt = 0;
    private everConnected = false;
    private refreshTried = false;
    private outageLogged = false;
    private stopped = false;
    private usedToken: string | null = null;
    private targetProjectId: string | null = null;
    private shareEmail: string | null = null;
    private isNewShare = createShareDeduper();
    private forbiddenWarned = new Set<string>();

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
        const wsUrl = new URL('/ws/websocket', this.serverUrl).toString().replace(/^http/, 'ws');
        let webSocketFactory: (() => any) | undefined;
        if (typeof globalThis.WebSocket === 'undefined') {
            try {
                // eslint-disable-next-line @typescript-eslint/no-var-requires
                const WS = require('ws');
                webSocketFactory = () => new WS(wsUrl);
            } catch {
                return Promise.reject(new Error('No WebSocket implementation available'));
            }
        }
        this.stopped = false;

        return new Promise((resolve, reject) => {
            let settled = false;
            const timer = setTimeout(() => giveUp('CONNECT_TIMEOUT'), CONNECT_TIMEOUT_MS);
            const settle = (error?: Error) => {
                if (settled) {
                    return;
                }
                settled = true;
                clearTimeout(timer);
                if (error) {
                    reject(error);
                } else {
                    resolve();
                }
            };
            const giveUp = (code: string) => {
                this.stop(code);
                settle(new Error(code));
            };

            this.client = new Client({
                ...(webSocketFactory ? { webSocketFactory } : { brokerURL: wsUrl }),
                debug: () => { },
                reconnectDelay: 100,
                connectionTimeout: CONNECT_TIMEOUT_MS,
                heartbeatIncoming: 10_000,
                heartbeatOutgoing: 10_000,
                beforeConnect: async (c) => {
                    if (this.attempt > 0) {
                        await sleep(backoffDelay(this.attempt - 1));
                    }
                    this.attempt++;
                    this.usedToken = await this.readToken();
                    if (!this.usedToken) {
                        giveUp('AUTH_MISSING');
                        return;
                    }
                    c.connectHeaders = { Authorization: `Bearer ${this.usedToken}` };
                },
                onConnect: () => {
                    this.attempt = 0;
                    this.everConnected = true;
                    this.refreshTried = false;
                    this.outageLogged = false;
                    this.state.connected = true;
                    this.subscriptions.clear();
                    this.subscribeToUserQueues();
                    if (this.shareEmail) {
                        this.subscribeToShareNotifications(this.shareEmail);
                    }
                    this.onConnectionChange?.(true);
                    const target = this.targetProjectId;
                    if (target) {
                        this.state.projectId = null;
                        this.joinProject(target)
                            .then(() => this.processPendingEdits())
                            .catch(error => console.error('[CollaborationManager] Rejoin failed:', error));
                    } else {
                        this.processPendingEdits();
                    }
                    settle();
                },
                onStompError: (frame: any) => {
                    const code = frame.headers?.['message'] || '';
                    const action = stompErrorAction(code, this.refreshTried);
                    if (action === 'stop') {
                        giveUp(code);
                    } else if (action === 'refresh') {
                        this.refreshTried = true;
                        void this.retryWithFreshToken().then(ok => {
                            if (!ok) {
                                giveUp(code);
                            }
                        });
                    } else {
                        console.warn('[CollaborationManager] STOMP error:', code);
                    }
                },
                onWebSocketClose: () => {
                    const wasConnected = this.state.connected;
                    this.state.connected = false;
                    if (wasConnected) {
                        this.onConnectionChange?.(false);
                    }
                    if (!this.outageLogged && !this.stopped) {
                        this.outageLogged = true;
                        console.warn('[CollaborationManager] Connection lost, retrying with backoff');
                    }
                }
            });

            this.client.activate();
        });
    }

    private async readToken(): Promise<string | null> {
        return this.getAuthToken ? (await Promise.resolve(this.getAuthToken())) ?? null : null;
    }

    private async retryWithFreshToken(): Promise<boolean> {
        await this.client?.deactivate();
        let fresh = await this.readToken();
        for (let i = 0; i < TOKEN_POLL_TRIES && fresh === this.usedToken && !this.stopped; i++) {
            await sleep(TOKEN_POLL_MS);
            fresh = await this.readToken();
        }
        if (this.stopped) {
            return true;
        }
        if (!fresh || fresh === this.usedToken) {
            return false;
        }
        this.attempt = 0;
        this.client?.activate();
        return true;
    }

    private stop(code: string): void {
        if (this.stopped) {
            return;
        }
        this.stopped = true;
        void this.client?.deactivate();
        this.onError?.(code);
    }

    /**
     * Disconnect from the WebSocket server.
     */
    async disconnect(): Promise<void> {
        this.stopped = true;
        this.targetProjectId = null;
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
        if (this.state.projectId && this.state.projectId !== projectId) {
            await this.leaveProject();
        }
        this.targetProjectId = projectId;
        if (!this.client || !this.state.connected) {
            return;
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

    }

    /**
     * Leave the current project.
     */
    async leaveProject(): Promise<void> {
        this.targetProjectId = null;
        if (!this.state.projectId) return;

        await this.sendPresence(PresenceType.USER_LEFT);

        PROJECT_TOPICS.forEach(key => {
            this.subscriptions.get(key)?.unsubscribe();
            this.subscriptions.delete(key);
        });

        this.state.projectId = null;
        this.targetProjectId = null;
        this.state.activeUsers.clear();
        this.state.locks.clear();
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

    private subscribeToImportStatus(projectId: string): void {
        if (!this.client) {
            return;
        }
        const subscription = this.client.subscribe(`/topic/import/${projectId}`, (message: any) => {
            try {
                this.onImportStatusUpdate?.(JSON.parse(message.body));
            } catch (error) {
                console.error('[CollaborationManager] Error parsing import status:', error);
            }
        });
        this.subscriptions.set('import', subscription);
    }

    private subscribeToCursors(projectId: string): void {
        if (!this.client) {
            return;
        }
        const subscription = this.client.subscribe(`/topic/cursor/${projectId}`, (message: any) => {
            try {
                const cursorData = JSON.parse(message.body);
                if (cursorData.userId !== this.userId) {
                    this.onCursorUpdate?.(cursorData);
                }
            } catch (error) {
                console.error('[CollaborationManager] Error parsing cursor update:', error);
            }
        });
        this.subscriptions.set('cursor', subscription);
    }

    private subscribeToUserQueues(): void {
        if (!this.client) {
            return;
        }
        this.subscriptions.set('userShares', this.client.subscribe('/user/queue/shares', message => this.handleShare(message.body)));
        this.subscriptions.set('errors', this.client.subscribe('/user/queue/errors', message => {
            try {
                const payload = JSON.parse(message.body);
                const dest = String(payload?.destination ?? '');
                if (payload?.code === 'WS_FORBIDDEN' && !this.forbiddenWarned.has(dest)) {
                    this.forbiddenWarned.add(dest);
                    console.warn('[CollaborationManager] Subscription not permitted:', dest);
                }
            } catch {
                return;
            }
        }));
    }

    private handleShare(body: string): void {
        try {
            const notification = JSON.parse(body);
            if (this.isNewShare(notification)) {
                this.onShareNotification?.(notification);
            }
        } catch (error) {
            console.error('[CollaborationManager] Error parsing share notification:', error);
        }
    }

    subscribeToShareNotifications(userEmail: string): void {
        this.shareEmail = userEmail;
        if (!this.client || !this.state.connected) {
            return;
        }
        this.subscriptions.get('shares')?.unsubscribe();
        this.subscriptions.set('shares', this.client.subscribe(`/topic/shares/${userEmail}`, message => this.handleShare(message.body)));
    }

    private processPendingEdits(): void {
        if (this.state.pendingEdits.length === 0) return;

        const edits = [...this.state.pendingEdits];
        this.state.pendingEdits = [];

        edits.forEach(edit => {
            this.sendEdit(edit).catch(error => {
                console.error('Failed to send pending edit:', error);
            });
        });
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

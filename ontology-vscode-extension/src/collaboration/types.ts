/**
 * Types for collaborative editing messages and state.
 */

export enum OperationType {
    // Class operations
    CLASS_ADDED = 'CLASS_ADDED',
    CLASS_MODIFIED = 'CLASS_MODIFIED',
    CLASS_DELETED = 'CLASS_DELETED',
    CLASS_RENAMED = 'CLASS_RENAMED',
    
    // Property operations
    PROPERTY_ADDED = 'PROPERTY_ADDED',
    PROPERTY_MODIFIED = 'PROPERTY_MODIFIED',
    PROPERTY_DELETED = 'PROPERTY_DELETED',
    PROPERTY_RENAMED = 'PROPERTY_RENAMED',
    
    // Individual operations
    INDIVIDUAL_ADDED = 'INDIVIDUAL_ADDED',
    INDIVIDUAL_MODIFIED = 'INDIVIDUAL_MODIFIED',
    INDIVIDUAL_DELETED = 'INDIVIDUAL_DELETED',
    
    // Annotation operations
    ANNOTATION_ADDED = 'ANNOTATION_ADDED',
    ANNOTATION_MODIFIED = 'ANNOTATION_MODIFIED',
    ANNOTATION_DELETED = 'ANNOTATION_DELETED',
    
    // Relationship operations
    SUBCLASS_ADDED = 'SUBCLASS_ADDED',
    SUBCLASS_REMOVED = 'SUBCLASS_REMOVED',
    PROPERTY_DOMAIN_ADDED = 'PROPERTY_DOMAIN_ADDED',
    PROPERTY_DOMAIN_REMOVED = 'PROPERTY_DOMAIN_REMOVED',
    PROPERTY_RANGE_ADDED = 'PROPERTY_RANGE_ADDED',
    PROPERTY_RANGE_REMOVED = 'PROPERTY_RANGE_REMOVED',
    
    // Axiom operations
    AXIOM_ADDED = 'AXIOM_ADDED',
    AXIOM_REMOVED = 'AXIOM_REMOVED',
    
    // Bulk operations
    BULK_IMPORT = 'BULK_IMPORT',
    BULK_DELETE = 'BULK_DELETE'
}

export interface EditOperation {
    type: OperationType;
    projectId: string;
    nodeId: string;
    property?: string;
    value?: any;
    previousValue?: any;
    userId: string;
    username: string;
    sessionId?: string;
    timestamp: number;
    serverTimestamp?: number;
    metadata?: Record<string, any>;
    version?: number;
}

export enum PresenceType {
    USER_JOINED = 'USER_JOINED',
    USER_LEFT = 'USER_LEFT',
    CURSOR_MOVED = 'CURSOR_MOVED',
    SELECTION_CHANGED = 'SELECTION_CHANGED',
    USER_IDLE = 'USER_IDLE',
    USER_ACTIVE = 'USER_ACTIVE'
}

export interface PresenceMessage {
    type: PresenceType;
    projectId: string;
    userId: string;
    username: string;
    sessionId?: string;
    color?: string;
    cursorPosition?: string;
    selectedNodes?: string[];
    timestamp: number;
}

export enum LockType {
    LOCK_ACQUIRED = 'LOCK_ACQUIRED',
    LOCK_RELEASED = 'LOCK_RELEASED',
    LOCK_EXPIRED = 'LOCK_EXPIRED',
    LOCK_DENIED = 'LOCK_DENIED',
    LOCK_REQUEST = 'LOCK_REQUEST',
    LOCK_FORCE_RELEASE = 'LOCK_FORCE_RELEASE'
}

export interface LockMessage {
    type: LockType;
    projectId: string;
    nodeId: string;
    userId: string;
    username: string;
    sessionId?: string;
    expiresAt?: number;
    timestamp: number;
    success?: boolean;
    error?: string;
}

export interface ActiveUser {
    userId: string;
    username: string;
    sessionId: string;
    color: string;
    lastActivity: number;
    cursorPosition?: string;
    selectedNodes?: string[];
}

export interface CollaborationState {
    connected: boolean;
    projectId: string | null;
    activeUsers: Map<string, ActiveUser>;
    locks: Map<string, LockMessage>;
    pendingEdits: EditOperation[];
}

/**
 * Common interface for CollaborationManager implementations
 */
export interface ICollaborationManager {
    connect(): Promise<void>;
    disconnect(): Promise<void>;
    joinProject(projectId: string): Promise<void>;
    leaveProject(): Promise<void>;
    sendEdit(edit: Omit<EditOperation, 'userId' | 'username' | 'timestamp'>): Promise<void>;
    sendPresence(type: PresenceType, data?: Partial<PresenceMessage>): Promise<void>;
    requestLock(nodeId: string): Promise<void>;
    releaseLock(nodeId: string): Promise<void>;
    getState(): Readonly<CollaborationState>;
    setHandlers(handlers: {
        onEditReceived?: (edit: EditOperation) => void;
        onPresenceUpdate?: (presence: PresenceMessage) => void;
        onLockUpdate?: (lock: LockMessage) => void;
        onImportStatusUpdate?: (status: any) => void;
        onConnectionChange?: (connected: boolean) => void;
        onError?: (error: string) => void;
        onShareNotification?: (notification: any) => void;
    }): void;
    isConnected(): boolean;
    subscribeToShareNotifications(userEmail: string): void;
}

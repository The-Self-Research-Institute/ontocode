import { ICollaborationManager, EditOperation, OperationType, PresenceType } from './types';

export class EditCapture {
    private collaborationManager: ICollaborationManager | null = null;
    private debounceTimers: Map<string, NodeJS.Timeout> = new Map();
    private debounceDelay = 500; // 500ms debounce
    private isApplyingRemoteEdit = false;

    constructor() {}

    setCollaborationManager(manager: ICollaborationManager): void {
        this.collaborationManager = manager;
    }

    setApplyingRemoteEdit(applying: boolean): void {
        this.isApplyingRemoteEdit = applying;
    }

    captureClassAdded(projectId: string, classUri: string, className: string, metadata?: Record<string, any>): void {
        if (this.isApplyingRemoteEdit || !this.collaborationManager) return;

        const operation: Omit<EditOperation, 'userId' | 'username' | 'timestamp'> = {
            type: OperationType.CLASS_ADDED,
            projectId,
            nodeId: classUri,
            property: 'name',
            value: className,
            metadata
        };

        this.broadcastWithDebounce(`class_add_${classUri}`, operation);
    }

    captureClassModified(
        projectId: string,
        classUri: string,
        property: string,
        newValue: any,
        previousValue?: any,
        metadata?: Record<string, any>
    ): void {
        if (this.isApplyingRemoteEdit || !this.collaborationManager) return;

        const operation: Omit<EditOperation, 'userId' | 'username' | 'timestamp'> = {
            type: OperationType.CLASS_MODIFIED,
            projectId,
            nodeId: classUri,
            property,
            value: newValue,
            previousValue,
            metadata
        };

        this.broadcastWithDebounce(`class_mod_${classUri}_${property}`, operation);
    }

    captureClassDeleted(projectId: string, classUri: string, metadata?: Record<string, any>): void {
        if (this.isApplyingRemoteEdit || !this.collaborationManager) return;

        const operation: Omit<EditOperation, 'userId' | 'username' | 'timestamp'> = {
            type: OperationType.CLASS_DELETED,
            projectId,
            nodeId: classUri,
            metadata
        };

        this.collaborationManager.sendEdit(operation);
    }

    capturePropertyAdded(
        projectId: string,
        propertyUri: string,
        propertyName: string,
        metadata?: Record<string, any>
    ): void {
        if (this.isApplyingRemoteEdit || !this.collaborationManager) return;

        const operation: Omit<EditOperation, 'userId' | 'username' | 'timestamp'> = {
            type: OperationType.PROPERTY_ADDED,
            projectId,
            nodeId: propertyUri,
            property: 'name',
            value: propertyName,
            metadata
        };

        this.broadcastWithDebounce(`prop_add_${propertyUri}`, operation);
    }

    capturePropertyModified(
        projectId: string,
        propertyUri: string,
        property: string,
        newValue: any,
        previousValue?: any,
        metadata?: Record<string, any>
    ): void {
        if (this.isApplyingRemoteEdit || !this.collaborationManager) return;

        const operation: Omit<EditOperation, 'userId' | 'username' | 'timestamp'> = {
            type: OperationType.PROPERTY_MODIFIED,
            projectId,
            nodeId: propertyUri,
            property,
            value: newValue,
            previousValue,
            metadata
        };

        this.broadcastWithDebounce(`prop_mod_${propertyUri}_${property}`, operation);
    }

    capturePropertyDeleted(projectId: string, propertyUri: string, metadata?: Record<string, any>): void {
        if (this.isApplyingRemoteEdit || !this.collaborationManager) return;

        const operation: Omit<EditOperation, 'userId' | 'username' | 'timestamp'> = {
            type: OperationType.PROPERTY_DELETED,
            projectId,
            nodeId: propertyUri,
            metadata
        };

        this.collaborationManager.sendEdit(operation);
    }

    captureAnnotationChanged(
        projectId: string,
        nodeUri: string,
        annotationType: 'label' | 'comment' | 'other',
        value: string,
        language?: string,
        previousValue?: string
    ): void {
        if (this.isApplyingRemoteEdit || !this.collaborationManager) return;

        const operation: Omit<EditOperation, 'userId' | 'username' | 'timestamp'> = {
            type: previousValue ? OperationType.ANNOTATION_MODIFIED : OperationType.ANNOTATION_ADDED,
            projectId,
            nodeId: nodeUri,
            property: annotationType,
            value,
            previousValue,
            metadata: language ? { language } : undefined
        };

        this.broadcastWithDebounce(`annot_${nodeUri}_${annotationType}`, operation);
    }

    captureSubclassAdded(projectId: string, childUri: string, parentUri: string, metadata?: Record<string, any>): void {
        if (this.isApplyingRemoteEdit || !this.collaborationManager) return;

        const operation: Omit<EditOperation, 'userId' | 'username' | 'timestamp'> = {
            type: OperationType.SUBCLASS_ADDED,
            projectId,
            nodeId: childUri,
            property: 'subClassOf',
            value: parentUri,
            metadata
        };

        this.broadcastWithDebounce(`subclass_add_${childUri}_${parentUri}`, operation);
    }

    captureSubclassRemoved(projectId: string, childUri: string, parentUri: string, metadata?: Record<string, any>): void {
        if (this.isApplyingRemoteEdit || !this.collaborationManager) return;

        const operation: Omit<EditOperation, 'userId' | 'username' | 'timestamp'> = {
            type: OperationType.SUBCLASS_REMOVED,
            projectId,
            nodeId: childUri,
            property: 'subClassOf',
            value: parentUri,
            metadata
        };

        this.collaborationManager.sendEdit(operation);
    }

    captureCursorMoved(projectId: string, nodeUri?: string, selectedNodes?: string[]): void {
        if (!this.collaborationManager) return;

        this.collaborationManager.sendPresence(PresenceType.CURSOR_MOVED, {
            cursorPosition: nodeUri,
            selectedNodes
        });
    }

    private broadcastWithDebounce(
        key: string,
        operation: Omit<EditOperation, 'userId' | 'username' | 'timestamp'>
    ): void {

        const existingTimer = this.debounceTimers.get(key);
        if (existingTimer) {
            clearTimeout(existingTimer);
        }

        const timer = setTimeout(() => {
            if (this.collaborationManager) {
                this.collaborationManager.sendEdit(operation);
            }
            this.debounceTimers.delete(key);
        }, this.debounceDelay);

        this.debounceTimers.set(key, timer);
    }

    flush(): void {
        this.debounceTimers.forEach((timer, key) => {
            clearTimeout(timer);
            this.debounceTimers.delete(key);
        });
    }

    dispose(): void {
        this.flush();
        this.collaborationManager = null;
    }
}

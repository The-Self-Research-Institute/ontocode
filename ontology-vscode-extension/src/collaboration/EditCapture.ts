import { ICollaborationManager, EditOperation, OperationType, PresenceType } from './types';

/**
 * Captures local ontology edits and broadcasts them to other users.
 * Provides debouncing to avoid flooding the server with rapid edits.
 */
export class EditCapture {
    private collaborationManager: ICollaborationManager | null = null;
    private debounceTimers: Map<string, NodeJS.Timeout> = new Map();
    private debounceDelay = 500; // 500ms debounce
    private isApplyingRemoteEdit = false;

    constructor() {}

    /**
     * Set the collaboration manager instance.
     */
    setCollaborationManager(manager: ICollaborationManager): void {
        this.collaborationManager = manager;
    }

    /**
     * Temporarily disable broadcasting (when applying remote edits).
     */
    setApplyingRemoteEdit(applying: boolean): void {
        this.isApplyingRemoteEdit = applying;
    }

    /**
     * Capture and broadcast a class addition.
     */
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

    /**
     * Capture and broadcast a class modification.
     */
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

    /**
     * Capture and broadcast a class deletion.
     */
    captureClassDeleted(projectId: string, classUri: string, metadata?: Record<string, any>): void {
        if (this.isApplyingRemoteEdit || !this.collaborationManager) return;

        const operation: Omit<EditOperation, 'userId' | 'username' | 'timestamp'> = {
            type: OperationType.CLASS_DELETED,
            projectId,
            nodeId: classUri,
            metadata
        };

        // No debounce for deletions - immediate
        this.collaborationManager.sendEdit(operation);
    }

    /**
     * Capture and broadcast a property addition.
     */
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

    /**
     * Capture and broadcast a property modification.
     */
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

    /**
     * Capture and broadcast a property deletion.
     */
    capturePropertyDeleted(projectId: string, propertyUri: string, metadata?: Record<string, any>): void {
        if (this.isApplyingRemoteEdit || !this.collaborationManager) return;

        const operation: Omit<EditOperation, 'userId' | 'username' | 'timestamp'> = {
            type: OperationType.PROPERTY_DELETED,
            projectId,
            nodeId: propertyUri,
            metadata
        };

        // No debounce for deletions - immediate
        this.collaborationManager.sendEdit(operation);
    }

    /**
     * Capture and broadcast an annotation addition/modification.
     */
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

    /**
     * Capture and broadcast a subclass relationship addition.
     */
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

    /**
     * Capture and broadcast a subclass relationship removal.
     */
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

    /**
     * Capture and broadcast cursor/selection changes.
     */
    captureCursorMoved(projectId: string, nodeUri?: string, selectedNodes?: string[]): void {
        if (!this.collaborationManager) return;

        this.collaborationManager.sendPresence(PresenceType.CURSOR_MOVED, {
            cursorPosition: nodeUri,
            selectedNodes
        });
    }

    /**
     * Broadcast with debouncing to avoid flooding the server.
     */
    private broadcastWithDebounce(
        key: string,
        operation: Omit<EditOperation, 'userId' | 'username' | 'timestamp'>
    ): void {
        // Clear existing timer for this operation
        const existingTimer = this.debounceTimers.get(key);
        if (existingTimer) {
            clearTimeout(existingTimer);
        }

        // Set new timer
        const timer = setTimeout(() => {
            if (this.collaborationManager) {
                this.collaborationManager.sendEdit(operation);
            }
            this.debounceTimers.delete(key);
        }, this.debounceDelay);

        this.debounceTimers.set(key, timer);
    }

    /**
     * Flush all pending debounced operations immediately.
     */
    flush(): void {
        this.debounceTimers.forEach((timer, key) => {
            clearTimeout(timer);
            this.debounceTimers.delete(key);
        });
    }

    /**
     * Clean up resources.
     */
    dispose(): void {
        this.flush();
        this.collaborationManager = null;
    }
}

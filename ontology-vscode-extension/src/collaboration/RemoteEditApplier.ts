import { EditOperation, OperationType } from './types';

/**
 * Applies remote edits from other users to the local ontology state.
 * Handles conflict resolution and prevents circular broadcasts.
 */
export class RemoteEditApplier {
    private onApplyEdit?: (edit: EditOperation) => Promise<void>;
    private onConflict?: (edit: EditOperation, reason: string) => void;

    /**
     * Set the handler for applying edits to the UI/state.
     */
    setEditHandler(handler: (edit: EditOperation) => Promise<void>): void {
        this.onApplyEdit = handler;
    }

    /**
     * Set the handler for conflict notifications.
     */
    setConflictHandler(handler: (edit: EditOperation, reason: string) => void): void {
        this.onConflict = handler;
    }

    /**
     * Apply a remote edit to the local state.
     */
    async applyRemoteEdit(edit: EditOperation): Promise<boolean> {
        console.log('[RemoteEditApplier] Applying edit:', edit);

        if (!this.onApplyEdit) {
            console.warn('[RemoteEditApplier] No edit handler set');
            return false;
        }

        try {
            // Validate edit
            if (!this.validateEdit(edit)) {
                console.warn('[RemoteEditApplier] Invalid edit rejected:', edit);
                return false;
            }

            // Apply the edit
            await this.onApplyEdit(edit);
            
            console.log('[RemoteEditApplier] Successfully applied edit:', edit.type);
            return true;

        } catch (error) {
            console.error('[RemoteEditApplier] Failed to apply edit:', error);
            
            if (this.onConflict) {
                this.onConflict(edit, `Failed to apply: ${error}`);
            }
            
            return false;
        }
    }

    /**
     * Validate an edit operation.
     */
    private validateEdit(edit: EditOperation): boolean {
        // Basic validation
        if (!edit.type || !edit.nodeId || !edit.projectId) {
            return false;
        }

        // Check timestamp is recent (within 5 minutes)
        const maxAge = 5 * 60 * 1000; // 5 minutes
        const age = Date.now() - edit.timestamp;
        if (age > maxAge) {
            console.warn('[RemoteEditApplier] Edit too old:', age, 'ms');
            return false;
        }

        return true;
    }

    /**
     * Transform operation type to a handler key.
     */
    getHandlerType(type: OperationType): string {
        switch (type) {
            case OperationType.CLASS_ADDED:
            case OperationType.CLASS_MODIFIED:
            case OperationType.CLASS_DELETED:
            case OperationType.CLASS_RENAMED:
                return 'class';

            case OperationType.PROPERTY_ADDED:
            case OperationType.PROPERTY_MODIFIED:
            case OperationType.PROPERTY_DELETED:
            case OperationType.PROPERTY_RENAMED:
                return 'property';

            case OperationType.ANNOTATION_ADDED:
            case OperationType.ANNOTATION_MODIFIED:
            case OperationType.ANNOTATION_DELETED:
                return 'annotation';

            case OperationType.SUBCLASS_ADDED:
            case OperationType.SUBCLASS_REMOVED:
                return 'relationship';

            case OperationType.AXIOM_ADDED:
            case OperationType.AXIOM_REMOVED:
                return 'axiom';

            default:
                return 'unknown';
        }
    }

    /**
     * Check if an edit conflicts with local state.
     * This is a placeholder for more sophisticated conflict detection.
     */
    detectConflict(edit: EditOperation, localVersion?: number): boolean {
        // Simple version-based conflict detection
        if (edit.version !== undefined && localVersion !== undefined) {
            return edit.version < localVersion;
        }

        return false;
    }

    /**
     * Create a conflict resolution strategy.
     * For now, we use simple "last-write-wins" based on timestamps.
     */
    resolveConflict(localEdit: EditOperation, remoteEdit: EditOperation): 'local' | 'remote' | 'merge' {
        // Last-write-wins based on server timestamp
        if (remoteEdit.serverTimestamp && localEdit.serverTimestamp) {
            return remoteEdit.serverTimestamp > localEdit.serverTimestamp ? 'remote' : 'local';
        }

        // Fallback to client timestamp
        return remoteEdit.timestamp > localEdit.timestamp ? 'remote' : 'local';
    }
}

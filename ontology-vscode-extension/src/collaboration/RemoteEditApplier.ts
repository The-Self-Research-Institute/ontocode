import { EditOperation, OperationType } from './types';

export class RemoteEditApplier {
    private onApplyEdit?: (edit: EditOperation) => Promise<void>;
    private onConflict?: (edit: EditOperation, reason: string) => void;

    setEditHandler(handler: (edit: EditOperation) => Promise<void>): void {
        this.onApplyEdit = handler;
    }

    setConflictHandler(handler: (edit: EditOperation, reason: string) => void): void {
        this.onConflict = handler;
    }

    async applyRemoteEdit(edit: EditOperation): Promise<boolean> {
        console.log('[RemoteEditApplier] Applying edit:', edit);

        if (!this.onApplyEdit) {
            console.warn('[RemoteEditApplier] No edit handler set');
            return false;
        }

        try {

            if (!this.validateEdit(edit)) {
                console.warn('[RemoteEditApplier] Invalid edit rejected:', edit);
                return false;
            }

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

    private validateEdit(edit: EditOperation): boolean {

        if (!edit.type || !edit.nodeId || !edit.projectId) {
            return false;
        }

        const maxAge = 5 * 60 * 1000; // 5 minutes
        const age = Date.now() - edit.timestamp;
        if (age > maxAge) {
            console.warn('[RemoteEditApplier] Edit too old:', age, 'ms');
            return false;
        }

        return true;
    }

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

    detectConflict(edit: EditOperation, localVersion?: number): boolean {

        if (edit.version !== undefined && localVersion !== undefined) {
            return edit.version < localVersion;
        }

        return false;
    }

    resolveConflict(localEdit: EditOperation, remoteEdit: EditOperation): 'local' | 'remote' | 'merge' {

        if (remoteEdit.serverTimestamp && localEdit.serverTimestamp) {
            return remoteEdit.serverTimestamp > localEdit.serverTimestamp ? 'remote' : 'local';
        }

        return remoteEdit.timestamp > localEdit.timestamp ? 'remote' : 'local';
    }
}

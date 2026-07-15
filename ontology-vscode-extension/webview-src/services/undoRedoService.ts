import apiClient from './apiClient';
import changeTrackingService, { type OntologyChange } from './changeTrackingService';

export interface RollbackChangePayload {
  id: string;
  changeType: string;
  action: string;
  entityIRI: string;
  entityLabel?: string;
  oldValue?: string;
  newValue?: string;
}

const redoStack: RollbackChangePayload[] = [];

function mapChangeType(change: OntologyChange): string {
  return change.changeType || change.changeCategory || 'modified';
}

function mapAction(change: OntologyChange): string {
  const t = (change.changeType || '').toLowerCase();
  if (t.includes('create') || t.includes('add') || t.includes('insert')) return 'added';
  if (t.includes('delete') || t.includes('remove')) return 'deleted';
  if (change.oldValue && change.newValue) return 'modified';
  return 'modified';
}

function toPayload(change: OntologyChange): RollbackChangePayload {
  return {
    id: change.id,
    changeType: mapChangeType(change),
    action: mapAction(change),
    entityIRI: change.entityIRI || '',
    entityLabel: change.entityLabel,
    oldValue: change.oldValue,
    newValue: change.newValue,
  };
}

function invertAction(action: string): string {
  const lower = action.toLowerCase();
  if (lower === 'added') return 'deleted';
  if (lower === 'deleted') return 'added';
  return 'modified';
}

async function rollbackPayload(
  projectId: string,
  payload: RollbackChangePayload,
  userId: string,
  username: string,
): Promise<{ success: boolean; error?: string }> {
  const res = await apiClient.post<{ success: boolean; error?: string; message?: string }>(
    `/api/ontology/${encodeURIComponent(projectId)}/changes/rollback`,
    {
      changeId: payload.id,
      changeType: payload.changeType,
      action: payload.action,
      entityIRI: payload.entityIRI,
      entityLabel: payload.entityLabel,
      oldValue: payload.oldValue,
      newValue: payload.newValue,
      userId,
      username,
    },
  );
  const data = (res as { data?: { success: boolean; error?: string } }).data || res;
  return { success: data.success !== false, error: data.error };
}

export const undoRedoService = {
  canRedo(): boolean {
    return redoStack.length > 0;
  },

  clearRedoStack(): void {
    redoStack.length = 0;
  },

  async undo(
    projectId: string,
    userId: string,
    username: string,
  ): Promise<{ success: boolean; error?: string; change?: RollbackChangePayload }> {
    const changes = await changeTrackingService.getRecentChanges(projectId, 50);
    const target = changes.find((c) => !c.reverted && c.entityIRI);
    if (!target) {
      return { success: false, error: 'No changes to undo' };
    }

    const payload = toPayload(target);
    const result = await rollbackPayload(projectId, payload, userId, username);
    if (result.success) {
      redoStack.push(payload);
      window.dispatchEvent(
        new CustomEvent('ontologyRollback', {
          detail: {
            projectId,
            changeId: payload.id,
            entityIRI: payload.entityIRI,
            entityLabel: payload.oldValue || payload.entityLabel,
            originalAction: payload.action,
            username,
          },
        }),
      );
      return { success: true, change: payload };
    }
    return result;
  },

  async redo(
    projectId: string,
    userId: string,
    username: string,
  ): Promise<{ success: boolean; error?: string }> {
    const payload = redoStack.pop();
    if (!payload) {
      return { success: false, error: 'Nothing to redo' };
    }

    const redoPayload: RollbackChangePayload = {
      ...payload,
      action: invertAction(payload.action),
      oldValue: payload.newValue,
      newValue: payload.oldValue,
    };

    const result = await rollbackPayload(projectId, redoPayload, userId, username);
    if (!result.success) {
      redoStack.push(payload);
    } else {
      window.dispatchEvent(
        new CustomEvent('ontologyRollback', {
          detail: {
            projectId,
            changeId: payload.id,
            entityIRI: payload.entityIRI,
            entityLabel: payload.newValue || payload.entityLabel,
            originalAction: redoPayload.action,
            username,
            isRedo: true,
          },
        }),
      );
    }
    return result;
  },
};

export default undoRedoService;

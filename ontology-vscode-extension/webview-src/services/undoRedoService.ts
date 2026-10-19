import { apiErrorDetails } from '../utils/apiErrorDetails';
import apiClient from './apiClient';
import { isDraftScopeActive } from '../utils/draftScope';
import { emitCommittedChange } from '../utils/committedChange';

export interface UndoDependent {
  changeId: string;
  username?: string;
  entityIRI?: string;
  label?: string;
}

export interface UndoStep {
  available: boolean;
  direction: 'UNDO' | 'REDO';
  changeId?: string;
  changeSetId?: string;
  auditId?: string;
  entityIRI?: string;
  label?: string;
  reason?: string;
  dependents: UndoDependent[];
}

export interface UndoState {
  undo: UndoStep;
  redo: UndoStep;
}

export interface UndoRedoResult {
  success: boolean;
  error?: string;
  label?: string;
  entityIRI?: string;
  state?: UndoState;
}

export interface QueueStep {
  changeId: string;
  changeSetId?: string;
  auditId?: string;
  entityIRI?: string;
  label?: string;
  changeCount?: number;
  sourceView?: string;
  available?: boolean;
  source?: string;
  affected?: string;
  restoring?: string;
}

export interface QueueBlock {
  changeId?: string;
  changeSetId?: string;
  label?: string;
  reason?: string;
  dependents?: UndoDependent[];
}

export interface QueueResponse {
  steps: QueueStep[];
  blocked: QueueBlock | null;
}

export type StepOutcome =
  | { kind: 'done'; auditId?: string; state?: UndoState }
  | { kind: 'refused'; message: string; state?: UndoState }
  | { kind: 'running' }
  | { kind: 'unknown'; message: string };

interface ServerBody extends Partial<UndoState> {
  success?: boolean;
  auditId?: string;
  changeSetId?: string;
  error?: string;
  message?: string;
  dependents?: UndoDependent[];
  status?: string;
  inProgress?: boolean;
  steps?: QueueStep[];
  blocked?: QueueBlock | null;
}

function isStillRunning(body: ServerBody, status?: number): boolean {
  return status === 202 || body.inProgress === true || /^(RUNNING|IN_PROGRESS)$/i.test(body.status || '')
    || (status === 409 && /still running/i.test(body.message || body.error || ''));
}

let inFlight = false;

function base(projectId: string): string {
  return `/api/ontology/${encodeURIComponent(projectId)}`;
}

function newOpId(): string {
  const cryptoApi = typeof crypto !== 'undefined' ? crypto : undefined;
  return cryptoApi?.randomUUID ? cryptoApi.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function scopeQuery(): string {
  return `draft=${isDraftScopeActive() ? 'true' : 'false'}&opId=${newOpId()}`;
}

function unwrap(res: unknown): ServerBody {
  const body = res as { data?: ServerBody } & ServerBody;
  return body?.data || body || {};
}

function stateOf(body: ServerBody): UndoState | undefined {
  return body.undo && body.redo ? { undo: body.undo, redo: body.redo } : undefined;
}

function failureText(error: unknown): { text: string; body?: ServerBody } {
  const body = apiErrorDetails<ServerBody>(error).data;
  if (body) {
    const dependents = body.dependents || [];
    const names = Array.from(new Set(dependents.map((d) => d.username).filter(Boolean)));
    const suffix = names.length ? ` (changed by ${names.join(', ')})` : '';
    return { text: `${body.error || body.message || 'The request failed'}${suffix}`, body };
  }
  return { text: error instanceof Error && error.message ? error.message : 'The request failed' };
}

async function guarded(run: () => Promise<UndoRedoResult>): Promise<UndoRedoResult> {
  if (inFlight) return { success: false, error: 'Another undo or redo is still running' };
  inFlight = true;
  try {
    return await run();
  } catch (error) {
    const { text, body } = failureText(error);
    return { success: false, error: text, state: body ? stateOf(body) : undefined };
  } finally {
    inFlight = false;
  }
}

function announce(projectId: string, step: UndoStep, isRedo: boolean, auditId?: string): void {
  emitCommittedChange({
    projectId,
    direction: isRedo ? 'REDO' : 'UNDO',
    opId: auditId,
    auditId,
    changeSetId: step.changeSetId,
    entityIRIs: step.entityIRI ? [step.entityIRI] : [],
    draft: isDraftScopeActive(),
  });
  window.dispatchEvent(
    new CustomEvent('ontologyRollback', {
      detail: {
        projectId,
        changeId: step.changeId,
        entityIRI: step.entityIRI || '',
        entityLabel: step.label,
        username: '',
        ...(isRedo ? { isRedo: true } : {}),
      },
    }),
  );
}

async function perform(projectId: string, step: UndoStep, path: string, guard: string): Promise<UndoRedoResult> {
  const body = unwrap(await apiClient.post<ServerBody>(`${base(projectId)}/${path}?${scopeQuery()}&${guard}`, {}));
  if (body.success === false) {
    return { success: false, error: body.error || body.message || 'The request failed', state: stateOf(body) };
  }
  announce(projectId, step, path === 'redo', body.auditId);
  return { success: true, label: step.label, entityIRI: step.entityIRI, state: stateOf(body) };
}

function toStep(step: QueueStep, isRedo: boolean): UndoStep {
  return {
    available: true,
    direction: isRedo ? 'REDO' : 'UNDO',
    changeId: step.changeId,
    changeSetId: step.changeSetId,
    auditId: step.auditId,
    entityIRI: step.entityIRI,
    label: step.label,
    dependents: [],
  };
}

export const undoRedoService = {
  async fetchQueue(projectId: string, count: number): Promise<QueueResponse | null> {
    const draft = isDraftScopeActive() ? 'true' : 'false';
    try {
      const body = unwrap(await apiClient.get<ServerBody>(`${base(projectId)}/undo/queue?draft=${draft}&count=${count}`));
      return { steps: body.steps || [], blocked: body.blocked || null };
    } catch (error) {
      if (apiErrorDetails(error).status === 404) {
        return null;
      }
      throw error;
    }
  },

  async sendStep(
    projectId: string,
    kind: 'undo' | 'redo',
    expected: { changeId?: string; auditId?: string },
    opId: string,
  ): Promise<StepOutcome> {
    const guard =
      kind === 'undo'
        ? expected.changeId ? `&expectedChangeId=${encodeURIComponent(expected.changeId)}` : ''
        : expected.auditId ? `&expectedAuditId=${encodeURIComponent(expected.auditId)}` : '';
    const draft = isDraftScopeActive() ? 'true' : 'false';
    try {
      const res = (await apiClient.post<ServerBody>(
        `${base(projectId)}/${kind}?draft=${draft}&opId=${encodeURIComponent(opId)}${guard}`,
        {},
      )) as { status?: number } & ServerBody;
      const body = unwrap(res);
      if (isStillRunning(body, res?.status)) return { kind: 'running' };
      if (body.success === false) {
        return { kind: 'refused', message: body.error || body.message || 'The request failed', state: stateOf(body) };
      }
      return { kind: 'done', auditId: body.auditId, state: stateOf(body) };
    } catch (error) {
      const { status } = apiErrorDetails(error);
      const { text, body } = failureText(error);
      if (body && isStillRunning(body, status)) return { kind: 'running' };
      if (!body || (status !== undefined && status >= 500)) return { kind: 'unknown', message: text };
      return { kind: 'refused', message: text, state: stateOf(body) };
    }
  },

  announceStep(projectId: string, step: QueueStep, kind: 'undo' | 'redo', auditId?: string): void {
    announce(projectId, toStep(step, kind === 'redo'), kind === 'redo', auditId);
  },

  notifyRefusal(projectId: string): void {
    window.dispatchEvent(new CustomEvent('ontologyChangesSaved', { detail: { projectId } }));
  },

  isBusy(): boolean {
    return inFlight;
  },

  async fetchState(projectId: string): Promise<UndoState | undefined> {
    try {
      return stateOf(unwrap(await apiClient.get<ServerBody>(`${base(projectId)}/undo/next?${scopeQuery()}`)));
    } catch {
      return undefined;
    }
  },

  undo(projectId: string, step: UndoStep | undefined): Promise<UndoRedoResult> {
    return guarded(async () => {
      const target = step?.available && step.changeId ? step : undefined;
      if (!target) return this.requestUndo(projectId);
      return perform(projectId, target, 'undo', `expectedChangeId=${encodeURIComponent(target.changeId || '')}`);
    });
  },

  redo(projectId: string, step: UndoStep | undefined): Promise<UndoRedoResult> {
    return guarded(async () => {
      const target = step?.available && step.auditId ? step : undefined;
      if (!target) return this.requestRedo(projectId);
      return perform(projectId, target, 'redo', `expectedAuditId=${encodeURIComponent(target.auditId || '')}`);
    });
  },

  async requestUndo(projectId: string): Promise<UndoRedoResult> {
    const body = unwrap(await apiClient.post<ServerBody>(`${base(projectId)}/undo?${scopeQuery()}`, {}));
    return this.fromBody(projectId, body, 'undo');
  },

  async requestRedo(projectId: string): Promise<UndoRedoResult> {
    const body = unwrap(await apiClient.post<ServerBody>(`${base(projectId)}/redo?${scopeQuery()}`, {}));
    return this.fromBody(projectId, body, 'redo');
  },

  fromBody(projectId: string, body: ServerBody, kind: 'undo' | 'redo'): UndoRedoResult {
    if (body.success === false) {
      return { success: false, error: body.error || body.message || 'The request failed', state: stateOf(body) };
    }
    announce(projectId, { available: true, direction: kind === 'undo' ? 'UNDO' : 'REDO', dependents: [] }, kind === 'redo', body.auditId);
    return { success: true, state: stateOf(body) };
  },
};

export default undoRedoService;

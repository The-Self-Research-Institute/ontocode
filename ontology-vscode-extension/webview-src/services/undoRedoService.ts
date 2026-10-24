import { apiErrorDetails } from '../utils/apiErrorDetails';
import apiClient from './apiClient';
import { isDraftScopeActive } from '../utils/draftScope';
import { emitCommittedChange } from '../utils/committedChange';
import { newOpId } from '../utils/opId';

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
  | { kind: 'refused'; message: string; state?: UndoState; othersOnTop?: OtherChange[]; revertPr?: RevertPrHint; foreignTarget?: ForeignTarget }
  | { kind: 'running' }
  | { kind: 'unknown'; message: string };

export interface ForeignTarget {
  username?: string;
  label?: string;
  changes?: number;
}

export interface RevertPrHint {
  prId?: string;
  title?: string;
  mode?: 'revert' | 'reapply';
}

export interface OtherChange {
  changeId?: string;
  changeSetId?: string;
  username?: string;
  label?: string;
}

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
  othersOnTop?: OtherChange[];
  revertPr?: RevertPrHint;
  foreignTarget?: ForeignTarget;
}

function isStillRunning(body: ServerBody, status?: number): boolean {
  return status === 202 || body.inProgress === true || /^(RUNNING|IN_PROGRESS)$/i.test(body.status || '')
    || (status === 409 && /still running/i.test(body.message || body.error || ''));
}

function base(projectId: string): string {
  return `/api/ontology/${encodeURIComponent(projectId)}`;
}

function draftFlag(): string {
  return isDraftScopeActive() ? 'true' : 'false';
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
  async fetchQueue(projectId: string, count: number): Promise<QueueResponse> {
    const body = unwrap(await apiClient.get<ServerBody>(`${base(projectId)}/undo/queue?draft=${draftFlag()}&count=${count}`));
    return { steps: body.steps || [], blocked: body.blocked || null };
  },

  async sendStep(
    projectId: string,
    kind: 'undo' | 'redo',
    expected: { changeId?: string; auditId?: string },
    opId: string,
    confirmOther?: boolean | 'target',
  ): Promise<StepOutcome> {
    const guard =
      kind === 'undo'
        ? expected.changeId ? `&expectedChangeId=${encodeURIComponent(expected.changeId)}` : ''
        : expected.auditId ? `&expectedAuditId=${encodeURIComponent(expected.auditId)}` : '';
    try {
      const res = (await apiClient.post<ServerBody>(
        `${base(projectId)}/${kind}?draft=${draftFlag()}&opId=${encodeURIComponent(opId)}${guard}${confirmOther === 'target' ? '&confirmTarget=true' : confirmOther ? '&confirmOther=true' : ''}`,
        {},
      )) as { status?: number } & ServerBody;
      const body = unwrap(res);
      if (isStillRunning(body, res?.status)) return { kind: 'running' };
      if (body.success === false) {
        return { kind: 'refused', message: body.error || body.message || 'The request failed', state: stateOf(body), othersOnTop: body.othersOnTop, revertPr: body.revertPr, foreignTarget: body.foreignTarget };
      }
      return { kind: 'done', auditId: body.auditId, state: stateOf(body) };
    } catch (error) {
      const { status } = apiErrorDetails(error);
      const { text, body } = failureText(error);
      if (body && isStillRunning(body, status)) return { kind: 'running' };
      if (!body || (status !== undefined && status >= 500)) return { kind: 'unknown', message: text };
      return { kind: 'refused', message: text, state: stateOf(body), othersOnTop: body.othersOnTop, revertPr: body.revertPr, foreignTarget: body.foreignTarget };
    }
  },

  announceStep(projectId: string, step: QueueStep, kind: 'undo' | 'redo', auditId?: string): void {
    announce(projectId, toStep(step, kind === 'redo'), kind === 'redo', auditId);
  },

  notifyRefusal(projectId: string): void {
    window.dispatchEvent(new CustomEvent('ontologyChangesSaved', { detail: { projectId } }));
  },

  async fetchState(projectId: string): Promise<UndoState | undefined> {
    try {
      return stateOf(unwrap(await apiClient.get<ServerBody>(`${base(projectId)}/undo/next?draft=${draftFlag()}&opId=${newOpId()}`)));
    } catch {
      return undefined;
    }
  },
};

export default undoRedoService;

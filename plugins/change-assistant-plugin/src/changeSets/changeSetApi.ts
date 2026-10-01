import { apiBase, authFetch, currentActor } from '../authFetch';
import { OperationOutcome, OperationResponse, OperationTarget } from './types';

function operationPath(target: OperationTarget): string {
  if (target.kind === 'set') {
    const verb = target.direction === 'REDO' ? 'redo' : 'undo';
    return `change-sets/${encodeURIComponent(target.changeSetId)}/${verb}`;
  }
  if (target.kind === 'undo') {
    return `undos/${encodeURIComponent(target.auditId)}/redo`;
  }
  if (target.kind === 'property') {
    return `changes/${encodeURIComponent(target.changeId)}/subchanges/${encodeURIComponent(target.subChangeId)}/rollback`;
  }
  return 'changes/rollback';
}

function operationBody(target: OperationTarget): string {
  const actor = currentActor();
  if (target.kind === 'entry') return JSON.stringify({ changeId: target.changeId, ...actor });
  return JSON.stringify(actor);
}

function normalize(raw: any): OperationResponse | null {
  if (!raw || typeof raw !== 'object') return null;
  return {
    ...raw,
    success: !!raw.success,
    applied: Array.isArray(raw.applied) ? raw.applied : [],
    skipped: Array.isArray(raw.skipped) ? raw.skipped : []
  };
}

export async function runOperation(projectId: string, target: OperationTarget, dryRun: boolean): Promise<OperationOutcome> {
  const url = `${apiBase()}/api/ontology/${projectId}/${operationPath(target)}?dryRun=${dryRun}`;
  try {
    const response = await authFetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: operationBody(target)
    });
    const raw = await response.json().catch(() => null);
    return { status: response.status, data: normalize(raw) };
  } catch {
    return { status: 0, data: null };
  }
}

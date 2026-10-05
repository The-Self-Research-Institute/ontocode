import apiClient, { ApiError } from './apiClient';

export type ChangeSetDirection = 'UNDO' | 'REDO';

export interface ChangeSetItem {
  changeId: string;
  subChangeId?: string | null;
  entityIRI?: string | null;
  entityLabel?: string | null;
  predicate?: string | null;
  reason?: string | null;
}

export interface ChangeSetResult {
  success: boolean;
  alreadyReverted: boolean;
  dryRun: boolean;
  direction: ChangeSetDirection;
  applied: ChangeSetItem[];
  skipped: ChangeSetItem[];
  auditId?: string | null;
  message?: string | null;
  error?: string | null;
  status?: number;
}

export interface ChangeSetActor {
  userId: string;
  username: string;
}

function normalize(data: Partial<ChangeSetResult> | undefined, direction: ChangeSetDirection, dryRun: boolean): ChangeSetResult {
  return {
    success: data?.success !== false,
    alreadyReverted: data?.alreadyReverted === true,
    dryRun: data?.dryRun ?? dryRun,
    direction: data?.direction ?? direction,
    applied: Array.isArray(data?.applied) ? data!.applied : [],
    skipped: Array.isArray(data?.skipped) ? data!.skipped : [],
    auditId: data?.auditId ?? null,
    message: data?.message ?? null,
    error: data?.error ?? null,
  };
}

function failure(error: unknown, direction: ChangeSetDirection, dryRun: boolean): ChangeSetResult {
  const status = error instanceof ApiError ? error.status : undefined;
  const data = error instanceof ApiError && error.data && typeof error.data === 'object' ? error.data : undefined;
  const result = normalize(data, direction, dryRun);
  return { ...result, success: false, status, error: result.error || 'Request failed' };
}

export async function requestChangeSet(
  projectId: string,
  changeSetId: string,
  direction: ChangeSetDirection,
  dryRun: boolean,
  actor: ChangeSetActor,
): Promise<ChangeSetResult> {
  const action = direction === 'UNDO' ? 'undo' : 'redo';
  const url =
    `/api/ontology/${encodeURIComponent(projectId)}/change-sets/${encodeURIComponent(changeSetId)}/${action}` +
    `?dryRun=${dryRun ? 'true' : 'false'}`;
  try {
    const response = await apiClient.post(url, { userId: actor.userId, username: actor.username });
    const data = response?.data || response;
    return { ...normalize(data, direction, dryRun), status: 200 };
  } catch (error) {
    console.error(`[changeSetService] ${action} failed:`, error);
    return failure(error, direction, dryRun);
  }
}

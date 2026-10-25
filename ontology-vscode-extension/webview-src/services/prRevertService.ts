import apiClient from './apiClient';

export interface RevertOutcome {
  ok: boolean;
  message: string;
  changeSets: any[];
}

export interface RevertActor {
  userId: string;
  username: string;
}

export interface RevertSummary {
  changes: Array<{ label: string; count: number }>;
  blocked: string[];
  alreadyUndone: number;
}

export function summarizeRevert(changeSets: any[]): RevertSummary {
  const counts = new Map<string, number>();
  const blocked: string[] = [];
  let alreadyUndone = 0;
  for (const set of changeSets) {
    if (set.alreadyUndone) {
      alreadyUndone += 1;
    } else if (set.canRevert === false) {
      const reasons: string[] = set.reasons && set.reasons.length ? set.reasons : ['unknown reason'];
      reasons.forEach((reason) => !blocked.includes(reason) && blocked.push(reason));
    } else {
      for (const item of set.items || []) {
        const label = String(item.label || 'Change');
        counts.set(label, (counts.get(label) || 0) + 1);
      }
    }
  }
  return { changes: Array.from(counts, ([label, count]) => ({ label, count })), blocked, alreadyUndone };
}

export function failureOf(e: any): RevertOutcome {
  const data = e?.data || e?.response?.data;
  return { ok: false, message: data?.error || data?.message || e?.message || 'Request failed', changeSets: data?.changeSets || [] };
}

export type RevertMode = 'revert' | 'reapply';

export async function previewRevert(projectId: string, prId: string, actor: RevertActor, mode: RevertMode = 'revert'): Promise<RevertOutcome> {
  try {
    const res = await apiClient.post<any>(`/api/ontology/${projectId}/draft-prs/${prId}/${mode}?dryRun=true`, { userId: actor.userId, username: actor.username });
    return { ok: true, message: res?.message || 'Preview ready', changeSets: res?.changeSets || [] };
  } catch (e: any) {
    return failureOf(e);
  }
}

export async function confirmRevert(projectId: string, prId: string, reason: string, actor: RevertActor, mode: RevertMode = 'revert'): Promise<RevertOutcome> {
  try {
    const res = await apiClient.post<any>(`/api/ontology/${projectId}/draft-prs/${prId}/${mode}`, { userId: actor.userId, username: actor.username, reason: reason.trim() });
    return { ok: true, message: res?.message || (mode === 'reapply' ? 'Applied again' : 'Reverted'), changeSets: res?.changeSets || [] };
  } catch (e: any) {
    return failureOf(e);
  }
}

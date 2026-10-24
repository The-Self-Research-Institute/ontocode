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

export function failureOf(e: any): RevertOutcome {
  const data = e?.data || e?.response?.data;
  return { ok: false, message: data?.error || data?.message || e?.message || 'Request failed', changeSets: data?.changeSets || [] };
}

export async function previewRevert(projectId: string, prId: string, actor: RevertActor): Promise<RevertOutcome> {
  try {
    const res = await apiClient.post<any>(`/api/ontology/${projectId}/draft-prs/${prId}/revert?dryRun=true`, { userId: actor.userId, username: actor.username });
    return { ok: true, message: res?.message || 'Preview ready', changeSets: res?.changeSets || [] };
  } catch (e: any) {
    return failureOf(e);
  }
}

export async function confirmRevert(projectId: string, prId: string, reason: string, actor: RevertActor): Promise<RevertOutcome> {
  try {
    const res = await apiClient.post<any>(`/api/ontology/${projectId}/draft-prs/${prId}/revert`, { userId: actor.userId, username: actor.username, reason: reason.trim() });
    return { ok: true, message: res?.message || 'Reverted', changeSets: res?.changeSets || [] };
  } catch (e: any) {
    return failureOf(e);
  }
}

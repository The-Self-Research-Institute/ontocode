import { apiBase, authFetch } from '../authFetch';

function changeUrl(projectId: string, changeId: string, action: string): string {
  return `${apiBase()}/api/ontology/${projectId}/changes/${changeId}/${action}`;
}

function postJson(url: string, body: object): Promise<Response> {
  return authFetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

export async function fetchDraftList(projectId: string, userId: string): Promise<any[] | null> {
  const base = apiBase();
  const query = `?userId=${encodeURIComponent(userId)}`;
  const response = await authFetch(`${base}/api/ontology/${projectId}/drafts/stats${query}`);
  if (!response.ok) return null;
  const draftsResponse = await authFetch(`${base}/api/ontology/${projectId}/drafts${query}`);
  if (!draftsResponse.ok) return null;
  const draftsData = await draftsResponse.json();
  if (!draftsData.drafts || !Array.isArray(draftsData.drafts)) return null;
  return draftsData.drafts;
}

export async function fetchDraftSessionActive(projectId: string, userId: string): Promise<boolean> {
  const response = await authFetch(
    `${apiBase()}/api/ontology/${projectId}/draft/copy/status?userId=${encodeURIComponent(userId)}`,
  );
  if (!response.ok) return false;
  const data = await response.json();
  return data.status === 'READY' || data.status === 'COPYING';
}

export async function fetchRecentChanges(projectId: string): Promise<any> {
  const response = await authFetch(`${apiBase()}/api/ontology/${projectId}/changes/recent?count=100`);
  return response.json();
}

export async function fetchChangeDetails(projectId: string, changeId: string): Promise<any> {
  const response = await authFetch(changeUrl(projectId, changeId, 'details'));
  return response.json();
}

export function postComment(projectId: string, changeId: string, body: object): Promise<Response> {
  return postJson(changeUrl(projectId, changeId, 'comments'), body);
}

export function postConflictResolution(projectId: string, changeId: string, resolution: string): Promise<Response> {
  return postJson(changeUrl(projectId, changeId, 'resolve-conflict'), { resolution });
}

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

export async function fetchDraftList(projectId: string): Promise<any[] | null> {
  const base = apiBase();
  console.log('[ChangeAssistant] Loading drafts for projectId:', projectId);
  console.log('[ChangeAssistant] API_BASE_URL:', base);
  const response = await authFetch(`${base}/api/ontology/${projectId}/drafts/stats`);
  console.log('[ChangeAssistant] Draft stats response status:', response.status);
  if (!response.ok) return null;
  const data = await response.json();
  console.log('[ChangeAssistant] Draft stats:', data);
  const draftsResponse = await authFetch(`${base}/api/ontology/${projectId}/drafts`);
  console.log('[ChangeAssistant] Drafts response status:', draftsResponse.status);
  if (!draftsResponse.ok) return null;
  const draftsData = await draftsResponse.json();
  console.log('[ChangeAssistant] Drafts data:', draftsData);
  if (!draftsData.drafts || !Array.isArray(draftsData.drafts)) return null;
  console.log('[ChangeAssistant] Found', draftsData.drafts.length, 'drafts');
  return draftsData.drafts;
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

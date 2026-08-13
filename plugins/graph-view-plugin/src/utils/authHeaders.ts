
export function authHeaders(token?: string | null): Record<string, string> {
  const resolved = token !== undefined ? token : localStorage.getItem('authToken');
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (resolved && resolved !== 'null' && resolved !== 'undefined') {
    headers['Authorization'] = `Bearer ${resolved}`;
  }
  return headers;
}

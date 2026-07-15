/**
 * Authorization header helper. Never emits "Bearer null" — desktop runs a
 * permit-all local backend with no login, so localStorage has no authToken
 * and a templated `Bearer ${token}` header would fail JWT parsing server-side.
 */
export function authHeaders(token?: string | null): Record<string, string> {
  const resolved = token !== undefined ? token : localStorage.getItem('authToken');
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (resolved && resolved !== 'null' && resolved !== 'undefined') {
    headers['Authorization'] = `Bearer ${resolved}`;
  }
  return headers;
}

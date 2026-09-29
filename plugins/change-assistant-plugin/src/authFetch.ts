export async function authFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const hostFetch = (window as any).authenticatedFetch;
  if (typeof hostFetch === 'function') {
    return hostFetch(input, init);
  }
  const headers = new Headers(init?.headers);
  const token = localStorage.getItem('authToken');
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (init?.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  return fetch(input, { ...init, headers });
}

export function apiBase(): string {
  return (window as any).API_BASE_URL || 'http://localhost:8082';
}

export function currentActor(): { userId: string; username: string } {
  let stored: any = {};
  try {
    stored = JSON.parse(localStorage.getItem('user') || '{}');
  } catch {
    stored = {};
  }
  const user = (window as any).vscodeUser || stored;
  return { userId: user?.email || user?.id || 'anonymous', username: user?.username || 'Anonymous' };
}

import { isDesktop } from './desktop';

/** Headers with Bearer token when logged in (cloud web only). */
export function getAuthHeaders(extra?: HeadersInit): Headers {
  const headers = new Headers(extra);
  if (!isDesktop()) {
    const token = localStorage.getItem('authToken');
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
  }
  return headers;
}

/** fetch() that attaches JWT — use in plugins and any code outside apiClient. */
export async function authenticatedFetch(
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> {
  const headers = getAuthHeaders(init?.headers);
  if (
    init?.body &&
    !(init.body instanceof FormData) &&
    !headers.has('Content-Type')
  ) {
    headers.set('Content-Type', 'application/json');
  }
  return fetch(input, { ...init, headers });
}

if (typeof window !== 'undefined') {
  (window as any).authenticatedFetch = authenticatedFetch;
  (window as any).getAuthHeaders = getAuthHeaders;
}

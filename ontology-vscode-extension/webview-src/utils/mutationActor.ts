import { isDesktop } from './desktop';

export const DESKTOP_USER_ID = 'desktop-user-local';

export type MutationActor = { userId: string; username: string };

/** Decode user id from JWT for draft graph scope (web/cloud). */
export function userIdFromAuthToken(): string | null {
  try {
    const token = localStorage.getItem('authToken');
    if (!token) return null;
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
    return payload.userId || payload.id || payload.sub || payload.email || null;
  } catch {
    return null;
  }
}

/**
 * Resolve user id for draft graph writes/reads — must match backend SparqlQueryContext.
 */
export function resolveMutationActor(userId?: string, username?: string): MutationActor {
  if (isDesktop()) {
    return { userId: DESKTOP_USER_ID, username: username || 'Desktop User' };
  }
  const jwtUserId = userIdFromAuthToken();
  if (jwtUserId) {
    return { userId: jwtUserId, username: username || 'Anonymous' };
  }
  if (userId && userId !== 'anonymous') {
    return { userId, username: username || 'Anonymous' };
  }
  return { userId: userId || 'anonymous', username: username || 'Anonymous' };
}

/** For API headers and save/draft endpoints. */
export function resolveMutationUserId(explicitUserId?: string): string {
  return resolveMutationActor(explicitUserId).userId;
}

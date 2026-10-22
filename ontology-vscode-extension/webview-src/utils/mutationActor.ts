import { isDesktop } from './desktop';

export const DESKTOP_USER_ID = 'desktop-user-local';

export type MutationActor = { userId: string; username: string };

function authTokenPayload(): Record<string, unknown> | null {
  try {
    const token = localStorage.getItem('authToken');
    if (!token) return null;
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    return JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
  } catch {
    return null;
  }
}

export function userIdFromAuthToken(): string | null {
  const payload = authTokenPayload();
  return (payload && ((payload.userId || payload.id || payload.sub || payload.email) as string)) || null;
}

function emailFromAuthToken(): string | null {
  const email = authTokenPayload()?.email;
  return typeof email === 'string' && email ? email : null;
}

export function resolveMutationActor(userId?: string, username?: string): MutationActor {
  if (isDesktop()) {
    return { userId: DESKTOP_USER_ID, username: username || 'Desktop User' };
  }
  const jwtUserId = userIdFromAuthToken();
  if (jwtUserId) {
    return { userId: jwtUserId, username: username || emailFromAuthToken() || 'Anonymous' };
  }
  if (userId && userId !== 'anonymous') {
    return { userId, username: username || 'Anonymous' };
  }
  return { userId: userId || 'anonymous', username: username || 'Anonymous' };
}

export function readerUserId(user?: { userId?: string; email?: string } | null): string | undefined {
  const id = resolveMutationActor(user?.userId || user?.email).userId;
  return id && id !== 'anonymous' ? id : undefined;
}

export function resolveMutationUserId(explicitUserId?: string): string {
  return resolveMutationActor(explicitUserId).userId;
}

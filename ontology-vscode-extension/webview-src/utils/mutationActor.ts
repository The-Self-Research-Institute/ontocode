import { isDesktop } from './desktop';

export const DESKTOP_USER_ID = 'desktop-user-local';

export class MissingUserIdError extends Error {
  constructor() {
    super('The signed-in session has no user id');
    this.name = 'MissingUserIdError';
  }
}

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
  const id = payload && (payload.userId || payload.id);
  return typeof id === 'string' && id ? id : null;
}

function hasAuthToken(): boolean {
  try {
    return !!localStorage.getItem('authToken');
  } catch {
    return false;
  }
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
  if (hasAuthToken()) {
    throw new MissingUserIdError();
  }
  return { userId: 'anonymous', username: username || 'Anonymous' };
}

export function readerUserId(user?: { userId?: string } | null): string | undefined {
  try {
    const id = resolveMutationActor(user?.userId).userId;
    return id && id !== 'anonymous' ? id : undefined;
  } catch {
    return undefined;
  }
}

export function resolveMutationUserId(explicitUserId?: string): string {
  return resolveMutationActor(explicitUserId).userId;
}

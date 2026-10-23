import { resolveMutationActor } from './mutationActor';

export const USER_SCOPED_PREFIX = 'ontocode.u.';

export function storageOwnerId(): string {
  try {
    return resolveMutationActor().userId;
  } catch {
    return 'anonymous';
  }
}

export function userScopedKey(baseKey: string, ownerId: string = storageOwnerId()): string {
  return `${USER_SCOPED_PREFIX}${encodeURIComponent(ownerId)}.${baseKey}`;
}

export function clearUserScopedStorage(storage: Storage): void {
  Object.keys(storage)
    .filter((key) => key.startsWith(USER_SCOPED_PREFIX))
    .forEach((key) => storage.removeItem(key));
}

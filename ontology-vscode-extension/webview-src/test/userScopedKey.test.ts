import { afterEach, describe, expect, it } from 'vitest';
import { USER_SCOPED_PREFIX, clearUserScopedStorage, userScopedKey } from '../utils/userScopedKey';
import { clearSessionCache } from '../utils/sessionCleanup';
import { chatHistoryStorageKey } from '../components/codeAssistantPanelHelpers';

function signIn(userId: string) {
  const payload = btoa(JSON.stringify({ userId })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  localStorage.setItem('authToken', `h.${payload}.s`);
}

describe('userScopedKey', () => {
  afterEach(() => localStorage.clear());

  it('gives two users different keys for the same project', () => {
    signIn('alice');
    const alice = userScopedKey('draft_p1');
    signIn('bob');
    const bob = userScopedKey('draft_p1');
    expect(alice).not.toBe(bob);
    expect(alice.startsWith(USER_SCOPED_PREFIX)).toBe(true);
  });

  it('uses the shared key for ai chat history', () => {
    signIn('alice');
    expect(chatHistoryStorageKey('p1')).toBe(userScopedKey('ontocode.askAi.chat.p1'));
  });

  it('is removed on logout cleanup', () => {
    signIn('alice');
    localStorage.setItem(userScopedKey('ontocode_sync_mode_p1'), 'private');
    localStorage.setItem(userScopedKey('draft_p1'), '{}');
    localStorage.setItem('theme', 'dark');
    clearSessionCache();
    expect(Object.keys(localStorage).some((k) => k.startsWith(USER_SCOPED_PREFIX))).toBe(false);
    expect(localStorage.getItem('theme')).toBe('dark');
  });

  it('clears only scoped keys from a storage', () => {
    localStorage.setItem(`${USER_SCOPED_PREFIX}x.a`, '1');
    localStorage.setItem('keep', '1');
    clearUserScopedStorage(localStorage);
    expect(localStorage.getItem('keep')).toBe('1');
    expect(localStorage.getItem(`${USER_SCOPED_PREFIX}x.a`)).toBeNull();
  });
});

import { afterEach, describe, expect, it } from 'vitest';
import { resolveMutationActor } from '../utils/mutationActor';

function signIn(claims: Record<string, string>) {
  const payload = btoa(JSON.stringify(claims)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  localStorage.setItem('authToken', `h.${payload}.s`);
}

describe('resolveMutationActor', () => {
  afterEach(() => localStorage.clear());

  it('names an edit after the signed-in email when no username is given', () => {
    signIn({ userId: 'mongo-1', email: 'real@x.org' });
    expect(resolveMutationActor()).toEqual({ userId: 'mongo-1', username: 'real@x.org' });
  });

  it('keeps a username that was given', () => {
    signIn({ userId: 'mongo-1', email: 'real@x.org' });
    expect(resolveMutationActor(undefined, 'Sam Lee').username).toBe('Sam Lee');
  });

  it('falls back to Anonymous only when nobody is signed in', () => {
    expect(resolveMutationActor().username).toBe('Anonymous');
  });
});

import { readerUserId } from '../utils/mutationActor';

describe('readerUserId', () => {
  afterEach(() => localStorage.clear());

  it('reads under the same id the draft was written under, not the email', () => {
    signIn({ userId: 'mongo-1', email: 'real@x.org' });
    expect(readerUserId({ userId: 'mongo-1', email: 'real@x.org' })).toBe('mongo-1');
    expect(readerUserId({ email: 'real@x.org' })).toBe('mongo-1');
  });

  it('has no id when nobody is signed in', () => {
    expect(readerUserId(null)).toBeUndefined();
  });
});

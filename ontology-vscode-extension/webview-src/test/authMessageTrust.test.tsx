import React, { act, useContext } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthContext, AuthProvider } from '../contexts/AuthContexts';

vi.mock('../services/apiClient', () => ({
  default: {
    setUnauthorizedCallback: () => {},
    setMaintenanceCallback: () => {},
    get: () => Promise.resolve({}),
    post: () => Promise.resolve({}),
  },
}));

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function makeToken(): string {
  const encode = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${encode({ alg: 'none' })}.${encode({ sub: 'u1', userId: 'u1', username: 'ann', exp: Math.floor(Date.now() / 1000) + 3600 })}.sig`;
}

let container: HTMLDivElement;
let root: Root;
let currentUser: unknown;

function Probe() {
  const ctx = useContext(AuthContext);
  currentUser = ctx?.user;
  return null;
}

beforeEach(() => {
  vi.spyOn(console, 'log').mockImplementation(() => {});
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  localStorage.clear();
  currentUser = undefined;
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.restoreAllMocks();
});

describe('AuthProvider message handling', () => {
  it('does not log in from a token delivered by a message event in the browser build', async () => {
    await act(async () => root.render(<AuthProvider><Probe /></AuthProvider>));
    await act(async () => {
      window.dispatchEvent(
        new MessageEvent('message', {
          data: { type: 'storedAuthToken', token: makeToken() },
          source: window,
          origin: window.location.origin,
        }),
      );
    });
    expect(currentUser).toBeNull();
    expect(localStorage.getItem('authToken')).toBeNull();
  });

  it('does not log in from a token sent by another window', async () => {
    await act(async () => root.render(<AuthProvider><Probe /></AuthProvider>));
    await act(async () => {
      window.dispatchEvent(
        new MessageEvent('message', {
          data: { type: 'storedAuthToken', token: makeToken() },
          origin: 'https://evil.example',
        }),
      );
    });
    expect(currentUser).toBeNull();
  });
});

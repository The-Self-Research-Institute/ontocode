import { afterEach, describe, expect, it } from 'vitest';
import { hasHostChannel, isTrustedHostMessage, isTrustedMessage } from '../utils/trustedMessage';

const win = window as any;
const originalVscode = win.vscode;
const originalParent = Object.getOwnPropertyDescriptor(window, 'parent');

function setParent(value: unknown) {
  Object.defineProperty(window, 'parent', { value, configurable: true });
}

function messageFrom(source: unknown, origin: string): MessageEvent {
  return { source, origin, data: {} } as unknown as MessageEvent;
}

afterEach(() => {
  if (originalParent) Object.defineProperty(window, 'parent', originalParent);
  win.vscode = originalVscode;
  delete win.__ONTOCODE_BROWSER_BRIDGE__;
});

describe('isTrustedMessage', () => {
  it('rejects a message from another window in a plain browser tab', () => {
    const attacker = {};
    expect(isTrustedMessage(messageFrom(attacker, 'https://evil.example'))).toBe(false);
  });

  it('accepts a message this window sent to itself', () => {
    expect(isTrustedMessage(messageFrom(window, window.location.origin))).toBe(true);
  });

  it('rejects a self message with a foreign origin', () => {
    expect(isTrustedMessage(messageFrom(window, 'https://evil.example'))).toBe(false);
  });

  it('rejects a message with no source', () => {
    expect(isTrustedMessage(messageFrom(null, ''))).toBe(false);
  });

  it('accepts the parent frame when embedded in a host', () => {
    const host = {};
    setParent(host);
    expect(isTrustedMessage(messageFrom(host, 'vscode-webview://x'))).toBe(true);
    expect(isTrustedMessage(messageFrom({}, 'vscode-webview://x'))).toBe(false);
  });
});

describe('isTrustedHostMessage', () => {
  it('never accepts a token channel in the browser build', () => {
    win.vscode = { postMessage() {} };
    win.__ONTOCODE_BROWSER_BRIDGE__ = true;
    expect(hasHostChannel()).toBe(false);
    expect(isTrustedHostMessage(messageFrom(window, window.location.origin))).toBe(false);
  });

  it('accepts only the parent frame inside the VS Code webview', () => {
    const host = {};
    setParent(host);
    win.vscode = { postMessage() {} };
    expect(hasHostChannel()).toBe(true);
    expect(isTrustedHostMessage(messageFrom(host, 'vscode-webview://x'))).toBe(true);
    expect(isTrustedHostMessage(messageFrom(window, window.location.origin))).toBe(false);
  });
});

import { isTrustedAppMessage } from '../utils/trustedMessage';

describe('isTrustedAppMessage', () => {
  it('holds the token message to the stricter host check', () => {
    const tokenMessage = { source: window, origin: window.location.origin, data: { type: 'storedAuthToken', token: 't' } } as unknown as MessageEvent;
    const otherMessage = { source: window, origin: window.location.origin, data: { type: 'fileReady' } } as unknown as MessageEvent;
    expect(isTrustedAppMessage(tokenMessage)).toBe(false);
    expect(isTrustedAppMessage(otherMessage)).toBe(true);
  });
});

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
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

beforeEach(() => {
  win.vscode = undefined;
});

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

  it('rejects a message with no source outside a host', () => {
    expect(isTrustedMessage(messageFrom(null, ''))).toBe(false);
  });

  it('accepts a message from the host inside the VS Code webview but not one from a web page', () => {
    win.vscode = { postMessage() {} };
    expect(isTrustedMessage(messageFrom({}, 'vscode-webview://x'))).toBe(true);
    expect(isTrustedMessage(messageFrom(null, ''))).toBe(true);
    expect(isTrustedMessage(messageFrom({}, 'https://evil.example'))).toBe(false);
  });

  it('accepts a message with no source inside a VS Code webview, where the host delivers over a message port', () => {
    win.vscode = { postMessage() {} };
    expect(isTrustedMessage(messageFrom(null, ''))).toBe(true);
  });

  it('accepts the parent frame when embedded in a host', () => {
    const host = {};
    setParent(host);
    win.vscode = undefined;
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

  it('accepts the host channel inside the VS Code webview and nothing outside it', () => {
    win.vscode = { postMessage() {} };
    expect(hasHostChannel()).toBe(true);
    expect(isTrustedHostMessage(messageFrom({}, 'vscode-webview://x'))).toBe(true);
    win.vscode = undefined;
    expect(hasHostChannel()).toBe(false);
    expect(isTrustedHostMessage(messageFrom(window, window.location.origin))).toBe(false);
  });
});

import { isTrustedAppMessage } from '../utils/trustedMessage';

describe('isTrustedAppMessage', () => {
  it('holds the token message to the stricter host check', () => {
    win.vscode = undefined;
    const tokenMessage = { source: window, origin: window.location.origin, data: { type: 'storedAuthToken', token: 't' } } as unknown as MessageEvent;
    const otherMessage = { source: window, origin: window.location.origin, data: { type: 'fileReady' } } as unknown as MessageEvent;
    expect(isTrustedAppMessage(tokenMessage)).toBe(false);
    expect(isTrustedAppMessage(otherMessage)).toBe(true);
  });
});

import { describe, it, expect } from 'vitest';
import { buildWebviewCsp, buildConnectSources } from '../../src/utils/webviewCsp';

const csp = buildWebviewCsp({
  cspSource: 'vscode-resource:',
  nonce: 'abc',
  serviceUrls: ['https://gw.example.org', 'http://localhost:8083'],
});

describe('buildWebviewCsp', () => {
  it('drops eval, remote script hosts and wildcards', () => {
    expect(csp).not.toContain('unsafe-eval');
    expect(csp).not.toContain('unpkg.com');
    expect(csp).not.toContain('cdn.tailwindcss.com');
    expect(csp).not.toContain('aistudiocdn.com');
    expect(csp).not.toContain(':*');
    expect(csp).not.toMatch(/connect-src[^;]*\s(https:|wss:|ws:)(\s|;|$)/);
  });

  it('limits scripts to nonce, extension and stripe', () => {
    const script = csp.split('; ').find((d) => d.startsWith('script-src'))!;
    expect(script).toBe("script-src 'nonce-abc' vscode-resource: https://js.stripe.com");
  });

  it('connects only to configured origins and their sockets', () => {
    const sources = buildConnectSources(['https://gw.example.org/x', 'not a url']);
    expect(sources).toContain('https://gw.example.org');
    expect(sources).toContain('wss://gw.example.org');
    expect(sources).not.toContain('https://evil.com');
    expect(csp).toContain('ws://localhost:8083');
  });
});

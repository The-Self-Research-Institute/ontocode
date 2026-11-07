import { describe, it, expect } from 'vitest';
import {
  PickedFileRegistry,
  isGatewayApiPath,
  isPublicGatewayPath,
  isSafeExternalUrl,
  isValidBaseUrl,
  resolveAllowedUrl,
  resolveGatewayApiUrl,
} from '../../src/utils/urlGuard';

const GATEWAY = 'https://gw.example.org';
const BASES = [GATEWAY, 'https://editor.example.org', 'https://plugin.example.org:8087'];

describe('urlGuard', () => {
  it('allows only http, https and mailto for external urls', () => {
    expect(isSafeExternalUrl('https://a.org/x')).toBe(true);
    expect(isSafeExternalUrl('mailto:a@b.org')).toBe(true);
    expect(isSafeExternalUrl('javascript:alert(1)')).toBe(false);
    expect(isSafeExternalUrl('file:///etc/passwd')).toBe(false);
    expect(isSafeExternalUrl('vscode://x/y')).toBe(false);
    expect(isSafeExternalUrl(undefined)).toBe(false);
  });

  it('accepts only plain api paths', () => {
    expect(isGatewayApiPath('/api/projects/1')).toBe(true);
    expect(isGatewayApiPath('/api/../admin')).toBe(false);
    expect(isGatewayApiPath('/api/%2e%2e/admin')).toBe(false);
    expect(isGatewayApiPath('//evil.com/api/x')).toBe(false);
    expect(isGatewayApiPath('https://evil.com/api/x')).toBe(false);
    expect(isGatewayApiPath('/other/api/x')).toBe(false);
    expect(isGatewayApiPath('/api/x\\..\\y')).toBe(false);
  });

  it('accepts the plugin-service reasoner path the gateway routes', () => {
    expect(isGatewayApiPath('/plugin-service/api/reasoner/p1/classify')).toBe(true);
    expect(isGatewayApiPath('/plugin-service/api/reasoner/p1/classify/status/t1?x=1')).toBe(true);
    expect(isGatewayApiPath('/plugin-service/api/../admin')).toBe(false);
    expect(isGatewayApiPath('/plugin-service/api/%2e%2e/admin')).toBe(false);
    expect(isGatewayApiPath('/plugin-service/other')).toBe(false);
    expect(isGatewayApiPath('/plugin-service/api/x\\y')).toBe(false);
    expect(resolveGatewayApiUrl('/plugin-service/api/reasoner/p1/stop', GATEWAY)).toBe(
      'https://gw.example.org/plugin-service/api/reasoner/p1/stop',
    );
  });

  it('resolves api paths against the gateway only', () => {
    expect(resolveGatewayApiUrl('/api/a?b=1', GATEWAY)).toBe('https://gw.example.org/api/a?b=1');
    expect(resolveGatewayApiUrl('/api/a', 'https://gw.example.org/')).toBe('https://gw.example.org/api/a');
    expect(resolveGatewayApiUrl('https://evil.com/api/a', GATEWAY)).toBeNull();
  });

  it('does not treat login lookalikes as public endpoints', () => {
    expect(isPublicGatewayPath('/api/auth/login')).toBe(true);
    expect(isPublicGatewayPath('/api/invitations/details/abc')).toBe(true);
    expect(isPublicGatewayPath('/api/projects/x?next=/api/auth/login')).toBe(false);
    expect(isPublicGatewayPath('/api/users/api/auth/login')).toBe(false);
  });

  it('only forwards to the configured services', () => {
    expect(resolveAllowedUrl('/api/x', GATEWAY, BASES)).toBe('https://gw.example.org/api/x');
    expect(resolveAllowedUrl('https://editor.example.org/api/y', GATEWAY, BASES)).toBe('https://editor.example.org/api/y');
    expect(resolveAllowedUrl('https://plugin.example.org:8087/z', GATEWAY, BASES)).toBe('https://plugin.example.org:8087/z');
    expect(resolveAllowedUrl('https://evil.com/steal', GATEWAY, BASES)).toBeNull();
    expect(resolveAllowedUrl('//evil.com/steal', GATEWAY, BASES)).toBeNull();
    expect(resolveAllowedUrl('https://gw.example.org.evil.com/x', GATEWAY, BASES)).toBeNull();
    expect(resolveAllowedUrl('https://gw.example.org@evil.com/x', GATEWAY, BASES)).toBeNull();
    expect(resolveAllowedUrl('file:///etc/passwd', GATEWAY, BASES)).toBeNull();
  });

  it('validates base urls', () => {
    expect(isValidBaseUrl('https://a.org')).toBe(true);
    expect(isValidBaseUrl('ftp://a.org')).toBe(false);
    expect(isValidBaseUrl('https://user:pw@a.org')).toBe(false);
    expect(isValidBaseUrl('nonsense')).toBe(false);
  });

  it('tracks picked files', () => {
    const registry = new PickedFileRegistry();
    registry.add('file:///a.owl');
    expect(registry.has('file:///a.owl')).toBe(true);
    expect(registry.has('file:///b.owl')).toBe(false);
    registry.clear();
    expect(registry.has('file:///a.owl')).toBe(false);
  });
});

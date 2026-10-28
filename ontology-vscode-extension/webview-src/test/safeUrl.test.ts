import { describe, it, expect } from 'vitest';
import { isSafeUrl, safeHref } from '../utils/safeUrl';
import { safeHref as pluginSafeHref } from '../../../plugins/graph-view-plugin/src/utils/safeUrl';

describe.each([
  ['app', safeHref],
  ['graph plugin', pluginSafeHref],
])('safeHref (%s)', (_name, fn) => {
  it('keeps http, https and mailto links', () => {
    expect(fn('https://example.org/a?b=1')).toBe('https://example.org/a?b=1');
    expect(fn('http://example.org')).toBe('http://example.org');
    expect(fn('mailto:a@b.org')).toBe('mailto:a@b.org');
  });

  it('neutralises script and other schemes', () => {
    expect(fn('javascript:alert(1)')).toBe('#');
    expect(fn(' JaVaScRiPt:alert(1)')).toBe('#');
    expect(fn('java\nscript:alert(1)')).toBe('#');
    expect(fn('data:text/html,<script>1</script>')).toBe('#');
    expect(fn('vbscript:x')).toBe('#');
    expect(fn('/relative')).toBe('#');
    expect(fn(undefined)).toBe('#');
    expect(fn(null)).toBe('#');
  });
});

describe('isSafeUrl', () => {
  it('rejects non strings', () => {
    expect(isSafeUrl(5)).toBe(false);
  });
});

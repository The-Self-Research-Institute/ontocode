import { describe, it, expect, vi } from 'vitest';
import { postToSelf } from '../utils/selfMessage';
import { isTrustedMessage } from '../utils/trustedMessage';

describe('postToSelf', () => {
  it('delivers a message the trust check accepts', () => {
    const seen: boolean[] = [];
    const listener = (event: MessageEvent) => seen.push(isTrustedMessage(event));
    window.addEventListener('message', listener);
    postToSelf({ type: 'ping' });
    window.removeEventListener('message', listener);
    expect(seen).toEqual([true]);
  });

  it('carries the data through unchanged', () => {
    const handler = vi.fn();
    window.addEventListener('message', handler);
    postToSelf({ type: 'uploadProgress', percent: 40 });
    window.removeEventListener('message', handler);
    expect(handler.mock.calls[0][0].data).toEqual({ type: 'uploadProgress', percent: 40 });
  });
});

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

if (typeof window === 'undefined') {
  (globalThis as any).window = new EventTarget();
  (globalThis as any).localStorage = { getItem: () => null };
}

const { default: apiClient } = await import('../src/apiClient');

function answer(status: number, body: unknown) {
  vi.stubGlobal('fetch', vi.fn(async () => ({
    ok: false,
    status,
    headers: { get: () => null },
    text: async () => JSON.stringify(body),
  })));
}

describe('rule saves while public editing is off', () => {
  const heard = vi.fn();

  beforeEach(() => {
    heard.mockReset();
    window.addEventListener('ontocode:public-edit-disabled', heard);
  });

  afterEach(() => {
    window.removeEventListener('ontocode:public-edit-disabled', heard);
    vi.unstubAllGlobals();
  });

  it('keeps the status and body on the error and tells the page once', async () => {
    answer(403, { code: 'PUBLIC_EDIT_DISABLED', error: 'The project owner turned off public editing.' });
    const error: any = await apiClient.post('/api/ontology/p/rules?draft=false', {}).catch((e: unknown) => e);
    expect(error.status).toBe(403);
    expect(error.data.code).toBe('PUBLIC_EDIT_DISABLED');
    expect(heard).toHaveBeenCalledTimes(1);
  });

  it('stays quiet for other refusals', async () => {
    answer(403, { viewOnly: true, error: 'View only' });
    await apiClient.post('/api/ontology/p/rules?draft=false', {}).catch(() => undefined);
    expect(heard).not.toHaveBeenCalled();
  });
});

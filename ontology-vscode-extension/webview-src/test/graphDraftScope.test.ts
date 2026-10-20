import { afterEach, describe, expect, it, vi } from 'vitest';
import { createGraphDataFetchService } from '../../../plugins/graph-view-plugin/src/services/GraphDataFetchService';

function requestedUrls(draftMode: boolean): Promise<string[]> {
  const urls: string[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    urls.push(url);
    return { ok: true, status: 200, json: async () => ({ data: [] }), text: async () => '[]' };
  }));
  const service = createGraphDataFetchService('http://api', 'proj', 'token', draftMode);
  return service.fetchGraphData().catch(() => undefined).then(() => urls);
}

describe('graph data requests', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('ask for the draft version while the user is in draft mode', async () => {
    const urls = await requestedUrls(true);
    expect(urls.length).toBeGreaterThan(0);
    expect(urls.every((url) => url.includes('draft=true'))).toBe(true);
  });

  it('ask for the public version otherwise', async () => {
    const urls = await requestedUrls(false);
    expect(urls.length).toBeGreaterThan(0);
    expect(urls.some((url) => url.includes('draft=true'))).toBe(false);
  });
});

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

if (typeof window === 'undefined') {
  (globalThis as any).window = new EventTarget();
  (globalThis as any).localStorage = { getItem: () => null };
}

const { default: apiClient } = await import('../src/apiClient');

type Reply = { status: number; body: unknown };

function serve(replies: Reply[]) {
  const calls: { method: string; url: string; body?: any }[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: any) => {
    calls.push({ method: init.method, url, body: init.body ? JSON.parse(init.body) : undefined });
    const reply = replies.length > 1 ? replies.shift()! : replies[0];
    return {
      ok: reply.status < 400,
      status: reply.status,
      headers: { get: () => null },
      text: async () => JSON.stringify(reply.body),
    };
  }));
  return calls;
}

const customerFact = {
  axiomType: 'ClassAssertion' as const,
  subjectIri: 'http://example.org/p#Customer2',
  predicateIri: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type',
  objectIri: 'http://example.org/p#Person',
};

const finished = {
  jobId: 'j1',
  projectId: 'p1',
  status: 'COMPLETED',
  explainStatus: 'EXPLAINED',
  justification: [{ axiomType: 'ClassAssertion', role: 'ASSERTED', text: 'Customer2 Type Customer' }],
};

describe('explaining an inferred fact', () => {
  beforeEach(() => {
    vi.useRealTimers();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('submits the fact to the project explain route', async () => {
    const calls = serve([{ status: 202, body: { success: true, async: true, jobId: 'j1', status: 'QUEUED', queuePosition: 1, estimatedWaitTimeMs: 0 } }]);

    const submitted = await apiClient.submitExplainInference('p1', customerFact);

    expect(submitted.jobId).toBe('j1');
    expect(calls).toHaveLength(1);
    expect(calls[0].method).toBe('POST');
    expect(calls[0].url).toBe('/api/ontology/p1/explain-inference');
    expect(calls[0].body.axiomType).toBe('ClassAssertion');
    expect(calls[0].body.subjectIri).toBe(customerFact.subjectIri);
  });

  it('asks for the draft copy when the draft flag is on', async () => {
    const calls = serve([{ status: 202, body: { success: true, jobId: 'j1' } }]);

    await apiClient.submitExplainInference('p1', customerFact, true);

    expect(calls[0].url).toBe('/api/ontology/p1/explain-inference?draft=true');
  });

  it('reads one job back by its id and escapes the id', async () => {
    const calls = serve([{ status: 200, body: finished }]);

    const job = await apiClient.getExplainInference('p 1', 'j/1');

    expect(job.explainStatus).toBe('EXPLAINED');
    expect(calls[0].method).toBe('GET');
    expect(calls[0].url).toBe('/api/ontology/p%201/explain-inference/j%2F1');
  });

  it('cancels a job with a delete', async () => {
    const calls = serve([{ status: 200, body: { success: true, cancelled: true } }]);

    const result = await apiClient.cancelExplainInference('p1', 'j1');

    expect(result.cancelled).toBe(true);
    expect(calls[0].method).toBe('DELETE');
    expect(calls[0].url).toBe('/api/ontology/p1/explain-inference/j1');
  });

  it('submits then polls until the job is finished and reports each update', async () => {
    const calls = serve([
      { status: 202, body: { success: true, async: true, jobId: 'j1', status: 'QUEUED' } },
      { status: 200, body: { jobId: 'j1', projectId: 'p1', status: 'PROCESSING' } },
      { status: 200, body: finished },
    ]);
    const seen: string[] = [];

    const job = await apiClient.explainInference('p1', customerFact, {
      pollIntervalMs: 1,
      onUpdate: (update) => seen.push(update.status),
    });

    expect(job.status).toBe('COMPLETED');
    expect(job.justification).toHaveLength(1);
    expect(seen).toEqual(['PROCESSING', 'COMPLETED']);
    expect(calls.map((call) => call.method)).toEqual(['POST', 'GET', 'GET']);
  });

  it('returns a failed job as it is so the caller can show its message', async () => {
    serve([
      { status: 202, body: { success: true, jobId: 'j1', status: 'QUEUED' } },
      { status: 200, body: { jobId: 'j1', projectId: 'p1', status: 'FAILED', success: false, error: 'The reasoner ran out of memory.' } },
    ]);

    const job = await apiClient.explainInference('p1', customerFact, { pollIntervalMs: 1 });

    expect(job.status).toBe('FAILED');
    expect(job.error).toBe('The reasoner ran out of memory.');
  });

  it('stops and cancels the job when the caller aborts', async () => {
    const calls = serve([
      { status: 202, body: { success: true, jobId: 'j1', status: 'QUEUED' } },
      { status: 200, body: { success: true, cancelled: true } },
    ]);
    const controller = new AbortController();
    controller.abort();

    await expect(apiClient.explainInference('p1', customerFact, { signal: controller.signal, pollIntervalMs: 1 }))
      .rejects.toMatchObject({ name: 'AbortError' });

    expect(calls.map((call) => call.method)).toEqual(['POST', 'DELETE']);
  });

  it('gives up with a plain message when the job never finishes', async () => {
    const calls = serve([
      { status: 202, body: { success: true, jobId: 'j1', status: 'QUEUED' } },
      { status: 200, body: { jobId: 'j1', projectId: 'p1', status: 'PROCESSING' } },
    ]);

    await expect(apiClient.explainInference('p1', customerFact, { pollIntervalMs: 5, timeoutMs: 20 }))
      .rejects.toThrow('taking too long');

    expect(calls[calls.length - 1].method).toBe('DELETE');
  });

  it('lets a busy answer through with its retry hint', async () => {
    serve([{ status: 429, body: { busy: true, reason: 'OWNER_LIMIT', retryAfterSeconds: 5, message: 'You already have 2 explanations running.' } }]);

    await expect(apiClient.submitExplainInference('p1', customerFact)).rejects.toMatchObject({
      status: 429,
      message: 'You already have 2 explanations running.',
      data: { busy: true, reason: 'OWNER_LIMIT', retryAfterSeconds: 5 },
    });
  });
});

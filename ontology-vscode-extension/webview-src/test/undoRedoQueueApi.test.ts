import { beforeEach, describe, expect, it, vi } from 'vitest';

const post = vi.fn();
const get = vi.fn();

vi.mock('../services/apiClient', () => ({
  default: { post: (...args: unknown[]) => post(...args), get: (...args: unknown[]) => get(...args) },
}));
vi.mock('../utils/draftScope', () => ({ isDraftScopeActive: () => true }));

import undoRedoService from '../services/undoRedoService';

const httpError = (status: number, data?: unknown) => Object.assign(new Error('x'), { status, data });

describe('undo queue api', () => {
  beforeEach(() => {
    post.mockReset();
    get.mockReset();
  });

  it('asks for the next steps in the active scope', async () => {
    get.mockResolvedValue({ steps: [{ changeId: 'a' }], blocked: null });
    const result = await undoRedoService.fetchQueue('p', 3);
    expect(get.mock.calls[0][0]).toBe('/api/ontology/p/undo/queue?draft=true&count=3');
    expect(result?.steps[0].changeId).toBe('a');
  });

  it('lets a failed queue request reach the caller', async () => {
    get.mockRejectedValueOnce(httpError(500));
    await expect(undoRedoService.fetchQueue('p', 1)).rejects.toBeTruthy();
  });

  it('sends one step with its own operation id and expected change', async () => {
    post.mockResolvedValue({ success: true, auditId: 'au' });
    const outcome = await undoRedoService.sendStep('p', 'undo', { changeId: 'a b' }, 'op1');
    expect(post.mock.calls[0][0]).toBe('/api/ontology/p/undo?draft=true&opId=op1&expectedChangeId=a%20b');
    expect(outcome).toEqual({ kind: 'done', auditId: 'au', state: undefined });
  });

  it('treats the server answer for a repeated id that is still running as running, not as a conflict', async () => {
    post.mockRejectedValueOnce(httpError(409, { success: false, error: 'This request is still running', message: 'This request is still running' }));
    expect(await undoRedoService.sendStep('p', 'undo', { changeId: 'a' }, 'o')).toEqual({ kind: 'running' });
    post.mockRejectedValueOnce(httpError(409, { success: false, error: 'Change domain of hasParent was changed since this edit' }));
    expect(await undoRedoService.sendStep('p', 'undo', { changeId: 'a' }, 'o')).toMatchObject({ kind: 'refused' });
  });

  it('classifies refusals, running and unknown results', async () => {
    post.mockRejectedValueOnce(httpError(409, { success: false, error: 'Nope' }));
    expect(await undoRedoService.sendStep('p', 'undo', { changeId: 'a' }, 'o')).toMatchObject({ kind: 'refused', message: 'Nope' });
    post.mockResolvedValueOnce({ success: false, error: 'Bad' });
    expect(await undoRedoService.sendStep('p', 'undo', { changeId: 'a' }, 'o')).toMatchObject({ kind: 'refused' });
    post.mockResolvedValueOnce({ status: 'RUNNING' });
    expect(await undoRedoService.sendStep('p', 'undo', { changeId: 'a' }, 'o')).toEqual({ kind: 'running' });
    post.mockRejectedValueOnce(httpError(500, { error: 'boom' }));
    expect(await undoRedoService.sendStep('p', 'undo', { changeId: 'a' }, 'o')).toMatchObject({ kind: 'unknown' });
    post.mockRejectedValueOnce(new Error('Network Error'));
    expect(await undoRedoService.sendStep('p', 'redo', { auditId: 'x' }, 'o')).toMatchObject({ kind: 'unknown' });
    expect(post.mock.calls[4][0]).toContain('expectedAuditId=x');
  });

  it('tells the change feed to reload after a refusal', () => {
    const seen = vi.fn();
    window.addEventListener('ontologyChangesSaved', seen);
    undoRedoService.notifyRefusal('p');
    window.removeEventListener('ontologyChangesSaved', seen);
    expect((seen.mock.calls[0][0] as CustomEvent).detail).toEqual({ projectId: 'p' });
  });
});

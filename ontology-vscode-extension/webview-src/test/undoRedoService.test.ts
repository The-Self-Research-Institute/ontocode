import { beforeEach, describe, expect, it, vi } from 'vitest';

const post = vi.fn();
const get = vi.fn();
let draft = false;

vi.mock('../services/apiClient', () => ({
  default: { post: (...args: unknown[]) => post(...args), get: (...args: unknown[]) => get(...args) },
}));
vi.mock('../utils/draftScope', () => ({ isDraftScopeActive: () => draft }));

import undoRedoService, { type UndoState, type UndoStep } from '../services/undoRedoService';

const step = (overrides: Partial<UndoStep> = {}): UndoStep => ({
  available: true,
  direction: 'UNDO',
  changeId: 'c1',
  entityIRI: 'http://x/A',
  label: 'A',
  dependents: [],
  ...overrides,
});

const state = (undo: Partial<UndoStep> = {}, redo: Partial<UndoStep> = {}): UndoState => ({
  undo: step(undo),
  redo: step({ available: false, direction: 'REDO', changeId: undefined, ...redo }),
});

describe('undoRedoService', () => {
  beforeEach(() => {
    post.mockReset();
    get.mockReset();
    draft = false;
  });

  it('asks the server for the undo state in the active scope', async () => {
    get.mockResolvedValue(state());
    draft = true;
    const result = await undoRedoService.fetchState('p');
    expect(get.mock.calls[0][0]).toMatch(/^\/api\/ontology\/p\/undo\/next\?draft=true/);
    expect(result?.undo.changeId).toBe('c1');
  });

  it('returns nothing when the state request fails', async () => {
    get.mockRejectedValue(new Error('down'));
    expect(await undoRedoService.fetchState('p')).toBeUndefined();
  });

  it('undoes the shown step and guards against a changed history', async () => {
    post.mockResolvedValue({ success: true, ...state({ changeId: 'c0' }) });
    const result = await undoRedoService.undo('p', step());
    expect(post.mock.calls[0][0]).toMatch(/^\/api\/ontology\/p\/undo\?draft=false&opId=[^&]+&expectedChangeId=c1$/);
    expect(result.success).toBe(true);
    expect(result.label).toBe('A');
    expect(result.state?.undo.changeId).toBe('c0');
  });

  it('redoes by audit id', async () => {
    post.mockResolvedValue({ success: true, ...state() });
    const result = await undoRedoService.redo('p', step({ direction: 'REDO', auditId: 'au1' }));
    expect(post.mock.calls[0][0]).toMatch(/^\/api\/ontology\/p\/redo\?draft=false&opId=[^&]+&expectedAuditId=au1$/);
    expect(result.success).toBe(true);
  });

  it('asks the server to pick when no step is cached', async () => {
    post.mockResolvedValue({ success: true, ...state() });
    const result = await undoRedoService.undo('p', undefined);
    expect(post.mock.calls[0][0]).toMatch(/^\/api\/ontology\/p\/undo\?draft=false&opId=[^&]+$/);
    expect(result.success).toBe(true);
  });

  it('reports a refusal with the people who changed it since', async () => {
    post.mockRejectedValue({
      response: {
        data: {
          success: false,
          error: 'Others have changed A since',
          dependents: [{ changeId: 'o', username: 'sam@x.com' }],
          ...state({ available: false }),
        },
      },
    });
    const result = await undoRedoService.undo('p', step());
    expect(result.success).toBe(false);
    expect(result.error).toBe('Others have changed A since (changed by sam@x.com)');
    expect(result.state?.undo.available).toBe(false);
  });

  it('reports a plain failure without throwing', async () => {
    post.mockRejectedValue(new Error('boom'));
    const result = await undoRedoService.undo('p', step());
    expect(result).toEqual({ success: false, error: 'boom', state: undefined });
    expect(undoRedoService.isBusy()).toBe(false);
  });

  it('allows only one request at a time', async () => {
    let release: (value: unknown) => void = () => {};
    post.mockReturnValue(new Promise((resolve) => (release = resolve)));
    const first = undoRedoService.undo('p', step());
    const second = await undoRedoService.undo('p', step());
    expect(second.success).toBe(false);
    expect(second.error).toMatch(/still running/);
    release({ success: true, ...state() });
    expect((await first).success).toBe(true);
  });
});

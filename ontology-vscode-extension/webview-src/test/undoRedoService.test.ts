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
});

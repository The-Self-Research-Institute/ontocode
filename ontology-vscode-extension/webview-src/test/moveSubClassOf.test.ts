import { beforeEach, describe, expect, it, vi } from 'vitest';

const post = vi.fn();

vi.mock('../services/apiClient', () => ({ default: { post: (...args: unknown[]) => post(...args) } }));
vi.mock('../utils/draftScope', () => ({ isDraftScopeActive: () => true }));

import { ontologyMutationService } from '../services/ontologyMutationService';

const THING = 'http://www.w3.org/2002/07/owl#Thing';

describe('moveSubClassOf', () => {
  beforeEach(() => {
    post.mockReset();
    post.mockResolvedValue({ changeSetId: 'cs' });
  });

  it('sends the removal of the old parent and the new parent in one request', async () => {
    await ontologyMutationService.moveSubClassOf('p', 'http://x#A', 'http://x#Old', 'http://x#New', 'u', 'n');
    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0][1].ops).toEqual([
      { type: 'deleteSubClassOf', iri: 'http://x#A', target: 'http://x#Old' },
      { type: 'addSubClassOf', iri: 'http://x#A', target: 'http://x#New' },
    ]);
  });

  it('moving a root class under a parent removes the explicit owl:Thing parent', async () => {
    await ontologyMutationService.moveSubClassOf('p', 'http://x#A', THING, 'http://x#New');
    expect(post.mock.calls[0][1].ops.map((o: any) => [o.type, o.target])).toEqual([
      ['deleteSubClassOf', THING],
      ['addSubClassOf', 'http://x#New'],
    ]);
  });

  it('moving to the root only removes the old parent', async () => {
    await ontologyMutationService.moveSubClassOf('p', 'http://x#A', 'http://x#Old', THING);
    expect(post.mock.calls[0][1].ops).toEqual([{ type: 'deleteSubClassOf', iri: 'http://x#A', target: 'http://x#Old' }]);
  });

  it('sends nothing when the parent does not change', async () => {
    await ontologyMutationService.moveSubClassOf('p', 'http://x#A', THING, THING);
    expect(post).not.toHaveBeenCalled();
  });
});

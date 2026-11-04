import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const post = vi.fn();
const put = vi.fn();

vi.mock('../services/apiClient', () => ({
  default: { post: (...args: unknown[]) => post(...args), put: (...args: unknown[]) => put(...args) },
}));
vi.mock('../utils/draftScope', () => ({ isDraftScopeActive: () => true }));

import { ontologyMutationService, PUBLIC_EDIT_DISABLED_MESSAGE } from '../services/ontologyMutationService';

const rejection = () => ({
  status: 403,
  data: { success: false, code: 'PUBLIC_EDIT_DISABLED', requireDraftForMembers: true, draftAllowed: true },
});

const op = { type: 'createClass', iri: 'http://x#A', label: 'A' };

describe('public editing turned off by the owner', () => {
  const handler = vi.fn();

  beforeEach(() => {
    post.mockReset();
    put.mockReset();
    handler.mockReset();
    ontologyMutationService.setPublicEditDisabledHandler(handler);
  });

  afterEach(() => {
    ontologyMutationService.setPublicEditDisabledHandler(null);
  });

  it('turns a rejected public save into a clear draft-required error and refreshes the UI once', async () => {
    post.mockRejectedValue(rejection());
    await expect(ontologyMutationService.applyMutations('p', [op], false, 'u', 'n')).rejects.toMatchObject({
      message: PUBLIC_EDIT_DISABLED_MESSAGE,
      reason: 'draftRequired',
    });
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('does the same for a relation edit', async () => {
    put.mockRejectedValue(rejection());
    await expect(
      ontologyMutationService.editRelation('p', { operation: 'add', entityIri: 'http://x#A' } as any),
    ).rejects.toMatchObject({ reason: 'draftRequired' });
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('leaves other 403 errors alone', async () => {
    post.mockRejectedValue({ status: 403, data: { viewOnly: true } });
    await expect(ontologyMutationService.applyMutations('p', [op], false, 'u', 'n')).rejects.toMatchObject({
      reason: 'viewOnly',
    });
    expect(handler).not.toHaveBeenCalled();
  });
});

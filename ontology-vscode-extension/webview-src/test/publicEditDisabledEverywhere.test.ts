import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError } from 'axios';

vi.mock('../utils/desktop', () => ({ isDesktop: () => false }));

import apiClient, { ApiError } from '../services/apiClient';
import { ontologyMutationService, PUBLIC_EDIT_DISABLED_MESSAGE } from '../services/ontologyMutationService';

const refusal = {
  success: false,
  draft: false,
  error: PUBLIC_EDIT_DISABLED_MESSAGE,
  code: 'PUBLIC_EDIT_DISABLED',
  requireDraftForMembers: true,
  draftAllowed: true,
};

function answerWith(status: number, data: unknown) {
  const axiosClient = (apiClient as any).axiosClient;
  axiosClient.defaults.adapter = async (config: any) => {
    const response = { status, data, statusText: '', headers: {}, config };
    throw new AxiosError('failed', 'ERR_BAD_REQUEST', config, null, response as any);
  };
}

const writes: Array<[string, () => Promise<unknown>]> = [
  ['undo', () => apiClient.post('/api/ontology/p/undo?draft=false', {})],
  ['redo', () => apiClient.post('/api/ontology/p/redo?draft=false', {})],
  ['change set undo', () => apiClient.post('/api/ontology/p/change-sets/c1/undo', {})],
  ['rename', () => apiClient.post('/api/ontology/entity/p/rename?draft=false', { oldIri: 'a', newIri: 'b' })],
  ['ontology IRI', () => apiClient.put('/api/ontology/metadata/p/iri', { ontologyIri: 'http://x#' })],
  ['annotation', () => apiClient.post('/api/ontology/metadata/p/annotations', {})],
  ['prefix delete', () => apiClient.delete('/api/ontology/metadata/p/prefixes?prefix=ex')],
  ['general class axiom', () => apiClient.post('/api/ontology/metadata/p/gci', {})],
  ['DL query add', () => apiClient.post('/api/ontology/p/dl/add?draft=false', {})],
  ['spreadsheet import', () => apiClient.post('/api/ontology/spreadsheet-import/p/commit?draft=false', {})],
  ['rule save', () => apiClient.post('/api/ontology/p/rules?draft=false', {})],
];

describe('public editing turned off by the owner, on every save path', () => {
  const handler = vi.fn();

  beforeEach(() => {
    handler.mockReset();
    ontologyMutationService.setPublicEditDisabledHandler(handler);
    answerWith(403, refusal);
  });

  afterEach(() => {
    ontologyMutationService.setPublicEditDisabledHandler(null);
  });

  it.each(writes)('%s becomes a draft-required error and opens the dialog once', async (_name, call) => {
    const error: any = await call().catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.reason).toBe('draftRequired');
    expect(error.message).toBe(PUBLIC_EDIT_DISABLED_MESSAGE);
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('does not open the dialog a second time when the mutation service maps the same error', async () => {
    const error: any = await apiClient.post('/api/ontology/mutations/p?draft=false', {}).catch((e) => e);
    expect(handler).toHaveBeenCalledTimes(1);
    const { publicEditDisabledError } = await import('../services/ontologyMutationService');
    expect(publicEditDisabledError(error)?.message).toBe(PUBLIC_EDIT_DISABLED_MESSAGE);
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('leaves view-only and other 403 answers alone', async () => {
    answerWith(403, { error: 'You have view-only access to this project.', viewOnly: true });
    const error: any = await apiClient.post('/api/ontology/p/undo', {}).catch((e) => e);
    expect(error.reason).toBeUndefined();
    expect(handler).not.toHaveBeenCalled();
  });
});

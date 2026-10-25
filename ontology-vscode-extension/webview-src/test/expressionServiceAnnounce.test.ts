import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/apiClient', () => ({
  default: { post: vi.fn(), get: vi.fn() },
}));

import apiClient from '../services/apiClient';
import expressionService from '../services/expressionService';

const post = apiClient.post as unknown as ReturnType<typeof vi.fn>;

describe('expressionService announces the edits it saves', () => {
  let mutated: Array<{ projectId: string; ops: string[] }>;
  const listener = (event: Event) => mutated.push((event as CustomEvent).detail);

  beforeEach(() => {
    mutated = [];
    post.mockReset();
    window.addEventListener('ontology:mutated', listener);
  });

  afterEach(() => {
    window.removeEventListener('ontology:mutated', listener);
  });

  it('tells the page a property domain was added', async () => {
    post.mockResolvedValue({ success: true });

    await expressionService.addPropertyExpressionAxiom('p1', 'http://x/p', 'Domain', 'Person', true);

    expect(mutated).toEqual([{ projectId: 'p1', ops: ['addPropertyDomain'] }]);
  });

  it('tells the page a property range was removed', async () => {
    post.mockResolvedValue({ success: true });

    await expressionService.deletePropertyExpressionAxiom('p1', 'http://x/p', 'Range', 'xsd:string', true);

    expect(mutated).toEqual([{ projectId: 'p1', ops: ['deletePropertyRange'] }]);
  });

  it('tells the page a class expression was added', async () => {
    post.mockResolvedValue({ success: true });

    await expressionService.addClassExpressionAxiom('p1', 'http://x/C', 'SubClassOf', 'p some D');

    expect(mutated).toEqual([{ projectId: 'p1', ops: ['addClassExpression'] }]);
  });

  it('says nothing when the save failed', async () => {
    post.mockRejectedValue(new Error('boom'));

    await expect(expressionService.addPropertyExpressionAxiom('p1', 'http://x/p', 'Domain', 'Person', true)).rejects.toThrow('boom');

    expect(mutated).toEqual([]);
  });
});

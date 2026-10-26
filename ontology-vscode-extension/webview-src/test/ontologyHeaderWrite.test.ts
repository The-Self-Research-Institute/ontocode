import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { announceOntologyHeaderWrite } from '../utils/ontologyHeaderWrite';

describe('announceOntologyHeaderWrite', () => {
  let mutated: Array<{ projectId: string; ops: string[] }>;
  const listener = (event: Event) => mutated.push((event as CustomEvent).detail);

  beforeEach(() => {
    mutated = [];
    window.addEventListener('ontology:mutated', listener);
  });

  afterEach(() => {
    window.removeEventListener('ontology:mutated', listener);
  });

  it('announces a saved annotation, import, prefix or general axiom of the active ontology', () => {
    for (const part of ['annotations', 'imports', 'prefixes', 'gci', 'gci/2']) {
      announceOntologyHeaderWrite(`/api/ontology/metadata/p1/${part}?draft=false`, null);
    }

    expect(mutated.map((m) => m.projectId)).toEqual(['p1', 'p1', 'p1', 'p1', 'p1']);
    expect(mutated.every((m) => m.ops[0] === 'ontologyHeader')).toBe(true);
  });

  it('decodes the project id', () => {
    announceOntologyHeaderWrite('/api/ontology/metadata/my%20project/annotations', null);

    expect(mutated[0].projectId).toBe('my project');
  });

  it('hands the response back unchanged', () => {
    const response = { success: true };

    expect(announceOntologyHeaderWrite('/api/ontology/metadata/p1/annotations', response)).toBe(response);
  });

  it('ignores every other address', () => {
    announceOntologyHeaderWrite('/api/ontology/metadata/p1', null);
    announceOntologyHeaderWrite('/api/ontology/metadata/p1/timestamp', null);
    announceOntologyHeaderWrite('/api/ontology/mutations/p1', null);
    announceOntologyHeaderWrite('/api/auth/login', null);

    expect(mutated).toEqual([]);
  });
});

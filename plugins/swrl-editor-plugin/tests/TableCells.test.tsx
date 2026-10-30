import React from 'react';
import { createRoot, Root } from 'react-dom/client';
import { act } from 'react-dom/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
  getBlob: vi.fn(),
  postForm: vi.fn(),
}));

vi.mock('../src/apiClient', () => ({ default: api }));

import { SWRLEditor } from '../src/SWRLEditor';
import { axiomCells } from '../src/axiomCells';

(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;

const NS = 'http://example.org/o#';
const TYPE = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type';
const RULES = [{ id: 'r1', ruleName: 'Senior', ruleText: 'Patient(?p) -> Senior(?p)', comment: '', category: '', enabled: true }];

const AXIOMS = [
  { axiomType: 'ClassAssertion', category: 'conclusion', description: `ClassAssertion(<${NS}Person> <${NS}Chef>)`, readable: 'Chef is a Person', subjectIri: `${NS}Chef`, predicateIri: TYPE, objectIri: `${NS}Person` },
  { axiomType: 'ObjectPropertyAssertion', category: 'conclusion', description: `ObjectPropertyAssertion(<${NS}hasTopping> <${NS}Pizza1> <${NS}Ham>)`, readable: 'Pizza1 has topping Ham', subjectIri: `${NS}Pizza1`, predicateIri: `${NS}hasTopping`, objectIri: `${NS}Ham` },
  { axiomType: 'DataPropertyAssertion', category: 'conclusion', description: `DataPropertyAssertion(<${NS}hasAge> <${NS}Ann> "42"^^<http://www.w3.org/2001/XMLSchema#integer>)`, readable: 'Ann has age 42', subjectIri: `${NS}Ann`, predicateIri: `${NS}hasAge`, objectLiteral: '42' },
  { axiomType: 'SubClassOf', category: 'structure', description: `SubClassOf(<${NS}Customer> <${NS}Person>)`, readable: 'Customer is a kind of Person', subjectIri: null, predicateIri: null, objectIri: null },
  { axiomType: 'AnnotationAssertion', category: 'conclusion', description: `AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <${NS}Chef> "The cook"@en)`, readable: 'Chef has the label The cook' },
  { axiomType: 'HasKey', category: 'conclusion', description: `HasKey(<${NS}Person> (<${NS}ssn>))`, readable: 'Person has the key ssn' },
];

const RESULT = {
  success: true,
  executionMode: 'all',
  executedRuleNames: ['Senior'],
  totalRulesExecuted: 1,
  executionTimeMs: 5,
  inferredAxiomsCount: AXIOMS.length,
  conclusionCount: 5,
  structureCount: 1,
  inferredAxioms: AXIOMS,
};

let container: HTMLDivElement;
let root: Root;

async function click(el: HTMLElement) {
  await act(async () => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

const byText = (label: string) => Array.from(container.querySelectorAll('button')).find(b => (b.textContent || '').trim().includes(label)) as HTMLElement;

const rows = () =>
  Array.from(container.querySelectorAll('tr.swrl-why-host')).map(tr => ({
    subject: tr.querySelector('[data-testid="cell-subject"]')?.textContent ?? '',
    relation: tr.querySelector('[data-testid="cell-relation"]')?.textContent ?? '',
    object: tr.querySelector('[data-testid="cell-object"]')?.textContent ?? '',
  }));

beforeEach(async () => {
  Object.values(api).forEach(f => f.mockReset());
  api.get.mockResolvedValue({ rules: RULES });
  api.post.mockResolvedValue(RESULT);
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  vi.spyOn(console, 'error').mockImplementation(() => {});
  await act(async () => {
    root.render(<SWRLEditor projectId="p1" />);
  });
  await click(byText('Run and results'));
  await click(byText('Run rules'));
  await click(container.querySelector('[data-testid="facts-view-standard"]') as HTMLElement);
  await click(byText('Table'));
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.restoreAllMocks();
});

describe('the Table view of new facts', () => {
  it('shows subject, relation and object for every axiom type and never the whole sentence in Subject', () => {
    const all = rows();
    expect(all).toContainEqual({ subject: 'Chef', relation: 'is a', object: 'Person' });
    expect(all).toContainEqual({ subject: 'Pizza1', relation: 'hasTopping', object: 'Ham' });
    expect(all).toContainEqual({ subject: 'Ann', relation: 'hasAge', object: '42' });
    expect(all).toContainEqual({ subject: 'Customer', relation: 'is a kind of', object: 'Person' });
    expect(all).toContainEqual({ subject: 'Chef', relation: 'label', object: 'The cook' });
    const unknown = all.find(r => r.relation === 'has key');
    expect(unknown).toBeTruthy();
    expect(unknown?.subject).toBe('-');
    expect(unknown?.object).toContain('Person');
    expect(all.every(r => !/ is a | has /.test(r.subject))).toBe(true);
  });

  it('shows the technical text in the Raw view and the short form in the grouped view', async () => {
    await click(byText('Raw'));
    expect(container.textContent).toContain(`ClassAssertion(<${NS}Person> <${NS}Chef>)`);
    await click(byText('Grouped'));
    expect(container.textContent).toContain('Chef');
    expect(container.textContent).not.toContain('Chef is a Person');
  });
});

describe('axiomCells', () => {
  const parsed = { type: 'Unknown', subject: 'x', formatted: 'x' };

  it('falls back to the structured fields when the description cannot be read', () => {
    expect(axiomCells({ axiomType: 'ClassAssertion', description: 'x', readable: 'y', subjectIri: `${NS}A`, objectIri: `${NS}B` }, parsed)).toEqual({ subject: 'A', relation: 'is a', object: 'B' });
  });

  it('uses the literal for a data property and the property short name as relation', () => {
    expect(axiomCells({ axiomType: 'DataPropertyAssertion', description: 'x', readable: 'y', subjectIri: `${NS}A`, predicateIri: `${NS}hasAge`, objectLiteral: '7' }, parsed)).toEqual({ subject: 'A', relation: 'hasAge', object: '7' });
  });

  it('puts an unknown type in the relation and the text in the object', () => {
    expect(axiomCells({ axiomType: 'HasKey', description: 'x', readable: 'y' }, { type: 'HasKey', subject: 'some text', formatted: 'some text' })).toEqual({ subject: '', relation: 'has key', object: 'some text' });
  });
});

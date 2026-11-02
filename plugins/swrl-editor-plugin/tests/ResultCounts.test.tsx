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

(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;

const NS = 'http://example.org/o#';
const TYPE = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type';
const SUB = 'http://www.w3.org/2000/01/rdf-schema#subClassOf';
const RULES = [{ id: 'r1', ruleName: 'Senior', ruleText: 'Patient(?p) -> Senior(?p)', comment: '', category: '', enabled: true }];

const fact = (cls: string, n: number) => ({
  axiomType: 'ClassAssertion',
  description: `ClassAssertion(<${NS}${cls}> <${NS}i${n}>)`,
  readable: `ClassAssertion(<${NS}${cls}> <${NS}i${n}>)`,
  category: 'conclusion',
  subjectIri: `${NS}i${n}`,
  predicateIri: TYPE,
  objectIri: `${NS}${cls}`,
});

const relation = (n: number) => ({
  axiomType: 'SubClassOf',
  description: `SubClassOf(<${NS}C${n}> <${NS}Top>)`,
  readable: `SubClassOf(<${NS}C${n}> <${NS}Top>)`,
  category: 'structure',
  subjectIri: `${NS}C${n}`,
  predicateIri: SUB,
  objectIri: `${NS}Top`,
});

const range = (n: number) => Array.from({ length: n }, (_, i) => i + 1);
const FACTS = [...range(100).map(n => fact('Senior', n)), ...range(18).map(n => fact('Person', 100 + n))];
const PREVIEW = [...FACTS, ...range(882).map(relation)];

const UNPAGED = {
  success: true,
  executionTimeMs: 1,
  totalRulesExecuted: 1,
  inferredAxiomsCount: 1108,
  conclusionCount: 118,
  structureCount: 945,
  omittedTrivialCount: 45,
  truncated: true,
  inferredAxioms: PREVIEW,
  errorMessage: null,
};

const PAGED = {
  ...UNPAGED,
  inferredAxioms: PREVIEW.slice(0, 50),
  runId: 'run1',
  counts: { conclusions: 118, structure: 945, omittedTrivial: 45 },
};

const ALLOWED = new Set(['118', '945', '1,063', '100', '50', '882', '845', '82', '145', '18', '1,000', '111', '1', '45', '1,108']);

let container: HTMLDivElement;
let root: Root;

const text = () => container.textContent || '';
const heading = () => container.querySelector('[data-testid="inferred-heading"]')?.textContent;
const cutoff = () => container.querySelector('[data-testid="cutoff-note"]')?.textContent;
const section = (key: string) => container.querySelector(`[data-testid="section-${key}"]`) as HTMLElement | null;

function find(label: RegExp | string) {
  return Array.from(container.querySelectorAll('button')).find(b =>
    typeof label === 'string' ? (b.textContent || '').trim() === label : label.test((b.textContent || '').trim())
  ) as HTMLButtonElement | undefined;
}

function button(label: RegExp | string) {
  const found = find(label);
  if (!found) throw new Error('button not found: ' + label);
  return found;
}

async function click(el: HTMLElement) {
  await act(async () => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

async function search(value: string) {
  const input = container.querySelector('input[placeholder^="Search axioms"]') as HTMLInputElement;
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
  await act(async () => {
    setter.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

async function go(result: unknown) {
  api.post.mockResolvedValue(result);
  await act(async () => {
    root.render(<SWRLEditor projectId="p1" />);
  });
  await click(button(/Execute All/));
}

function numbersIn(s: string): string[] {
  return s.match(/\d[\d,]*/g) ?? [];
}

beforeEach(() => {
  Object.values(api).forEach(f => f.mockReset());
  api.get.mockImplementation((url: string) => {
    if (url.includes('/axioms?')) {
      const kind = new URL(url, 'http://x').searchParams.get('kind');
      return Promise.resolve(kind === 'structure'
        ? { axioms: range(50).map(relation), total: 945, offset: 0, limit: 50, kind }
        : { axioms: FACTS.slice(0, 50), total: 118, offset: 0, limit: 50, kind });
    }
    return Promise.resolve({ rules: RULES });
  });
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.restoreAllMocks();
});

describe('unpaged run, grouped view', () => {
  it('facts: counts the real total and reaches it by showing more', async () => {
    await go(UNPAGED);
    expect(heading()).toBe('Facts about individuals (118 in total), showing 100');
    expect(section('rules')!.textContent).toContain('From your rules: 100 in total');
    expect(button(/^Show 18 more/).textContent).toBe('Show 18 more (18 remaining)');
    expect(cutoff()).toBeUndefined();
    await click(button(/^Show 18 more/));
    expect(heading()).toBe('Facts about individuals (118 in total)');
    expect(section('standard')!.textContent).toContain('From standard reasoning: 18 in total');
    expect(find(/more \(/)).toBeUndefined();
    expect(text()).not.toContain('matching');
  });

  it('class relationships: total, shown, remaining and the cut-off note agree and stop at the loaded rows', async () => {
    await go(UNPAGED);
    await click(button(/^Class relationships \(945\)$/));
    expect(heading()).toBe('Class relationships (945 in total), showing 100');
    expect(section('structure')!.textContent).toContain('Class relationships: 945 in total, showing 100');
    expect(section('structure')!.textContent).not.toContain('100 inferred');
    expect(button(/^Show 100 more/).textContent).toBe('Show 100 more (845 remaining)');
    expect(cutoff()).toBe('Showing 100 of 945. Search or filter to narrow, or show more.');
    expect(text()).not.toContain('1,063');
    expect(text()).not.toContain('matching');
    for (const n of numbersIn([heading(), cutoff(), section('structure')!.querySelector('button')!.textContent, button(/more \(/).textContent].join(' '))) {
      expect(ALLOWED.has(n)).toBe(true);
    }
    for (let i = 0; i < 7; i++) await click(button(/^Show 100 more/));
    expect(heading()).toBe('Class relationships (945 in total), showing 800');
    expect(button(/more \(/).textContent).toBe('Show 82 more (145 remaining)');
    await click(button(/^Show 82 more/));
    expect(heading()).toBe('Class relationships (945 in total), showing 882');
    expect(cutoff()).toBe('Showing 882 of 945 (the rest is not loaded for this run).');
    expect(find(/more \(/)).toBeUndefined();
  });

  it('everything: the total is the sum and is only used here', async () => {
    await go(UNPAGED);
    await click(button('Everything'));
    expect(heading()).toBe('Everything (1,063 in total), showing 100');
    expect(cutoff()).toBe('Showing 100 of 1,063. Search or filter to narrow, or show more.');
    expect(button(/more \(/).textContent).toBe('Show 100 more (963 remaining)');
  });

  it('search only covers the loaded rows and says so', async () => {
    await go(UNPAGED);
    await click(button(/^Class relationships \(945\)$/));
    await search('C1');
    expect(text()).toContain('111 match "C1" in the loaded rows');
    expect(cutoff()).toBe('Search covers only the 882 loaded rows of 945.');
    expect(section('structure')!.textContent).toContain('Class relationships: 111 match in the loaded rows, showing 100');
    expect(button(/more \(/).textContent).toBe('Show 11 more (11 remaining)');
    expect(text()).not.toContain('matching');
  });

  it('shows no match wording without a search', async () => {
    await go(UNPAGED);
    expect(text()).not.toContain('match "');
    expect(text()).not.toContain('in the loaded rows');
  });
});

describe('unpaged run, table view', () => {
  it('uses the same heading, section rows and note', async () => {
    await go(UNPAGED);
    await click(button(/^Class relationships \(945\)$/));
    await click(button(/Table/));
    expect(heading()).toBe('Class relationships (945 in total), showing 100');
    expect(container.querySelector('table')!.textContent).toContain('Class relationships: 945 in total, showing 100');
    expect(cutoff()).toBe('Showing 100 of 945. Search or filter to narrow, or show more.');
    expect(button(/more \(/).textContent).toBe('Show 100 more (845 remaining)');
  });

  it('facts table shows the exact split once every fact is loaded', async () => {
    await go(UNPAGED);
    await click(button(/Table/));
    await click(button(/^Show 18 more/));
    const t = container.querySelector('table')!.textContent!;
    expect(t).toContain('From your rules: 100 in total');
    expect(t).toContain('From standard reasoning: 18 in total');
  });
});

describe('unpaged run with only part of the facts loaded', () => {
  it('hides the split totals and shows the facts total only in the heading', async () => {
    await go({ ...UNPAGED, conclusionCount: 500, inferredAxioms: PREVIEW });
    expect(heading()).toBe('Facts about individuals (500 in total), showing 100');
    expect(section('rules')!.textContent).toContain('From your rules: showing 100');
    expect(section('rules')!.textContent).not.toContain('in total');
    expect(cutoff()).toBe('Showing 100 of 500. Search or filter to narrow, or show more.');
  });
});

describe('paged run', () => {
  it('facts: totals come from the server and the pager is the way to the rest', async () => {
    await go(PAGED);
    expect(heading()).toBe('Facts about individuals (118 in total), showing 50');
    expect(cutoff()).toBeUndefined();
    expect(find(/more \(/)).toBeUndefined();
    expect(text()).toContain('Showing 1-50 of 118');
    expect(text()).not.toContain('matching');
  });

  it('class relationships: total is the server total and the section agrees', async () => {
    await go(PAGED);
    await click(button(/^Class relationships \(945\)$/));
    expect(heading()).toBe('Class relationships (945 in total), showing 50');
    expect(section('structure')!.textContent).toContain('Class relationships: 945 in total, showing 50');
    expect(text()).toContain('Showing 1-50 of 945');
    expect(cutoff()).toBeUndefined();
    expect(find(/more \(/)).toBeUndefined();
    expect(text()).not.toContain('1,063');
  });

  it('table view keeps the same heading', async () => {
    await go(PAGED);
    await click(button(/^Class relationships \(945\)$/));
    await click(button(/Table/));
    expect(heading()).toBe('Class relationships (945 in total), showing 50');
    expect(container.querySelector('table')!.textContent).toContain('Class relationships: 945 in total, showing 50');
  });
});

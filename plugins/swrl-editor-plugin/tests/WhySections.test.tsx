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
const RULES = [
  { id: 'r1', ruleName: 'Senior', ruleText: 'Patient(?p) -> Senior(?p)', comment: '', category: '', enabled: true },
  { id: 'r2', ruleName: 'Off', ruleText: 'Patient(?p) -> Disabled(?p)', comment: '', category: '', enabled: false },
];

const fact = (cls: string, ind: string) => ({
  axiomType: 'ClassAssertion',
  description: `${cls}-${ind}`,
  readable: `ClassAssertion(<${NS}${cls}> <${NS}${ind}>)`,
  category: 'conclusion',
  subjectIri: `${NS}${ind}`,
  predicateIri: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type',
  objectIri: `${NS}${cls}`,
});

const RESULT = {
  success: true,
  executionTimeMs: 1,
  totalRulesExecuted: 1,
  inferredAxiomsCount: 5,
  conclusionCount: 4,
  structureCount: 1,
  inferredAxioms: [
    fact('Senior', 'ann'),
    fact('Senior', 'bob'),
    fact('Person', 'ann'),
    fact('Disabled', 'cy'),
    {
      axiomType: 'SubClassOf',
      description: 'sub-Customer-Person',
      readable: `SubClassOf(<${NS}Customer> <${NS}Person>)`,
      category: 'structure',
      subjectIri: `${NS}Customer`,
      predicateIri: 'http://www.w3.org/2000/01/rdf-schema#subClassOf',
      objectIri: `${NS}Person`,
    },
  ],
  errorMessage: null,
};

const NOTE_START = 'These follow from the class tree and the standard reasoning';
const PLAIN = 'None of your rules produced this fact.';
const NEW_NOTE = 'None of your rules produced this fact. It comes from the standard reasoning the engine always applies, for example "a Customer is also a Person because Customer is a kind of Person". It is not an error.';

let container: HTMLDivElement;
let root: Root;

const text = () => container.textContent || '';
const whys = (scope: ParentNode = container) => Array.from(scope.querySelectorAll('button[aria-label="Why was this concluded?"]')) as HTMLButtonElement[];
const section = (key: string) => container.querySelector(`[data-testid="section-${key}"]`) as HTMLElement | null;
const count = (haystack: string, needle: string) => haystack.split(needle).length - 1;

function button(label: RegExp | string) {
  const found = Array.from(container.querySelectorAll('button')).find(b =>
    typeof label === 'string' ? (b.textContent || '').trim() === label : label.test(b.textContent || '')
  );
  if (!found) throw new Error('button not found: ' + label);
  return found as HTMLButtonElement;
}

async function click(el: HTMLElement) {
  await act(async () => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

async function run() {
  await act(async () => {
    root.render(<SWRLEditor projectId="p1" />);
  });
  await click(button(/Execute All/));
  await click(button(/^Everything/));
}

beforeEach(() => {
  Object.values(api).forEach(f => f.mockReset());
  api.get.mockResolvedValue({ rules: RULES });
  api.post.mockResolvedValue(RESULT);
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

describe('inferred facts split by where they come from', () => {
  it('puts a fact for a rule head class under your rules and the rest under standard reasoning', async () => {
    await run();
    const mine = section('rules')!;
    const standard = section('standard')!;
    expect(mine.textContent).toContain('From your rules: 2 in total');
    expect(mine.textContent).toContain('ann');
    expect(mine.textContent).toContain('bob');
    expect(mine.textContent).not.toContain('Person');
    expect(standard.textContent).toContain('From standard reasoning: 2 in total');
    expect(standard.textContent).toContain('Person');
    expect(standard.textContent).toContain('Disabled');
  });

  it('shows the explanation control on rule rows and on standard and structure rows that can be explained in detail', async () => {
    await run();
    expect(whys(section('rules')!)).toHaveLength(2);
    expect(whys(section('standard')!)).toHaveLength(2);
    expect(whys(section('structure')!)).toHaveLength(1);
    const w = whys()[0];
    expect(w.tabIndex).toBe(0);
    expect(w.disabled).toBe(false);
    expect(w.textContent).toBe('Why?');
  });

  it('keeps the control always visible and reachable by keyboard', async () => {
    await run();
    const css = container.querySelector('style')!.textContent!;
    expect(css).not.toContain('opacity:0}');
    expect(css).toContain('.swrl-why{opacity:.8}');
    expect(css).toContain('.swrl-why:focus-visible');
    const w = whys()[0];
    expect(w.closest('.swrl-why-host')).not.toBeNull();
    await act(async () => {
      w.focus();
    });
    expect(document.activeElement).toBe(w);
  });

  it('writes the standard reasoning note once per section', async () => {
    await run();
    expect(count(section('standard')!.textContent!, NOTE_START)).toBe(1);
    expect(count(section('structure')!.textContent!, NOTE_START)).toBe(1);
    expect(section('rules')!.textContent).not.toContain(NOTE_START);
  });

  it('selects rows across sections for adding', async () => {
    await run();
    const boxes = Array.from(container.querySelectorAll('input[type="checkbox"][aria-label="Select this conclusion"]')) as HTMLInputElement[];
    expect(boxes.length).toBe(4);
    await click(section('rules')!.querySelector('input[type="checkbox"]') as HTMLElement);
    await click(section('standard')!.querySelector('input[type="checkbox"]') as HTMLElement);
    expect(text()).toMatch(/2 selected|\(2\)|Add 2/);
    expect(boxes.filter(b => b.checked)).toHaveLength(2);
  });

  it('collapses a section and keeps its count in the header', async () => {
    await run();
    await click(button(/From your rules/));
    expect(section('rules')!.textContent).toContain('From your rules: 2 in total');
    expect(whys(section('rules')!)).toHaveLength(0);
  });
});

describe('why dialog for a fact no rule produced', () => {
  it('shows the plain sentence once', async () => {
    api.post.mockImplementation((url: string) =>
      url.includes('/explain') ? Promise.resolve({ status: 'origin_only', justifications: [], notes: [NEW_NOTE] }) : Promise.resolve(RESULT)
    );
    await run();
    await click(whys()[0]);
    expect(text()).toContain('Why was this concluded?');
    expect(count(text(), PLAIN)).toBe(1);
    expect(text()).not.toContain('cannot evaluate');
    expect(text()).not.toContain('Produced by OWL');
  });

  it('shows the plain sentence once when an older server sends the old note', async () => {
    api.post.mockImplementation((url: string) =>
      url.includes('/explain')
        ? Promise.resolve({ status: 'origin_only', justifications: [], notes: ['Produced by OWL reasoning or a rule this explanation cannot evaluate'] })
        : Promise.resolve(RESULT)
    );
    await run();
    await click(whys()[0]);
    expect(count(text(), PLAIN)).toBe(1);
    expect(text()).not.toContain('cannot evaluate');
  });
});

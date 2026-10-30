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
  description: `ClassAssertion(<${NS}${cls}> <${NS}${ind}>)`,
  readable: `${ind} is a ${cls}`,
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
      description: `SubClassOf(<${NS}Customer> <${NS}Person>)`,
      readable: 'Customer is a kind of Person',
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
    typeof label === 'string' ? (b.textContent || '').trim() === label : label.test((b.textContent || '').trim())
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
  await click(button(/^Run and results/));
  await click(button('Run rules'));
  await click(container.querySelector('[data-testid="facts-show-all"]') as HTMLElement);
}

async function runStandard() {
  await run();
  await click(container.querySelector('[data-testid="facts-view-standard"]') as HTMLElement);
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
  it('puts a fact for a rule head class under your rules', async () => {
    await run();
    const mine = section('rules')!;
    expect(mine.textContent).toContain('From your rules (2)');
    expect(mine.textContent).toContain('ann');
    expect(mine.textContent).toContain('bob');
    expect(mine.textContent).not.toContain('Person');
    expect(section('standard')).toBeNull();
    expect(section('structure')).toBeNull();
  });

  it('puts everything else under standard reasoning when that view is chosen', async () => {
    await runStandard();
    const standard = section('standard')!;
    expect(standard.textContent).toContain('From standard reasoning (2)');
    expect(standard.textContent).toContain('Person');
    expect(standard.textContent).toContain('Disabled');
    expect(section('rules')).toBeNull();
    expect(section('structure')).toBeTruthy();
  });

  it('shows the explanation control only on rule rows and never on standard or structure rows', async () => {
    await run();
    expect(whys(section('rules')!)).toHaveLength(2);
    const w = whys()[0];
    expect(w.tabIndex).toBe(0);
    expect(w.disabled).toBe(false);
    expect(text()).not.toContain('Why?');
    await click(container.querySelector('[data-testid="facts-view-standard"]') as HTMLElement);
    expect(whys()).toHaveLength(0);
  });

  it('keeps the control hidden until hover or focus and reachable by keyboard', async () => {
    await run();
    const css = Array.from(container.querySelectorAll('style')).map(e => e.textContent).join('');
    expect(css).toContain('.swrl-why-host .swrl-why{opacity:0}');
    expect(css).toContain('.swrl-why-host:hover .swrl-why');
    expect(css).toContain('.swrl-why-host:focus-within .swrl-why');
    const w = whys()[0];
    expect(w.closest('.swrl-why-host')).not.toBeNull();
    await act(async () => {
      w.focus();
    });
    expect(document.activeElement).toBe(w);
  });

  it('writes the standard reasoning note once per section', async () => {
    await runStandard();
    expect(count(section('standard')!.textContent!, NOTE_START)).toBe(1);
    expect(count(section('structure')!.textContent!, NOTE_START)).toBe(1);
    await click(container.querySelector('[data-testid="facts-view-rules"]') as HTMLElement);
    expect(section('rules')!.textContent).not.toContain(NOTE_START);
  });

  it('selects rows across sections for adding', async () => {
    await run();
    expect(container.querySelectorAll('input[type="checkbox"][aria-label="Select this conclusion"]').length).toBe(2);
    await click(section('rules')!.querySelector('input[type="checkbox"]') as HTMLElement);
    await click(container.querySelector('[data-testid="facts-view-standard"]') as HTMLElement);
    await click(section('standard')!.querySelector('input[type="checkbox"]') as HTMLElement);
    expect(text()).toMatch(/Add 2 selected/);
  });

  it('collapses a section and keeps its count in the header', async () => {
    await run();
    await click(section('rules')!.querySelector('button') as HTMLElement);
    expect(section('rules')!.textContent).toContain('From your rules (2)');
    expect(whys()).toHaveLength(0);
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

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
import { AssertedAxiomsPanel, Owl2RlPanel } from '../src/AxiomPanels';
import { ranAtText, ruleTitle, shortenIris } from '../src/ruleModel';

(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

const text = () => container.textContent || '';

const RUN_ALL = /^Run rules$|^Running…$/;

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

async function render(node: React.ReactElement) {
  await act(async () => {
    root.render(node);
  });
}

function deferred<T = unknown>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>(res => {
    resolve = res;
  });
  return { promise, resolve };
}

beforeEach(() => {
  Object.values(api).forEach(f => f.mockReset());
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

describe('friendly axiom rows', () => {
  it('shows the note, short sentences with the full text on hover, and a details row', async () => {
    api.get.mockResolvedValue({
      total: 1, page: 0, pageSize: 50,
      items: [{ axiomType: 'ClassAssertion', readable: 'ann is a Patient', description: 'ClassAssertion(<http://example.org/clinic#Patient> <http://example.org/clinic#ann>)' }],
    });
    await render(<AssertedAxiomsPanel projectId="p1" draftMode={false} />);
    expect(text()).toContain('Existing facts are the statements already in the ontology.');
    const sentence = Array.from(container.querySelectorAll('li span')).find(e => e.textContent === 'ann is a Patient') as HTMLElement;
    expect(sentence.getAttribute('title')).toContain('<http://example.org/clinic#ann>');
    expect(container.querySelector('[data-testid="axiom-details"]')).toBeNull();
    const toggle = button('Details');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    await click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(container.querySelector('[data-testid="axiom-details"]')!.textContent).toContain('ClassAssertion(<http://example.org/clinic#Patient>');
    expect(container.querySelector('ul')!.getAttribute('aria-label')).toBe('Asserted axioms');
  });

  it('shortens full web addresses when an older server still sends them', async () => {
    api.get.mockResolvedValue({
      total: 1, page: 0, pageSize: 50,
      items: [{ axiomType: 'SubClassOf', readable: 'SubClassOf(<http://example.org/clinic#Patient> <http://example.org/clinic#Person>)' }],
    });
    await render(<AssertedAxiomsPanel projectId="p1" draftMode={false} />);
    expect(text()).toContain('SubClassOf(Patient Person)');
    expect(text()).not.toContain('http://example.org');
    expect(shortenIris('<http://example.org/a/b#Name>')).toBe('Name');
  });
});

describe('OWL 2 RL labels and unsupported rules', () => {
  const run = {
    success: true, completion: 'completed', derivedCount: 2, executionTimeMs: 5, runId: 'r', page: 0, pageSize: 50, totalPages: 1, typeCounts: {},
    axioms: [
      { axiomType: 'SubClassOf', category: 'structure', readable: 'Patient is a kind of Person' },
      { axiomType: 'ClassAssertion', category: 'conclusion', readable: 'ann is a Senior' },
    ],
  };

  it('uses the plain category names', async () => {
    api.post.mockResolvedValue(run);
    await render(<Owl2RlPanel projectId="p1" draftMode={false} />);
    await click(button('Compute OWL 2 RL closure'));
    expect(text()).toContain('Class relationships');
    expect(text()).toContain('Facts about individuals');
    expect(text()).not.toContain('structure');
  });

  it('turns codes into one plain sentence, with unknown codes as other standard rules and the raw codes behind a toggle', async () => {
    api.post.mockResolvedValue({ ...run, unsupportedRules: ['prp-spo2', 'prp-key', 'cax-adc', 'eq-diff2', 'eq-diff3', 'zzz-new'] });
    await render(<Owl2RlPanel projectId="p1" draftMode={false} />);
    await click(button('Compute OWL 2 RL closure'));
    const note = container.querySelector('[data-testid="owl2rl-unsupported"]') as HTMLElement;
    expect(note.textContent).toContain('Not applied: property chains, keys, disjoint unions and groups of disjoint classes, groups of different individuals and other standard rules.');
    expect(note.textContent).not.toContain('zzz-new');
    const toggle = note.querySelector('button') as HTMLElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    await click(toggle);
    expect(container.querySelector('[data-testid="owl2rl-unsupported-codes"]')!.textContent).toContain('prp-spo2, prp-key, cax-adc, eq-diff2, eq-diff3, zzz-new');
  });
});

describe('rule titles and run time', () => {
  it('uses the start of the rule text for generated names only', () => {
    const long = 'Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThan(?a, 65) ^ hasTemp(?p, ?t) -> Senior(?p)';
    const shown = ruleTitle({ ruleName: 'Rule 12fc4201', ruleText: long });
    expect(shown.title.endsWith('...')).toBe(true);
    expect(shown.title.length).toBe(60);
    expect(shown.tooltip).toBe(`Rule 12fc4201: ${long}`);
    expect(ruleTitle({ ruleName: 'Senior', ruleText: long })).toEqual({ title: 'Senior', tooltip: 'Senior' });
  });

  it('says just now, then a clock time', () => {
    const at = new Date(2026, 9, 30, 9, 5).getTime();
    expect(ranAtText(at, at + 5000)).toBe('just now');
    expect(ranAtText(at, at + 120000)).toBe('at 09:05');
  });
});

describe('the rule checklist on the Run and results screen', () => {
  const RULES = [
    { id: 'a', ruleName: 'Senior', ruleText: 'Patient(?p) -> Senior(?p)', enabled: true },
    { id: 'b', ruleName: 'Old rule', ruleText: 'Patient(?p) -> Child(?p)', enabled: false },
    { id: 'c', ruleName: 'Count them', ruleText: 'Patient(?p) -> sqwrl:select(?p)', enabled: true },
    { id: 'd', ruleName: 'Rule 12fc4201', ruleText: 'Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThan(?a, 65) -> Senior(?p)', enabled: true },
  ];
  const base = { success: true, inferredAxioms: [], inferredAxiomsCount: 0, totalRulesExecuted: 2, executionTimeMs: 9 };

  async function open() {
    api.get.mockResolvedValue({ rules: RULES });
    await render(<SWRLEditor projectId="p1" />);
    await click(button(/^Run and results/));
    await click(button('Choose which rules'));
  }

  const rowOf = (id: string) => container.querySelector(`[data-testid="control-rule-${id}"]`) as HTMLElement;
  const box = (id: string) => rowOf(id).querySelector('input') as HTMLInputElement;

  it('lists every rule with markers only for the unusual states and a readable title for unnamed rules', async () => {
    await open();
    expect(container.querySelectorAll('li[data-testid^="control-rule-"]').length).toBe(4);
    expect(rowOf('a').textContent).not.toContain('Enabled');
    expect(rowOf('a').textContent).toContain('Patient(?p) -> Senior(?p)');
    expect(rowOf('b').textContent).toContain('Disabled');
    expect(rowOf('c').textContent).toContain('SQWRL query');
    expect(rowOf('d').textContent).toContain('Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThan(?a, 65)');
    expect(rowOf('d').textContent).not.toContain('Rule 12fc4201');
    expect((rowOf('d').querySelector('label') as HTMLElement).title).toContain('Rule 12fc4201');
    expect(container.querySelector('[data-testid="control-scope-line"]')?.textContent).toBe('2 rules will run(all enabled rules)');
    expect(container.querySelector('[data-testid="control-rule-search"]')).toBeNull();
  });

  it('shows a search box only for more than 8 rules and filters by name', async () => {
    const many = Array.from({ length: 9 }, (_, i) => ({ id: `m${i}`, ruleName: `Rule number ${i}`, ruleText: `Patient(?p) -> C${i}(?p)`, enabled: true }));
    api.get.mockResolvedValue({ rules: many });
    await render(<SWRLEditor projectId="p1" />);
    await click(button(/^Run and results/));
    await click(button('Choose which rules'));
    const search = container.querySelector('[data-testid="control-rule-search"]') as HTMLInputElement;
    expect(search).toBeTruthy();
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(search, 'number 3');
      search.dispatchEvent(new Event('input', { bubbles: true }));
    });
    expect(container.querySelectorAll('li[data-testid^="control-rule-"]').length).toBe(1);
  });

  it('shows the empty state with a button to the Rules tab', async () => {
    api.get.mockResolvedValue({ rules: [] });
    await render(<SWRLEditor projectId="p1" />);
    await click(button(/^Run and results/));
    expect(text()).toContain('No rules yet. Create one on the Rules tab, or import an Excel file.');
    expect(container.querySelector('[data-testid="control-run"]')).toBeNull();
    await click(button('Go to the Rules tab'));
    expect(container.querySelector('[data-testid="control-panel"]')).toBeNull();
  });

  it('keeps ticks in the checklist, updates the counter and the button, and blocks disabled and query rows', async () => {
    await open();
    expect(box('b').disabled).toBe(true);
    expect(box('c').disabled).toBe(true);
    await click(box('a'));
    expect(box('a').checked).toBe(true);
    expect(button('Run 1 selected rule').disabled).toBe(false);
    expect(text()).toContain('1 of 2 selected');
    await click(button('Select all'));
    expect(button('Run 2 selected rules')).toBeTruthy();
    expect(box('b').checked).toBe(false);
    await click(button('Select none'));
    expect(button('Run rules')).toBeTruthy();
  });

  it('has no rule ticks in the Rules tab list', async () => {
    api.get.mockResolvedValue({ rules: RULES });
    await render(<SWRLEditor projectId="p1" />);
    expect(container.querySelectorAll('aside input[type="checkbox"]').length).toBe(0);
  });

  it('says what ran once in the results, marks rules that ran or were skipped, and lists the skipped reasons', async () => {
    api.post.mockResolvedValue({
      ...base, executionMode: 'all', executedRuleNames: ['Senior', 'Rule 12fc4201'], inferredAxiomsCount: 5,
      conclusionCount: 3, structureCount: 2, rounds: 2, engineVersions: { reasoner: '1.0' },
      skippedRules: [
        { ruleName: 'Old rule', reason: 'This rule is disabled.' },
        { ruleName: 'Count them', reason: 'This is a SQWRL query. It is not run as a rule.' },
      ],
    });
    await open();
    await click(button('Run rules'));
    const summary = container.querySelector('[data-testid="control-summary"]') as HTMLElement;
    expect(summary.textContent).toContain('Completed with warnings');
    expect(summary.textContent).toContain('in 9 ms, from 2 rules, just now');
    expect(summary.textContent).toContain('From your rules (3)');
    expect(summary.textContent).toContain('Standard reasoning (2)');
    expect(summary.textContent).toContain('2 rules were skipped.');
    const badge = Array.from(container.querySelectorAll('span')).find(e => /^5$/.test(e.textContent || '') && (e.className || '').includes('rounded-full'));
    expect(badge).toBeTruthy();
    expect(rowOf('a').textContent).toContain('Ran');
    expect(rowOf('b').textContent).toContain('Disabled');
    expect(rowOf('c').textContent).toContain('SQWRL query');
    const toggle = summary.querySelector('[data-testid="control-skipped"] button') as HTMLElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    await click(toggle);
    expect(summary.textContent).toContain('Old rule: This rule is disabled');
    expect(summary.textContent).toContain('Count them: This is a SQWRL query. It is not run as a rule');
    await click(toggle);
    expect(summary.textContent).not.toContain('Old rule: This rule is disabled');
  });

  it('names a selected run and shows a rule that could not run', async () => {
    api.post.mockResolvedValue({
      ...base, executionMode: 'selected', executedRuleNames: ['Senior'], totalRulesExecuted: 1,
      skippedRules: [{ ruleName: 'Rule 12fc4201', reason: 'Rule Rule 12fc4201 could not run: bad atom' }],
    });
    await open();
    await click(box('a'));
    await click(button('Run 1 selected rule'));
    expect(api.post.mock.calls[0][0]).toContain('/api/swrl/p1/execute/selected');
    const summary = container.querySelector('[data-testid="control-summary"]') as HTMLElement;
    expect(summary.textContent).toContain('in 9 ms, from 1 rule, just now');
    expect(rowOf('d').textContent).toContain('Could not run');
    await click(summary.querySelector('[data-testid="control-skipped"] button') as HTMLElement);
    expect(summary.textContent).toContain('Could not run: bad atom');
  });
});

async function goMore(label: RegExp) {
  await click(button('More'));
  await click(button(label));
}

async function showAll() {
  await click(container.querySelector('[data-testid="facts-show-all"]') as HTMLElement);
}

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
    expect(text()).toContain('Asserted axioms are the statements typed into the ontology.');
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

describe('Control tab rules list and last run', () => {
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
    await click(button('Control'));
  }

  const rowOf = (id: string) => container.querySelector(`[data-testid="control-rule-${id}"]`) as HTMLElement;
  const box = (id: string) => rowOf(id).querySelector('input') as HTMLInputElement;

  it('lists every rule with state badges, a readable title for unnamed rules, and counts in the buttons', async () => {
    await open();
    expect(container.querySelectorAll('li[data-testid^="control-rule-"]').length).toBe(4);
    expect(rowOf('a').textContent).toContain('Enabled');
    expect(rowOf('b').textContent).toContain('Disabled');
    expect(rowOf('c').textContent).toContain('SQWRL query');
    expect(rowOf('d').textContent).toContain('Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThan(?a, 65)');
    expect(rowOf('d').textContent).not.toContain('Rule 12fc4201');
    expect((rowOf('d').querySelector('label') as HTMLElement).title).toContain('Rule 12fc4201');
    expect(button(/Run rules/).textContent).toContain('(3)');
    expect(button(/Run rules/).disabled).toBe(true);
    expect(button(/Run selected/).textContent).toContain('(0)');
    expect(button(/Run selected/).disabled).toBe(true);
    expect(text()).toContain('Tick rules below to run only those.');
  });

  it('keeps ticks in sync with the left list and the header button, and blocks disabled and query rows', async () => {
    await open();
    expect(box('b').disabled).toBe(true);
    expect(box('c').disabled).toBe(true);
    await click(box('a'));
    expect(box('a').checked).toBe(true);
    expect(button(/Run selected/).textContent).toContain('(1)');
    expect(button(/Run 1 Selected/)).toBeTruthy();
    await click(button('Select all enabled'));
    expect(button(/Run selected/).textContent).toContain('(2)');
    expect(box('b').checked).toBe(false);
    await click(button('Select none'));
    expect(button(/Run selected/).textContent).toContain('(0)');
    const leftBox = container.querySelector('aside input[type="checkbox"]') as HTMLInputElement;
    await click(leftBox);
    expect(button(/Run selected/).textContent).toContain('(1)');
  });

  it('says what ran, splits the counts, agrees with the Results badge, and explains each rule after a run', async () => {
    api.post.mockResolvedValue({
      ...base, executionMode: 'all', executedRuleNames: ['Senior', 'Rule 12fc4201'], inferredAxiomsCount: 5,
      conclusionCount: 3, structureCount: 2, rounds: 2, engineVersions: { reasoner: '1.0' },
      skippedRules: [
        { ruleName: 'Old rule', reason: 'This rule is disabled.' },
        { ruleName: 'Count them', reason: 'This is a SQWRL query. It is not run as a rule.' },
      ],
    });
    await open();
    await click(button('Load ontology and rules'));
    await click(button(/Run rules \(/));
    const summary = container.querySelector('[data-testid="control-summary"]') as HTMLElement;
    expect(summary.textContent).toContain('Ran all enabled rules just now');
    expect(summary.textContent).toContain('5 inferred facts: 3 facts about individuals, 2 class relationships');
    expect(summary.textContent).toContain('Completed with warnings: 2 rules were skipped.');
    expect(summary.textContent).toContain('2 rounds');
    expect(summary.textContent).toContain('reasoner 1.0');
    expect(summary.textContent).toContain('Old rule: This rule is disabled');
    expect(summary.textContent).toContain('Count them: This is a SQWRL query. It is not run as a rule');
    const badge = Array.from(container.querySelectorAll('span')).find(e => /^5$/.test(e.textContent || '') && (e.className || '').includes('rounded-full'));
    expect(badge).toBeTruthy();
    expect(rowOf('a').textContent).toContain('Ran');
    expect(rowOf('b').textContent).toContain('Skipped: This rule is disabled');
    expect(rowOf('c').textContent).toContain('Skipped: This is a SQWRL query');
    const toggle = summary.querySelector('[data-testid="control-skipped"] button') as HTMLElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
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
    await click(button(/Run selected/));
    expect(api.post.mock.calls[0][0]).toContain('/api/swrl/p1/execute/selected');
    const summary = container.querySelector('[data-testid="control-summary"]') as HTMLElement;
    expect(summary.textContent).toContain('Ran 1 selected rule just now');
    expect(rowOf('d').textContent).toContain('Could not run: bad atom');
  });

  it('sends one request for a double click', async () => {
    const gate = deferred();
    api.post.mockReturnValue(gate.promise);
    await open();
    await click(button('Load ontology and rules'));
    await act(async () => {
      const b = button(/Run rules \(/);
      b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(api.post).toHaveBeenCalledTimes(1);
    await act(async () => {
      gate.resolve({ ...base, executionMode: 'all' });
    });
  });
});

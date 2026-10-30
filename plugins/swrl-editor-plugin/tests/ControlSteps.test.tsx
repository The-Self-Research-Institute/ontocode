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
import { addConfirmText, axiomToOp, inferredPlan, loadLogLines, runLogLines } from '../src/ruleModel';

(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

const text = () => container.textContent || '';
const logText = () => {
  if (!container.querySelector('[data-testid="control-log"]')) {
    act(() => {
      (container.querySelector('[data-testid="details-menu"]') as HTMLElement).dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    act(() => {
      (container.querySelector('[data-testid="details-menu-technical"]') as HTMLElement).dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
  }
  return (container.querySelector('[data-testid="control-log"]') as HTMLElement).textContent || '';
};

const BANNED_NAMES = new RegExp([['prot', 'ege'].join(''), ['dro', 'ols'].join('')].join('|'), 'i');
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

function deferred<T = unknown>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const RULES = [
  { id: 'a', ruleName: 'Senior', ruleText: 'Patient(?p) -> Senior(?p)', enabled: true },
  { id: 'b', ruleName: 'Child', ruleText: 'Patient(?p) -> Child(?p)', enabled: true },
];

const SUMMARY = {
  rulesExported: 8,
  classDeclarations: 57,
  individualDeclarations: 45,
  objectPropertyDeclarations: 12,
  dataPropertyDeclarations: 5,
  totalAxioms: 378,
  skippedRules: [{ ruleName: 'Count them', reason: 'This is a SQWRL query. It is not run as a rule.' }],
  elapsedMs: 177,
};

const TYPE = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type';
const fact = (n: number) => ({
  axiomType: 'ClassAssertion', category: 'conclusion', description: `ClassAssertion(<http://x#Senior> <http://x#p${n}>)`, readable: `ClassAssertion(<http://x#Senior> <http://x#p${n}>)`,
  subjectIri: `http://x#p${n}`, predicateIri: TYPE, objectIri: 'http://x#Senior',
});
const relation = {
  axiomType: 'SubClassOf', category: 'structure', description: 'SubClassOf(<http://x#Senior> <http://x#Patient>)', readable: 'Senior is a kind of Patient',
  subjectIri: null, predicateIri: null, objectIri: null,
};

const RUN = {
  success: true, executionMode: 'all', executedRuleNames: ['Senior', 'Child'], totalRulesExecuted: 2, executionTimeMs: 242,
  inferredAxiomsCount: 3, conclusionCount: 2, structureCount: 1, inferredAxioms: [fact(1), fact(2), relation],
  stamp: { ontologyRevision: 'r1', draftVersion: '', rulesFingerprint: 'f' },
  skippedRules: [{ ruleName: 'Old', reason: 'This rule is disabled.' }],
};

function routes(overrides: Record<string, unknown> = {}) {
  api.get.mockImplementation((url: string) => {
    if (url in overrides) return overrides[url] as Promise<unknown>;
    if (url.includes('transfer-summary')) return Promise.resolve(SUMMARY);
    if (url.includes('/axioms/asserted')) return Promise.resolve({ total: 0, page: 0, pageSize: 50, items: [] });
    return Promise.resolve({ rules: RULES });
  });
}

async function open(run: unknown = RUN) {
  routes();
  api.post.mockImplementation((url: string) => {
    if (url.includes('/mutations/')) return Promise.resolve({ changeSetId: 'cs1' });
    return Promise.resolve(run);
  });
  await act(async () => {
    root.render(<SWRLEditor projectId="p1" />);
  });
  await click(button(/^Run and results/));
}

async function menu(id: string, item: string) {
  await click(container.querySelector(`[data-testid="${id}"]`) as HTMLElement);
  await click(container.querySelector(`[data-testid="${id}-${item}"]`) as HTMLElement);
}

const load = () => menu('details-menu', 'check');
const run = () => click(button(RUN_ALL));
const add = () => click(button(/^Add [\d,]+ facts? to the ontology…/));
const mutationCalls = () => api.post.mock.calls.filter(c => String(c[0]).includes('/mutations/'));

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

describe('the Run and results screen', () => {
  it('starts with the scope line, the helper sentence and one big Run rules button', async () => {
    await open();
    expect(container.querySelector('[data-testid="control-scope-line"]')?.textContent).toBe('2 rules will run(all enabled rules)');
    expect(button('Run rules').disabled).toBe(false);
    expect(container.querySelector('[data-testid="control-helper"]')?.textContent).toBe('Nothing is saved until you add the results to your ontology.');
    expect(container.querySelector('[data-testid="control-rules"]')).toBeNull();
    expect(container.querySelector('[data-testid="control-summary"]')).toBeNull();
    expect(container.querySelector('[data-testid="control-add"]')).toBeNull();
  });

  it('runs without the optional check', async () => {
    await open();
    await run();
    expect(api.get.mock.calls.some(c => String(c[0]).includes('transfer-summary'))).toBe(false);
    expect(button(/^Add 2 facts to the ontology…/).disabled).toBe(false);
  });

  it('opens the rule checklist from Choose which rules and changes the button to the selected count', async () => {
    await open();
    await click(button('Choose which rules'));
    expect(container.querySelector('[data-testid="control-selected-count"]')?.textContent).toBe('0 of 2 selected');
    await click(container.querySelector('[data-testid="control-rule-a"] input') as HTMLElement);
    expect(button('Run 1 selected rule')).toBeTruthy();
    expect(container.querySelector('[data-testid="control-scope-line"]')?.textContent).toBe('1 rule will run(selected rules)');
    await click(button('Select all'));
    expect(button('Run 2 selected rules')).toBeTruthy();
    await click(button('Select none'));
    expect(button('Run rules')).toBeTruthy();
    await click(button('Hide the rule list'));
    expect(container.querySelector('[data-testid="control-rules"]')).toBeNull();
  });

  it('runs only the ticked rules when some are ticked and all enabled rules otherwise', async () => {
    await open();
    await click(button('Choose which rules'));
    await click(container.querySelector('[data-testid="control-rule-a"] input') as HTMLElement);
    await click(button('Run 1 selected rule'));
    expect(api.post.mock.calls[0][0]).toContain('/api/swrl/p1/execute/selected');
    await menu('details-menu', 'clear');
    await click(button('Select none'));
    await click(button('Run rules'));
    expect(api.post.mock.calls[1][0]).not.toContain('/execute/selected');
  });

  it('shows no Enabled badge on normal rows and the selected counter', async () => {
    await open();
    await click(button('Choose which rules'));
    expect(container.querySelector('[data-testid="control-rule-a"]')?.textContent).not.toContain('Enabled');
    expect(text()).toContain('0 of 2 selected');
  });

  it('shows Running with a spinner while the run is in flight and keeps the button disabled', async () => {
    await open();
    const gate = deferred();
    api.post.mockReturnValue(gate.promise);
    await click(button('Run rules'));
    expect(button('Running…').disabled).toBe(true);
    expect(button('Running…').querySelector('.animate-spin')).toBeTruthy();
    await act(async () => {
      gate.resolve(RUN);
    });
    expect(container.querySelector('[data-testid="control-summary"]')).toBeTruthy();
    expect(container.querySelector('[data-testid="control-helper"]')).toBeNull();
  });

  it('sends one request for a double click on Run rules', async () => {
    await open();
    const gate = deferred();
    api.post.mockReturnValue(gate.promise);
    await act(async () => {
      const b = button('Run rules');
      b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(api.post).toHaveBeenCalledTimes(1);
    await act(async () => {
      gate.resolve(RUN);
    });
  });

  it('shows the big number, the time line, the next step and the first facts after a run', async () => {
    await open();
    await run();
    expect(container.querySelector('[data-testid="control-stat-facts"]')?.textContent).toBe('2');
    expect(container.querySelector('[data-testid="control-status"]')?.textContent).toContain('new facts found');
    expect(container.querySelector('[data-testid="control-scope"]')?.textContent).toMatch(/^in 242 ms, from 2 rules/);
    expect(container.querySelector('[data-testid="control-next-step"]')?.textContent).toBe('Add them to your ontology to keep them.');
    expect(container.querySelector('[data-testid="facts-preview"]')).toBeTruthy();
    expect(button('Choose which to add')).toBeTruthy();
    expect(container.querySelector('[data-testid="control-status"]')?.getAttribute('aria-live')).toBe('polite');
  });

  it('keeps the result line and the Add button in a sticky bar', async () => {
    await open();
    await run();
    const sticky = container.querySelector('[data-testid="control-sticky"]') as HTMLElement;
    expect(sticky.className).toContain('sticky');
    expect(sticky.querySelector('[data-testid="control-add"]')).toBeTruthy();
    expect(sticky.querySelector('[data-testid="control-status"]')).toBeTruthy();
  });

  it('shows one yellow line for skipped rules that expands to the reasons, and the Completed with warnings label', async () => {
    await open();
    await run();
    expect(container.querySelector('[data-testid="control-warn-label"]')?.textContent).toBe('Completed with warnings');
    expect(container.querySelector('[data-testid="control-skipped"]')?.textContent).toContain('1 rule was skipped.');
    expect(text()).not.toContain('Old: This rule is disabled');
    await click(button('See why'));
    expect(text()).toContain('Old: This rule is disabled');
  });

  it('shows Completed without a warning line or a skipped list when nothing was skipped', async () => {
    await open({ ...RUN, skippedRules: [] });
    await run();
    expect(container.querySelector('[data-testid="control-warn-label"]')).toBeNull();
    expect(container.querySelector('[data-testid="control-skipped"]')).toBeNull();
  });

  it('shows Failed with the message and no Add button', async () => {
    await open({ success: false, errorMessage: 'Built-in function error', executionTimeMs: 1, totalRulesExecuted: 0, inferredAxiomsCount: 0, inferredAxioms: [] });
    await run();
    expect(container.querySelector('[data-testid="control-status-text"]')?.textContent).toBe('Failed');
    expect(text()).toContain('Built-in function error');
    expect(container.querySelector('[data-testid="control-add"]')).toBeNull();
  });

  it('points to the next action when the run found nothing', async () => {
    await open({ ...RUN, inferredAxioms: [], inferredAxiomsCount: 0, conclusionCount: 0, structureCount: 0 });
    await run();
    expect(container.querySelector('[data-testid="control-next-step"]')?.textContent).toBe('No new facts. Try ticking more rules, or see Standard reasoning.');
    expect(button(/^Add 0 facts to the ontology…/).disabled).toBe(true);
    expect(container.querySelector('[data-testid="control-add-reason"]')?.textContent).toContain('no new facts');
  });

  it('shows the segmented control with both counts and reveals standard reasoning only when chosen', async () => {
    await open();
    await run();
    expect(container.querySelector('[data-testid="facts-view-rules"]')?.textContent).toBe('From your rules (2)');
    expect(container.querySelector('[data-testid="facts-view-standard"]')?.textContent).toBe('Standard reasoning (1)');
    expect(container.querySelector('[data-testid="section-rules"]')).toBeNull();
    await showAll();
    expect(container.querySelector('[data-testid="facts-preview"]')).toBeNull();
    expect(container.querySelector('[data-testid="section-rules"]')).toBeTruthy();
    expect(container.querySelector('[data-testid="section-structure"]')).toBeNull();
    await click(container.querySelector('[data-testid="facts-view-standard"]') as HTMLElement);
    expect(container.querySelector('[data-testid="section-structure"]')).toBeTruthy();
    expect(container.querySelector('[data-testid="section-rules"]')).toBeNull();
    await click(container.querySelector('[data-testid="facts-view-rules"]') as HTMLElement);
    expect(container.querySelector('[data-testid="section-rules"]')).toBeTruthy();
  });

  it('opens the whole list for Choose which to add so rows can be ticked', async () => {
    await open();
    await run();
    await click(button('Choose which to add'));
    expect(container.querySelectorAll('input[aria-label="Select this conclusion"]').length).toBeGreaterThan(0);
  });

  it('has no Execute All or Selected buttons on the Rules screen', async () => {
    routes();
    await act(async () => {
      root.render(<SWRLEditor projectId="p1" />);
    });
    const labels = Array.from(container.querySelectorAll('button')).map(b => (b.textContent || '').trim());
    expect(labels.some(l => /Execute All|Selected/.test(l))).toBe(false);
  });
});

describe('the Details menu and the technical details', () => {
  it('lists the technical details, the optional check, run again, clear results and the explanation guide', async () => {
    await open();
    await run();
    await click(container.querySelector('[data-testid="details-menu"]') as HTMLElement);
    const list = container.querySelector('[data-testid="details-menu-list"]') as HTMLElement;
    for (const label of ['Technical details', 'Check what will be sent', 'Run again', 'Clear results', 'How facts are explained']) {
      expect(list.textContent).toContain(label);
    }
  });

  it('is the only place for Run again and Clear results', async () => {
    await open();
    await run();
    expect(button('Choose which to add')).toBeTruthy();
    expect(Array.from(container.querySelectorAll('button')).some(b => (b.textContent || '').trim() === 'Run again')).toBe(false);
    await menu('details-menu', 'again');
    expect(api.post).toHaveBeenCalledTimes(2);
    await menu('details-menu', 'clear');
    expect(container.querySelector('[data-testid="control-summary"]')).toBeNull();
    expect(button('Run rules')).toBeTruthy();
  });

  it('keeps the technical details closed until asked, then shows the log with Clear log', async () => {
    await open();
    await run();
    expect(container.querySelector('[data-testid="control-log"]')).toBeNull();
    await menu('details-menu', 'technical');
    expect(container.querySelector('[data-testid="control-log-section"]')?.textContent).toMatch(/Technical details \(\d+ lines\)/);
    expect(logText()).toContain('Rules ran successfully.');
    expect(button('Clear log')).toBeTruthy();
    await click(button('Hide'));
    expect(container.querySelector('[data-testid="control-log"]')).toBeNull();
  });

  it('shows the counts of the optional check and the engine line', async () => {
    await open({ ...RUN, rounds: 2, engineVersions: { reasoner: '1.0' } });
    await run();
    await load();
    expect(container.querySelector('[data-testid="control-check-summary"]')?.textContent).toBe('Sends 8 rules, 57 classes, 45 individuals, 378 axioms');
    expect(container.querySelector('[data-testid="control-engine"]')?.textContent).toBe('2 rounds · Engine: reasoner 1.0');
  });

  it('sends one request for a double click on the check and shows it busy', async () => {
    const gate = deferred();
    await open();
    routes({ '/api/swrl/p1/transfer-summary': gate.promise });
    await click(container.querySelector('[data-testid="details-menu"]') as HTMLElement);
    await act(async () => {
      const item = container.querySelector('[data-testid="details-menu-check"]') as HTMLElement;
      item.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(api.get.mock.calls.filter(c => String(c[0]).includes('transfer-summary')).length).toBe(1);
    expect(container.querySelector('[data-testid="control-check-summary"]')?.textContent).toBe('Checking…');
    await act(async () => {
      gate.resolve(SUMMARY);
    });
    expect(container.querySelector('[data-testid="control-check-summary"]')?.textContent).toContain('Sends 8 rules');
  });

  it('asks for the draft copy when editing a private draft', async () => {
    routes();
    api.post.mockResolvedValue(RUN);
    await act(async () => {
      root.render(<SWRLEditor projectId="p1" draftMode />);
    });
    await click(button(/^Run and results/));
    await load();
    expect(api.get.mock.calls.some(c => c[0] === '/api/swrl/p1/transfer-summary?draft=true')).toBe(true);
  });

  it('names no engine or tool in any tooltip', async () => {
    await open();
    await run();
    for (const b of Array.from(container.querySelectorAll('button'))) expect(b.title).not.toMatch(BANNED_NAMES);
  });
});

describe('the log', () => {
  it('prints the check in plain words without repeating the counts the summary shows', async () => {
    await open();
    await load();
    const log = logText();
    expect(log).toContain('Ontology and rules checked successfully.');
    expect(log).toContain('Number of object properties that will be sent: 12');
    expect(log).toContain('Number of data properties that will be sent: 5');
    expect(log).not.toContain('Number of classes that will be sent');
    expect(log).toContain('Skipped rule Count them: This is a SQWRL query. It is not run as a rule');
    expect(log).toContain('The check took 177 millisecond(s).');
    expect(log).toContain('built fresh every time');
  });

  it('prints the run with skipped rules and the time, without repeating the counts the card shows', async () => {
    await open();
    await run();
    const log = logText();
    expect(log).toContain('Rules ran successfully.');
    expect(log).toContain('Skipped rule Old: This rule is disabled');
    expect(log).toContain('The run took 242 millisecond(s).');
    expect(log).not.toContain('Number of inferred facts');
    expect(log).not.toContain('Number of rules run');
  });

  it('keeps the newest line last, survives a tab switch, and clears on request', async () => {
    await open();
    await run();
    const lines = logText().split('\n');
    expect(lines[lines.length - 1]).toContain('The run took');
    await click(button(/^Rules$/));
    await click(button(/^Run and results/));
    expect(logText()).toContain('Rules ran successfully.');
    await click(button('Clear log'));
    expect(logText()).toContain('Nothing has happened yet.');
  });

  it('says plainly when the check or the run fails', async () => {
    await open();
    routes({ '/api/swrl/p1/transfer-summary': Promise.reject(Object.assign(new Error('busy'), { status: 429 })) });
    await load();
    expect(logText()).toContain('The rule engine is busy. Try again in a moment.');
    expect(button('Run rules').disabled).toBe(false);
    routes();
    api.post.mockResolvedValue({ success: false, errorMessage: 'Built-in function error', executionTimeMs: 1, totalRulesExecuted: 0, inferredAxiomsCount: 0, inferredAxioms: [] });
    await run();
    expect(logText()).toContain('The rules could not be run: Built-in function error');
  });
});

describe('adding the inferred facts', () => {
  async function ready(runBody: unknown = RUN) {
    await open(runBody);
    await load();
    await run();
  }

  it('asks first, says how many facts, that they become asserted and can be undone, and writes nothing on Cancel', async () => {
    await ready();
    await add();
    const dialog = container.querySelector('[data-testid="add-confirm"]') as HTMLElement;
    expect(dialog.getAttribute('role')).toBe('alertdialog');
    const sentence = (container.querySelector('[data-testid="add-confirm-text"]') as HTMLElement).textContent || '';
    expect(sentence).toContain('add 2 facts');
    expect(sentence).toContain('ordinary asserted statements');
    expect(sentence).toContain('undo the change from the history');
    expect((dialog.querySelector('input[type="checkbox"]') as HTMLInputElement).checked).toBe(false);
    await click(button('Cancel'));
    expect(container.querySelector('[data-testid="add-confirm"]')).toBeNull();
    expect(mutationCalls().length).toBe(0);
  });

  it('counts class relationships only when the box is ticked', async () => {
    await ready();
    await add();
    await click(container.querySelector('[data-testid="add-confirm"] input') as HTMLElement);
    expect((container.querySelector('[data-testid="add-confirm-text"]') as HTMLElement).textContent).toContain('add 3 facts');
  });

  it('writes the facts as one batch through the ordinary mutations call with the run stamp', async () => {
    await ready();
    await add();
    await click(button('Yes, add them'));
    const calls = mutationCalls();
    expect(calls.length).toBe(1);
    expect(calls[0][0]).toBe('/api/ontology/mutations/p1');
    expect(calls[0][1].ops).toEqual([
      { type: 'addClassAssertion', iri: 'http://x#p1', classIri: 'http://x#Senior' },
      { type: 'addClassAssertion', iri: 'http://x#p2', classIri: 'http://x#Senior' },
    ]);
    expect(calls[0][1].precondition).toEqual(RUN.stamp);
    expect(calls[0][1].origin.source).toBe('swrl-rules');
    expect(logText()).toContain('Added 2 facts to the ontology. You can undo this from the history.');
    expect(container.querySelector('[data-testid="control-add-notice"]')?.textContent).toContain('Added 2 facts');
    expect(button(/^Add [\d,]+ facts? to the ontology…/).disabled).toBe(true);
  });

  it('includes class relationships as subclass statements when the box is ticked', async () => {
    await ready();
    await add();
    await click(container.querySelector('[data-testid="add-confirm"] input') as HTMLElement);
    await click(button('Yes, add them'));
    const ops = mutationCalls()[0][1].ops;
    expect(ops.length).toBe(3);
    expect(ops[2]).toEqual({ type: 'addSubClassOf', iri: 'http://x#Senior', target: 'http://x#Patient' });
  });

  it('sends the draft flag for a private draft', async () => {
    routes();
    api.post.mockImplementation((url: string) => Promise.resolve(url.includes('/mutations/') ? { changeSetId: 'c' } : RUN));
    await act(async () => {
      root.render(<SWRLEditor projectId="p1" draftMode />);
    });
    await click(button(/^Run and results/));
    await load();
    await run();
    await add();
    await click(button('Yes, add them'));
    expect(mutationCalls()[0][0]).toBe('/api/ontology/mutations/p1?draft=true');
  });

  it('shows the public-edit refusal in the log and does not mark the run as added', async () => {
    await ready();
    api.post.mockRejectedValue(Object.assign(new Error('Public editing is off'), { status: 403, data: { code: 'PUBLIC_EDIT_DISABLED', message: 'Public editing is off' } }));
    await add();
    await click(button('Yes, add them'));
    expect(logText()).toContain('Public editing is off');
    expect(container.querySelector('[data-testid="control-add-notice"]')?.textContent).toContain('Public editing is off');
    expect(container.querySelector('[data-testid="control-add-notice"]')?.getAttribute('role')).toBe('alert');
    expect(button(/^Add [\d,]+ facts? to the ontology…/).disabled).toBe(false);
  });

  it('refuses a second click while the write is in flight', async () => {
    await ready();
    const gate = deferred();
    api.post.mockReturnValue(gate.promise);
    await add();
    await act(async () => {
      const yes = button('Yes, add them');
      yes.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      yes.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(mutationCalls().length).toBe(1);
    await act(async () => {
      gate.resolve({ changeSetId: 'c' });
    });
  });

  it('caps one click at 5,000 facts and says so before and after', async () => {
    const many = { ...RUN, inferredAxioms: [], conclusionCount: 6200, structureCount: 0, inferredAxiomsCount: 6200, runId: 'run1', counts: { conclusions: 6200, structure: 0 } };
    api.get.mockImplementation((url: string) => {
      if (url.includes('/rules/execute/run1/axioms')) {
        const offset = Number(new URL(url, 'http://h').searchParams.get('offset'));
        const limit = Number(new URL(url, 'http://h').searchParams.get('limit'));
        const axioms = Array.from({ length: Math.min(limit, 6200 - offset) }, (_, i) => fact(offset + i));
        return Promise.resolve({ axioms, total: 6200, offset, limit, kind: 'conclusions' });
      }
      if (url.includes('transfer-summary')) return Promise.resolve(SUMMARY);
      if (url.includes('/axioms/asserted')) return Promise.resolve({ total: 0, page: 0, pageSize: 50, items: [] });
      return Promise.resolve({ rules: RULES });
    });
    api.post.mockImplementation((url: string) => Promise.resolve(url.includes('/mutations/') ? { changeSetId: 'c' } : many));
    await act(async () => {
      root.render(<SWRLEditor projectId="p1" />);
    });
    await click(button(/^Run and results/));
    await load();
    await run();
    await add();
    const sentence = (container.querySelector('[data-testid="add-confirm-text"]') as HTMLElement).textContent || '';
    expect(sentence).toContain('add 5,000 facts');
    expect(sentence).toContain('Only the first 5,000 of 6,200');
    await click(button('Yes, add them'));
    const calls = mutationCalls();
    expect(calls.length).toBe(1);
    expect(calls[0][1].ops.length).toBe(5000);
    expect(logText()).toContain('Added 5,000 facts');
    expect(logText()).toContain('Only the first 5,000 of 6,200');
  });

  it('refreshes the Asserted Axioms list after a successful add', async () => {
    await ready();
    const before = api.get.mock.calls.filter(c => String(c[0]).includes('/axioms/asserted')).length;
    await add();
    await click(button('Yes, add them'));
    await goMore(/^Existing facts/);
    expect(api.get.mock.calls.filter(c => String(c[0]).includes('/axioms/asserted')).length).toBeGreaterThan(before);
  });
});

describe('tabs, the More menu and wording', () => {
  const tabLabels = () => Array.from(container.querySelectorAll('[role="tab"], [data-testid="more-menu"]')).map(b => (b.textContent || '').trim());

  it('has Rules, Run and results and More, and no run buttons in the page header', async () => {
    await open();
    expect(tabLabels()).toEqual(['Rules', 'Run and results', 'More']);
    const labels = Array.from(container.querySelectorAll('button')).map(b => (b.textContent || '').trim());
    expect(labels).not.toContain('Control');
    expect(labels).not.toContain('Inferred Axioms');
    expect(labels).not.toContain('Asserted Axioms');
    expect(labels.some(l => /Execute All|Run \d+ Selected/.test(l))).toBe(false);
  });

  it('lists the More items with their one-line descriptions and the classic layout switch', async () => {
    await open();
    await click(button('More'));
    const list = container.querySelector('[data-testid="more-menu-list"]') as HTMLElement;
    for (const text of ['Existing facts', 'What is already stated in the ontology', 'Standard reasoning', 'Facts the ontology implies with no rules', 'Ask a question (query)', 'Write a query and get a table', 'Function help', 'Functions a rule can use', 'Classic layout']) {
      expect(list.textContent).toContain(text);
    }
  });

  it('shows the count of existing facts once the menu has been opened', async () => {
    await open();
    routes({ '/api/swrl/p1/axioms/asserted?page=0&pageSize=1&draft=false': Promise.resolve({ total: 280, page: 0, pageSize: 1, items: [] }) });
    api.get.mockImplementation((url: string) => {
      if (url.includes('/axioms/asserted')) return Promise.resolve({ total: 280, page: 0, pageSize: 1, items: [] });
      return Promise.resolve({ rules: RULES });
    });
    await click(button('More'));
    expect(container.querySelector('[data-testid="more-menu-asserted"]')?.textContent).toContain('Existing facts (280)');
  });

  it('opens one item with a breadcrumb and a way back to Run and results', async () => {
    await open();
    await click(button('More'));
    await click(container.querySelector('[data-testid="more-menu-owl2rl"]') as HTMLElement);
    expect(container.querySelector('[data-testid="more-breadcrumb"]')?.textContent).toContain('Standard reasoning');
    expect(container.querySelector('[data-testid="owl2rl-panel"]')).toBeTruthy();
    await click(container.querySelector('[data-testid="more-back"]') as HTMLElement);
    expect(container.querySelector('[data-testid="control-panel"]')).toBeTruthy();
    expect(container.querySelector('[data-testid="more-breadcrumb"]')).toBeNull();
  });

  it('opens Function help and Ask a question (query) from the More menu', async () => {
    await open();
    await click(button('More'));
    await click(container.querySelector('[data-testid="more-menu-reference"]') as HTMLElement);
    expect(text()).toContain('Functions you can use in rules and queries.');
    await click(container.querySelector('[data-testid="more-back"]') as HTMLElement);
    await click(button('More'));
    await click(container.querySelector('[data-testid="more-menu-query"]') as HTMLElement);
    expect(container.querySelector('[data-testid="more-breadcrumb"]')?.textContent).toContain('Ask a question (query)');
  });

  it('closes the menu with Escape', async () => {
    await open();
    await click(button('More'));
    expect(container.querySelector('[data-testid="more-menu-list"]')).toBeTruthy();
    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(container.querySelector('[data-testid="more-menu-list"]')).toBeNull();
  });

  it('keeps the count badge on the Run and results tab and explains New facts and Existing facts', async () => {
    await open();
    await run();
    expect(button(/^Run and results/).textContent).toContain('3');
    await showAll();
    expect(text()).toContain('New facts');
    await goMore(/^Existing facts/);
    expect(text()).toContain('Existing facts are the statements already in the ontology.');
  });
});

describe('the classic layout', () => {
  afterEach(() => {
    try {
      window.localStorage.removeItem('ontocode.swrlClassicLayout');
    } catch {
      return;
    }
  });

  it('restores the seven tabs and the header run buttons, remembers the choice, and switches back', async () => {
    await open();
    await click(button('More'));
    await click(container.querySelector('[data-testid="more-menu-classic"]') as HTMLElement);
    const tabs = Array.from(container.querySelectorAll('[data-testid="classic-tabs"] button')).map(b => (b.textContent || '').trim());
    expect(tabs).toEqual(['Editor', 'Inferred Axioms', 'Reference', 'SQWRL Query', 'Control', 'Asserted Axioms', 'OWL 2 RL', 'Switch to the new layout']);
    expect(button(/Execute All \(2\)/)).toBeTruthy();
    expect(window.localStorage.getItem('ontocode.swrlClassicLayout')).toBe('1');
    await click(button('Control'));
    expect(container.querySelector('[data-testid="control-panel"]')).toBeTruthy();
    await click(button('Inferred Axioms'));
    expect(text()).toContain('New facts are worked out by the rules');
    await click(container.querySelector('[data-testid="classic-switch"]') as HTMLElement);
    expect(container.querySelector('[data-testid="classic-tabs"]')).toBeNull();
    expect(window.localStorage.getItem('ontocode.swrlClassicLayout')).toBeNull();
  });

  it('starts in the classic layout when it was chosen before, and survives blocked storage', async () => {
    window.localStorage.setItem('ontocode.swrlClassicLayout', '1');
    routes();
    await act(async () => {
      root.render(<SWRLEditor projectId="p1" />);
    });
    expect(container.querySelector('[data-testid="classic-tabs"]')).toBeTruthy();
    await act(async () => {
      root.unmount();
    });
    root = createRoot(container);
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    await act(async () => {
      root.render(<SWRLEditor projectId="p1" />);
    });
    expect(container.querySelector('[data-testid="classic-tabs"]')).toBeNull();
    spy.mockRestore();
  });
});

describe('the numbers on the Run and results screen', () => {
  const inferredTab = async (r: unknown) => {
    await open(r);
    await run();
  };

  it('shows the fact count big and both counts in the segmented control, using the same two labels in the filter chips', async () => {
    await inferredTab({ ...RUN, inferredAxiomsCount: 1108, conclusionCount: 118, structureCount: 945, omittedTrivialCount: 45 });
    expect(container.querySelector('[data-testid="control-stat-facts"]')?.textContent).toBe('118');
    expect(container.querySelector('[data-testid="facts-view-standard"]')?.textContent).toBe('Standard reasoning (945)');
    await click(container.querySelector('[data-testid="facts-view-standard"]') as HTMLElement);
    expect(button(/Facts about individuals \(118\)/)).toBeTruthy();
    expect(button(/Class relationships \(945\)/)).toBeTruthy();
    expect(button('Everything')).toBeTruthy();
    expect(text()).not.toContain('Rule conclusions');
    expect(text()).not.toContain('Ontology structure');
  });

  it('handles singulars', async () => {
    await inferredTab({ ...RUN, inferredAxiomsCount: 2, conclusionCount: 1, structureCount: 1 });
    expect(container.querySelector('[data-testid="control-status"]')?.textContent).toContain('1new fact found');
    expect(container.querySelector('[data-testid="facts-view-standard"]')?.textContent).toBe('Standard reasoning (1)');
    expect(button(/^Add 1 fact to the ontology…/)).toBeTruthy();
  });

  it('shows zero standard reasoning when only facts were produced', async () => {
    await inferredTab({ ...RUN, inferredAxioms: [fact(1), fact(2)], inferredAxiomsCount: 2, conclusionCount: 2, structureCount: 0 });
    expect(container.querySelector('[data-testid="facts-view-standard"]')?.textContent).toBe('Standard reasoning (0)');
    expect(container.querySelector('[data-testid="control-stat-facts"]')?.textContent).toBe('2');
  });

  it('shows zero facts when only class relationships were produced', async () => {
    await inferredTab({ ...RUN, inferredAxioms: [relation], inferredAxiomsCount: 1, conclusionCount: 0, structureCount: 1 });
    expect(container.querySelector('[data-testid="control-stat-facts"]')?.textContent).toBe('0');
    expect(container.querySelector('[data-testid="facts-view-standard"]')?.textContent).toBe('Standard reasoning (1)');
    expect((container.querySelector('[data-testid="control-add"]') as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows the hidden trivial count in the list header instead of repeating it', async () => {
    await inferredTab({ ...RUN, inferredAxiomsCount: 1108, conclusionCount: 118, structureCount: 945, omittedTrivialCount: 45 });
    await click(container.querySelector('[data-testid="facts-view-standard"]') as HTMLElement);
    expect(text()).toContain('45 trivial same-individual facts hidden');
  });

  it('uses the response counts, not the preview length, for a paged run', async () => {
    await inferredTab({ ...RUN, inferredAxioms: [fact(1)], inferredAxiomsCount: 7300, conclusionCount: 6200, structureCount: 1000, omittedTrivialCount: 100, runId: 'run1', counts: { conclusions: 6200, structure: 1000, omittedTrivial: 100 } });
    expect(container.querySelector('[data-testid="control-stat-facts"]')?.textContent).toBe('6,200');
    await click(container.querySelector('[data-testid="facts-view-standard"]') as HTMLElement);
    expect(button(/Facts about individuals \(6,200\)/)).toBeTruthy();
    expect(button(/Class relationships \(1,000\)/)).toBeTruthy();
  });

  const CUT = { ...RUN, truncated: true, inferredAxiomsCount: 1063, conclusionCount: 118, structureCount: 945 };
  const standardView = () => click(container.querySelector('[data-testid="facts-view-standard"]') as HTMLElement);
  const note = () => (text().match(/Showing the first [\d,]+ of [\d,]+; narrow the list with the search or the filter\./) || [''])[0];

  it('counts the cut-off note against the facts when Facts about individuals is chosen', async () => {
    await inferredTab(CUT);
    await standardView();
    await click(button(/^Facts about individuals/));
    expect(note()).toBe('Showing the first 2 of 118; narrow the list with the search or the filter.');
  });

  it('counts the cut-off note against the class relationships when Class relationships is chosen', async () => {
    await inferredTab(CUT);
    await standardView();
    await click(button(/^Class relationships/));
    expect(note()).toBe('Showing the first 1 of 945; narrow the list with the search or the filter.');
  });

  it('counts the cut-off note against everything when Everything is chosen', async () => {
    await inferredTab(CUT);
    await standardView();
    await click(button('Everything'));
    expect(note()).toBe('Showing the first 3 of 1,063; narrow the list with the search or the filter.');
  });

  it('shows no cut-off note when everything in the chosen filter is loaded', async () => {
    await inferredTab({ ...RUN, truncated: true });
    await standardView();
    expect(note()).toBe('');
    expect(text()).not.toContain('SWRL Rule Results');
    expect(text()).not.toContain('Execution Summary');
    expect(text()).not.toContain('individuals classified');
    expect(text()).not.toContain('cut off');
  });
});

describe('model helpers', () => {
  it('turns facts and plain subclass statements into operations and refuses anything else', () => {
    expect(axiomToOp(fact(1))).toEqual({ type: 'addClassAssertion', iri: 'http://x#p1', classIri: 'http://x#Senior' });
    expect(axiomToOp(relation)).toEqual({ type: 'addSubClassOf', iri: 'http://x#Senior', target: 'http://x#Patient' });
    expect(axiomToOp({ axiomType: 'SubClassOf', description: 'SubClassOf(Annotation(<a> "x") <http://x#A> <http://x#B>)' })).toBeNull();
    expect(axiomToOp({ axiomType: 'EquivalentClasses', description: 'EquivalentClasses(<http://x#A> <http://x#B>)' })).toBeNull();
  });

  it('plans and words the confirmation with the cap', () => {
    const plan = inferredPlan({ conclusionCount: 4000, structureCount: 2000 }, true);
    expect(plan).toMatchObject({ total: 6000, willAdd: 5000, overCap: true });
    expect(addConfirmText(plan)).toContain('Only the first 5,000 of 6,000');
    expect(addConfirmText(inferredPlan({ conclusionCount: 1, structureCount: 9 }, false))).toContain('add 1 fact to');
  });

  it('words the log lines', () => {
    expect(loadLogLines(SUMMARY).length).toBeGreaterThan(4);
    expect(runLogLines({ success: true, executedRuleNames: ['a'], executionTimeMs: 3, inferredAxiomsCount: 2 })).toContain('The run took 3 millisecond(s).');
  });
});

async function goMore(label: RegExp) {
  await click(button('More'));
  await click(button(label));
}

async function showAll() {
  await click(container.querySelector('[data-testid="facts-show-all"]') as HTMLElement);
}

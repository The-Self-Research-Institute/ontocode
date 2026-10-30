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
const logText = () => (container.querySelector('[data-testid="control-log"]') as HTMLElement).textContent || '';

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
  axiomType: 'ClassAssertion', category: 'conclusion', description: `ClassAssertion(<http://x#Senior> <http://x#p${n}>)`, readable: `p${n} is a Senior`,
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
  await click(button('Control'));
}

const load = () => click(button('Load ontology and rules'));
const run = () => click(button(/Run rules \(/));
const add = () => click(button('Add inferred facts to ontology'));
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

describe('the three step buttons', () => {
  it('enable in order: load first, then run, then add after a run with results', async () => {
    await open();
    expect(button('Load ontology and rules').disabled).toBe(false);
    expect(button(/Run rules \(/).disabled).toBe(true);
    expect(button('Add inferred facts to ontology').disabled).toBe(true);
    await load();
    expect(button(/Run rules \(/).disabled).toBe(false);
    expect(button('Add inferred facts to ontology').disabled).toBe(true);
    await run();
    expect(button('Add inferred facts to ontology').disabled).toBe(false);
  });

  it('names each button plainly and keeps the Protege name in the tooltip', async () => {
    await open();
    expect(button('Load ontology and rules').title).toContain('OWL+SWRL->Drools');
    expect(button('Load ontology and rules').title).toContain('built fresh');
    expect(button(/Run rules \(/).title).toContain('Run Drools');
    expect(button('Add inferred facts to ontology').title).toContain('Drools->OWL');
  });

  it('keeps Add off when the run found nothing', async () => {
    await open({ ...RUN, inferredAxioms: [], inferredAxiomsCount: 0, conclusionCount: 0, structureCount: 0 });
    await load();
    await run();
    expect(button('Add inferred facts to ontology').disabled).toBe(true);
  });

  it('sends one request for a double click on Load and shows it busy', async () => {
    const gate = deferred();
    await open();
    routes({ '/api/swrl/p1/transfer-summary': gate.promise });
    await act(async () => {
      const b = button('Load ontology and rules');
      b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(api.get.mock.calls.filter(c => String(c[0]).includes('transfer-summary')).length).toBe(1);
    expect(button('Load ontology and rules').disabled).toBe(true);
    expect(button('Load ontology and rules').querySelector('.animate-spin')).toBeTruthy();
    await act(async () => {
      gate.resolve(SUMMARY);
    });
    expect(button('Load ontology and rules').disabled).toBe(false);
  });

  it('sends one request for a double click on Run', async () => {
    await open();
    await load();
    const gate = deferred();
    api.post.mockReturnValue(gate.promise);
    await act(async () => {
      const b = button(/Run rules \(/);
      b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(button(/Run rules \(/).disabled).toBe(true);
    await act(async () => {
      gate.resolve(RUN);
    });
    expect(button(/Run rules \(/).disabled).toBe(false);
  });

  it('runs only the ticked rules from the Run rules button when some are ticked', async () => {
    await open();
    await load();
    const box = container.querySelector('[data-testid="control-rule-a"] input') as HTMLInputElement;
    await click(box);
    expect(button(/Run rules \(/).textContent).toContain('(1)');
    await run();
    expect(api.post.mock.calls[0][0]).toContain('/api/swrl/p1/execute/selected');
  });

  it('asks for the draft copy when editing a private draft', async () => {
    routes();
    api.post.mockResolvedValue(RUN);
    await act(async () => {
      root.render(<SWRLEditor projectId="p1" draftMode />);
    });
    await click(button('Control'));
    await load();
    expect(api.get.mock.calls.some(c => c[0] === '/api/swrl/p1/transfer-summary?draft=true')).toBe(true);
  });
});

describe('the log', () => {
  it('prints the load step in plain words with the real numbers', async () => {
    await open();
    await load();
    const log = logText();
    expect(log).toContain('Ontology and rules checked successfully.');
    expect(log).toContain('Number of rules that will be sent to the rule engine: 8');
    expect(log).toContain('Number of classes that will be sent: 57');
    expect(log).toContain('Number of individuals that will be sent: 45');
    expect(log).toContain('Number of object properties that will be sent: 12');
    expect(log).toContain('Number of data properties that will be sent: 5');
    expect(log).toContain('Total number of axioms that will be sent: 378');
    expect(log).toContain('Skipped rule Count them: This is a SQWRL query. It is not run as a rule');
    expect(log).toContain('The check took 177 millisecond(s).');
    expect(log).toContain('built fresh every time');
    expect(log).toContain('Press the Run rules button to run the rules.');
  });

  it('prints the run step with rules run, skipped rules, the split, the time and the next hint', async () => {
    await open();
    await load();
    await run();
    const log = logText();
    expect(log).toContain('Rules ran successfully.');
    expect(log).toContain('Number of rules run: 2');
    expect(log).toContain('Skipped rule Old: This rule is disabled');
    expect(log).toContain('Number of inferred facts: 3 (2 about individuals, 1 class relationships)');
    expect(log).toContain('The run took 242 millisecond(s).');
    expect(log).toContain('Look at the Inferred Axioms tab');
    expect(log).toContain('Press the Add inferred facts to ontology button');
  });

  it('puts the newest line last, survives a tab switch, and clears on request', async () => {
    await open();
    await load();
    await run();
    const lines = logText().split('\n');
    expect(lines[lines.length - 1]).toContain('Add inferred facts to ontology');
    await click(button('Asserted Axioms'));
    await click(button('Control'));
    expect(logText()).toContain('Rules ran successfully.');
    await click(button('Clear log'));
    expect(logText()).toContain('Nothing has happened yet.');
  });

  it('says plainly when the check or the run fails', async () => {
    await open();
    routes({ '/api/swrl/p1/transfer-summary': Promise.reject(Object.assign(new Error('busy'), { status: 429 })) });
    await load();
    expect(logText()).toContain('The rule engine is busy. Try again in a moment.');
    expect(button(/Run rules \(/).disabled).toBe(true);
    routes();
    await load();
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
    expect(button('Add inferred facts to ontology').disabled).toBe(true);
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
    await click(button('Control'));
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
    expect(button('Add inferred facts to ontology').disabled).toBe(false);
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
    await click(button('Control'));
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
    await click(button('Asserted Axioms'));
    expect(api.get.mock.calls.filter(c => String(c[0]).includes('/axioms/asserted')).length).toBeGreaterThan(before);
  });
});

describe('tabs and wording', () => {
  it('has separate Asserted Axioms and Inferred Axioms tabs and no Results tab', async () => {
    await open();
    const labels = Array.from(container.querySelectorAll('button')).map(b => (b.textContent || '').trim());
    expect(labels).toContain('Inferred Axioms');
    expect(labels).toContain('Asserted Axioms');
    expect(labels).toContain('OWL 2 RL');
    expect(labels).not.toContain('Results');
  });

  it('keeps the count badge on the Inferred Axioms tab and explains each tab in one sentence', async () => {
    await open();
    await load();
    await run();
    const tab = button(/Inferred Axioms/);
    expect(tab.textContent).toContain('3');
    await click(tab);
    expect(text()).toContain('Inferred axioms are worked out by the rules and are not saved in the ontology until you add them.');
    await click(button('Asserted Axioms'));
    expect(text()).toContain('Asserted axioms are the statements typed into the ontology.');
  });
});

describe('inferred axioms summary and labels', () => {
  const summary = () => container.querySelector('[data-testid="results-summary"]')?.textContent;
  const inferredTab = async (r: unknown) => {
    await open(r);
    await load();
    await run();
    await click(button(/Inferred Axioms/));
  };

  it('shows one summary line under the stats and uses the same two labels in the filter chips', async () => {
    await inferredTab({ ...RUN, inferredAxiomsCount: 1108, conclusionCount: 118, structureCount: 945, omittedTrivialCount: 45 });
    expect(summary()).toBe('1,108 produced: 118 facts about individuals from your rules, 945 class relationships from standard reasoning, 45 trivial ones hidden.');
    expect(button(/Facts about individuals \(118\)/)).toBeTruthy();
    expect(button(/Class relationships \(945\)/)).toBeTruthy();
    expect(button('Everything')).toBeTruthy();
    expect(text()).not.toContain('Rule conclusions');
    expect(text()).not.toContain('Ontology structure');
  });

  it('leaves out the hidden part when nothing was hidden and handles singulars', async () => {
    await inferredTab({ ...RUN, inferredAxiomsCount: 2, conclusionCount: 1, structureCount: 1 });
    expect(summary()).toBe('2 produced: 1 fact about an individual from your rules, 1 class relationship from standard reasoning.');
  });

  it('says there are no class relationships when only facts were produced', async () => {
    await inferredTab({ ...RUN, inferredAxioms: [fact(1), fact(2)], inferredAxiomsCount: 2, conclusionCount: 2, structureCount: 0 });
    expect(summary()).toBe('2 produced: 2 facts about individuals from your rules, no class relationships.');
  });

  it('omits the facts part when only class relationships were produced', async () => {
    await inferredTab({ ...RUN, inferredAxioms: [relation], inferredAxiomsCount: 1, conclusionCount: 0, structureCount: 1 });
    expect(summary()).toBe('1 produced: 1 class relationship from standard reasoning.');
  });

  it('uses the response counts, not the preview length, for a paged run', async () => {
    await inferredTab({ ...RUN, inferredAxioms: [fact(1)], inferredAxiomsCount: 7300, conclusionCount: 6200, structureCount: 1000, omittedTrivialCount: 100, runId: 'run1', counts: { conclusions: 6200, structure: 1000, omittedTrivial: 100 } });
    expect(summary()).toBe('7,300 produced: 6,200 facts about individuals from your rules, 1,000 class relationships from standard reasoning, 100 trivial ones hidden.');
    expect(button(/Facts about individuals \(6,200\)/)).toBeTruthy();
    expect(button(/Class relationships \(1,000\)/)).toBeTruthy();
  });

  it('words the cut-off note and no longer shows the SWRL Rule Results panel', async () => {
    await inferredTab({ ...RUN, truncated: true });
    expect(text()).toContain('Showing the first 3 of 3; narrow the list with the search or the filter.');
    expect(text()).not.toContain('SWRL Rule Results');
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
    expect(loadLogLines(SUMMARY).length).toBeGreaterThan(8);
    expect(runLogLines({ success: true, executedRuleNames: ['a'], executionTimeMs: 3, inferredAxiomsCount: 2 })).toContain('Number of inferred facts: 2');
  });
});

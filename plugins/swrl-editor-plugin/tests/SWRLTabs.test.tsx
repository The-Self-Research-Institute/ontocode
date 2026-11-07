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

(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;

const RULE = { id: 'r1', ruleName: 'Senior', ruleText: 'Patient(?p) -> Senior(?p)', comment: '', category: '', enabled: true };

function deferred<T = unknown>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

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

function item(type: string, readable: string) {
  return { axiomType: type, readable, description: readable, category: 'conclusion' };
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

describe('Clone', () => {
  async function selectRule() {
    api.get.mockResolvedValue({ rules: [RULE, { ...RULE, id: 'r2', ruleName: 'Senior (copy)' }] });
    await render(<SWRLEditor projectId="p1" />);
    const row = Array.from(container.querySelectorAll('*')).find(e => e.children.length === 0 && e.textContent === 'Senior') as HTMLElement;
    await click(row);
  }

  it('sends one create request per click with the next free copy name', async () => {
    const save = deferred();
    api.post.mockReturnValue(save.promise);
    await selectRule();
    await act(async () => {
      const b = button('Clone');
      b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(api.post).toHaveBeenCalledTimes(1);
    const [url, body] = api.post.mock.calls[0];
    expect(url).toContain('/api/ontology/p1/rules');
    expect(JSON.stringify(body)).toContain('Senior (copy 2)');
    await act(async () => {
      save.resolve({ id: 'r3', ruleName: 'Senior (copy 2)', ruleText: RULE.ruleText, enabled: true });
    });
    expect(api.post).toHaveBeenCalledTimes(1);
  });

  it('shows the duplicate name error when the server refuses', async () => {
    api.post.mockRejectedValue(Object.assign(new Error('exists'), { status: 409, data: { message: 'exists' } }));
    await selectRule();
    await click(button('Clone'));
    expect(text()).toContain('Another rule already has this name');
  });
});

describe('Control tab', () => {
  it('runs all through the existing call and shows the summary with skipped rules', async () => {
    api.get.mockResolvedValue({ rules: [RULE] });
    api.post.mockResolvedValue({
      success: true,
      status: 'completed_with_warnings',
      statusLabel: 'Completed with warnings',
      inferredAxioms: [],
      inferredAxiomsCount: 3,
      totalRulesExecuted: 1,
      executionTimeMs: 12,
      rounds: 2,
      engineVersions: { reasoner: '1.0' },
      skippedRules: [{ ruleName: 'Bad', reason: 'unsupported atom' }],
    });
    await render(<SWRLEditor projectId="p1" />);
    await click(button('Control'));
    expect(text()).toContain('Nothing has been run yet.');
    await click(button('Load ontology and rules'));
    await click(button(/Run rules \(/));
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(text()).toContain('Completed with warnings');
    expect(text()).toContain('2 rounds');
    expect(text()).toContain('reasoner 1.0');
    expect(text()).toContain('Bad: unsupported atom');
    await click(button(/Clear results/));
    expect(text()).toContain('Nothing has been run yet.');
  });
});

describe('Asserted Axioms tab', () => {
  const page0 = { total: 120, page: 0, pageSize: 50, truncated: false, items: [item('ClassAssertion', 'Rex is a Dog')] };

  it('loads page zero, shows readable text with a type badge, and pages forward', async () => {
    api.get.mockResolvedValue(page0);
    await render(<AssertedAxiomsPanel projectId="p1" draftMode={true} />);
    expect(api.get).toHaveBeenCalledWith('/api/swrl/p1/axioms/asserted?page=0&pageSize=50&draft=true');
    expect(text()).toContain('Rex is a Dog');
    expect(text()).toContain('ClassAssertion');
    expect(text()).toContain('Showing 1-50 of 120');
    await click(button('Next'));
    expect(api.get).toHaveBeenLastCalledWith('/api/swrl/p1/axioms/asserted?page=1&pageSize=50&draft=true');
  });

  it('sends the type filter and resets to the first page', async () => {
    api.get.mockResolvedValue(page0);
    await render(<AssertedAxiomsPanel projectId="p1" draftMode={false} />);
    const select = container.querySelector('select') as HTMLSelectElement;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')!.set!.call(select, 'SubClassOf');
      select.dispatchEvent(new Event('change', { bubbles: true }));
    });
    expect(api.get).toHaveBeenLastCalledWith('/api/swrl/p1/axioms/asserted?page=0&pageSize=50&type=SubClassOf');
  });

  it('notes truncation', async () => {
    api.get.mockResolvedValue({ ...page0, truncated: true, scanned: 20000, maxScan: 20000 });
    await render(<AssertedAxiomsPanel projectId="p1" draftMode={false} />);
    expect(text()).toContain('only part of it was searched');
  });

  it('shows a friendly busy message with the retry delay and retries on request', async () => {
    api.get.mockRejectedValueOnce(Object.assign(new Error('busy'), { status: 429, retryAfter: '4' }));
    await render(<AssertedAxiomsPanel projectId="p1" draftMode={false} />);
    expect(text()).toContain('Try again in about 4 seconds');
    api.get.mockResolvedValue(page0);
    await click(button('Try again'));
    expect(text()).toContain('Rex is a Dog');
    expect(text()).not.toContain('about 4 seconds');
  });

  it('shows the server message for a 400 window error', async () => {
    api.get.mockRejectedValue(Object.assign(new Error('bad'), { status: 400, data: { message: 'Too deep, narrow the search' } }));
    await render(<AssertedAxiomsPanel projectId="p1" draftMode={false} />);
    expect(text()).toContain('Too deep, narrow the search');
  });
});

describe('OWL 2 RL tab', () => {
  const ok = {
    success: true,
    completion: 'completed',
    derivedCount: 7,
    returnedCount: 2,
    truncated: false,
    executionTimeMs: 40,
    omittedTrivialCount: 0,
    typeCounts: { SubClassOf: 1, ClassAssertion: 1 },
    unsupportedRules: [],
    runId: 'run1',
    page: 0,
    pageSize: 50,
    filteredCount: 2,
    totalPages: 2,
    axioms: [
      { axiomType: 'SubClassOf', category: 'structure', readable: 'Structure fact' },
      { axiomType: 'ClassAssertion', category: 'conclusion', readable: 'Conclusion fact' },
    ],
  };

  it('is idle until the button is pressed and mentions the size limit', async () => {
    await render(<Owl2RlPanel projectId="p1" draftMode={false} />);
    expect(api.post).not.toHaveBeenCalled();
    expect(text()).toContain('over about 5,000 axioms');
    expect(button('Compute OWL 2 RL closure').disabled).toBe(false);
  });

  it('shows running, then conclusions before structure, with one request per double click', async () => {
    const run = deferred();
    api.post.mockReturnValue(run.promise);
    await render(<Owl2RlPanel projectId="p1" draftMode={true} />);
    await act(async () => {
      const b = button('Compute OWL 2 RL closure');
      b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post.mock.calls[0][0]).toBe('/api/swrl/p1/owl2rl?pageSize=50&draft=true');
    expect(text()).toContain('Computing…');
    await act(async () => {
      run.resolve(ok);
    });
    expect(text()).toContain('Worked out 7 new facts');
    expect(text().indexOf('Conclusion fact')).toBeLessThan(text().indexOf('Structure fact'));
  });

  it('loads a later page from the run id', async () => {
    api.post.mockResolvedValue(ok);
    await render(<Owl2RlPanel projectId="p1" draftMode={false} />);
    await click(button('Compute OWL 2 RL closure'));
    api.get.mockResolvedValue({ ...ok, page: 1, axioms: [{ axiomType: 'ClassAssertion', category: 'conclusion', readable: 'Second page fact' }] });
    await click(button('Next'));
    expect(api.get).toHaveBeenCalledWith('/api/swrl/p1/owl2rl/runs/run1?page=1&pageSize=50');
    expect(text()).toContain('Second page fact');
  });

  it('offers a recompute when the stored run has expired (410)', async () => {
    api.post.mockResolvedValue(ok);
    await render(<Owl2RlPanel projectId="p1" draftMode={false} />);
    await click(button('Compute OWL 2 RL closure'));
    api.get.mockRejectedValue(Object.assign(new Error('gone'), { status: 410, data: { expired: true } }));
    await click(button('Next'));
    expect(text()).toContain('These results have expired');
    expect(button('Compute again').disabled).toBe(false);
  });

  it('shows the server error text when the run fails with success=false', async () => {
    api.post.mockResolvedValue({ success: false, completion: 'timeout', errorMessage: 'Stopped after 30 seconds' });
    await render(<Owl2RlPanel projectId="p1" draftMode={false} />);
    await click(button('Compute OWL 2 RL closure'));
    expect(text()).toContain('Stopped after 30 seconds');
    expect(text()).not.toContain('Worked out');
  });

  it('shows the busy message on 429', async () => {
    api.post.mockRejectedValue(Object.assign(new Error('busy'), { status: 429, data: { busy: true, message: 'Another computation is running, try in 5 seconds' } }));
    await render(<Owl2RlPanel projectId="p1" draftMode={false} />);
    await click(button('Compute OWL 2 RL closure'));
    expect(text()).toContain('Another computation is running');
  });

  it('explains unsupported rules and truncation', async () => {
    api.post.mockResolvedValue({ ...ok, unsupportedRules: ['prp-spo2'], truncated: true, returnedCount: 1000, derivedCount: 5000 });
    await render(<Owl2RlPanel projectId="p1" draftMode={false} />);
    await click(button('Compute OWL 2 RL closure'));
    expect(text()).toContain('does not apply some standard rules, such as property chains');
    expect(text()).toContain('Not applied: property chains.');
    expect(text()).not.toContain('prp-spo2');
    await click(container.querySelector('[data-testid="owl2rl-unsupported"] button') as HTMLElement);
    expect(text()).toContain('prp-spo2');
    expect(text()).toContain('Showing the first 1,000 of 5,000');
  });
});

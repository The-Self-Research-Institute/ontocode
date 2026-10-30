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

const RULE = { id: 'r1', ruleName: 'Senior', ruleText: 'Patient(?p) -> Senior(?p)', comment: '', category: '', enabled: true };
const RULE2 = { id: 'r2', ruleName: 'Adult', ruleText: 'Patient(?p) -> Adult(?p)', comment: '', category: '', enabled: true };

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

function labelled(label: string) {
  const found = container.querySelector(`button[aria-label="${label}"]`);
  if (!found) throw new Error('button not found: ' + label);
  return found as HTMLButtonElement;
}

async function click(el: HTMLElement) {
  await act(async () => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

async function doubleClick(el: HTMLElement) {
  await act(async () => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

async function mount() {
  await act(async () => {
    root.render(<SWRLEditor projectId="p1" />);
  });
}

function deleteButtons() {
  return Array.from(container.querySelectorAll('button[title="Delete rule"]')) as HTMLButtonElement[];
}

async function confirmDelete() {
  await click(deleteButtons()[0]);
  const confirm = Array.from(container.querySelectorAll('button')).filter(b => (b.textContent || '').trim() === 'Delete').pop()!;
  await doubleClick(confirm);
}

beforeEach(() => {
  Object.values(api).forEach(f => f.mockReset());
  api.get.mockResolvedValue({ rules: [RULE, RULE2] });
  api.post.mockResolvedValue({});
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

describe('deleting a rule', () => {
  it('shows Deleting, sends one DELETE for a double click, and removes the row when the server answers', async () => {
    const gate = deferred();
    api.delete.mockReturnValue(gate.promise);
    await mount();
    await confirmDelete();
    expect(api.delete).toHaveBeenCalledTimes(1);
    expect(text()).toContain('Deleting…');
    expect(deleteButtons()[0].disabled).toBe(true);
    expect(text()).not.toContain('Are you sure');
    await act(async () => {
      gate.resolve({});
    });
    expect(text()).not.toContain('Deleting…');
    expect(text()).not.toContain('Senior');
    expect(text()).toContain('Adult');
    expect(api.delete).toHaveBeenCalledTimes(1);
  });

  it('treats a 404 as already deleted and shows no error', async () => {
    api.delete.mockRejectedValue(Object.assign(new Error('gone'), { status: 404 }));
    await mount();
    await confirmDelete();
    expect(text()).not.toContain('Senior');
    expect(text()).not.toContain('no longer exists');
    expect(text()).not.toContain('Could not delete');
  });

  it('keeps the row and shows the message when the server fails', async () => {
    api.delete.mockRejectedValue(Object.assign(new Error('boom'), { status: 400, data: { errors: ['Server broke'] } }));
    await mount();
    await confirmDelete();
    expect(api.delete).toHaveBeenCalledTimes(1);
    expect(text()).toContain('Senior');
    expect(text()).toContain('Server broke');
    expect(text()).not.toContain('Deleting…');
    expect(deleteButtons()[0].disabled).toBe(false);
  });
});

describe('enable toggle', () => {
  it('locks the row while the server answers and sends one request for a double click', async () => {
    const gate = deferred();
    api.put.mockReturnValue(gate.promise);
    await mount();
    const toggle = container.querySelector('button[title^="Enabled"]') as HTMLButtonElement;
    await doubleClick(toggle);
    expect(api.put).toHaveBeenCalledTimes(1);
    expect(toggle.disabled).toBe(true);
    expect(deleteButtons()[0].disabled).toBe(true);
    await act(async () => {
      gate.resolve({ ...RULE, enabled: false });
    });
    expect(deleteButtons()[0].disabled).toBe(false);
  });
});

function item(type: string, name: string) {
  const readable = `${type}(<http://x#${name}> <http://x#C>)`;
  return { axiomType: type, readable, description: readable, category: 'conclusion' };
}

const AXIOMS = [item('ClassAssertion', 'a'), item('ClassAssertion', 'b'), item('ObjectPropertyAssertion', 'c')];

function groupHeaders() {
  return Array.from(container.querySelectorAll('button[aria-expanded]')).filter(b => /inferred/.test(b.textContent || '')) as HTMLButtonElement[];
}

function expandedStates() {
  return groupHeaders().map(b => b.getAttribute('aria-expanded'));
}

async function runAndOpenResults(response: unknown) {
  api.post.mockResolvedValue(response);
  await mount();
  await click(button('Control'));
  await click(button('Load ontology and rules'));
  await click(button(/Run rules \(/));
  await click(button(/Inferred Axioms/));
}

const plainRun = (axioms = AXIOMS) => ({
  success: true,
  inferredAxioms: axioms,
  inferredAxiomsCount: axioms.length,
  conclusionCount: axioms.length,
  structureCount: 0,
  totalRulesExecuted: 1,
  executionTimeMs: 1,
  executionMode: 'all',
});

describe('result group accordions', () => {
  it('opens every group by default', async () => {
    await runAndOpenResults(plainRun());
    expect(groupHeaders().length).toBe(2);
    expect(expandedStates()).toEqual(['true', 'true']);
  });

  it('keeps a manual collapse, then Collapse all and Expand all change every group', async () => {
    await runAndOpenResults(plainRun());
    await click(groupHeaders()[0]);
    expect(expandedStates()).toEqual(['false', 'true']);
    await click(labelled('Collapse all'));
    expect(expandedStates()).toEqual(['false', 'false']);
    await click(labelled('Expand all'));
    expect(expandedStates()).toEqual(['true', 'true']);
  });

  it('opens the groups again after a new run', async () => {
    await runAndOpenResults(plainRun());
    await click(labelled('Collapse all'));
    expect(expandedStates()).toEqual(['false', 'false']);
    api.post.mockResolvedValue(plainRun([item('ClassAssertion', 'z'), item('ObjectPropertyAssertion', 'y')]));
    await click(button('Control'));
    await click(button('Load ontology and rules'));
    await click(button(/Run rules \(/));
    await click(button(/Inferred Axioms/));
    expect(expandedStates()).toEqual(['true', 'true']);
  });

  it('expands and collapses a paged run whose first page arrives from the server', async () => {
    api.get.mockImplementation((url: string) =>
      url.includes('/axioms') ? Promise.resolve({ axioms: AXIOMS, total: 3, offset: 0, limit: 100 }) : Promise.resolve({ rules: [RULE] })
    );
    await runAndOpenResults({
      success: true,
      inferredAxioms: [{ axiomType: 'SubClassOf', readable: 'SubClassOf(<http://x#A> <http://x#B>)', description: 'SubClassOf(A B)', category: 'structure' }],
      inferredAxiomsCount: 3,
      conclusionCount: 3,
      structureCount: 1,
      runId: 'run1',
      counts: { conclusions: 3, structure: 1 },
      totalRulesExecuted: 1,
      executionTimeMs: 1,
      executionMode: 'all',
    });
    expect(expandedStates()).toEqual(['true', 'true']);
    await click(labelled('Collapse all'));
    expect(expandedStates()).toEqual(['false', 'false']);
    await click(labelled('Expand all'));
    expect(expandedStates()).toEqual(['true', 'true']);
  });

  it('disables the expand and collapse buttons when the search matches no group', async () => {
    await runAndOpenResults(plainRun());
    const search = container.querySelector('input[placeholder^="Search axioms"]') as HTMLInputElement;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(search, 'zzzz-no-match');
      search.dispatchEvent(new Event('input', { bubbles: true }));
    });
    expect(groupHeaders().length).toBe(0);
    expect(labelled('Expand all').disabled).toBe(true);
    expect(labelled('Collapse all').disabled).toBe(true);
  });
});

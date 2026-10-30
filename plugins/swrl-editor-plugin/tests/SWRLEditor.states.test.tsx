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

const RULE = {
  id: 'r1',
  ruleName: 'Senior',
  ruleText: 'Patient(?p) -> Senior(?p)',
  comment: '',
  category: '',
  enabled: true,
};

const RUN_RESULT = { success: true, inferredAxioms: [], inferredAxiomsCount: 0, totalRulesExecuted: 1, executionTimeMs: 1 };

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

async function mount() {
  await act(async () => {
    root.render(<SWRLEditor projectId="p1" />);
  });
}

function text() {
  return container.textContent || '';
}

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

async function doubleClick(el: HTMLElement) {
  await act(async () => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

function setValue(el: HTMLTextAreaElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(el, value);
  el.dispatchEvent(new Event('input', { bubbles: true }));
}

async function openNewRuleWithText() {
  await click(button(/New Rule/));
  const areas = Array.from(container.querySelectorAll('textarea')) as HTMLTextAreaElement[];
  const ruleArea = areas.find(t => !t.placeholder.includes('notes')) || areas[0];
  await act(async () => {
    setValue(ruleArea, 'Patient(?p) -> Adult(?p)');
  });
}

beforeEach(() => {
  Object.values(api).forEach(f => f.mockReset());
  api.get.mockResolvedValue({ rules: [RULE] });
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

describe('rule list loading state', () => {
  it('shows loading text instead of 0 of 0 until the list answers', async () => {
    const list = deferred();
    api.get.mockImplementation((url: string) => (url.endsWith('/rules') ? list.promise : Promise.resolve({})));
    await mount();
    expect(text()).toContain('Loading rules…');
    expect(text()).not.toContain('0 of 0');
    expect(text()).not.toContain('No rules yet');

    await act(async () => {
      list.resolve({ rules: [RULE] });
    });
    expect(text()).toContain('1 of 1 rules enabled');
    expect(text()).not.toContain('Loading rules…');
  });

  it('shows an error with retry when the list fails, never 0 of 0 or No rules yet', async () => {
    api.get.mockImplementation((url: string) =>
      url.endsWith('/rules') ? Promise.reject(Object.assign(new Error('boom'), { status: 500 })) : Promise.resolve({})
    );
    await mount();
    expect(text()).toContain('boom');
    expect(text()).not.toContain('0 of 0');
    expect(text()).not.toContain('No rules yet');

    api.get.mockImplementation(() => Promise.resolve({ rules: [RULE] }));
    await click(button('Retry'));
    expect(text()).toContain('1 of 1 rules enabled');
    expect(text()).not.toContain('boom');
  });
});

describe('save busy state', () => {
  it('shows Saving, sends one request on double click, and locks Cancel meanwhile', async () => {
    const save = deferred();
    api.post.mockReturnValue(save.promise);
    await mount();
    await openNewRuleWithText();

    await doubleClick(button('Save'));
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(button(/Saving…/).disabled).toBe(true);
    expect(button('Cancel').disabled).toBe(true);

    await act(async () => {
      save.resolve({ id: 'r2', ruleName: 'New Rule', ruleText: 'Patient(?p) -> Adult(?p)', enabled: true });
    });
    expect(text()).not.toContain('Saving…');
    expect(api.post).toHaveBeenCalledTimes(1);
  });

  it('shows the duplicate name error and re-enables Save when the server answers 409', async () => {
    api.post.mockRejectedValue(Object.assign(new Error('Rule name already exists'), { status: 409, data: { message: 'Rule name already exists' } }));
    await mount();
    await openNewRuleWithText();
    await click(button('Save'));
    expect(text()).toContain('Another rule already has this name');
    expect(button('Save').disabled).toBe(false);
    expect(text()).not.toContain('Saving…');
  });
});

describe('execute and test busy state', () => {
  it('shows Running and sends one request on double click', async () => {
    const run = deferred();
    api.post.mockReturnValue(run.promise);
    await mount();
    await click(button(/^Run and results/));
    await doubleClick(button('Run rules'));
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(button('Running…').disabled).toBe(true);
    await act(async () => {
      run.resolve(RUN_RESULT);
    });
    expect(text()).not.toContain('Running…');
  });

  it('shows a running state for a rule test and sends one request on double click', async () => {
    const run = deferred();
    api.post.mockReturnValue(run.promise);
    await mount();
    const row = Array.from(container.querySelectorAll('*')).find(e => e.children.length === 0 && e.textContent === 'Senior') as HTMLElement;
    await click(row);
    await doubleClick(button('Try it'));
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(button('Running…').disabled).toBe(true);
    await act(async () => {
      run.resolve(RUN_RESULT);
    });
    expect(text()).not.toContain('Running…');
  });
});

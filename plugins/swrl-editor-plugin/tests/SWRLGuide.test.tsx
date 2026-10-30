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
import { GUIDE_SEEN_KEY, shouldAutoOpenGuide } from '../src/guideStorage';

(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

const RULE = { id: 'r1', ruleName: 'Senior', ruleText: 'Patient(?p) -> Senior(?p)', enabled: true };
const guide = () => container.querySelector('[data-testid="swrl-guide"]');
const testId = (id: string) => container.querySelector(`[data-testid="${id}"]`) as HTMLElement;

async function click(el: HTMLElement) {
  await act(async () => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

async function mount(rules: unknown[]) {
  api.get.mockResolvedValue({ rules });
  await act(async () => {
    root.render(<SWRLEditor projectId="p1" />);
  });
}

beforeEach(() => {
  Object.values(api).forEach(f => f.mockReset());
  window.localStorage.clear();
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

describe('the SWRL Guide button', () => {
  it('is a labelled Guide button that opens the guide and closes it with the close button', async () => {
    await mount([RULE]);
    expect(testId('swrl-guide-open').textContent).toContain('Guide');
    expect(guide()).toBeNull();
    await click(testId('swrl-guide-open'));
    expect(guide()).toBeTruthy();
    expect(guide()?.textContent).toContain('SWRL Guide');
    expect(guide()?.textContent).toContain('Rules work out new facts from what is already in your ontology. Nothing is saved until you add the results.');
    await click(testId('swrl-guide-close'));
    expect(guide()).toBeNull();
  });

  it('closes with Escape and with a click on the backdrop', async () => {
    await mount([RULE]);
    await click(testId('swrl-guide-open'));
    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(guide()).toBeNull();
    await click(testId('swrl-guide-open'));
    await act(async () => {
      guide()?.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, button: 0 }));
    });
    expect(guide()).toBeNull();
  });

  it('has the getting started steps, the cards with examples and the problems table', async () => {
    await mount([RULE]);
    await click(testId('swrl-guide-open'));
    const text = guide()?.textContent ?? '';
    for (const part of ['Getting started', 'Write a rule', 'Customer(?c) ^ numberOfPizzasPurchased(?c, ?n) ^ swrlb:greaterThan(?n, 1) -> hasDiscount(?c, 0.2)', 'Run and read the results', 'Add facts to your ontology', 'Ask a question (query)', 'Person(?p) -> sqwrl:count(?p)', 'See what standard reasoning adds', 'Look up a function', 'If something goes wrong', 'Skipped: This rule is disabled.', 'The server is busy with other work. Try again in a moment.']) {
      expect(text).toContain(part);
    }
  });
});

describe('opening the guide the first time', () => {
  it('opens once when there are no rules and the guide was never seen, and remembers it on close', async () => {
    await mount([]);
    expect(guide()).toBeTruthy();
    await click(testId('swrl-guide-close'));
    expect(guide()).toBeNull();
    expect(window.localStorage.getItem(GUIDE_SEEN_KEY)).not.toBeNull();
  });

  it('does not open when rules already exist', async () => {
    await mount([RULE]);
    expect(guide()).toBeNull();
  });

  it('does not open again once it was seen', async () => {
    window.localStorage.setItem(GUIDE_SEEN_KEY, '1');
    await mount([]);
    expect(guide()).toBeNull();
  });

  it('does not open when storage is blocked, and the button still works', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    await mount([]);
    expect(guide()).toBeNull();
    await click(testId('swrl-guide-open'));
    expect(guide()).toBeTruthy();
    await click(testId('swrl-guide-close'));
    expect(guide()).toBeNull();
  });

  it('does not open when the rules could not be loaded', async () => {
    api.get.mockRejectedValue(new Error('down'));
    await act(async () => {
      root.render(<SWRLEditor projectId="p1" />);
    });
    expect(guide()).toBeNull();
  });

  it('decides from the three conditions only', () => {
    expect(shouldAutoOpenGuide({ loaded: true, failed: false, ruleCount: 0 })).toBe(true);
    expect(shouldAutoOpenGuide({ loaded: false, failed: false, ruleCount: 0 })).toBe(false);
    expect(shouldAutoOpenGuide({ loaded: true, failed: true, ruleCount: 0 })).toBe(false);
    expect(shouldAutoOpenGuide({ loaded: true, failed: false, ruleCount: 2 })).toBe(false);
  });
});

describe('the Import / Export menu and the guide from the Details menu', () => {
  it('lists import and export once in the page header', async () => {
    await mount([RULE]);
    await click(testId('import-export-menu'));
    const list = testId('import-export-menu-list').textContent ?? '';
    for (const label of ['Export to Excel', 'Export to JSON', 'Download Excel template', 'Import rules…', 'Refresh the rule list']) {
      expect(list).toContain(label);
    }
    const labels = Array.from(container.querySelectorAll('button')).map(b => (b.textContent || '').trim());
    expect(labels).not.toContain('Export Excel');
    expect(labels.filter(l => l === 'Import / Export')).toHaveLength(1);
  });

  it('opens the guide at the explained facts card from the Details menu', async () => {
    api.post.mockResolvedValue({ success: true, executionMode: 'all', executedRuleNames: ['Senior'], totalRulesExecuted: 1, executionTimeMs: 3, inferredAxiomsCount: 0, inferredAxioms: [], conclusionCount: 0, structureCount: 0 });
    await mount([RULE]);
    const tab = Array.from(container.querySelectorAll('[role="tab"]')).find(b => (b.textContent || '').includes('Run and results')) as HTMLElement;
    await click(tab);
    await click(testId('control-run'));
    await click(testId('details-menu'));
    await click(testId('details-menu-explained'));
    expect(guide()).toBeTruthy();
    expect(guide()?.querySelector('#swrl-guide-explained')).toBeTruthy();
  });
});

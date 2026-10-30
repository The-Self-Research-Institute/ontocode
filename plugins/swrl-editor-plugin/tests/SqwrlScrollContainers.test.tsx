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

let container: HTMLDivElement;
let root: Root;

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

describe('SQWRL Query tab scrolling', () => {
  it('bounds the results box so long results scroll inside it', async () => {
    api.get.mockResolvedValue({ rules: [] });
    await act(async () => {
      root.render(<SWRLEditor projectId="p1" />);
    });
    const more = container.querySelector('[data-testid="more-menu"]') as HTMLElement;
    await act(async () => {
      more.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    const tab = container.querySelector('[data-testid="more-menu-query"]') as HTMLElement;
    await act(async () => {
      tab.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const panelRoot = Array.from(container.querySelectorAll('div')).find(d => (d.textContent || '').startsWith('SQWRL Query') && d.classList.contains('h-full') && d.classList.contains('flex-col')) as HTMLElement;
    expect(panelRoot.classList.contains('min-h-0')).toBe(true);

    const body = panelRoot.querySelector('textarea')!.parentElement as HTMLElement;
    expect(body.classList.contains('flex-1')).toBe(true);
    expect(body.classList.contains('min-h-0')).toBe(true);
    expect(body.classList.contains('overflow-y-auto')).toBe(true);

    const results = Array.from(body.children).find(c => c.classList.contains('overflow-auto')) as HTMLElement;
    expect(results.classList.contains('flex-1')).toBe(true);
    expect(results.classList.contains('min-h-0')).toBe(true);
  });
});

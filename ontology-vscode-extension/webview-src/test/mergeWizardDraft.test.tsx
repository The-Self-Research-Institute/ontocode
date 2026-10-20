import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const post = vi.fn();
let draftFields: Record<string, string> = {};

vi.mock('../services/apiClient', () => ({
  default: { post: (...args: unknown[]) => post(...args) },
  getBaseUrl: () => 'http://localhost',
}));
vi.mock('../utils/draftScope', () => ({ draftBodyFields: () => draftFields }));

import MergeWizard from '../components/MergeWizard';

const analysis = {
  conflicts: [],
  sourceClassCount: 1,
  sourcePropertyCount: 0,
  targetClassCount: 1,
  targetPropertyCount: 0,
  sourceIndividualCount: 0,
  targetIndividualCount: 0,
};

let host: HTMLDivElement;
let root: Root;

const button = (text: string) =>
  Array.from(host.querySelectorAll('button')).find((b) => b.textContent?.includes(text)) as HTMLButtonElement;

async function runWizard() {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<MergeWizard isOpen onClose={() => undefined} projectId="p1" projectTitle="P" />);
  });
  const input = host.querySelector('input[type="file"]') as HTMLInputElement;
  const file = new File(['<rdf/>'], 'a.owl');
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  await act(async () => {
    input.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await act(async () => { button('Analyze').click(); });
  await act(async () => { button('Configure Merge').click(); });
  await act(async () => { button('Execute Merge').click(); });
}

describe('merge wizard in draft mode', () => {
  beforeEach(() => {
    post.mockReset();
    post.mockImplementation(async (url: string) => (url.includes('/analyze') ? analysis : { success: true, targetProjectId: 'p1' }));
  });
  afterEach(() => {
    act(() => root.unmount());
    host.remove();
  });

  it('sends the draft flag and the draft owner with the analysis and the merge', async () => {
    draftFields = { draft: 'true', userId: 'mongo-1' };
    await runWizard();
    const analyzeUrl = post.mock.calls.find((c) => String(c[0]).includes('/analyze'))![0] as string;
    expect(analyzeUrl).toContain('draft=true');
    expect(analyzeUrl).toContain('userId=mongo-1');
    const execute = post.mock.calls.find((c) => String(c[0]).includes('/execute'))!;
    const form = execute[1] as FormData;
    expect(form.get('draft')).toBe('true');
    expect(form.get('userId')).toBe('mongo-1');
  });

  it('sends nothing extra in public mode', async () => {
    draftFields = {};
    await runWizard();
    const analyzeUrl = post.mock.calls.find((c) => String(c[0]).includes('/analyze'))![0] as string;
    expect(analyzeUrl).not.toContain('draft=');
    const form = post.mock.calls.find((c) => String(c[0]).includes('/execute'))![1] as FormData;
    expect(form.get('draft')).toBeNull();
  });
});

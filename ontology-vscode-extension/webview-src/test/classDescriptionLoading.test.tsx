import React, { act, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ClassEditor from '../components/details/ClassEditor';
import apiClient from '../services/apiClient';
import type { TreeNode } from '../types';

vi.mock('../services/apiClient', () => ({ default: { get: vi.fn() } }));
vi.mock('../custom-hook/useAuth', () => ({ useAuth: () => ({ user: { userId: 'reader' } }) }));
vi.mock('../contexts/CollaborationContext', () => ({
  useCollaboration: () => ({ state: { locks: new Map(), activeUsers: new Map() } }),
}));

const subject = { id: 'urn:test:Child', label: 'Child', annotations: {}, children: [] } as TreeNode;
const parent = { iri: 'urn:test:Parent', label: 'Parent' };
const get = vi.mocked(apiClient.get);

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(complete => { resolve = complete; });
  return { promise, resolve };
}

function Fixture() {
  const [item, setItem] = useState(subject);
  return <ClassEditor item={item} projectId="test" onUpdate={setItem}
    onAddAnnotation={() => {}} onEditAnnotation={() => {}} onDeleteAnnotation={() => {}}
    objectProperties={[{ id: 'urn:test:property' }]} />;
}

describe('progressive class Description', () => {
  let host: HTMLDivElement;
  let root: ReturnType<typeof createRoot>;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    get.mockReset();
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
  });

  const callsFor = (part: string) => get.mock.calls.filter(([path]) => String(path).includes(part));

  async function clickTab(title: string) {
    const button = [...host.querySelectorAll('button')].find(el => el.textContent?.trim() === title);
    expect(button).toBeDefined();
    await act(async () => { button!.click(); });
  }

  it('shows annotations and direct parents while axioms are pending, without fetching Usage', async () => {
    const parents = deferred<unknown>();
    const axioms = deferred<unknown>();
    get.mockImplementation(async path => {
      if (path.includes('view=parents')) return parents.promise;
      if (path.includes('/classes/details/')) return axioms.promise;
      if (path.includes('/classes/annotations/')) return { annotations: { 'rdfs:comment': 'Early comment' } };
      return [];
    });

    await act(async () => { root.render(<Fixture />); });
    expect(host.textContent).toContain('Early comment');
    expect(host.textContent).toContain('Loading direct parents');
    expect(callsFor('/classes/details/')).toHaveLength(1);
    expect(callsFor('/classes/usage/')).toHaveLength(0);

    await act(async () => { parents.resolve([parent]); });
    expect(host.querySelector('[aria-label="Direct parents"]')?.textContent).toContain('Parent');
    expect(host.textContent).toContain('Early comment');
    expect(host.textContent).toContain('Loading axioms and restrictions');
    expect(callsFor('/classes/details/')).toHaveLength(2);
    expect(callsFor('/classes/usage/')).toHaveLength(0);

    await act(async () => { axioms.resolve({ subClassOfAxioms: [] }); });
    expect(host.textContent).toContain('SubClass Of');
    expect(host.textContent).not.toContain('Loading axioms and restrictions');
  });

  it('loads Usage on demand and reuses it when revisiting the tab', async () => {
    const usage = deferred<unknown>();
    get.mockImplementation(async path => {
      if (path.includes('/classes/usage/')) return usage.promise;
      if (path.includes('view=parents')) return [parent];
      if (path.includes('/classes/details/')) return {};
      if (path.includes('/classes/annotations/')) return { annotations: {} };
      return [];
    });

    await act(async () => { root.render(<Fixture />); });
    expect(callsFor('/classes/usage/')).toHaveLength(0);
    await clickTab('Usage');
    expect(callsFor('/classes/usage/')).toHaveLength(1);
    await act(async () => { usage.resolve([{ type: 'subclass', subject: 'An example use' }]); });
    expect(host.textContent).toContain('An example use');
    await clickTab('Description');
    await clickTab('Usage');
    expect(callsFor('/classes/usage/')).toHaveLength(1);
  });
});

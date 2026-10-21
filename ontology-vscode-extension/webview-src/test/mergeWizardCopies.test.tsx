import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const post = vi.fn();

vi.mock('../services/apiClient', () => ({
  default: { post: (...args: unknown[]) => post(...args) },
  getBaseUrl: () => 'http://localhost',
}));
vi.mock('../utils/draftScope', () => ({ draftBodyFields: () => ({}) }));

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

const OBO = 'http://purl.obolibrary.org/obo/';

let host: HTMLDivElement;
let root: Root;

const button = (text: string) =>
  Array.from(host.querySelectorAll('button')).find((b) => b.textContent?.includes(text)) as HTMLButtonElement;

async function runWizard(mergeResult: Record<string, unknown>) {
  post.mockImplementation(async (url: string) => (url.includes('/analyze') ? analysis : mergeResult));
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

const listed = () =>
  Array.from(host.querySelectorAll('[data-testid="merge-copies"] li')).map((li) => li.textContent);

describe('merge wizard summary of kept copies', () => {
  beforeEach(() => {
    post.mockReset();
  });
  afterEach(() => {
    act(() => root.unmount());
    host.remove();
  });

  it('lists every copy by its label and new name under the renamed count', async () => {
    await runWizard({
      success: true,
      targetProjectId: 'p1',
      entitiesRenamed: 3,
      copies: [
        { originalIri: `${OBO}BFO_0000001`, newIri: `${OBO}BFO_0000001_imported`, label: 'entity_imported', kind: 'Class' },
        { originalIri: 'http://ex.org/p#hasTopping', newIri: 'http://ex.org/p#hasTopping_imported', label: 'has topping_imported', kind: 'ObjectProperty' },
        { originalIri: 'http://ex.org/p#Mystery', newIri: 'http://ex.org/p#Mystery_imported', label: 'Mystery_imported', kind: 'Class' },
      ],
    });

    expect(host.textContent).toContain('Entities Renamed:');
    expect(listed()).toEqual([
      'entity_imported (BFO_0000001_imported)class',
      'has topping_imported (hasTopping_imported)object property',
      'Mystery_importedclass',
    ]);
    expect(host.textContent).not.toContain('more');
  });

  it('says how many copies are not listed when the list was cut short', async () => {
    await runWizard({
      success: true,
      targetProjectId: 'p1',
      entitiesRenamed: 1500,
      copies: [{ originalIri: `${OBO}A`, newIri: `${OBO}A_imported`, label: 'a_imported', kind: 'Class' }],
    });

    expect(listed()).toHaveLength(1);
    expect(host.textContent).toContain('and 1499 more');
  });

  it('shows no copy list when nothing was copied', async () => {
    await runWizard({ success: true, targetProjectId: 'p1', entitiesRenamed: 0, copies: [] });

    expect(host.querySelector('[data-testid="merge-copies"]')).toBeNull();
  });

  it('warns when the merged file could not be imported again', async () => {
    await runWizard({ success: true, targetProjectId: 'p1', copies: [], reimportSkipped: true });

    expect(host.textContent).toContain('the class tree will not show this merge');
  });
});

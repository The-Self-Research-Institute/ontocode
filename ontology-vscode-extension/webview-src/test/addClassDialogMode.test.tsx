import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

const desktopFlag = vi.hoisted(() => ({ value: false }));
vi.mock('../utils/desktop', () => ({ isDesktop: () => desktopFlag.value, isRealVSCode: () => false }));


import AddClassDialog from '../components/dialogs/AddClassDialog';

(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;

function render(syncMode: 'private' | 'public') {
  const host = document.createElement('div');
  document.body.appendChild(host);
  const root = createRoot(host);
  act(() => {
    root.render(
      <AddClassDialog isOpen onClose={() => {}} onCreate={() => {}} type="subclass" parentLabel="Thing" syncMode={syncMode} />,
    );
  });
  return host;
}

afterEach(() => {
  desktopFlag.value = false;
  document.body.innerHTML = '';
});

describe('AddClassDialog mode banner', () => {
  it('shows the public wording on the web', () => {
    expect(render('public').textContent).toContain('Public Live — class will be applied to the shared ontology immediately.');
  });

  it('shows the private wording on the web', () => {
    expect(render('private').textContent).toContain('Private Draft — class will be saved to your draft until you publish.');
  });

  it('shows the local wording on desktop for both sync modes', () => {
    desktopFlag.value = true;
    for (const mode of ['public', 'private'] as const) {
      const text = render(mode).textContent || '';
      expect(text).toContain('Local Mode — Changes will be applied to your local ontology.');
      expect(text).not.toContain('Public Live');
      expect(text).not.toContain('Private Draft');
    }
  });
});

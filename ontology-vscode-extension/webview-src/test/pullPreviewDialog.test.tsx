import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const post = vi.fn();

vi.mock('../services/apiClient', () => ({
  default: { post: (...args: unknown[]) => post(...args) },
  getBaseUrl: () => 'http://localhost',
}));

import PullPreviewDialog from '../components/PullPreviewDialog';

let host: HTMLDivElement;
let root: Root;

const button = (text: string) =>
  Array.from(host.querySelectorAll('button')).find((b) => b.textContent?.includes(text)) as HTMLButtonElement | undefined;

async function openDialog(analysis: unknown, applied: unknown = { success: true, mergedCount: 1 }) {
  post.mockImplementation(async (url: string) => (url.includes('/analyze') ? analysis : applied));
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(
      <PullPreviewDialog isOpen onClose={() => undefined} onConfirm={() => undefined} projectId="p1" userId="u1" />,
    );
  });
}

const iriChange = {
  entityIri: 'header:ontologyIri',
  entityLabel: 'Ontology IRI',
  kind: 'header',
  field: 'ontologyIri',
  publicAxioms: 'Ontology IRI: http://ex.org/pizza → http://ex.org/pizza-v2',
};

describe('pull dialog with ontology header changes', () => {
  beforeEach(() => {
    post.mockReset();
  });
  afterEach(() => {
    act(() => root.unmount());
    host.remove();
  });

  it('shows an ontology IRI change from Public instead of saying the draft is up to date', async () => {
    await openDialog({
      hasChanges: true,
      safeChanges: [
        iriChange,
        { entityIri: 'other-axioms', entityLabel: 'Other axioms', kind: 'other_axioms', publicAxioms: '1 added, 0 removed' },
      ],
      conflicts: [],
    });

    expect(host.textContent).not.toContain('already up to date');
    expect(host.textContent).toContain('Ontology IRI: http://ex.org/pizza → http://ex.org/pizza-v2');
    expect(host.textContent).toContain('Other axioms');
    expect(host.textContent).toContain('1 added, 0 removed');

    await act(async () => { button('Pull changes into my draft')!.click(); });
    const apply = post.mock.calls.find((c) => String(c[0]).includes('/apply'))!;
    expect(apply[1]).toEqual({ resolutions: {} });
  });

  it('asks the user to choose when both sides changed the ontology IRI', async () => {
    await openDialog({
      hasChanges: true,
      hasConflicts: true,
      safeChanges: [],
      conflicts: [{
        ...iriChange,
        conflict: true,
        yourAxioms: 'Ontology IRI: http://ex.org/pizza → http://ex.org/pizza-mine',
      }],
    });

    expect(host.textContent).toContain('pizza-mine');
    expect(button('Keep both')).toBeUndefined();
    expect(button('Apply my choices')!.disabled).toBe(true);

    await act(async () => { button('Keep mine')!.click(); });
    expect(button('Apply my choices')!.disabled).toBe(false);

    await act(async () => { button('Apply my choices')!.click(); });
    const apply = post.mock.calls.find((c) => String(c[0]).includes('/apply'))!;
    expect(apply[1]).toEqual({ resolutions: { 'header:ontologyIri': 'keep_draft' } });
  });

  it('sends take_public when the user picks Public’s IRI', async () => {
    await openDialog({
      hasConflicts: true,
      safeChanges: [],
      conflicts: [{ ...iriChange, conflict: true, yourAxioms: 'Ontology IRI: a → b' }],
    });

    await act(async () => { button("Use Public's version")!.click(); });
    await act(async () => { button('Apply my choices')!.click(); });

    const apply = post.mock.calls.find((c) => String(c[0]).includes('/apply'))!;
    expect(apply[1]).toEqual({ resolutions: { 'header:ontologyIri': 'take_public' } });
  });

  it('shows the reason when the server still needs a choice', async () => {
    await openDialog(
      { hasChanges: true, safeChanges: [iriChange], conflicts: [] },
      { success: false, needsChoice: true, message: 'Choose which Ontology IRI to keep' },
    );

    await act(async () => { button('Pull changes into my draft')!.click(); });

    expect(host.textContent).toContain('Choose which Ontology IRI to keep');
    expect(host.textContent).not.toContain('Pull complete');
  });
});

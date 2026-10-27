import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it, vi } from 'vitest';
import UndoRunPanel from '../components/UndoRunPanel';
import UndoConflictDialog from '../components/UndoConflictDialog';
import { plainUndoReason } from '../utils/undoReason';

const status: any = { kind: 'undo', phase: 'paused', pending: 2, current: 'Code View save', item: 'Code View save (21 changes)', reason: 'Conflict', message: 'Other items still refer to Gluten Free Pizza 1', finishedItems: [] };
const menu: any = { resume: vi.fn(), stopPending: vi.fn(), openHistory: vi.fn(), redoHint: 'Ctrl+Y redoes the last undo' };

function mount(node: React.ReactElement) {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  const host = document.createElement('div');
  document.body.appendChild(host);
  const root = createRoot(host);
  act(() => root.render(node));
  return host;
}

const find = (host: HTMLElement, text: string) =>
  Array.from(host.querySelectorAll('button,div,dd')).find((el) => el.textContent === text) as HTMLElement;

describe('undo panels', () => {
  it('keeps the buttons on one line and the hint on its own line', () => {
    const host = mount(<UndoRunPanel status={status} menu={menu} />);
    const stop = find(host, 'Stop pending');
    expect(stop.className).toContain('whitespace-nowrap');
    expect(stop.parentElement).not.toBe(find(host, menu.redoHint).parentElement);
    act(() => find(host, 'Continue').click());
    expect(menu.resume).toHaveBeenCalled();
  });

  it('shows source, affected and restoring and opens the affected item', () => {
    const seen: any[] = [];
    const listener = (e: Event) => seen.push((e as CustomEvent).detail);
    window.addEventListener('collaboration:navigate-to-entity', listener);
    const detailed = {
      ...status,
      phase: 'running',
      current: 'Change domain of hasParent',
      currentDetail: { source: 'Entities → Properties', affected: 'hasParent → Domain', restoring: 'Animal → Person', entityIRI: 'http://x#hasParent' },
    };
    const host = mount(<UndoRunPanel status={detailed} menu={menu} />);
    expect(host.textContent).toContain('SourceEntities → Properties');
    expect(host.textContent).toContain('AffectedhasParent → Domain');
    expect(host.textContent).toContain('RestoringAnimal → Person');
    act(() => find(host, 'View affected item').click());
    window.removeEventListener('collaboration:navigate-to-entity', listener);
    expect(seen[0].entityIRI).toBe('http://x#hasParent');
  });

  it('hides the detail rows and the link when the server sent nothing', () => {
    const host = mount(<UndoRunPanel status={{ ...status, phase: 'running' }} menu={menu} />);
    expect(host.textContent).not.toContain('Source');
    expect(find(host, 'View affected item')).toBeUndefined();
  });

  it('names the entry in the dialog title and explains the reason', () => {
    const host = mount(<UndoConflictDialog status={status} menu={menu} onClose={() => undefined} />);
    expect(host.textContent).toContain("Can't undo this change: Code View save");
    expect(host.textContent).toContain('Other items still use it');
  });

  it('asks to confirm before undoing a change by someone else and then confirms through the menu', () => {
    const confirmOthers = vi.fn();
    const foreign = { ...status, foreignTarget: { username: 'Sam', label: 'Created class Pizza', changes: 3 } };
    const host = mount(<UndoConflictDialog status={foreign} menu={{ ...menu, confirmOthers }} onClose={() => undefined} />);
    expect(host.textContent).toContain("Undo someone else's change?");
    expect(host.textContent).toContain('Created class Pizza was made by Sam and is part of an edit with 3 changes');
    act(() => find(host, 'Undo it anyway').click());
    expect(confirmOthers).toHaveBeenCalled();
  });

  it('falls back to the server text for unknown reasons', () => {
    expect(plainUndoReason('Something odd')).toBe('Something odd');
    expect(plainUndoReason(undefined)).toBe('');
  });
});

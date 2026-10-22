import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { mount } from './hookHarness';
import UndoEditMenuItems from '../components/UndoEditMenuItems';
import { chipText } from '../components/UndoRunChip';
import type { UndoMenu } from '../hooks/useOntologyUndoRedo';
import type { UndoRunStatus } from '../hooks/useUndoRunStatus';

const menu = (extra: Partial<UndoMenu> = {}): UndoMenu => ({
  undo: vi.fn(),
  redo: vi.fn(),
  canUndo: true,
  canRedo: false,
  undoLabel: 'Change domain of hasParent',
  stopPending: vi.fn(),
  resume: vi.fn(),
  openHistory: vi.fn(),
  ...extra,
});

const status = (extra: Partial<UndoRunStatus>): UndoRunStatus =>
  ({ phase: 'running', completed: 2, running: true, pending: 7, slow: false, finishedItems: [], ...extra }) as UndoRunStatus;

describe('undo edit menu', () => {
  it('names the ontology target and keeps fixed entries', () => {
    const view = mount();
    const onClose = vi.fn();
    const m = menu();
    view.render(<UndoEditMenuItems menu={m} onClose={onClose} />);
    const buttons = Array.from(view.container.querySelectorAll('button'));
    expect(buttons.map((b) => b.textContent)).toEqual([
      'Undo text editCtrl+Z',
      'Undo ontology change: Change domain of hasParent',
      'Redo ontology change: nothing to redoCtrl+Y',
    ]);
    expect(buttons[0].disabled).toBe(true);
    expect(buttons[2].disabled).toBe(true);
    buttons[1].click();
    expect(m.undo).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
    view.unmount();
  });

  it('enables text undo while a text field has focus', () => {
    const view = mount();
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();
    view.render(<UndoEditMenuItems menu={menu()} onClose={() => undefined} />);
    expect(view.container.querySelector('button')?.disabled).toBe(false);
    input.remove();
    view.unmount();
  });
});

describe('chip text', () => {
  it('names the reason in words', () => {
    expect(chipText(status({}))).toBe('2 completed · 1 running · 7 pending');
    expect(chipText(status({ slow: true }))).toContain('Taking longer than usual…');
    expect(chipText(status({ phase: 'paused', reason: 'Conflict', pending: 6 }))).toBe('Paused · Conflict · 6 pending');
    expect(chipText(status({ phase: 'paused', reason: 'Result unknown', pending: 1 }))).toBe('Paused · Result unknown · 1 pending');
    expect(chipText(status({ phase: 'checking' }))).toContain('Checking result…');
    expect(chipText(status({ phase: 'finished', summary: '3 change sets undone · 8 changes' }))).toBe('3 change sets undone · 8 changes');
  });
});

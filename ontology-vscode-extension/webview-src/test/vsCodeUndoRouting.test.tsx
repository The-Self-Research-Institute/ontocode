import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../utils/desktop', () => ({ isRealVSCode: () => true, isDesktop: () => false }));

import { useVsCodeUndoRouting } from '../hooks/useVsCodeUndoRouting';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const postMessage = vi.fn();
const onUndo = vi.fn();
const onRedo = vi.fn();

function Probe() {
  useVsCodeUndoRouting({ onUndo, onRedo });
  return (
    <div>
      <input data-testid="text" />
      <button data-testid="plain">go</button>
    </div>
  );
}

async function flush() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 5));
  });
}

describe('useVsCodeUndoRouting', () => {
  let root: Root;
  let host: HTMLElement;

  beforeEach(() => {
    postMessage.mockReset();
    onUndo.mockReset();
    onRedo.mockReset();
    (window as unknown as { vscode: unknown }).vscode = { postMessage };
    vi.spyOn(document, 'hasFocus').mockReturnValue(true);
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
    vi.restoreAllMocks();
  });

  it('reports ontology focus when nothing editable is focused', async () => {
    await act(async () => root.render(<Probe />));
    await flush();
    expect(postMessage).toHaveBeenCalledWith({ type: 'focusContext', ontology: true });
  });

  it('reports text focus when an input is focused and ontology focus when it blurs', async () => {
    await act(async () => root.render(<Probe />));
    await flush();
    const input = host.querySelector('input') as HTMLInputElement;
    await act(async () => input.focus());
    await flush();
    expect(postMessage).toHaveBeenLastCalledWith({ type: 'focusContext', ontology: false });
    await act(async () => input.blur());
    await flush();
    expect(postMessage).toHaveBeenLastCalledWith({ type: 'focusContext', ontology: true });
  });

  it('reports no ontology focus while a dialog is open', async () => {
    await act(async () => root.render(<Probe />));
    await flush();
    const dialog = document.createElement('div');
    dialog.setAttribute('role', 'dialog');
    await act(async () => {
      document.body.appendChild(dialog);
    });
    await flush();
    expect(postMessage).toHaveBeenLastCalledWith({ type: 'focusContext', ontology: false });
    dialog.remove();
  });

  it('runs undo and redo when the extension asks for them', async () => {
    await act(async () => root.render(<Probe />));
    await act(async () => {
      window.dispatchEvent(new MessageEvent('message', { data: { type: 'ontologyUndo' } }));
      window.dispatchEvent(new MessageEvent('message', { data: { type: 'ontologyRedo' } }));
    });
    expect(onUndo).toHaveBeenCalledTimes(1);
    expect(onRedo).toHaveBeenCalledTimes(1);
  });

  it('clears ontology focus when it unmounts', async () => {
    await act(async () => root.render(<Probe />));
    await flush();
    await act(async () => root.render(<div />));
    expect(postMessage).toHaveBeenLastCalledWith({ type: 'focusContext', ontology: false });
  });
});

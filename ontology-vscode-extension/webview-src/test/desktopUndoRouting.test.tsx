import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useDesktopUndoRouting } from '../hooks/useDesktopUndoRouting';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const onUndo = vi.fn();
const onRedo = vi.fn();
let menuCallback: ((kind: string) => void) | undefined;

function Probe() {
  useDesktopUndoRouting({ onUndo, onRedo });
  return <input data-testid="text" />;
}

describe('useDesktopUndoRouting', () => {
  let root: Root;
  let host: HTMLElement;

  beforeEach(() => {
    onUndo.mockReset();
    onRedo.mockReset();
    menuCallback = undefined;
    (window as unknown as { electronAPI: unknown }).electronAPI = {
      onMenuUndoRedo: (callback: (kind: string) => void) => {
        menuCallback = callback;
      },
    };
    (document as unknown as { execCommand: unknown }).execCommand = vi.fn();
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
    (window as unknown as { electronAPI?: unknown }).electronAPI = undefined;
  });

  it('undoes and redoes the ontology when nothing editable is focused', async () => {
    await act(async () => root.render(<Probe />));
    menuCallback?.('undo');
    menuCallback?.('redo');
    expect(onUndo).toHaveBeenCalledTimes(1);
    expect(onRedo).toHaveBeenCalledTimes(1);
    expect(document.execCommand).not.toHaveBeenCalled();
  });

  it('leaves undo to the text field when one is focused', async () => {
    await act(async () => root.render(<Probe />));
    (host.querySelector('input') as HTMLInputElement).focus();
    menuCallback?.('undo');
    expect(document.execCommand).toHaveBeenCalledWith('undo');
    expect(onUndo).not.toHaveBeenCalled();
  });

  it('ignores unknown menu actions', async () => {
    await act(async () => root.render(<Probe />));
    menuCallback?.('cut');
    expect(onUndo).not.toHaveBeenCalled();
    expect(onRedo).not.toHaveBeenCalled();
  });
});

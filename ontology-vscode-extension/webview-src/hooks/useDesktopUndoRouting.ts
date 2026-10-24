import { useEffect, useRef } from 'react';
import { isTextElement } from '../utils/textEditTarget';
import { isModalOpen } from '../utils/modalOpen';

interface DesktopApi {
  onMenuUndoRedo?: (callback: (kind: string) => void) => void | (() => void);
}

export function hasDesktopUndoMenu(): boolean {
  const api = typeof window !== 'undefined' ? (window as unknown as { electronAPI?: DesktopApi }).electronAPI : undefined;
  return typeof api?.onMenuUndoRedo === 'function';
}


interface Handlers {
  onUndo: () => void;
  onRedo: () => void;
}

export function useDesktopUndoRouting({ onUndo, onRedo }: Handlers): void {
  const handlers = useRef<Handlers>({ onUndo, onRedo });
  handlers.current = { onUndo, onRedo };

  useEffect(() => {
    if (!hasDesktopUndoMenu()) {
      return;
    }
    const api = (window as unknown as { electronAPI: DesktopApi }).electronAPI;
    const unsubscribe = api.onMenuUndoRedo?.((kind) => {
      if (kind !== 'undo' && kind !== 'redo') {
        return;
      }
      if (isTextElement(document.activeElement)) {
        document.execCommand(kind);
      } else if (isModalOpen()) {
        return;
      } else if (kind === 'undo') {
        handlers.current.onUndo();
      } else {
        handlers.current.onRedo();
      }
    });
    return () => {
      if (typeof unsubscribe === 'function') unsubscribe();
    };
  }, []);
}

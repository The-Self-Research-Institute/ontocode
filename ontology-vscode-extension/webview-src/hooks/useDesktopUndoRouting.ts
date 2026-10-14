import { useEffect, useRef } from 'react';

interface DesktopApi {
  onMenuUndoRedo?: (callback: (kind: string) => void) => void;
}

export function hasDesktopUndoMenu(): boolean {
  const api = typeof window !== 'undefined' ? (window as unknown as { electronAPI?: DesktopApi }).electronAPI : undefined;
  return typeof api?.onMenuUndoRedo === 'function';
}

function textFocused(): boolean {
  const active = document.activeElement as HTMLElement | null;
  return !!active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable);
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
    api.onMenuUndoRedo?.((kind) => {
      if (kind !== 'undo' && kind !== 'redo') {
        return;
      }
      if (textFocused()) {
        document.execCommand(kind);
      } else if (kind === 'undo') {
        handlers.current.onUndo();
      } else {
        handlers.current.onRedo();
      }
    });
  }, []);
}

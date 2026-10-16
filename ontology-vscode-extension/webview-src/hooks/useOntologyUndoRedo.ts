import { useCallback, useEffect, useRef, useState } from 'react';
import undoRedoService, { type UndoState } from '../services/undoRedoService';
import { notificationService } from '../services/notificationService';
import { isRealVSCode } from '../utils/desktop';
import { useVsCodeUndoRouting } from './useVsCodeUndoRouting';
import { hasDesktopUndoMenu, useDesktopUndoRouting } from './useDesktopUndoRouting';

interface Options {
  projectId: string | null | undefined;
}

function isTextTarget(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  if (!element) {
    return false;
  }
  return element.tagName === 'INPUT' || element.tagName === 'TEXTAREA' || element.isContentEditable;
}

export function useOntologyUndoRedo({ projectId }: Options) {
  const [busy, setBusy] = useState(false);
  const [state, setState] = useState<UndoState | undefined>(undefined);
  const stateRef = useRef<UndoState | undefined>(undefined);
  const projectRef = useRef(projectId);
  projectRef.current = projectId;

  const apply = useCallback((next: UndoState | undefined) => {
    stateRef.current = next;
    setState(next);
  }, []);

  const refresh = useCallback(async () => {
    if (!projectId) {
      apply(undefined);
      return;
    }
    const next = await undoRedoService.fetchState(projectId);
    if (projectRef.current === projectId) {
      apply(next);
    }
  }, [projectId, apply]);

  const run = useCallback(
    async (kind: 'undo' | 'redo') => {
      if (!projectId || undoRedoService.isBusy()) {
        return;
      }
      setBusy(true);
      const label = kind === 'undo' ? 'Undo' : 'Redo';
      const step = kind === 'undo' ? stateRef.current?.undo : stateRef.current?.redo;
      const result =
        kind === 'undo' ? await undoRedoService.undo(projectId, step) : await undoRedoService.redo(projectId, step);
      setBusy(false);
      if (projectRef.current !== projectId) {
        return;
      }
      if (result.state) {
        apply(result.state);
      } else {
        void refresh();
      }
      if (result.success) {
        const what = result.label ? `: ${result.label}` : '';
        notificationService.success(label, `${kind === 'undo' ? 'Undid' : 'Redid'}${what}`);
      } else if (result.error) {
        notificationService.info(label, result.error);
      }
    },
    [projectId, apply, refresh],
  );

  useEffect(() => {
    apply(undefined);
    void refresh();
    window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, [projectId, apply, refresh]);

  useEffect(() => {
    if (!projectId || isRealVSCode() || hasDesktopUndoMenu()) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.altKey || isTextTarget(event.target)) {
        return;
      }
      const key = event.key.toLowerCase();
      const isUndo = key === 'z' && !event.shiftKey;
      const isRedo = key === 'y' || (key === 'z' && event.shiftKey);
      if (!isUndo && !isRedo) {
        return;
      }
      event.preventDefault();
      if (event.repeat) {
        return;
      }
      void run(isUndo ? 'undo' : 'redo');
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [projectId, run]);

  const undo = useCallback(() => run('undo'), [run]);
  const redo = useCallback(() => run('redo'), [run]);
  useVsCodeUndoRouting({ onUndo: undo, onRedo: redo });
  useDesktopUndoRouting({ onUndo: undo, onRedo: redo });

  return {
    busy,
    state,
    canUndo: !!projectId && !busy && state?.undo.available !== false,
    canRedo: !!projectId && !busy && !!state?.redo.available,
    undoLabel: state?.undo.available ? state.undo.label : undefined,
    undo,
    redo,
    refresh,
  };
}

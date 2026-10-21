import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import undoRedoService, { type UndoState } from '../services/undoRedoService';
import { undoQueue } from '../services/undoQueue';
import { notificationService } from '../services/notificationService';
import { isRealVSCode } from '../utils/desktop';
import { isTextElement } from '../utils/textEditTarget';
import { useVsCodeUndoRouting } from './useVsCodeUndoRouting';
import { hasDesktopUndoMenu, useDesktopUndoRouting } from './useDesktopUndoRouting';
import { useCommittedChangeRefresh } from './useCommittedChangeRefresh';

interface Options {
  projectId: string | null | undefined;
  draft?: boolean;
  onOpenHistory?: () => void;
}

const PUBLISH_OPS = ['draftPublish', 'draftPrApproved'];

export interface UndoMenu {
  undo: () => void;
  redo: () => void;
  canUndo: boolean;
  canRedo: boolean;
  undoLabel?: string;
  redoLabel?: string;
  redoHint?: string;
  stopPending: () => void;
  resume: () => void;
  openHistory: () => void;
  refresh?: () => void;
}

function redoHintFor(phase: string): string | undefined {
  if (phase === 'running') return 'Redo is off while an undo run is active';
  if (phase === 'paused' || phase === 'checking') return 'Redo is off until the undo run is settled';
  return undefined;
}

export function useOntologyUndoRedo({ projectId, draft, onOpenHistory }: Options) {
  const snap = useSyncExternalStore(undoQueue.subscribe, undoQueue.getSnapshot);
  const [state, setState] = useState<UndoState | undefined>(undefined);
  const stateRef = useRef<UndoState | undefined>(undefined);
  const projectRef = useRef(projectId);
  projectRef.current = projectId;
  const historyRef = useRef(onOpenHistory);
  historyRef.current = onOpenHistory;
  const lastPhase = useRef(snap.phase);
  const toastedRun = useRef(0);

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
      if (!projectId) {
        return;
      }
      const step = kind === 'undo' ? stateRef.current?.undo : stateRef.current?.redo;
      const result = await undoQueue.press(projectId, kind, step);
      if (!result.accepted && result.message && projectRef.current === projectId) {
        notificationService.info(kind === 'undo' ? 'Undo' : 'Redo', result.message);
      }
    },
    [projectId],
  );

  useEffect(() => {
    undoQueue.reset();
    apply(undefined);
    void refresh();
    window.addEventListener('focus', refresh);
    window.addEventListener('ontologyChangesSaved', refresh);
    return () => {
      window.removeEventListener('focus', refresh);
      window.removeEventListener('ontologyChangesSaved', refresh);
    };
  }, [projectId, draft, apply, refresh]);

  useEffect(() => {
    if (!projectId) {
      return undefined;
    }
    const onMutated = (event: Event) => {
      const detail = (event as CustomEvent<{ projectId?: string; ops?: string[] }>).detail;
      if (detail?.projectId !== projectId || !detail.ops?.some((op) => PUBLISH_OPS.includes(op))) {
        return;
      }
      undoQueue.reset();
      void refresh();
    };
    window.addEventListener('ontology:mutated', onMutated);
    return () => window.removeEventListener('ontology:mutated', onMutated);
  }, [projectId, refresh]);

  useCommittedChangeRefresh(projectId ?? undefined, () => void refresh(), ['APPLY', 'UNDO', 'REDO']);

  useEffect(() => {
    if (snap.lastState) apply(snap.lastState);
  }, [snap.lastState, apply]);

  useEffect(() => {
    const before = lastPhase.current;
    lastPhase.current = snap.phase;
    if (before !== snap.phase && (snap.phase === 'idle' || snap.phase === 'finished' || snap.phase === 'paused')) {
      void refresh();
    }
  }, [snap.phase, refresh]);

  useEffect(() => {
    if (snap.phase !== 'finished' || toastedRun.current === snap.runId) return;
    toastedRun.current = snap.runId;
    const only = snap.completed === 1 ? snap.finishedItems[0]?.label : undefined;
    const verb = snap.kind === 'undo' ? 'Undid' : 'Redid';
    notificationService.success(snap.kind === 'undo' ? 'Undo' : 'Redo', only ? `${verb}: ${only}` : snap.summary || '');
  }, [snap.phase, snap.runId, snap.completed, snap.finishedItems, snap.kind, snap.summary]);

  useEffect(() => {
    if (!projectId || isRealVSCode() || hasDesktopUndoMenu()) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.altKey || isTextElement(event.target as Element | null)) {
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

  const settled = snap.phase === 'idle' || snap.phase === 'finished';
  const canUndo = !!projectId && (settled ? state?.undo.available !== false : snap.phase === 'running' && snap.kind === 'undo');
  const canRedo = !!projectId && settled && !!state?.redo.available;
  const menu: UndoMenu = {
    undo,
    redo,
    canUndo,
    canRedo,
    undoLabel: state?.undo.available ? state.undo.label : undefined,
    redoLabel: state?.redo.available ? state.redo.label : undefined,
    redoHint: redoHintFor(snap.phase),
    stopPending: undoQueue.stopPending,
    resume: () => void undoQueue.resume(),
    openHistory: () => historyRef.current?.(),
    refresh: () => void refresh(),
  };

  return { menu };
}

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { undoQueue, type UndoRunSnapshot } from '../services/undoQueue';

export const CHIP_DELAY_MS = 300;
export const SLOW_AFTER_MS = 8000;
export const AUTO_OPEN_QUEUE = 3;

export interface UndoRunStatus extends UndoRunSnapshot {
  chipVisible: boolean;
  slow: boolean;
  panelOpen: boolean;
  togglePanel: () => void;
  closePanel: () => void;
}

export function useUndoRunStatus(): UndoRunStatus {
  const snap = useSyncExternalStore(undoQueue.subscribe, undoQueue.getSnapshot);
  const active = snap.phase !== 'idle';
  const [delayed, setDelayed] = useState(false);
  const [slow, setSlow] = useState(false);
  const [panelOpen, setPanelOpen] = useState(false);
  const autoOpenedRun = useRef(0);

  useEffect(() => {
    setDelayed(false);
    setSlow(false);
    if (!active) {
      setPanelOpen(false);
      return undefined;
    }
    const chipTimer = setTimeout(() => setDelayed(true), CHIP_DELAY_MS);
    const slowTimer = setTimeout(() => setSlow(true), Math.max(0, SLOW_AFTER_MS - (Date.now() - snap.startedAt)));
    return () => {
      clearTimeout(chipTimer);
      clearTimeout(slowTimer);
    };
  }, [snap.runId, active, snap.startedAt]);

  useEffect(() => {
    if (!active || autoOpenedRun.current === snap.runId) return;
    if (slow || snap.pending > AUTO_OPEN_QUEUE) {
      autoOpenedRun.current = snap.runId;
      setPanelOpen(true);
    }
  }, [active, slow, snap.pending, snap.runId]);

  const togglePanel = useCallback(() => setPanelOpen((open) => !open), []);
  const closePanel = useCallback(() => setPanelOpen(false), []);
  const immediate = snap.phase === 'paused' || snap.phase === 'checking' || snap.phase === 'finished';

  return {
    ...snap,
    chipVisible: active && (delayed || immediate),
    slow: slow && snap.phase === 'running',
    panelOpen: active && panelOpen,
    togglePanel,
    closePanel,
  };
}

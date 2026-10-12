import React, { useEffect, useRef } from 'react';
import { OperationResponse, OperationTarget } from './types';
import { confirmLabel, summarizePreview } from './previewText';
import { useUndoPreview } from './useUndoPreview';

interface Props {
  projectId: string;
  target: OperationTarget;
  onClose: () => void;
  onApplied: (target: OperationTarget, data: OperationResponse) => void;
  onRefresh: () => void;
}

function PreviewBody({ summary, loading, redo }: { summary: ReturnType<typeof summarizePreview> | null; loading: boolean; redo: boolean }) {
  if (loading || !summary) {
    return <p className="cs-dialog-text">{redo ? 'Checking what can be redone…' : 'Checking what can be undone…'}</p>;
  }
  return (
    <>
      <p className="cs-dialog-text">{summary.headline}</p>
      {summary.skippedTitle && <div className="cs-dialog-skip">{summary.skippedTitle}</div>}
      {summary.skippedLines.length > 0 && (
        <ul className="cs-dialog-list">
          {summary.skippedLines.map((line, i) => <li key={i}>{line}</li>)}
        </ul>
      )}
    </>
  );
}

function useDialogFocus(onClose: () => void) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    dialogRef.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') closeRef.current(); };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      if (opener && document.contains(opener)) opener.focus();
    };
  }, []);
  return dialogRef;
}

const UndoPreviewDialog: React.FC<Props> = ({ projectId, target, onClose, onApplied, onRefresh }) => {
  const { phase, outcome, error, apply } = useUndoPreview(projectId, target, onApplied, onRefresh);
  const dialogRef = useDialogFocus(onClose);
  const redo = target.direction === 'REDO';
  const summary = outcome ? summarizePreview(outcome, target.direction) : null;
  const showConfirm = (phase === 'ready' || phase === 'applying') && !!summary?.canApply;
  const busyLabel = redo ? 'Redoing…' : 'Undoing…';
  return (
    <div className="cs-overlay" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="cs-dialog" role="dialog" aria-modal="true" aria-labelledby="cs-dialog-title" tabIndex={-1} ref={dialogRef}>
        <h3 id="cs-dialog-title" className="cs-dialog-title">{redo ? 'Redo changes' : 'Undo changes'}</h3>
        <div className="cs-dialog-target">{target.label}</div>
        <PreviewBody summary={summary} loading={phase === 'loading'} redo={redo} />
        {error && <p className="cs-dialog-error" role="alert">{error}</p>}
        <div className="cs-dialog-actions">
          <button type="button" className="cs-btn" onClick={onClose}>{showConfirm ? 'Cancel' : 'Close'}</button>
          {showConfirm && summary && (
            <button type="button" className="cs-btn is-primary" onClick={apply} disabled={phase === 'applying'} autoFocus>
              {phase === 'applying' ? busyLabel : confirmLabel(target.direction, summary.appliedCount)}
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

export default UndoPreviewDialog;

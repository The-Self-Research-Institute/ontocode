import React from "react";
import type { UndoRunStatus } from "../hooks/useUndoRunStatus";
import type { UndoMenu } from "../hooks/useOntologyUndoRedo";
import UndoActionButton from "./UndoActionButton";
import RevertPrDialog from "./RevertPrDialog";
import { plainUndoReason } from "../utils/undoReason";

export default function UndoConflictDialog({ status, menu, onClose }: { status: UndoRunStatus; menu: UndoMenu; onClose: () => void }) {
  const unknown = status.reason === "Result unknown";
  if (!unknown && status.revertPr?.prId && menu.projectId) {
    return (
      <RevertPrDialog
        projectId={menu.projectId}
        prId={status.revertPr.prId}
        title={status.revertPr.title}
        mode={status.revertPr.mode === "reapply" ? "reapply" : "revert"}
        onClose={() => {
          menu.stopPending();
          onClose();
        }}
        onDone={() => {
          menu.stopPending();
          menu.refresh?.();
          onClose();
        }}
      />
    );
  }
  if (!unknown && status.foreignTarget) {
    const { username, label, changes } = status.foreignTarget;
    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/35">
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Undo someone else's change"
          className="w-[min(380px,calc(100vw-2rem))] rounded-lg border border-amber-500 bg-theme-surface p-4 text-xs shadow-2xl space-y-2"
        >
          <b className="block text-sm">Undo someone else's change?</b>
          <p>
            {label || "This change"} was made by {username || "someone else"}{changes && changes > 1 ? ` and is part of an edit with ${changes} changes` : ""}. Undoing it removes {changes && changes > 1 ? "all of them" : "their change"} from the shared ontology.
          </p>
          <div className="flex flex-wrap gap-2 pt-1">
            <UndoActionButton tone="danger" onClick={menu.confirmOthers ?? (() => undefined)}>Undo it anyway</UndoActionButton>
            <UndoActionButton tone="neutral" onClick={() => { menu.stopPending(); onClose(); }}>Cancel</UndoActionButton>
          </div>
        </div>
      </div>
    );
  }
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/35">
      <div
        role="dialog"
        aria-modal="true"
        aria-label={unknown ? "Undo result unknown" : "Cannot undo"}
        className="w-[min(380px,calc(100vw-2rem))] rounded-lg border border-red-500 bg-theme-surface p-4 text-xs shadow-2xl space-y-2"
      >
        <b className="block text-sm">{unknown ? "Not sure the last undo went through" : status.item ? `Can't undo this change: ${status.item}` : "Can't undo this change"}</b>
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
          <dt className="opacity-60">{unknown ? "Status" : "Conflict"}</dt>
          <dd>{plainUndoReason(status.message)}</dd>
          <dt className="opacity-60">Waiting</dt>
          <dd>{status.pending} change {status.pending === 1 ? "set" : "sets"}</dd>
        </dl>
        {!unknown && status.othersOnTop && status.othersOnTop.length > 0 && (
          <div className="rounded border border-amber-500 p-2 space-y-1">
            <div>These changes by other people are on top of yours. Undoing yours also undoes them, newest first:</div>
            <ul className="list-disc pl-4">
              {status.othersOnTop.map((other) => (
                <li key={other.changeSetId || other.changeId}>{other.label || "Change"} by {other.username || "someone"}</li>
              ))}
            </ul>
          </div>
        )}
        <div className="flex flex-wrap gap-2 pt-1">
          {!unknown && status.othersOnTop && status.othersOnTop.length > 0 && menu.confirmOthers && (
            <UndoActionButton tone="danger" onClick={menu.confirmOthers}>Undo their changes too</UndoActionButton>
          )}
          <UndoActionButton tone="primary" onClick={menu.resume}>{unknown ? "Check again and continue" : "Revalidate and continue"}</UndoActionButton>
          <UndoActionButton tone="danger" onClick={menu.stopPending}>Stop pending</UndoActionButton>
          <UndoActionButton tone="neutral" onClick={onClose}>Close</UndoActionButton>
        </div>
      </div>
    </div>
  );
}

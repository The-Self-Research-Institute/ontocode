import React from "react";
import type { UndoRunStatus } from "../hooks/useUndoRunStatus";
import type { UndoMenu } from "../hooks/useOntologyUndoRedo";
import UndoActionButton from "./UndoActionButton";
import { plainUndoReason } from "../utils/undoReason";

export default function UndoConflictDialog({ status, menu, onClose }: { status: UndoRunStatus; menu: UndoMenu; onClose: () => void }) {
  const unknown = status.reason === "Result unknown";
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
        <div className="flex flex-wrap gap-2 pt-1">
          <UndoActionButton tone="primary" onClick={menu.resume}>{unknown ? "Check again and continue" : "Revalidate and continue"}</UndoActionButton>
          <UndoActionButton tone="danger" onClick={menu.stopPending}>Stop pending</UndoActionButton>
          <UndoActionButton tone="neutral" onClick={onClose}>Close</UndoActionButton>
        </div>
      </div>
    </div>
  );
}

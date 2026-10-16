import React from "react";
import type { UndoRunStatus } from "../hooks/useUndoRunStatus";
import type { UndoMenu } from "../hooks/useOntologyUndoRedo";
import UndoActionButton from "./UndoActionButton";
import { plainUndoReason } from "../utils/undoReason";

export default function UndoRunPanel({ status, menu }: { status: UndoRunStatus; menu: UndoMenu }) {
  const verb = status.kind === "undo" ? "Undoing" : "Redoing";
  const canStop = status.pending > 0 && status.phase !== "finished";
  return (
    <div
      role="region"
      aria-label="Undo progress"
      className="absolute right-0 top-full mt-1 z-30 w-72 rounded-lg border bg-theme-surface p-3 text-xs shadow-xl space-y-2"
      style={{ borderColor: "var(--color-border)" }}
    >
      {status.current && (
        <div>
          <span className="opacity-60">{verb} </span>
          <b>{status.current}</b>
        </div>
      )}
      {status.phase === "paused" && status.message && <div className="text-red-600">{plainUndoReason(status.message)}</div>}
      {status.phase === "checking" && <div>Waiting for the server to confirm the last change.</div>}
      <div className="opacity-70">Waiting: {status.pending}</div>
      {status.finishedItems.length > 0 && (
        <ul className="border-t pt-1 space-y-0.5 opacity-80" style={{ borderColor: "var(--color-border)" }}>
          {status.finishedItems.map((entry, index) => (
            <li key={`${entry.label}-${index}`} className="flex justify-between gap-2">
              <span className="truncate">{entry.label}</span>
              <span>done</span>
            </li>
          ))}
        </ul>
      )}
      <button type="button" onClick={menu.openHistory} className="font-semibold text-purple-600 cursor-pointer">
        View full history
      </button>
      <div className="flex flex-wrap items-center gap-2">
        {status.phase === "paused" && <UndoActionButton tone="primary" onClick={menu.resume}>Continue</UndoActionButton>}
        {canStop && <UndoActionButton tone="danger" onClick={menu.stopPending}>Stop pending</UndoActionButton>}
      </div>
      {menu.redoHint && <div className="opacity-60">{menu.redoHint}</div>}
    </div>
  );
}

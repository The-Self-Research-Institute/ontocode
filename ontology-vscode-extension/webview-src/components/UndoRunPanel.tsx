import React from "react";
import type { UndoRunStatus } from "../hooks/useUndoRunStatus";
import type { UndoMenu } from "../hooks/useOntologyUndoRedo";
import UndoActionButton from "./UndoActionButton";
import { plainUndoReason } from "../utils/undoReason";
import { dispatchCollaborationNavigate } from "../utils/collaborationNavigation";

const DETAIL_ROWS: Array<["source" | "affected" | "restoring", string]> = [
  ["source", "Source"],
  ["affected", "Affected"],
  ["restoring", "Restoring"],
];

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
      {status.currentDetail && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 m-0">
          {DETAIL_ROWS.map(([key, title]) =>
            status.currentDetail?.[key] ? (
              <React.Fragment key={key}>
                <dt className="opacity-60">{title}</dt>
                <dd className="m-0">{status.currentDetail[key]}</dd>
              </React.Fragment>
            ) : null,
          )}
        </dl>
      )}
      {status.currentDetail?.entityIRI && (
        <button
          type="button"
          onClick={() => dispatchCollaborationNavigate({ entityIRI: status.currentDetail?.entityIRI as string, entityLabel: status.currentDetail?.affected })}
          className="font-semibold text-purple-600 cursor-pointer"
        >
          View affected item
        </button>
      )}
      {status.phase === "paused" && status.message && <div className="text-red-600">{plainUndoReason(status.message)}</div>}
      {status.phase === "checking" && <div>Waiting for the server to confirm the last change.</div>}
      <div className="opacity-70">Waiting: {status.pending}</div>
      {status.finishedItems.length > 0 && (
        <ul className="border-t pt-1 space-y-0.5 opacity-80" style={{ borderColor: "var(--color-border)" }}>
          {status.finishedItems.map((entry, index) => (
            <li key={`${entry.label}-${index}`} className="flex items-start justify-between gap-2">
              <span className="min-w-0 break-words">{entry.label}</span>
              <span className="shrink-0">done</span>
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

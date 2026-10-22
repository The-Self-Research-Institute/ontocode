import React from "react";
import type { UndoRunStatus } from "../hooks/useUndoRunStatus";

export function chipText(status: UndoRunStatus): string {
  switch (status.phase) {
    case "paused":
      return `Paused · ${status.reason} · ${status.pending} pending`;
    case "checking":
      return "Checking result… · further undos paused";
    case "finished":
      return status.summary || "";
    default: {
      const base = `${status.completed} completed · ${status.running ? 1 : 0} running · ${status.pending} pending`;
      return status.slow ? `${base} · Taking longer than usual…` : base;
    }
  }
}

function toneOf(status: UndoRunStatus): string {
  if (status.phase === "paused") return "bg-red-100 text-red-700";
  if (status.phase === "finished") return "bg-green-100 text-green-700";
  if (status.phase === "checking" || status.slow) return "bg-amber-100 text-amber-700";
  return "bg-purple-100 text-purple-700";
}

export default function UndoRunChip({ status, onClick }: { status: UndoRunStatus; onClick: () => void }) {
  const spinning = status.phase === "running" || status.phase === "checking";
  return (
    <button
      type="button"
      onClick={onClick}
      aria-expanded={status.panelOpen}
      title={`${chipText(status)} - click for details`}
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[11px] font-semibold max-w-[16rem] sm:max-w-none truncate cursor-pointer ${toneOf(status)}`}
    >
      {spinning && (
        <span
          aria-hidden="true"
          className="inline-block h-2.5 w-2.5 rounded-full border-2 border-current border-r-transparent animate-spin motion-reduce:animate-none"
        />
      )}
      <span className="truncate">{chipText(status)}</span>
    </button>
  );
}

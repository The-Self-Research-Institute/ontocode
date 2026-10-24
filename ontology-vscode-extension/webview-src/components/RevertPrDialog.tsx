import React, { useEffect, useState } from "react";
import { resolveActor } from "../services/changeTrackingService";
import { confirmRevert, previewRevert, summarizeRevert, type RevertMode, type RevertOutcome } from "../services/prRevertService";
import UndoActionButton from "./UndoActionButton";

function RevertSummaryView({ changeSets, mode }: { changeSets: any[]; mode: RevertMode }) {
  const summary = summarizeRevert(changeSets);
  return (
    <div className="space-y-1">
      {summary.changes.length > 0 ? (
        <>
          <div>{mode === "reapply" ? "These changes will be added to the shared ontology again:" : "These changes will be removed from the shared ontology:"}</div>
          <ul className="list-disc pl-4">
            {summary.changes.map((change) => (
              <li key={change.label}>{change.label}{change.count > 1 ? ` (${change.count} changes)` : ""}</li>
            ))}
          </ul>
        </>
      ) : summary.blocked.length === 0 ? (
        <div>{mode === "reapply" ? "Nothing is left to apply." : "Nothing is left to remove from the shared ontology."}</div>
      ) : null}
      {summary.alreadyUndone > 0 && (
        <div className="opacity-70">{summary.alreadyUndone} part{summary.alreadyUndone === 1 ? " was" : "s were"} {mode === "reapply" ? "already in place." : "already undone or already gone."}</div>
      )}
      {summary.blocked.length > 0 && (
        <div className="text-red-500">
          Blocked: {summary.blocked.join("; ")}
        </div>
      )}
    </div>
  );
}

export default function RevertPrDialog({ projectId, prId, title, onClose, onDone, mode = "revert" }: { projectId: string; prId: string; title?: string; onClose: () => void; onDone: () => void; mode?: RevertMode }) {
  const reapply = mode === "reapply";
  const [preview, setPreview] = useState<RevertOutcome | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    void previewRevert(projectId, prId, resolveActor(), mode).then((p) => live && setPreview(p));
    return () => {
      live = false;
    };
  }, [projectId, prId, mode]);

  const submit = async () => {
    if ((!reapply && !reason.trim()) || busy) return;
    setBusy(true);
    setError(null);
    const result = await confirmRevert(projectId, prId, reason, resolveActor(), mode);
    setBusy(false);
    if (result.ok) {
      window.dispatchEvent(new CustomEvent("ontology:mutated", { detail: { projectId, ops: [reapply ? "draftPrReapplied" : "draftPrReverted"] } }));
      onDone();
    } else {
      setError(result.message);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/35">
      <div role="dialog" aria-modal="true" aria-label={reapply ? "Apply pull request again" : "Revert pull request"} className="w-[min(420px,calc(100vw-2rem))] rounded-lg border border-red-500 bg-theme-surface p-4 text-xs shadow-2xl space-y-2">
        <b className="block text-sm">{reapply ? "Apply pull request again" : "Revert pull request"}{title ? `: ${title}` : ""}</b>
        {!preview && <div>{reapply ? "Checking what this would apply..." : "Checking what this would remove..."}</div>}
        {preview && (
          <div className={`rounded border p-2 space-y-1 ${preview.ok ? "border-amber-500" : "border-red-500"}`}>
            <div className="font-medium">{preview.message}</div>
            {preview.changeSets.length > 0 && <RevertSummaryView changeSets={preview.changeSets} mode={mode} />}
            <div className="text-[11px] opacity-60">
              Checked: the statements this pull request added and removed, and whether later changes use them. Not checked: logical consistency. Run the reasoner after {reapply ? "applying" : "reverting"}.
            </div>
          </div>
        )}
        {!reapply && (
          <label className="block">
            <span className="opacity-60">Reason (required)</span>
            <textarea className="mt-1 w-full rounded border border-theme-border bg-theme-bg p-1" rows={2} value={reason} onChange={(e) => setReason(e.target.value)} />
          </label>
        )}
        {error && <div role="alert" className="text-red-500">{error}</div>}
        <div className="flex flex-wrap gap-2 pt-1">
          <UndoActionButton tone="danger" onClick={() => void submit()} disabled={!preview?.ok || (!reapply && !reason.trim()) || busy}>{reapply ? "Apply again" : "Confirm revert"}</UndoActionButton>
          <UndoActionButton tone="neutral" onClick={onClose}>Cancel</UndoActionButton>
        </div>
      </div>
    </div>
  );
}

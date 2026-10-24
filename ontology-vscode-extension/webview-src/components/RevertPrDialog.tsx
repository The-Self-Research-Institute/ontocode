import React, { useEffect, useState } from "react";
import { resolveActor } from "../services/changeTrackingService";
import { confirmRevert, previewRevert, type RevertOutcome } from "../services/prRevertService";
import UndoActionButton from "./UndoActionButton";

export default function RevertPrDialog({ projectId, prId, title, onClose, onDone }: { projectId: string; prId: string; title?: string; onClose: () => void; onDone: () => void }) {
  const [preview, setPreview] = useState<RevertOutcome | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    void previewRevert(projectId, prId, resolveActor()).then((p) => live && setPreview(p));
    return () => {
      live = false;
    };
  }, [projectId, prId]);

  const submit = async () => {
    if (!reason.trim() || busy) return;
    setBusy(true);
    setError(null);
    const result = await confirmRevert(projectId, prId, reason, resolveActor());
    setBusy(false);
    if (result.ok) {
      window.dispatchEvent(new CustomEvent("ontology:mutated", { detail: { projectId, ops: ["draftPrReverted"] } }));
      onDone();
    } else {
      setError(result.message);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/35">
      <div role="dialog" aria-modal="true" aria-label="Revert pull request" className="w-[min(420px,calc(100vw-2rem))] rounded-lg border border-red-500 bg-theme-surface p-4 text-xs shadow-2xl space-y-2">
        <b className="block text-sm">Revert pull request{title ? `: ${title}` : ""}</b>
        {!preview && <div>Checking what this would remove...</div>}
        {preview && (
          <div className={`rounded border p-2 space-y-1 ${preview.ok ? "border-amber-500" : "border-red-500"}`}>
            <div className="font-medium">{preview.message}</div>
            {preview.changeSets.length > 0 && (
              <ul className="list-disc pl-4">
                {preview.changeSets.map((cs: any, i: number) => (
                  <li key={cs.changeSetId || i}>{cs.label || cs.summary || cs.changeSetId}{cs.reason ? `: ${cs.reason}` : ""}</li>
                ))}
              </ul>
            )}
          </div>
        )}
        <label className="block">
          <span className="opacity-60">Reason (required)</span>
          <textarea className="mt-1 w-full rounded border border-theme-border bg-theme-bg p-1" rows={2} value={reason} onChange={(e) => setReason(e.target.value)} />
        </label>
        {error && <div role="alert" className="text-red-500">{error}</div>}
        <div className="flex flex-wrap gap-2 pt-1">
          <UndoActionButton tone="danger" onClick={() => void submit()} disabled={!preview?.ok || !reason.trim() || busy}>Confirm revert</UndoActionButton>
          <UndoActionButton tone="neutral" onClick={onClose}>Cancel</UndoActionButton>
        </div>
      </div>
    </div>
  );
}

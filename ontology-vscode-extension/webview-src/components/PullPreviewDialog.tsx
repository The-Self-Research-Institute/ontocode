import React, { useEffect, useState, useCallback } from "react";
import { X, Download, AlertTriangle, CheckCircle, Loader2, RefreshCw, GitMerge } from "lucide-react";
import apiClient from "../services/apiClient";
import { extractLocalName } from "../utils/draftChangeHelpers";

interface PullPreviewDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  projectId: string;
  userId: string;
}

interface ChangeRow {
  entityIri: string;
  entityLabel: string;
  publicAxioms?: string;
  yourAxioms?: string;
}

type Resolution = "keep_draft" | "take_public";
type Phase = "analyzing" | "no_changes" | "ready" | "merging" | "done" | "error";

const PullPreviewDialog: React.FC<PullPreviewDialogProps> = ({
  isOpen, onClose, onConfirm, projectId, userId,
}) => {
  const [phase, setPhase] = useState<Phase>("analyzing");
  const [safeChanges, setSafeChanges] = useState<ChangeRow[]>([]);
  const [conflicts, setConflicts] = useState<ChangeRow[]>([]);
  const [resolutions, setResolutions] = useState<Record<string, Resolution>>({});
  const [errorMsg, setErrorMsg] = useState("");

  const analyze = useCallback(() => {
    setPhase("analyzing");
    setErrorMsg("");
    apiClient
      .post<any>(`/api/ontology/${projectId}/pull-from-public/analyze`, {}, { params: { userId } })
      .then((res) => {
        const data = res?.data || res;
        const safe: ChangeRow[] = data.safeChanges || [];
        const conf: ChangeRow[] = data.conflicts || [];
        setSafeChanges(safe);
        setConflicts(conf);
        setResolutions({});
        setPhase(safe.length === 0 && conf.length === 0 ? "no_changes" : "ready");
      })
      .catch((e: any) => {
        setErrorMsg(e?.message || "Could not analyse differences with the public version.");
        setPhase("error");
      });
  }, [projectId, userId]);

  useEffect(() => {
    if (!isOpen) return;
    analyze();
  }, [isOpen, analyze]);

  if (!isOpen) return null;

  const setResolution = (iri: string, resolution: Resolution) => {
    setResolutions((prev) => ({ ...prev, [iri]: resolution }));
  };

  const allResolved = conflicts.length === 0 || conflicts.every((c) => resolutions[c.entityIri]);

  const applyPull = () => {
    setPhase("merging");
    apiClient
      .post<any>(`/api/ontology/${projectId}/pull-from-public/apply`, { resolutions }, { params: { userId } })
      .then(() => {
        setPhase("done");
        setTimeout(() => {
          onConfirm();
          onClose();
        }, 600);
      })
      .catch((e: any) => {
        setErrorMsg(e?.message || "Failed to merge public changes into your draft.");
        setPhase("error");
      });
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={onClose}
    >
      <div
        className="relative w-full max-w-lg rounded-lg shadow-xl border flex flex-col"
        style={{
          backgroundColor: "var(--color-background)",
          borderColor: "var(--color-border)",
          color: "var(--color-text)",
          maxHeight: "85vh",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        {}
        <div
          className="flex items-center justify-between px-4 py-3 border-b flex-shrink-0"
          style={{ borderColor: "var(--color-border)" }}
        >
          <div className="flex items-center gap-2 font-semibold text-sm">
            <Download size={15} />
            Pull from Public
          </div>
          <button onClick={onClose} className="opacity-60 hover:opacity-100">
            <X size={15} />
          </button>
        </div>

        {}
        <div className="flex-1 overflow-y-auto px-4 py-3 flex flex-col gap-3 text-xs">
          {phase === "analyzing" && (
            <div className="flex flex-col items-center justify-center py-10 gap-2 opacity-70">
              <Loader2 size={20} className="animate-spin" />
              <span>Comparing your draft with the public version…</span>
            </div>
          )}

          {phase === "no_changes" && (
            <div className="flex flex-col items-center justify-center py-10 gap-2 opacity-70 text-center">
              <CheckCircle size={22} className="text-green-500" />
              <span>Your draft is already up to date with public. Nothing to pull.</span>
            </div>
          )}

          {phase === "merging" && (
            <div className="flex flex-col items-center justify-center py-10 gap-2 opacity-70">
              <Loader2 size={20} className="animate-spin" />
              <span>Merging public changes into your draft…</span>
            </div>
          )}

          {phase === "done" && (
            <div className="flex flex-col items-center justify-center py-10 gap-2 text-green-600">
              <CheckCircle size={24} />
              <span className="font-medium">Pull complete!</span>
            </div>
          )}

          {phase === "error" && (
            <div className="flex flex-col items-center gap-3 py-8">
              <div className="flex items-center gap-2 text-red-500">
                <AlertTriangle size={18} />
                <span className="font-medium">Pull failed</span>
              </div>
              <p className="opacity-70 text-center max-w-sm">{errorMsg}</p>
              <button
                onClick={analyze}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded border transition-colors"
                style={{ borderColor: "var(--color-border)" }}
              >
                <RefreshCw size={12} /> Retry
              </button>
            </div>
          )}

          {phase === "ready" && (
            <>
              {conflicts.length > 0 ? (
                <div
                  className="flex items-start gap-2 rounded-md px-3 py-2 border"
                  style={{ borderColor: "rgba(234,179,8,0.4)", backgroundColor: "rgba(234,179,8,0.08)" }}
                >
                  <AlertTriangle size={14} className="text-amber-500 flex-shrink-0 mt-0.5" />
                  <div className="text-amber-600 dark:text-amber-400">
                    <strong>{conflicts.length} conflict{conflicts.length !== 1 ? "s" : ""}</strong> —
                    entities changed in both your draft and public. Choose which version to keep for each.
                  </div>
                </div>
              ) : (
                <div
                  className="flex items-start gap-2 rounded-md px-3 py-2 border"
                  style={{ borderColor: "var(--color-border)", backgroundColor: "rgba(34,197,94,0.06)" }}
                >
                  <CheckCircle size={14} className="text-green-500 flex-shrink-0 mt-0.5" />
                  <span className="opacity-80">
                    No conflicts — {safeChanges.length} public change{safeChanges.length !== 1 ? "s" : ""} will
                    be merged into your draft automatically. Your draft edits are kept as-is.
                  </span>
                </div>
              )}

              {safeChanges.length > 0 && conflicts.length > 0 && (
                <div className="text-[11px] opacity-60 pl-1">
                  Plus {safeChanges.length} safe change{safeChanges.length !== 1 ? "s" : ""} with no conflict —
                  merged automatically.
                </div>
              )}

              {}
              {conflicts.length > 0 && (
                <div className="flex flex-col gap-2">
                  {conflicts.map((c) => {
                    const resolution = resolutions[c.entityIri];
                    return (
                      <div
                        key={c.entityIri}
                        className="rounded-md border overflow-hidden"
                        style={{ borderColor: resolution ? "rgba(34,197,94,0.5)" : "var(--color-border)" }}
                      >
                        <div
                          className="flex items-center justify-between px-3 py-1.5 border-b text-[11px] font-medium"
                          style={{ borderColor: "var(--color-border)", backgroundColor: "rgba(128,128,128,0.06)" }}
                        >
                          <span className="truncate">{c.entityLabel || extractLocalName(c.entityIri)}</span>
                          {resolution && (
                            <span className="flex items-center gap-1 text-green-500 flex-shrink-0">
                              <CheckCircle size={10} /> Resolved
                            </span>
                          )}
                        </div>
                        <div className="grid grid-cols-2 divide-x" style={{ borderColor: "var(--color-border)" }}>
                          <button
                            onClick={() => setResolution(c.entityIri, "keep_draft")}
                            className="text-left p-2.5 transition-colors hover:opacity-90"
                            style={{
                              backgroundColor: resolution === "keep_draft" ? "rgba(59,130,246,0.12)" : "transparent",
                            }}
                          >
                            <div className="text-[10px] font-semibold text-blue-500 mb-1 uppercase tracking-wide">
                              Your Draft
                            </div>
                            <p className="opacity-70 leading-relaxed whitespace-pre-wrap break-words line-clamp-4">
                              {c.yourAxioms || "(no axioms)"}
                            </p>
                          </button>
                          <button
                            onClick={() => setResolution(c.entityIri, "take_public")}
                            className="text-left p-2.5 transition-colors hover:opacity-90"
                            style={{
                              backgroundColor: resolution === "take_public" ? "rgba(168,85,247,0.12)" : "transparent",
                            }}
                          >
                            <div className="text-[10px] font-semibold text-purple-500 mb-1 uppercase tracking-wide">
                              Public Version
                            </div>
                            <p className="opacity-70 leading-relaxed whitespace-pre-wrap break-words line-clamp-4">
                              {c.publicAxioms || "(no axioms)"}
                            </p>
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </>
          )}
        </div>

        {}
        <div
          className="flex justify-end gap-2 px-4 py-3 border-t flex-shrink-0"
          style={{ borderColor: "var(--color-border)" }}
        >
          <button
            onClick={onClose}
            className="text-xs px-3 py-1.5 rounded border transition-colors"
            style={{ borderColor: "var(--color-border)" }}
          >
            Cancel
          </button>
          {phase === "ready" && (
            <button
              onClick={applyPull}
              disabled={!allResolved}
              className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded font-medium bg-blue-600 text-white hover:bg-blue-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <GitMerge size={12} />
              {conflicts.length > 0 ? "Apply & Merge" : "Pull & Merge"}
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

export default PullPreviewDialog;

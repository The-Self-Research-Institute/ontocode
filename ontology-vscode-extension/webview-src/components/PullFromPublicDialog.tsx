import React, { useState, useEffect, useCallback } from "react";
import { X, AlertTriangle, CheckCircle, GitMerge, Loader2, RefreshCw, ArrowRight } from "lucide-react";

interface PublicChange {
  entityIri: string;
  entityLabel: string;
  changeType: string;
  description: string;
}

interface ConflictEntry {
  entityIri: string;
  entityLabel: string;
  changeType: string;
  draftDescription: string;
  publicDescription: string;
  resolution: "keep_draft" | "take_public" | null;
}

interface AnalysisResult {
  hasConflicts: boolean;
  conflicts: ConflictEntry[];
  safeChanges: PublicChange[];
  draftChanges: PublicChange[];
  publicVersion: string;
  draftCount: number;
}

type Phase = "analyzing" | "no_conflict" | "has_conflict" | "merging" | "done" | "error";

interface Props {
  projectId: string;
  apiBase?: string;
  authToken?: string;
  onClose: () => void;
  onPullComplete: () => void;
}

const PullFromPublicDialog: React.FC<Props> = ({
  projectId,
  apiBase = "/api/ontology",
  authToken,
  onClose,
  onPullComplete,
}) => {
  const [phase, setPhase] = useState<Phase>("analyzing");
  const [analysis, setAnalysis] = useState<AnalysisResult | null>(null);
  const [conflicts, setConflicts] = useState<ConflictEntry[]>([]);
  const [errorMsg, setErrorMsg] = useState<string>("");

  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (authToken) headers["Authorization"] = `Bearer ${authToken}`;

  const analyze = useCallback(async () => {
    setPhase("analyzing");
    try {
      const res = await fetch(`${apiBase}/${projectId}/pull-from-public/analyze`, {
        method: "POST",
        headers,
      });
      if (!res.ok) throw new Error(`Analysis failed: ${res.status}`);
      const data: AnalysisResult = await res.json();
      setAnalysis(data);
      if (data.hasConflicts) {
        setConflicts(data.conflicts.map((c) => ({ ...c, resolution: null })));
        setPhase("has_conflict");
      } else {
        setPhase("no_conflict");
        performMerge("auto", data);
      }
    } catch (e: any) {
      setErrorMsg(e.message || "Could not analyse differences.");
      setPhase("error");
    }
  }, [projectId, apiBase]);

  useEffect(() => {
    analyze();
  }, [analyze]);

  const performMerge = async (
    strategy: "auto" | "resolved" | "overwrite",
    analysisData?: AnalysisResult,
    resolvedConflicts?: ConflictEntry[]
  ) => {
    setPhase("merging");
    const resolutions: Record<string, string> = {};
    (resolvedConflicts || []).forEach((c) => {
      if (c.resolution) resolutions[c.entityIri] = c.resolution;
    });
    try {
      const res = await fetch(`${apiBase}/${projectId}/pull-from-public/apply`, {
        method: "POST",
        headers,
        body: JSON.stringify({ strategy, conflictResolutions: resolutions }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.error || `Merge failed: ${res.status}`);
      }
      setPhase("done");
      setTimeout(() => onPullComplete(), 800);
    } catch (e: any) {
      setErrorMsg(e.message || "Merge failed.");
      setPhase("error");
    }
  };

  const setResolution = (iri: string, resolution: "keep_draft" | "take_public") => {
    setConflicts((prev) => prev.map((c) => (c.entityIri === iri ? { ...c, resolution } : c)));
  };

  const allResolved = conflicts.length > 0 && conflicts.every((c) => c.resolution !== null);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-2xl max-h-[90vh] flex flex-col overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-200">
          <div className="flex items-center gap-2.5">
            <GitMerge size={18} className="text-purple-600" />
            <span className="font-semibold text-gray-800 text-sm">Pull from Public</span>
          </div>
          <button onClick={onClose} className="p-1 rounded hover:bg-gray-100 text-gray-500">
            <X size={16} />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-5">
          {phase === "analyzing" && (
            <div className="flex flex-col items-center justify-center py-12 gap-3 text-gray-500">
              <Loader2 size={32} className="animate-spin text-purple-500" />
              <p className="text-sm">Analysing differences between your draft and public…</p>
            </div>
          )}

          {phase === "no_conflict" && (
            <div className="flex flex-col items-center justify-center py-10 gap-3 text-gray-600">
              <Loader2 size={28} className="animate-spin text-green-500" />
              <p className="text-sm font-medium">No conflicts found — auto-merging…</p>
            </div>
          )}

          {phase === "merging" && (
            <div className="flex flex-col items-center justify-center py-10 gap-3 text-gray-600">
              <Loader2 size={28} className="animate-spin text-purple-500" />
              <p className="text-sm font-medium">Applying merge…</p>
            </div>
          )}

          {phase === "done" && (
            <div className="flex flex-col items-center justify-center py-10 gap-3 text-green-600">
              <CheckCircle size={36} />
              <p className="text-sm font-semibold">Pull complete!</p>
            </div>
          )}

          {phase === "error" && (
            <div className="flex flex-col items-center gap-4 py-8">
              <div className="flex items-center gap-2 text-red-600">
                <AlertTriangle size={22} />
                <span className="font-medium text-sm">Pull failed</span>
              </div>
              <p className="text-xs text-gray-500 text-center max-w-sm">{errorMsg}</p>
              <button
                onClick={analyze}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-gray-100 hover:bg-gray-200 text-gray-700 text-xs rounded-md"
              >
                <RefreshCw size={12} /> Retry
              </button>
            </div>
          )}

          {phase === "has_conflict" && (
            <div className="flex flex-col gap-4">
              <div className="flex items-start gap-2.5 p-3 bg-amber-50 border border-amber-200 rounded-lg">
                <AlertTriangle size={16} className="text-amber-600 mt-0.5 flex-shrink-0" />
                <div>
                  <p className="text-sm font-semibold text-amber-800">
                    {conflicts.length} conflict{conflicts.length !== 1 ? "s" : ""} found
                  </p>
                  <p className="text-xs text-amber-700 mt-0.5">
                    Choose how to resolve each conflict, then click "Apply Resolution" — or use "Pull &amp; Overwrite" to discard all draft changes.
                  </p>
                </div>
              </div>

              {/* Safe changes summary */}
              {analysis && analysis.safeChanges.length > 0 && (
                <div className="text-xs text-gray-500 border-l-2 border-green-300 pl-2">
                  {analysis.safeChanges.length} public change{analysis.safeChanges.length !== 1 ? "s" : ""} will be auto-merged (no conflict).
                </div>
              )}

              {/* Conflict cards */}
              <div className="flex flex-col gap-3">
                {conflicts.map((c) => (
                  <div
                    key={c.entityIri}
                    className={`border rounded-lg overflow-hidden ${
                      c.resolution ? "border-green-300" : "border-amber-300"
                    }`}
                  >
                    <div className="flex items-center justify-between px-3 py-2 bg-gray-50 border-b border-gray-200">
                      <div>
                        <span className="text-xs font-semibold text-gray-700">{c.entityLabel || c.entityIri}</span>
                        <span className="ml-2 px-1.5 py-0.5 text-[10px] bg-gray-200 text-gray-600 rounded">
                          {c.changeType}
                        </span>
                      </div>
                      {c.resolution && (
                        <span className="text-[10px] text-green-600 font-medium flex items-center gap-1">
                          <CheckCircle size={10} /> Resolved
                        </span>
                      )}
                    </div>
                    <div className="grid grid-cols-2 divide-x divide-gray-200">
                      {/* Draft side */}
                      <button
                        onClick={() => setResolution(c.entityIri, "keep_draft")}
                        className={`text-left p-3 transition-colors ${
                          c.resolution === "keep_draft"
                            ? "bg-blue-50 ring-2 ring-blue-400 ring-inset"
                            : "hover:bg-blue-50"
                        }`}
                      >
                        <div className="text-[10px] font-semibold text-blue-600 mb-1 uppercase tracking-wide flex items-center gap-1">
                          {c.resolution === "keep_draft" && <CheckCircle size={10} />}
                          Your Draft
                        </div>
                        <p className="text-xs text-gray-700 leading-relaxed">{c.draftDescription}</p>
                      </button>
                      {/* Public side */}
                      <button
                        onClick={() => setResolution(c.entityIri, "take_public")}
                        className={`text-left p-3 transition-colors ${
                          c.resolution === "take_public"
                            ? "bg-purple-50 ring-2 ring-purple-400 ring-inset"
                            : "hover:bg-purple-50"
                        }`}
                      >
                        <div className="text-[10px] font-semibold text-purple-600 mb-1 uppercase tracking-wide flex items-center gap-1">
                          {c.resolution === "take_public" && <CheckCircle size={10} />}
                          Public Version
                        </div>
                        <p className="text-xs text-gray-700 leading-relaxed">{c.publicDescription}</p>
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        {(phase === "has_conflict" || phase === "error") && (
          <div className="flex items-center justify-between px-5 py-3 border-t border-gray-200 bg-gray-50">
            <button
              onClick={() => performMerge("overwrite")}
              className="text-xs text-red-600 hover:text-red-700 hover:underline"
            >
              Pull &amp; Overwrite (discard draft)
            </button>
            <div className="flex gap-2">
              <button
                onClick={onClose}
                className="px-3 py-1.5 text-xs text-gray-600 hover:bg-gray-200 rounded-md"
              >
                Cancel
              </button>
              {phase === "has_conflict" && (
                <button
                  onClick={() => performMerge("resolved", analysis ?? undefined, conflicts)}
                  disabled={!allResolved}
                  className={`flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-md font-medium transition-colors ${
                    allResolved
                      ? "bg-purple-600 text-white hover:bg-purple-700"
                      : "bg-gray-200 text-gray-400 cursor-not-allowed"
                  }`}
                >
                  <ArrowRight size={12} /> Apply Resolution
                </button>
              )}
            </div>
          </div>
        )}

        {phase === "analyzing" || phase === "no_conflict" || phase === "merging" ? (
          <div className="flex justify-end px-5 py-3 border-t border-gray-200 bg-gray-50">
            <button
              onClick={onClose}
              className="px-3 py-1.5 text-xs text-gray-600 hover:bg-gray-200 rounded-md"
            >
              Cancel
            </button>
          </div>
        ) : null}
      </div>
    </div>
  );
};

export default PullFromPublicDialog;

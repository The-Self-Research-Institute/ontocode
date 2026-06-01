import React, { useEffect, useState } from "react";
import { Loader2, Clock, Users } from "lucide-react";

export const LoadingDialog = ({
  isOpen,
  message,
  projectName,
  loadingStatusMessage,
  progress,
  queuePosition,
  totalInQueue,
  estimatedWaitTimeMs,
}: {
  isOpen: boolean;
  message?: string;
  projectName?: string;
  loadingStatusMessage?: string;
  progress?: number;
  queuePosition?: number;
  totalInQueue?: number;
  estimatedWaitTimeMs?: number;
}) => {
  // Elapsed time counter — shows the import is actively working, not stuck
  const [elapsedSeconds, setElapsedSeconds] = useState(0);

  useEffect(() => {
    if (!isOpen) { setElapsedSeconds(0); return; }
    const t = setInterval(() => setElapsedSeconds(s => s + 1), 1000);
    return () => clearInterval(t);
  }, [isOpen]);

  if (!isOpen) return null;

  const formatElapsed = (s: number) => {
    if (s < 60) return `${s}s`;
    return `${Math.floor(s / 60)}m ${s % 60}s`;
  };

  const hasProgress = progress !== undefined && progress > 0;
  const hasQueue = queuePosition !== undefined && queuePosition > 0;

  // Derive a human-readable phase from the status message
  const phaseLabel = (() => {
    const msg = (loadingStatusMessage || "").toLowerCase();
    if (msg.includes("detect") || msg.includes("format")) return "Detecting format…";
    if (msg.includes("sanitiz")) return "Sanitising file…";
    if (msg.includes("convert")) return "Converting format…";
    if (msg.includes("loading") || msg.includes("bulk") || msg.includes("graphdb") || msg.includes("fuseki"))
      return "Loading triples into triple store…";
    if (msg.includes("index") || msg.includes("metadata") || msg.includes("background"))
      return "Building class index…";
    if (msg.includes("queue") || msg.includes("wait")) return "Waiting in queue…";
    if (loadingStatusMessage) return loadingStatusMessage;
    return "Processing ontology…";
  })();

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[60]">
      <div
        className="rounded-2xl shadow-2xl w-full max-w-sm mx-4 overflow-hidden border"
        style={{ backgroundColor: "var(--color-surface, #1e1e2e)", borderColor: "var(--color-border, #3b3b5c)" }}
      >
        {/* Progress bar at top */}
        <div className="h-1.5 bg-gray-700 relative overflow-hidden">
          {hasProgress ? (
            <div
              className="h-full bg-gradient-to-r from-purple-500 to-indigo-500 transition-all duration-500"
              style={{ width: `${progress}%` }}
            />
          ) : (
            /* Indeterminate shimmer when no progress data */
            <div className="h-full w-1/3 bg-gradient-to-r from-transparent via-purple-500 to-transparent animate-[shimmer_1.5s_infinite]"
                 style={{ animation: "shimmer 1.5s ease-in-out infinite" }} />
          )}
        </div>

        <div className="p-6">
          {/* Icon + title */}
          <div className="flex flex-col items-center text-center mb-4">
            <div className="relative mb-3">
              <div className="w-12 h-12 rounded-full bg-gradient-to-br from-purple-900 to-indigo-900 flex items-center justify-center">
                <Loader2 size={22} className="text-purple-400 animate-spin" />
              </div>
              {hasProgress && (
                <div className="absolute -bottom-1 -right-1 w-6 h-6 rounded-full bg-purple-600 text-white text-[9px] font-bold flex items-center justify-center shadow">
                  {Math.round(progress)}%
                </div>
              )}
            </div>

            <h3 className="text-base font-semibold" style={{ color: "var(--color-text, #e2e8f0)" }}>
              {message || "Loading Ontology"}
            </h3>
            {projectName && (
              <p className="text-xs mt-0.5 truncate w-full" style={{ color: "var(--color-text-secondary, #94a3b8)" }}>
                {projectName}
              </p>
            )}
          </div>

          {/* Progress bar (detailed) */}
          {hasProgress && (
            <div className="mb-3">
              <div className="flex justify-between mb-1">
                <span className="text-xs" style={{ color: "var(--color-text-secondary, #94a3b8)" }}>Progress</span>
                <span className="text-xs font-bold text-purple-400">{Math.round(progress)}%</span>
              </div>
              <div className="w-full h-1.5 bg-gray-700 rounded-full overflow-hidden">
                <div
                  className="h-full rounded-full bg-gradient-to-r from-purple-500 to-indigo-400 transition-all duration-500"
                  style={{ width: `${progress}%` }}
                />
              </div>
            </div>
          )}

          {/* Current phase */}
          <div className="text-xs text-center mb-3 px-3 py-2 rounded-lg"
               style={{ backgroundColor: "rgba(99,102,241,0.1)", color: "rgb(167,139,250)" }}>
            {phaseLabel}
          </div>

          {/* Elapsed time + note */}
          <div className="flex items-center justify-between text-xs"
               style={{ color: "var(--color-text-secondary, #64748b)" }}>
            <div className="flex items-center gap-1">
              <Clock size={11} />
              <span>Running: <span className="font-mono font-semibold text-purple-400">{formatElapsed(elapsedSeconds)}</span></span>
            </div>
            {!hasQueue && (
              <span className="text-[10px] opacity-60">Large files may take 1–3 min</span>
            )}
          </div>

          {/* Queue info */}
          {hasQueue && (
            <div className="mt-3 rounded-lg px-3 py-2 border text-xs"
                 style={{ backgroundColor: "rgba(147,51,234,0.05)", borderColor: "rgba(147,51,234,0.15)" }}>
              <div className="flex items-center justify-between text-purple-400 font-medium mb-1">
                <span className="flex items-center gap-1"><Clock size={11} /> Queue #{queuePosition}</span>
                {totalInQueue !== undefined && totalInQueue > 0 && (
                  <span className="text-[10px] bg-purple-900 text-purple-300 px-1.5 py-0.5 rounded-full">
                    {totalInQueue} in queue
                  </span>
                )}
              </div>
              {queuePosition > 1 && (
                <div className="flex items-center gap-1 text-purple-500">
                  <Users size={10} />
                  <span>{queuePosition - 1} file{queuePosition - 1 !== 1 ? "s" : ""} ahead</span>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Shimmer keyframe */}
      <style>{`
        @keyframes shimmer {
          0% { transform: translateX(-100%); }
          100% { transform: translateX(400%); }
        }
      `}</style>
    </div>
  );
};

import React, { useEffect, useState } from "react";
import { Loader2, Clock, Users } from "lucide-react";

const MODAL_FADE_MS = 220;

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
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [mounted, setMounted] = useState(isOpen);
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (isOpen) {
      setMounted(true);
      const frame = requestAnimationFrame(() => setVisible(true));
      return () => cancelAnimationFrame(frame);
    }
    setVisible(false);
    const t = setTimeout(() => setMounted(false), MODAL_FADE_MS);
    return () => clearTimeout(t);
  }, [isOpen]);

  useEffect(() => {
    if (!mounted) {
      setElapsedSeconds(0);
      return;
    }
    const t = setInterval(() => setElapsedSeconds((s) => s + 1), 1000);
    return () => clearInterval(t);
  }, [mounted]);

  if (!mounted) return null;

  const formatElapsed = (s: number) => {
    if (s < 60) return `${s}s`;
    return `${Math.floor(s / 60)}m ${s % 60}s`;
  };

  const hasProgress = progress !== undefined && progress > 0;
  const hasQueue = queuePosition !== undefined && queuePosition > 0;

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
    <div
      className={`fixed inset-0 z-[60] flex items-center justify-center bg-black/60 backdrop-blur-sm transition-opacity ease-out ${
        visible ? "opacity-100" : "opacity-0 pointer-events-none"
      }`}
      style={{ transitionDuration: `${MODAL_FADE_MS}ms` }}
      role="dialog"
      aria-modal="true"
      aria-busy="true"
      aria-label={message || "Loading ontology"}
    >
      <div
        className={`mx-4 w-full max-w-sm overflow-hidden rounded-2xl border shadow-2xl transition-all ease-out ${
          visible ? "scale-100 opacity-100" : "scale-[0.98] opacity-0"
        }`}
        style={{
          transitionDuration: `${MODAL_FADE_MS}ms`,
          backgroundColor: "var(--color-surface, #1e1e2e)",
          borderColor: "var(--color-border, #3b3b5c)",
        }}
      >
        <div className="relative h-1.5 overflow-hidden bg-gray-700">
          {hasProgress ? (
            <div
              className="h-full bg-gradient-to-r from-purple-500 to-indigo-500 transition-all duration-500"
              style={{ width: `${progress}%` }}
            />
          ) : (
            <div
              className="ontocode-loading-shimmer h-full w-1/3 bg-gradient-to-r from-transparent via-purple-500 to-transparent"
            />
          )}
        </div>

        <div className="p-6">
          <div className="mb-4 flex flex-col items-center text-center">
            <div className="relative mb-3">
              <div className="flex h-12 w-12 items-center justify-center rounded-full bg-gradient-to-br from-purple-900 to-indigo-900">
                <Loader2 size={22} className="animate-spin text-purple-400" />
              </div>
              {hasProgress && (
                <div className="absolute -bottom-1 -right-1 flex h-6 w-6 items-center justify-center rounded-full bg-purple-600 text-[9px] font-bold text-white shadow">
                  {Math.round(progress!)}%
                </div>
              )}
            </div>

            <h3 className="text-base font-semibold" style={{ color: "var(--color-text, #e2e8f0)" }}>
              {message || "Loading Ontology"}
            </h3>
            {projectName && (
              <p
                className="mt-0.5 w-full truncate text-xs"
                style={{ color: "var(--color-text-secondary, #94a3b8)" }}
              >
                {projectName}
              </p>
            )}
          </div>

          {hasProgress && (
            <div className="mb-3">
              <div className="mb-1 flex justify-between">
                <span className="text-xs" style={{ color: "var(--color-text-secondary, #94a3b8)" }}>
                  Progress
                </span>
                <span className="text-xs font-bold text-purple-400">{Math.round(progress!)}%</span>
              </div>
              <div className="h-1.5 w-full overflow-hidden rounded-full bg-gray-700">
                <div
                  className="h-full rounded-full bg-gradient-to-r from-purple-500 to-indigo-400 transition-all duration-500"
                  style={{ width: `${progress}%` }}
                />
              </div>
            </div>
          )}

          <div
            className="mb-3 rounded-lg px-3 py-2 text-center text-xs"
            style={{ backgroundColor: "rgba(99,102,241,0.1)", color: "rgb(167,139,250)" }}
          >
            {phaseLabel}
          </div>

          <div
            className="flex items-center justify-between text-xs"
            style={{ color: "var(--color-text-secondary, #64748b)" }}
          >
            <div className="flex items-center gap-1">
              <Clock size={11} />
              <span>
                Running:{" "}
                <span className="font-mono font-semibold text-purple-400">
                  {formatElapsed(elapsedSeconds)}
                </span>
              </span>
            </div>
            {!hasQueue && <span className="text-[10px] opacity-60">Large files may take 1–3 min</span>}
          </div>

          {hasQueue && (
            <div
              className="mt-3 rounded-lg border px-3 py-2 text-xs"
              style={{
                backgroundColor: "rgba(147,51,234,0.05)",
                borderColor: "rgba(147,51,234,0.15)",
              }}
            >
              <div className="mb-1 flex items-center justify-between font-medium text-purple-400">
                <span className="flex items-center gap-1">
                  <Clock size={11} /> Queue #{queuePosition}
                </span>
                {totalInQueue !== undefined && totalInQueue > 0 && (
                  <span className="rounded-full bg-purple-900 px-1.5 py-0.5 text-[10px] text-purple-300">
                    {totalInQueue} in queue
                  </span>
                )}
              </div>
              {queuePosition! > 1 && (
                <div className="flex items-center gap-1 text-purple-500">
                  <Users size={10} />
                  <span>
                    {queuePosition! - 1} file{queuePosition! - 1 !== 1 ? "s" : ""} ahead
                  </span>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

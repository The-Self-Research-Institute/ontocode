import React from "react";
import { Loader2, Sparkles, Clock, Users } from "lucide-react";

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
  if (!isOpen) return null;

  const formatWaitTime = (ms: number): string => {
    const minutes = Math.ceil(ms / 60000);
    if (minutes < 1) return "Less than a minute";
    if (minutes === 1) return "~1 minute";
    return `~${minutes} minutes`;
  };

  const hasProgress = progress !== undefined && progress > 0;
  const hasQueue = queuePosition !== undefined && queuePosition > 0;

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[60]">
      <div
        className="rounded-2xl shadow-2xl w-full max-w-sm mx-4 overflow-hidden border"
        style={{
          backgroundColor: "var(--color-surface, #fff)",
          borderColor: "var(--color-border, #e5e7eb)",
        }}
      >
        <div className="h-1.5 bg-gradient-to-r from-purple-500 via-indigo-500 to-purple-600">
          {hasProgress && (
            <div
              className="h-full bg-white/30 transition-all duration-500 ease-out"
              style={{ width: `${100 - progress}%`, marginLeft: "auto" }}
            />
          )}
        </div>

        <div className="p-6">
          <div className="flex flex-col items-center text-center mb-4">
            <div className="relative mb-3">
              <div className="w-12 h-12 rounded-full bg-gradient-to-br from-purple-100 to-indigo-100 flex items-center justify-center">
                <Loader2 size={22} className="text-purple-600 animate-spin" />
              </div>
              {hasProgress && (
                <div className="absolute -bottom-1 -right-1 w-5 h-5 rounded-full bg-purple-600 text-white text-[9px] font-bold flex items-center justify-center shadow-sm">
                  {Math.round(progress)}
                </div>
              )}
            </div>
            <h3 className="text-base font-semibold truncate w-full" style={{ color: "var(--color-text)" }}>
              {message || "Loading Ontology"}
            </h3>
            {projectName ? (
              <p className="text-sm truncate w-full mt-0.5" style={{ color: "var(--color-text-secondary)" }}>
                {projectName}
              </p>
            ) : (
              <p className="text-sm mt-0.5" style={{ color: "var(--color-text-secondary)" }}>
                Processing your ontology data…
              </p>
            )}
          </div>

          {hasProgress && (
            <div className="mb-4">
              <div className="flex items-center justify-between mb-1.5">
                <span className="text-xs font-medium" style={{ color: "var(--color-text-secondary)" }}>
                  Progress
                </span>
                <span className="text-xs font-bold text-purple-600">{Math.round(progress)}%</span>
              </div>
              <div className="w-full h-2 bg-gray-100 rounded-full overflow-hidden">
                <div
                  className="h-full rounded-full bg-gradient-to-r from-purple-500 to-indigo-500 transition-all duration-500 ease-out"
                  style={{ width: `${progress}%` }}
                />
              </div>
            </div>
          )}

          {loadingStatusMessage && (
            <div
              className="flex items-center justify-center gap-2 text-xs font-medium px-3 py-2 rounded-lg mb-4 text-center"
              style={{ backgroundColor: "rgba(99,102,241,0.08)", color: "rgb(79,70,229)" }}
            >
              <Sparkles size={13} className="flex-shrink-0 opacity-70" />
              <span>{loadingStatusMessage}</span>
            </div>
          )}

          {hasQueue && (
            <div
              className="rounded-lg px-3.5 py-3 mb-4 border"
              style={{
                backgroundColor: "rgba(147,51,234,0.05)",
                borderColor: "rgba(147,51,234,0.15)",
              }}
            >
              <div className="flex items-center justify-between mb-2">
                <div className="flex items-center gap-1.5 text-xs font-semibold text-purple-700">
                  <Clock size={12} />
                  <span>Queue Position #{queuePosition}</span>
                </div>
                {totalInQueue !== undefined && totalInQueue > 0 && (
                  <span className="text-[10px] font-medium text-purple-500 bg-purple-100 px-1.5 py-0.5 rounded-full">
                    {totalInQueue} in queue
                  </span>
                )}
              </div>
              <div className="text-xs text-purple-600 space-y-1">
                {queuePosition > 1 && (
                  <div className="flex items-center gap-1.5">
                    <Users size={11} className="opacity-70" />
                    <span>
                      {queuePosition - 1} file{queuePosition - 1 !== 1 ? "s" : ""} ahead of you
                    </span>
                  </div>
                )}
                {estimatedWaitTimeMs !== undefined && estimatedWaitTimeMs > 0 && (
                  <div className="flex items-center gap-1.5">
                    <Clock size={11} className="opacity-70" />
                    <span>Estimated wait: {formatWaitTime(estimatedWaitTimeMs)}</span>
                  </div>
                )}
              </div>
            </div>
          )}

          <div className="flex items-center justify-center py-2 text-xs font-medium text-purple-600">
            <span>{hasQueue ? "Waiting in queue…" : hasProgress ? "Importing…" : ""}</span>
          </div>
        </div>
      </div>
    </div>
  );
};

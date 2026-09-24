import React from "react";
import { Loader2, CheckCircle, AlertCircle, ShieldAlert } from "lucide-react";
import type { ProposedEditGroupResult } from "../services/codeAssistantSession";
import type { GroupDecision } from "../services/codeAssistantApplyQueue";

export type { GroupDecision };

export interface ApplyAllRunState {
  running: boolean;
  position: number;
  total: number;
  cancelRequested: boolean;
}

interface CodeAssistantReviewGroupsProps {
  groups: ProposedEditGroupResult[];
  decisions: Record<string, GroupDecision>;
  errors?: Record<string, string>;
  onApply: (serverGroupId: string) => void;
  onSkip: (serverGroupId: string) => void;
  onApplyAll?: () => void;
  onCancelApplyAll?: () => void;
  applyAllRun?: ApplyAllRunState | null;
  applyAllSummary?: string | null;
  applyBlockedReason?: string | null;
  applyBusy?: boolean;
}

export const CodeAssistantReviewGroups: React.FC<CodeAssistantReviewGroupsProps> = ({
  groups,
  decisions,
  errors,
  onApply,
  onSkip,
  onApplyAll,
  onCancelApplyAll,
  applyAllRun,
  applyAllSummary,
  applyBlockedReason,
  applyBusy = false,
}) => {
  const pendingCount = groups.filter((g) => (decisions[g.serverGroupId] ?? "pending") === "pending" && g.validation.passed).length;
  const isApplyingAny = groups.some((g) => decisions[g.serverGroupId] === "applying");
  const running = applyAllRun?.running === true;
  const applyLocked = Boolean(applyBlockedReason) || running || isApplyingAny || applyBusy;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm text-gray-600">Review each group. Nothing is applied until you approve it.</p>
        {onApplyAll && pendingCount > 1 && !running && (
          <button
            onClick={onApplyAll}
            disabled={applyLocked}
            title={applyBlockedReason ?? undefined}
            className="px-3 py-1.5 text-xs font-semibold text-white bg-green-700 rounded-md hover:bg-green-800 disabled:opacity-50 flex-shrink-0"
          >
            Apply All ({pendingCount})
          </button>
        )}
      </div>
      {running && applyAllRun && (
        <div className="flex items-center gap-2 px-3 py-2 bg-green-50 border border-green-200 rounded-md text-xs text-green-900">
          <Loader2 size={14} className="animate-spin" />
          <span>
            Applying {applyAllRun.position} of {applyAllRun.total}
            {applyAllRun.cancelRequested ? " — stopping after this group" : ""}
          </span>
          {onCancelApplyAll && !applyAllRun.cancelRequested && (
            <button onClick={onCancelApplyAll} className="ml-auto font-semibold text-green-800 hover:underline">
              Stop after this group
            </button>
          )}
        </div>
      )}
      {applyAllSummary && !running && (
        <div className="px-3 py-2 bg-gray-100 border border-gray-200 rounded-md text-xs text-gray-800">{applyAllSummary}</div>
      )}
      {applyBlockedReason && (
        <div className="flex items-start gap-2 px-3 py-2 bg-amber-50 border border-amber-200 rounded-md text-xs text-amber-900">
          <AlertCircle size={14} className="flex-shrink-0 mt-0.5" />
          <span>{applyBlockedReason}</span>
        </div>
      )}
      {groups.map((group) => {
        const decision = decisions[group.serverGroupId] ?? "pending";
        const error = errors?.[group.serverGroupId];
        return (
          <div key={group.serverGroupId} className="border-2 border-gray-200 rounded-lg p-4 space-y-2">
            {!group.validation.passed && (
              <div className="flex items-center gap-2 text-red-700 text-xs font-semibold">
                <AlertCircle size={14} />
                Failed validation — cannot apply
              </div>
            )}
            {group.diff.map((d, i) => (
              <div key={i} className="text-xs font-mono bg-gray-50 rounded p-2">
                <div className="text-gray-500">{d.targetPath}</div>
                <div className="text-red-600 line-through">{d.before}</div>
                <div className="text-green-700">{d.after}</div>
              </div>
            ))}
            <div className="flex flex-wrap items-center gap-2 pt-1">
              {decision === "pending" && group.validation.passed && (
                <>
                  <button
                    onClick={() => onApply(group.serverGroupId)}
                    disabled={applyLocked}
                    title={applyBlockedReason ?? undefined}
                    className="px-3 py-1.5 text-xs font-semibold text-white bg-green-600 rounded-md hover:bg-green-700 disabled:opacity-50"
                  >
                    Apply
                  </button>
                  <button
                    onClick={() => onSkip(group.serverGroupId)}
                    disabled={running}
                    className="px-3 py-1.5 text-xs font-semibold text-gray-700 bg-gray-100 rounded-md hover:bg-gray-200 disabled:opacity-50"
                  >
                    Skip
                  </button>
                  {error && (
                    <span className="flex items-center gap-1 text-xs text-red-700">
                      <AlertCircle size={14} /> {error}
                    </span>
                  )}
                </>
              )}
              {decision === "applying" && <Loader2 size={16} className="animate-spin text-gray-500" />}
              {decision === "applied" && (
                <span className="flex items-center gap-1 text-xs text-green-700 font-semibold">
                  <CheckCircle size={14} /> Applied
                </span>
              )}
              {decision === "skipped" && <span className="text-xs text-gray-500">Skipped</span>}
              {decision === "stale" && (
                <span className="flex items-center gap-1 text-xs text-amber-700 font-semibold">
                  <AlertCircle size={14} /> Changed by another applied group — ask again to get a fresh proposal
                </span>
              )}
              {decision === "conflict" && (
                <span className="flex items-center gap-1 text-xs text-amber-700 font-semibold">
                  <AlertCircle size={14} /> {error || "The document changed since this was checked"} — ask again to get a fresh proposal
                </span>
              )}
              {decision === "recovery" && (
                <span className="flex items-center gap-1 text-xs text-red-800 font-semibold">
                  <ShieldAlert size={14} /> {error || "This change didn't finish cleanly and the project needs checking."}
                </span>
              )}
              {decision === "failed" && (
                <span className="flex items-center gap-1 text-xs text-red-700" title={error}>
                  <AlertCircle size={14} /> {error || "Apply failed"}
                </span>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
};

export default CodeAssistantReviewGroups;

import React from "react";
import { Loader2, CheckCircle, AlertCircle } from "lucide-react";
import type { ProposedEditGroupResult } from "../services/codeAssistantSession";

export type GroupDecision = "pending" | "applying" | "applied" | "skipped" | "failed" | "stale";

interface CodeAssistantReviewGroupsProps {
  groups: ProposedEditGroupResult[];
  decisions: Record<string, GroupDecision>;
  errors?: Record<string, string>;
  onApply: (serverGroupId: string) => void;
  onSkip: (serverGroupId: string) => void;
  onApplyAll?: () => void;
}

export const CodeAssistantReviewGroups: React.FC<CodeAssistantReviewGroupsProps> = ({
  groups,
  decisions,
  errors,
  onApply,
  onSkip,
  onApplyAll,
}) => {
  const pendingCount = groups.filter((g) => (decisions[g.serverGroupId] ?? "pending") === "pending" && g.validation.passed).length;
  const isApplyingAny = groups.some((g) => decisions[g.serverGroupId] === "applying");

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm text-gray-600">Review each group. Nothing is applied until you approve it.</p>
        {onApplyAll && pendingCount > 1 && (
          <button
            onClick={onApplyAll}
            disabled={isApplyingAny}
            className="px-3 py-1.5 text-xs font-semibold text-white bg-green-700 rounded-md hover:bg-green-800 disabled:opacity-50 flex-shrink-0"
          >
            Apply All ({pendingCount})
          </button>
        )}
      </div>
      {groups.map((group) => {
        const decision = decisions[group.serverGroupId] ?? "pending";
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
            <div className="flex items-center gap-2 pt-1">
              {decision === "pending" && (
                <>
                  <button
                    onClick={() => onApply(group.serverGroupId)}
                    className="px-3 py-1.5 text-xs font-semibold text-white bg-green-600 rounded-md hover:bg-green-700"
                  >
                    Apply
                  </button>
                  <button
                    onClick={() => onSkip(group.serverGroupId)}
                    className="px-3 py-1.5 text-xs font-semibold text-gray-700 bg-gray-100 rounded-md hover:bg-gray-200"
                  >
                    Skip
                  </button>
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
              {decision === "failed" && (
                <span className="flex items-center gap-1 text-xs text-red-700" title={errors?.[group.serverGroupId]}>
                  <AlertCircle size={14} /> {errors?.[group.serverGroupId] || "Apply failed"}
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

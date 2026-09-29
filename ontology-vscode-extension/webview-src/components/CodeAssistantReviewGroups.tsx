import React from "react";
import { Loader2, AlertCircle } from "lucide-react";
import type { ProposedEditGroupResult } from "../services/codeAssistantSession";
import type { GroupDecision } from "../services/codeAssistantApplyQueue";
import { ReviewGroupCard } from "./CodeAssistantReviewGroupCard";
import type { GroupUndoState } from "../services/codeAssistantUndo";

export type { GroupDecision };

export interface GroupUndoHandlers {
  onRequest: (serverGroupId: string) => void;
  onConfirm: (serverGroupId: string) => void;
  onCancel: (serverGroupId: string) => void;
}

const APPLY_IN_PROGRESS_REASON = "Wait for the current apply to finish.";

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
  onShowInCodeView?: (format: string, startLine: number) => void;
  undoStates?: Record<string, GroupUndoState>;
  undoHandlers?: GroupUndoHandlers;
}

const ReviewGroupsHeader: React.FC<{
  showApplyAll: boolean;
  pendingCount: number;
  applyLocked: boolean;
  applyBlockedReason?: string | null;
  onApplyAll?: () => void;
}> = ({ showApplyAll, pendingCount, applyLocked, applyBlockedReason, onApplyAll }) => (
  <div className="flex items-center justify-between gap-2">
    <p className="text-sm text-gray-600">Review each group. Nothing is applied until you approve it.</p>
    {showApplyAll && (
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
);

const ApplyBlockedNotice: React.FC<{ reason: string }> = ({ reason }) => (
  <div className="flex items-start gap-2 px-3 py-2 bg-amber-50 border border-amber-200 rounded-md text-xs text-amber-900">
    <AlertCircle size={14} className="flex-shrink-0 mt-0.5" />
    <span>{reason}</span>
  </div>
);

const ApplyAllProgress: React.FC<{ run: ApplyAllRunState; onCancelApplyAll?: () => void }> = ({ run, onCancelApplyAll }) => (
  <div className="flex items-center gap-2 px-3 py-2 bg-green-50 border border-green-200 rounded-md text-xs text-green-900">
    <Loader2 size={14} className="animate-spin" aria-hidden="true" />
    <span role="status" aria-live="polite">
      Applying {run.position} of {run.total}
      {run.cancelRequested ? " — stopping after this group" : ""}
    </span>
    {onCancelApplyAll && !run.cancelRequested && (
      <button onClick={onCancelApplyAll} className="ml-auto font-semibold text-green-800 hover:underline">
        Stop after this group
      </button>
    )}
  </div>
);

function undoBinding(
  serverGroupId: string,
  handlers: GroupUndoHandlers | undefined,
  states: Record<string, GroupUndoState> | undefined,
  disabledReason: string | null,
) {
  if (!handlers) return undefined;
  return {
    state: states?.[serverGroupId],
    disabledReason,
    onRequest: () => handlers.onRequest(serverGroupId),
    onConfirm: () => handlers.onConfirm(serverGroupId),
    onCancel: () => handlers.onCancel(serverGroupId),
  };
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
  onShowInCodeView,
  undoStates,
  undoHandlers,
}) => {
  const pendingCount = groups.filter((g) => (decisions[g.serverGroupId] ?? "pending") === "pending" && g.validation.passed).length;
  const isApplyingAny = groups.some((g) => decisions[g.serverGroupId] === "applying");
  const running = applyAllRun?.running === true;
  const applyLocked = Boolean(applyBlockedReason) || running || isApplyingAny || applyBusy;
  const undoDisabledReason = applyBlockedReason || (applyLocked ? APPLY_IN_PROGRESS_REASON : null);

  return (
    <div className="space-y-4">
      <ReviewGroupsHeader
        showApplyAll={Boolean(onApplyAll) && pendingCount > 1 && !running}
        pendingCount={pendingCount}
        applyLocked={applyLocked}
        applyBlockedReason={applyBlockedReason}
        onApplyAll={onApplyAll}
      />
      {running && applyAllRun && <ApplyAllProgress run={applyAllRun} onCancelApplyAll={onCancelApplyAll} />}
      {applyAllSummary && !running && (
        <div role="status" className="px-3 py-2 bg-gray-100 border border-gray-200 rounded-md text-xs text-gray-800">{applyAllSummary}</div>
      )}
      {applyBlockedReason && <ApplyBlockedNotice reason={applyBlockedReason} />}
      {groups.map((group) => (
        <ReviewGroupCard
          key={group.serverGroupId}
          group={group}
          decision={decisions[group.serverGroupId] ?? "pending"}
          error={errors?.[group.serverGroupId]}
          onShowInCodeView={onShowInCodeView}
          applyLocked={applyLocked}
          running={running}
          applyBlockedReason={applyBlockedReason}
          onApply={onApply}
          onSkip={onSkip}
          undo={undoBinding(group.serverGroupId, undoHandlers, undoStates, undoDisabledReason)}
        />
      ))}
    </div>
  );
};

export default CodeAssistantReviewGroups;

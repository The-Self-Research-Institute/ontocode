import React from "react";
import { Loader2, CheckCircle, AlertCircle, ShieldAlert } from "lucide-react";
import type { ProposedEditGroupResult } from "../services/codeAssistantSession";
import type { GroupDecision } from "../services/codeAssistantApplyQueue";
import { CodeAssistantDiffEntry } from "./CodeAssistantDiffEntry";
import { CodeAssistantCheckNotes, CodeAssistantFailedChecks, CodeAssistantPendingChecks } from "./CodeAssistantFailedChecks";

export function hasPendingCheck(group: ProposedEditGroupResult): boolean {
  return group.validation.checks.some((c) => c.status === "pending");
}
import { CodeAssistantUndoControls, type UndoControlsProps } from "./CodeAssistantUndoControls";

const PendingGroupActions: React.FC<{
  serverGroupId: string;
  error?: string;
  applyLocked: boolean;
  running: boolean;
  applyBlockedReason?: string | null;
  onApply: (serverGroupId: string) => void;
  onSkip: (serverGroupId: string) => void;
}> = ({ serverGroupId, error, applyLocked, running, applyBlockedReason, onApply, onSkip }) => (
  <>
    <button
      onClick={() => onApply(serverGroupId)}
      disabled={applyLocked}
      title={applyBlockedReason ?? undefined}
      className="px-3 py-1.5 text-xs font-semibold text-white bg-green-600 rounded-md hover:bg-green-700 disabled:opacity-50"
    >
      Apply
    </button>
    <button
      onClick={() => onSkip(serverGroupId)}
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
);

const DecisionStatus: React.FC<{ decision: GroupDecision; error?: string }> = ({ decision, error }) => {
  if (decision === "applying") return <Loader2 size={16} className="animate-spin text-gray-500" aria-label="Applying" />;
  if (decision === "applied") {
    return (
      <span className="flex items-center gap-1 text-xs text-green-700 font-semibold">
        <CheckCircle size={14} /> Applied
      </span>
    );
  }
  if (decision === "skipped") return <span className="text-xs text-gray-500">Skipped</span>;
  if (decision === "stale") {
    return (
      <span className="flex items-center gap-1 text-xs text-amber-700 font-semibold">
        <AlertCircle size={14} /> Changed by another applied group — ask again to get a fresh proposal
      </span>
    );
  }
  if (decision === "conflict") {
    return (
      <span className="flex items-center gap-1 text-xs text-amber-700 font-semibold">
        <AlertCircle size={14} /> {error || "The document changed since this was checked"} — ask again to get a fresh proposal
      </span>
    );
  }
  if (decision === "recovery") {
    return (
      <span className="flex items-center gap-1 text-xs text-red-800 font-semibold">
        <ShieldAlert size={14} /> {error || "This change didn't finish cleanly and the project needs checking."}
      </span>
    );
  }
  if (decision === "failed") {
    if (!error) return null;
    return (
      <span className="flex items-center gap-1 text-xs text-red-700" title={error}>
        <AlertCircle size={14} /> {error}
      </span>
    );
  }
  return null;
};

type GroupCardProps = Omit<React.ComponentProps<typeof PendingGroupActions>, "serverGroupId" | "error"> & {
  group: ProposedEditGroupResult;
  decision: GroupDecision;
  error?: string;
  onShowInCodeView?: (format: string, startLine: number) => void;
  undo?: UndoControlsProps;
};

const GroupBody: React.FC<{ group: ProposedEditGroupResult; dimmed: boolean; onShowInCodeView?: GroupCardProps["onShowInCodeView"] }> = ({
  group,
  dimmed,
  onShowInCodeView,
}) => (
  <div className={dimmed ? "space-y-2 opacity-50" : "space-y-2"}>
    {!group.validation.passed && (
      <div className="space-y-1">
        <div className="flex items-center gap-2 text-red-700 text-xs font-semibold">
          <AlertCircle size={14} />
          Failed validation — cannot apply
        </div>
        <CodeAssistantFailedChecks checks={group.validation.checks} />
      </div>
    )}
    {group.validation.passed && <CodeAssistantCheckNotes checks={group.validation.checks} />}
    <CodeAssistantPendingChecks checks={group.validation.checks} />
    {group.diff.map((d, i) => (
      <CodeAssistantDiffEntry key={i} entry={d} onShowInCodeView={onShowInCodeView} />
    ))}
  </div>
);

export const ReviewGroupCard: React.FC<GroupCardProps> = ({ group, decision, error, onShowInCodeView, undo, ...actions }) => {
  const undone = decision === "applied" && undo?.state?.undone === true;
  return (
    <div className="border-2 border-gray-200 rounded-lg p-4 space-y-2" data-undone={undone ? "true" : undefined}>
      <GroupBody group={group} dimmed={undone} onShowInCodeView={onShowInCodeView} />
      <div className="flex flex-wrap items-center gap-2 pt-1">
        {decision === "pending" && group.validation.passed && !hasPendingCheck(group) && (
          <PendingGroupActions serverGroupId={group.serverGroupId} error={error} {...actions} />
        )}
        {!undone && <DecisionStatus decision={decision} error={error} />}
        {decision === "applied" && undo && <CodeAssistantUndoControls {...undo} />}
      </div>
    </div>
  );
};

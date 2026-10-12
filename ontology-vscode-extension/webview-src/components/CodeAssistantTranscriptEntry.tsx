import React from "react";
import { AlertCircle, Copy, Check } from "lucide-react";
import { CodeAssistantContextUsed } from "./CodeAssistantContextUsed";
import { CodeAssistantReviewGroups, type GroupUndoHandlers } from "./CodeAssistantReviewGroups";
import { CodeAssistantDeadEndNotice } from "./CodeAssistantDeadEndNotice";
import { CodeAssistantMarkdown } from "./CodeAssistantMarkdown";
import { ACTIONS, type CodeAssistantAction } from "./codeAssistantPanelHelpers";
import type { ChatEntry, PromptToRetry, ReviewEntry } from "./codeAssistantChatEntries";

export interface ReviewHandlers {
  onApply: (entry: ReviewEntry, groupId: string) => void;
  onSkip: (entry: ReviewEntry, groupId: string) => void;
  onApplyAll: (entry: ReviewEntry) => void;
  onCancelApplyAll: (entry: ReviewEntry) => void;
  applyBusy: boolean;
  applyBlockedReason: string | null;
  showInCodeViewFor: (entry: ReviewEntry) => ((format: string, startLine: number) => void) | undefined;
  undoFor?: (entry: ReviewEntry) => GroupUndoHandlers | undefined;
}

interface TranscriptEntryProps {
  entry: ChatEntry;
  copied: boolean;
  onCopy: (entryId: string, text: string) => void;
  review: ReviewHandlers;
  deadEndActive: boolean;
  now: number;
  onResubmit: (retry: PromptToRetry | undefined) => void;
  onSignIn: () => void;
}

function actionLabel(action: CodeAssistantAction): string {
  return ACTIONS.find((a) => a.id === action)?.label ?? action;
}

const ReviewCard: React.FC<{ entry: ReviewEntry; review: ReviewHandlers }> = ({ entry, review }) => (
  <div className="bg-gray-50 border border-gray-200 rounded-lg p-3 chat-message-enter">
    {entry.explanation && (
      <div className="text-sm text-gray-800">
        <CodeAssistantMarkdown text={entry.explanation} />
      </div>
    )}
    <CodeAssistantReviewGroups
      groups={entry.groups}
      decisions={entry.decisions}
      errors={entry.errors}
      onApply={(groupId) => review.onApply(entry, groupId)}
      onSkip={(groupId) => review.onSkip(entry, groupId)}
      onApplyAll={() => review.onApplyAll(entry)}
      onCancelApplyAll={() => review.onCancelApplyAll(entry)}
      applyAllRun={entry.applyAllRun ?? null}
      applyAllSummary={entry.applyAllSummary ?? null}
      applyBusy={review.applyBusy}
      applyBlockedReason={review.applyBlockedReason}
      onShowInCodeView={review.showInCodeViewFor(entry)}
      undoStates={entry.undo}
      undoHandlers={review.undoFor?.(entry)}
    />
    <CodeAssistantContextUsed events={entry.contextUsed} usage={entry.usage} />
  </div>
);

export const CodeAssistantTranscriptEntry: React.FC<TranscriptEntryProps> = (props) => {
  const { entry } = props;
  if (entry.role === "user") {
    return (
      <div className="flex justify-end chat-message-enter">
        <div className="max-w-[85%] bg-purple-600 text-white rounded-lg rounded-br-sm px-3 py-2">
          <div className="text-[10px] uppercase tracking-wide text-purple-200 mb-0.5">{actionLabel(entry.action)}</div>
          <div className="text-sm whitespace-pre-wrap">{entry.text}</div>
        </div>
      </div>
    );
  }
  if (entry.kind === "answer") {
    return (
      <div className="flex flex-col items-start chat-message-enter">
        <div className="max-w-[85%] min-w-0 bg-gray-100 rounded-lg rounded-bl-sm px-3 py-2 text-sm text-gray-800">
          <CodeAssistantMarkdown text={entry.text} />
        </div>
        <div className="flex items-center gap-3 mt-1">
          <button
            onClick={() => props.onCopy(entry.id, entry.text)}
            className="flex items-center gap-1 text-xs text-gray-400 hover:text-gray-600"
          >
            {props.copied ? <Check size={12} /> : <Copy size={12} />}
            {props.copied ? "Copied" : "Copy"}
          </button>
          <CodeAssistantContextUsed events={entry.contextUsed} usage={entry.usage} />
        </div>
      </div>
    );
  }
  if (entry.kind === "review") return <ReviewCard entry={entry} review={props.review} />;
  if (entry.deadEnd) {
    return (
      <CodeAssistantDeadEndNotice
        deadEnd={entry.deadEnd}
        active={props.deadEndActive}
        retryAt={entry.retryAt ?? null}
        now={props.now}
        onResubmit={() => props.onResubmit(entry.retry)}
        onSignIn={props.onSignIn}
      />
    );
  }
  return (
    <div className="flex items-start gap-2 px-3 py-2 bg-red-50 border border-red-200 rounded-lg text-red-900 text-sm chat-message-enter">
      <AlertCircle size={16} className="flex-shrink-0 mt-0.5" />
      <span className="min-w-0 break-words">{entry.text}</span>
    </div>
  );
};

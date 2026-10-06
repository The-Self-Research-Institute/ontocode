import React, { useEffect, useRef, useState } from "react";
import { CodeAssistantActionChips } from "./CodeAssistantActionChips";
import { CodeAssistantMarkdown } from "./CodeAssistantMarkdown";
import { CodeAssistantTranscriptEntry, type ReviewHandlers } from "./CodeAssistantTranscriptEntry";
import type { CodeAssistantAction } from "./codeAssistantPanelHelpers";
import type { ChatEntry, PromptToRetry } from "./codeAssistantChatEntries";
import type { DispatchBudget } from "../services/codeAssistantLoopDispatch";

interface CodeAssistantTranscriptProps {
  entries: ChatEntry[];
  ready: boolean;
  action: CodeAssistantAction;
  onSelectAction: (action: CodeAssistantAction) => void;
  editLocked: boolean;
  editLockedMessage: string;
  busy: boolean;
  statusText: string;
  liveBudget?: DispatchBudget;
  stageStartedAt: number | null;
  draft: string;
  now: number;
  review: ReviewHandlers;
  onResubmit: (retry: PromptToRetry | undefined) => void;
  onSignIn: () => void;
  onCancel: () => void;
}

const EMPTY_READY = "Ask a question about this document, or request an edit. Answers are grounded in the actual ontology content.";
const EMPTY_NOT_READY = "Pick a model below to add your API key, then ask a question or request an edit.";

const ELAPSED_DISPLAY_THRESHOLD_MS = 3_000;

const BudgetPill: React.FC<{ budget: DispatchBudget }> = ({ budget }) => (
  <span className="rounded-full border border-gray-300 bg-white px-2 py-0.5 text-[11px] font-semibold text-gray-600 whitespace-nowrap">
    {budget.retrievalAttemptsRemaining} calls left · {budget.tokenBudgetRemaining} tokens left
  </span>
);

const BusyIndicator: React.FC<{
  statusText: string;
  stageStartedAt: number | null;
  now: number;
  liveBudget?: DispatchBudget;
  onCancel: () => void;
}> = ({ statusText, stageStartedAt, now, liveBudget, onCancel }) => {
  const elapsedMs = stageStartedAt ? now - stageStartedAt : 0;
  const elapsedSeconds = elapsedMs >= ELAPSED_DISPLAY_THRESHOLD_MS ? Math.round(elapsedMs / 1000) : null;
  return (
    <div className="flex items-center gap-2 text-gray-500 text-sm px-1">
      <span className="flex items-center gap-0.5">
        {[0, 150, 300].map((delay) => (
          <span key={delay} className="w-1.5 h-1.5 rounded-full bg-purple-500 animate-bounce" style={{ animationDelay: `${delay}ms` }} />
        ))}
      </span>
      <span role="status" aria-live="polite">
        {statusText || "Working..."}
        {elapsedSeconds !== null && <span className="text-gray-400"> ({elapsedSeconds}s)</span>}
      </span>
      {liveBudget && <BudgetPill budget={liveBudget} />}
      <button onClick={onCancel} className="ml-auto text-xs font-semibold text-purple-700 hover:underline">
        Cancel
      </button>
    </div>
  );
};

export const CodeAssistantTranscript: React.FC<CodeAssistantTranscriptProps> = (props) => {
  const { entries, busy, statusText, stageStartedAt, now } = props;
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const endRef = useRef<HTMLDivElement | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const lastEntryId = entries.length > 0 ? entries[entries.length - 1].id : undefined;

  useEffect(() => {
    const container = scrollRef.current;
    if (!container) return;
    const distanceFromBottom = container.scrollHeight - container.scrollTop - container.clientHeight;
    if (distanceFromBottom < 120) endRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [entries, busy, statusText, props.draft]);

  const copyText = async (entryId: string, text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedId(entryId);
      setTimeout(() => setCopiedId((current) => (current === entryId ? null : current)), 1500);
    } catch {
    }
  };

  return (
    <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-4 space-y-3">
      {entries.length === 0 && (
        <div className="space-y-3">
          <p className="text-sm text-gray-500">{props.ready ? EMPTY_READY : EMPTY_NOT_READY}</p>
          <CodeAssistantActionChips
            action={props.action}
            onSelect={props.onSelectAction}
            editLocked={props.editLocked}
            editLockedMessage={props.editLockedMessage}
          />
        </div>
      )}
      {entries.map((entry) => (
        <CodeAssistantTranscriptEntry
          key={entry.id}
          entry={entry}
          copied={copiedId === entry.id}
          onCopy={(id, text) => void copyText(id, text)}
          review={props.review}
          deadEndActive={!busy && entry.id === lastEntryId}
          now={props.now}
          onResubmit={props.onResubmit}
          onSignIn={props.onSignIn}
        />
      ))}
      {busy && props.draft && (
        <div className="flex flex-col items-start" data-streaming-answer>
          <div className="max-w-[85%] min-w-0 bg-gray-100 rounded-lg rounded-bl-sm px-3 py-2 text-sm text-gray-800">
            <CodeAssistantMarkdown text={props.draft} />
          </div>
        </div>
      )}
      {busy && (
        <BusyIndicator
          statusText={statusText}
          stageStartedAt={stageStartedAt}
          now={now}
          liveBudget={props.liveBudget}
          onCancel={props.onCancel}
        />
      )}
      <div ref={endRef} />
    </div>
  );
};

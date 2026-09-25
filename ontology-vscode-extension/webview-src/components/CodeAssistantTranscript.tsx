import React, { useEffect, useRef, useState } from "react";
import { CodeAssistantActionChips } from "./CodeAssistantActionChips";
import { CodeAssistantTranscriptEntry, type ReviewHandlers } from "./CodeAssistantTranscriptEntry";
import type { CodeAssistantAction } from "./codeAssistantPanelHelpers";
import type { ChatEntry, PromptToRetry } from "./codeAssistantChatEntries";

interface CodeAssistantTranscriptProps {
  entries: ChatEntry[];
  ready: boolean;
  action: CodeAssistantAction;
  onSelectAction: (action: CodeAssistantAction) => void;
  editLocked: boolean;
  editLockedMessage: string;
  busy: boolean;
  statusText: string;
  now: number;
  review: ReviewHandlers;
  onResubmit: (retry: PromptToRetry | undefined) => void;
  onSignIn: () => void;
  onCancel: () => void;
}

const EMPTY_READY = "Ask a question about this document, or request an edit. Answers are grounded in the actual ontology content.";
const EMPTY_NOT_READY = "Pick a model below to add your API key, then ask a question or request an edit.";

const BusyIndicator: React.FC<{ statusText: string; onCancel: () => void }> = ({ statusText, onCancel }) => (
  <div className="flex items-center gap-2 text-gray-500 text-sm px-1">
    <span className="flex items-center gap-0.5">
      {[0, 150, 300].map((delay) => (
        <span key={delay} className="w-1.5 h-1.5 rounded-full bg-purple-500 animate-bounce" style={{ animationDelay: `${delay}ms` }} />
      ))}
    </span>
    <span role="status" aria-live="polite">{statusText || "Working..."}</span>
    <button onClick={onCancel} className="ml-auto text-xs font-semibold text-purple-700 hover:underline">
      Cancel
    </button>
  </div>
);

export const CodeAssistantTranscript: React.FC<CodeAssistantTranscriptProps> = (props) => {
  const { entries, busy, statusText } = props;
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const endRef = useRef<HTMLDivElement | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const lastEntryId = entries.length > 0 ? entries[entries.length - 1].id : undefined;

  useEffect(() => {
    const container = scrollRef.current;
    if (!container) return;
    const distanceFromBottom = container.scrollHeight - container.scrollTop - container.clientHeight;
    if (distanceFromBottom < 120) endRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [entries, busy, statusText]);

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
      {busy && <BusyIndicator statusText={statusText} onCancel={props.onCancel} />}
      <div ref={endRef} />
    </div>
  );
};

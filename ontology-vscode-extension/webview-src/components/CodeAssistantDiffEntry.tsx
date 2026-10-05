import React, { useState } from "react";
import type { ProposedDiffEntry } from "../services/codeAssistantSession";
import { resolveLabelOverlaySegments } from "./codeAssistantLabelOverlay";
import { useCodeAssistantLabelOverlay } from "./CodeAssistantLabelOverlayContext";

const COLLAPSED_LINES = 12;

interface CodeAssistantDiffEntryProps {
  entry: ProposedDiffEntry;
  onShowInCodeView?: (format: string, startLine: number) => void;
}

function lineLabel(startLine: number, lineCount: number): string {
  const first = startLine + 1;
  if (lineCount <= 1) return `line ${first}`;
  return `lines ${first}–${first + lineCount - 1}`;
}

function clip(text: string, expanded: boolean): { text: string; hidden: number } {
  const lines = text.split("\n");
  if (expanded || lines.length <= COLLAPSED_LINES) return { text, hidden: 0 };
  return { text: lines.slice(0, COLLAPSED_LINES).join("\n"), hidden: lines.length - COLLAPSED_LINES };
}

const DiffText: React.FC<{ text: string }> = ({ text }) => {
  const { enabled, labelMap, prefixMappings } = useCodeAssistantLabelOverlay();
  if (!enabled || labelMap.size === 0) {
    return <>{text}</>;
  }

  const segments = resolveLabelOverlaySegments(text, labelMap, prefixMappings);
  return (
    <>
      {segments.map((seg, i) =>
        seg.label ? (
          <span key={i} className="group relative inline-block border-b border-dotted border-current cursor-default">
            {seg.label}
            <span className="pointer-events-none absolute left-0 bottom-full z-10 mb-1 hidden whitespace-nowrap rounded bg-gray-900 px-1.5 py-0.5 text-[10px] font-mono font-normal text-white group-hover:block">
              {seg.text}
            </span>
          </span>
        ) : (
          <React.Fragment key={i}>{seg.text}</React.Fragment>
        ),
      )}
    </>
  );
};

export const CodeAssistantDiffEntry: React.FC<CodeAssistantDiffEntryProps> = ({ entry, onShowInCodeView }) => {
  const [expanded, setExpanded] = useState(false);
  const hasRange = typeof entry.startLine === "number";
  const before = clip(entry.before, expanded);
  const after = clip(entry.after, expanded);
  const hidden = Math.max(before.hidden, after.hidden);

  return (
    <div className="text-xs font-mono bg-gray-50 rounded p-2 space-y-1">
      <div className="flex items-center gap-2 text-gray-500">
        <span>
          {entry.targetPath}
          {hasRange && ` · ${entry.lineCount === 0 ? `insert at line ${(entry.startLine as number) + 1}` : lineLabel(entry.startLine as number, entry.lineCount ?? 1)}`}
        </span>
        {hasRange && onShowInCodeView && (
          <button
            type="button"
            onClick={() => onShowInCodeView(entry.targetPath, entry.startLine as number)}
            className="ml-auto font-sans font-semibold text-blue-700 hover:underline"
          >
            Show in Code View
          </button>
        )}
      </div>
      {entry.before && (
        <div className="text-red-600 line-through whitespace-pre-wrap break-all">
          <DiffText text={before.text} />
        </div>
      )}
      {entry.after && (
        <div className="text-green-700 whitespace-pre-wrap break-all">
          <DiffText text={after.text} />
        </div>
      )}
      {!entry.after && <div className="text-gray-500 font-sans italic">Removes these lines</div>}
      {hidden > 0 && (
        <button type="button" onClick={() => setExpanded(true)} className="font-sans text-blue-700 hover:underline">
          Show {hidden} more line{hidden === 1 ? "" : "s"}
        </button>
      )}
    </div>
  );
};

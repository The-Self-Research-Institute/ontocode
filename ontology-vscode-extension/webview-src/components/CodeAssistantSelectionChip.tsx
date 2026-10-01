import React from "react";
import { X } from "lucide-react";
import type { EditorSelectionContext } from "./codeSelection";

interface SelectionChipProps {
  selection: EditorSelectionContext & { pageStartLine: number };
  onClear?: () => void;
}

export const SelectionChip: React.FC<SelectionChipProps> = ({ selection, onClear }) => {
  const first = selection.startLine + selection.pageStartLine + 1;
  const last = selection.endLine + selection.pageStartLine + 1;
  const label = first === last ? `line ${first}` : `lines ${first}–${last}`;
  return (
    <div className="flex items-center gap-2 px-2 py-1 bg-purple-50 border border-purple-200 rounded-md text-xs text-purple-900">
      <span>Using your selection: {label}</span>
      {onClear && (
        <button type="button" onClick={onClear} aria-label="Stop using the selection" className="ml-auto p-0.5 rounded hover:bg-purple-100">
          <X size={12} />
        </button>
      )}
    </div>
  );
};

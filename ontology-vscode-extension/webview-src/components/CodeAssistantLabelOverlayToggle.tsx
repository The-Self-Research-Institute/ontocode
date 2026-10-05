import React from "react";
import { useCodeAssistantLabelOverlay } from "./CodeAssistantLabelOverlayContext";

export const LabelOverlayToggleRow: React.FC = () => {
  const { enabled, setEnabled, labelMap } = useCodeAssistantLabelOverlay();
  if (labelMap.size === 0) {
    return null;
  }
  return (
    <div className="flex items-center justify-between gap-2 px-3 py-2 border-t border-gray-100">
      <div className="min-w-0">
        <div className="text-xs font-medium text-gray-700">Show readable labels in diffs</div>
        <div className="text-[10px] text-gray-500">Hover a label to see its raw IRI.</div>
      </div>
      <button
        type="button"
        onClick={() => setEnabled(!enabled)}
        aria-label="Toggle readable labels in diffs"
        className={`relative h-5 w-9 flex-shrink-0 rounded-full transition-colors ${enabled ? "bg-purple-600" : "bg-gray-300"}`}
      >
        <span
          className={`absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-all ${enabled ? "left-4" : "left-0.5"}`}
        />
      </button>
    </div>
  );
};

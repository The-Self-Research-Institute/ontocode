import React from "react";
import { Settings } from "lucide-react";
import { REASONER_OPTIONS } from "./dashboardUtils";

export const ReasonerSettingsDialog = ({
  isOpen,
  selectedReasoner,
  isSynced,
  onSelectReasoner,
  onToggleSync,
  onClose,
}: {
  isOpen: boolean;
  selectedReasoner: string;
  isSynced: boolean;
  onSelectReasoner: (reasoner: string) => void;
  onToggleSync: () => void;
  onClose: () => void;
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-[65] bg-black/40 flex items-center justify-center">
      <div
        className="bg-white rounded-xl shadow-2xl w-full max-w-md mx-4 border"
        style={{ borderColor: "var(--color-border)" }}
      >
        <div className="px-5 py-3 border-b bg-gray-50 flex items-center justify-between">
          <div className="flex items-center gap-2 text-sm font-semibold text-gray-800">
            <Settings size={16} />
            Reasoner settings
          </div>
          <button onClick={onClose} className="text-xs text-gray-500 hover:text-gray-800">
            Close
          </button>
        </div>
        <div className="p-5 space-y-4 text-sm text-gray-800">
          <div>
            <div className="text-xs uppercase text-gray-500 font-semibold mb-1">Active reasoner</div>
            <select
              value={selectedReasoner}
              onChange={(event) => onSelectReasoner(event.target.value)}
              className="w-full border rounded px-3 py-2 text-sm"
            >
              {REASONER_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </div>
          <label className="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" checked={isSynced} onChange={onToggleSync} className="rounded border-gray-300" />
            Synchronize reasoner after edits
          </label>
          <p className="text-xs text-gray-500">Keep the reasoner in sync with edits, or run manually when needed.</p>
        </div>
      </div>
    </div>
  );
};

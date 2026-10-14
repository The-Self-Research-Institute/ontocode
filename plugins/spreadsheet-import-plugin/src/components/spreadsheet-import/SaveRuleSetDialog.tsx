import React, { useState } from "react";
import { X, Loader2 } from "lucide-react";

interface SaveRuleSetDialogProps {
  title: string;
  initialName: string;
  initialDescription?: string | null;
  saving: boolean;
  error: string | null;
  onCancel: () => void;
  onSave: (name: string, description: string) => void;
}

const SaveRuleSetDialog: React.FC<SaveRuleSetDialogProps> = ({
  title,
  initialName,
  initialDescription,
  saving,
  error,
  onCancel,
  onSave,
}) => {
  const [name, setName] = useState(initialName);
  const [description, setDescription] = useState(initialDescription ?? "");
  const inputClass =
    "w-full px-2 py-1 text-xs border rounded bg-theme-surface focus:outline-none focus:ring-1 focus:ring-purple-500";
  const canSave = name.trim().length > 0 && !saving;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onMouseDown={saving ? undefined : onCancel}>
      <form
        className="bg-theme-surface rounded-lg shadow-xl w-[min(440px,calc(100vw-2rem))]"
        onMouseDown={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          if (canSave) onSave(name.trim(), description.trim());
        }}
      >
        <div className="flex items-center justify-between px-4 py-2 border-b" style={{ borderColor: "var(--color-border)" }}>
          <h3 className="text-sm font-semibold">{title}</h3>
          <button type="button" onClick={onCancel} className="p-1 rounded hover:bg-gray-100" aria-label="Close">
            <X size={16} />
          </button>
        </div>
        <div className="p-4 space-y-3">
          <label className="block">
            <span className="text-xs text-gray-600">Name</span>
            <input
              autoFocus
              className={inputClass}
              value={name}
              maxLength={120}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. Sensor readings"
            />
          </label>
          <label className="block">
            <span className="text-xs text-gray-600">Note (optional)</span>
            <input
              className={inputClass}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="e.g. Monthly lab export, one reading per row"
            />
          </label>
          <p className="text-[11px] text-gray-400">
            Saved in this project. Load it later to run the same rules on a new file with the same columns.
          </p>
          {error && <p className="text-[11px] text-red-600">{error}</p>}
        </div>
        <div className="flex justify-end gap-2 px-4 py-2 border-t" style={{ borderColor: "var(--color-border)" }}>
          <button type="button" onClick={onCancel} disabled={saving} className="px-3 py-1 text-xs rounded border hover:bg-gray-50">
            Cancel
          </button>
          <button
            type="submit"
            disabled={!canSave}
            className="flex items-center gap-1.5 px-3 py-1 text-xs rounded bg-purple-600 text-white hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {saving && <Loader2 size={12} className="animate-spin" />} Save
          </button>
        </div>
      </form>
    </div>
  );
};

export default SaveRuleSetDialog;

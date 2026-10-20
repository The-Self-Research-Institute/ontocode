import React, { useEffect } from "react";
import { X, Loader2, Trash2 } from "lucide-react";

interface ConfirmDeleteDialogProps {
  title: string;
  message: React.ReactNode;
  busy?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

const ConfirmDeleteDialog: React.FC<ConfirmDeleteDialogProps> = ({ title, message, busy = false, onCancel, onConfirm }) => {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !busy) onCancel();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onCancel]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onMouseDown={busy ? undefined : onCancel}>
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="confirm-delete-title"
        className="bg-theme-surface rounded-lg shadow-xl w-[min(400px,calc(100vw-2rem))]"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-4 py-2 border-b" style={{ borderColor: "var(--color-border)" }}>
          <h3 id="confirm-delete-title" className="text-sm font-semibold">{title}</h3>
          <button type="button" onClick={onCancel} disabled={busy} className="p-1 rounded hover:bg-gray-100" aria-label="Close">
            <X size={16} />
          </button>
        </div>
        <div className="p-4 text-xs">{message}</div>
        <div className="flex justify-end gap-2 px-4 py-2 border-t" style={{ borderColor: "var(--color-border)" }}>
          <button type="button" autoFocus onClick={onCancel} disabled={busy} className="px-3 py-1 text-xs rounded border hover:bg-gray-50">
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy}
            className="flex items-center gap-1.5 px-3 py-1 text-xs rounded bg-red-600 text-white hover:bg-red-700 disabled:opacity-50"
          >
            {busy ? <Loader2 size={12} className="animate-spin" /> : <Trash2 size={12} />} Delete
          </button>
        </div>
      </div>
    </div>
  );
};

export default ConfirmDeleteDialog;

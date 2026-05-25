import React from "react";

export const ConfirmDialog = ({
  isOpen,
  onClose,
  onConfirm,
  onCancel,
  title,
  message,
  confirmLabel,
  cancelLabel,
}: {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  onCancel?: () => void;
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
}) => {
  if (!isOpen) return null;

  const cancelText = cancelLabel ?? (onCancel ? "Discard" : "Cancel");
  const confirmText = confirmLabel ?? (onCancel ? "Save" : "Confirm");

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={onClose}>
      <div
        className="bg-theme-surface rounded-lg shadow-xl p-6 max-w-md w-full mx-4"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-lg font-semibold mb-4" style={{ color: "var(--color-text)" }}>
          {title}
        </h3>
        <p className="text-sm mb-6" style={{ color: "var(--color-text-secondary)" }}>
          {message}
        </p>
        <div className="flex justify-end gap-3">
          <button
            onClick={() => {
              if (onCancel) onCancel();
              onClose();
            }}
            className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
          >
            {cancelText}
          </button>
          <button
            onClick={() => {
              onConfirm();
              onClose();
            }}
            className="px-4 py-2 text-sm bg-purple-600 text-white rounded-md hover:bg-purple-700"
          >
            {confirmText}
          </button>
        </div>
      </div>
    </div>
  );
};

export const DuplicateFileDialog = ({
  isOpen,
  fileName,
  detail,
  copyName,
  onCopyNameChange,
  onOpenExisting,
  onReplace,
  onCreateCopy,
  onCancel,
  allowOpenExisting,
  error,
  isSubmitting,
}: {
  isOpen: boolean;
  fileName: string;
  detail?: string;
  copyName: string;
  onCopyNameChange: (value: string) => void;
  onOpenExisting: () => void;
  onReplace: () => void;
  onCreateCopy: () => void;
  onCancel: () => void;
  allowOpenExisting?: boolean;
  error?: string | null;
  isSubmitting?: boolean;
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={onCancel}>
      <div
        className="bg-theme-surface rounded-lg shadow-xl p-6 max-w-lg w-full mx-4"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-lg font-semibold mb-2" style={{ color: "var(--color-text)" }}>
          Duplicate File
        </h3>
        <p className="text-sm mb-3" style={{ color: "var(--color-text-secondary)" }}>
          A file named "<span className="font-semibold">{fileName}</span>" already exists.
        </p>
        {detail && (
          <pre
            className="text-xs whitespace-pre-wrap rounded-md p-3 mb-3"
            style={{ backgroundColor: "var(--color-surface)", color: "var(--color-text-secondary)" }}
          >
            {detail}
          </pre>
        )}
        <div className="mb-3">
          <label className="block text-xs font-medium mb-1" style={{ color: "var(--color-text-secondary)" }}>
            Copy name
          </label>
          <input
            type="text"
            value={copyName}
            onChange={(e) => onCopyNameChange(e.target.value)}
            className="w-full px-3 py-2 text-sm border rounded-md focus:ring-2"
            style={
              {
                borderColor: "var(--color-border)",
                backgroundColor: "var(--color-surface)",
                color: "var(--color-text)",
                "--tw-ring-color": "var(--color-primary)",
              } as React.CSSProperties
            }
            placeholder="Enter copy name"
          />
        </div>
        {error && <div className="text-xs text-red-600 mb-3">{error}</div>}
        <div className="flex flex-wrap justify-end gap-2">
          {allowOpenExisting && (
            <button
              onClick={onOpenExisting}
              className="px-3 py-2 text-xs bg-gray-200 text-black rounded-md hover:bg-gray-300"
            >
              Open Existing
            </button>
          )}
          <button
            onClick={onReplace}
            className="px-3 py-2 text-xs bg-yellow-500 text-white rounded-md hover:bg-yellow-600"
          >
            Replace
          </button>
          <button
            onClick={onCreateCopy}
            disabled={isSubmitting}
            className="px-3 py-2 text-xs bg-purple-600 text-white rounded-md hover:bg-purple-700 disabled:opacity-50"
          >
            {isSubmitting ? "Checking..." : "Create Copy"}
          </button>
          <button
            onClick={onCancel}
            className="px-3 py-2 text-xs bg-gray-100 text-gray-700 rounded-md hover:bg-gray-200"
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
};

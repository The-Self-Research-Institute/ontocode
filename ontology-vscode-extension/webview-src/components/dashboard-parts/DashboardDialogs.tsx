import React, { useState, useEffect } from "react";
import { AlertTriangle, X } from "lucide-react";
import type { LintIssue } from "../../utils/ontologyLinter";

export const SaveErrorDialog = ({
  isOpen,
  error,
  onRetry,
  onClose,
  isConflict = false,
  onReload,
}: {
  isOpen: boolean;
  error: string;
  onRetry: () => void;
  onClose: () => void;

  isConflict?: boolean;
  onReload?: () => void;
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-theme-surface rounded-lg shadow-xl p-6 max-w-lg w-full mx-4">
        <h3 className="text-lg font-semibold mb-2 text-red-600">
          {isConflict ? "Ontology Changed — Save Not Applied" : "Save Failed — Not Applied"}
        </h3>
        <p className="text-sm mb-3" style={{ color: "var(--color-text-secondary)" }}>
          {isConflict ? (
            <>
              Your code view content was <span className="font-semibold">not</span> saved — this
              ontology was changed elsewhere (another tab, or an edit in Class Hierarchy/Individuals)
              since you opened Code View. Saving now would silently overwrite that change. Reload to
              see the latest content, then reapply your edit.
            </>
          ) : (
            <>
              Your code view content was <span className="font-semibold">not</span> saved to the
              ontology. Graph View, the Hierarchy Tree, and DL Query will not reflect this edit until
              the save succeeds. Nothing was written to a local cache either — fix the issue below and
              try again, or discard your changes.
            </>
          )}
        </p>
        {!isConflict && (
          <pre
            className="text-xs whitespace-pre-wrap rounded-md p-3 mb-4 max-h-48 overflow-auto"
            style={{ backgroundColor: "var(--color-surface)", color: "var(--color-text)" }}
          >
            {error}
          </pre>
        )}
        <div className="flex justify-end gap-3">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
          >
            Keep Editing
          </button>
          {isConflict ? (
            <button
              onClick={onReload}
              className="px-4 py-2 text-sm bg-purple-600 text-white rounded-md hover:bg-purple-700"
            >
              Reload Latest
            </button>
          ) : (
            <button
              onClick={onRetry}
              className="px-4 py-2 text-sm bg-purple-600 text-white rounded-md hover:bg-purple-700"
            >
              Retry Save
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

export const LintProblemsPanel = ({
  issues,
  onJumpToLine,
  onSaveAnyway,
  onDismiss,
}: {
  issues: LintIssue[];
  onJumpToLine: (line: number) => void;
  onSaveAnyway: () => void;
  onDismiss: () => void;
}) => {
  if (issues.length === 0) return null;

  return (
    <div
      className="border-t-2 border-amber-500 bg-[#1e1e1e] text-gray-200 flex flex-col"
      style={{ maxHeight: "40%", fontFamily: 'Consolas, "Courier New", monospace' }}
    >
      <div className="flex items-center justify-between px-3 py-1.5 bg-[#252526] border-b border-gray-700 flex-shrink-0">
        <div className="flex items-center gap-2 text-xs">
          <AlertTriangle size={13} className="text-amber-400" />
          <span className="font-semibold">
            {issues.length} issue{issues.length === 1 ? "" : "s"} found before saving
          </span>
        </div>
        <button
          onClick={onDismiss}
          className="text-gray-400 hover:text-white p-0.5 rounded"
          title="Close (does not save)"
        >
          <X size={14} />
        </button>
      </div>
      <div className="overflow-y-auto flex-1">
        {issues.map((issue, idx) => (
          <button
            key={idx}
            onClick={() => onJumpToLine(issue.line)}
            className="w-full text-left px-3 py-1.5 text-xs hover:bg-[#2a2d2e] border-b border-gray-800 flex items-start gap-2"
          >
            <span className="text-amber-400 flex-shrink-0">⚠</span>
            <span className="text-blue-400 flex-shrink-0">Line {issue.line}:</span>
            <span className="text-gray-300">{issue.message}</span>
          </button>
        ))}
      </div>
      <div className="flex items-center justify-end gap-2 px-3 py-2 bg-[#252526] border-t border-gray-700 flex-shrink-0">
        <span className="text-xs text-gray-500 mr-auto">
          Click an issue to jump to it. These are warnings — you can still save if intentional.
        </span>
        <button
          onClick={onDismiss}
          className="px-3 py-1 text-xs bg-gray-700 text-white rounded hover:bg-gray-600"
        >
          Keep Editing
        </button>
        <button
          onClick={onSaveAnyway}
          className="px-3 py-1 text-xs bg-amber-600 text-white rounded hover:bg-amber-700"
        >
          Save Anyway
        </button>
      </div>
    </div>
  );
};

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
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) onClose();
      }}
    >
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

export const DeleteClassDialog = ({
  isOpen,
  onClose,
  onConfirm,
  label,
  fetchDescendants,
}: {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (withDescendants: boolean, descendantIris: string[]) => void;
  label: string;
  fetchDescendants: () => Promise<{ iris: string[]; truncated: boolean }>;
}) => {
  const [withDescendants, setWithDescendants] = useState(false);
  const [descendants, setDescendants] = useState<{ iris: string[]; truncated: boolean } | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!isOpen) return;
    setWithDescendants(false);
    setDescendants(null);
    setLoading(true);
    fetchDescendants()
      .then((result) => setDescendants(result))
      .catch(() => setDescendants({ iris: [], truncated: false }))
      .finally(() => setLoading(false));
    // fetchDescendants is re-created per render in the caller; only re-fetch when the dialog opens.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  if (!isOpen) return null;

  const descendantCount = descendants?.iris.length ?? 0;
  const cascadeDisabled = loading || !!descendants?.truncated;

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) onClose();
      }}
    >
      <div
        className="bg-theme-surface rounded-lg shadow-xl p-6 max-w-md w-full mx-4"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-lg font-semibold mb-2" style={{ color: "var(--color-text)" }}>
          Delete {label}
        </h3>
        <p className="text-sm mb-4" style={{ color: "var(--color-text-secondary)" }}>
          Delete {label}? All references to {label} will be removed from the ontology.
        </p>
        <div className="flex flex-col gap-2 mb-6">
          <label className="flex items-center gap-2 text-sm cursor-pointer" style={{ color: "var(--color-text)" }}>
            <input type="radio" checked={!withDescendants} onChange={() => setWithDescendants(false)} />
            Delete {label} only
          </label>
          <label
            className="flex items-center gap-2 text-sm"
            style={{ color: cascadeDisabled ? "var(--color-text-secondary)" : "var(--color-text)", cursor: cascadeDisabled ? "not-allowed" : "pointer" }}
          >
            <input
              type="radio"
              checked={withDescendants}
              disabled={cascadeDisabled}
              onChange={() => setWithDescendants(true)}
            />
            Delete {label} and asserted descendant classes
            {loading && " (checking for descendants…)"}
            {!loading && !descendants?.truncated && descendantCount > 0 && ` (${descendantCount} found)`}
            {!loading && descendants?.truncated &&
              " — too many descendants to delete safely here; delete some children individually first"}
          </label>
        </div>
        <div className="flex justify-end gap-3">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
          >
            Cancel
          </button>
          <button
            onClick={() => {
              onConfirm(withDescendants, descendants?.iris ?? []);
              onClose();
            }}
            className="px-4 py-2 text-sm bg-red-600 text-white rounded-md hover:bg-red-700"
          >
            Delete
          </button>
        </div>
      </div>
    </div>
  );
};

export const PromptDialog = ({
  isOpen,
  title,
  message,
  defaultValue,
  placeholder,
  confirmLabel = "OK",
  cancelLabel = "Cancel",
  validate,
  onConfirm,
  onCancel,
}: {
  isOpen: boolean;
  title: string;
  message?: string;
  defaultValue?: string;
  placeholder?: string;
  confirmLabel?: string;
  cancelLabel?: string;

  validate?: (value: string) => string | null | undefined;
  onConfirm: (value: string) => void;
  onCancel: () => void;
}) => {
  const [value, setValue] = useState(defaultValue ?? "");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen) {
      setValue(defaultValue ?? "");
      setError(null);
    }
  }, [isOpen, defaultValue]);

  if (!isOpen) return null;

  const submit = () => {
    const trimmed = value.trim();
    if (!trimmed) {
      setError("This field is required.");
      return;
    }
    const validationError = validate?.(trimmed);
    if (validationError) {
      setError(validationError);
      return;
    }
    onConfirm(trimmed);
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) onCancel();
      }}
    >
      <div
        className="bg-theme-surface rounded-lg shadow-xl p-6 max-w-md w-full mx-4"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-lg font-semibold mb-2" style={{ color: "var(--color-text)" }}>
          {title}
        </h3>
        {message && (
          <p className="text-sm mb-3" style={{ color: "var(--color-text-secondary)" }}>
            {message}
          </p>
        )}
        <input
          type="text"
          value={value}
          autoFocus
          onChange={(e) => {
            setValue(e.target.value);
            setError(null);
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter") submit();
            if (e.key === "Escape") onCancel();
          }}
          placeholder={placeholder}
          className="w-full px-3 py-2 text-sm border rounded-md focus:ring-2"
          style={
            {
              borderColor: "var(--color-border)",
              backgroundColor: "var(--color-surface)",
              color: "var(--color-text)",
              "--tw-ring-color": "var(--color-primary)",
            } as React.CSSProperties
          }
        />
        {error && <div className="text-xs text-red-600 mt-2">{error}</div>}
        <div className="flex justify-end gap-3 mt-4">
          <button
            onClick={onCancel}
            className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
          >
            {cancelLabel}
          </button>
          <button
            onClick={submit}
            className="px-4 py-2 text-sm bg-purple-600 text-white rounded-md hover:bg-purple-700"
          >
            {confirmLabel}
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
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) onCancel();
      }}
    >
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

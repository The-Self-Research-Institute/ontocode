import React from "react";
import { AlertCircle, Loader2, RotateCcw, RotateCw } from "lucide-react";
import { changeCountLabel, type GroupUndoState, type UndoPreview } from "../services/codeAssistantUndo";

export interface UndoControlsProps {
  state?: GroupUndoState;
  disabledReason?: string | null;
  onRequest: () => void;
  onConfirm: () => void;
  onCancel: () => void;
}

const SECONDARY =
  "flex items-center gap-1 px-3 py-1.5 text-xs font-semibold text-gray-700 bg-gray-100 rounded-md hover:bg-gray-200 disabled:opacity-50";

const UndoConfirm: React.FC<{ preview: UndoPreview; busy: boolean; onConfirm: () => void; onCancel: () => void }> = ({
  preview,
  busy,
  onConfirm,
  onCancel,
}) => {
  const isUndo = preview.direction === "UNDO";
  const count = changeCountLabel(preview.count);
  return (
    <div role="group" aria-label={isUndo ? "Confirm undo" : "Confirm redo"} className="w-full space-y-2 px-3 py-2 bg-gray-50 border border-gray-200 rounded-md">
      <p className="text-xs text-gray-800">
        {isUndo ? "This undoes" : "This redoes"} {count}.
      </p>
      {preview.skipped && <p className="text-xs text-amber-800">{preview.skipped}</p>}
      <div className="flex items-center gap-2">
        <button onClick={onCancel} disabled={busy} className={SECONDARY}>
          Cancel
        </button>
        <button
          onClick={onConfirm}
          disabled={busy}
          className="flex items-center gap-1 px-3 py-1.5 text-xs font-semibold text-white bg-gray-700 rounded-md hover:bg-gray-800 disabled:opacity-50"
        >
          {busy && <Loader2 size={12} className="animate-spin" aria-hidden="true" />}
          {isUndo ? "Undo" : "Redo"} {count}
        </button>
      </div>
    </div>
  );
};

const UndoToggle: React.FC<{ undone: boolean; busy: boolean; disabledReason?: string | null; onRequest: () => void }> = ({
  undone,
  busy,
  disabledReason,
  onRequest,
}) => (
  <>
    {undone && <span className="text-xs text-gray-500 font-semibold">Undone</span>}
    <button onClick={onRequest} disabled={busy || Boolean(disabledReason)} title={disabledReason ?? undefined} className={SECONDARY}>
      {busy ? <Loader2 size={12} className="animate-spin" aria-hidden="true" /> : undone ? <RotateCw size={12} /> : <RotateCcw size={12} />}
      {undone ? "Redo" : "Undo"}
    </button>
  </>
);

export const CodeAssistantUndoControls: React.FC<UndoControlsProps> = ({ state, disabledReason, onRequest, onConfirm, onCancel }) => {
  const busy = state?.busy === true;
  return (
    <>
      {state?.preview ? (
        <UndoConfirm preview={state.preview} busy={busy} onConfirm={onConfirm} onCancel={onCancel} />
      ) : (
        <UndoToggle undone={state?.undone === true} busy={busy} disabledReason={disabledReason} onRequest={onRequest} />
      )}
      {state?.error && (
        <span role="alert" className="flex items-center gap-1 text-xs text-red-700">
          <AlertCircle size={14} /> {state.error}
        </span>
      )}
    </>
  );
};

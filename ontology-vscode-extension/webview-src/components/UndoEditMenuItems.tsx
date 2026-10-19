import React, { useEffect } from "react";
import type { UndoMenu } from "../hooks/useOntologyUndoRedo";
import { currentTextTarget, undoTextEdit } from "../utils/textEditTarget";

const ITEM = "ontocode-top-menu-item w-full text-left px-4 py-2 text-xs flex items-center justify-between disabled:opacity-50 disabled:cursor-not-allowed";

export default function UndoEditMenuItems({ menu, onClose }: { menu?: UndoMenu; onClose: () => void }) {
  useEffect(() => {
    menu?.refresh?.();
  }, []);
  const textReady = !!currentTextTarget();
  const run = (action?: () => void) => () => {
    onClose();
    action?.();
  };
  return (
    <div className="py-1">
      <button type="button" disabled={!textReady} onClick={run(() => undoTextEdit())} title="Only while a text field is focused" className={ITEM}>
        <span>Undo text edit</span>
        <span className="text-gray-400">Ctrl+Z</span>
      </button>
      <div className="border-t border-gray-100 my-1" />
      <button type="button" disabled={!menu?.canUndo} onClick={run(menu?.undo)} className={ITEM}>
        <span className="truncate">Undo ontology change: {menu?.undoLabel || "nothing to undo"}</span>
      </button>
      <button type="button" disabled={!menu?.canRedo} onClick={run(menu?.redo)} title={menu?.redoHint} className={ITEM}>
        <span className="truncate">Redo ontology change: {menu?.redoLabel || "nothing to redo"}</span>
        <span className="text-gray-400">Ctrl+Y</span>
      </button>
    </div>
  );
}

import React, { useEffect, useState } from "react";
import { useUndoRunStatus } from "../hooks/useUndoRunStatus";
import type { UndoMenu } from "../hooks/useOntologyUndoRedo";
import UndoRunChip from "./UndoRunChip";
import UndoRunPanel from "./UndoRunPanel";
import UndoConflictDialog from "./UndoConflictDialog";

export default function UndoRunControls({ menu }: { menu?: UndoMenu }) {
  const status = useUndoRunStatus();
  const [dialogOpen, setDialogOpen] = useState(false);
  const paused = status.phase === "paused";

  useEffect(() => {
    setDialogOpen(paused);
  }, [paused, status.runId, status.reason]);

  if (!menu || !status.chipVisible) return null;
  return (
    <div className="relative flex items-center" role="status" aria-live="polite">
      <UndoRunChip status={status} onClick={paused ? () => setDialogOpen(true) : status.togglePanel} />
      {status.panelOpen && <UndoRunPanel status={status} menu={menu} />}
      {paused && dialogOpen && <UndoConflictDialog status={status} menu={menu} onClose={() => setDialogOpen(false)} />}
    </div>
  );
}

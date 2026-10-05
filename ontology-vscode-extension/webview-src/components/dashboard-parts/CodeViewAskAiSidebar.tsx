import React from "react";
import { CodeAssistantPanel } from "../CodeAssistantPanel";
import { AskAiIcon } from "../AskAiIcon";
import { CodeAssistantRecoveryBanner } from "../CodeAssistantRecoveryBanner";
import type { useProjectRecovery } from "../../hooks/useCodeAssistantRecovery";
import type { useResizablePanelWidth } from "./hooks/useResizablePanelWidth";

type CodeViewAskAiSidebarProps = Omit<React.ComponentProps<typeof CodeAssistantPanel>, "recoveryShownByHost"> & {
  resize: ReturnType<typeof useResizablePanelWidth>;
};

export const CodeViewAskAiSidebar: React.FC<CodeViewAskAiSidebarProps> = ({ resize, ...panelProps }) => {
  return (
    <>
      <div
        onMouseDown={resize.onDragStart}
        className="w-1 flex-shrink-0 cursor-col-resize bg-gray-200 hover:bg-purple-400 active:bg-purple-500"
        title="Drag to resize"
      />
      <div ref={resize.panelRef} style={{ width: resize.width }} className="flex-shrink-0 border-l border-gray-200 overflow-hidden">
        <CodeAssistantPanel {...panelProps} recoveryShownByHost />
      </div>
    </>
  );
};

export const CodeViewAskAiToggle: React.FC<{ open: boolean; onToggle: () => void }> = ({ open, onToggle }) => (
  <button
    onClick={onToggle}
    className={`px-3 py-1 text-sm rounded-md flex items-center gap-1 ${
      open ? "bg-purple-600 text-white hover:bg-purple-700" : "bg-gray-200 text-gray-700 hover:bg-gray-300"
    }`}
    title="Ask the AI assistant about this document"
  >
    <AskAiIcon size={16} />
    Ask AI
  </button>
);

export const CodeViewAskAiStatus: React.FC<{ recovery: ReturnType<typeof useProjectRecovery>; refreshing: boolean }> = ({
  recovery,
  refreshing,
}) => (
  <>
    {recovery.recoveryLocked && (
      <CodeAssistantRecoveryBanner
        state={recovery.recoveryState}
        busy={recovery.recoveryBusy}
        error={recovery.recoveryError}
        onRestore={() => void recovery.restoreProject()}
        onClear={() => void recovery.unlockProject()}
      />
    )}
    {refreshing && (
      <div role="status" aria-live="polite" className="mb-2 px-2 py-1 text-xs text-gray-500">
        Updating after Ask AI change...
      </div>
    )}
  </>
);

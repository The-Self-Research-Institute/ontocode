import React from "react";
import { resolveSyncModeView } from "../../utils/syncModeControls";

interface SyncModeControlsProps {
  syncMode: "private" | "public";
  requireDraftForMembers?: boolean;
  isProjectOwner?: boolean;
  isProjectShared?: boolean;
  isDraftEditorRole?: boolean;
  isViewerRole?: boolean;
  autoDraftStatus?: "idle" | "copying" | "ready";
  onToggleSyncMode: () => void;
  onSwitchToDraftMode?: () => void;
  onToggleRequireDraftForMembers?: () => void;
}

const TONE_CLASS = { amber: "text-amber-600", green: "text-green-600", gray: "text-gray-500" } as const;
const TOGGLE_BASE =
  "relative inline-flex h-5 w-9 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-purple-500 focus:ring-offset-2";

const SyncModeControls: React.FC<SyncModeControlsProps> = ({
  syncMode,
  requireDraftForMembers,
  isProjectOwner,
  isProjectShared,
  isDraftEditorRole,
  isViewerRole,
  autoDraftStatus,
  onToggleSyncMode,
  onSwitchToDraftMode,
  onToggleRequireDraftForMembers,
}) => {
  const view = resolveSyncModeView({ syncMode, isProjectOwner, isViewerRole, isDraftEditorRole, requireDraftForMembers });
  const isPublic = syncMode === "public";
  return (
    <>
      {autoDraftStatus === "copying" && (
        <span className="hidden sm:inline text-xs text-blue-500 italic animate-pulse" title="Setting up your private draft workspace…">
          Setting up draft…
        </span>
      )}
      {autoDraftStatus === "ready" && (
        <span className="hidden sm:inline text-xs text-green-600 font-medium">Draft ready</span>
      )}
      <span className={`hidden sm:inline text-xs font-medium ${TONE_CLASS[view.tone]}`}>{view.label}</span>
      {view.control === "switchToDraft" && (
        <button
          onClick={onSwitchToDraftMode}
          className="ml-1 flex items-center gap-1 rounded px-2 py-0.5 text-[11px] font-medium border border-purple-300 bg-purple-50 text-purple-700 hover:bg-purple-100 transition-colors"
          title="Start your private draft copy to make edits"
        >
          Switch to Draft Mode
        </button>
      )}
      {view.control === "backToPublic" && (
        <button onClick={onToggleSyncMode} className={`${TOGGLE_BASE} bg-gray-300`} title="Switch back to Public view-only mode">
          <span className="inline-block h-3 w-3 transform rounded-full bg-white transition-transform translate-x-1" />
        </button>
      )}
      {view.control === "liveToggle" && (
        <button
          onClick={onToggleSyncMode}
          className={`${TOGGLE_BASE} ${isPublic ? "bg-green-500" : "bg-gray-300"}`}
          title={isPublic ? "Switch to Draft Mode" : "View Public (draft preserved)"}
        >
          <span
            className={`inline-block h-3 w-3 transform rounded-full bg-white transition-transform ${isPublic ? "translate-x-5" : "translate-x-1"}`}
          />
        </button>
      )}
      {isProjectOwner && (isProjectShared || requireDraftForMembers) && onToggleRequireDraftForMembers && (
        <button
          onClick={onToggleRequireDraftForMembers}
          className={`ml-1 flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium border transition-colors ${
            requireDraftForMembers
              ? "bg-amber-50 border-amber-300 text-amber-700 hover:bg-amber-100"
              : "bg-gray-50 border-gray-200 text-gray-500 hover:bg-gray-100"
          }`}
          title={
            requireDraftForMembers
              ? "Members are locked to Draft Mode — click to allow public editing"
              : "Allow members to edit publicly — click to require Draft Mode for members"
          }
        >
          {requireDraftForMembers ? "🔒 Draft required" : "Allow public"}
        </button>
      )}
    </>
  );
};

export default SyncModeControls;

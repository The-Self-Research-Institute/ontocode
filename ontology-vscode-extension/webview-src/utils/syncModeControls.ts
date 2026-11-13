export type SyncModeTone = "amber" | "green" | "gray";
export type SyncModeControl = "none" | "switchToDraft" | "backToPublic" | "liveToggle";

export interface SyncModeInput {
  syncMode: "private" | "public";
  isProjectOwner?: boolean;
  isViewerRole?: boolean;
  isDraftEditorRole?: boolean;
  requireDraftForMembers?: boolean;
}

export interface SyncModeView {
  label: string;
  tone: SyncModeTone;
  control: SyncModeControl;
}

export function resolveSyncModeView(input: SyncModeInput): SyncModeView {
  const { syncMode, isProjectOwner, isViewerRole, isDraftEditorRole, requireDraftForMembers } = input;
  const isPublic = syncMode === "public";
  if (isViewerRole) {
    return { label: "View only", tone: "gray", control: "none" };
  }
  if ((requireDraftForMembers && !isProjectOwner) || isDraftEditorRole) {
    return {
      label: isPublic ? "Public (View Only)" : "Draft Mode",
      tone: isPublic ? "amber" : "gray",
      control: isPublic ? "switchToDraft" : "backToPublic",
    };
  }
  return {
    label: isPublic ? "Public (Live)" : "Private (Draft)",
    tone: isPublic ? "green" : "gray",
    control: "liveToggle",
  };
}

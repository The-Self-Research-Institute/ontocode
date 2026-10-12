export interface DiscardResult {
  success: boolean;
  discardedCount: number;
  message: string;
}

export function discardDraftMessage(unsavedCount: number): string {
  const what =
    unsavedCount > 0
      ? `all ${unsavedCount} unsaved draft change${unsavedCount === 1 ? "" : "s"}`
      : "all your draft changes";
  return `This will discard ${what} and return your draft to the current Public version. This can't be undone.`;
}

export async function runDraftDiscard(deps: {
  projectId: string;
  userId: string;
  discard: (projectId: string, userId: string) => Promise<DiscardResult>;
  onDiscarded: (result: DiscardResult) => void;
  onFailed: (message: string) => void;
}): Promise<boolean> {
  try {
    const result = await deps.discard(deps.projectId, deps.userId);
    if (!result?.success) {
      deps.onFailed(result?.message || "Could not discard your draft.");
      return false;
    }
    deps.onDiscarded(result);
    return true;
  } catch (error: any) {
    deps.onFailed(error?.message || "Could not discard your draft.");
    return false;
  }
}

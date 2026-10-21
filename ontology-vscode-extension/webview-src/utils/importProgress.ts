const POST_COMPLETION_STATUSES = new Set(["METADATA_READY", "METADATA_ERROR"]);
const POST_COMPLETION_STAGES = new Set(["background-indexing", "metadata-ready", "metadata-error"]);

export const IMPORT_SETTLE_POLLS = 2;

export function isProjectStatusCompleted(response: unknown): boolean {
  const body = response as { data?: { status?: unknown }; status?: unknown } | null | undefined;
  return (body?.data?.status ?? body?.status) === "COMPLETED";
}

export interface ImportProgressStatus {
  status?: string;
  metadata?: { stage?: unknown } | null;
}

export function isPostCompletionProgress(status: ImportProgressStatus | null | undefined): boolean {
  if (!status) {
    return false;
  }
  if (status.status && POST_COMPLETION_STATUSES.has(status.status)) {
    return true;
  }
  const stage = status.metadata?.stage;
  return typeof stage === "string" && POST_COMPLETION_STAGES.has(stage);
}

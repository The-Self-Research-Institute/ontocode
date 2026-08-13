
export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function fileImportEtaHint(fileSizeBytes: number): string {
  const mb = fileSizeBytes / (1024 * 1024);
  if (mb < 5) return "Usually under 1 minute";
  if (mb < 50) return "Typically 1–5 minutes";
  if (mb < 150) return "Typically 5–15 minutes — you can browse as soon as import completes";
  return "Large file — 15–40 minutes. Class tree opens when done; use Load description for axioms";
}

export const FILE_UPLOAD_GUIDANCE =
  "Under 50 MB: fast import. 50–150 MB: a few minutes. Over 150 MB: allow time — browsing and annotations are instant; full class description loads on demand.";

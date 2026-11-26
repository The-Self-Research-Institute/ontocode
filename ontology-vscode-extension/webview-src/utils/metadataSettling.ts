export function metadataStillSettling(response: unknown): boolean {
  const wrapped = response as { data?: unknown } | null | undefined;
  const body = (wrapped?.data || wrapped) as Record<string, any> | null | undefined;
  if (!body || typeof body !== "object") {
    return true;
  }
  const classes = Number(body.classCount ?? body.counts?.classes ?? 0);
  if (classes > 0) {
    return false;
  }
  return body.countsPending === true || body.projectStatus === "PROCESSING";
}

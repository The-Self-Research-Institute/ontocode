const LOADING_STATES = new Set(["UPLOADED", "PROCESSING", "INDEXING"]);

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
  return body.countsPending === true || body.metricsFailed === true || LOADING_STATES.has(String(body.projectStatus));
}

export interface RetryOptions {
  attempts: number;
  waitMs: number;
  stopped: () => boolean;
  wait?: (ms: number) => Promise<void>;
}

export async function retryUntilSettled(
  fetchAgain: () => Promise<unknown>,
  apply: (response: unknown) => void,
  options: RetryOptions,
): Promise<boolean> {
  const wait = options.wait ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  for (let attempt = 0; attempt < options.attempts; attempt++) {
    await wait(options.waitMs);
    if (options.stopped()) {
      return false;
    }
    try {
      const again = await fetchAgain();
      if (!metadataStillSettling(again)) {
        apply(again);
        return true;
      }
    } catch {
      continue;
    }
  }
  return false;
}

import apiClient from "./apiClient";
import {
  ReasoningJobCancelledError,
  cancelReasoningJob,
  waitForReasoningJob,
} from "./reasoningJobWait";
import {
  ReasonerSnapshotUnsupportedError,
  failIfUnsuccessful,
  normalizeSnapshotScope,
  reasonerSnapshotKey,
  snapshotFromJob,
  snapshotResponseBody,
  snapshotScopeMatches,
  snapshotUrl,
  type NormalizedSnapshotScope,
  type ReasonerSnapshot,
  type ReasonerSnapshotScope,
} from "./reasonerSnapshotTypes";

export * from "./reasonerSnapshotTypes";

interface InflightEntry {
  key: string;
  scope: NormalizedSnapshotScope;
  controller: AbortController;
  promise: Promise<ReasonerSnapshot>;
  jobId: string | null;
  waiters: number;
  detached: boolean;
  settled: boolean;
}

const UNSUPPORTED_STATUSES = new Set([404, 405, 501]);

const cache = new Map<string, { scope: NormalizedSnapshotScope; snapshot: ReasonerSnapshot }>();
const inflight = new Map<string, InflightEntry>();
const running = new Set<InflightEntry>();
let unsupported = false;

const removeEntry = (entry: InflightEntry) => {
  running.delete(entry);
  if (inflight.get(entry.key) === entry) {
    inflight.delete(entry.key);
  }
};

const abortEntry = (entry: InflightEntry) => {
  if (entry.settled || entry.controller.signal.aborted) {
    return;
  }
  entry.detached = true;
  entry.controller.abort();
  removeEntry(entry);
  if (entry.jobId) {
    void cancelReasoningJob(entry.scope.projectId, entry.jobId);
  }
};

async function requestSnapshot(entry: InflightEntry, maxWaitMs?: number): Promise<ReasonerSnapshot> {
  const { scope, controller } = entry;
  let response: any;
  try {
    response = await apiClient.get<any>(snapshotUrl(scope.projectId), {
      reasonerType: scope.reasonerType,
      draft: scope.draft ? "true" : "false",
      ...(scope.userId ? { userId: scope.userId } : {}),
    });
  } catch (error) {
    if (UNSUPPORTED_STATUSES.has(Number((error as any)?.status ?? (error as any)?.response?.status))) {
      unsupported = true;
      throw new ReasonerSnapshotUnsupportedError();
    }
    throw error;
  }
  const body = failIfUnsuccessful(snapshotResponseBody(response));
  if (body?.async && body?.jobId) {
    entry.jobId = String(body.jobId);
    if (controller.signal.aborted) {
      void cancelReasoningJob(scope.projectId, entry.jobId);
      throw new ReasoningJobCancelledError();
    }
    const job = await waitForReasoningJob(scope.projectId, entry.jobId, {
      signal: controller.signal,
      maxWaitMs,
      acceptPushResult: true,
    });
    return failIfUnsuccessful(snapshotFromJob(job));
  }
  if (controller.signal.aborted) {
    throw new ReasoningJobCancelledError();
  }
  return body;
}

function startRequest(key: string, scope: NormalizedSnapshotScope, maxWaitMs?: number): InflightEntry {
  const entry = {
    key,
    scope,
    controller: new AbortController(),
    jobId: null,
    waiters: 0,
    detached: false,
    settled: false,
  } as unknown as InflightEntry;
  entry.promise = requestSnapshot(entry, maxWaitMs);
  inflight.set(key, entry);
  running.add(entry);
  entry.promise.then(
    (snapshot) => {
      entry.settled = true;
      removeEntry(entry);
      if (!entry.detached) {
        cache.set(key, { scope, snapshot });
      }
    },
    () => {
      entry.settled = true;
      removeEntry(entry);
    },
  );
  return entry;
}

function join(entry: InflightEntry, signal?: AbortSignal): Promise<ReasonerSnapshot> {
  entry.waiters += 1;
  return new Promise((resolve, reject) => {
    let done = false;
    const settle = (finish: () => void) => {
      if (done) {
        return;
      }
      done = true;
      signal?.removeEventListener("abort", onAbort);
      entry.controller.signal.removeEventListener("abort", onEntryAbort);
      finish();
    };
    const onAbort = () => settle(() => {
      entry.waiters -= 1;
      if (entry.waiters <= 0) {
        abortEntry(entry);
      }
      reject(new ReasoningJobCancelledError());
    });
    const onEntryAbort = () => settle(() => reject(new ReasoningJobCancelledError()));
    signal?.addEventListener("abort", onAbort);
    entry.controller.signal.addEventListener("abort", onEntryAbort);
    entry.promise.then((value) => settle(() => resolve(value)), (error) => settle(() => reject(error)));
  });
}

export interface GetReasonerSnapshotOptions {
  signal?: AbortSignal;
  maxWaitMs?: number;
}

export async function getReasonerSnapshot(
  scope: ReasonerSnapshotScope,
  options: GetReasonerSnapshotOptions = {},
): Promise<ReasonerSnapshot> {
  if (unsupported) {
    throw new ReasonerSnapshotUnsupportedError();
  }
  if (options.signal?.aborted) {
    throw new ReasoningJobCancelledError();
  }
  const key = reasonerSnapshotKey(scope);
  const cached = cache.get(key);
  if (cached) {
    return cached.snapshot;
  }
  const entry = inflight.get(key) ?? startRequest(key, normalizeSnapshotScope(scope), options.maxWaitMs);
  return join(entry, options.signal);
}

export function peekReasonerSnapshot(scope: ReasonerSnapshotScope): ReasonerSnapshot | undefined {
  return cache.get(reasonerSnapshotKey(scope))?.snapshot;
}

export function invalidateReasonerSnapshots(match?: Partial<ReasonerSnapshotScope>): void {
  Array.from(cache.entries()).forEach(([key, value]) => {
    if (snapshotScopeMatches(value.scope, match)) {
      cache.delete(key);
    }
  });
  Array.from(inflight.values()).forEach((entry) => {
    if (snapshotScopeMatches(entry.scope, match)) {
      entry.detached = true;
      inflight.delete(entry.key);
    }
  });
}

export function cancelReasonerSnapshots(match?: Partial<ReasonerSnapshotScope>): number {
  const targets = Array.from(running).filter((entry) => snapshotScopeMatches(entry.scope, match));
  targets.forEach(abortEntry);
  return targets.length;
}

export function resetReasonerSnapshotState(): void {
  Array.from(running).forEach(abortEntry);
  cache.clear();
  inflight.clear();
  running.clear();
  unsupported = false;
}

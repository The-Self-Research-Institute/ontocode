import apiClient from "./apiClient";
import { isTerminalPollError, pollErrorMessage } from "../components/dashboard-parts/dashboardUtils";
import {
  isReasoningPushAvailable,
  onReasoningSocketStateChange,
  subscribeReasoningJob,
} from "./reasoningJobTopic";

export const PUSH_POLL_INTERVAL_MS = 10_000;
export const FALLBACK_POLL_INTERVAL_MS = 2_500;
export const DEFAULT_JOB_MAX_WAIT_MS = 30 * 60 * 1000;
const STALE_CHECK_MS = 1_000;

export class ReasoningJobCancelledError extends Error {
  readonly cancelled = true;
  constructor(message = "The reasoning job was cancelled") {
    super(message);
    this.name = "ReasoningJobCancelledError";
  }
}

export class ReasoningJobTimeoutError extends Error {
  readonly timeout = true;
  constructor(message = "The reasoning job did not finish in time") {
    super(message);
    this.name = "ReasoningJobTimeoutError";
  }
}

export class ReasoningJobFailedError extends Error {
  constructor(message: string, public readonly job?: any, public readonly status?: number) {
    super(message);
    this.name = "ReasoningJobFailedError";
  }
}

export const isReasoningJobCancelled = (error: unknown): boolean =>
  error instanceof ReasoningJobCancelledError || (error as any)?.cancelled === true;

const jobErrorMessage = (job: any) => job?.error || job?.message || "Reasoning job failed";

export const reasonerJobUrl = (projectId: string, jobId: string) =>
  `/api/ontology/${encodeURIComponent(projectId)}/reasoner/jobs/${encodeURIComponent(jobId)}`;

export const dlQueryJobUrl = (jobId: string) => `/api/dl-query/jobs/${encodeURIComponent(jobId)}`;

export async function cancelReasoningJob(projectId: string, jobId: string, url?: string): Promise<boolean> {
  try {
    const response: any = await apiClient.delete(url ?? reasonerJobUrl(projectId, jobId));
    const body = response?.data && response?.cancelled === undefined ? response.data : response;
    return body?.cancelled === true;
  } catch (error) {
    console.warn("[reasoningJobWait] Cancel request failed:", error);
    return false;
  }
}

export function anySignal(...signals: Array<AbortSignal | undefined>): AbortSignal | undefined {
  const present = signals.filter((signal): signal is AbortSignal => !!signal);
  if (present.length <= 1) {
    return present[0];
  }
  const controller = new AbortController();
  const abort = () => controller.abort();
  present.forEach((signal) => {
    if (signal.aborted) {
      controller.abort();
    } else {
      signal.addEventListener("abort", abort, { once: true });
    }
  });
  return controller.signal;
}

export interface WaitForReasoningJobOptions {
  signal?: AbortSignal;
  isStale?: () => boolean;
  maxWaitMs?: number;
  pushIntervalMs?: number;
  fallbackIntervalMs?: number;
  jobUrl?: string;
  fetchJob?: () => Promise<any>;
  acceptPushResult?: boolean;
  push?: boolean;
  cancelUrl?: string;
}

export async function waitForReasoningJob(
  projectId: string,
  jobId: string,
  options: WaitForReasoningJobOptions = {},
): Promise<any> {
  const {
    signal,
    isStale,
    maxWaitMs = DEFAULT_JOB_MAX_WAIT_MS,
    pushIntervalMs = PUSH_POLL_INTERVAL_MS,
    fallbackIntervalMs = FALLBACK_POLL_INTERVAL_MS,
    acceptPushResult = false,
    push = true,
  } = options;
  const fetchJob = options.fetchJob ?? (() => apiClient.get<any>(options.jobUrl ?? reasonerJobUrl(projectId, jobId)));
  const cancelled = () => signal?.aborted === true || isStale?.() === true;
  if (cancelled()) {
    throw new ReasoningJobCancelledError();
  }
  const cancelOnAbort = () => {
    if (options.cancelUrl) {
      void cancelReasoningJob(projectId, jobId, options.cancelUrl);
    }
  };
  signal?.addEventListener("abort", cancelOnAbort);

  let pushed: any = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let wake: (() => void) | null = null;
  const wakeUp = () => {
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
    const resume = wake;
    wake = null;
    resume?.();
  };
  const sleep = (ms: number) =>
    new Promise<void>((resolve) => {
      wake = resolve;
      timer = setTimeout(wakeUp, Math.max(0, ms));
    });

  const unsubscribe = push
    ? subscribeReasoningJob(jobId, (payload) => {
      if (/^(COMPLETED|FAILED)$/i.test(String(payload?.status ?? ""))) {
        pushed = payload;
        wakeUp();
      }
    })
    : () => undefined;
  const offState = onReasoningSocketStateChange(wakeUp);
  signal?.addEventListener("abort", wakeUp);

  const deadline = Date.now() + maxWaitMs;
  let lastCheckAt = Date.now();
  let checkNow = false;
  try {
    for (;;) {
      if (cancelled()) {
        throw new ReasoningJobCancelledError();
      }
      if (pushed) {
        const payload = pushed;
        pushed = null;
        if (String(payload.status).toUpperCase() === "FAILED") {
          throw new ReasoningJobFailedError(jobErrorMessage(payload), payload);
        }
        if (acceptPushResult && payload.result && typeof payload.result === "object") {
          return payload;
        }
        checkNow = true;
      }
      const now = Date.now();
      if (now >= deadline) {
        throw new ReasoningJobTimeoutError();
      }
      const interval = push && isReasoningPushAvailable(jobId) ? pushIntervalMs : fallbackIntervalMs;
      const dueAt = checkNow ? now : lastCheckAt + interval;
      if (now < dueAt) {
        const until = Math.min(dueAt, deadline) - now;
        await sleep(isStale ? Math.min(until, STALE_CHECK_MS) : until);
        continue;
      }
      checkNow = false;
      lastCheckAt = now;
      let job: any;
      try {
        job = await fetchJob();
      } catch (error) {
        if (cancelled()) {
          throw new ReasoningJobCancelledError();
        }
        if (isTerminalPollError(error)) {
          throw new ReasoningJobFailedError(
            pollErrorMessage(error, "The reasoning job is no longer available."),
            undefined,
            Number((error as any)?.status ?? (error as any)?.response?.status),
          );
        }
        console.warn("[reasoningJobWait] Job status check failed, retrying:", error);
        continue;
      }
      if (cancelled()) {
        throw new ReasoningJobCancelledError();
      }
      const status = String(job?.status ?? "").toUpperCase();
      if (status === "COMPLETED") {
        return job;
      }
      if (status === "FAILED") {
        throw new ReasoningJobFailedError(jobErrorMessage(job), job);
      }
    }
  } finally {
    if (timer) {
      clearTimeout(timer);
    }
    wake = null;
    unsubscribe();
    offState();
    signal?.removeEventListener("abort", wakeUp);
    signal?.removeEventListener("abort", cancelOnAbort);
  }
}

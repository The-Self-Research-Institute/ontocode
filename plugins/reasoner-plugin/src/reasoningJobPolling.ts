export const PUSH_POLL_INTERVAL_MS = 10_000;
export const FALLBACK_POLL_INTERVAL_MS = 2_500;

export class JobCancelledError extends Error {
  readonly cancelled = true;
  constructor(message = 'Reasoning was stopped') {
    super(message);
    this.name = 'JobCancelledError';
  }
}

export const isJobCancelled = (error: unknown): boolean =>
  error instanceof JobCancelledError || (error as { cancelled?: boolean } | null)?.cancelled === true;

interface HostReasoningJobs {
  subscribe: (jobId: string, listener: (payload: any) => void) => () => void;
  isPushAvailable?: (jobId: string) => boolean;
}

const hostReasoningJobs = (): HostReasoningJobs | null => {
  const bridge = typeof window !== 'undefined' ? (window as any).ontocodeReasoningJobs : undefined;
  return bridge && typeof bridge.subscribe === 'function' ? bridge : null;
};

export interface PollJobOptions {
  timeoutMs: number;
  timeoutMessage: string;
  signal?: AbortSignal;
  push?: boolean;
}

export async function pollJobUntilDone<T>(
  jobId: string,
  check: () => Promise<T | undefined>,
  options: PollJobOptions,
): Promise<T> {
  const host = options.push === false ? null : hostReasoningJobs();
  const { signal } = options;
  const deadline = Date.now() + options.timeoutMs;
  let pushedTerminal = false;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let wake: (() => void) | null = null;
  const wakeUp = () => {
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
    const resume = wake;
    wake = null;
    if (resume) {
      resume();
    }
  };
  const unsubscribe = host
    ? host.subscribe(jobId, (payload) => {
      const status = String(payload?.status ?? '').toUpperCase();
      if (status === 'COMPLETED' || status === 'FAILED') {
        pushedTerminal = true;
        wakeUp();
      }
    })
    : () => undefined;
  signal?.addEventListener('abort', wakeUp);
  try {
    for (;;) {
      if (signal?.aborted) {
        throw new JobCancelledError();
      }
      if (!pushedTerminal) {
        const pushAvailable = host?.isPushAvailable ? host.isPushAvailable(jobId) : false;
        const interval = pushAvailable ? PUSH_POLL_INTERVAL_MS : FALLBACK_POLL_INTERVAL_MS;
        const wait = Math.max(0, Math.min(interval, deadline - Date.now()));
        await new Promise<void>((resolve) => {
          wake = resolve;
          timer = setTimeout(wakeUp, wait);
        });
      }
      pushedTerminal = false;
      if (signal?.aborted) {
        throw new JobCancelledError();
      }
      const result = await check();
      if (signal?.aborted) {
        throw new JobCancelledError();
      }
      if (result !== undefined) {
        return result;
      }
      if (Date.now() >= deadline) {
        throw new Error(options.timeoutMessage);
      }
    }
  } finally {
    if (timer) {
      clearTimeout(timer);
    }
    unsubscribe();
    signal?.removeEventListener('abort', wakeUp);
  }
}

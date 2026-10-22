export type ReasoningJobListener = (payload: any) => void;
export type ReasoningTopicSubscriber = (jobId: string, onMessage: ReasoningJobListener) => () => void;

export const REASONING_TOPIC_PREFIX = "/topic/reasoning/";

const listeners = new Map<string, Set<ReasoningJobListener>>();
const socketSubscriptions = new Map<string, () => void>();
const forbiddenJobs = new Set<string>();
const stateListeners = new Set<() => void>();
let subscriber: ReasoningTopicSubscriber | null = null;

const safely = (fn: () => void) => {
  try {
    fn();
  } catch {
    return;
  }
};

const notifyState = () => {
  Array.from(stateListeners).forEach((listener) => safely(listener));
};

const deliver = (jobId: string, payload: any) => {
  const current = listeners.get(jobId);
  if (!current) {
    return;
  }
  Array.from(current).forEach((listener) => safely(() => listener(payload)));
};

const openSocketSubscription = (jobId: string) => {
  if (!subscriber || socketSubscriptions.has(jobId) || forbiddenJobs.has(jobId)) {
    return;
  }
  try {
    socketSubscriptions.set(jobId, subscriber(jobId, (payload) => deliver(jobId, payload)));
  } catch (error) {
    console.warn("[reasoningJobTopic] Could not subscribe to reasoning job updates:", error);
  }
};

const closeSocketSubscription = (jobId: string) => {
  const close = socketSubscriptions.get(jobId);
  if (!close) {
    return;
  }
  socketSubscriptions.delete(jobId);
  safely(close);
};

export function attachReasoningSocket(next: ReasoningTopicSubscriber): void {
  subscriber = next;
  socketSubscriptions.clear();
  listeners.forEach((_, jobId) => openSocketSubscription(jobId));
  notifyState();
}

export function detachReasoningSocket(): void {
  if (!subscriber && socketSubscriptions.size === 0) {
    return;
  }
  subscriber = null;
  Array.from(socketSubscriptions.keys()).forEach(closeSocketSubscription);
  notifyState();
}

export function isReasoningSocketConnected(): boolean {
  return subscriber !== null;
}

export function isReasoningPushAvailable(jobId: string): boolean {
  return subscriber !== null && socketSubscriptions.has(jobId) && !forbiddenJobs.has(jobId);
}

export function subscribeReasoningJob(jobId: string, listener: ReasoningJobListener): () => void {
  let current = listeners.get(jobId);
  if (!current) {
    current = new Set();
    listeners.set(jobId, current);
  }
  current.add(listener);
  openSocketSubscription(jobId);
  let active = true;
  return () => {
    if (!active) {
      return;
    }
    active = false;
    const remaining = listeners.get(jobId);
    if (!remaining) {
      return;
    }
    remaining.delete(listener);
    if (remaining.size === 0) {
      listeners.delete(jobId);
      closeSocketSubscription(jobId);
      forbiddenJobs.delete(jobId);
    }
  };
}

export function onReasoningSocketStateChange(listener: () => void): () => void {
  stateListeners.add(listener);
  return () => {
    stateListeners.delete(listener);
  };
}

export function markReasoningDestinationForbidden(destination: string | null | undefined): boolean {
  const dest = String(destination ?? "");
  if (!dest.startsWith(REASONING_TOPIC_PREFIX)) {
    return false;
  }
  const jobId = dest.slice(REASONING_TOPIC_PREFIX.length);
  forbiddenJobs.add(jobId);
  closeSocketSubscription(jobId);
  notifyState();
  return true;
}

export interface ReasoningJobWindowBridge {
  subscribe: (jobId: string, listener: ReasoningJobListener) => () => void;
  isConnected: () => boolean;
  isPushAvailable: (jobId: string) => boolean;
  onStateChange: (listener: () => void) => () => void;
}

export function installReasoningJobWindowBridge(target: any = typeof window !== "undefined" ? window : undefined): () => void {
  if (!target) {
    return () => undefined;
  }
  const bridge: ReasoningJobWindowBridge = {
    subscribe: subscribeReasoningJob,
    isConnected: isReasoningSocketConnected,
    isPushAvailable: isReasoningPushAvailable,
    onStateChange: onReasoningSocketStateChange,
  };
  target.ontocodeReasoningJobs = bridge;
  return () => {
    if (target.ontocodeReasoningJobs === bridge) {
      delete target.ontocodeReasoningJobs;
    }
  };
}

export function resetReasoningTopicState(): void {
  subscriber = null;
  listeners.clear();
  socketSubscriptions.clear();
  forbiddenJobs.clear();
  stateListeners.clear();
}

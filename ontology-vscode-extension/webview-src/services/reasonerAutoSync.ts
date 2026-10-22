export const AUTO_SYNC_SETTLE_MS = 2_000;
export const AUTO_SYNC_BUSY_SETTLE_MS = 5_000;
export const AUTO_SYNC_BUSY_WINDOW_MS = 10_000;
export const AUTO_SYNC_MAX_DELAY_MS = 20_000;

export function autoSyncSettleDelay(now: number, previousEditAt: number | null, firstPendingEditAt: number | null): number {
  const busy = previousEditAt !== null && now - previousEditAt <= AUTO_SYNC_BUSY_WINDOW_MS;
  const delay = busy ? AUTO_SYNC_BUSY_SETTLE_MS : AUTO_SYNC_SETTLE_MS;
  if (firstPendingEditAt === null) {
    return delay;
  }
  return Math.max(0, Math.min(delay, firstPendingEditAt + AUTO_SYNC_MAX_DELAY_MS - now));
}

export interface AutoSyncSettleTimer {
  noteEdit: () => number;
  cancel: () => void;
  isPending: () => boolean;
}

export function createAutoSyncSettleTimer(onSettle: () => void, now: () => number = () => Date.now()): AutoSyncSettleTimer {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let firstPendingEditAt: number | null = null;
  let previousEditAt: number | null = null;
  const clear = () => {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  };
  return {
    noteEdit: () => {
      const at = now();
      const first = firstPendingEditAt ?? at;
      const delay = autoSyncSettleDelay(at, previousEditAt, first);
      previousEditAt = at;
      firstPendingEditAt = first;
      clear();
      timer = setTimeout(() => {
        timer = null;
        firstPendingEditAt = null;
        onSettle();
      }, delay);
      return delay;
    },
    cancel: () => {
      clear();
      firstPendingEditAt = null;
      previousEditAt = null;
    },
    isPending: () => timer !== null,
  };
}

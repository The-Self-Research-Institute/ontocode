import { getEditActionDescription } from "../contexts/collaborationEditDescription";

export const RETRY_BASE_MS = 1000;
export const RETRY_CAP_MS = 30_000;
export const OP_BATCH_MS = 300;
export const MAX_VISIBLE_NOTIFICATIONS = 5;

export function backoffDelay(attempt: number, random: () => number = Math.random): number {
  const ceiling = Math.min(RETRY_CAP_MS, RETRY_BASE_MS * 2 ** Math.max(0, attempt));
  return Math.round(ceiling / 2 + (random() * ceiling) / 2);
}

export type StompErrorAction = "refresh" | "stop" | "retry";

export function stompErrorAction(code: string | undefined, refreshTried: boolean): StompErrorAction {
  switch ((code || "").trim()) {
    case "AUTH_MISSING":
      return "stop";
    case "AUTH_EXPIRED":
    case "AUTH_INVALID":
      return refreshTried ? "stop" : "refresh";
    default:
      return "retry";
  }
}

export function shareKey(n: any): string {
  const id = n?.id ?? n?.shareId ?? n?.projectId ?? "";
  return `${id}|${n?.sharedWithEmail ?? ""}|${n?.permission ?? ""}|${n?.timestamp ?? ""}`;
}

export function createShareDeduper(limit = 100): (n: any) => boolean {
  const seen: string[] = [];
  return (n) => {
    const key = shareKey(n);
    if (seen.includes(key)) {
      return false;
    }
    seen.push(key);
    if (seen.length > limit) {
      seen.shift();
    }
    return true;
  };
}

export function createForbiddenFilter(): (payload: any) => string | null {
  const warned = new Set<string>();
  return (payload) => {
    const dest = String(payload?.destination ?? "");
    if (payload?.code !== "WS_FORBIDDEN" || warned.has(dest)) {
      return null;
    }
    warned.add(dest);
    return dest;
  };
}

export interface RemoteOpSummary {
  userId: string;
  username: string;
  message: string;
  timestamp: number;
}

export function summarizeRemoteOps(ops: any[]): RemoteOpSummary[] {
  const byUser = new Map<string, any[]>();
  for (const op of ops) {
    const key = String(op?.userId ?? "");
    const list = byUser.get(key);
    if (list) {
      list.push(op);
    } else {
      byUser.set(key, [op]);
    }
  }
  return [...byUser.entries()].map(([userId, list]) => {
    const last = list[list.length - 1];
    const who = last?.username || "Someone";
    const message = list.length === 1
      ? `${who} ${getEditActionDescription(last.type, last)}`
      : `${list.length} changes by ${who}`;
    return { userId, username: last?.username, message, timestamp: last?.timestamp || Date.now() };
  });
}

export function capNotifications<T>(list: T[], max = MAX_VISIBLE_NOTIFICATIONS): T[] {
  return list.length > max ? list.slice(list.length - max) : list;
}

export function collaborationAllowed(desktop: boolean, license: { features?: Record<string, unknown> } | null): boolean {
  return !desktop || license?.features?.collaboration === true;
}

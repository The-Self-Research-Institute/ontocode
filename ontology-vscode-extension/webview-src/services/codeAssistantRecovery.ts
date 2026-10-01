export interface RecoveryState {
  locked: boolean;
  reason?: string;
  lockedAt?: string;
  operationId?: string;
  canRestore: boolean;
}

export const UNLOCKED_RECOVERY_STATE: RecoveryState = { locked: false, canRestore: false };

export class RecoveryApiError extends Error {
  constructor(message: string, readonly status: number, readonly errorCode?: string) {
    super(message);
  }
}

function optionalText(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value : undefined;
}

export function normalizeRecoveryState(raw: unknown): RecoveryState {
  if (!raw || typeof raw !== "object") return UNLOCKED_RECOVERY_STATE;
  const rec = raw as Record<string, unknown>;
  const locked = rec.locked === true;
  return {
    locked,
    reason: optionalText(rec.reason),
    lockedAt: optionalText(rec.lockedAt),
    operationId: optionalText(rec.operationId),
    canRestore: locked && rec.canRestore === true,
  };
}

function recoveryPath(projectId: string, suffix = ""): string {
  return `/api/v1/code-assistant/projects/${encodeURIComponent(projectId)}/recovery${suffix}`;
}

async function send(
  apiBaseUrl: string,
  path: string,
  token: string | undefined,
  method: "GET" | "POST",
  signal?: AbortSignal,
): Promise<unknown> {
  const res = await fetch(`${apiBaseUrl}${path}`, {
    method,
    headers: {
      ...(method === "POST" ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: method === "POST" ? "{}" : undefined,
    signal,
  });
  const data: unknown = await res.json().catch(() => null);
  const body = data && typeof data === "object" ? (data as Record<string, unknown>) : null;
  if (!res.ok || body?.ok === false) {
    throw new RecoveryApiError(
      optionalText(body?.message) ?? optionalText(body?.error) ?? `Recovery request failed (HTTP ${res.status}).`,
      res.status,
      optionalText(body?.errorCode),
    );
  }
  return data;
}

export async function fetchRecoveryState(
  apiBaseUrl: string,
  token: string | undefined,
  projectId: string,
  signal?: AbortSignal,
): Promise<RecoveryState> {
  return normalizeRecoveryState(await send(apiBaseUrl, recoveryPath(projectId), token, "GET", signal));
}

export async function restorePreviousVersion(
  apiBaseUrl: string,
  token: string | undefined,
  projectId: string,
  signal?: AbortSignal,
): Promise<void> {
  await send(apiBaseUrl, recoveryPath(projectId, "/restore"), token, "POST", signal);
}

export async function clearRecoveryLock(
  apiBaseUrl: string,
  token: string | undefined,
  projectId: string,
  signal?: AbortSignal,
): Promise<void> {
  await send(apiBaseUrl, recoveryPath(projectId, "/clear"), token, "POST", signal);
}

export function describeLockedAt(lockedAt: string | undefined, locale?: string): string | null {
  if (!lockedAt) return null;
  const time = Date.parse(lockedAt);
  if (Number.isNaN(time)) return null;
  return new Date(time).toLocaleString(locale);
}

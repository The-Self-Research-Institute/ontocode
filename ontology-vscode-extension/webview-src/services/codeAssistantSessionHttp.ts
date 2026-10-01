import { assistantAuthHeaders } from "./codeAssistantAuthHeaders";
import { toAssistantApiError, type ErrorEnvelope } from "./codeAssistantSessionErrors";

export interface IdempotentCallOptions {
  idempotencyKey?: string;
}

export function newIdempotencyKey(): string {
  const cryptoApi = typeof globalThis !== "undefined" ? (globalThis.crypto as Crypto | undefined) : undefined;
  if (cryptoApi && typeof cryptoApi.randomUUID === "function") return cryptoApi.randomUUID();
  const random = () => Math.random().toString(16).slice(2, 10);
  return `${random()}-${random()}-${Date.now().toString(16)}`;
}

const IDEMPOTENT_RETRY_STATUSES = new Set([502, 503, 504]);
const MAX_IDEMPOTENT_RETRIES = 2;
const IDEMPOTENT_RETRY_BASE_DELAY_MS = 500;

function waitFor(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException("Aborted", "AbortError"));
      return;
    }
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener(
      "abort",
      () => {
        clearTimeout(timer);
        reject(new DOMException("Aborted", "AbortError"));
      },
      { once: true },
    );
  });
}

function isAbortError(e: unknown): boolean {
  return e instanceof DOMException && e.name === "AbortError";
}

function isRetryableResponse(res: Response, data: unknown): boolean {
  if (IDEMPOTENT_RETRY_STATUSES.has(res.status)) return true;
  const code = data && typeof data === "object" ? (data as ErrorEnvelope).errorCode : undefined;
  return res.status === 409 && code === "IDEMPOTENCY_KEY_REUSED";
}

export async function postJson<T>(
  apiBaseUrl: string,
  path: string,
  token: string | undefined,
  body: unknown,
  signal?: AbortSignal,
  idempotencyKey?: string,
): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...assistantAuthHeaders(token),
    ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}),
  };
  const serializedBody = JSON.stringify(body);
  const maxRetries = idempotencyKey ? MAX_IDEMPOTENT_RETRIES : 0;

  for (let attempt = 0; ; attempt++) {
    let res: Response;
    try {
      res = await fetch(`${apiBaseUrl}${path}`, { method: "POST", headers, body: serializedBody, signal });
    } catch (e) {
      if (isAbortError(e) || attempt >= maxRetries) throw e;
      await waitFor(IDEMPOTENT_RETRY_BASE_DELAY_MS * (attempt + 1), signal);
      continue;
    }

    const data = await res.json().catch(() => null);
    if (!res.ok || (data && (data as ErrorEnvelope).ok === false)) {
      if (!res.ok && attempt < maxRetries && isRetryableResponse(res, data)) {
        await waitFor(IDEMPOTENT_RETRY_BASE_DELAY_MS * (attempt + 1), signal);
        continue;
      }
      throw toAssistantApiError(res, data, path);
    }
    return data as T;
  }
}

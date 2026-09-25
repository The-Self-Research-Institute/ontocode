import { LlmProvider, LlmRequestError } from "./LlmInsightsService";
import { toAssistantApiError } from "./codeAssistantSessionErrors";

function providerErrorDetail(data: unknown): string | null {
  const error = data && typeof data === "object" ? (data as { error?: unknown }).error : undefined;
  const message = error && typeof error === "object" ? (error as { message?: unknown }).message : undefined;
  return typeof message === "string" && message.trim() ? message.trim() : null;
}

async function extractProviderErrorMessage(res: Response): Promise<string | null> {
  try {
    return providerErrorDetail(await res.json());
  } catch {
    return null;
  }
}

function summarizeQuotaMessage(detail: string): string {
  if (detail.length <= 160 && !detail.includes("\n") && !detail.includes(" * ")) return detail;
  const headline = (detail.match(/^[^.]*\./)?.[0] ?? detail.split(/\s\*\s|\n/)[0]).trim().replace(/\.$/, "");
  const modelMatch = detail.match(/model:\s*([\w.-]+)/i);
  const retryMatch = detail.match(/retry in\s+([\d.]+)\s*s/i);
  const parts = [headline || "Quota exceeded"];
  if (modelMatch) parts.push(`for ${modelMatch[1]}`);
  const sentence = parts.join(" ") + ".";
  return retryMatch ? `${sentence} Try again in about ${Math.ceil(Number(retryMatch[1]))}s.` : sentence;
}

function providerHttpError(provider: LlmProvider, status: number, detail: string | null): LlmRequestError {
  if (status === 401 || status === 403) return new LlmRequestError(`Invalid or unauthorized API key for ${provider}.`);
  if (status === 404) return new LlmRequestError(`Model not found or unavailable for ${provider}.`);
  if (status === 429) {
    return new LlmRequestError(detail ? `Rate limit reached: ${summarizeQuotaMessage(detail)}` : "Rate limit reached. Try again shortly.");
  }
  if (status === 503) return new LlmRequestError(`${provider} is temporarily overloaded. Try again shortly.`);
  return new LlmRequestError(`${provider} API error (HTTP ${status}).`);
}

export async function mapHttpError(provider: LlmProvider, res: Response): Promise<LlmRequestError> {
  const detail = res.status === 429 ? await extractProviderErrorMessage(res) : null;
  return providerHttpError(provider, res.status, detail);
}

const BACKEND_ONLY_STATUSES = new Set([401, 403, 423]);

export async function mapManagedHttpError(provider: LlmProvider, res: Response, path: string): Promise<Error> {
  const data: unknown = await res.json().catch(() => null);
  const errorCode = data && typeof data === "object" ? (data as { errorCode?: unknown }).errorCode : undefined;
  if (typeof errorCode === "string" || BACKEND_ONLY_STATUSES.has(res.status)) return toAssistantApiError(res, data, path);
  return providerHttpError(provider, res.status, providerErrorDetail(data));
}

export interface ManagedProviderCall {
  apiBaseUrl: string;
  token: string | undefined;
  sessionId: string;
  model: string;
}

export function managedProviderCallPath(sessionId: string): string {
  return `/api/v1/code-assistant/sessions/${encodeURIComponent(sessionId)}/provider-call`;
}

export interface ProviderTarget {
  url: string;
  headers: Record<string, string>;
  payload: string;
  path?: string;
}

export function managedTarget(managed: ManagedProviderCall, body: Record<string, unknown>): ProviderTarget {
  const path = managedProviderCallPath(managed.sessionId);
  return {
    url: `${managed.apiBaseUrl}${path}`,
    headers: { "Content-Type": "application/json", ...(managed.token ? { Authorization: `Bearer ${managed.token}` } : {}) },
    payload: JSON.stringify({ request: body }),
    path,
  };
}

export function delay(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
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

export function nowMs(): number {
  return typeof performance !== "undefined" && typeof performance.now === "function" ? performance.now() : Date.now();
}

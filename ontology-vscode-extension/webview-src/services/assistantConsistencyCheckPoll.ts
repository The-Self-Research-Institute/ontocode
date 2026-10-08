import { delay } from "./codeAssistantProviderHttp";
import type { ProposedCheckResult } from "./codeAssistantSessionTypes";

const POLL_INTERVAL_MS = 1500;

export async function pollConsistencyCheck(
  apiBaseUrl: string,
  token: string | undefined,
  sessionId: string,
  serverGroupId: string,
  onResolved: (check: ProposedCheckResult) => void,
  signal?: AbortSignal,
): Promise<void> {
  const url = `${apiBaseUrl}/api/v1/code-assistant/sessions/${encodeURIComponent(sessionId)}/groups/${encodeURIComponent(serverGroupId)}/consistency-check`;
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;

  while (!signal?.aborted) {
    try {
      const res = await fetch(url, { headers, signal });
      if (res.status === 404) return;
      if (res.ok) {
        const body = (await res.json()) as { ok?: boolean; name?: string; passed?: boolean; detail?: string; status?: string };
        if (body?.ok && body.name) {
          const check: ProposedCheckResult = {
            name: body.name,
            passed: body.passed ?? true,
            detail: body.detail,
            status: body.status === "pending" ? "pending" : undefined,
          };
          if (check.status !== "pending") {
            onResolved(check);
            return;
          }
        }
      }
    } catch {
    }
    try {
      await delay(POLL_INTERVAL_MS, signal);
    } catch {
      return;
    }
  }
}

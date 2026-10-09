import { delay } from "./codeAssistantProviderHttp";
import type { ProposedCheckResult } from "./codeAssistantSessionTypes";

const POLL_INTERVAL_MS = 1500;
const MAX_ATTEMPTS = 80;
const CONSISTENCY_PRESERVED_CHECK = "consistency_preserved";

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

  const giveUp = (detail: string): void => {
    onResolved({ name: CONSISTENCY_PRESERVED_CHECK, passed: true, detail });
  };

  let attempts = 0;
  while (!signal?.aborted) {
    attempts++;
    try {
      const res = await fetch(url, { headers, signal });
      if (res.status === 404) {
        giveUp("Couldn't find this check anymore — treating it as inconclusive.");
        return;
      }
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
    if (attempts >= MAX_ATTEMPTS) {
      giveUp("Stopped waiting for this check to finish — treating it as inconclusive.");
      return;
    }
    try {
      await delay(POLL_INTERVAL_MS, signal);
    } catch {
      return;
    }
  }
}

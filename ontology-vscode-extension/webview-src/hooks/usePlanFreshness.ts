import { useEffect, useRef } from "react";
import { isDesktop } from "../utils/desktop";

export const PLAN_REFRESH_INTERVAL_MS = 60_000;

export function tokenAgeMs(token: string, now: number): number | null {
  try {
    const payload = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
    return typeof payload.iat === "number" ? now - payload.iat * 1000 : null;
  } catch {
    return null;
  }
}

export function usePlanFreshness(
  token: string | undefined,
  workspaceId: string | undefined,
  refresh: () => Promise<void>,
  now: () => number = Date.now,
): void {
  const refreshRef = useRef(refresh);
  const tokenRef = useRef(token);
  const lastAttempt = useRef(0);
  refreshRef.current = refresh;
  tokenRef.current = token;

  useEffect(() => {
    if (isDesktop() || !workspaceId) {
      return undefined;
    }
    const maybeRefresh = () => {
      const current = tokenRef.current;
      if (!current || document.visibilityState === "hidden") {
        return;
      }
      const at = now();
      const age = tokenAgeMs(current, at);
      if (at - lastAttempt.current < PLAN_REFRESH_INTERVAL_MS || (age !== null && age < PLAN_REFRESH_INTERVAL_MS)) {
        return;
      }
      lastAttempt.current = at;
      void refreshRef.current().catch(() => undefined);
    };
    maybeRefresh();
    window.addEventListener("focus", maybeRefresh);
    document.addEventListener("visibilitychange", maybeRefresh);
    return () => {
      window.removeEventListener("focus", maybeRefresh);
      document.removeEventListener("visibilitychange", maybeRefresh);
    };
  }, [workspaceId, now]);
}

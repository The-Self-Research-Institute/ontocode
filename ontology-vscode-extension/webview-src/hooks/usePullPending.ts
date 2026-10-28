import { useCallback, useEffect, useState } from "react";
import apiClient from "../services/apiClient";

export const PULL_STATUS_POLL_MS = 60000;

export function usePullPending(projectId: string | undefined, userId: string | undefined, enabled: boolean) {
  const [pending, setPending] = useState(false);

  const refresh = useCallback(() => {
    if (!enabled || !projectId || !userId) {
      setPending(false);
      return;
    }
    apiClient
      .get<any>(`/api/ontology/${projectId}/draft/pull-status`, { userId })
      .then((res: any) => {
        const data = res?.data || res;
        setPending(data?.pending === true);
      })
      .catch(() => {});
  }, [enabled, projectId, userId]);

  useEffect(() => {
    if (!enabled || !projectId || !userId) {
      setPending(false);
      return undefined;
    }
    refresh();
    const timer = window.setInterval(refresh, PULL_STATUS_POLL_MS);
    const onChange = () => refresh();
    window.addEventListener("ontology:mutated", onChange);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("ontology:mutated", onChange);
    };
  }, [enabled, projectId, userId, refresh]);

  return { pending, refresh };
}

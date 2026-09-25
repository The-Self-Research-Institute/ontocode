import type { MutableRefObject } from "react";
import {
  fetchRecoveryState,
  RecoveryApiError,
  UNLOCKED_RECOVERY_STATE,
  type RecoveryState,
} from "../services/codeAssistantRecovery";
import { getApiBaseUrl, toFriendlyErrorMessage } from "../components/codeAssistantPanelHelpers";

export type RecoveryRequest = (apiBaseUrl: string, token: string | undefined, projectId: string) => Promise<void>;

export interface RecoveryRefreshContext {
  projectIdRef: MutableRefObject<string | undefined>;
  tokenRef: MutableRefObject<string | undefined>;
  mountedRef: MutableRefObject<boolean>;
  requestRef: MutableRefObject<number>;
  setRecoveryState: (state: RecoveryState) => void;
}

export interface RecoveryActionContext {
  projectIdRef: MutableRefObject<string | undefined>;
  tokenRef: MutableRefObject<string | undefined>;
  mountedRef: MutableRefObject<boolean>;
  setRecoveryBusy: (busy: boolean) => void;
  setRecoveryError: (error: string | null) => void;
  refreshRecovery: () => Promise<void>;
  onRecoveryChanged: () => void;
}

export function makeRefreshRecovery(ctx: RecoveryRefreshContext) {
  return async (): Promise<void> => {
    const pid = ctx.projectIdRef.current;
    const requestId = ++ctx.requestRef.current;
    if (!pid) {
      ctx.setRecoveryState(UNLOCKED_RECOVERY_STATE);
      return;
    }
    try {
      const next = await fetchRecoveryState(getApiBaseUrl(), ctx.tokenRef.current, pid);
      if (requestId === ctx.requestRef.current && ctx.mountedRef.current) ctx.setRecoveryState(next);
    } catch (e) {
      if (requestId !== ctx.requestRef.current || !ctx.mountedRef.current) return;
      if (e instanceof RecoveryApiError && e.status === 404) ctx.setRecoveryState(UNLOCKED_RECOVERY_STATE);
    }
  };
}

export function makeRunRecoveryAction(ctx: RecoveryActionContext) {
  return async (request: RecoveryRequest, failurePrefix: string, onSuccess?: () => void) => {
    const pid = ctx.projectIdRef.current;
    if (!pid) return;
    ctx.setRecoveryBusy(true);
    ctx.setRecoveryError(null);
    try {
      await request(getApiBaseUrl(), ctx.tokenRef.current, pid);
      onSuccess?.();
    } catch (e) {
      const message = e instanceof Error ? toFriendlyErrorMessage(e.message) : "unexpected error";
      if (ctx.mountedRef.current) ctx.setRecoveryError(`${failurePrefix}: ${message}`);
    } finally {
      await ctx.refreshRecovery();
      if (ctx.mountedRef.current) ctx.setRecoveryBusy(false);
      ctx.onRecoveryChanged();
    }
  };
}

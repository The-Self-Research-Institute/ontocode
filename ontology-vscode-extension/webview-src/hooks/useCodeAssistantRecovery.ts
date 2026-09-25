import { useEffect, useRef, useState, type MutableRefObject } from "react";
import {
  clearRecoveryLock,
  fetchRecoveryState,
  RecoveryApiError,
  restorePreviousVersion,
  UNLOCKED_RECOVERY_STATE,
  type RecoveryState,
} from "../services/codeAssistantRecovery";
import { getApiBaseUrl, toFriendlyErrorMessage } from "../components/codeAssistantPanelHelpers";

type RecoveryRequest = (apiBaseUrl: string, token: string | undefined, projectId: string) => Promise<void>;

interface RecoveryOptions {
  projectId?: string;
  projectIdRef: MutableRefObject<string | undefined>;
  tokenRef: MutableRefObject<string | undefined>;
  token?: string;
  mountedRef: MutableRefObject<boolean>;
  recoveryVersion: number;
  onRecoveryChanged?: () => void;
  onProjectRestored?: () => void;
}

export function useCodeAssistantRecovery(options: RecoveryOptions) {
  const { projectId, projectIdRef, tokenRef, token, mountedRef, recoveryVersion } = options;
  const [recoveryState, setRecoveryState] = useState<RecoveryState>(UNLOCKED_RECOVERY_STATE);
  const [recoveryBusy, setRecoveryBusy] = useState(false);
  const [recoveryError, setRecoveryError] = useState<string | null>(null);
  const requestRef = useRef(0);
  const projectRef = useRef<string | undefined>(undefined);
  const recoveryLocked = recoveryState.locked;
  const recoveryLockedRef = useRef(recoveryLocked);
  recoveryLockedRef.current = recoveryLocked;
  const optionsRef = useRef(options);
  optionsRef.current = options;

  const refreshRecovery = async (): Promise<void> => {
    const pid = projectIdRef.current;
    const requestId = ++requestRef.current;
    if (!pid) {
      setRecoveryState(UNLOCKED_RECOVERY_STATE);
      return;
    }
    try {
      const next = await fetchRecoveryState(getApiBaseUrl(), tokenRef.current, pid);
      if (requestId === requestRef.current && mountedRef.current) setRecoveryState(next);
    } catch (e) {
      if (requestId !== requestRef.current || !mountedRef.current) return;
      if (e instanceof RecoveryApiError && e.status === 404) setRecoveryState(UNLOCKED_RECOVERY_STATE);
    }
  };

  const noteRecoveryProblem = () => {
    recoveryLockedRef.current = true;
    setRecoveryState((prev) => (prev.locked ? prev : { ...prev, locked: true }));
    void refreshRecovery();
  };

  useEffect(() => {
    if (projectRef.current !== projectId) {
      projectRef.current = projectId;
      recoveryLockedRef.current = false;
      setRecoveryState(UNLOCKED_RECOVERY_STATE);
      setRecoveryError(null);
    }
    void refreshRecovery();
  }, [projectId, token]);

  useEffect(() => {
    if (recoveryVersion > 0) void refreshRecovery();
  }, [recoveryVersion]);

  useEffect(() => {
    if (!recoveryLocked) return;
    const recheck = () => void refreshRecovery();
    window.addEventListener("focus", recheck);
    return () => window.removeEventListener("focus", recheck);
  }, [recoveryLocked]);

  const runRecoveryAction = async (request: RecoveryRequest, failurePrefix: string, onSuccess?: () => void) => {
    const pid = projectIdRef.current;
    if (!pid) return;
    setRecoveryBusy(true);
    setRecoveryError(null);
    try {
      await request(getApiBaseUrl(), tokenRef.current, pid);
      onSuccess?.();
    } catch (e) {
      const message = e instanceof Error ? toFriendlyErrorMessage(e.message) : "unexpected error";
      if (mountedRef.current) setRecoveryError(`${failurePrefix}: ${message}`);
    } finally {
      await refreshRecovery();
      if (mountedRef.current) setRecoveryBusy(false);
      optionsRef.current.onRecoveryChanged?.();
    }
  };

  const restoreProject = () =>
    runRecoveryAction(restorePreviousVersion, "Couldn't restore the previous version", () =>
      optionsRef.current.onProjectRestored?.(),
    );

  const unlockProject = () => runRecoveryAction(clearRecoveryLock, "Couldn't unlock the project");

  return {
    recoveryState,
    recoveryLocked,
    recoveryLockedRef,
    recoveryBusy,
    recoveryError,
    refreshRecovery,
    noteRecoveryProblem,
    restoreProject,
    unlockProject,
  };
}

export function useProjectRecovery(
  projectId: string | undefined,
  token: string | undefined,
  callbacks: Pick<RecoveryOptions, "onRecoveryChanged" | "onProjectRestored">,
) {
  const projectIdRef = useRef(projectId);
  projectIdRef.current = projectId;
  const tokenRef = useRef(token);
  tokenRef.current = token;
  const mountedRef = useRef(true);
  return useCodeAssistantRecovery({ projectId, projectIdRef, tokenRef, token, mountedRef, recoveryVersion: 0, ...callbacks });
}

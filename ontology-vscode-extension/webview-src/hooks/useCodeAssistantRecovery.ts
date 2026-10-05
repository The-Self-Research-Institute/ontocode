import { useEffect, useRef, useState, type MutableRefObject } from "react";
import {
  clearRecoveryLock,
  restorePreviousVersion,
  UNLOCKED_RECOVERY_STATE,
  type RecoveryState,
} from "../services/codeAssistantRecovery";
import { makeRefreshRecovery, makeRunRecoveryAction } from "./codeAssistantRecoveryActions";

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

interface RecoveryTriggers {
  projectId?: string;
  token?: string;
  recoveryVersion: number;
  recoveryLocked: boolean;
  refreshRecovery: () => Promise<void>;
  onProjectChanged: () => void;
}

function useRecoveryTriggers({ projectId, token, recoveryVersion, recoveryLocked, refreshRecovery, onProjectChanged }: RecoveryTriggers) {
  const projectRef = useRef<string | undefined>(undefined);

  useEffect(() => {
    if (projectRef.current !== projectId) {
      projectRef.current = projectId;
      onProjectChanged();
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
}

export function useCodeAssistantRecovery(options: RecoveryOptions) {
  const { projectId, projectIdRef, tokenRef, token, mountedRef, recoveryVersion } = options;
  const [recoveryState, setRecoveryState] = useState<RecoveryState>(UNLOCKED_RECOVERY_STATE);
  const [recoveryBusy, setRecoveryBusy] = useState(false);
  const [recoveryError, setRecoveryError] = useState<string | null>(null);
  const requestRef = useRef(0);
  const recoveryLocked = recoveryState.locked;
  const recoveryLockedRef = useRef(recoveryLocked);
  recoveryLockedRef.current = recoveryLocked;
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const refreshRecovery = makeRefreshRecovery({ projectIdRef, tokenRef, mountedRef, requestRef, setRecoveryState });

  const noteRecoveryProblem = () => {
    recoveryLockedRef.current = true;
    setRecoveryState((prev) => (prev.locked ? prev : { ...prev, locked: true }));
    void refreshRecovery();
  };

  const onProjectChanged = () => {
    recoveryLockedRef.current = false;
    setRecoveryState(UNLOCKED_RECOVERY_STATE);
    setRecoveryError(null);
  };
  useRecoveryTriggers({ projectId, token, recoveryVersion, recoveryLocked, refreshRecovery, onProjectChanged });

  const runRecoveryAction = makeRunRecoveryAction({
    projectIdRef,
    tokenRef,
    mountedRef,
    setRecoveryBusy,
    setRecoveryError,
    refreshRecovery,
    onRecoveryChanged: () => optionsRef.current.onRecoveryChanged?.(),
  });

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

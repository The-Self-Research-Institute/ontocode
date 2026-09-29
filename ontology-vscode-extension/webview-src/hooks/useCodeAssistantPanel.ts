import { useEffect, useRef, useState, type MutableRefObject } from "react";
import { hasApiKey } from "../services/LlmInsightsService";
import { onApiKeyChange } from "../services/assistantKeyStore";
import { useAuth } from "../custom-hook/useAuth";
import { useSubscription } from "./useSubscription";
import { useCodeAssistantRecovery } from "./useCodeAssistantRecovery";
import { useCodeAssistantEntries } from "./useCodeAssistantEntries";
import { useCodeAssistantRun } from "./useCodeAssistantRun";
import { useCodeAssistantApply } from "./useCodeAssistantApply";
import { useCodeAssistantUndo } from "./useCodeAssistantUndo";
import { getApiBaseUrl, resolveApplyBlock, type CodeAssistantAction } from "../components/codeAssistantPanelHelpers";
import { getCachedProviderConfig, getProviderConfig, type ProviderConfig } from "../services/codeAssistantProviderConfig";
import type { CodeAssistantPanelProps } from "../components/CodeAssistantPanel";

function useLatestRef<T>(value: T): MutableRefObject<T> {
  const ref = useRef(value);
  ref.current = value;
  return ref;
}

function useProviderState() {
  const [configured, setConfigured] = useState(hasApiKey());
  const [providerConfig, setProviderConfig] = useState<ProviderConfig | null>(() => getCachedProviderConfig());
  const managedProvider = providerConfig?.managed ? providerConfig : null;
  const ready = managedProvider !== null || configured;
  return { configured, setConfigured, setProviderConfig, managedProvider, ready };
}

function usePanelLifecycle(
  mountedRef: MutableRefObject<boolean>,
  run: { abortOnUnmount: () => void },
  token: string | undefined,
  provider: ReturnType<typeof useProviderState>,
) {
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      run.abortOnUnmount();
    };
  }, []);

  useEffect(() => onApiKeyChange(() => provider.setConfigured(hasApiKey())), []);

  useEffect(() => {
    let cancelled = false;
    void getProviderConfig(getApiBaseUrl(), token).then((config) => {
      if (!cancelled) provider.setProviderConfig(config);
    });
    return () => {
      cancelled = true;
    };
  }, [token]);
}

export function useCodeAssistantPanel(props: CodeAssistantPanelProps) {
  const { projectId, hasUnsavedCodeViewChanges = false } = props;
  const { user, logout } = useAuth();
  const { isFree, getUpgradeMessage } = useSubscription();
  const provider = useProviderState();
  const [action, setAction] = useState<CodeAssistantAction>("ask");
  const [input, setInput] = useState("");
  const mountedRef = useRef(true);
  const projectIdRef = useLatestRef(projectId);
  const tokenRef = useLatestRef(user?.token);
  const editLockedMessage = getUpgradeMessage("AI-assisted editing");
  const recovery = useCodeAssistantRecovery({
    projectId,
    projectIdRef,
    tokenRef,
    token: user?.token,
    mountedRef,
    recoveryVersion: props.recoveryVersion ?? 0,
    onRecoveryChanged: props.onRecoveryChanged,
    onProjectRestored: props.onProjectRestored,
  });
  const { recoveryLocked, recoveryLockedRef } = recovery;
  const applyBlock = resolveApplyBlock({ hasUnsavedCodeViewChanges, recoveryLocked });
  const applyBlockRef = useLatestRef(applyBlock);
  const chat = useCodeAssistantEntries(projectId, projectIdRef, (previous) => run.abandonRunFor(previous));
  const run = useCodeAssistantRun({
    chat,
    projectIdRef,
    mountedRef,
    recoveryLockedRef,
    noteRecoveryProblem: recovery.noteRecoveryProblem,
    setInput,
    setAction,
    setProviderConfig: provider.setProviderConfig,
  });
  const apply = useCodeAssistantApply({
    chat,
    tokenRef,
    projectIdRef,
    mountedRef,
    blockedReason: () => (recoveryLockedRef.current ? "the project is locked for recovery" : applyBlockRef.current?.shortReason ?? null),
    noteRecoveryProblem: recovery.noteRecoveryProblem,
    onApplySuccess: props.onApplySuccess,
  });
  const undo = useCodeAssistantUndo({ chat, projectIdRef, onApplySuccess: props.onApplySuccess });
  usePanelLifecycle(mountedRef, run, user?.token, provider);
  return {
    user, logout, isFree, editLockedMessage, ...provider, action, setAction, input, setInput,
    recovery, recoveryLocked, applyBlock, chat, run, apply, undo,
  };
}

export type CodeAssistantPanelController = ReturnType<typeof useCodeAssistantPanel>;

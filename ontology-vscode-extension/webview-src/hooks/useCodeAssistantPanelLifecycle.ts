import { useEffect, useRef, useState, type MutableRefObject } from "react";
import { hasApiKey, getStoredModel } from "../services/LlmInsightsService";
import { onApiKeyChange } from "../services/assistantKeyStore";
import { getApiBaseUrl } from "../components/codeAssistantPanelHelpers";
import { getCachedProviderConfig, getProviderConfig, type ProviderConfig } from "../services/codeAssistantProviderConfig";

export function useLatestRef<T>(value: T): MutableRefObject<T> {
  const ref = useRef(value);
  ref.current = value;
  return ref;
}

export function useProviderState() {
  const [configured, setConfigured] = useState(hasApiKey());
  const [providerConfig, setProviderConfig] = useState<ProviderConfig | null>(() => getCachedProviderConfig());
  const managedProvider = providerConfig?.managed ? providerConfig : null;
  const ready = managedProvider !== null || configured;
  const modelUnavailable = ready && managedProvider === null && !getStoredModel();
  return { configured, setConfigured, setProviderConfig, managedProvider, ready, modelUnavailable };
}

export function usePanelLifecycle(
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

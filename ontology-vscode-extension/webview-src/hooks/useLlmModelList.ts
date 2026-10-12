import { useState, useEffect, useContext } from 'react';
import { AuthContext } from '../contexts/AuthContexts';
import { getCachedProviderConfig, getProviderConfig, type ProviderConfig } from '../services/codeAssistantProviderConfig';
import { getApiBaseUrl } from '../components/codeAssistantPanelHelpers';
import {
  getStoredApiKey,
  ensureApiKeyLoaded,
  getProviderModels,
  refreshAvailableModels,
  LlmProvider,
  KnownModel,
} from '../services/LlmInsightsService';

export type LlmSettingsMessage = { type: 'success' | 'error'; text: string } | null;

export function useStoredApiKeyState() {
  const [apiKey, setApiKey] = useState(getStoredApiKey());
  useEffect(() => {
    let cancelled = false;
    void ensureApiKeyLoaded().then(() => {
      if (!cancelled) setApiKey((current) => current || getStoredApiKey());
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return [apiKey, setApiKey] as const;
}

export function useProviderConfigState() {
  const token = useContext(AuthContext)?.user?.token;
  const [providerConfig, setProviderConfig] = useState<ProviderConfig | null>(() => getCachedProviderConfig());
  useEffect(() => {
    let cancelled = false;
    void getProviderConfig(getApiBaseUrl(), token).then((config) => {
      if (!cancelled) setProviderConfig(config);
    });
    return () => {
      cancelled = true;
    };
  }, [token]);
  return providerConfig;
}

export function useLlmModelList(
  provider: LlmProvider,
  apiKey: string,
  model: string,
  setModel: (model: string) => void,
  setMessage: (message: LlmSettingsMessage) => void,
) {
  const [models, setModels] = useState<KnownModel[]>(() => getProviderModels(provider));
  const [modelsSource, setModelsSource] = useState<'default' | 'live'>('default');
  const [modelsRefreshing, setModelsRefreshing] = useState(false);

  const handleRefreshModels = async () => {
    const activeKey = apiKey.trim();
    if (!activeKey) {
      setMessage({ type: 'error', text: 'Enter an API key first — the model list is fetched from your key.' });
      return;
    }
    setModelsRefreshing(true);
    try {
      const { models: live, live: isLive } = await refreshAvailableModels(provider, activeKey);
      setModels(live);
      setModelsSource(isLive ? 'live' : 'default');
      if (!isLive) {
        setMessage({ type: 'error', text: 'Could not reach the provider — showing default models instead.' });
      } else {
        if (live.length && !live.some(m => m.id === model)) {
          setModel(live[0].id);
        }
        setMessage({ type: 'success', text: `Found ${live.length} model${live.length === 1 ? '' : 's'} for this key.` });
      }
    } catch {
      setMessage({ type: 'error', text: 'Could not refresh the model list. Check your key and connection.' });
    } finally {
      setModelsRefreshing(false);
      setTimeout(() => setMessage(null), 3000);
    }
  };

  useEffect(() => {
    if (apiKey.trim() && !getCachedProviderConfig()?.managed) handleRefreshModels();
  }, [provider]);

  return { models, setModels, modelsSource, setModelsSource, modelsRefreshing, handleRefreshModels };
}

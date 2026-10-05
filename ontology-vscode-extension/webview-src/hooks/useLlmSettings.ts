import { useState } from 'react';
import {
  getStoredProvider,
  setStoredProvider,
  setStoredApiKey,
  getStoredModel,
  setStoredModel,
  getStoredMaxResponseTokens,
  setStoredMaxResponseTokens,
  getStoredSessionTokenBudget,
  setStoredSessionTokenBudget,
  getAvailableProviders,
  getProviderModels,
  refreshAvailableModels,
  LlmProvider,
} from '../services/LlmInsightsService';
import { useLlmModelList, useProviderConfigState, useStoredApiKeyState, type LlmSettingsMessage } from './useLlmModelList';

type ModelList = ReturnType<typeof useLlmModelList>;

interface SaveContext {
  provider: LlmProvider;
  apiKey: string;
  model: string;
  maxResponseTokens: number;
  sessionTokenBudget: number;
  list: ModelList;
  setModel: (model: string) => void;
  setSaving: (saving: boolean) => void;
  setMessage: (message: LlmSettingsMessage) => void;
  providerLabel: string | undefined;
  onSave?: () => void;
}

function makeSaveHandler(ctx: SaveContext) {
  return async () => {
    const activeKey = ctx.apiKey.trim();
    if (!activeKey) {
      ctx.setMessage({ type: 'error', text: 'Please enter an API key.' });
      return;
    }

    let modelToSave = ctx.model;
    if (ctx.list.modelsSource === 'default') {
      ctx.setSaving(true);
      const { models: live, live: isLive } = await refreshAvailableModels(ctx.provider, activeKey);
      if (isLive) {
        ctx.list.setModels(live);
        ctx.list.setModelsSource('live');
        if (live.length && !live.some((m) => m.id === modelToSave)) {
          modelToSave = live[0].id;
          ctx.setModel(modelToSave);
        }
      }
      ctx.setSaving(false);
    }

    setStoredProvider(ctx.provider);
    setStoredApiKey(ctx.apiKey);
    setStoredModel(modelToSave);
    setStoredMaxResponseTokens(ctx.maxResponseTokens);
    setStoredSessionTokenBudget(ctx.sessionTokenBudget);

    ctx.setMessage({ type: 'success', text: `Saved ${ctx.providerLabel} settings.` });
    setTimeout(() => ctx.setMessage(null), 3000);
    ctx.onSave?.();
  };
}

function makeTestHandler(
  apiKey: string,
  providerLabel: string | undefined,
  setTesting: (testing: boolean) => void,
  setMessage: (message: LlmSettingsMessage) => void,
) {
  return async () => {
    if (!apiKey.trim()) {
      setMessage({ type: 'error', text: 'Please save your API key first.' });
      return;
    }

    setTesting(true);
    try {
      const keyTrimmed = apiKey.trim();
      if (keyTrimmed.length < 10) {
        setMessage({ type: 'error', text: 'API key seems too short. Please check it.' });
        return;
      }

      setMessage({ type: 'success', text: `✓ ${providerLabel} key is valid.` });
    } catch (error) {
      setMessage({ type: 'error', text: 'Could not validate key. Please try again.' });
    } finally {
      setTesting(false);
      setTimeout(() => setMessage(null), 3000);
    }
  };
}

export function useLlmSettings(onSave?: () => void) {
  const [provider, setProvider] = useState<LlmProvider>(getStoredProvider());
  const [apiKey, setApiKey] = useStoredApiKeyState();
  const [model, setModel] = useState(getStoredModel());
  const [maxResponseTokens, setMaxResponseTokens] = useState(getStoredMaxResponseTokens());
  const [sessionTokenBudget, setSessionTokenBudget] = useState(getStoredSessionTokenBudget());
  const [showKey, setShowKey] = useState(false);
  const [message, setMessage] = useState<LlmSettingsMessage>(null);
  const [testing, setTesting] = useState(false);
  const [saving, setSaving] = useState(false);
  const providers = getAvailableProviders();
  const providerConfig = useProviderConfigState();
  const list = useLlmModelList(provider, apiKey, model, setModel, setMessage);
  const providerLabel = providers.find(p => p.id === provider)?.label;

  const selectProvider = (id: LlmProvider) => {
    const nextModels = getProviderModels(id);
    setProvider(id);
    list.setModels(nextModels);
    list.setModelsSource('default');
    setModel(nextModels[0].id);
  };

  const handleClear = () => {
    setApiKey('');
    setStoredApiKey('');
    setMessage({ type: 'success', text: 'API key cleared.' });
    setTimeout(() => setMessage(null), 3000);
  };

  const handleSave = makeSaveHandler({
    provider, apiKey, model, maxResponseTokens, sessionTokenBudget, list, setModel, setSaving, setMessage, providerLabel, onSave,
  });
  const handleTestConnection = makeTestHandler(apiKey, providerLabel, setTesting, setMessage);

  return {
    provider, apiKey, setApiKey, model, setModel, maxResponseTokens, setMaxResponseTokens,
    sessionTokenBudget, setSessionTokenBudget,
    showKey, setShowKey, message, testing, saving, providers, providerConfig, providerLabel,
    models: list.models, modelsSource: list.modelsSource, modelsRefreshing: list.modelsRefreshing,
    handleRefreshModels: list.handleRefreshModels, selectProvider, handleClear, handleSave, handleTestConnection,
  };
}

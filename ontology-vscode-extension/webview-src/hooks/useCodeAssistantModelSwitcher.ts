import React, { useEffect, useRef, useState } from "react";
import {
  getStoredProvider,
  setStoredProvider,
  getStoredApiKey,
  setStoredApiKey,
  getStoredModel,
  setStoredModel,
  getProviderModels,
  refreshAvailableModels,
  hasApiKey,
  isLikelyPaidOnlyModel,
  type LlmProvider,
  type KnownModel,
} from "../services/LlmInsightsService";

interface SwitcherSetters {
  provider: LlmProvider;
  onChange: () => void;
  setModels: (models: KnownModel[]) => void;
  setModel: (model: string) => void;
  setError: (error: string) => void;
  setLoadingModels: (loading: boolean) => void;
}

function useCloseOnOutsideClick(open: boolean, popoverRef: React.RefObject<HTMLDivElement | null>, close: () => void) {
  useEffect(() => {
    if (!open) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (popoverRef.current && !popoverRef.current.contains(e.target as Node)) close();
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [open]);
}

function makeRefreshModels(s: SwitcherSetters) {
  return async (key: string) => {
    if (!key.trim()) return;
    s.setLoadingModels(true);
    try {
      const { models: live, live: isLive } = await refreshAvailableModels(s.provider, key.trim());
      if (!isLive) {
        s.setError("Could not reach the provider — showing default models.");
        return;
      }
      s.setModels(live);
      const currentModel = getStoredModel();
      if (isLikelyPaidOnlyModel(s.provider, currentModel) && live.length && live[0].id !== currentModel) {
        s.setModel(live[0].id);
        setStoredModel(live[0].id);
        s.onChange();
      }
    } finally {
      s.setLoadingModels(false);
    }
  };
}

function makeSaveKey(s: SwitcherSetters, keyInput: string, setKeyInput: (v: string) => void, setForceKeyInput: (v: boolean) => void) {
  return async () => {
    const key = keyInput.trim();
    if (!key) return;
    s.setError("");
    s.setLoadingModels(true);
    try {
      const { models: live, live: isLive } = await refreshAvailableModels(s.provider, key);
      const list = isLive ? live : getProviderModels(s.provider);
      s.setModels(list);
      setStoredProvider(s.provider);
      setStoredApiKey(key);
      if (list.length) {
        s.setModel(list[0].id);
        setStoredModel(list[0].id);
      }
      if (!isLive) s.setError("Could not verify the key with the provider — saved anyway.");
      setKeyInput("");
      setForceKeyInput(false);
      s.onChange();
    } finally {
      s.setLoadingModels(false);
    }
  };
}

export function useCodeAssistantModelSwitcher(onChange: () => void) {
  const [open, setOpen] = useState(false);
  const [provider, setProvider] = useState<LlmProvider>(getStoredProvider());
  const [model, setModel] = useState(getStoredModel());
  const [models, setModels] = useState<KnownModel[]>(() => getProviderModels(getStoredProvider()));
  const [keyInput, setKeyInput] = useState("");
  const [loadingModels, setLoadingModels] = useState(false);
  const [error, setError] = useState("");
  const [forceKeyInput, setForceKeyInput] = useState(false);
  const popoverRef = useRef<HTMLDivElement | null>(null);
  const setters: SwitcherSetters = { provider, onChange, setModels, setModel, setError, setLoadingModels };
  const refreshModels = makeRefreshModels(setters);

  useCloseOnOutsideClick(open, popoverRef, () => setOpen(false));

  useEffect(() => {
    if (!open) return;
    setError("");
    setForceKeyInput(false);
    if (provider === getStoredProvider() && hasApiKey()) {
      setModels(getProviderModels(provider));
      refreshModels(getStoredApiKey());
    } else {
      setModels(getProviderModels(provider));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, provider]);

  const handleClearKey = () => {
    setStoredApiKey("");
    setError("");
    setForceKeyInput(true);
    onChange();
  };

  const selectModel = (id: string) => {
    setModel(id);
    setStoredProvider(provider);
    setStoredModel(id);
    setOpen(false);
    onChange();
  };

  return {
    open, setOpen, provider, setProvider, model, models, keyInput, setKeyInput, loadingModels, error,
    forceKeyInput, setForceKeyInput, popoverRef, handleSaveKey: makeSaveKey(setters, keyInput, setKeyInput, setForceKeyInput),
    handleClearKey, selectModel,
  };
}

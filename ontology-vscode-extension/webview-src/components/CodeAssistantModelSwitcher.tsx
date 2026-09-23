import React, { useEffect, useRef, useState } from "react";
import { Check, ChevronDown, Loader2, LogOut } from "lucide-react";
import {
  getAvailableProviders,
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

const KEY_LINKS: Record<LlmProvider, string> = {
  gemini: "https://ai.google.dev/pricing",
  claude: "https://console.anthropic.com/account/keys",
  openai: "https://platform.openai.com/account/api-keys",
};

interface CodeAssistantModelSwitcherProps {
  onChange: () => void;
}

export const CodeAssistantModelSwitcher: React.FC<CodeAssistantModelSwitcherProps> = ({ onChange }) => {
  const [open, setOpen] = useState(false);
  const [provider, setProvider] = useState<LlmProvider>(getStoredProvider());
  const [model, setModel] = useState(getStoredModel());
  const [models, setModels] = useState<KnownModel[]>(() => getProviderModels(getStoredProvider()));
  const [keyInput, setKeyInput] = useState("");
  const [loadingModels, setLoadingModels] = useState(false);
  const [error, setError] = useState("");
  const [forceKeyInput, setForceKeyInput] = useState(false);
  const popoverRef = useRef<HTMLDivElement | null>(null);

  const providers = getAvailableProviders();
  const keyReadyForProvider = hasApiKey() && getStoredProvider() === provider && !forceKeyInput;

  useEffect(() => {
    if (!open) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (popoverRef.current && !popoverRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [open]);

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

  const refreshModels = async (key: string) => {
    if (!key.trim()) return;
    setLoadingModels(true);
    try {
      const { models: live, live: isLive } = await refreshAvailableModels(provider, key.trim());
      if (!isLive) {
        setError("Could not reach the provider — showing default models.");
        return;
      }
      setModels(live);
      const currentModel = getStoredModel();
      if (isLikelyPaidOnlyModel(provider, currentModel) && live.length && live[0].id !== currentModel) {
        setModel(live[0].id);
        setStoredModel(live[0].id);
        onChange();
      }
    } finally {
      setLoadingModels(false);
    }
  };

  const handleSaveKey = async () => {
    const key = keyInput.trim();
    if (!key) return;
    setError("");
    setLoadingModels(true);
    try {
      const { models: live, live: isLive } = await refreshAvailableModels(provider, key);
      const list = isLive ? live : getProviderModels(provider);
      setModels(list);
      setStoredProvider(provider);
      setStoredApiKey(key);
      if (list.length) {
        setModel(list[0].id);
        setStoredModel(list[0].id);
      }
      if (!isLive) setError("Could not verify the key with the provider — saved anyway.");
      setKeyInput("");
      setForceKeyInput(false);
      onChange();
    } finally {
      setLoadingModels(false);
    }
  };

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

  const currentProviderLabel = providers.find((p) => p.id === getStoredProvider())?.label ?? "";
  const currentModelLabel = getProviderModels(getStoredProvider()).find((m) => m.id === getStoredModel())?.label ?? model;

  return (
    <div className="relative" ref={popoverRef}>
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-1.5 px-2 py-1 text-xs font-medium text-gray-600 bg-gray-100 hover:bg-gray-200 rounded-md max-w-[220px]"
        title="Switch provider or model"
      >
        <span className="truncate">
          {hasApiKey() ? `${currentProviderLabel} · ${currentModelLabel}` : "Add API key"}
        </span>
        <ChevronDown size={12} className="flex-shrink-0" />
      </button>

      {open && (
        <div className="absolute bottom-full left-0 mb-2 w-72 bg-white border border-gray-200 rounded-lg shadow-lg overflow-hidden z-10">
          <div className="px-3 py-2 border-b border-gray-100 text-xs font-semibold text-gray-500 uppercase tracking-wide">
            Select a model
          </div>
          <div className="flex gap-1 px-3 py-2 border-b border-gray-100">
            {providers.map((p) => (
              <button
                key={p.id}
                onClick={() => setProvider(p.id)}
                className={`px-2 py-1 text-xs font-semibold rounded-md ${
                  provider === p.id ? "bg-purple-600 text-white" : "bg-gray-100 text-gray-600 hover:bg-gray-200"
                }`}
              >
                {p.label}
              </button>
            ))}
          </div>

          {keyReadyForProvider ? (
            <div className="max-h-64 overflow-y-auto py-1">
              {loadingModels && (
                <div className="flex items-center gap-2 px-3 py-2 text-xs text-gray-500">
                  <Loader2 size={12} className="animate-spin" /> Refreshing models...
                </div>
              )}
              {models.map((m) => (
                <button
                  key={m.id}
                  onClick={() => selectModel(m.id)}
                  className="w-full text-left px-3 py-2 hover:bg-gray-50 flex items-center justify-between gap-2"
                >
                  <span className="text-sm text-gray-800 truncate">{m.label}</span>
                  {getStoredModel() === m.id && getStoredProvider() === provider && (
                    <Check size={14} className="text-purple-600 flex-shrink-0" />
                  )}
                </button>
              ))}
              {error && <div className="px-3 py-2 text-xs text-amber-700">{error}</div>}
              <div className="border-t border-gray-100 mt-1 px-3 py-2 flex items-center justify-between">
                <button onClick={() => setForceKeyInput(true)} className="text-xs text-gray-500 hover:text-gray-700 hover:underline">
                  Change key
                </button>
                <button onClick={handleClearKey} className="text-xs text-red-600 hover:underline flex items-center gap-1">
                  <LogOut size={11} /> Clear key
                </button>
              </div>
            </div>
          ) : (
            <div className="px-3 py-3 space-y-2">
              {forceKeyInput && hasApiKey() && getStoredProvider() === provider && (
                <button
                  onClick={() => setForceKeyInput(false)}
                  className="text-xs text-gray-500 hover:text-gray-700 hover:underline"
                >
                  ← Cancel
                </button>
              )}
              <input
                type="password"
                value={keyInput}
                onChange={(e) => setKeyInput(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleSaveKey()}
                placeholder={`Paste your ${providers.find((p) => p.id === provider)?.label} API key`}
                className="w-full px-2.5 py-1.5 border border-gray-300 rounded-md text-xs focus:ring-2 focus:ring-purple-500 focus:border-purple-500"
              />
              <div className="flex items-center justify-between gap-2">
                <a
                  href={KEY_LINKS[provider]}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-xs text-purple-700 hover:underline"
                >
                  Get a key →
                </a>
                <button
                  onClick={handleSaveKey}
                  disabled={!keyInput.trim() || loadingModels}
                  className="px-2.5 py-1 text-xs font-semibold text-white bg-purple-600 rounded-md hover:bg-purple-700 disabled:opacity-50"
                >
                  {loadingModels ? "Checking..." : "Save key"}
                </button>
              </div>
              {error && <div className="text-xs text-amber-700">{error}</div>}
              <p className="text-[10px] text-gray-400">Stored only in your browser, never sent to OntoCode servers.</p>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default CodeAssistantModelSwitcher;

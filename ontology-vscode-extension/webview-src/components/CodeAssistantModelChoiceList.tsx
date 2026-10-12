import React from "react";
import { Check, Loader2, LogOut, RefreshCw } from "lucide-react";
import { getStoredProvider, getStoredModel, type LlmProvider, type KnownModel } from "../services/LlmInsightsService";

export const KEY_LINKS: Record<LlmProvider, string> = {
  gemini: "https://ai.google.dev/pricing",
  claude: "https://console.anthropic.com/account/keys",
  openai: "https://platform.openai.com/account/api-keys",
};

export const ModelChoiceList: React.FC<{
  provider: LlmProvider;
  models: KnownModel[];
  loadingModels: boolean;
  error: string;
  selectModel: (id: string) => void;
  onChangeKey: () => void;
  onClearKey: () => void;
  onRetry: () => void;
}> = ({ provider, models, loadingModels, error, selectModel, onChangeKey, onClearKey, onRetry }) => (
  <div className="max-h-64 overflow-y-auto py-1">
    {loadingModels && (
      <div className="flex items-center gap-2 px-3 py-2 text-xs text-gray-500">
        <Loader2 size={12} className="animate-spin" /> Refreshing models...
      </div>
    )}
    {!loadingModels && models.length === 0 && (
      <div className="px-3 py-2 text-xs text-amber-700 flex items-center justify-between gap-2">
        <span>Couldn't load available models for this provider. Check your API key, or try again.</span>
        <button
          onClick={onRetry}
          title="Retry"
          className="text-amber-700 hover:text-amber-900 flex-shrink-0"
        >
          <RefreshCw size={12} />
        </button>
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
      <button onClick={onChangeKey} className="text-xs text-gray-500 hover:text-gray-700 hover:underline">
        Change key
      </button>
      <button onClick={onClearKey} className="text-xs text-red-600 hover:underline flex items-center gap-1">
        <LogOut size={11} /> Clear key
      </button>
    </div>
  </div>
);

export const KeyEntryForm: React.FC<{
  provider: LlmProvider;
  providerLabel: string | undefined;
  showCancel: boolean;
  onCancel: () => void;
  keyInput: string;
  setKeyInput: (value: string) => void;
  onSaveKey: () => void;
  loadingModels: boolean;
  error: string;
}> = ({ provider, providerLabel, showCancel, onCancel, keyInput, setKeyInput, onSaveKey, loadingModels, error }) => (
  <div className="px-3 py-3 space-y-2">
    {showCancel && (
      <button onClick={onCancel} className="text-xs text-gray-500 hover:text-gray-700 hover:underline">
        ← Cancel
      </button>
    )}
    <input
      type="password"
      value={keyInput}
      onChange={(e) => setKeyInput(e.target.value)}
      onKeyDown={(e) => e.key === "Enter" && onSaveKey()}
      placeholder={`Paste your ${providerLabel} API key`}
      className="w-full px-2.5 py-1.5 border border-gray-300 rounded-md text-xs focus:ring-2 focus:ring-purple-500 focus:border-purple-500"
    />
    <div className="flex items-center justify-between gap-2">
      <a href={KEY_LINKS[provider]} target="_blank" rel="noopener noreferrer" className="text-xs text-purple-700 hover:underline">
        Get a key →
      </a>
      <button
        onClick={onSaveKey}
        disabled={!keyInput.trim() || loadingModels}
        className="px-2.5 py-1 text-xs font-semibold text-white bg-purple-600 rounded-md hover:bg-purple-700 disabled:opacity-50"
      >
        {loadingModels ? "Checking..." : "Save key"}
      </button>
    </div>
    {error && <div className="text-xs text-amber-700">{error}</div>}
    <p className="text-[10px] text-gray-400">Stored only in your browser, never sent to OntoCode servers.</p>
  </div>
);

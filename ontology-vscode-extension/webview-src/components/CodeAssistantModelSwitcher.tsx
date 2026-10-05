import React, { useState } from "react";
import { Check, ChevronDown, Loader2, LogOut } from "lucide-react";
import {
  getAvailableProviders,
  getStoredProvider,
  getStoredModel,
  getProviderModels,
  getStoredSessionTokenBudget,
  setStoredSessionTokenBudget,
  hasApiKey,
  type LlmProvider,
  type KnownModel,
} from "../services/LlmInsightsService";
import { useCodeAssistantModelSwitcher } from "../hooks/useCodeAssistantModelSwitcher";
import { LabelOverlayToggleRow } from "./CodeAssistantLabelOverlayToggle";

const SessionBudgetRow: React.FC = () => {
  const [budget, setBudget] = useState(getStoredSessionTokenBudget());
  return (
    <div className="px-3 py-2 border-t border-gray-100">
      <div className="flex items-center justify-between gap-2">
        <label htmlFor="code-assistant-session-budget" className="text-xs font-medium text-gray-700">
          Session budget (tokens)
        </label>
        <input
          id="code-assistant-session-budget"
          type="number"
          min={2000}
          max={20000}
          step={1000}
          value={budget}
          onChange={(e) => {
            const next = Number(e.target.value) || budget;
            setBudget(next);
            setStoredSessionTokenBudget(next);
          }}
          className="w-20 px-2 py-1 text-xs border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent"
        />
      </div>
      <p className="text-[10px] text-gray-500 mt-1">Applies to your next message, not the current one.</p>
    </div>
  );
};

const KEY_LINKS: Record<LlmProvider, string> = {
  gemini: "https://ai.google.dev/pricing",
  claude: "https://console.anthropic.com/account/keys",
  openai: "https://platform.openai.com/account/api-keys",
};

interface CodeAssistantModelSwitcherProps {
  onChange: () => void;
}

type ProviderOption = { id: LlmProvider; label: string };

const ProviderTabs: React.FC<{
  providers: ProviderOption[];
  provider: LlmProvider;
  setProvider: (id: LlmProvider) => void;
}> = ({ providers, provider, setProvider }) => (
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
);

const ModelChoiceList: React.FC<{
  provider: LlmProvider;
  models: KnownModel[];
  loadingModels: boolean;
  error: string;
  selectModel: (id: string) => void;
  onChangeKey: () => void;
  onClearKey: () => void;
}> = ({ provider, models, loadingModels, error, selectModel, onChangeKey, onClearKey }) => (
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
      <button onClick={onChangeKey} className="text-xs text-gray-500 hover:text-gray-700 hover:underline">
        Change key
      </button>
      <button onClick={onClearKey} className="text-xs text-red-600 hover:underline flex items-center gap-1">
        <LogOut size={11} /> Clear key
      </button>
    </div>
  </div>
);

const KeyEntryForm: React.FC<{
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

type SwitcherState = ReturnType<typeof useCodeAssistantModelSwitcher>;

const SwitcherPopover: React.FC<{ s: SwitcherState; providers: ProviderOption[] }> = ({ s, providers }) => {
  const keyReadyForProvider = hasApiKey() && getStoredProvider() === s.provider && !s.forceKeyInput;
  return (
    <div className="absolute bottom-full left-0 mb-2 w-72 bg-white border border-gray-200 rounded-lg shadow-lg overflow-hidden z-10">
      <div className="px-3 py-2 border-b border-gray-100 text-xs font-semibold text-gray-500 uppercase tracking-wide">
        Select a model
      </div>
      <ProviderTabs providers={providers} provider={s.provider} setProvider={s.setProvider} />
      {keyReadyForProvider ? (
        <ModelChoiceList
          provider={s.provider}
          models={s.models}
          loadingModels={s.loadingModels}
          error={s.error}
          selectModel={s.selectModel}
          onChangeKey={() => s.setForceKeyInput(true)}
          onClearKey={s.handleClearKey}
        />
      ) : (
        <KeyEntryForm
          provider={s.provider}
          providerLabel={providers.find((p) => p.id === s.provider)?.label}
          showCancel={s.forceKeyInput && hasApiKey() && getStoredProvider() === s.provider}
          onCancel={() => s.setForceKeyInput(false)}
          keyInput={s.keyInput}
          setKeyInput={s.setKeyInput}
          onSaveKey={s.handleSaveKey}
          loadingModels={s.loadingModels}
          error={s.error}
        />
      )}
      <SessionBudgetRow />
      <LabelOverlayToggleRow />
    </div>
  );
};

export const CodeAssistantModelSwitcher: React.FC<CodeAssistantModelSwitcherProps> = ({ onChange }) => {
  const s = useCodeAssistantModelSwitcher(onChange);
  const providers = getAvailableProviders();
  const currentProviderLabel = providers.find((p) => p.id === getStoredProvider())?.label ?? "";
  const currentModelLabel = getProviderModels(getStoredProvider()).find((m) => m.id === getStoredModel())?.label ?? s.model;

  return (
    <div className="relative" ref={s.popoverRef}>
      <button
        onClick={() => s.setOpen((v) => !v)}
        className="flex items-center gap-1.5 px-2 py-1 text-xs font-medium text-gray-600 bg-gray-100 hover:bg-gray-200 rounded-md max-w-[220px]"
        title="Switch provider or model"
      >
        <span className="truncate">
          {hasApiKey() ? `${currentProviderLabel} · ${currentModelLabel}` : "Add API key"}
        </span>
        <ChevronDown size={12} className="flex-shrink-0" />
      </button>

      {s.open && <SwitcherPopover s={s} providers={providers} />}
    </div>
  );
};

export default CodeAssistantModelSwitcher;

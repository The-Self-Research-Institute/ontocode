import React, { useState } from "react";
import { ChevronDown } from "lucide-react";
import {
  getAvailableProviders,
  getStoredProvider,
  getStoredModel,
  getProviderModels,
  getStoredSessionTokenBudget,
  MAX_SESSION_TOKEN_BUDGET,
  MIN_SESSION_TOKEN_BUDGET,
  setStoredSessionTokenBudget,
  getStoredRetrievalAttempts,
  setStoredRetrievalAttempts,
  hasApiKey,
  type LlmProvider,
} from "../services/LlmInsightsService";
import { useCodeAssistantModelSwitcher } from "../hooks/useCodeAssistantModelSwitcher";
import { LabelOverlayToggleRow } from "./CodeAssistantLabelOverlayToggle";
import { ModelChoiceList, KeyEntryForm } from "./CodeAssistantModelChoiceList";

const ClampedNumberRow: React.FC<{
  id: string;
  label: string;
  helperText: string;
  min: number;
  max: number;
  step: number;
  getStored: () => number;
  setStored: (value: number) => number;
}> = ({ id, label, helperText, min, max, step, getStored, setStored }) => {
  const [value, setValue] = useState(getStored());
  const [text, setText] = useState(String(value));
  return (
    <div className="px-3 py-2 border-t border-gray-100">
      <div className="flex items-center justify-between gap-2">
        <label htmlFor={id} className="text-xs font-medium text-gray-700">
          {label}
        </label>
        <input
          id={id}
          type="number"
          min={min}
          max={max}
          step={step}
          value={text}
          onChange={(e) => {
            const raw = e.target.value;
            setText(raw);
            const parsed = Number(raw);
            if (raw.trim() !== "" && Number.isFinite(parsed)) {
              setValue(setStored(parsed));
            }
          }}
          onBlur={() => setText(String(value))}
          className="w-20 px-2 py-1 text-xs border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent"
        />
      </div>
      <p className="text-[10px] text-gray-500 mt-1">{helperText}</p>
    </div>
  );
};

const SessionBudgetRow: React.FC = () => (
  <ClampedNumberRow
    id="code-assistant-session-budget"
    label="Session budget (tokens)"
    helperText={`Between ${MIN_SESSION_TOKEN_BUDGET.toLocaleString("en-US")} and ${MAX_SESSION_TOKEN_BUDGET.toLocaleString("en-US")} tokens. Applies to your next message, not the current one.`}
    min={MIN_SESSION_TOKEN_BUDGET}
    max={MAX_SESSION_TOKEN_BUDGET}
    step={1000}
    getStored={getStoredSessionTokenBudget}
    setStored={setStoredSessionTokenBudget}
  />
);

const RetrievalAttemptsRow: React.FC = () => (
  <ClampedNumberRow
    id="code-assistant-retrieval-attempts"
    label="Retrieval attempts"
    helperText="Between 2 and 30 tool calls (read_context, run_sparql, etc.) per session. Applies to your next message."
    min={2}
    max={30}
    step={1}
    getStored={getStoredRetrievalAttempts}
    setStored={setStoredRetrievalAttempts}
  />
);

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
          onRetry={s.handleRetryModels}
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
      <RetrievalAttemptsRow />
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
          {!hasApiKey()
            ? "Add API key"
            : currentModelLabel
              ? `${currentProviderLabel} · ${currentModelLabel}`
              : `${currentProviderLabel} · no model available`}
        </span>
        <ChevronDown size={12} className="flex-shrink-0" />
      </button>

      {s.open && <SwitcherPopover s={s} providers={providers} />}
    </div>
  );
};

export default CodeAssistantModelSwitcher;

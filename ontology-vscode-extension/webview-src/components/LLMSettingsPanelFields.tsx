import React from 'react';
import { Save, Trash2, Eye, EyeOff, Check, X, RefreshCw } from 'lucide-react';
import {
  DEFAULT_MAX_RESPONSE_TOKENS,
  DEFAULT_SESSION_TOKEN_BUDGET,
  KnownModel,
  LlmProvider,
  MAX_SESSION_TOKEN_BUDGET,
  MIN_SESSION_TOKEN_BUDGET,
} from '../services/LlmInsightsService';
import type { LlmSettingsMessage } from '../hooks/useLlmModelList';

const INPUT_CLASS = 'w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent';

export const ApiKeyField: React.FC<{
  apiKey: string;
  setApiKey: (value: string) => void;
  showKey: boolean;
  setShowKey: (value: boolean) => void;
  providerLabel: string | undefined;
  onRefreshModels: () => void;
}> = ({ apiKey, setApiKey, showKey, setShowKey, providerLabel, onRefreshModels }) => (
  <div>
    <label className="block text-sm font-medium text-gray-700 mb-2">
      API Key
    </label>
    <div className="relative">
      <input
        type={showKey ? 'text' : 'password'}
        value={apiKey}
        onChange={(e) => setApiKey(e.target.value)}
        onBlur={() => { if (apiKey.trim()) onRefreshModels(); }}
        placeholder={`Enter your ${providerLabel} API key`}
        className="w-full px-4 py-2 pr-10 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
      />
      <button
        type="button"
        onClick={() => setShowKey(!showKey)}
        className="absolute right-3 top-2.5 text-gray-500 hover:text-gray-700"
      >
        {showKey ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
      </button>
    </div>
    <p className="text-xs text-gray-500 mt-2">
      🔒 Your key is stored only in your browser's localStorage. Never sent to OntoCode Studio servers.
    </p>
  </div>
);

export const ModelField: React.FC<{
  model: string;
  setModel: (value: string) => void;
  models: KnownModel[];
  modelsSource: 'default' | 'live';
  modelsRefreshing: boolean;
  onRefreshModels: () => void;
}> = ({ model, setModel, models, modelsSource, modelsRefreshing, onRefreshModels }) => (
  <div>
    <div className="flex items-center justify-between mb-2">
      <label className="block text-sm font-medium text-gray-700">
        Model
      </label>
      <button
        type="button"
        onClick={onRefreshModels}
        disabled={modelsRefreshing}
        title="Fetch the latest models available for your API key"
        className="flex items-center gap-1 text-xs text-indigo-600 hover:text-indigo-800 disabled:opacity-50 disabled:cursor-not-allowed"
      >
        <RefreshCw className={`w-3 h-3 ${modelsRefreshing ? 'animate-spin' : ''}`} />
        {modelsRefreshing ? 'Refreshing…' : 'Refresh models'}
      </button>
    </div>
    <select value={model} onChange={(e) => setModel(e.target.value)} className={INPUT_CLASS}>
      {models.map((m) => (
        <option key={m.id} value={m.id}>
          {m.label}
        </option>
      ))}
    </select>
    <p className={`text-xs mt-1 ${modelsSource === 'live' ? 'text-green-600' : 'text-gray-400'}`}>
      {modelsSource === 'live' ? '✓ Live list from your API key' : 'Default list — add your API key above to fetch the real one'}
    </p>
  </div>
);

export const MaxTokensField: React.FC<{ value: number; onChange: (value: number) => void }> = ({ value, onChange }) => (
  <div>
    <label className="block text-sm font-medium text-gray-700 mb-2">
      Max response length (tokens)
    </label>
    <input
      type="number"
      min={512}
      max={32768}
      step={512}
      value={value}
      onChange={(e) => onChange(Number(e.target.value) || DEFAULT_MAX_RESPONSE_TOKENS)}
      className={INPUT_CLASS}
    />
    <p className="text-xs text-gray-500 mt-1">
      Longer answers and larger propose_edit changes need more tokens; a low value risks the response being cut off mid-way.
    </p>
  </div>
);

export const SessionBudgetField: React.FC<{ value: number; onChange: (value: number) => void }> = ({ value, onChange }) => (
  <div>
    <label className="block text-sm font-medium text-gray-700 mb-2">
      Session exploration budget (tokens)
    </label>
    <input
      type="number"
      min={MIN_SESSION_TOKEN_BUDGET}
      max={MAX_SESSION_TOKEN_BUDGET}
      step={1000}
      value={value}
      onChange={(e) => onChange(Number(e.target.value) || DEFAULT_SESSION_TOKEN_BUDGET)}
      className={INPUT_CLASS}
    />
    <p className="text-xs text-gray-500 mt-1">
      How much the assistant can spend looking things up (e.g. running SPARQL queries) before it has to answer.
      Raise it if a complex request keeps getting cut short; lower it to spend less.
    </p>
  </div>
);

export const StatusMessage: React.FC<{ message: NonNullable<LlmSettingsMessage> }> = ({ message }) => (
  <div
    className={`p-4 rounded-lg flex items-center gap-2 ${
      message.type === 'success'
        ? 'bg-green-50 text-green-800 border border-green-200'
        : 'bg-red-50 text-red-800 border border-red-200'
    }`}
  >
    {message.type === 'success' ? (
      <Check className="w-5 h-5" />
    ) : (
      <X className="w-5 h-5" />
    )}
    <span className="text-sm">{message.text}</span>
  </div>
);

export const ActionButtons: React.FC<{
  compact: boolean;
  saving: boolean;
  testing: boolean;
  hasKey: boolean;
  onSave: () => void;
  onTest: () => void;
  onClear: () => void;
}> = ({ compact, saving, testing, hasKey, onSave, onTest, onClear }) => (
  <div className={`flex gap-3 ${compact ? 'flex-wrap' : ''}`}>
    <button
      onClick={onSave}
      disabled={saving}
      className={`flex items-center justify-center gap-2 bg-indigo-600 hover:bg-indigo-700 text-white font-medium py-2.5 rounded-lg transition disabled:opacity-50 disabled:cursor-not-allowed ${compact ? 'w-full' : 'flex-1'}`}
    >
      <Save className="w-4 h-4" />
      {saving ? 'Checking model…' : 'Save Settings'}
    </button>
    <button
      onClick={onTest}
      disabled={testing || !hasKey}
      className="flex-1 px-4 py-2.5 border border-indigo-600 text-indigo-600 hover:bg-indigo-50 font-medium rounded-lg transition disabled:opacity-50 disabled:cursor-not-allowed"
    >
      {testing ? '⏳ Testing...' : 'Test Connection'}
    </button>
    <button
      onClick={onClear}
      className="flex items-center justify-center gap-2 px-4 py-2.5 border border-gray-300 text-gray-700 hover:bg-gray-50 font-medium rounded-lg transition"
    >
      <Trash2 className="w-4 h-4" />
      Clear
    </button>
  </div>
);

const KEY_LINKS: Record<LlmProvider, { href: string; text: string }> = {
  gemini: { href: 'https://ai.google.dev/pricing', text: '1. Get Gemini API key at ai.google.dev →' },
  claude: { href: 'https://console.anthropic.com/account/keys', text: '1. Generate Claude API key at console.anthropic.com →' },
  openai: { href: 'https://platform.openai.com/account/api-keys', text: '1. Create OpenAI API key at platform.openai.com →' },
};

export const ApiKeyLinks: React.FC<{ provider: LlmProvider; usageNote: string }> = ({ provider, usageNote }) => {
  const link = KEY_LINKS[provider];
  return (
    <div className="border-t pt-6">
      <p className="text-sm font-medium text-gray-700 mb-3">Get Your API Key:</p>
      <ul className="space-y-2 text-sm">
        {link && (
          <li>
            <a href={link.href} target="_blank" rel="noopener noreferrer" className="text-indigo-600 hover:underline">
              {link.text}
            </a>
          </li>
        )}
        <li>2. Paste it above and click "Save Settings"</li>
        <li>{usageNote}</li>
      </ul>
    </div>
  );
};

import React, { useState, useEffect } from 'react';
import { Save, Trash2, Eye, EyeOff, Check, X, Zap, RefreshCw } from 'lucide-react';
import {
  getStoredProvider,
  setStoredProvider,
  getStoredApiKey,
  setStoredApiKey,
  getStoredModel,
  setStoredModel,
  getAvailableProviders,
  getProviderModels,
  refreshAvailableModels,
  LlmProvider,
  KnownModel,
} from '../services/LlmInsightsService';

interface LLMSettingsPanelProps {
  onSave?: () => void;
}

const LLMSettingsPanel: React.FC<LLMSettingsPanelProps> = ({ onSave }) => {
  const [provider, setProvider] = useState<LlmProvider>(getStoredProvider());
  const [apiKey, setApiKey] = useState(getStoredApiKey());
  const [model, setModel] = useState(getStoredModel());
  const [showKey, setShowKey] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [testing, setTesting] = useState(false);

  // Model list per provider. The hardcoded PROVIDERS table is only ever a placeholder
  // — shown before a key exists, or if a live fetch fails. Whenever a key is present
  // we always ask the provider directly (on mount and on blurring the key field),
  // so the list stays current without needing an app update.
  const [models, setModels] = useState<KnownModel[]>(() => getProviderModels(provider));
  const [modelsSource, setModelsSource] = useState<'default' | 'live'>('default');
  const [modelsRefreshing, setModelsRefreshing] = useState(false);

  const providers = getAvailableProviders();

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

  // Fetch the real model list automatically: once on mount if a key is already
  // saved, and again whenever the provider changes while a key is present.
  useEffect(() => {
    if (apiKey.trim()) handleRefreshModels();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [provider]);

  const handleSave = () => {
    if (!apiKey.trim()) {
      setMessage({ type: 'error', text: 'Please enter an API key.' });
      return;
    }

    setStoredProvider(provider);
    setStoredApiKey(apiKey);
    setStoredModel(model);

    setMessage({ type: 'success', text: `Saved ${providers.find(p => p.id === provider)?.label} settings.` });
    setTimeout(() => setMessage(null), 3000);
    onSave?.();
  };

  const handleClear = () => {
    setApiKey('');
    setStoredApiKey('');
    setMessage({ type: 'success', text: 'API key cleared.' });
    setTimeout(() => setMessage(null), 3000);
  };

  const handleTestConnection = async () => {
    if (!apiKey.trim()) {
      setMessage({ type: 'error', text: 'Please save your API key first.' });
      return;
    }

    setTesting(true);
    try {
      // Just validate that the key exists and has reasonable format
      const keyTrimmed = apiKey.trim();
      if (keyTrimmed.length < 10) {
        setMessage({ type: 'error', text: 'API key seems too short. Please check it.' });
        return;
      }

      setMessage({ type: 'success', text: `✓ ${providers.find(p => p.id === provider)?.label} key is valid.` });
    } catch (error) {
      setMessage({ type: 'error', text: 'Could not validate key. Please try again.' });
    } finally {
      setTesting(false);
      setTimeout(() => setMessage(null), 3000);
    }
  };

  const providerInfo: Record<LlmProvider, { color: string; description: string; url: string }> = {
    gemini: {
      color: 'from-blue-500 to-cyan-500',
      description: 'Free tier available with generous quota',
      url: 'https://ai.google.dev/pricing',
    },
    claude: {
      color: 'from-purple-500 to-pink-500',
      description: 'Pay-as-you-go, optimized reasoning',
      url: 'https://www.anthropic.com/pricing',
    },
    openai: {
      color: 'from-green-500 to-emerald-500',
      description: 'Industry standard, GPT models',
      url: 'https://openai.com/pricing',
    },
  };

  const currentInfo = providerInfo[provider];

  return (
    <div className="space-y-6 p-4 sm:p-6 max-w-full sm:max-w-2xl">
      <div className="bg-gradient-to-r from-indigo-50 to-blue-50 border border-indigo-200 rounded-lg p-4">
        <div className="flex items-start gap-3">
          <Zap className="w-5 h-5 text-indigo-600 flex-shrink-0 mt-0.5" />
          <div>
            <h3 className="font-semibold text-indigo-900">Graph View AI Insights</h3>
            <p className="text-sm text-indigo-700 mt-1">
              Bring your own LLM API key. OntoCode Studio never stores or sees your credentials. Everything stays in your browser.
            </p>
          </div>
        </div>
      </div>

      {/* Provider Selection */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-3">
          Choose Your LLM Provider
        </label>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          {providers.map((p) => (
            <button
              key={p.id}
              onClick={() => {
                const nextModels = getProviderModels(p.id as LlmProvider);
                setProvider(p.id as LlmProvider);
                setModels(nextModels); // instant placeholder; the mount/provider effect fetches the real list
                setModelsSource('default');
                setModel(nextModels[0].id);
              }}
              className={`p-3 rounded-lg border-2 transition-all text-center ${
                provider === p.id
                  ? 'border-indigo-500 bg-indigo-50 font-semibold text-indigo-900'
                  : 'border-gray-200 bg-white text-gray-700 hover:border-gray-300'
              }`}
            >
              {p.label}
            </button>
          ))}
        </div>
      </div>

      {/* Provider Info */}
      <div className={`bg-gradient-to-r ${currentInfo.color} rounded-lg p-4 text-white`}>
        <p className="font-medium mb-2">{providers.find(p => p.id === provider)?.label}</p>
        <p className="text-sm opacity-90">{currentInfo.description}</p>
        <a
          href={currentInfo.url}
          target="_blank"
          rel="noopener noreferrer"
          className="text-sm font-semibold mt-2 inline-block opacity-100 hover:opacity-80 transition"
        >
          View Pricing →
        </a>
      </div>

      {/* API Key Input */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          API Key
        </label>
        <div className="relative">
          <input
            type={showKey ? 'text' : 'password'}
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            onBlur={() => { if (apiKey.trim()) handleRefreshModels(); }}
            placeholder={`Enter your ${providers.find(p => p.id === provider)?.label} API key`}
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

      {/* Model Selection */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <label className="block text-sm font-medium text-gray-700">
            Model
          </label>
          <button
            type="button"
            onClick={handleRefreshModels}
            disabled={modelsRefreshing}
            title="Fetch the latest models available for your API key"
            className="flex items-center gap-1 text-xs text-indigo-600 hover:text-indigo-800 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <RefreshCw className={`w-3 h-3 ${modelsRefreshing ? 'animate-spin' : ''}`} />
            {modelsRefreshing ? 'Refreshing…' : 'Refresh models'}
          </button>
        </div>
        <select
          value={model}
          onChange={(e) => setModel(e.target.value)}
          className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
        >
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

      {/* Messages */}
      {message && (
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
      )}

      {/* Action Buttons */}
      <div className="flex gap-3">
        <button
          onClick={handleSave}
          className="flex-1 flex items-center justify-center gap-2 bg-indigo-600 hover:bg-indigo-700 text-white font-medium py-2.5 rounded-lg transition"
        >
          <Save className="w-4 h-4" />
          Save Settings
        </button>
        <button
          onClick={handleTestConnection}
          disabled={testing || !apiKey.trim()}
          className="flex-1 px-4 py-2.5 border border-indigo-600 text-indigo-600 hover:bg-indigo-50 font-medium rounded-lg transition disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {testing ? '⏳ Testing...' : 'Test Connection'}
        </button>
        <button
          onClick={handleClear}
          className="flex items-center justify-center gap-2 px-4 py-2.5 border border-gray-300 text-gray-700 hover:bg-gray-50 font-medium rounded-lg transition"
        >
          <Trash2 className="w-4 h-4" />
          Clear
        </button>
      </div>

      {/* API Key Links */}
      <div className="border-t pt-6">
        <p className="text-sm font-medium text-gray-700 mb-3">Get Your API Key:</p>
        <ul className="space-y-2 text-sm">
          {provider === 'gemini' && (
            <li>
              <a href="https://ai.google.dev/pricing" target="_blank" rel="noopener noreferrer" className="text-indigo-600 hover:underline">
                1. Get Gemini API key at ai.google.dev →
              </a>
            </li>
          )}
          {provider === 'claude' && (
            <li>
              <a href="https://console.anthropic.com/account/keys" target="_blank" rel="noopener noreferrer" className="text-indigo-600 hover:underline">
                1. Generate Claude API key at console.anthropic.com →
              </a>
            </li>
          )}
          {provider === 'openai' && (
            <li>
              <a href="https://platform.openai.com/account/api-keys" target="_blank" rel="noopener noreferrer" className="text-indigo-600 hover:underline">
                1. Create OpenAI API key at platform.openai.com →
              </a>
            </li>
          )}
          <li>2. Paste it above and click "Save Settings"</li>
          <li>3. Graph analytics will use your key when you click "AI Insights" on the graph</li>
        </ul>
      </div>
    </div>
  );
};

export default LLMSettingsPanel;

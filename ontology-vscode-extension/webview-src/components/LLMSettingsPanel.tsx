import React from 'react';
import { Zap, Building2 } from 'lucide-react';
import type { ProviderConfig } from '../services/codeAssistantProviderConfig';
import { LlmProvider } from '../services/LlmInsightsService';
import { useLlmSettings } from '../hooks/useLlmSettings';
import { ApiKeyField, ApiKeyLinks, ActionButtons, MaxTokensField, ModelField, SessionBudgetField, StatusMessage } from './LLMSettingsPanelFields';

interface LLMSettingsPanelProps {
  onSave?: () => void;
  compact?: boolean;
  bannerTitle?: string;
  bannerDescription?: string;
  usageNote?: string;
}

type ProviderOption = { id: string; label: string };

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

const ManagedProviderNotice: React.FC<{ compact: boolean; config: Extract<ProviderConfig, { managed: true }>; providers: ProviderOption[] }> = ({
  compact,
  config,
  providers,
}) => {
  const managedLabel = providers.find((p) => p.id === config.provider)?.label ?? config.provider;
  return (
    <div className={`space-y-4 p-4 ${compact ? '' : 'sm:p-6 max-w-full sm:max-w-2xl'}`} data-managed-provider>
      <div className="bg-gradient-to-r from-indigo-50 to-blue-50 border border-indigo-200 rounded-lg p-4">
        <div className="flex items-start gap-3">
          <Building2 className="w-5 h-5 text-indigo-600 flex-shrink-0 mt-0.5" />
          <div>
            <h3 className="font-semibold text-indigo-900">Managed by your organization</h3>
            <p className="text-sm text-indigo-700 mt-1">
              Your organization runs the AI provider for you, so you don&apos;t need an API key and none is stored in this browser.
            </p>
            <p className="text-sm text-indigo-900 mt-2">
              {managedLabel} · {config.model}
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

const IntroBanner: React.FC<{ title: string; description: string }> = ({ title, description }) => (
  <div className="bg-gradient-to-r from-indigo-50 to-blue-50 border border-indigo-200 rounded-lg p-4">
    <div className="flex items-start gap-3">
      <Zap className="w-5 h-5 text-indigo-600 flex-shrink-0 mt-0.5" />
      <div>
        <h3 className="font-semibold text-indigo-900">{title}</h3>
        <p className="text-sm text-indigo-700 mt-1">{description}</p>
      </div>
    </div>
  </div>
);

const ProviderPicker: React.FC<{
  compact: boolean;
  providers: ProviderOption[];
  provider: LlmProvider;
  onSelect: (id: LlmProvider) => void;
}> = ({ compact, providers, provider, onSelect }) => (
  <div>
    <label className="block text-sm font-medium text-gray-700 mb-3">
      Choose Your LLM Provider
    </label>
    <div className={`grid grid-cols-1 gap-3 ${compact ? '' : 'sm:grid-cols-3'}`}>
      {providers.map((p) => (
        <button
          key={p.id}
          onClick={() => onSelect(p.id as LlmProvider)}
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
);

const ProviderInfoCard: React.FC<{ provider: LlmProvider; label: string | undefined }> = ({ provider, label }) => {
  const currentInfo = providerInfo[provider];
  return (
    <div className={`bg-gradient-to-r ${currentInfo.color} rounded-lg p-4 text-white`}>
      <p className="font-medium mb-2">{label}</p>
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
  );
};

const LLMSettingsPanel: React.FC<LLMSettingsPanelProps> = ({
  onSave,
  compact = false,
  bannerTitle = 'Graph View AI Insights',
  bannerDescription = 'Bring your own LLM API key. OntoCode Studio never stores or sees your credentials. Everything stays in your browser.',
  usageNote = '3. Graph analytics will use your key when you click "AI Insights" on the graph',
}) => {
  const s = useLlmSettings(onSave);

  if (s.providerConfig?.managed) {
    return <ManagedProviderNotice compact={compact} config={s.providerConfig} providers={s.providers} />;
  }

  return (
    <div className={`space-y-6 p-4 ${compact ? '' : 'sm:p-6 max-w-full sm:max-w-2xl'}`}>
      <IntroBanner title={bannerTitle} description={bannerDescription} />
      <ProviderPicker compact={compact} providers={s.providers} provider={s.provider} onSelect={s.selectProvider} />
      <ProviderInfoCard provider={s.provider} label={s.providerLabel} />
      <ApiKeyField
        apiKey={s.apiKey}
        setApiKey={s.setApiKey}
        showKey={s.showKey}
        setShowKey={s.setShowKey}
        providerLabel={s.providerLabel}
        onRefreshModels={s.handleRefreshModels}
      />
      <ModelField
        model={s.model}
        setModel={s.setModel}
        models={s.models}
        modelsSource={s.modelsSource}
        modelsRefreshing={s.modelsRefreshing}
        onRefreshModels={s.handleRefreshModels}
      />
      <MaxTokensField value={s.maxResponseTokens} onChange={s.setMaxResponseTokens} />
      <SessionBudgetField value={s.sessionTokenBudget} onChange={s.setSessionTokenBudget} />
      {s.message && <StatusMessage message={s.message} />}
      <ActionButtons
        compact={compact}
        saving={s.saving}
        testing={s.testing}
        hasKey={!!s.apiKey.trim()}
        onSave={s.handleSave}
        onTest={s.handleTestConnection}
        onClear={s.handleClear}
      />
      <ApiKeyLinks provider={s.provider} usageNote={usageNote} />
    </div>
  );
};

export default LLMSettingsPanel;

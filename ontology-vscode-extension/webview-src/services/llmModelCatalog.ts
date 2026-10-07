export type LlmProvider = 'gemini' | 'claude' | 'openai';

interface ProviderConfig {
  name: string;
  displayName: string;
  models: { id: string; label: string }[];
  defaultModel: string;
}

export const PROVIDERS: Record<LlmProvider, ProviderConfig> = {
  gemini: {
    name: 'gemini',
    displayName: 'Google Gemini',
    models: [
      { id: 'gemini-2.5-flash-lite', label: 'Gemini 2.5 Flash Lite (fast, free)' },
      { id: 'gemini-2.5-pro', label: 'Gemini 2.5 Pro (powerful)' },
      { id: 'gemini-3.5-flash', label: 'Gemini 3.5 Flash (latest)' },
    ],
    defaultModel: 'gemini-2.5-flash-lite',
  },
  claude: {
    name: 'claude',
    displayName: 'Anthropic Claude',
    models: [
      { id: 'claude-haiku-4-5', label: 'Claude Haiku 4.5 (fast, cheap)' },
      { id: 'claude-sonnet-5', label: 'Claude Sonnet 5 (balanced)' },
      { id: 'claude-opus-4-8', label: 'Claude Opus 4.8 (most capable)' },
    ],
    defaultModel: 'claude-sonnet-5',
  },
  openai: {
    name: 'openai',
    displayName: 'OpenAI',
    models: [
      { id: 'gpt-5.6-luna', label: 'GPT-5.6 Luna (fast, cheap)' },
      { id: 'gpt-5.6-terra', label: 'GPT-5.6 Terra (balanced)' },
      { id: 'gpt-5.6-sol', label: 'GPT-5.6 Sol (most capable)' },
    ],
    defaultModel: 'gpt-5.6-terra',
  },
};

export interface KnownModel {
  id: string;
  label: string;
}

const MODELS_CACHE_STORAGE = 'ontocode_llm_models_cache';

const MODELS_CACHE_TTL_MS = 12 * 60 * 60 * 1000;

interface ModelsCacheEntry {
  fetchedAt: number;
  models: KnownModel[];
}
type ModelsCache = Partial<Record<LlmProvider, ModelsCacheEntry>>;

function readModelsCache(): ModelsCache {
  try {
    const raw = localStorage.getItem(MODELS_CACHE_STORAGE);
    return raw ? (JSON.parse(raw) as ModelsCache) : {};
  } catch {
    return {};
  }
}

function writeModelsCache(cache: ModelsCache): void {
  try {
    localStorage.setItem(MODELS_CACHE_STORAGE, JSON.stringify(cache));
  } catch {
  }
}

export function getProviderModels(provider: LlmProvider): KnownModel[] {
  const entry = readModelsCache()[provider];
  if (entry && entry.models.length && Date.now() - entry.fetchedAt < MODELS_CACHE_TTL_MS) {
    return entry.models;
  }
  return [];
}

const BUDGET_TIER = /(^|-)(lite|mini|nano|8b|small)(-|$)/i;
const GEMINI_PAID_TIER = /(^|-)pro(-|$)/i;

export function isLikelyPaidOnlyModel(provider: LlmProvider, modelId: string): boolean {
  return provider === 'gemini' && GEMINI_PAID_TIER.test(modelId);
}

function sortModelsTopFirst(models: KnownModel[]): KnownModel[] {
  const flagship = models.filter((m) => !BUDGET_TIER.test(m.id));
  const budget = models.filter((m) => BUDGET_TIER.test(m.id));
  return [...flagship, ...budget];
}

async function listGeminiModels(key: string): Promise<KnownModel[]> {
  const res = await fetch('https://generativelanguage.googleapis.com/v1beta/models', {
    headers: { 'x-goog-api-key': key },
  });
  if (!res.ok) return [];
  const data = await res.json().catch(() => null);
  const models = Array.isArray(data?.models) ? data.models : [];
  const known = models
    .filter((m: any) => Array.isArray(m?.supportedGenerationMethods) && m.supportedGenerationMethods.includes('generateContent'))
    .map((m: any) => ({
      id: String(m.name ?? '').replace(/^models\//, ''),
      label: String(m.displayName ?? m.name ?? '').replace(/^models\//, ''),
    }))
    .filter((m: KnownModel) => m.id);

  const gemini = known.filter((m) => m.id.startsWith('gemini-'));
  const other = known.filter((m) => !m.id.startsWith('gemini-'));

  const flash = gemini.filter((m) => !BUDGET_TIER.test(m.id) && !GEMINI_PAID_TIER.test(m.id));
  const pro = gemini.filter((m) => GEMINI_PAID_TIER.test(m.id) && !BUDGET_TIER.test(m.id));
  const budget = gemini.filter((m) => BUDGET_TIER.test(m.id));

  return [...flash, ...pro, ...budget, ...sortModelsTopFirst(other)];
}

async function listClaudeModels(key: string): Promise<KnownModel[]> {
  const res = await fetch('https://api.anthropic.com/v1/models', {
    headers: { 'x-api-key': key, 'anthropic-version': '2023-06-01', 'anthropic-dangerous-direct-browser-access': 'true' },
  });
  if (!res.ok) return [];
  const data = await res.json().catch(() => null);
  const models = Array.isArray(data?.data) ? data.data : [];
  return sortModelsTopFirst(
    models
      .map((m: any) => ({ id: String(m.id ?? ''), label: String(m.display_name ?? m.id ?? '') }))
      .filter((m: KnownModel) => m.id),
  );
}

async function listOpenAIModels(key: string): Promise<KnownModel[]> {
  const res = await fetch('https://api.openai.com/v1/models', {
    headers: { Authorization: `Bearer ${key}` },
  });
  if (!res.ok) return [];
  const data = await res.json().catch(() => null);
  const models = Array.isArray(data?.data) ? data.data : [];

  const NON_CHAT = /audio|embedding|whisper|tts|instruct|realtime|transcribe|search|moderation|davinci|babbage|image|vision-preview$/i;
  return sortModelsTopFirst(
    models
      .map((m: any) => ({ id: String(m.id ?? ''), label: String(m.id ?? '') }))
      .filter((m: KnownModel) => m.id && /^gpt-/i.test(m.id) && !NON_CHAT.test(m.id)),
  );
}

async function fetchLiveModels(provider: LlmProvider, key: string): Promise<KnownModel[]> {
  try {
    switch (provider) {
      case 'gemini':
        return await listGeminiModels(key);
      case 'claude':
        return await listClaudeModels(key);
      case 'openai':
        return await listOpenAIModels(key);
      default:
        return [];
    }
  } catch {
    return [];
  }
}

export interface RefreshModelsResult {
  models: KnownModel[];

  live: boolean;
}

export async function refreshAvailableModels(provider: LlmProvider, key: string): Promise<RefreshModelsResult> {
  const live = await fetchLiveModels(provider, key);
  if (live.length === 0) return { models: getProviderModels(provider), live: false };
  const cache = readModelsCache();
  cache[provider] = { fetchedAt: Date.now(), models: live };
  writeModelsCache(cache);
  return { models: live, live: true };
}

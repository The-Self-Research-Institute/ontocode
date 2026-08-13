

export type LlmProvider = 'gemini' | 'claude' | 'openai';

interface ProviderConfig {
  name: string;
  displayName: string;
  models: { id: string; label: string }[];
  defaultModel: string;
}

const PROVIDERS: Record<LlmProvider, ProviderConfig> = {
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

const PROVIDER_STORAGE = 'ontocode_llm_provider';
const KEY_STORAGE = 'ontocode_llm_api_key';
const MODEL_STORAGE = 'ontocode_llm_model';
const DEFAULT_PROVIDER: LlmProvider = 'gemini';

export interface LlmInsightRequest {
  ontologyName?: string;
  nodeCount: number;
  clusterCount: number;
  discourseLabel: string;
  focusScore: number;
  topConcepts: string[];
  clusters: Array<{ topWords: string[]; size: number }>;
  gaps: Array<{ a: string; b: string; suggestion: string }>;
}

export interface SelectedNodeContext {
  label: string;
  type: string;
  iri?: string;

  neighbors: string[];

  clusterTopWords?: string[];
}

export interface TopicSuggestion {
  topic: string;
  reason: string;
}

export class LlmConfigError extends Error {}
export class LlmRequestError extends Error {}

export class LlmModelNotFoundError extends LlmRequestError {}

export function getStoredProvider(): LlmProvider {
  try {
    const stored = localStorage.getItem(PROVIDER_STORAGE) as LlmProvider | null;
    return stored && stored in PROVIDERS ? stored : DEFAULT_PROVIDER;
  } catch {
    return DEFAULT_PROVIDER;
  }
}

export function setStoredProvider(provider: LlmProvider): void {
  try {
    if (provider in PROVIDERS) localStorage.setItem(PROVIDER_STORAGE, provider);
  } catch {
    /* ignore */
  }
}

export function getAvailableProviders(): Array<{ id: LlmProvider; label: string }> {
  return Object.entries(PROVIDERS).map(([id, cfg]) => ({
    id: id as LlmProvider,
    label: cfg.displayName,
  }));
}

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
    /* storage unavailable — the live list just won't persist across reloads */
  }
}

export function getProviderModels(provider: LlmProvider): KnownModel[] {
  const entry = readModelsCache()[provider];
  if (entry && entry.models.length && Date.now() - entry.fetchedAt < MODELS_CACHE_TTL_MS) {
    return entry.models;
  }
  return PROVIDERS[provider]?.models ?? [];
}

async function listGeminiModels(key: string): Promise<KnownModel[]> {
  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models?key=${encodeURIComponent(key)}`,
  );
  if (!res.ok) return [];
  const data = await res.json().catch(() => null);
  const models = Array.isArray(data?.models) ? data.models : [];
  return models
    .filter((m: any) => Array.isArray(m?.supportedGenerationMethods) && m.supportedGenerationMethods.includes('generateContent'))
    .map((m: any) => ({
      id: String(m.name ?? '').replace(/^models\//, ''),
      label: String(m.displayName ?? m.name ?? '').replace(/^models\//, ''),
    }))
    .filter((m: KnownModel) => m.id);
}

async function listClaudeModels(key: string): Promise<KnownModel[]> {
  const res = await fetch('https://api.anthropic.com/v1/models', {
    headers: { 'x-api-key': key, 'anthropic-version': '2023-06-01' },
  });
  if (!res.ok) return [];
  const data = await res.json().catch(() => null);
  const models = Array.isArray(data?.data) ? data.data : [];
  return models
    .map((m: any) => ({ id: String(m.id ?? ''), label: String(m.display_name ?? m.id ?? '') }))
    .filter((m: KnownModel) => m.id);
}

async function listOpenAIModels(key: string): Promise<KnownModel[]> {
  const res = await fetch('https://api.openai.com/v1/models', {
    headers: { Authorization: `Bearer ${key}` },
  });
  if (!res.ok) return [];
  const data = await res.json().catch(() => null);
  const models = Array.isArray(data?.data) ? data.data : [];

  const NON_CHAT = /audio|embedding|whisper|tts|instruct|realtime|transcribe|search|moderation|davinci|babbage|image|vision-preview$/i;
  return models
    .map((m: any) => ({ id: String(m.id ?? ''), label: String(m.id ?? '') }))
    .filter((m: KnownModel) => m.id && /^gpt-/i.test(m.id) && !NON_CHAT.test(m.id));
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
    return []; // network failure — caller keeps whatever list it already had
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

export function getStoredApiKey(): string {
  try {
    return localStorage.getItem(KEY_STORAGE) ?? '';
  } catch {
    return '';
  }
}

export function setStoredApiKey(key: string): void {
  try {
    const trimmed = key.trim();
    if (trimmed) localStorage.setItem(KEY_STORAGE, trimmed);
    else localStorage.removeItem(KEY_STORAGE);
  } catch {
    /* storage unavailable — ignore */
  }
}

export function hasApiKey(): boolean {
  return getStoredApiKey().length > 0;
}

export function getStoredModel(): string {
  try {
    const provider = getStoredProvider();
    const stored = localStorage.getItem(MODEL_STORAGE);

    const isKnownModel = stored != null && getProviderModels(provider).some((m) => m.id === stored);
    return isKnownModel ? stored : PROVIDERS[provider].defaultModel;
  } catch {
    return PROVIDERS[DEFAULT_PROVIDER].defaultModel;
  }
}

export function setStoredModel(model: string): void {
  try {
    if (model) localStorage.setItem(MODEL_STORAGE, model);
  } catch {
    /* ignore */
  }
}

function buildContextBlock(req: LlmInsightRequest): string {
  const clusters = req.clusters
    .slice(0, 8)
    .map((c, i) => `  ${i + 1}. [${c.size} concepts] ${c.topWords.slice(0, 5).join(', ')}`)
    .join('\n');
  const gaps = req.gaps
    .slice(0, 6)
    .map((g) => `  - Between "${g.a}" and "${g.b}": ${g.suggestion}`)
    .join('\n');

  return [
    `Ontology: ${req.ontologyName || 'Untitled'}`,
    `Total concepts: ${req.nodeCount}`,
    `Topic clusters: ${req.clusterCount}`,
    `Discourse structure: ${req.discourseLabel} (focus score ${req.focusScore}%)`,
    '',
    'Top concepts by centrality:',
    `  ${req.topConcepts.slice(0, 10).join(', ') || '(none)'}`,
    '',
    'Topic clusters:',
    clusters || '  (none)',
    '',
    'Structural gaps (missing bridges):',
    gaps || '  (none)',
  ].join('\n');
}

function describeSelectedNode(node: SelectedNodeContext): string {
  return [
    `Selected node: "${node.label}" (${node.type}${node.iri ? `, IRI ${node.iri}` : ''})`,
    `Directly connected concepts: ${node.neighbors.slice(0, 20).join(', ') || '(none visible)'}`,
    node.clusterTopWords?.length
      ? `Topic cluster around it: ${node.clusterTopWords.slice(0, 5).join(', ')}`
      : '',
  ]
    .filter(Boolean)
    .join('\n');
}

function buildPrompt(req: LlmInsightRequest): string {
  return [
    'You are an ontology engineering assistant. Analyze the following knowledge-graph',
    'analytics for an OWL ontology and produce concise, actionable insights.',
    '',
    buildContextBlock(req),
    '',
    'Respond in markdown with three short sections:',
    '1. **Summary** — 2-3 sentences describing the ontology structure.',
    '2. **Strengths & Gaps** — bullet points.',
    '3. **Suggestions** — 3-5 concrete modeling improvements (new classes, relations, or bridges).',
    'Keep the whole response under 220 words.',
  ].join('\n');
}

async function callGemini(key: string, model: string, prompt: string, signal?: AbortSignal): Promise<string> {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(key)}`;

  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      contents: [{ parts: [{ text: prompt }] }],
      generationConfig: { temperature: 0.4, maxOutputTokens: 512, topP: 0.9 },
    }),
    signal,
  });

  if (res.status === 400 || res.status === 403) {
    throw new LlmRequestError('Invalid or unauthorized API key. Check your Gemini key.');
  }
  if (res.status === 404) {
    throw new LlmModelNotFoundError(`Gemini model "${model}" is not available for this API key or has been retired.`);
  }
  if (res.status === 429) {
    throw new LlmRequestError('Rate limit reached. Try again shortly.');
  }
  if (!res.ok) {
    throw new LlmRequestError(`Gemini API error (HTTP ${res.status}).`);
  }

  const data = await res.json().catch(() => null);
  const text: string =
    data?.candidates?.[0]?.content?.parts?.map((p: { text?: string }) => p.text ?? '').join('') ?? '';
  if (!text.trim()) {
    throw new LlmRequestError('The AI provider returned an empty response.');
  }
  return text.trim();
}

async function callClaude(key: string, model: string, prompt: string, signal?: AbortSignal): Promise<string> {
  const res = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-api-key': key,
      'anthropic-version': '2023-06-01',
    },
    body: JSON.stringify({

      model,
      max_tokens: 512,
      messages: [{ role: 'user', content: prompt }],
    }),
    signal,
  });

  if (res.status === 401) {
    throw new LlmRequestError('Invalid or unauthorized API key. Check your Claude key.');
  }
  if (res.status === 404) {
    throw new LlmModelNotFoundError(`Claude model "${model}" is not available for this API key or has been retired.`);
  }
  if (res.status === 429) {
    throw new LlmRequestError('Rate limit reached. Try again shortly.');
  }
  if (!res.ok) {
    throw new LlmRequestError(`Claude API error (HTTP ${res.status}).`);
  }

  const data = await res.json().catch(() => null);
  const text: string = data?.content?.[0]?.text ?? '';
  if (!text.trim()) {
    throw new LlmRequestError('The AI provider returned an empty response.');
  }
  return text.trim();
}

async function callOpenAI(key: string, model: string, prompt: string, signal?: AbortSignal): Promise<string> {
  const res = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${key}`,
    },
    body: JSON.stringify({
      model,
      max_tokens: 512,
      temperature: 0.4,
      messages: [{ role: 'user', content: prompt }],
    }),
    signal,
  });

  if (res.status === 401) {
    throw new LlmRequestError('Invalid or unauthorized API key. Check your OpenAI key.');
  }
  if (res.status === 404) {
    throw new LlmModelNotFoundError(`OpenAI model "${model}" is not available for this API key or has been retired.`);
  }
  if (res.status === 429) {
    throw new LlmRequestError('Rate limit reached. Try again shortly.');
  }
  if (!res.ok) {
    throw new LlmRequestError(`OpenAI API error (HTTP ${res.status}).`);
  }

  const data = await res.json().catch(() => null);
  const text: string = data?.choices?.[0]?.message?.content ?? '';
  if (!text.trim()) {
    throw new LlmRequestError('The AI provider returned an empty response.');
  }
  return text.trim();
}

async function callModel(
  provider: LlmProvider,
  key: string,
  model: string,
  prompt: string,
  signal?: AbortSignal,
): Promise<string> {
  switch (provider) {
    case 'gemini':
      return callGemini(key, model, prompt, signal);
    case 'claude':
      return callClaude(key, model, prompt, signal);
    case 'openai':
      return callOpenAI(key, model, prompt, signal);
    default:
      throw new LlmRequestError(`Unknown LLM provider: ${provider}`);
  }
}

async function callProvider(prompt: string, signal?: AbortSignal): Promise<string> {
  const key = getStoredApiKey();
  if (!key) {
    const provider = getStoredProvider();
    const providerName = PROVIDERS[provider].displayName;
    throw new LlmConfigError(`No API key configured. Add your ${providerName} API key to enable AI insights.`);
  }

  const provider = getStoredProvider();
  const model = getStoredModel();

  try {
    return await callModel(provider, key, model, prompt, signal);
  } catch (e) {
    if (!(e instanceof LlmModelNotFoundError)) {
      if (e instanceof LlmRequestError || e instanceof LlmConfigError) throw e;
      throw new LlmRequestError(
        'Could not reach the AI provider. Check your connection and firewall settings.',
      );
    }

    const tried = new Set([model]);
    const attemptAll = async (ids: string[]): Promise<string | null> => {
      for (const candidate of ids) {
        if (tried.has(candidate)) continue;
        tried.add(candidate);
        try {
          const text = await callModel(provider, key, candidate, prompt, signal);
          setStoredModel(candidate); // remember what actually works for this key
          return (
            `_Note: "${model}" isn't available for your API key — switched to ` +
            `${PROVIDERS[provider].displayName}'s "${candidate}" and saved it as your model. ` +
            `You can change this any time in AI settings._\n\n${text}`
          );
        } catch (candidateError) {
          if (candidateError instanceof LlmModelNotFoundError) {
            lastError = candidateError;
            continue; // this one 404s too — try the next candidate
          }

          throw candidateError;
        }
      }
      return null;
    };

    let lastError: LlmModelNotFoundError = e;
    const cachedResult = await attemptAll(getProviderModels(provider).map(m => m.id));
    if (cachedResult) return cachedResult;

    const { models: liveModels } = await refreshAvailableModels(provider, key);
    const liveResult = await attemptAll(liveModels.map(m => m.id));
    if (liveResult) return liveResult;

    throw new LlmRequestError(
      `${lastError.message} None of ${PROVIDERS[provider].displayName}'s known models worked for this ` +
      'API key. Double-check the key at the provider\'s console, or try a different provider.',
    );
  }
}

export async function generateGraphInsights(
  req: LlmInsightRequest,
  signal?: AbortSignal,
): Promise<string> {
  return callProvider(buildPrompt(req), signal);
}

export async function askGraphQuestion(
  question: string,
  req: LlmInsightRequest,
  node?: SelectedNodeContext | null,
  signal?: AbortSignal,
): Promise<string> {
  const trimmed = question.trim();
  if (!trimmed) throw new LlmRequestError('Type a question first.');

  const prompt = [
    'You are an ontology engineering assistant. Use the knowledge-graph analytics',
    "below as context and answer the user's question about this OWL ontology.",
    '',
    buildContextBlock(req),
    ...(node ? ['', describeSelectedNode(node)] : []),
    '',
    `User question: ${trimmed}`,
    '',
    'Answer in concise markdown. Ground statements in the context above; if the context',
    'is insufficient, say what additional information would be needed. Keep it under 250 words.',
  ].join('\n');

  return callProvider(prompt, signal);
}

export async function suggestTopicsForNode(
  node: SelectedNodeContext,
  req: LlmInsightRequest,
  signal?: AbortSignal,
): Promise<TopicSuggestion[]> {
  const prompt = [
    'You are an ontology engineering assistant helping to extend an OWL ontology.',
    'Based on the selected node and its context, suggest topics to model next:',
    'subclasses, sibling concepts, related concepts, or missing links.',
    '',
    buildContextBlock(req),
    '',
    describeSelectedNode(node),
    '',
    'Respond with ONLY a JSON array (no prose, no code fences) of 5 to 8 items shaped as:',
    '[{"topic": "Short Topic Name", "reason": "one line on why it belongs near the selected node"}]',
    'Topics must be concise noun phrases (max 4 words) and must not duplicate the',
    'directly connected concepts listed above.',
  ].join('\n');

  const raw = await callProvider(prompt, signal);
  const parsed = parseTopicSuggestions(raw);
  if (!parsed.length) {
    throw new LlmRequestError('The AI provider returned no usable topic suggestions. Try again.');
  }
  return parsed;
}

function parseTopicSuggestions(raw: string): TopicSuggestion[] {

  const unfenced = raw.replace(/```(?:json)?/gi, '').trim();
  const start = unfenced.indexOf('[');
  const end = unfenced.lastIndexOf(']');
  if (start !== -1 && end > start) {
    try {
      const arr = JSON.parse(unfenced.slice(start, end + 1));
      if (Array.isArray(arr)) {
        return arr
          .map((it: { topic?: unknown; reason?: unknown }) => ({
            topic: String(it?.topic ?? '').trim(),
            reason: String(it?.reason ?? '').trim(),
          }))
          .filter((it) => it.topic.length > 0 && it.topic.length <= 80)
          .slice(0, 8);
      }
    } catch {
      /* fall through to line parsing */
    }
  }

  return unfenced
    .split('\n')
    .map((line) => line.replace(/^\s*(?:[-*•]|\d+[.)])\s*/, '').trim())
    .filter(Boolean)
    .map((line) => {
      const m = line.match(/^(.{2,80}?)\s*[—–:]\s+(.+)$/);
      return m ? { topic: m[1].trim(), reason: m[2].trim() } : { topic: line.slice(0, 80), reason: '' };
    })
    .filter((it) => it.topic.length > 1)
    .slice(0, 8);
}

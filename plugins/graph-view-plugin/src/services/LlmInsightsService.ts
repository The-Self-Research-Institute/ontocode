/**
 * BYOK (Bring-Your-Own-Key) LLM insights for the graph analytics panel.
 *
 * Users can choose their preferred LLM provider (Gemini, Claude, OpenAI).
 * API keys are stored only in the user's browser localStorage and sent directly to
 * the provider's API from the client. OntoCode never stores or sees the keys.
 *
 * Security notes:
 * - We never bundle any API key. The key is user-provided at runtime.
 * - In the VS Code webview, calls to external APIs require the host CSP to allow
 *   that connect-src; in the web/desktop app it works directly.
 */

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
      { id: 'claude-3-5-haiku-20241022', label: 'Claude 3.5 Haiku (fast, cheap)' },
      { id: 'claude-3-5-sonnet-20241022', label: 'Claude 3.5 Sonnet (balanced)' },
      { id: 'claude-opus-4-1-20250805', label: 'Claude Opus (most capable)' },
    ],
    defaultModel: 'claude-3-5-sonnet-20241022',
  },
  openai: {
    name: 'openai',
    displayName: 'OpenAI',
    models: [
      { id: 'gpt-4o-mini', label: 'GPT-4o Mini (fast, cheap)' },
      { id: 'gpt-4o', label: 'GPT-4o (balanced)' },
      { id: 'o1', label: 'o1 (reasoning, slower)' },
    ],
    defaultModel: 'gpt-4o-mini',
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

export class LlmConfigError extends Error {}
export class LlmRequestError extends Error {}

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

export function getProviderModels(provider: LlmProvider): { id: string; label: string }[] {
  return PROVIDERS[provider]?.models ?? [];
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
    // A previously-stored model id can go stale when a provider retires a model
    // generation (e.g. Gemini shut down the entire 1.0/1.5 line) — fall back to
    // the current default instead of repeating a 404 the user can't self-diagnose.
    const isKnownModel = stored != null && PROVIDERS[provider].models.some((m) => m.id === stored);
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

function buildPrompt(req: LlmInsightRequest): string {
  const clusters = req.clusters
    .slice(0, 8)
    .map((c, i) => `  ${i + 1}. [${c.size} concepts] ${c.topWords.slice(0, 5).join(', ')}`)
    .join('\n');
  const gaps = req.gaps
    .slice(0, 6)
    .map((g) => `  - Between "${g.a}" and "${g.b}": ${g.suggestion}`)
    .join('\n');

  return [
    'You are an ontology engineering assistant. Analyze the following knowledge-graph',
    'analytics for an OWL ontology and produce concise, actionable insights.',
    '',
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
      temperature: 0.4,
      messages: [{ role: 'user', content: prompt }],
    }),
    signal,
  });

  if (res.status === 401) {
    throw new LlmRequestError('Invalid or unauthorized API key. Check your Claude key.');
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

/**
 * Generate insights via the user's chosen LLM provider. Returns markdown text.
 * Throws LlmConfigError when no key is set, LlmRequestError on API failure.
 */
export async function generateGraphInsights(
  req: LlmInsightRequest,
  signal?: AbortSignal,
): Promise<string> {
  const key = getStoredApiKey();
  if (!key) {
    const provider = getStoredProvider();
    const providerName = PROVIDERS[provider].displayName;
    throw new LlmConfigError(`No API key configured. Add your ${providerName} API key to enable AI insights.`);
  }

  const provider = getStoredProvider();
  const model = getStoredModel();
  const prompt = buildPrompt(req);

  try {
    switch (provider) {
      case 'gemini':
        return await callGemini(key, model, prompt, signal);
      case 'claude':
        return await callClaude(key, model, prompt, signal);
      case 'openai':
        return await callOpenAI(key, model, prompt, signal);
      default:
        throw new LlmRequestError(`Unknown LLM provider: ${provider}`);
    }
  } catch (e) {
    if (e instanceof LlmRequestError || e instanceof LlmConfigError) throw e;
    throw new LlmRequestError(
      'Could not reach the AI provider. Check your connection and firewall settings.',
    );
  }
}

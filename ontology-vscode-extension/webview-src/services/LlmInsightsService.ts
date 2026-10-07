import { getApiKey, initApiKeyStore, setApiKey } from './assistantKeyStore';
import { getProviderModels, LlmProvider, PROVIDERS, refreshAvailableModels } from './llmModelCatalog';

export type { KnownModel, LlmProvider, RefreshModelsResult } from './llmModelCatalog';
export { getProviderModels, isLikelyPaidOnlyModel, refreshAvailableModels } from './llmModelCatalog';

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

export function ensureApiKeyLoaded(): Promise<void> {
  return initApiKeyStore(KEY_STORAGE);
}

export function getStoredApiKey(): string {
  void ensureApiKeyLoaded();
  return getApiKey();
}

export function setStoredApiKey(key: string): void {
  void setApiKey(key);
}

export function hasApiKey(): boolean {
  return getStoredApiKey().length > 0;
}

export function getStoredModel(): string {
  try {
    const provider = getStoredProvider();
    const stored = localStorage.getItem(MODEL_STORAGE);

    const isKnownModel = stored != null && getProviderModels(provider).some((m) => m.id === stored);
    return isKnownModel ? stored : "";
  } catch {
    return "";
  }
}

export function setStoredModel(model: string): void {
  try {
    if (model) localStorage.setItem(MODEL_STORAGE, model);
  } catch {
    /* ignore */
  }
}

const MAX_TOKENS_STORAGE = 'ontocode_llm_max_response_tokens';
export const DEFAULT_MAX_RESPONSE_TOKENS = 8192;
const MIN_MAX_RESPONSE_TOKENS = 512;
const MAX_MAX_RESPONSE_TOKENS = 32768;

const SHOW_LABEL_OVERLAY_STORAGE = 'ontocode_llm_show_label_overlay';

export function getStoredShowLabelOverlay(): boolean {
  try {
    const stored = localStorage.getItem(SHOW_LABEL_OVERLAY_STORAGE);
    if (stored === '0') return false;
    if (stored === '1') return true;
    return true;
  } catch {
    return true;
  }
}

export function setStoredShowLabelOverlay(value: boolean): void {
  try {
    localStorage.setItem(SHOW_LABEL_OVERLAY_STORAGE, value ? '1' : '0');
  } catch {
  }
}

export function getStoredMaxResponseTokens(): number {
  try {
    const stored = Number(localStorage.getItem(MAX_TOKENS_STORAGE));
    if (Number.isInteger(stored) && stored >= MIN_MAX_RESPONSE_TOKENS && stored <= MAX_MAX_RESPONSE_TOKENS) return stored;
    return DEFAULT_MAX_RESPONSE_TOKENS;
  } catch {
    return DEFAULT_MAX_RESPONSE_TOKENS;
  }
}

export function setStoredMaxResponseTokens(value: number): void {
  try {
    const clamped = Math.round(Math.min(MAX_MAX_RESPONSE_TOKENS, Math.max(MIN_MAX_RESPONSE_TOKENS, value)));
    localStorage.setItem(MAX_TOKENS_STORAGE, String(clamped));
  } catch {
  }
}

const SESSION_TOKEN_BUDGET_STORAGE = 'ontocode_llm_session_token_budget';
export const DEFAULT_SESSION_TOKEN_BUDGET = 8000;
const MIN_SESSION_TOKEN_BUDGET = 2000;
const MAX_SESSION_TOKEN_BUDGET = 20000;

export function getStoredSessionTokenBudget(): number {
  try {
    const stored = Number(localStorage.getItem(SESSION_TOKEN_BUDGET_STORAGE));
    if (Number.isInteger(stored) && stored >= MIN_SESSION_TOKEN_BUDGET && stored <= MAX_SESSION_TOKEN_BUDGET) {
      return stored;
    }
    return DEFAULT_SESSION_TOKEN_BUDGET;
  } catch {
    return DEFAULT_SESSION_TOKEN_BUDGET;
  }
}

export function setStoredSessionTokenBudget(value: number): number {
  const clamped = Math.round(Math.min(MAX_SESSION_TOKEN_BUDGET, Math.max(MIN_SESSION_TOKEN_BUDGET, value)));
  try {
    localStorage.setItem(SESSION_TOKEN_BUDGET_STORAGE, String(clamped));
  } catch {
  }
  return clamped;
}

const RETRIEVAL_ATTEMPTS_STORAGE = 'ontocode_llm_retrieval_attempts';
export const DEFAULT_RETRIEVAL_ATTEMPTS = 8;
const MIN_RETRIEVAL_ATTEMPTS = 2;
const MAX_RETRIEVAL_ATTEMPTS = 30;

export function getStoredRetrievalAttempts(): number {
  try {
    const stored = Number(localStorage.getItem(RETRIEVAL_ATTEMPTS_STORAGE));
    if (Number.isInteger(stored) && stored >= MIN_RETRIEVAL_ATTEMPTS && stored <= MAX_RETRIEVAL_ATTEMPTS) {
      return stored;
    }
    return DEFAULT_RETRIEVAL_ATTEMPTS;
  } catch {
    return DEFAULT_RETRIEVAL_ATTEMPTS;
  }
}

export function setStoredRetrievalAttempts(value: number): number {
  const clamped = Math.round(Math.min(MAX_RETRIEVAL_ATTEMPTS, Math.max(MIN_RETRIEVAL_ATTEMPTS, value)));
  try {
    localStorage.setItem(RETRIEVAL_ATTEMPTS_STORAGE, String(clamped));
  } catch {
  }
  return clamped;
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
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`;

  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
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
      'anthropic-dangerous-direct-browser-access': 'true',
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

  const findWorkingModel = async (seedError: LlmModelNotFoundError | null): Promise<string> => {
    const tried = new Set(model ? [model] : []);
    const attemptAll = async (ids: string[]): Promise<string | null> => {
      for (const candidate of ids) {
        if (tried.has(candidate)) continue;
        tried.add(candidate);
        try {
          const text = await callModel(provider, key, candidate, prompt, signal);
          setStoredModel(candidate); // remember what actually works for this key
          return model
            ? `_Note: "${model}" isn't available for your API key — switched to ` +
              `${PROVIDERS[provider].displayName}'s "${candidate}" and saved it as your model. ` +
              `You can change this any time in AI settings._\n\n${text}`
            : text;
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

    let lastError = seedError;
    const cachedResult = await attemptAll(getProviderModels(provider).map(m => m.id));
    if (cachedResult) return cachedResult;

    const { models: liveModels } = await refreshAvailableModels(provider, key);
    const liveResult = await attemptAll(liveModels.map(m => m.id));
    if (liveResult) return liveResult;

    if (lastError) {
      throw new LlmRequestError(
        `${lastError.message} None of ${PROVIDERS[provider].displayName}'s known models worked for this ` +
        'API key. Double-check the key at the provider\'s console, or try a different provider.',
      );
    }
    throw new LlmRequestError(
      `No ${PROVIDERS[provider].displayName} model is available yet. Open the model picker to refresh ` +
      'the list, or double-check your API key.',
    );
  };

  if (!model) return findWorkingModel(null);

  try {
    return await callModel(provider, key, model, prompt, signal);
  } catch (e) {
    if (!(e instanceof LlmModelNotFoundError)) {
      if (e instanceof LlmRequestError || e instanceof LlmConfigError) throw e;
      throw new LlmRequestError(
        'Could not reach the AI provider. Check your connection and firewall settings.',
      );
    }
    return findWorkingModel(e);
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

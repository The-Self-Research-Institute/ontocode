/**
 * BYOK (Bring-Your-Own-Key) LLM insights for the graph analytics panel.
 *
 * The user supplies their own Google Gemini API key — OntoCode never stores a key
 * of its own and incurs no LLM cost. The key lives only in the user's browser
 * localStorage and is sent directly to Google's API from the client.
 *
 * Security notes:
 * - We never bundle any API key. The key is user-provided at runtime.
 * - In the VS Code webview, calls to generativelanguage.googleapis.com require the
 *   host CSP to allow that connect-src; in the web/desktop app it works directly.
 */

const KEY_STORAGE = 'ontocode_llm_api_key';
const MODEL_STORAGE = 'ontocode_llm_model';
const DEFAULT_MODEL = 'gemini-1.5-flash';

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
    return localStorage.getItem(MODEL_STORAGE) || DEFAULT_MODEL;
  } catch {
    return DEFAULT_MODEL;
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

/**
 * Generate insights via the user's Gemini key. Returns markdown text.
 * Throws LlmConfigError when no key is set, LlmRequestError on API failure.
 */
export async function generateGraphInsights(
  req: LlmInsightRequest,
  signal?: AbortSignal,
): Promise<string> {
  const key = getStoredApiKey();
  if (!key) {
    throw new LlmConfigError('No API key configured. Add your Gemini API key to enable AI insights.');
  }

  const model = getStoredModel();
  const url =
    `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(key)}`;

  let res: Response;
  try {
    res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{ parts: [{ text: buildPrompt(req) }] }],
        generationConfig: { temperature: 0.4, maxOutputTokens: 512, topP: 0.9 },
      }),
      signal,
    });
  } catch (e) {
    throw new LlmRequestError(
      'Could not reach the AI provider. Check your connection (and, in VS Code, that the host allows generativelanguage.googleapis.com).',
    );
  }

  if (res.status === 400 || res.status === 403) {
    throw new LlmRequestError('Invalid or unauthorized API key. Double-check your Gemini key.');
  }
  if (res.status === 429) {
    throw new LlmRequestError('Rate limit reached on your Gemini key. Try again shortly.');
  }
  if (!res.ok) {
    throw new LlmRequestError(`AI provider error (HTTP ${res.status}).`);
  }

  const data = await res.json().catch(() => null);
  const text: string =
    data?.candidates?.[0]?.content?.parts?.map((p: { text?: string }) => p.text ?? '').join('') ?? '';
  if (!text.trim()) {
    throw new LlmRequestError('The AI provider returned an empty response.');
  }
  return text.trim();
}

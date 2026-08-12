/**
 * Local LLM term mapping for ask-the-graph (privacy-safe, zero-cost).
 * Talks to Ollama at localhost:11434. Maps question terms that matched no
 * node label (e.g. "sensors") onto actual labels ("SensingDevice").
 * Every failure path returns null so the deterministic matcher stays in charge.
 */

const OLLAMA_URL = 'http://localhost:11434';
const MAX_LABELS = 400;
const REQUEST_TIMEOUT_MS = 15000;

/** Small instruct models good at JSON, in preference order. */
const PREFERRED_MODELS = ['qwen2.5:1.5b', 'qwen2.5:3b', 'llama3.2:1b', 'llama3.2:3b', 'phi3'];

let cachedModel: string | null | undefined;

async function fetchJson(url: string, init?: RequestInit): Promise<any> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const res = await fetch(url, { ...init, signal: controller.signal });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

/** Probe Ollama and pick a model (preferred list first, then smallest installed). */
export async function getLocalModel(): Promise<string | null> {
  if (cachedModel !== undefined) return cachedModel;
  const data = await fetchJson(`${OLLAMA_URL}/api/tags`);
  const models: Array<{ name: string; size: number }> = data?.models ?? [];
  if (models.length === 0) {
    cachedModel = null;
    return null;
  }
  const preferred = PREFERRED_MODELS
    .map(p => models.find(m => m.name === p || m.name.startsWith(`${p}-`) || m.name.startsWith(`${p}:`)))
    .find(Boolean);
  cachedModel = (preferred ?? [...models].sort((a, b) => a.size - b.size)[0]).name;
  return cachedModel;
}

/** Allow re-probing after the user starts/installs Ollama mid-session. */
export function resetLocalModelCache(): void {
  cachedModel = undefined;
}

/**
 * Ask the local model to map unmatched question terms to real node labels.
 * Returns { term: [label, …] } using only labels from the graph, or null when
 * the model is unavailable / returns nothing usable.
 */
export async function mapTermsToLabels(
  question: string,
  unmatchedTerms: string[],
  labels: string[]
): Promise<Record<string, string[]> | null> {
  if (unmatchedTerms.length === 0 || labels.length === 0) return null;
  const model = await getLocalModel();
  if (!model) return null;

  const labelList = labels.slice(0, MAX_LABELS);
  const prompt = [
    'You map words from a user question onto node labels of a knowledge graph.',
    `Question: "${question}"`,
    `Words with no matching label: ${JSON.stringify(unmatchedTerms)}`,
    `Available node labels: ${JSON.stringify(labelList)}`,
    'For each word, pick the labels (max 3) that mean the same thing or are the closest concept.',
    'Use ONLY labels from the list, verbatim. If nothing fits a word, use an empty array.',
    'Respond with a single JSON object mapping each word to an array of labels. No other text.'
  ].join('\n');

  const data = await fetchJson(`${OLLAMA_URL}/api/generate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, prompt, stream: false, format: 'json', options: { temperature: 0 } })
  });
  if (!data?.response) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(data.response);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== 'object') return null;

  // Accept only labels that truly exist — small models occasionally invent variants.
  const canonical = new Map(labelList.map(l => [l.toLowerCase(), l]));
  const result: Record<string, string[]> = {};
  for (const term of unmatchedTerms) {
    const raw = (parsed as Record<string, unknown>)[term];
    if (!Array.isArray(raw)) continue;
    const valid = raw
      .filter((l): l is string => typeof l === 'string')
      .map(l => canonical.get(l.toLowerCase()))
      .filter((l): l is string => !!l);
    if (valid.length > 0) result[term] = [...new Set(valid)];
  }
  return Object.keys(result).length > 0 ? result : null;
}

/**
 * Ask the local model to write a SPARQL SELECT for a natural-language question.
 * Schema = class labels+IRIs from the rendered graph. Returns null when the
 * model is unavailable or the output is not a usable query.
 */
export async function generateSparql(
  question: string,
  classes: Array<{ iri: string; label: string }>,
  predicates: string[]
): Promise<string | null> {
  const model = await getLocalModel();
  if (!model) return null;

  const schema = classes.slice(0, 200).map(c => `${c.label} = <${c.iri}>`).join('\n');
  const prompt = [
    'Write one SPARQL SELECT query for a question about an OWL ontology.',
    `Question: "${question}"`,
    'Classes (label = IRI):',
    schema,
    predicates.length > 0 ? `Relation names that exist: ${predicates.slice(0, 30).join(', ')}` : '',
    'Rules:',
    '- Use PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>',
    '- Use only IRIs from the list above, in full <...> form.',
    '- Prefer rdfs:subClassOf patterns (with * for transitivity) and FILTER/CONTAINS on STR() when unsure.',
    '- For "most"/"how many"/"count" questions use COUNT with GROUP BY, ORDER BY DESC and LIMIT.',
    '- Bind every entity the user asks about to a variable in the SELECT clause.',
    '- Output ONLY the SPARQL query. No markdown, no explanation.'
  ].filter(Boolean).join('\n');

  const data = await fetchJson(`${OLLAMA_URL}/api/generate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, prompt, stream: false, options: { temperature: 0 } })
  });
  if (!data?.response) return null;

  const query = String(data.response).replace(/```(?:sparql)?/gi, '').trim();
  const isSparql = /^\s*(PREFIX|BASE|SELECT)\b/i.test(query) && /\bSELECT\b/i.test(query) && query.includes('{');
  return isSparql ? query : null;
}

/**
 * Compose a short natural-language answer from query results (IRIs should
 * already be replaced with labels). Returns null when the model is unavailable
 * or produced nothing usable — the card then just shows the raw summary.
 */
export async function answerQuestion(
  question: string,
  results: Array<Record<string, string>>,
  sparql?: string
): Promise<string | null> {
  if (results.length === 0) return null;
  const model = await getLocalModel();
  if (!model) return null;

  const prompt = [
    'Answer the question using ONLY the query results below.',
    `Question: "${question}"`,
    sparql ? `SPARQL that produced the results:\n${sparql}` : '',
    `Results (JSON):\n${JSON.stringify(results.slice(0, 30))}`,
    'Reply with 1-2 short plain-text sentences naming the entities from the results.',
    'If the results do not answer the question, briefly say what they show instead. No markdown.'
  ].filter(Boolean).join('\n');

  const data = await fetchJson(`${OLLAMA_URL}/api/generate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, prompt, stream: false, options: { temperature: 0 } })
  });
  const answer = typeof data?.response === 'string' ? data.response.trim() : '';
  return answer.length > 0 && answer.length < 600 ? answer : null;
}

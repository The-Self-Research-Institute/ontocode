

import type Graph from 'graphology';
import type { AskResult } from './askGraph';

export function isSparqlQuery(text: string): boolean {
  return /^\s*(PREFIX|BASE|SELECT|ASK|CONSTRUCT|DESCRIBE)\b/i.test(text);
}

interface SparqlResponse {
  head?: { vars?: string[] };
  results?: Array<Record<string, string>>;
  error?: string;
}

export async function runSparqlHighlight(
  graph: Graph,
  query: string,
  projectId: string
): Promise<AskResult> {
  const apiBaseUrl = (window as any).__DESKTOP_API_URL__ || (window as any).API_BASE_URL;
  if (!apiBaseUrl) {
    return emptyResult('No API endpoint configured — SPARQL needs the editor backend');
  }

  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const token = localStorage.getItem('authToken');
  if (token) headers['Authorization'] = `Bearer ${token}`;

  let data: SparqlResponse;
  try {
    const res = await fetch(`${apiBaseUrl}/api/sparql/query/${encodeURIComponent(projectId)}`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ query })
    });
    data = await res.json();
    if (!res.ok) return emptyResult(`SPARQL error: ${data?.error ?? `HTTP ${res.status}`}`);
  } catch (e) {
    return emptyResult(`SPARQL request failed: ${e instanceof Error ? e.message : String(e)}`);
  }

  const rows = data.results ?? [];
  const vars = data.head?.vars ?? [];

  const answers = new Set<string>();
  for (const row of rows) {
    for (const value of Object.values(row)) {
      if (value && graph.hasNode(value)) answers.add(value);
    }
  }

  const subgraph = new Set(answers);
  const varList = vars.length > 0 ? ` · ${vars.map(v => `?${v}`).join(' ')}` : '';
  return {
    subgraph,
    answers,
    summary: rows.length === 0
      ? 'SPARQL: 0 results'
      : `SPARQL: ${rows.length} result${rows.length === 1 ? '' : 's'}, ${answers.size} in view${varList}`,
    predicates: [],
    rows
  };
}

function emptyResult(summary: string): AskResult {
  return { subgraph: new Set(), answers: new Set(), summary, predicates: [] };
}

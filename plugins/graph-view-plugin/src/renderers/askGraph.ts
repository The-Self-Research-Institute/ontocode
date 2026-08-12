/**
 * Ask-the-graph: deterministic NL question → highlighted subgraph.
 * Structure-only: terms are matched to node labels, then typed edge paths
 * connect the term groups. No LLM in the loop — an LLM can later replace
 * parseQuestion() without touching the path machinery.
 */

import type Graph from 'graphology';

export interface AskResult {
  /** Every node to emphasize (answers + connecting paths). */
  subgraph: Set<string>;
  /** The nodes that answer the question (strong highlight). */
  answers: Set<string>;
  /** Human-readable summary for the answer card. */
  summary: string;
  /** Distinct predicates used on connecting paths. */
  predicates: string[];
  /** Optional technical detail (e.g. AI-generated SPARQL) shown in the card. */
  detail?: string;
  /** Natural-language answer composed by the local AI. */
  answer?: string;
  /** Raw SPARQL result rows (when the result came from a query). */
  rows?: Array<Record<string, string>>;
}

const STOPWORDS = new Set([
  'the', 'a', 'an', 'of', 'in', 'on', 'at', 'to', 'by', 'for', 'with', 'and', 'or',
  'is', 'are', 'was', 'were', 'be', 'been', 'do', 'does', 'did', 'has', 'have', 'had',
  'which', 'what', 'who', 'whose', 'where', 'when', 'how', 'why', 'show', 'me', 'all',
  'list', 'find', 'get', 'that', 'this', 'these', 'those', 'there', 'their', 'its', 'it',
  'graph', 'ontology', 'node', 'nodes', 'class', 'classes'
]);

/** quality/qualities, sensor/sensors, process/processes → shared stem. */
function stem(word: string): string {
  let w = word.toLowerCase();
  if (w.endsWith('ies')) w = w.slice(0, -3) + 'y';
  else if (w.endsWith('ses')) w = w.slice(0, -2);
  else if (w.endsWith('s') && !w.endsWith('ss')) w = w.slice(0, -1);
  return w;
}

function parseQuestion(question: string): string[] {
  return question
    .split(/[^a-zA-Z0-9]+/)
    .filter(t => t.length > 2 && !STOPWORDS.has(t.toLowerCase()))
    .map(stem);
}

/** Question terms that match no node label — candidates for AI synonym mapping. */
export function getUnmatchedTerms(graph: Graph, question: string): string[] {
  return parseQuestion(question).filter(term =>
    !graph.someNode((_id, attrs) => stemmedIncludes(String(attrs.label ?? ''), term))
  );
}

export function askGraph(
  graph: Graph,
  question: string,
  synonyms?: Record<string, string[]>
): AskResult | null {
  const terms = parseQuestion(question);
  if (terms.length === 0) return null;

  // Map each term to the nodes whose label contains it (or an AI-mapped synonym label)
  const groups: Array<{ term: string; ids: Set<string> }> = [];
  for (const term of terms) {
    const synLabels = new Set((synonyms?.[term] ?? []).map(l => l.toLowerCase()));
    const ids = new Set<string>(
      graph.filterNodes((_id, attrs) => {
        const label = String(attrs.label ?? '');
        return stemmedIncludes(label, term) || synLabels.has(label.toLowerCase());
      })
    );
    if (ids.size > 0 && !groups.some(g => g.term === term)) groups.push({ term, ids });
  }
  if (groups.length === 0) return null;

  // Single concept: the answer is the group plus its immediate neighborhood
  if (groups.length === 1) {
    const [g] = groups;
    const subgraph = new Set<string>(g.ids);
    g.ids.forEach(id => graph.forEachNeighbor(id, n => subgraph.add(n)));
    return {
      subgraph,
      answers: g.ids,
      summary: `${g.ids.size} “${g.term}” node${g.ids.size === 1 ? '' : 's'} and their direct connections`,
      predicates: collectPredicates(graph, subgraph)
    };
  }

  // Two+ concepts: connect the first group to the last via shortest paths (≤ 4 hops)
  const source = groups[0];
  const target = groups[groups.length - 1];
  const subgraph = new Set<string>();
  const answers = new Set<string>();

  target.ids.forEach(targetId => {
    const path = shortestPath(graph, targetId, source.ids, 4);
    if (path) {
      path.forEach(id => subgraph.add(id));
      answers.add(targetId);
    }
  });

  if (answers.size === 0) {
    return {
      subgraph: new Set([...source.ids, ...target.ids]),
      answers: new Set(),
      summary: `No path (≤4 hops) connects “${source.term}” and “${target.term}” — possible blind spot`,
      predicates: []
    };
  }

  return {
    subgraph,
    answers,
    summary: `${answers.size} “${target.term}” node${answers.size === 1 ? ' connects' : 's connect'} to “${source.term}”`,
    predicates: collectPredicates(graph, subgraph)
  };
}

function stemmedIncludes(label: string, term: string): boolean {
  const l = label.toLowerCase();
  if (l.includes(term)) return true;
  return label.split(/[^a-zA-Z0-9]+/).some(w => stem(w) === term);
}

/** BFS from start until any node in goals; returns the node path or null. */
function shortestPath(graph: Graph, start: string, goals: Set<string>, maxDepth: number): string[] | null {
  if (goals.has(start)) return [start];
  const prev = new Map<string, string>();
  const seen = new Set([start]);
  let frontier = [start];
  for (let depth = 0; depth < maxDepth; depth++) {
    const next: string[] = [];
    for (const node of frontier) {
      for (const neighbor of graph.neighbors(node)) {
        if (seen.has(neighbor)) continue;
        seen.add(neighbor);
        prev.set(neighbor, node);
        if (goals.has(neighbor)) {
          const path = [neighbor];
          let cur = neighbor;
          while (prev.has(cur)) { cur = prev.get(cur)!; path.push(cur); }
          return path;
        }
        next.push(neighbor);
      }
    }
    frontier = next;
    if (frontier.length === 0) break;
  }
  return null;
}

function collectPredicates(graph: Graph, nodes: Set<string>): string[] {
  const preds = new Set<string>();
  graph.forEachEdge((_e, attrs, s, t) => {
    if (nodes.has(s) && nodes.has(t)) {
      const label = String(attrs.label || attrs.edgeType || '').trim();
      if (label) preds.add(label);
    }
  });
  return [...preds].slice(0, 6);
}

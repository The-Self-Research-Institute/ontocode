/**
 * Headless structural analysis over the graphology model — the field-validated
 * "task-first" insights: orphans (unconnected nodes), islands (disconnected
 * components), hubs (degree outliers that hairball the layout).
 */

import Graph from 'graphology';
import louvain from 'graphology-communities-louvain';

export interface GraphInsights {
  /** Nodes with no edges at all. */
  orphans: string[];
  /** Connected components other than the largest (singletons excluded — those are orphans). */
  islands: string[][];
  /** Degree outliers (>= max(10, mean + 3σ)). */
  hubs: string[];
  /** Louvain community per node id. */
  communities: Record<string, number>;
  communityCount: number;
}

export function analyzeGraph(graph: Graph): GraphInsights {
  const orphans: string[] = [];
  graph.forEachNode(id => {
    if (graph.degree(id) === 0) orphans.push(id);
  });

  const seen = new Set<string>();
  const components: string[][] = [];
  graph.forEachNode(start => {
    if (seen.has(start)) return;
    const component: string[] = [];
    const queue = [start];
    seen.add(start);
    while (queue.length) {
      const current = queue.pop()!;
      component.push(current);
      graph.forEachNeighbor(current, neighbor => {
        if (!seen.has(neighbor)) {
          seen.add(neighbor);
          queue.push(neighbor);
        }
      });
    }
    components.push(component);
  });
  components.sort((a, b) => b.length - a.length);
  const islands = components.slice(1).filter(c => c.length > 1);

  const degrees = graph.mapNodes(id => graph.degree(id));
  const n = degrees.length || 1;
  const mean = degrees.reduce((a, b) => a + b, 0) / n;
  const std = Math.sqrt(degrees.reduce((a, d) => a + (d - mean) ** 2, 0) / n);
  const threshold = Math.max(10, mean + 3 * std);
  const hubs = graph.filterNodes(id => graph.degree(id) >= threshold);

  // Louvain needs a simple undirected graph — collapse the directed multigraph
  let communities: Record<string, number> = {};
  let communityCount = 0;
  try {
    const undirected = new Graph({ type: 'undirected' });
    graph.forEachNode(id => undirected.addNode(id));
    graph.forEachEdge((_e, _a, source, target) => {
      if (source !== target && !undirected.hasEdge(source, target)) {
        undirected.addEdge(source, target);
      }
    });
    if (undirected.size > 0) {
      communities = louvain(undirected);
      communityCount = new Set(Object.values(communities)).size;
    }
  } catch { /* analysis overlay only — never fatal */ }

  return { orphans, islands, hubs, communities, communityCount };
}

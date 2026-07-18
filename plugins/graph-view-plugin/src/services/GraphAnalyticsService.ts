/**
 * Graph analytics for ontology visualization (OntoCode).
 * Pure functions — safe to call on filtered subgraphs.
 */

import type { OntologyNode, OntologyEdge } from '../types';

export interface DiscourseStructure {
  focusScore: number; // 0-100: % of intra-cluster edges
  label: 'focused' | 'balanced' | 'diversified';
  advice: string;
  clusterCount: number;
  avgClusterSize: number;
}

export interface GraphAnalytics {
  degree: Map<string, number>;
  betweenness: Map<string, number>;
  communities: Map<string, number>;
  communitySizes: Map<number, number>;
  topConcepts: Array<{ node: OntologyNode; score: number; degree: number }>;
  gaps: StructuralGap[];
  clusterColors: Map<number, string>;
  discourseStructure: DiscourseStructure;
}

export interface StructuralGap {
  clusterA: number;
  clusterB: number;
  labelA: string;
  labelB: string;
  bridgeScore: number;
  suggestion: string;
}

const CLUSTER_PALETTE = [
  '#3b82f6', '#10b981', '#f59e0b', '#8b5cf6', '#ec4899',
  '#06b6d4', '#ef4444', '#84cc16', '#f97316', '#6366f1',
  '#14b8a6', '#a855f7'
];

export function computeGraphAnalytics(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: { maxNodes?: number } = {}
): GraphAnalytics {
  const maxNodes = options.maxNodes ?? 2500;
  const nodeIds = new Set(nodes.map(n => n.id));
  const activeEdges = edges.filter(e => nodeIds.has(e.from) && nodeIds.has(e.to));

  const adjacency = buildUndirectedAdjacency(activeEdges);
  const degree = computeDegree(adjacency);
  const betweenness = nodes.length <= maxNodes
    ? brandesBetweenness(nodeIds, adjacency)
    : approximateBetweenness(degree);

  const communities = detectCommunitiesLabelPropagation(nodeIds, adjacency);
  const communitySizes = new Map<number, number>();
  for (const c of communities.values()) {
    communitySizes.set(c, (communitySizes.get(c) ?? 0) + 1);
  }

  const clusterColors = new Map<number, string>();
  for (const id of communitySizes.keys()) {
    clusterColors.set(id, CLUSTER_PALETTE[id % CLUSTER_PALETTE.length]);
  }

  const nodeById = new Map(nodes.map(n => [n.id, n]));
  const topConcepts = [...betweenness.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([id, score]) => ({
      node: nodeById.get(id)!,
      score,
      degree: degree.get(id) ?? 0
    }))
    .filter(x => x.node);

  const gaps = findStructuralGaps(communities, communitySizes, nodes, activeEdges, degree);
  const discourseStructure = computeDiscourseStructure(communities, activeEdges, communitySizes);

  return { degree, betweenness, communities, communitySizes, topConcepts, gaps, clusterColors, discourseStructure };
}

function buildUndirectedAdjacency(edges: OntologyEdge[]): Map<string, Set<string>> {
  const adj = new Map<string, Set<string>>();
  const link = (a: string, b: string) => {
    if (!adj.has(a)) adj.set(a, new Set());
    adj.get(a)!.add(b);
  };
  for (const e of edges) {
    link(e.from, e.to);
    link(e.to, e.from);
  }
  return adj;
}

function computeDegree(adjacency: Map<string, Set<string>>): Map<string, number> {
  const degree = new Map<string, number>();
  for (const [id, neighbors] of adjacency) {
    degree.set(id, neighbors.size);
  }
  return degree;
}

/** Brandes algorithm — O(VE), suitable for medium graphs. */
function brandesBetweenness(
  nodeIds: Set<string>,
  adjacency: Map<string, Set<string>>
): Map<string, number> {
  const betweenness = new Map<string, number>();
  for (const v of nodeIds) betweenness.set(v, 0);

  for (const s of nodeIds) {
    const stack: string[] = [];
    const pred = new Map<string, string[]>();
    const sigma = new Map<string, number>();
    const dist = new Map<string, number>();
    const delta = new Map<string, number>();

    for (const v of nodeIds) {
      pred.set(v, []);
      sigma.set(v, 0);
      dist.set(v, -1);
      delta.set(v, 0);
    }
    sigma.set(s, 1);
    dist.set(s, 0);

    const queue: string[] = [s];
    while (queue.length > 0) {
      const v = queue.shift()!;
      stack.push(v);
      for (const w of adjacency.get(v) ?? []) {
        if (dist.get(w)! < 0) {
          queue.push(w);
          dist.set(w, dist.get(v)! + 1);
        }
        if (dist.get(w) === dist.get(v)! + 1) {
          sigma.set(w, sigma.get(w)! + sigma.get(v)!);
          pred.get(w)!.push(v);
        }
      }
    }

    while (stack.length > 0) {
      const w = stack.pop()!;
      for (const v of pred.get(w) ?? []) {
        const coeff = (sigma.get(v)! / sigma.get(w)!) * (1 + delta.get(w)!);
        delta.set(v, delta.get(v)! + coeff);
      }
      if (w !== s) {
        betweenness.set(w, betweenness.get(w)! + delta.get(w)!);
      }
    }
  }

  // Undirected graph normalization
  for (const v of nodeIds) {
    betweenness.set(v, (betweenness.get(v) ?? 0) / 2);
  }
  return betweenness;
}

function approximateBetweenness(degree: Map<string, number>): Map<string, number> {
  const max = Math.max(1, ...degree.values());
  const result = new Map<string, number>();
  for (const [id, d] of degree) {
    result.set(id, d / max);
  }
  return result;
}

/** Lightweight community detection (label propagation). */
function detectCommunitiesLabelPropagation(
  nodeIds: Set<string>,
  adjacency: Map<string, Set<string>>,
  maxIterations = 12
): Map<string, number> {
  const labels = new Map<string, number>();
  let nextLabel = 0;
  for (const id of nodeIds) labels.set(id, nextLabel++);

  for (let iter = 0; iter < maxIterations; iter++) {
    let changed = false;
    const order = [...nodeIds].sort(() => Math.random() - 0.5);
    for (const nodeId of order) {
      const neighbors = adjacency.get(nodeId);
      if (!neighbors || neighbors.size === 0) continue;

      const freq = new Map<number, number>();
      for (const n of neighbors) {
        const label = labels.get(n)!;
        freq.set(label, (freq.get(label) ?? 0) + 1);
      }
      let bestLabel = labels.get(nodeId)!;
      let bestCount = -1;
      for (const [label, count] of freq) {
        if (count > bestCount) {
          bestCount = count;
          bestLabel = label;
        }
      }
      if (bestLabel !== labels.get(nodeId)) {
        labels.set(nodeId, bestLabel);
        changed = true;
      }
    }
    if (!changed) break;
  }

  // Renumber to 0..k-1 by size
  const sizeByLabel = new Map<number, number>();
  for (const label of labels.values()) {
    sizeByLabel.set(label, (sizeByLabel.get(label) ?? 0) + 1);
  }
  const sorted = [...sizeByLabel.entries()].sort((a, b) => b[1] - a[1]);
  const remap = new Map<number, number>();
  sorted.forEach(([old], i) => remap.set(old, i));
  const communities = new Map<string, number>();
  for (const [id, label] of labels) {
    communities.set(id, remap.get(label) ?? 0);
  }
  return communities;
}

function findStructuralGaps(
  communities: Map<string, number>,
  communitySizes: Map<number, number>,
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  degree: Map<string, number>
): StructuralGap[] {
  const crossEdges = new Map<string, number>();
  for (const e of edges) {
    const ca = communities.get(e.from);
    const cb = communities.get(e.to);
    if (ca === undefined || cb === undefined || ca === cb) continue;
    const key = ca < cb ? `${ca}|${cb}` : `${cb}|${ca}`;
    crossEdges.set(key, (crossEdges.get(key) ?? 0) + 1);
  }

  const nodeById = new Map(nodes.map(n => [n.id, n]));
  const topInCluster = (clusterId: number): string => {
    let best = '';
    let bestScore = -1;
    for (const [id, c] of communities) {
      if (c !== clusterId) continue;
      const score = degree.get(id) ?? 0;
      if (score > bestScore) {
        bestScore = score;
        best = nodeById.get(id)?.label || id.split(/[#/]/).pop() || id;
      }
    }
    return best;
  };

  const gaps: StructuralGap[] = [];
  const clusterIds = [...communitySizes.keys()].sort((a, b) => (communitySizes.get(b) ?? 0) - (communitySizes.get(a) ?? 0));

  for (let i = 0; i < clusterIds.length; i++) {
    for (let j = i + 1; j < clusterIds.length; j++) {
      const a = clusterIds[i];
      const b = clusterIds[j];
      const key = a < b ? `${a}|${b}` : `${b}|${a}`;
      const cross = crossEdges.get(key) ?? 0;
      if (cross > 0) continue;
      const sizeA = communitySizes.get(a) ?? 0;
      const sizeB = communitySizes.get(b) ?? 0;
      if (sizeA < 2 || sizeB < 2) continue;
      const labelA = topInCluster(a);
      const labelB = topInCluster(b);
      gaps.push({
        clusterA: a,
        clusterB: b,
        labelA,
        labelB,
        bridgeScore: sizeA * sizeB,
        suggestion: `Connect "${labelA}" and "${labelB}" — disconnected topic clusters`
      });
    }
  }

  return gaps.sort((x, y) => y.bridgeScore - x.bridgeScore).slice(0, 6);
}

function computeDiscourseStructure(
  communities: Map<string, number>,
  edges: OntologyEdge[],
  communitySizes: Map<number, number>
): DiscourseStructure {
  let intra = 0, total = 0;
  for (const e of edges) {
    const ca = communities.get(e.from);
    const cb = communities.get(e.to);
    if (ca === undefined || cb === undefined) continue;
    total++;
    if (ca === cb) intra++;
  }
  const focusScore = total === 0 ? 50 : Math.round((intra / total) * 100);
  const clusterCount = communitySizes.size;
  const totalNodes = [...communitySizes.values()].reduce((a, b) => a + b, 0);
  const avgClusterSize = clusterCount === 0 ? 0 : Math.round(totalNodes / clusterCount);
  const label: DiscourseStructure['label'] = focusScore >= 60 ? 'focused' : focusScore <= 35 ? 'diversified' : 'balanced';
  const advice = label === 'focused' ? 'diversify' : label === 'diversified' ? 'focus' : 'explore gaps';
  return { focusScore, label, advice, clusterCount, avgClusterSize };
}

export function getClusterColor(clusterId: number | undefined, palette: Map<number, string>): string | null {
  if (clusterId === undefined) return null;
  return palette.get(clusterId) ?? CLUSTER_PALETTE[clusterId % CLUSTER_PALETTE.length];
}

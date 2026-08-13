import type { OntologyNode, OntologyEdge } from '../types';
import { applyCircularLayout } from './CircularLayout';

export interface ClusterLayoutOptions {
  width: number;
  height: number;
  /** Community id per node id — e.g. computeGraphAnalytics(...).communities. */
  communities: Map<string, number>;
  padding?: number;
}

/**
 * Cluster Layout
 * Best for: seeing which classes group together structurally (communities), rather
 * than by explicit subClassOf hierarchy.
 * Gives each community its own cell in a macro grid, then arranges that community's
 * members in a circle within the cell (reusing applyCircularLayout per cell).
 */
export function applyClusterLayout(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: ClusterLayoutOptions
): Map<string, { x: number; y: number }> {
  const { width, height, communities, padding = 60 } = options;
  const positionMap = new Map<string, { x: number; y: number }>();
  if (nodes.length === 0) return positionMap;

  // Group nodes by community. A node missing from the map (shouldn't normally happen —
  // communities is computed over the same node set) gets its own singleton group rather
  // than being silently dropped.
  const groups = new Map<number, OntologyNode[]>();
  let nextUnclustered = -1;
  nodes.forEach(node => {
    const community = communities.has(node.id) ? communities.get(node.id)! : nextUnclustered--;
    if (!groups.has(community)) groups.set(community, []);
    groups.get(community)!.push(node);
  });

  const clusterIds = [...groups.keys()];
  const clusterCount = clusterIds.length;

  const availableWidth = Math.max(1, width - 2 * padding);
  const availableHeight = Math.max(1, height - 2 * padding);
  const cols = Math.max(1, Math.ceil(Math.sqrt(clusterCount * (availableWidth / availableHeight))));
  const rows = Math.max(1, Math.ceil(clusterCount / cols));
  const cellWidth = availableWidth / cols;
  const cellHeight = availableHeight / rows;

  clusterIds.forEach((clusterId, index) => {
    const members = groups.get(clusterId)!;
    const col = index % cols;
    const row = Math.floor(index / cols);
    const cellOriginX = padding + col * cellWidth;
    const cellOriginY = padding + row * cellHeight;

    const localPositions = applyCircularLayout(members, [], {
      width: cellWidth,
      height: cellHeight,
      radius: Math.min(cellWidth, cellHeight) * 0.38,
      sortBy: 'none'
    });
    localPositions.forEach((pos, nodeId) => {
      positionMap.set(nodeId, { x: cellOriginX + pos.x, y: cellOriginY + pos.y });
    });
  });

  return positionMap;
}

import type { OntologyNode, OntologyEdge } from '../types';

export interface GridLayoutOptions {
  width: number;
  height: number;
  padding?: number;
  nodeSize?: number;
}

/**
 * Grid Layout
 * Best for: Overview of all nodes without specific structure
 * Places nodes in a regular grid pattern
 */
export function applyGridLayout(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: GridLayoutOptions
): Map<string, { x: number; y: number }> {
  const {
    width,
    height,
    padding = 50,
    nodeSize = 100
  } = options;

  const positionMap = new Map<string, { x: number; y: number }>();
  const nodeCount = nodes.length;
  
  if (nodeCount === 0) return positionMap;

  const availableWidth = width - 2 * padding;
  const availableHeight = height - 2 * padding;
  
  const aspectRatio = availableWidth / availableHeight;
  const cols = Math.ceil(Math.sqrt(nodeCount * aspectRatio));
  const rows = Math.ceil(nodeCount / cols);
  
  const cellWidth = availableWidth / cols;
  const cellHeight = availableHeight / rows;

  nodes.forEach((node, index) => {
    const col = index % cols;
    const row = Math.floor(index / cols);
    
    positionMap.set(node.id, {
      x: padding + col * cellWidth + cellWidth / 2,
      y: padding + row * cellHeight + cellHeight / 2
    });
  });

  return positionMap;
}

import type { OntologyNode, OntologyEdge } from '../types';

export interface RadialLayoutOptions {
  width: number;
  height: number;
  rootNodeId?: string;
  levelDistance?: number;
}

export function applyRadialLayout(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: RadialLayoutOptions
): Map<string, { x: number; y: number }> {
  const {
    width,
    height,
    rootNodeId,
    levelDistance = Math.min(width, height) * 0.15
  } = options;

  const positionMap = new Map<string, { x: number; y: number }>();
  const centerX = width / 2;
  const centerY = height / 2;

  const adjacency = new Map<string, string[]>();
  edges.forEach(edge => {
    if (!adjacency.has(edge.from)) {
      adjacency.set(edge.from, []);
    }
    if (!adjacency.has(edge.to)) {
      adjacency.set(edge.to, []);
    }
    adjacency.get(edge.from)!.push(edge.to);
    adjacency.get(edge.to)!.push(edge.from); // Bidirectional for radial
  });

  let root: string;
  if (rootNodeId && nodes.find(n => n.id === rootNodeId)) {
    root = rootNodeId;
  } else {

    const classNodes = nodes.filter(n => n.type === 'class');
    root = classNodes.length > 0 ? classNodes[0].id : nodes[0]?.id;
  }

  if (!root) {
    return positionMap;
  }

  const levels = new Map<string, number>();
  const visited = new Set<string>();
  const queue: Array<{ nodeId: string; level: number }> = [{ nodeId: root, level: 0 }];

  while (queue.length > 0) {
    const { nodeId, level } = queue.shift()!;

    if (visited.has(nodeId)) continue;
    visited.add(nodeId);
    levels.set(nodeId, level);

    const neighbors = adjacency.get(nodeId) || [];
    neighbors.forEach(neighbor => {
      if (!visited.has(neighbor)) {
        queue.push({ nodeId: neighbor, level: level + 1 });
      }
    });
  }

  const nodesByLevel = new Map<number, string[]>();
  levels.forEach((level, nodeId) => {
    if (!nodesByLevel.has(level)) {
      nodesByLevel.set(level, []);
    }
    nodesByLevel.get(level)!.push(nodeId);
  });

  nodesByLevel.forEach((levelNodes, level) => {
    if (level === 0) {

      positionMap.set(levelNodes[0], { x: centerX, y: centerY });
    } else {

      const radius = level * levelDistance;
      const angleStep = (2 * Math.PI) / levelNodes.length;

      levelNodes.forEach((nodeId, i) => {
        const angle = i * angleStep;
        const x = centerX + radius * Math.cos(angle);
        const y = centerY + radius * Math.sin(angle);
        positionMap.set(nodeId, { x, y });
      });
    }
  });

  nodes.forEach(node => {
    if (!positionMap.has(node.id)) {
      const angle = Math.random() * 2 * Math.PI;
      const radius = Math.max(...Array.from(levels.values())) * levelDistance + levelDistance;
      const x = centerX + radius * Math.cos(angle);
      const y = centerY + radius * Math.sin(angle);
      positionMap.set(node.id, { x, y });
    }
  });

  return positionMap;
}

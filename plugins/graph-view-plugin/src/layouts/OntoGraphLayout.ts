import type { OntologyNode, OntologyEdge } from '../types';

export interface OntoGraphLayoutOptions {
  width: number;
  height: number;
  horizontalSpacing?: number;
  verticalSpacing?: number;
  centerX?: number;
  centerY?: number;
}

interface LayoutNode {
  node: OntologyNode;
  x: number;
  y: number;
  level: number;
  column: number;
}

export function applyOntoGraphLayout(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: OntoGraphLayoutOptions
): Map<string, { x: number; y: number }> {
  const {
    width,
    height,
    horizontalSpacing = 200, // Increased for left-to-right layout
    verticalSpacing = 80,    // Spacing between siblings
    centerX = width / 2,
    centerY = height / 2
  } = options;

  const positionMap = new Map<string, { x: number; y: number }>();

  const parentToChildren = new Map<string, Set<string>>();
  const childToParents = new Map<string, Set<string>>();

  edges.forEach(edge => {

    if (edge.type === 'subClassOf') {

      if (!parentToChildren.has(edge.to)) {
        parentToChildren.set(edge.to, new Set());
      }
      parentToChildren.get(edge.to)!.add(edge.from);

      if (!childToParents.has(edge.from)) {
        childToParents.set(edge.from, new Set());
      }
      childToParents.get(edge.from)!.add(edge.to);
    }
  });

  const rootNodes = nodes.filter(node => 
    !childToParents.has(node.id) || childToParents.get(node.id)!.size === 0
  );

  if (rootNodes.length === 0) {
    const nodesByChildren = nodes
      .map(node => ({
        node,
        childCount: (parentToChildren.get(node.id)?.size || 0)
      }))
      .sort((a, b) => b.childCount - a.childCount);

    if (nodesByChildren.length > 0) {
      rootNodes.push(nodesByChildren[0].node);
    } else {
      rootNodes.push(nodes[0]);
    }
  }

  const nodeLevels = new Map<string, number>();
  const visited = new Set<string>();
  const queue: Array<{ nodeId: string; level: number }> = [];

  rootNodes.forEach(root => {
    queue.push({ nodeId: root.id, level: 0 });
    nodeLevels.set(root.id, 0);
  });

  while (queue.length > 0) {
    const { nodeId, level } = queue.shift()!;

    if (visited.has(nodeId)) continue;
    visited.add(nodeId);

    const children = parentToChildren.get(nodeId);
    if (children) {
      children.forEach(childId => {
        if (!nodeLevels.has(childId) || nodeLevels.get(childId)! > level + 1) {
          nodeLevels.set(childId, level + 1);
          queue.push({ nodeId: childId, level: level + 1 });
        }
      });
    }
  }

  nodes.forEach(node => {
    if (!nodeLevels.has(node.id)) {
      nodeLevels.set(node.id, 0);
    }
  });

  const levelGroups = new Map<number, OntologyNode[]>();
  nodes.forEach(node => {
    const level = nodeLevels.get(node.id) || 0;
    if (!levelGroups.has(level)) {
      levelGroups.set(level, []);
    }
    levelGroups.get(level)!.push(node);
  });

  const maxLevel = Math.max(...Array.from(nodeLevels.values()));
  const startX = 100; // Start from left margin

  const levelHeights = new Map<number, number>();
  Array.from(levelGroups.entries()).forEach(([level, levelNodes]) => {
    const totalHeight = (levelNodes.length - 1) * verticalSpacing;
    levelHeights.set(level, totalHeight);
  });

  Array.from(levelGroups.entries())
    .sort(([a], [b]) => a - b)
    .forEach(([level, levelNodes]) => {
      const x = startX + level * horizontalSpacing;
      const totalHeight = (levelNodes.length - 1) * verticalSpacing;
      const startY = Math.max(80, (height - totalHeight) / 2);

      const sortedNodes = sortNodesByParent(levelNodes, childToParents, parentToChildren);

      sortedNodes.forEach((node, index) => {
        const y = startY + index * verticalSpacing;
        positionMap.set(node.id, { x, y });
      });
    });

  nodes.forEach(node => {
    if (!positionMap.has(node.id)) {

      const isolatedX = startX + (maxLevel + 1) * horizontalSpacing;
      const isolatedY = 100 + (positionMap.size % 5) * 80;
      positionMap.set(node.id, { x: isolatedX, y: isolatedY });
    }
  });

  return positionMap;
}

function sortNodesByParent(
  nodes: OntologyNode[],
  childToParents: Map<string, Set<string>>,
  parentToChildren: Map<string, Set<string>>
): OntologyNode[] {
  if (nodes.length <= 1) return nodes;

  const nodesByParent = new Map<string, OntologyNode[]>();
  const noParentNodes: OntologyNode[] = [];

  nodes.forEach(node => {
    const parents = childToParents.get(node.id);
    if (!parents || parents.size === 0) {
      noParentNodes.push(node);
    } else {

      const parentId = Array.from(parents)[0];
      if (!nodesByParent.has(parentId)) {
        nodesByParent.set(parentId, []);
      }
      nodesByParent.get(parentId)!.push(node);
    }
  });

  const sortedGroups: OntologyNode[][] = [];
  nodesByParent.forEach((groupNodes, parentId) => {
    const sorted = groupNodes.sort((a, b) => 
      (a.label || a.id).localeCompare(b.label || b.id)
    );
    sortedGroups.push(sorted);
  });

  const result = sortedGroups.flat();
  return result.concat(noParentNodes.sort((a, b) => 
    (a.label || a.id).localeCompare(b.label || b.id)
  ));
}

export function refineOntoGraphLayout(
  positionMap: Map<string, { x: number; y: number }>,
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  iterations: number = 50
): Map<string, { x: number; y: number }> {
  const refinedMap = new Map(positionMap);
  const nodeSpacing = 150;

  const isLarge = nodes.length > 10000;

  if (isLarge) {

    const gridSize = nodeSpacing * 2;

    for (let iter = 0; iter < Math.min(iterations, 10); iter++) { // Fewer iterations for large graphs
      const forces = new Map<string, { dx: number; dy: number }>();

      nodes.forEach(node => {
        forces.set(node.id, { dx: 0, dy: 0 });
      });

      const grid = new Map<string, OntologyNode[]>();
      nodes.forEach(node => {
        const pos = refinedMap.get(node.id)!;
        const gridX = Math.floor(pos.x / gridSize);
        const gridY = Math.floor(pos.y / gridSize);
        const key = `${gridX},${gridY}`;

        if (!grid.has(key)) {
          grid.set(key, []);
        }
        grid.get(key)!.push(node);
      });

      nodes.forEach(node1 => {
        const pos1 = refinedMap.get(node1.id)!;
        const gridX = Math.floor(pos1.x / gridSize);
        const gridY = Math.floor(pos1.y / gridSize);

        for (let dx = -1; dx <= 1; dx++) {
          for (let dy = -1; dy <= 1; dy++) {
            const key = `${gridX + dx},${gridY + dy}`;
            const cellNodes = grid.get(key);

            if (cellNodes) {
              cellNodes.forEach(node2 => {
                if (node1.id === node2.id) return;

                const pos2 = refinedMap.get(node2.id)!;
                const dx = pos2.x - pos1.x;
                const dy = pos2.y - pos1.y;
                const distance = Math.sqrt(dx * dx + dy * dy);

                if (distance < nodeSpacing && distance > 0) {
                  const force = (nodeSpacing - distance) / distance;
                  const fx = (dx / distance) * force * 0.05; // Reduced force for large graphs
                  const fy = (dy / distance) * force * 0.05;

                  const f1 = forces.get(node1.id)!;
                  f1.dx -= fx;
                  f1.dy -= fy;
                }
              });
            }
          }
        }
      });

      nodes.forEach(node => {
        const pos = refinedMap.get(node.id)!;
        const force = forces.get(node.id)!;

        refinedMap.set(node.id, {
          x: pos.x + force.dx,
          y: pos.y + force.dy
        });
      });
    }
  } else {

    for (let iter = 0; iter < iterations; iter++) {
      const forces = new Map<string, { dx: number; dy: number }>();

      nodes.forEach(node => {
        forces.set(node.id, { dx: 0, dy: 0 });
      });

      for (let i = 0; i < nodes.length; i++) {
        for (let j = i + 1; j < nodes.length; j++) {
          const node1 = nodes[i];
          const node2 = nodes[j];

          const pos1 = refinedMap.get(node1.id)!;
          const pos2 = refinedMap.get(node2.id)!;

          const dx = pos2.x - pos1.x;
          const dy = pos2.y - pos1.y;
          const distance = Math.sqrt(dx * dx + dy * dy);

          if (distance < nodeSpacing && distance > 0) {
            const force = (nodeSpacing - distance) / distance;
            const fx = (dx / distance) * force * 0.1;
            const fy = (dy / distance) * force * 0.1;

            const f1 = forces.get(node1.id)!;
            const f2 = forces.get(node2.id)!;

            f1.dx -= fx;
            f1.dy -= fy;
            f2.dx += fx;
            f2.dy += fy;
          }
        }
      }

      nodes.forEach(node => {
        const pos = refinedMap.get(node.id)!;
        const force = forces.get(node.id)!;

        refinedMap.set(node.id, {
          x: pos.x + force.dx,
          y: pos.y + force.dy
        });
      });
    }
  }

  return refinedMap;
}

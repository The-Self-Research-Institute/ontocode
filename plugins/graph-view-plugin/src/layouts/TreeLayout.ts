import type { OntologyNode, OntologyEdge } from '../types';

export interface TreeLayoutOptions {
  width: number;
  height: number;
  orientation?: 'vertical' | 'horizontal';
  nodeSpacing?: number;
  levelSpacing?: number;
}

export function applyTreeLayout(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: TreeLayoutOptions
): Map<string, { x: number; y: number }> {
  const {
    width,
    height,
    orientation = 'vertical',
    nodeSpacing = 170,
    levelSpacing = 260
  } = options;

  const positionMap = new Map<string, { x: number; y: number }>();

  const children = new Map<string, string[]>();
  const hasParent = new Set<string>();

  edges.forEach(edge => {
    if (edge.type === 'subClassOf' || edge.type === 'subPropertyOf') {

      if (!children.has(edge.to)) {
        children.set(edge.to, []);
      }
      children.get(edge.to)!.push(edge.from);
      hasParent.add(edge.from);
    }
  });

  const roots = nodes.filter(node => !hasParent.has(node.id));

  if (roots.length === 0) {

    return applyGridLayout(nodes, width, height);
  }

  interface TreeNode {
    id: string;
    children: TreeNode[];
  }

  const buildTree = (nodeId: string, visited = new Set<string>()): TreeNode | null => {
    if (visited.has(nodeId)) return null;
    visited.add(nodeId);

    const childIds = children.get(nodeId) || [];
    const childTrees = childIds
      .map(childId => buildTree(childId, visited))
      .filter((t): t is TreeNode => t !== null);

    return { id: nodeId, children: childTrees };
  };

  const nodeById = new Map(nodes.map(node => [node.id, node]));
  const sortedRoots = [...roots].sort((a, b) => (a.label || a.id).localeCompare(b.label || b.id));

  const trees = sortedRoots
    .map(root => buildTree(root.id))
    .filter((tree): tree is TreeNode => tree !== null);

  let leafCursor = 0;
  const treeGap = 2;

  const assignPositions = (node: TreeNode, depth: number): number => {
    const sortedChildren = [...node.children].sort((a, b) => {
      const aNode = nodeById.get(a.id);
      const bNode = nodeById.get(b.id);
      return (aNode?.label || a.id).localeCompare(bNode?.label || b.id);
    });

    let breadthIndex: number;
    if (sortedChildren.length === 0) {
      breadthIndex = leafCursor++;
    } else {
      const childBreadth = sortedChildren.map(child => assignPositions(child, depth + 1));
      breadthIndex = (Math.min(...childBreadth) + Math.max(...childBreadth)) / 2;
    }

    const margin = 120;
    if (orientation === 'vertical') {
      positionMap.set(node.id, {
        x: margin + breadthIndex * nodeSpacing,
        y: margin + depth * levelSpacing
      });
    } else {
      positionMap.set(node.id, {
        x: margin + depth * levelSpacing,
        y: margin + breadthIndex * nodeSpacing
      });
    }

    return breadthIndex;
  };

  trees.forEach(tree => {
    assignPositions(tree, 0);
    leafCursor += treeGap;
  });

  if (positionMap.size > 0) {
    const positions = Array.from(positionMap.values());
    const minX = Math.min(...positions.map(pos => pos.x));
    const maxX = Math.max(...positions.map(pos => pos.x));
    const minY = Math.min(...positions.map(pos => pos.y));
    const maxY = Math.max(...positions.map(pos => pos.y));
    const graphWidth = maxX - minX;
    const graphHeight = maxY - minY;
    const dx = Math.max(60, (width - graphWidth) / 2) - minX;
    const dy = Math.max(60, (height - graphHeight) / 2) - minY;

    positionMap.forEach((pos, id) => {
      positionMap.set(id, { x: pos.x + dx, y: pos.y + dy });
    });
  }

  let disconnectedIndex = 0;
  nodes.forEach(node => {
    if (!positionMap.has(node.id)) {
      if (orientation === 'vertical') {
        positionMap.set(node.id, {
          x: 120 + disconnectedIndex * nodeSpacing,
          y: height + 160
        });
      } else {
        positionMap.set(node.id, {
          x: width + 160,
          y: 120 + disconnectedIndex * nodeSpacing
        });
      }
      disconnectedIndex++;
    }
  });

  return positionMap;
}

function applyGridLayout(
  nodes: OntologyNode[],
  width: number,
  height: number
): Map<string, { x: number; y: number }> {
  const positionMap = new Map<string, { x: number; y: number }>();
  const cols = Math.ceil(Math.sqrt(nodes.length));
  const cellWidth = width / cols;
  const cellHeight = height / Math.ceil(nodes.length / cols);

  nodes.forEach((node, i) => {
    const col = i % cols;
    const row = Math.floor(i / cols);
    positionMap.set(node.id, {
      x: col * cellWidth + cellWidth / 2,
      y: row * cellHeight + cellHeight / 2
    });
  });

  return positionMap;
}

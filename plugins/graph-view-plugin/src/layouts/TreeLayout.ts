import type { OntologyNode, OntologyEdge } from '../types';

export interface TreeLayoutOptions {
  width: number;
  height: number;
  orientation?: 'vertical' | 'horizontal';
  nodeSpacing?: number;
  levelSpacing?: number;
}

/**
 * Tree Layout
 * Best for: Pure hierarchies (subClassOf, instanceOf)
 * Uses D3 tree algorithm for optimal spacing
 */
export function applyTreeLayout(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: TreeLayoutOptions
): Map<string, { x: number; y: number }> {
  const {
    width,
    height,
    orientation = 'vertical',
    nodeSpacing = 100,
    levelSpacing = 150
  } = options;

  const positionMap = new Map<string, { x: number; y: number }>();

  // Build parent-child relationships for tree structure
  const children = new Map<string, string[]>();
  const hasParent = new Set<string>();

  edges.forEach(edge => {
    if (edge.type === 'subClassOf' || edge.type === 'instanceOf' || edge.type === 'subPropertyOf') {
      // edge.from is child, edge.to is parent
      if (!children.has(edge.to)) {
        children.set(edge.to, []);
      }
      children.get(edge.to)!.push(edge.from);
      hasParent.add(edge.from);
    }
  });

  // Find root nodes
  const roots = nodes.filter(node => !hasParent.has(node.id));

  if (roots.length === 0) {
    // No tree structure, fallback to grid
    return applyGridLayout(nodes, width, height);
  }

  // Build tree structure recursively
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

  // Process each tree
  let offsetX = 0;
  const treePadding = 100;

  roots.forEach(root => {
    const tree = buildTree(root.id);
    if (!tree) return;

    // Calculate positions using simple tree layout
    const assignPositions = (node: TreeNode, depth: number, indexAtLevel: number, siblingsCount: number) => {
      let x: number, y: number;

      if (orientation === 'vertical') {
        // Vertical tree (top to bottom)
        const levelX = offsetX + (width / (roots.length + 1)) * (indexAtLevel + 1) / (siblingsCount + 1);
        x = levelX;
        y = 50 + depth * levelSpacing;
      } else {
        // Horizontal tree (left to right)
        x = 50 + depth * levelSpacing;
        const levelY = offsetX + (height / (roots.length + 1)) * (indexAtLevel + 1) / (siblingsCount + 1);
        y = levelY;
      }

      positionMap.set(node.id, { x, y });

      // Position children
      node.children.forEach((child, childIndex) => {
        assignPositions(child, depth + 1, childIndex, node.children.length);
      });
    };

    assignPositions(tree, 0, 0, roots.length);
    offsetX += treePadding;
  });

  // Position disconnected nodes
  nodes.forEach(node => {
    if (!positionMap.has(node.id)) {
      positionMap.set(node.id, {
        x: width - 100,
        y: height - 100 - Math.random() * 200
      });
    }
  });

  return positionMap;
}

/**
 * Grid layout fallback
 */
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

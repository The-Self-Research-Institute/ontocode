import type { OntologyNode, OntologyEdge } from '../types';

export interface TreeLayoutOptions {
  width: number;
  height: number;
  orientation?: 'vertical' | 'horizontal';
  nodeSpacing?: number;
  levelSpacing?: number;
}

const STACK_MIN_LEAVES = 4;
const STACK_COLUMNS = 2;
const WRAP_MIN_BRANCHES = 4;

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
    if (edge.type === 'subClassOf' || edge.type === 'subPropertyOf' || edge.type === 'instanceOf') {
      if (edge.from === edge.to) return;
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
  const horizontal = orientation === 'horizontal';
  const breadthSpacing = horizontal ? Math.min(nodeSpacing, 85) : nodeSpacing;
  const rowSpacing = horizontal ? 220 : Math.max(48, levelSpacing * 0.25);

  const margin = 120;
  let groupMaxY = -Infinity;
  const setPosition = (id: string, pos: { x: number; y: number }): void => {
    positionMap.set(id, pos);
    if (pos.y > groupMaxY) groupMaxY = pos.y;
  };

  const layoutInRows = (
    items: TreeNode[],
    depth: number,
    yOffset: number,
    perRow: number,
    gap: number
  ): number[] => {
    const breadths: number[] = [];
    const rowStart = leafCursor;
    let maxCursor = leafCursor;
    let rowOffset = yOffset;
    for (let i = 0; i < items.length; i += perRow) {
      leafCursor = rowStart;
      const outerMaxY = groupMaxY;
      groupMaxY = -Infinity;
      items.slice(i, i + perRow).forEach(item => {
        breadths.push(assignPositions(item, depth, rowOffset));
        leafCursor += gap;
      });
      const rowMaxY = groupMaxY;
      groupMaxY = Math.max(outerMaxY, rowMaxY);
      maxCursor = Math.max(maxCursor, leafCursor - gap);
      rowOffset = rowMaxY + levelSpacing * 0.6 - (margin + depth * levelSpacing);
    }
    leafCursor = maxCursor;
    return breadths;
  };

  const rowSize = (count: number): number => Math.max(4, Math.ceil(Math.sqrt(count) * 1.2));

  function assignPositions(node: TreeNode, depth: number, yOffset: number): number {
    const sortedChildren = [...node.children].sort((a, b) => {
      const aNode = nodeById.get(a.id);
      const bNode = nodeById.get(b.id);
      return (aNode?.label || a.id).localeCompare(bNode?.label || b.id);
    });

    const leafChildren = sortedChildren.filter(child => child.children.length === 0);
    const stackLeaves = leafChildren.length > STACK_MIN_LEAVES;

    let breadthIndex: number;
    if (sortedChildren.length === 0) {
      breadthIndex = leafCursor++;
    } else {
      const branchChildren = stackLeaves
        ? sortedChildren.filter(child => child.children.length > 0)
        : sortedChildren;
      const childBreadth: number[] = [];

      if (stackLeaves) {
        const columns = Math.max(STACK_COLUMNS, Math.ceil(Math.sqrt(leafChildren.length / 2)));
        const firstColumn = leafCursor;
        leafCursor += columns;
        leafChildren.forEach((leaf, index) => {
          const column = firstColumn + (index % columns);
          const row = Math.floor(index / columns);
          setPosition(leaf.id, {
            x: margin + column * breadthSpacing,
            y: margin + (depth + 1) * levelSpacing + yOffset + row * rowSpacing
          });
        });
        childBreadth.push(firstColumn, firstColumn + columns - 1);
      }

      if (branchChildren.length > WRAP_MIN_BRANCHES) {
        childBreadth.push(...layoutInRows(branchChildren, depth + 1, yOffset, rowSize(branchChildren.length), 0));
      } else {
        branchChildren.forEach(child => childBreadth.push(assignPositions(child, depth + 1, yOffset)));
      }

      breadthIndex = (Math.min(...childBreadth) + Math.max(...childBreadth)) / 2;
    }

    setPosition(node.id, {
      x: margin + breadthIndex * breadthSpacing,
      y: margin + depth * levelSpacing + yOffset
    });

    return breadthIndex;
  }

  const singleRoots = trees.filter(tree => tree.children.length === 0);
  const branchTrees = trees.filter(tree => tree.children.length > 0);

  if (branchTrees.length > WRAP_MIN_BRANCHES) {
    layoutInRows(branchTrees, 0, 0, rowSize(branchTrees.length), treeGap);
    leafCursor += treeGap;
  } else {
    branchTrees.forEach(tree => {
      assignPositions(tree, 0, 0);
      leafCursor += treeGap;
    });
  }

  if (singleRoots.length > 0) {
    const placedYs = Array.from(positionMap.values()).map(pos => pos.y);
    const gridTop = (placedYs.length > 0 ? Math.max(...placedYs) : margin) + levelSpacing;
    const columns = Math.max(4, Math.min(Math.max(leafCursor - treeGap, 4), Math.ceil(Math.sqrt(singleRoots.length * 3))));
    singleRoots.forEach((tree, index) => {
      positionMap.set(tree.id, {
        x: margin + (index % columns) * breadthSpacing,
        y: gridTop + Math.floor(index / columns) * rowSpacing
      });
    });
  }

  if (horizontal) {
    positionMap.forEach((pos, id) => {
      positionMap.set(id, { x: pos.y, y: pos.x });
    });
  }

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

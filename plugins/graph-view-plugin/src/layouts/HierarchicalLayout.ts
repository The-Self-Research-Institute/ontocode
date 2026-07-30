import * as d3 from 'd3';
import type { OntologyNode, OntologyEdge } from '../types';

interface D3HierNode extends OntologyNode, d3.SimulationNodeDatum {
  x?: number;
  y?: number;
  depth?: number;
  children?: D3HierNode[];
}

export interface HierarchicalLayoutOptions {
  width: number;
  height: number;
  nodeWidth?: number;
  nodeHeight?: number;
  levelSeparation?: number;
  siblingSeparation?: number;
  orientation?: 'vertical' | 'horizontal';
  /**
   * For multi-parent (DAG) hierarchies, repeat the child under each parent.
   * Default true (matches OntoCode hierarchy behavior).
   * Set false to assign the child to a single canonical parent (first asserted).
   */
  duplicateMultiParent?: boolean;
}

const HIERARCHY_TYPES: ReadonlySet<string> = new Set([
  'subClassOf',
  'instanceOf',
  'subPropertyOf'
]);

/**
 * Hierarchical Tree Layout (Sugiyama-style).
 * Best for class hierarchies and taxonomies.
 *
 * Multi-parent safe: by default a child appearing under N parents is rendered
 * under each parent (positions averaged for the canonical instance).
 * Cycle safe: a global visited set prevents infinite recursion.
 */
export function applyHierarchicalLayout(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: HierarchicalLayoutOptions
): Map<string, { x: number; y: number }> {
  const {
    width,
    height,
    levelSeparation = 100,
    siblingSeparation = 50,
    orientation = 'vertical',
    duplicateMultiParent = true
  } = options;

  const positionMap = new Map<string, { x: number; y: number }>();
  if (nodes.length === 0) return positionMap;

  // Build parent → children adjacency (multi-parent safe).
  const parentToChildren = new Map<string, string[]>();
  const childToParents = new Map<string, string[]>();
  const nodeById = new Map<string, OntologyNode>();
  for (const node of nodes) nodeById.set(node.id, node);

  for (const edge of edges) {
    if (!HIERARCHY_TYPES.has(edge.type as string)) continue;
    const child = edge.from;
    const parent = edge.to;
    if (!nodeById.has(child) || !nodeById.has(parent)) continue;
    if (child === parent) continue; // self-loop guard
    const c = parentToChildren.get(parent);
    if (!c) parentToChildren.set(parent, [child]);
    else if (!c.includes(child)) c.push(child);
    const p = childToParents.get(child);
    if (!p) childToParents.set(child, [parent]);
    else if (!p.includes(parent)) p.push(parent);
  }

  // Roots = nodes with no parent in the hierarchy index.
  const roots: OntologyNode[] = nodes.filter(node => {
    const parents = childToParents.get(node.id);
    return !parents || parents.length === 0;
  });

  if (roots.length === 0) {
    return applyGridLayout(nodes, width, height);
  }

  // Build forest. Cycle safe via visited set; multi-parent honored via duplicateMultiParent.
  const positionsAccumulator = new Map<string, { sumX: number; sumY: number; count: number }>();
  const recordPosition = (id: string, x: number, y: number): void => {
    const existing = positionsAccumulator.get(id);
    if (existing) {
      existing.sumX += x;
      existing.sumY += y;
      existing.count += 1;
    } else {
      positionsAccumulator.set(id, { sumX: x, sumY: y, count: 1 });
    }
  };

  const buildTree = (
    nodeId: string,
    depth: number,
    pathSet: Set<string>
  ): D3HierNode | null => {
    const node = nodeById.get(nodeId);
    if (!node) return null;
    if (pathSet.has(nodeId)) return null; // cycle break
    pathSet.add(nodeId);

    const treeNode: D3HierNode = { ...node, depth, children: [] };
    const childIds = parentToChildren.get(nodeId) ?? [];
    for (const childId of childIds) {
      // If duplicate disabled, only follow the first asserted parent.
      if (!duplicateMultiParent) {
        const parents = childToParents.get(childId);
        if (parents && parents[0] !== nodeId) continue;
      }
      const subtree = buildTree(childId, depth + 1, new Set(pathSet));
      if (subtree) treeNode.children!.push(subtree);
    }
    return treeNode;
  };

  const trees = roots
    .map(root => buildTree(root.id, 0, new Set<string>()))
    .filter((t): t is D3HierNode => t !== null);

  let currentX = siblingSeparation;
  for (const root of trees) {
    const treeLayout = d3.tree<D3HierNode>()
      .nodeSize([siblingSeparation, levelSeparation])
      .separation(() => 1);

    const hierarchy = d3.hierarchy(root, d => d.children);
    treeLayout(hierarchy);

    hierarchy.each(layoutNode => {
      if (!layoutNode.data) return;
      let x: number;
      let y: number;
      if (orientation === 'vertical') {
        x = currentX + (layoutNode.x ?? 0);
        y = 50 + (layoutNode.y ?? 0);
      } else {
        x = 50 + (layoutNode.y ?? 0);
        y = currentX + (layoutNode.x ?? 0);
      }
      recordPosition(layoutNode.data.id, x, y);
    });

    const treeWidth = Math.max(1, hierarchy.leaves().length) * siblingSeparation;
    currentX += treeWidth + 100;
  }

  // Average positions across multi-parent occurrences.
  for (const [id, agg] of positionsAccumulator) {
    positionMap.set(id, {
      x: agg.sumX / agg.count,
      y: agg.sumY / agg.count
    });
  }

  // Place orphan nodes that never appeared (e.g. islands) in a fallback grid below.
  const placedCount = positionMap.size;
  if (placedCount < nodes.length) {
    const orphans = nodes.filter(n => !positionMap.has(n.id));
    const baseY = orientation === 'vertical' ? height - 80 : 80;
    let cx = 50;
    for (const node of orphans) {
      positionMap.set(node.id, { x: cx, y: baseY });
      cx += siblingSeparation;
    }
  }

  return positionMap;
}

/** Simple grid fallback used when no hierarchy edges exist. */
function applyGridLayout(
  nodes: OntologyNode[],
  width: number,
  height: number
): Map<string, { x: number; y: number }> {
  const positionMap = new Map<string, { x: number; y: number }>();
  if (nodes.length === 0) return positionMap;

  const cols = Math.max(1, Math.ceil(Math.sqrt(nodes.length)));
  const rows = Math.max(1, Math.ceil(nodes.length / cols));
  const cellWidth = width / cols;
  const cellHeight = height / rows;

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

/**
 * ============================================================================
 * HIERARCHICAL LAZY LOADING MODULE
 * ============================================================================
 *
 * Production utilities for hierarchical navigation in the graph view.
 *
 * Correctness invariants enforced here:
 *   1. Multi-parent classes are preserved (a class may appear under every
 *      asserted parent).
 *   2. Cycles in the asserted hierarchy never recurse infinitely (defensive
 *      visited-sets on every traversal).
 *   3. Public API never returns duplicate IDs.
 *   4. All helpers are pure and side-effect free; no console noise in hot paths.
 */

import type { OntologyNode, OntologyEdge } from './types';

/** Edge types that participate in the asserted hierarchy. */
const HIERARCHY_EDGE_TYPES: ReadonlySet<string> = new Set([
  'subClassOf',
  'subPropertyOf',
  'instanceOf'
]);

const isHierarchyEdge = (edge: OntologyEdge): boolean =>
  HIERARCHY_EDGE_TYPES.has(edge.type as string);

/** True when the given node objects expose a `parent` field (precomputed by backend). */
const nodesHaveParentField = (nodes?: OntologyNode[]): boolean =>
  Array.isArray(nodes) && nodes.length > 0 && 'parent' in (nodes[0] as unknown as Record<string, unknown>);

const getParentField = (node: OntologyNode): string | string[] | null | undefined =>
  (node as unknown as { parent?: string | string[] | null }).parent;

/** Build a parent→children adjacency map once for fast repeated lookups. */
export interface HierarchyIndex {
  childrenOf: Map<string, string[]>;
  parentsOf: Map<string, string[]>;
}

export const buildHierarchyIndex = (
  nodes: OntologyNode[],
  edges: OntologyEdge[]
): HierarchyIndex => {
  const childrenOf = new Map<string, string[]>();
  const parentsOf = new Map<string, string[]>();

  const pushUnique = (map: Map<string, string[]>, key: string, value: string): void => {
    const list = map.get(key);
    if (!list) {
      map.set(key, [value]);
    } else if (!list.includes(value)) {
      list.push(value);
    }
  };

  if (nodesHaveParentField(nodes)) {
    for (const node of nodes) {
      const parent = getParentField(node);
      if (parent === null || parent === undefined || parent === '') continue;
      const parents = Array.isArray(parent) ? parent : [parent];
      for (const p of parents) {
        if (!p) continue;
        pushUnique(parentsOf, node.id, p);
        pushUnique(childrenOf, p, node.id);
      }
    }
  }

  // Always also index hierarchy edges — covers cases where backend sends both,
  // and where asserted edges contradict the precomputed parent field.
  for (const edge of edges) {
    if (!isHierarchyEdge(edge)) continue;
    pushUnique(parentsOf, edge.from, edge.to);
    pushUnique(childrenOf, edge.to, edge.from);
  }

  return { childrenOf, parentsOf };
};

/**
 * Find root nodes (nodes with no asserted parent in the hierarchy index).
 * Multi-parent safe; cycle safe.
 */
export const getRootNodes = (
  nodes: OntologyNode[],
  edges: OntologyEdge[]
): string[] => {
  if (nodes.length === 0) return [];
  const { parentsOf } = buildHierarchyIndex(nodes, edges);
  const roots: string[] = [];
  const seen = new Set<string>();
  for (const node of nodes) {
    if (seen.has(node.id)) continue;
    seen.add(node.id);
    const parents = parentsOf.get(node.id);
    if (!parents || parents.length === 0) {
      roots.push(node.id);
    }
  }
  return roots;
};

/**
 * Get immediate parents of a node (multi-parent safe).
 * For SUBCLASS_OF / subPropertyOf: child (from) → parent (to).
 * For instanceOf: individual (from) → class (to).
 */
export const getParents = (
  nodeId: string,
  edges: OntologyEdge[],
  nodes?: OntologyNode[]
): string[] => {
  if (nodesHaveParentField(nodes)) {
    const node = nodes!.find(n => n.id === nodeId);
    if (node) {
      const parent = getParentField(node);
      if (parent === null || parent === undefined || parent === '') return [];
      return Array.isArray(parent) ? Array.from(new Set(parent.filter(Boolean))) : [parent];
    }
  }

  const out = new Set<string>();
  for (const edge of edges) {
    if (
      edge.from === nodeId &&
      (edge.type === 'subClassOf' || edge.type === 'subPropertyOf')
    ) {
      out.add(edge.to);
    }
  }
  return Array.from(out);
};

/**
 * Get immediate children of a node. Includes sub-classes, sub-properties,
 * and instance-of relationships when the node is the parent class.
 */
export const getChildren = (
  nodeId: string,
  edges: OntologyEdge[],
  nodes?: OntologyNode[]
): string[] => {
  const out = new Set<string>();

  if (nodesHaveParentField(nodes)) {
    for (const node of nodes!) {
      const parent = getParentField(node);
      if (parent === undefined || parent === null) continue;
      if (Array.isArray(parent)) {
        if (parent.includes(nodeId)) out.add(node.id);
      } else if (parent === nodeId) {
        out.add(node.id);
      }
    }
  }

  for (const edge of edges) {
    if (edge.to === nodeId && isHierarchyEdge(edge)) {
      out.add(edge.from);
    }
  }
  return Array.from(out);
};

/**
 * Check if a node has any children (uses precomputed `hasChildren` field if present).
 */
export const hasChildren = (
  nodeId: string,
  edges: OntologyEdge[],
  nodes?: OntologyNode[]
): boolean => {
  if (Array.isArray(nodes) && nodes.length > 0 && 'hasChildren' in (nodes[0] as unknown as Record<string, unknown>)) {
    const node = nodes.find(n => n.id === nodeId);
    if (node && 'hasChildren' in node) {
      return (node as unknown as { hasChildren?: boolean }).hasChildren === true;
    }
  }

  if (nodesHaveParentField(nodes)) {
    for (const node of nodes!) {
      const parent = getParentField(node);
      if (Array.isArray(parent) ? parent.includes(nodeId) : parent === nodeId) {
        return true;
      }
    }
  }

  for (const edge of edges) {
    if (edge.to === nodeId && isHierarchyEdge(edge)) return true;
  }
  return false;
};

/**
 * Get all descendants of a node (BFS, cycle safe).
 * Only traverses through expanded nodes when expandedNodeIds is provided —
 * useful for "what would I hide if I collapse this branch?".
 */
export const getAllDescendants = (
  nodeId: string,
  edges: OntologyEdge[],
  expandedNodeIds: Set<string>,
  nodes?: OntologyNode[]
): string[] => {
  const descendants: string[] = [];
  const queue: string[] = [nodeId];
  const visited = new Set<string>([nodeId]);

  while (queue.length > 0) {
    const current = queue.shift() as string;
    const children = getChildren(current, edges, nodes);
    for (const child of children) {
      if (visited.has(child)) continue;
      visited.add(child);
      descendants.push(child);
      // Only descend further when this node was previously expanded.
      if (expandedNodeIds.has(child)) {
        queue.push(child);
      }
    }
  }

  return descendants;
};

/**
 * Find the shortest path from a root to the target node (cycle safe, BFS).
 * For multi-parent classes returns one valid path; ordered root → … → target.
 */
export const findPathToNode = (
  targetId: string,
  edges: OntologyEdge[],
  nodes?: OntologyNode[]
): string[] => {
  if (!targetId) return [];

  const { parentsOf } = buildHierarchyIndex(nodes ?? [], edges);

  // BFS upward from target; prev map reconstructs the path.
  const prev = new Map<string, string | null>();
  prev.set(targetId, null);
  const queue: string[] = [targetId];
  let root: string | null = null;

  while (queue.length > 0) {
    const current = queue.shift() as string;
    const parents = parentsOf.get(current);
    if (!parents || parents.length === 0) {
      root = current;
      break;
    }
    for (const parent of parents) {
      if (prev.has(parent)) continue;
      prev.set(parent, current);
      queue.push(parent);
    }
  }

  if (root === null) return [targetId];

  const path: string[] = [];
  let cursor: string | null = root;
  const guard = new Set<string>();
  while (cursor !== null && !guard.has(cursor)) {
    path.push(cursor);
    guard.add(cursor);
    cursor = prev.get(cursor) ?? null;
  }
  return path;
};

/**
 * Search nodes and return paths to all matching nodes.
 * Auto-expands the path so matches are visible in the tree.
 */
export const searchNodesWithPaths = (
  query: string,
  nodes: OntologyNode[],
  edges: OntologyEdge[]
): {
  matchingNodes: OntologyNode[];
  nodesToShow: Set<string>;
  nodesToExpand: Set<string>;
} => {
  if (!query) {
    return {
      matchingNodes: [],
      nodesToShow: new Set(getRootNodes(nodes, edges)),
      nodesToExpand: new Set()
    };
  }

  const queryLower = query.toLowerCase();
  const matchingNodes = nodes.filter(node =>
    (node.label?.toLowerCase().includes(queryLower)) ||
    node.id.toLowerCase().includes(queryLower) ||
    (node.description?.toLowerCase().includes(queryLower))
  );

  const nodesToShow = new Set<string>();
  const nodesToExpand = new Set<string>();

  for (const node of matchingNodes) {
    const path = findPathToNode(node.id, edges, nodes);
    for (const id of path) nodesToShow.add(id);
    // Expand all ancestors (everything except the match itself) so the match is reachable.
    for (let i = 0; i < path.length - 1; i++) nodesToExpand.add(path[i]);

    // Show immediate children for context.
    for (const child of getChildren(node.id, edges, nodes)) nodesToShow.add(child);
    nodesToExpand.add(node.id);
  }

  return { matchingNodes, nodesToShow, nodesToExpand };
};

/**
 * Toggle node expansion. Adds/removes the node's descendants from the visible set.
 */
export const toggleNodeExpansion = (
  nodeId: string,
  expandedNodeIds: Set<string>,
  visibleNodeIds: Set<string>,
  edges: OntologyEdge[],
  nodes?: OntologyNode[]
): {
  newExpandedIds: Set<string>;
  newVisibleIds: Set<string>;
  action: 'expanded' | 'collapsed';
} => {
  if (expandedNodeIds.has(nodeId)) {
    const toRemove = getAllDescendants(nodeId, edges, expandedNodeIds, nodes);
    const newVisibleIds = new Set(visibleNodeIds);
    // Only hide a descendant if it has no other visible parent (multi-parent safety).
    for (const id of toRemove) {
      const parents = getParents(id, edges, nodes);
      const hasOtherVisibleParent = parents.some(
        p => p !== nodeId && newVisibleIds.has(p) && expandedNodeIds.has(p)
      );
      if (!hasOtherVisibleParent) newVisibleIds.delete(id);
    }
    const newExpandedIds = new Set(expandedNodeIds);
    newExpandedIds.delete(nodeId);
    return { newExpandedIds, newVisibleIds, action: 'collapsed' };
  }

  const children = getChildren(nodeId, edges, nodes);
  const newVisibleIds = new Set(visibleNodeIds);
  for (const child of children) newVisibleIds.add(child);
  const newExpandedIds = new Set(expandedNodeIds);
  newExpandedIds.add(nodeId);
  return { newExpandedIds, newVisibleIds, action: 'expanded' };
};

/**
 * Expand every node in the graph (visible = all, expanded = all).
 */
export const expandAll = (
  nodes: OntologyNode[]
): {
  newExpandedIds: Set<string>;
  newVisibleIds: Set<string>;
} => {
  const allIds = nodes.map(n => n.id);
  return {
    newExpandedIds: new Set(allIds),
    newVisibleIds: new Set(allIds)
  };
};

/**
 * Ontologies at or below this size open fully expanded (OntoCode default for teaching files).
 * Kept low (not the ontology-size definition of "small") because full-expand renders every
 * class/property/individual as VOWL circles on a fixed-size canvas — past a few dozen nodes
 * that's a dense, illegible "hairball" rather than a readable teaching view. Above this cap,
 * initialGraphVisibility() shows roots + one level instead, same as the "large ontology" path.
 */
const SMALL_ONTOLOGY_NODE_CAP = 40;

/**
 * Target node count for initialGraphVisibility's first paint. Chosen to sit
 * comfortably under SMALL_ONTOLOGY_NODE_CAP so the curated view is visibly
 * lighter than "everything", while still being enough to look populated.
 */
const INITIAL_VISIBILITY_NODE_BUDGET = 35;

/**
 * Initial visibility for ontologies above SMALL_ONTOLOGY_NODE_CAP: breadth-first
 * expansion from the class hierarchy roots until INITIAL_VISIBILITY_NODE_BUDGET
 * is reached, instead of a fixed "one level" depth.
 *
 * A fixed one-level expansion assumes a bushy hierarchy (several roots, each
 * with several children) — for a narrow one (a single root/owl:Thing with one
 * direct child before it branches, common in real ontologies built around one
 * top concept) it revealed almost nothing (verified against a 82-node test
 * ontology: 1 root -> 1 child -> only 2 nodes visible). BFS-to-budget instead
 * keeps expanding deeper for narrow hierarchies and stays shallow for bushy
 * ones, landing near the same visible node count either way.
 */
export const initialGraphVisibility = (
  nodes: OntologyNode[],
  edges: OntologyEdge[]
): {
  newExpandedIds: Set<string>;
  newVisibleIds: Set<string>;
} => {
  const owlThingIri = 'http://www.w3.org/2002/07/owl#Thing';
  const classNodes = nodes.filter(n => n.type === 'class');
  const roots = getRootNodes(classNodes, edges);
  const visible = new Set<string>();
  const expanded = new Set<string>();

  // owl:Thing first (if present) so it — not an unrelated orphan root — gets
  // priority for the budget when there isn't room to expand everything.
  const orderedRoots = [
    ...(classNodes.some(n => n.id === owlThingIri) ? [owlThingIri] : []),
    ...roots.filter(r => r !== owlThingIri)
  ];

  let frontier = orderedRoots.slice();
  frontier.forEach(id => visible.add(id));

  while (frontier.length > 0 && visible.size < INITIAL_VISIBILITY_NODE_BUDGET) {
    const nextFrontier: string[] = [];
    for (const nodeId of frontier) {
      if (visible.size >= INITIAL_VISIBILITY_NODE_BUDGET) break;
      expanded.add(nodeId);
      for (const childId of getChildren(nodeId, edges, nodes)) {
        if (visible.has(childId) || visible.size >= INITIAL_VISIBILITY_NODE_BUDGET) continue;
        visible.add(childId);
        nextFrontier.push(childId);
      }
    }
    frontier = nextFrontier;
  }

  if (visible.size === 0 && classNodes.length > 0) {
    classNodes.slice(0, Math.min(12, classNodes.length)).forEach(n => visible.add(n.id));
  }

  return { newExpandedIds: expanded, newVisibleIds: visible };
};

/**
 * Small/medium ontologies: show the full class tree on first paint.
 * Large ontologies: lazy roots + one level to avoid heap spikes.
 */
export const smartInitialGraphVisibility = (
  nodes: OntologyNode[],
  edges: OntologyEdge[]
): {
  newExpandedIds: Set<string>;
  newVisibleIds: Set<string>;
} => {
  if (nodes.length > 0 && nodes.length <= SMALL_ONTOLOGY_NODE_CAP) {
    return expandAll(nodes);
  }
  return initialGraphVisibility(nodes, edges);
};

/**
 * Collapse every branch — show only roots for each entity type that has a hierarchy.
 */
export const collapseAll = (
  nodes: OntologyNode[],
  edges: OntologyEdge[]
): {
  newExpandedIds: Set<string>;
  newVisibleIds: Set<string>;
} => {
  const visibleIds: string[] = [];
  for (const type of ['class', 'objectProperty', 'dataProperty', 'individual'] as const) {
    const subset = nodes.filter(n => n.type === type);
    visibleIds.push(...getRootNodes(subset, edges));
  }
  return {
    newExpandedIds: new Set<string>(),
    newVisibleIds: new Set(visibleIds)
  };
};

/**
 * Build a one-line stats string for the toolbar.
 * Defensive against zero totals.
 */
export const getExpansionStats = (
  totalNodes: number,
  visibleNodes: number,
  expandedNodes: number
): string => {
  if (totalNodes === 0) return 'No nodes';
  const visiblePercent = Math.round((visibleNodes / totalNodes) * 100);
  return `Showing ${visibleNodes}/${totalNodes} nodes (${visiblePercent}%) · ${expandedNodes} expanded`;
};

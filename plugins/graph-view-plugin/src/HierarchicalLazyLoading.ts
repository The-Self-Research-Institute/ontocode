

import type { OntologyNode, OntologyEdge } from './types';

const HIERARCHY_EDGE_TYPES: ReadonlySet<string> = new Set([
  'subClassOf',
  'subPropertyOf'
]);

const INSTANCE_EDGE_TYPES: ReadonlySet<string> = new Set(['instanceOf']);

const isHierarchyEdge = (edge: OntologyEdge, includeInstances = false): boolean =>
  HIERARCHY_EDGE_TYPES.has(edge.type as string) ||
  (includeInstances && INSTANCE_EDGE_TYPES.has(edge.type as string));

const nodesHaveParentField = (nodes?: OntologyNode[]): boolean =>
  Array.isArray(nodes) && nodes.length > 0 && 'parent' in (nodes[0] as unknown as Record<string, unknown>);

const getParentField = (node: OntologyNode): string | string[] | null | undefined =>
  (node as unknown as { parent?: string | string[] | null }).parent;

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
      if (node.type === 'individual') continue;
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

  for (const edge of edges) {
    if (!isHierarchyEdge(edge)) continue;
    pushUnique(parentsOf, edge.from, edge.to);
    pushUnique(childrenOf, edge.to, edge.from);
  }

  return { childrenOf, parentsOf };
};

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

export const getChildren = (
  nodeId: string,
  edges: OntologyEdge[],
  nodes?: OntologyNode[],
  options?: { includeInstances?: boolean }
): string[] => {
  const includeInstances = options?.includeInstances === true;
  const out = new Set<string>();

  if (nodesHaveParentField(nodes)) {
    for (const node of nodes!) {

      if (!includeInstances && node.type === 'individual') continue;
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
    if (edge.to === nodeId && isHierarchyEdge(edge, includeInstances)) {
      out.add(edge.from);
    }
  }
  return Array.from(out);
};

export const buildChildrenParentsIndex = (
  nodes: OntologyNode[],
  edges: OntologyEdge[]
): Map<string, { children: string[]; parents: string[] }> => {
  const result = new Map<string, { children: string[]; parents: string[] }>();
  const ensure = (id: string) => {
    let entry = result.get(id);
    if (!entry) {
      entry = { children: [], parents: [] };
      result.set(id, entry);
    }
    return entry;
  };
  nodes.forEach(node => ensure(node.id));

  const hasParentField = nodesHaveParentField(nodes);

  const childSets = new Map<string, Set<string>>();
  const addChild = (parentId: string, childId: string) => {
    let set = childSets.get(parentId);
    if (!set) {
      set = new Set();
      childSets.set(parentId, set);
    }
    set.add(childId);
  };
  if (hasParentField) {
    for (const node of nodes) {
      if (node.type === 'individual') continue;
      const parent = getParentField(node);
      if (parent === undefined || parent === null) continue;
      const parents = Array.isArray(parent) ? parent : [parent];
      for (const p of parents) {
        if (p) addChild(p, node.id);
      }
    }
  }
  for (const edge of edges) {
    if (isHierarchyEdge(edge, false)) addChild(edge.to, edge.from);
  }
  childSets.forEach((set, parentId) => {
    ensure(parentId).children = Array.from(set);
  });

  if (hasParentField) {
    for (const node of nodes) {
      const parent = getParentField(node);
      ensure(node.id).parents =
        parent === null || parent === undefined || parent === ''
          ? []
          : Array.isArray(parent)
            ? Array.from(new Set(parent.filter(Boolean)))
            : [parent];
    }
  } else {
    const parentSets = new Map<string, Set<string>>();
    for (const edge of edges) {
      if (edge.type === 'subClassOf' || edge.type === 'subPropertyOf') {
        let set = parentSets.get(edge.from);
        if (!set) {
          set = new Set();
          parentSets.set(edge.from, set);
        }
        set.add(edge.to);
      }
    }
    parentSets.forEach((set, nodeId) => {
      ensure(nodeId).parents = Array.from(set);
    });
  }

  return result;
};

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

      if (expandedNodeIds.has(child)) {
        queue.push(child);
      }
    }
  }

  return descendants;
};

export const findPathToNode = (
  targetId: string,
  edges: OntologyEdge[],
  nodes?: OntologyNode[]
): string[] => {
  if (!targetId) return [];

  const { parentsOf } = buildHierarchyIndex(nodes ?? [], edges);

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

export type SearchVisibilityOptions = {

  includeAncestors?: boolean;

  childDepth?: number;
};

export const searchNodesWithPaths = (
  query: string,
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: SearchVisibilityOptions = {}
): {
  matchingNodes: OntologyNode[];
  nodesToShow: Set<string>;
  nodesToExpand: Set<string>;
} => {
  const includeAncestors = options.includeAncestors !== false;
  const childDepth = Math.max(0, options.childDepth ?? 0);

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

  const { childrenOf, parentsOf } = buildHierarchyIndex(nodes, edges);

  const pathToRoot = (targetId: string): string[] => {
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

    const path: string[] = [];
    let cursor: string | null = root ?? targetId;
    const guard = new Set<string>();
    while (cursor !== null && !guard.has(cursor)) {
      path.push(cursor);
      guard.add(cursor);
      cursor = prev.get(cursor) ?? null;
    }
    return path;
  };

  const addDescendantsToDepth = (startId: string, depth: number) => {
    if (depth <= 0) return;
    let frontier = [startId];
    nodesToExpand.add(startId);
    for (let d = 0; d < depth; d++) {
      const next: string[] = [];
      for (const id of frontier) {
        for (const child of childrenOf.get(id) ?? []) {
          if (!nodesToShow.has(child)) {
            nodesToShow.add(child);
            next.push(child);
          }
          nodesToExpand.add(id);
        }
      }
      frontier = next;
      if (frontier.length === 0) break;
    }
  };

  for (const node of matchingNodes) {
    nodesToShow.add(node.id);

    if (includeAncestors) {
      const path = pathToRoot(node.id);
      for (const id of path) nodesToShow.add(id);

      for (let i = 0; i < path.length - 1; i++) nodesToExpand.add(path[i]);
    }

    addDescendantsToDepth(node.id, childDepth);
  }

  return { matchingNodes, nodesToShow, nodesToExpand };
};

export const expandSeedsOneLevel = (
  seedIds: Iterable<string>,
  expandedNodeIds: Set<string>,
  visibleNodeIds: Set<string>,
  edges: OntologyEdge[],
  nodes?: OntologyNode[]
): { newExpandedIds: Set<string>; newVisibleIds: Set<string> } => {
  const newVisibleIds = new Set(visibleNodeIds);
  const newExpandedIds = new Set(expandedNodeIds);
  for (const id of seedIds) {
    const children = getChildren(id, edges, nodes);
    if (children.length === 0) continue;
    for (const child of children) newVisibleIds.add(child);
    newExpandedIds.add(id);
  }
  return { newExpandedIds, newVisibleIds };
};

export const collapseSeedsOneLevel = (
  seedIds: Iterable<string>,
  expandedNodeIds: Set<string>,
  visibleNodeIds: Set<string>,
  edges: OntologyEdge[],
  nodes?: OntologyNode[]
): { newExpandedIds: Set<string>; newVisibleIds: Set<string> } => {
  const seeds = Array.from(seedIds);
  if (seeds.length === 0) {
    return { newExpandedIds: new Set(expandedNodeIds), newVisibleIds: new Set(visibleNodeIds) };
  }

  const { childrenOf } = buildHierarchyIndex(nodes ?? [], edges);
  const depthOf = new Map<string, number>();
  const queue: string[] = [];
  for (const id of seeds) {
    if (!visibleNodeIds.has(id)) continue;
    depthOf.set(id, 0);
    queue.push(id);
  }

  let maxDepth = 0;
  while (queue.length > 0) {
    const id = queue.shift() as string;
    const depth = depthOf.get(id) ?? 0;
    if (depth > maxDepth) maxDepth = depth;
    if (!expandedNodeIds.has(id)) continue;
    for (const child of childrenOf.get(id) ?? []) {
      if (!visibleNodeIds.has(child) || depthOf.has(child)) continue;
      depthOf.set(child, depth + 1);
      queue.push(child);
    }
  }

  if (maxDepth === 0) {
    return { newExpandedIds: new Set(expandedNodeIds), newVisibleIds: new Set(visibleNodeIds) };
  }

  const newVisibleIds = new Set(visibleNodeIds);
  const newExpandedIds = new Set(expandedNodeIds);
  for (const [id, depth] of depthOf) {
    if (depth === maxDepth) {
      newVisibleIds.delete(id);
      newExpandedIds.delete(id);
    }
  }

  for (const [id, depth] of depthOf) {
    if (depth !== maxDepth - 1) continue;
    const kids = childrenOf.get(id) ?? [];
    const anyVisibleChild = kids.some((c) => newVisibleIds.has(c));
    if (!anyVisibleChild) newExpandedIds.delete(id);
  }

  return { newExpandedIds, newVisibleIds };
};

export const expandSeedsToDepth = (
  seedIds: Iterable<string>,
  depth: number,
  expandedNodeIds: Set<string>,
  visibleNodeIds: Set<string>,
  edges: OntologyEdge[],
  nodes?: OntologyNode[]
): { newExpandedIds: Set<string>; newVisibleIds: Set<string> } => {
  const newVisibleIds = new Set(visibleNodeIds);
  const newExpandedIds = new Set(expandedNodeIds);
  const { childrenOf } = buildHierarchyIndex(nodes ?? [], edges);

  let frontier = Array.from(seedIds);
  for (const id of frontier) newVisibleIds.add(id);

  const maxDepth = Math.max(0, depth);
  for (let d = 0; d < maxDepth; d++) {
    const next: string[] = [];
    for (const id of frontier) {
      const children = childrenOf.get(id) ?? getChildren(id, edges, nodes);
      if (children.length === 0) continue;
      newExpandedIds.add(id);
      for (const child of children) {
        if (!newVisibleIds.has(child)) {
          newVisibleIds.add(child);
          next.push(child);
        } else {
          next.push(child);
        }
      }
    }
    frontier = next;
    if (frontier.length === 0) break;
  }

  return { newExpandedIds, newVisibleIds };
};

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

const SMALL_ONTOLOGY_NODE_CAP = 40;

const INITIAL_VISIBILITY_NODE_BUDGET = 35;

export const NETWORK_VISIBILITY_NODE_BUDGET = 400;

export const expandToNodeBudget = (
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  budget: number
): {
  newExpandedIds: Set<string>;
  newVisibleIds: Set<string>;
} => {
  const cap = Math.max(1, budget);
  const owlThingIri = 'http://www.w3.org/2002/07/owl#Thing';
  const classNodes = nodes.filter(n => n.type === 'class');
  const roots = getRootNodes(classNodes, edges);
  const visible = new Set<string>();
  const expanded = new Set<string>();

  const orderedRoots = [
    ...(classNodes.some(n => n.id === owlThingIri) ? [owlThingIri] : []),
    ...roots.filter(r => r !== owlThingIri)
  ];

  let frontier = orderedRoots.slice();
  frontier.forEach(id => visible.add(id));

  while (frontier.length > 0 && visible.size < cap) {
    const nextFrontier: string[] = [];
    for (const nodeId of frontier) {
      if (visible.size >= cap) break;
      expanded.add(nodeId);
      for (const childId of getChildren(nodeId, edges, nodes)) {
        if (visible.has(childId) || visible.size >= cap) continue;
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

export const initialGraphVisibility = (
  nodes: OntologyNode[],
  edges: OntologyEdge[]
): {
  newExpandedIds: Set<string>;
  newVisibleIds: Set<string>;
} => expandToNodeBudget(nodes, edges, INITIAL_VISIBILITY_NODE_BUDGET);

export const networkGraphVisibility = (
  nodes: OntologyNode[],
  edges: OntologyEdge[]
): {
  newExpandedIds: Set<string>;
  newVisibleIds: Set<string>;
  capped: boolean;
} => {
  if (nodes.length > 0 && nodes.length <= NETWORK_VISIBILITY_NODE_BUDGET) {
    const full = expandAll(nodes);
    return { ...full, capped: false };
  }
  const partial = expandToNodeBudget(nodes, edges, NETWORK_VISIBILITY_NODE_BUDGET);
  return { ...partial, capped: true };
};

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
    const roots = getRootNodes(subset, edges);
    visibleIds.push(...roots);
  }
  return {
    newExpandedIds: new Set<string>(),
    newVisibleIds: new Set(visibleIds)
  };
};

export const getExpansionStats = (
  totalNodes: number,
  visibleNodes: number,
  expandedNodes: number
): string => {
  if (totalNodes === 0) return 'No nodes';
  const visiblePercent = Math.round((visibleNodes / totalNodes) * 100);
  return `Showing ${visibleNodes}/${totalNodes} nodes (${visiblePercent}%) · ${expandedNodes} expanded`;
};

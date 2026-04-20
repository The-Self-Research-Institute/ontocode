/**
 * ============================================================================
 * HIERARCHICAL LAZY LOADING MODULE
 * ============================================================================
 *
 * Utilities for hierarchical navigation with lazy loading in the graph view
 */

import type { OntologyNode, OntologyEdge } from './types';

/**
 * Find root nodes (nodes with no parent)
 * First tries to use node.parent field, falls back to edge analysis
 * Now supports all entity types: classes, objectProperty, dataProperty, individual
 */
export const getRootNodes = (
  nodes: OntologyNode[],
  edges: OntologyEdge[]
): string[] => {
  // Check if nodes have a 'parent' field
  const hasParentField = nodes.length > 0 && 'parent' in nodes[0];

  console.log(`[Hierarchy] Checking for parent field:`, hasParentField);
  if (hasParentField && nodes.length > 0) {
    console.log(`[Hierarchy] First node:`, nodes[0]);
  }

  if (hasParentField) {
    // Use parent field from nodes
    const rootIds = nodes
      .filter(node => {
        const parent = (node as any).parent;
        return parent === null || parent === undefined || parent === '';
      })
      .map(node => node.id);

    console.log(`[Hierarchy] Found ${rootIds.length} root nodes (using parent field) out of ${nodes.length} total`);
    console.log(`[Hierarchy] First 5 root nodes:`, rootIds.slice(0, 5).map(id =>
      nodes.find(n => n.id === id)?.label
    ));
    return rootIds;
  }

  // Fallback: Use edge analysis
  // For SUBCLASS_OF: child (from) → parent (to)
  // For subPropertyOf: child (from) → parent (to)
  // For instanceOf: individual (from) → class (to)
  // Root nodes are those that never appear as 'from' (i.e., they have no parents)
  const childIds = new Set(
    edges
      .filter(e => e.type === 'subClassOf' || e.type === 'subPropertyOf' || e.type === 'instanceOf')
      .map(e => e.from)  // Nodes that ARE children
  );

  const rootIds = nodes
    .filter(node => !childIds.has(node.id))  // Nodes that are NOT children = roots
    .map(node => node.id);

  console.log(`[Hierarchy] Found ${rootIds.length} root nodes (using edges) out of ${nodes.length} total`);
  console.log(`[Hierarchy] Root nodes:`, rootIds.map(id => nodes.find(n => n.id === id)?.label));
  return rootIds;
};

/**
 * Get immediate parents of a node
 * Uses edges to find parents (nodes that are the parent of this node)
 * For SUBCLASS_OF: child (from/source) → parent (to/target)
 * For subPropertyOf: child property (from/source) → parent property (to/target)
 * For instanceOf: individual (from/source) → class (to/target)
 */
export const getParents = (
  nodeId: string,
  edges: OntologyEdge[],
  nodes?: OntologyNode[]
): string[] => {
  // Use edges to find parents
  // For SUBCLASS_OF/subPropertyOf: if nodeId is 'from', then 'to' is the parent
  return edges
    .filter(edge => edge.from === nodeId && (edge.type === 'subClassOf' || edge.type === 'subPropertyOf'))
    .map(edge => edge.to);
};
/**
 * Get immediate children of a node
 * Uses edges to find children (nodes where this node is the parent)
 * For SUBCLASS_OF: child (from/source) → parent (to/target)
 * For subPropertyOf: child property (from/source) → parent property (to/target)
 * For instanceOf: individual (from/source) → class (to/target)
 */
export const getChildren = (
  nodeId: string,
  edges: OntologyEdge[],
  nodes?: OntologyNode[]
): string[] => {
  // If we have nodes with parent field, filter by parent
  if (nodes && nodes.length > 0 && 'parent' in nodes[0]) {
    return nodes
      .filter(node => (node as any).parent === nodeId)
      .map(node => node.id);
  }

  // Use edges to find children
  // For SUBCLASS_OF/subPropertyOf: if nodeId is 'to', then 'from' is the child
  // For instanceOf: if nodeId is a class (to), then 'from' are its individuals
  const children = edges
    .filter(edge => edge.to === nodeId && (edge.type === 'subClassOf' || edge.type === 'subPropertyOf' || edge.type === 'instanceOf'))
    .map(edge => edge.from);
    
  console.log(`[Hierarchy] getChildren for ${nodeId}: Found ${children.length} children`);
  return children;
};
/**
 * Check if node has children
 * Uses hasChildren field from node if available, otherwise checks edges
 * Now supports all entity types with hierarchy
 */
export const hasChildren = (
  nodeId: string,
  edges: OntologyEdge[],
  nodes?: OntologyNode[]
): boolean => {
  // If we have nodes with hasChildren field, use it
  if (nodes && nodes.length > 0 && 'hasChildren' in nodes[0]) {
    const node = nodes.find(n => n.id === nodeId);
    if (node && 'hasChildren' in node) {
      return (node as any).hasChildren === true;
    }
  }

  // Fallback: check if any node has this node as parent
  if (nodes && nodes.length > 0 && 'parent' in nodes[0]) {
    return nodes.some(node => (node as any).parent === nodeId);
  }

  // Final fallback: use edges
  // For SUBCLASS_OF: child (from) -> parent (to)
  // For subPropertyOf: child property (from) -> parent property (to)
  // For instanceOf: individual (from) -> class (to)
  // So a node has children if it appears as 'to' in these edge types
  return edges.some(edge => edge.to === nodeId && (edge.type === 'subClassOf' || edge.type === 'subPropertyOf' || edge.type === 'instanceOf'));
};

/**
 * Get all descendants of a node (recursively)
 */
export const getAllDescendants = (
  nodeId: string,
  edges: OntologyEdge[],
  expandedNodeIds: Set<string>,
  nodes?: OntologyNode[]
): string[] => {
  const descendants: string[] = [];
  const queue = [nodeId];
  const visited = new Set<string>();

  while (queue.length > 0) {
    const current = queue.shift()!;
    if (visited.has(current)) continue;
    visited.add(current);

    const children = getChildren(current, edges, nodes);

    children.forEach(child => {
      if (!descendants.includes(child)) {
        descendants.push(child);
        // Only traverse if this node was expanded
        if (expandedNodeIds.has(child)) {
          queue.push(child);
        }
      }
    });
  }

  return descendants;
};

/**
 * Find path from root to target node
 */
export const findPathToNode = (
  targetId: string,
  edges: OntologyEdge[],
  nodes?: OntologyNode[]
): string[] => {
  const path: string[] = [];
  let currentId = targetId;
  const visited = new Set<string>();

  // Traverse up the hierarchy using parent field if available
  if (nodes && nodes.length > 0 && 'parent' in nodes[0]) {
    while (currentId && !visited.has(currentId)) {
      path.unshift(currentId);
      visited.add(currentId);

      const currentNode = nodes.find(n => n.id === currentId);
      const parentId = currentNode ? (currentNode as any).parent : null;

      if (!parentId) break;
      currentId = parentId;
    }
    return path;
  }

  // Fallback: traverse using edges
  // subClassOf edges: Child (from) -> Parent (to)
  while (currentId && !visited.has(currentId)) {
    path.unshift(currentId);
    visited.add(currentId);

    // Find parent: edge where current node is the child (from) and parent is (to)
    const parentEdge = edges.find(e =>
      e.from === currentId && e.type === 'subClassOf'
    );

    if (!parentEdge) break;
    currentId = parentEdge.to;
  }

  return path;
};

/**
 * Search nodes and return paths to all matching nodes
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
    node.label.toLowerCase().includes(queryLower) ||
    node.id.toLowerCase().includes(queryLower) ||
    node.description?.toLowerCase().includes(queryLower)
  );

  const nodesToShow = new Set<string>();
  const nodesToExpand = new Set<string>();

  matchingNodes.forEach(node => {
    const path = findPathToNode(node.id, edges, nodes);

    console.log(`[Search] Path to "${node.label}":`, path.map(id =>
      nodes.find(n => n.id === id)?.label
    ).join(' → '));

    // Add all nodes in path
    path.forEach(nodeId => nodesToShow.add(nodeId));

    // Expand all nodes in path except the last one
    path.slice(0, -1).forEach(nodeId => nodesToExpand.add(nodeId));

    // Also add immediate children of the found node for context
    const children = getChildren(node.id, edges, nodes);
    children.forEach(child => nodesToShow.add(child));

    // Expand the found node to show its children
    nodesToExpand.add(node.id);
  });

  console.log(`[Search] "${query}": Found ${matchingNodes.length} matches, showing ${nodesToShow.size} nodes`);

  return { matchingNodes, nodesToShow, nodesToExpand };
};

/**
 * Toggle node expansion state
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
    // COLLAPSE: Remove descendants
    const toRemove = getAllDescendants(nodeId, edges, expandedNodeIds, nodes);
    const newVisibleIds = new Set(visibleNodeIds);
    toRemove.forEach(id => newVisibleIds.delete(id));

    const newExpandedIds = new Set(expandedNodeIds);
    newExpandedIds.delete(nodeId);

    console.log(`[Hierarchy] Collapsed "${nodeId}", removed ${toRemove.length} descendants`);

    return {
      newExpandedIds,
      newVisibleIds,
      action: 'collapsed'
    };
  } else {
    // EXPAND: Add immediate children only
    const children = getChildren(nodeId, edges, nodes);
    const newVisibleIds = new Set([...visibleNodeIds, ...children]);
    const newExpandedIds = new Set([...expandedNodeIds, nodeId]);

    console.log(`[Hierarchy] Expanded "${nodeId}", added ${children.length} children`);

    return {
      newExpandedIds,
      newVisibleIds,
      action: 'expanded'
    };
  }
};

/**
 * Expand all nodes in the graph
 */
export const expandAll = (
  nodes: OntologyNode[]
): {
  newExpandedIds: Set<string>;
  newVisibleIds: Set<string>;
} => {
  const allNodeIds = nodes.map(n => n.id);
  return {
    newExpandedIds: new Set(allNodeIds),
    newVisibleIds: new Set(allNodeIds)
  };
};
/**
 * Collapse all nodes to show only roots with first-level children
 * Now supports all entity types: classes, object properties, data properties, individuals
 */
export const collapseAll = (
  nodes: OntologyNode[],
  edges: OntologyEdge[]
): {
  newExpandedIds: Set<string>;
  newVisibleIds: Set<string>;
} => {
  console.log('[collapseAll] Collapsing to show only root entities (no children, no edges)');
  
  // Separate nodes by type
  const classNodes = nodes.filter(n => n.type === 'class');
  const objectPropertyNodes = nodes.filter(n => n.type === 'objectProperty');
  const dataPropertyNodes = nodes.filter(n => n.type === 'dataProperty');
  const individualNodes = nodes.filter(n => n.type === 'individual');
  
  // Get root entities for each type
  const classRootIds = getRootNodes(classNodes, edges);
  const objectPropertyRootIds = getRootNodes(objectPropertyNodes, edges);
  const dataPropertyRootIds = getRootNodes(dataPropertyNodes, edges);
  const individualRootIds = getRootNodes(individualNodes, edges);
  
  // Combine all root IDs — only roots, no children, no other types
  const visibleIds = [...classRootIds, ...objectPropertyRootIds, ...dataPropertyRootIds, ...individualRootIds];
  
  console.log('[collapseAll] ✅ Showing', visibleIds.length, 'root entities only:', {
    classRoots: classRootIds.length,
    objectPropertyRoots: objectPropertyRootIds.length,
    dataPropertyRoots: dataPropertyRootIds.length,
    individualRoots: individualRootIds.length
  });

  return {
    newExpandedIds: new Set<string>(), // Nothing expanded
    newVisibleIds: new Set(visibleIds)
  };
};

/**
 * Get expansion stats for UI display
 */
export const getExpansionStats = (
  totalNodes: number,
  visibleNodes: number,
  expandedNodes: number
): string => {
  const visiblePercent = Math.round((visibleNodes / totalNodes) * 100);
  return `Showing ${visibleNodes}/${totalNodes} nodes (${visiblePercent}%) · ${expandedNodes} expanded`;
};

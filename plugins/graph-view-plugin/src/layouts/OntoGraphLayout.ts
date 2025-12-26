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

/**
 * OntoGraph Layout - Protégé Style
 * Organized hierarchical layout similar to Protégé OntoGraf
 * Features:
 * - Root node on the left
 * - Children expand to the right in vertical columns
 * - Hierarchical levels organized horizontally (left to right)
 * - Siblings organized vertically at same level
 * - Clean, non-overlapping positioning
 */
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

  // Build relationship maps
  const parentToChildren = new Map<string, Set<string>>();
  const childToParents = new Map<string, Set<string>>();

  edges.forEach(edge => {
    // Track hierarchical relationships (subClassOf)
    if (edge.type === 'subClassOf') {
      // edge.from is child, edge.to is parent
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

  // Find root nodes (nodes with no parents in hierarchy)
  const rootNodes = nodes.filter(node => 
    !childToParents.has(node.id) || childToParents.get(node.id)!.size === 0
  );

  // If no clear roots, use most connected node as root
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

  // Assign levels to each node (BFS from roots) - levels go LEFT to RIGHT
  const nodeLevels = new Map<string, number>();
  const visited = new Set<string>();
  const queue: Array<{ nodeId: string; level: number }> = [];

  // Initialize with root nodes at level 0 (leftmost)
  rootNodes.forEach(root => {
    queue.push({ nodeId: root.id, level: 0 });
    nodeLevels.set(root.id, 0);
  });

  // BFS to assign levels (deeper levels go to the right)
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

  // Assign levels to any remaining unconnected nodes
  nodes.forEach(node => {
    if (!nodeLevels.has(node.id)) {
      nodeLevels.set(node.id, 0);
    }
  });

  // Group nodes by level (column in left-to-right layout)
  const levelGroups = new Map<number, OntologyNode[]>();
  nodes.forEach(node => {
    const level = nodeLevels.get(node.id) || 0;
    if (!levelGroups.has(level)) {
      levelGroups.set(level, []);
    }
    levelGroups.get(level)!.push(node);
  });

  // Calculate positions for each level (left to right)
  const maxLevel = Math.max(...Array.from(nodeLevels.values()));
  const startX = 100; // Start from left margin

  // First pass: calculate vertical positions for each level
  const levelHeights = new Map<number, number>();
  Array.from(levelGroups.entries()).forEach(([level, levelNodes]) => {
    const totalHeight = (levelNodes.length - 1) * verticalSpacing;
    levelHeights.set(level, totalHeight);
  });

  // Position nodes: X by level (left to right), Y by index within level (top to bottom)
  Array.from(levelGroups.entries())
    .sort(([a], [b]) => a - b)
    .forEach(([level, levelNodes]) => {
      const x = startX + level * horizontalSpacing;
      const totalHeight = (levelNodes.length - 1) * verticalSpacing;
      const startY = Math.max(80, (height - totalHeight) / 2);

      // Sort nodes within level by parent connections for better organization
      const sortedNodes = sortNodesByParent(levelNodes, childToParents, parentToChildren);

      sortedNodes.forEach((node, index) => {
        const y = startY + index * verticalSpacing;
        positionMap.set(node.id, { x, y });
      });
    });

  // Handle any nodes not yet positioned (isolated nodes)
  nodes.forEach(node => {
    if (!positionMap.has(node.id)) {
      // Place isolated nodes on the right side
      const isolatedX = startX + (maxLevel + 1) * horizontalSpacing;
      const isolatedY = 100 + (positionMap.size % 5) * 80;
      positionMap.set(node.id, { x: isolatedX, y: isolatedY });
    }
  });

  return positionMap;
}

/**
 * Sort nodes at the same level by their parent relationships
 * Nodes with same parent should be grouped together
 */
function sortNodesByParent(
  nodes: OntologyNode[],
  childToParents: Map<string, Set<string>>,
  parentToChildren: Map<string, Set<string>>
): OntologyNode[] {
  if (nodes.length <= 1) return nodes;

  // Group by parent
  const nodesByParent = new Map<string, OntologyNode[]>();
  const noParentNodes: OntologyNode[] = [];

  nodes.forEach(node => {
    const parents = childToParents.get(node.id);
    if (!parents || parents.size === 0) {
      noParentNodes.push(node);
    } else {
      // Use first parent for grouping
      const parentId = Array.from(parents)[0];
      if (!nodesByParent.has(parentId)) {
        nodesByParent.set(parentId, []);
      }
      nodesByParent.get(parentId)!.push(node);
    }
  });

  // Sort within each parent group alphabetically
  const sortedGroups: OntologyNode[][] = [];
  nodesByParent.forEach((groupNodes, parentId) => {
    const sorted = groupNodes.sort((a, b) => 
      (a.label || a.id).localeCompare(b.label || b.id)
    );
    sortedGroups.push(sorted);
  });

  // Combine: parent groups first, then orphans
  const result = sortedGroups.flat();
  return result.concat(noParentNodes.sort((a, b) => 
    (a.label || a.id).localeCompare(b.label || b.id)
  ));
}

/**
 * Apply force-based adjustments to reduce overlaps while maintaining structure
 * Optimized for large graphs using spatial indexing
 */
export function refineOntoGraphLayout(
  positionMap: Map<string, { x: number; y: number }>,
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  iterations: number = 50
): Map<string, { x: number; y: number }> {
  const refinedMap = new Map(positionMap);
  const nodeSpacing = 150;
  
  // For very large graphs (>10k nodes), use spatial indexing
  const isLarge = nodes.length > 10000;
  
  if (isLarge) {
    // Spatial hash grid optimization for O(n) instead of O(n²)
    const gridSize = nodeSpacing * 2;
    
    for (let iter = 0; iter < Math.min(iterations, 10); iter++) { // Fewer iterations for large graphs
      const forces = new Map<string, { dx: number; dy: number }>();
      
      // Initialize forces
      nodes.forEach(node => {
        forces.set(node.id, { dx: 0, dy: 0 });
      });
      
      // Build spatial grid
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
      
      // Only check nodes in same and adjacent grid cells
      nodes.forEach(node1 => {
        const pos1 = refinedMap.get(node1.id)!;
        const gridX = Math.floor(pos1.x / gridSize);
        const gridY = Math.floor(pos1.y / gridSize);
        
        // Check current and 8 adjacent cells
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
      
      // Apply forces
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
    // Original O(n²) algorithm for smaller graphs
    for (let iter = 0; iter < iterations; iter++) {
      const forces = new Map<string, { dx: number; dy: number }>();

      // Initialize forces
      nodes.forEach(node => {
        forces.set(node.id, { dx: 0, dy: 0 });
      });

      // Repulsion between nodes
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

      // Apply forces
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

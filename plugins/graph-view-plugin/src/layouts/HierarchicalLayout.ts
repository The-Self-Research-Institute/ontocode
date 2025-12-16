import * as d3 from 'd3';
import type { OntologyNode, OntologyEdge } from '../types';

interface D3Node extends OntologyNode, d3.SimulationNodeDatum {
  x?: number;
  y?: number;
  depth?: number;
  children?: D3Node[];
}

export interface HierarchicalLayoutOptions {
  width: number;
  height: number;
  nodeWidth?: number;
  nodeHeight?: number;
  levelSeparation?: number;
  siblingSeparation?: number;
  orientation?: 'vertical' | 'horizontal';
}

/**
 * Hierarchical Tree Layout (Sugiyama-style)
 * Best for: Class hierarchies, taxonomies
 */
export function applyHierarchicalLayout(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: HierarchicalLayoutOptions
): Map<string, { x: number; y: number }> {
  const {
    width,
    height,
    nodeWidth = 120,
    nodeHeight = 50,
    levelSeparation = 100,
    siblingSeparation = 50,
    orientation = 'vertical'
  } = options;

  const positionMap = new Map<string, { x: number; y: number }>();

  // Build adjacency map (child -> parent relationships for hierarchy)
  const childToParent = new Map<string, string>();
  const parentToChildren = new Map<string, string[]>();
  
  edges.forEach(edge => {
    // For hierarchical relationships (subClassOf, instanceOf, etc.)
    if (edge.type === 'subClassOf' || edge.type === 'instanceOf' || edge.type === 'subPropertyOf') {
      // edge.from is child, edge.to is parent
      childToParent.set(edge.from, edge.to);
      
      if (!parentToChildren.has(edge.to)) {
        parentToChildren.set(edge.to, []);
      }
      parentToChildren.get(edge.to)!.push(edge.from);
    }
  });

  // Find root nodes (nodes with no parents)
  const roots = nodes.filter(node => !childToParent.has(node.id));

  if (roots.length === 0) {
    // No hierarchy found, fallback to simple grid
    return applyGridLayout(nodes, width, height);
  }

  // Create hierarchy tree structure
  const buildTree = (nodeId: string, depth = 0): D3Node | null => {
    const node = nodes.find(n => n.id === nodeId);
    if (!node) return null;

    const treeNode: D3Node = { ...node, depth, children: [] };
    const children = parentToChildren.get(nodeId) || [];
    
    treeNode.children = children
      .map(childId => buildTree(childId, depth + 1))
      .filter((n): n is D3Node => n !== null);

    return treeNode;
  };

  // Build trees for each root
  const trees = roots.map(root => buildTree(root.id)).filter((t): t is D3Node => t !== null);

  // Apply D3 tree layout to each tree
  let currentX = siblingSeparation;

  trees.forEach(root => {
    const treeLayout = d3.tree<D3Node>()
      .nodeSize([siblingSeparation, levelSeparation])
      .separation(() => 1);

    const hierarchy = d3.hierarchy(root, d => d.children);
    treeLayout(hierarchy);

    // Extract positions from D3 layout
    hierarchy.each(node => {
      if (node.data) {
        let x: number, y: number;
        
        if (orientation === 'vertical') {
          x = currentX + (node.x || 0);
          y = 50 + (node.y || 0);
        } else {
          x = 50 + (node.y || 0);
          y = currentX + (node.x || 0);
        }

        positionMap.set(node.data.id, { x, y });
      }
    });

    // Update offset for next tree
    const treeWidth = hierarchy.leaves().length * siblingSeparation;
    currentX += treeWidth + 100;
  });

  return positionMap;
}

/**
 * Simple grid layout fallback
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

import type { OntologyNode, OntologyEdge } from '../types';

export interface MatrixLayoutOptions {
  width: number;
  height: number;
  cellSize?: number;
  padding?: number;
}

/**
 * Matrix/Adjacency Layout
 * Best for: Visualizing all relationships, finding patterns, dense graphs
 * Shows nodes on both axes with edges as cells
 */
export function prepareMatrixData(
  nodes: OntologyNode[],
  edges: OntologyEdge[]
): {
  nodes: OntologyNode[];
  matrix: Array<Array<OntologyEdge | null>>;
  nodeIndexMap: Map<string, number>;
} {
  // Sort nodes by type and name for better readability
  const sortedNodes = [...nodes].sort((a, b) => {
    if (a.type !== b.type) {
      return a.type.localeCompare(b.type);
    }
    return a.label.localeCompare(b.label);
  });

  // Create index map
  const nodeIndexMap = new Map<string, number>();
  sortedNodes.forEach((node, index) => {
    nodeIndexMap.set(node.id, index);
  });

  // Build adjacency matrix
  const size = sortedNodes.length;
  const matrix: Array<Array<OntologyEdge | null>> = Array(size)
    .fill(null)
    .map(() => Array(size).fill(null));

  edges.forEach(edge => {
    const fromIndex = nodeIndexMap.get(edge.from);
    const toIndex = nodeIndexMap.get(edge.to);
    
    if (fromIndex !== undefined && toIndex !== undefined) {
      // Store edge in matrix (can handle multiple edges by keeping the first or combining)
      if (!matrix[fromIndex][toIndex]) {
        matrix[fromIndex][toIndex] = edge;
      }
    }
  });

  return {
    nodes: sortedNodes,
    matrix,
    nodeIndexMap
  };
}

export interface MatrixVisualizationData {
  cells: Array<{
    x: number;
    y: number;
    sourceNode: OntologyNode;
    targetNode: OntologyNode;
    edge: OntologyEdge;
  }>;
  xLabels: Array<{ x: number; label: string; node: OntologyNode }>;
  yLabels: Array<{ y: number; label: string; node: OntologyNode }>;
  cellSize: number;
}

/**
 * Generate matrix visualization data
 */
export function generateMatrixVisualization(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: MatrixLayoutOptions
): MatrixVisualizationData {
  const {
    width,
    height,
    cellSize = 20,
    padding = 100
  } = options;

  const { nodes: sortedNodes, matrix } = prepareMatrixData(nodes, edges);

  const cells: MatrixVisualizationData['cells'] = [];
  const xLabels: MatrixVisualizationData['xLabels'] = [];
  const yLabels: MatrixVisualizationData['yLabels'] = [];

  // Generate cell positions
  sortedNodes.forEach((sourceNode, i) => {
    sortedNodes.forEach((targetNode, j) => {
      const edge = matrix[i][j];
      if (edge) {
        cells.push({
          x: padding + j * cellSize,
          y: padding + i * cellSize,
          sourceNode,
          targetNode,
          edge
        });
      }
    });
  });

  // Generate labels
  sortedNodes.forEach((node, i) => {
    xLabels.push({
      x: padding + i * cellSize + cellSize / 2,
      label: node.label,
      node
    });
    yLabels.push({
      y: padding + i * cellSize + cellSize / 2,
      label: node.label,
      node
    });
  });

  return {
    cells,
    xLabels,
    yLabels,
    cellSize
  };
}



import Graph from 'graphology';
import { OntologyNode, OntologyEdge } from '../types';
import { nodeAccent, nodeStroke } from '../utils/nodePalette';

export interface AdapterOptions {
  dark: boolean;
  positions?: Map<string, { x: number; y: number }>;
  nodeSize?: number;
}

export function buildGraphologyGraph(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: AdapterOptions
): Graph {
  const graph = new Graph({ multi: true, type: 'directed' });
  const baseSize = options.nodeSize ?? 8;

  nodes.forEach((node, index) => {
    if (graph.hasNode(node.id)) return;
    const saved = options.positions?.get(node.id);

    const angle = index * 2.399963;
    const radius = 12 * Math.sqrt(index);
    graph.addNode(node.id, {
      label: node.label || node.id,
      x: saved?.x ?? Math.cos(angle) * radius,
      y: saved?.y ?? Math.sin(angle) * radius,
      size: baseSize * (node.type === 'class' ? 1.25 : 1),
      color: nodeAccent(node.type),
      borderColor: nodeStroke(node.type, options.dark),
      nodeType: node.type
    });
  });

  edges.forEach(edge => {
    if (!graph.hasNode(edge.from) || !graph.hasNode(edge.to)) return;
    try {
      graph.addEdgeWithKey(edge.id, edge.from, edge.to, {
        label: edge.label || '',
        size: edge.type === 'subClassOf' ? 1.5 : 1,
        color: options.dark ? '#556274' : '#aab8cc',
        edgeType: edge.type
      });
    } catch {
      // Duplicate edge key — skip
    }
  });

  graph.forEachNode(id => {
    const degree = graph.degree(id);
    graph.setNodeAttribute(id, 'size', Math.min(18, 4 + Math.sqrt(degree) * 2.4));
  });

  return graph;
}

export function isWebGLAvailable(): boolean {
  try {
    const canvas = document.createElement('canvas');
    return Boolean(canvas.getContext('webgl2') || canvas.getContext('webgl'));
  } catch {
    return false;
  }
}

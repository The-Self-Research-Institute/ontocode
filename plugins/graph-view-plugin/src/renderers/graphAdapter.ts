/**
 * Adapter from the plugin's OntologyNode/OntologyEdge model to a graphology graph
 * for the Sigma.js WebGL renderer. Colors come from the canonical node palette so
 * the WebGL view matches the SVG renderer's identity encoding.
 */

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
    // Phyllotaxis seed when no saved position — keeps the initial frame sane
    // before the headless force layout takes over.
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

  // Degree-based sizing: hubs read instantly, leaves stay small
  graph.forEachNode(id => {
    const degree = graph.degree(id);
    graph.setNodeAttribute(id, 'size', Math.min(18, 4 + Math.sqrt(degree) * 2.4));
  });

  return graph;
}

/** True when the host can create a WebGL context (Electron GPU blocklist, remote desktop, etc.). */
export function isWebGLAvailable(): boolean {
  try {
    const canvas = document.createElement('canvas');
    return Boolean(canvas.getContext('webgl2') || canvas.getContext('webgl'));
  } catch {
    return false;
  }
}

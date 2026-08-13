import type { OntologyNode, OntologyEdge } from '../types';

export interface LayeredLayoutOptions {
  width: number;
  height: number;
  layerHeight?: number;
  nodeSpacing?: number;
  direction?: 'top-down' | 'bottom-up' | 'left-right' | 'right-left';
}

export function applyLayeredLayout(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: LayeredLayoutOptions
): Map<string, { x: number; y: number }> {
  const {
    width,
    height,
    layerHeight = 100,
    nodeSpacing = 80,
    direction = 'top-down'
  } = options;

  const positionMap = new Map<string, { x: number; y: number }>();

  const outgoing = new Map<string, string[]>();
  const incoming = new Map<string, number>();

  nodes.forEach(node => {
    outgoing.set(node.id, []);
    incoming.set(node.id, 0);
  });

  edges.forEach(edge => {

    if (outgoing.has(edge.from) && incoming.has(edge.to)) {
      outgoing.get(edge.from)!.push(edge.to);
      incoming.set(edge.to, (incoming.get(edge.to) || 0) + 1);
    }
  });

  const layers: string[][] = [];
  const assigned = new Set<string>();
  const incomingCopy = new Map(incoming);

  while (assigned.size < nodes.length) {

    const currentLayer: string[] = [];

    incomingCopy.forEach((count, nodeId) => {
      if (count === 0 && !assigned.has(nodeId)) {
        currentLayer.push(nodeId);
        assigned.add(nodeId);
      }
    });

    if (currentLayer.length === 0) {

      nodes.forEach(node => {
        if (!assigned.has(node.id)) {
          currentLayer.push(node.id);
          assigned.add(node.id);
        }
      });
    }

    if (currentLayer.length > 0) {
      layers.push(currentLayer);

      currentLayer.forEach(nodeId => {
        const targets = outgoing.get(nodeId) || [];
        targets.forEach(target => {
          const count = incomingCopy.get(target) || 0;
          incomingCopy.set(target, count - 1);
        });
      });
    }

    if (layers.length > nodes.length) break;
  }

  layers.forEach((layer, layerIndex) => {
    const layerNodeCount = layer.length;
    const layerWidth = layerNodeCount * nodeSpacing;
    const startX = (width - layerWidth) / 2;

    layer.forEach((nodeId, nodeIndex) => {
      let x: number, y: number;

      const layerPos = layerIndex * layerHeight + 50;
      const nodePos = startX + nodeIndex * nodeSpacing + nodeSpacing / 2;

      switch (direction) {
        case 'top-down':
          x = nodePos;
          y = layerPos;
          break;
        case 'bottom-up':
          x = nodePos;
          y = height - layerPos;
          break;
        case 'left-right':
          x = layerPos;
          y = nodePos;
          break;
        case 'right-left':
          x = width - layerPos;
          y = nodePos;
          break;
      }

      positionMap.set(nodeId, { x, y });
    });
  });

  return positionMap;
}

import type { OntologyNode, OntologyEdge } from '../types';

export interface CircularLayoutOptions {
  width: number;
  height: number;
  radius?: number;
  startAngle?: number;
  endAngle?: number;
  sortBy?: 'type' | 'name' | 'none';
}

export function applyCircularLayout(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: CircularLayoutOptions
): Map<string, { x: number; y: number }> {
  const {
    width,
    height,
    radius = Math.min(width, height) * 0.4,
    startAngle = 0,
    endAngle = 2 * Math.PI,
    sortBy = 'type'
  } = options;

  const positionMap = new Map<string, { x: number; y: number }>();
  const centerX = width / 2;
  const centerY = height / 2;

  let sortedNodes = [...nodes];
  if (sortBy === 'type') {
    const typeOrder = { class: 0, property: 1, objectProperty: 2, dataProperty: 3, individual: 4, annotation: 5, datatype: 6 };
    sortedNodes.sort((a, b) => (typeOrder[a.type] || 99) - (typeOrder[b.type] || 99));
  } else if (sortBy === 'name') {
    sortedNodes.sort((a, b) => a.label.localeCompare(b.label));
  }

  const angleRange = endAngle - startAngle;
  const angleStep = angleRange / Math.max(nodes.length, 1);

  sortedNodes.forEach((node, i) => {
    const angle = startAngle + (i * angleStep);
    const x = centerX + radius * Math.cos(angle);
    const y = centerY + radius * Math.sin(angle);
    positionMap.set(node.id, { x, y });
  });

  return positionMap;
}

export function applyMultiRingLayout(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: CircularLayoutOptions
): Map<string, { x: number; y: number }> {
  const { width, height } = options;
  const positionMap = new Map<string, { x: number; y: number }>();
  const centerX = width / 2;
  const centerY = height / 2;

  const nodesByType = new Map<string, OntologyNode[]>();
  nodes.forEach(node => {
    if (!nodesByType.has(node.type)) {
      nodesByType.set(node.type, []);
    }
    nodesByType.get(node.type)!.push(node);
  });

  const typeRings = {
    class: Math.min(width, height) * 0.35,
    property: Math.min(width, height) * 0.25,
    objectProperty: Math.min(width, height) * 0.25,
    dataProperty: Math.min(width, height) * 0.25,
    individual: Math.min(width, height) * 0.4,
    annotation: Math.min(width, height) * 0.15,
    datatype: Math.min(width, height) * 0.2
  };

  nodesByType.forEach((typeNodes, type) => {
    const radius = typeRings[type as keyof typeof typeRings] || Math.min(width, height) * 0.3;
    const angleStep = (2 * Math.PI) / Math.max(typeNodes.length, 1);

    typeNodes.forEach((node, i) => {
      const angle = i * angleStep;
      const x = centerX + radius * Math.cos(angle);
      const y = centerY + radius * Math.sin(angle);
      positionMap.set(node.id, { x, y });
    });
  });

  return positionMap;
}

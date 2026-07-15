/**
 * WebGL renderer spike — Sigma.js v3 over graphology, with the d3-force layout
 * running headless. Feature-flagged via settings.renderer === 'webgl'.
 *
 * In scope: render at 10k+ nodes, zoom/pan, hover neighbor-dim, click-select,
 * node drag, label density LOD (Sigma built-in), dark mode, ?bench=N synthetic mode.
 * Out of scope (stays on the SVG renderer): edit-on-canvas, VOWL notation shapes,
 * collaborative cursors, matrix view, export.
 */

import React, { useEffect, useMemo, useRef, useState } from 'react';
import Sigma from 'sigma';
import Graph from 'graphology';
import { OntologyNode, OntologyEdge } from '../types';
import { buildGraphologyGraph } from './graphAdapter';
import { useHeadlessForceLayout } from './useHeadlessForceLayout';

export interface WebGLGraphViewProps {
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  dark: boolean;
  nodeSize?: number;
  positions?: Map<string, { x: number; y: number }>;
  selectedNodeIds?: Set<string>;
  onNodeClick?: (nodeId: string) => void;
  onNodeRightClick?: (nodeId: string, event: { x: number; y: number }) => void;
  onNodeDoubleClick?: (nodeId: string) => void;
}

/** Synthetic graph for renderer benchmarks (?bench=N or the perf harness). */
export function buildBenchmarkData(count: number): { nodes: OntologyNode[]; edges: OntologyEdge[] } {
  const types = ['class', 'individual', 'datatype', 'objectProperty', 'dataProperty'] as const;
  const nodes: OntologyNode[] = [];
  const edges: OntologyEdge[] = [];
  for (let i = 0; i < count; i++) {
    nodes.push({
      id: `bench-${i}`,
      label: `Node ${i}`,
      type: types[i % types.length] as OntologyNode['type'],
      uri: `urn:bench:${i}`
    });
    if (i > 0) {
      // Tree backbone + occasional cross-links for realistic force topology
      const parent = Math.floor((i - 1) / 4);
      edges.push({ id: `bench-e-${i}`, from: `bench-${i}`, to: `bench-${parent}`, type: 'subClassOf', label: '' } as OntologyEdge);
      if (i % 7 === 0) {
        const other = Math.floor(i * 0.31); // deterministic cross-link for reproducible benchmarks
        edges.push({ id: `bench-x-${i}`, from: `bench-${i}`, to: `bench-${other}`, type: 'propertyRelation', label: 'rel' } as OntologyEdge);
      }
    }
  }
  return { nodes, edges };
}

export const WebGLGraphView: React.FC<WebGLGraphViewProps> = ({
  nodes,
  edges,
  dark,
  nodeSize,
  positions,
  selectedNodeIds,
  onNodeClick,
  onNodeRightClick,
  onNodeDoubleClick
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const sigmaRef = useRef<Sigma | null>(null);
  const [hoveredNode, setHoveredNode] = useState<string | null>(null);
  const hoveredNodeStateRef = useRef<string | null>(null);
  const hoveredNeighborsRef = useRef<Set<string>>(new Set());
  const draggedNodeRef = useRef<string | null>(null);

  const graph = useMemo<Graph>(
    () => buildGraphologyGraph(nodes, edges, { dark, positions, nodeSize }),
    [nodes, edges, dark, positions, nodeSize]
  );

  // Layout: run headless d3-force only when no saved positions were provided
  useHeadlessForceLayout(graph, !positions || positions.size === 0);

  useEffect(() => {
    if (!containerRef.current) return;

    const renderer = new Sigma(graph, containerRef.current, {
      allowInvalidContainer: true,
      renderEdgeLabels: false,
      labelDensity: 0.6,
      labelGridCellSize: 100,
      labelRenderedSizeThreshold: 7,
      labelColor: { color: dark ? '#e7e9f0' : '#1f2430' },
      defaultEdgeColor: dark ? '#4b5563' : '#cbd5e1',
      zIndex: true
    });
    sigmaRef.current = renderer;

    // Hover: dim everything outside the hovered node's neighborhood
    renderer.setSetting('nodeReducer', (node, data) => {
      const res: Record<string, unknown> = { ...data };
      const hovered = hoveredNodeStateRef.current;
      if (hovered && node !== hovered && !hoveredNeighborsRef.current.has(node)) {
        res.color = dark ? '#2a3341' : '#e5e7eb';
        res.label = '';
      }
      if (selectedNodeIds?.has(node)) {
        res.highlighted = true;
      }
      return res;
    });
    renderer.setSetting('edgeReducer', (edge, data) => {
      const res: Record<string, unknown> = { ...data };
      const hovered = hoveredNodeStateRef.current;
      if (hovered && !graph.hasExtremity(edge, hovered)) {
        res.hidden = true;
      }
      return res;
    });

    renderer.on('enterNode', ({ node }) => {
      hoveredNeighborsRef.current = new Set(graph.neighbors(node));
      setHoveredNode(node);
    });
    renderer.on('leaveNode', () => {
      hoveredNeighborsRef.current = new Set();
      setHoveredNode(null);
    });
    renderer.on('clickNode', ({ node }) => onNodeClick?.(node));
    renderer.on('doubleClickNode', ({ node, event }) => {
      (event as any).preventSigmaDefault?.();
      onNodeDoubleClick?.(node);
    });
    renderer.on('rightClickNode', ({ node, event }) => {
      (event.original as MouseEvent).preventDefault();
      onNodeRightClick?.(node, { x: (event.original as MouseEvent).clientX, y: (event.original as MouseEvent).clientY });
    });

    // Node drag — the documented Sigma pattern (downNode + mousemovebody)
    renderer.on('downNode', ({ node }) => {
      draggedNodeRef.current = node;
      graph.setNodeAttribute(node, 'highlighted', true);
    });
    renderer.getMouseCaptor().on('mousemovebody', (e) => {
      const node = draggedNodeRef.current;
      if (!node) return;
      const pos = renderer.viewportToGraph(e);
      graph.setNodeAttribute(node, 'x', pos.x);
      graph.setNodeAttribute(node, 'y', pos.y);
      e.preventSigmaDefault();
      e.original.preventDefault();
      e.original.stopPropagation();
    });
    const endDrag = () => {
      if (draggedNodeRef.current) {
        graph.removeNodeAttribute(draggedNodeRef.current, 'highlighted');
        draggedNodeRef.current = null;
      }
    };
    renderer.getMouseCaptor().on('mouseup', endDrag);

    return () => {
      renderer.kill();
      sigmaRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [graph, dark]);

  // Keep the reducers' view of hover state fresh without re-creating Sigma
  useEffect(() => {
    hoveredNodeStateRef.current = hoveredNode;
    sigmaRef.current?.refresh({ skipIndexation: true });
  }, [hoveredNode]);

  useEffect(() => {
    sigmaRef.current?.refresh({ skipIndexation: true });
  }, [selectedNodeIds]);

  return (
    <div
      ref={containerRef}
      data-testid="graph-webgl-canvas"
      style={{ width: '100%', height: '100%', minHeight: 400 }}
    />
  );
};

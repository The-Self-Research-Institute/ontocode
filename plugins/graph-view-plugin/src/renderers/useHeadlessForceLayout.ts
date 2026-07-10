/**
 * DOM-less d3-force layout that writes positions into a graphology graph.
 * Proves the "keep d3 as the layout source, let Sigma render" architecture;
 * the scale-up path for 50k+ nodes is graphology-layout-forceatlas2's worker.
 */

import { useEffect } from 'react';
import * as d3 from 'd3';
import type Graph from 'graphology';

interface SimNode extends d3.SimulationNodeDatum {
  id: string;
}

export function useHeadlessForceLayout(graph: Graph | null, enabled: boolean): void {
  useEffect(() => {
    if (!graph || !enabled || graph.order === 0) return;

    const simNodes: SimNode[] = graph.mapNodes((id, attrs) => ({ id, x: attrs.x, y: attrs.y }));
    const nodeIndex = new Map(simNodes.map(n => [n.id, n]));
    const simEdges = graph.mapEdges((_edge, _attrs, source, target) => ({ source, target }))
      .filter(e => nodeIndex.has(e.source as string) && nodeIndex.has(e.target as string));

    const large = simNodes.length > 2000;
    const simulation = d3.forceSimulation(simNodes)
      .force('link', d3.forceLink(simEdges).id((d: any) => d.id).distance(60).strength(0.4).iterations(large ? 1 : 2))
      .force('charge', d3.forceManyBody().strength(large ? -60 : -120).theta(0.9))
      .force('center', d3.forceCenter(0, 0).strength(0.05))
      .force('collision', d3.forceCollide().radius(14).iterations(large ? 1 : 2))
      .alphaDecay(large ? 0.06 : 0.03)
      .alphaMin(large ? 0.01 : 0.003);

    // Throttled write-back: positions flow into graphology (Sigma re-renders reactively)
    let frame: number | null = null;
    simulation.on('tick', () => {
      if (frame != null) return;
      frame = requestAnimationFrame(() => {
        frame = null;
        simNodes.forEach(n => {
          if (n.x != null && n.y != null && graph.hasNode(n.id)) {
            graph.mergeNodeAttributes(n.id, { x: n.x, y: n.y });
          }
        });
      });
    });

    return () => {
      simulation.stop();
      if (frame != null) cancelAnimationFrame(frame);
    };
  }, [graph, enabled]);
}

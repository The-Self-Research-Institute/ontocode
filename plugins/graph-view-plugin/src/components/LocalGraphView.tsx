/**
 * ============================================================================
 * LOCAL GRAPH VIEW
 * ============================================================================
 *
 * OntoCode focused neighborhood view of an ontology graph.
 *
 * Centers on a single "focus" node and renders its N-hop neighborhood with a
 * smooth force-directed layout (D3 v7). Designed for the right-pane / sidebar
 * companion view; the user keeps the main graph open and uses this to drill
 * into context without losing their place.
 *
 * Features:
 *   - Depth slider (1–5 hops)
 *   - "Follow selection" toggle so the focus snaps to whatever the user
 *     selects elsewhere in the application
 *   - Focus-fade hover (non-neighbors dim on node hover)
 *   - Node size scaled by degree
 *   - Color by entity type (matches the rest of the plugin)
 *   - Smooth zoom + pan
 *   - Pause / resume simulation
 *   - Cycle-safe BFS for neighborhood expansion
 *
 * The component is purely presentational and stateless w.r.t. the parent —
 * it never mutates the upstream nodes/edges arrays.
 */

import React, {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState
} from 'react';
import * as d3 from 'd3';
import {
  Crosshair,
  PauseCircle,
  PlayCircle,
  RotateCcw,
  Eye,
  EyeOff
} from 'lucide-react';
import type { OntologyNode, OntologyEdge, NodeType } from '../types';
import { useIsDarkTheme } from '../hooks/useIsDarkTheme';
import { nodeAccent } from '../utils/nodePalette';

interface LocalGraphViewProps {
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  /** Node id the local graph centers on. */
  focusNodeId: string | null;
  /** Fired when the user clicks a neighbor (allows the parent to retarget). */
  onSelect?: (node: OntologyNode) => void;
  /** Optional: emit when the user double-clicks (e.g. to bring node into main graph). */
  onActivate?: (node: OntologyNode) => void;
  /** Default neighborhood depth (1 hop). */
  initialDepth?: number;
  /** Maximum neighborhood depth allowed by the slider (default 5). */
  maxDepth?: number;
  /** Whether the toolbar is visible. Default true. */
  showToolbar?: boolean;
  /** Optional CSS height; defaults to 100% of the parent. */
  height?: number | string;
}

// Node color comes from the canonical NODE_ACCENTS palette (utils/nodePalette) via
// nodeAccent() — this used to be its own hardcoded set of colors, different from what the
// main graph view uses for the same entity types (e.g. individuals were green here, violet
// there), which looked inconsistent switching between the two views.

interface SimNode extends d3.SimulationNodeDatum {
  id: string;
  label: string;
  type: NodeType;
  depthFromFocus: number;
  degree: number;
}

interface SimLink extends d3.SimulationLinkDatum<SimNode> {
  id: string;
  type: string;
  source: string | SimNode;
  target: string | SimNode;
}

const NODE_RADIUS_BASE = 6;
const NODE_RADIUS_SCALE = 1.6;
const FOCUS_RADIUS_BONUS = 6;
const LABEL_FONT_SIZE = 11;

export const LocalGraphView: React.FC<LocalGraphViewProps> = ({
  nodes,
  edges,
  focusNodeId,
  onSelect,
  onActivate,
  initialDepth = 1,
  maxDepth = 5,
  showToolbar = true,
  height = '100%'
}) => {
  const svgRef = useRef<SVGSVGElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const simulationRef = useRef<d3.Simulation<SimNode, SimLink> | null>(null);
  const zoomRef = useRef<d3.ZoomBehavior<SVGSVGElement, unknown> | null>(null);

  const [depth, setDepth] = useState(Math.max(1, Math.min(maxDepth, initialDepth)));
  const [paused, setPaused] = useState(false);
  const [showLabels, setShowLabels] = useState(true);
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const [size, setSize] = useState({ width: 480, height: 320 });
  const isDark = useIsDarkTheme();

  // ----- Resize observer --------------------------------------------------
  useLayoutEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const update = (): void => {
      const rect = el.getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) {
        setSize({ width: rect.width, height: rect.height });
      }
    };
    update();
    const obs = new ResizeObserver(update);
    obs.observe(el);
    return () => obs.disconnect();
  }, []);

  // ----- Compute the focused subgraph ------------------------------------
  const subgraph = useMemo(() => {
    if (!focusNodeId) {
      return { nodes: [] as SimNode[], links: [] as SimLink[] };
    }

    const nodeMap = new Map<string, OntologyNode>();
    for (const n of nodes) nodeMap.set(n.id, n);
    if (!nodeMap.has(focusNodeId)) {
      return { nodes: [], links: [] };
    }

    // BFS through edges (undirected) up to `depth` hops.
    const adjacency = new Map<string, Array<{ neighbor: string; edge: OntologyEdge }>>();
    for (const edge of edges) {
      pushAdj(adjacency, edge.from, edge.to, edge);
      pushAdj(adjacency, edge.to, edge.from, edge);
    }

    const depthOf = new Map<string, number>([[focusNodeId, 0]]);
    const queue: string[] = [focusNodeId];
    while (queue.length > 0) {
      const cur = queue.shift() as string;
      const curDepth = depthOf.get(cur) ?? 0;
      if (curDepth >= depth) continue;
      const neighbors = adjacency.get(cur) ?? [];
      for (const { neighbor } of neighbors) {
        if (depthOf.has(neighbor)) continue;
        depthOf.set(neighbor, curDepth + 1);
        queue.push(neighbor);
      }
    }

    // Materialize nodes.
    const degree = new Map<string, number>();
    const includedEdges: OntologyEdge[] = [];
    for (const edge of edges) {
      if (depthOf.has(edge.from) && depthOf.has(edge.to)) {
        includedEdges.push(edge);
        degree.set(edge.from, (degree.get(edge.from) ?? 0) + 1);
        degree.set(edge.to, (degree.get(edge.to) ?? 0) + 1);
      }
    }

    const simNodes: SimNode[] = [];
    for (const [id, d] of depthOf) {
      const node = nodeMap.get(id);
      if (!node) continue;
      simNodes.push({
        id,
        label: node.label || (id.split('#').pop() || id),
        type: node.type,
        depthFromFocus: d,
        degree: degree.get(id) ?? 0
      });
    }

    const simLinks: SimLink[] = includedEdges.map(e => ({
      id: e.id,
      type: e.type,
      source: e.from,
      target: e.to
    }));

    return { nodes: simNodes, links: simLinks };
  }, [nodes, edges, focusNodeId, depth]);

  // ----- Build simulation -------------------------------------------------
  useEffect(() => {
    if (!svgRef.current) return;
    const svg = d3.select(svgRef.current);
    svg.selectAll('*').remove();

    const { width, height: h } = size;
    if (subgraph.nodes.length === 0) {
      simulationRef.current?.stop();
      simulationRef.current = null;
      return;
    }

    // Zoom container.
    const root = svg.append('g').attr('class', 'lg-root');

    // Setup zoom.
    const zoom = d3
      .zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.1, 8])
      .on('zoom', (event) => {
        root.attr('transform', event.transform.toString());
      });
    svg.call(zoom);
    zoomRef.current = zoom;

    const linkLayer = root.append('g').attr('class', 'lg-links');
    const nodeLayer = root.append('g').attr('class', 'lg-nodes');
    const labelLayer = root.append('g').attr('class', 'lg-labels');

    const linkSelection = linkLayer
      .selectAll<SVGLineElement, SimLink>('line')
      .data(subgraph.links, d => d.id)
      .enter()
      .append('line')
      .attr('stroke', isDark ? '#64748b' : '#94a3b8')
      .attr('stroke-opacity', 0.6)
      .attr('stroke-width', 1);

    const focusedRadius = (d: SimNode): number =>
      NODE_RADIUS_BASE +
      NODE_RADIUS_SCALE * Math.sqrt(d.degree) +
      (d.id === focusNodeId ? FOCUS_RADIUS_BONUS : 0);

    const nodeSelection = nodeLayer
      .selectAll<SVGCircleElement, SimNode>('circle')
      .data(subgraph.nodes, d => d.id)
      .enter()
      .append('circle')
      .attr('r', focusedRadius)
      .attr('fill', d => nodeAccent(d.type))
      .attr('stroke', d => (d.id === focusNodeId ? (isDark ? '#f1f5f9' : '#0f172a') : (isDark ? '#1e293b' : '#ffffff')))
      .attr('stroke-width', d => (d.id === focusNodeId ? 2.5 : 1))
      .attr('cursor', 'pointer')
      .on('mouseenter', (_event, d) => setHoveredId(d.id))
      .on('mouseleave', () => setHoveredId(null))
      .on('click', (_event, d) => {
        const original = nodes.find(n => n.id === d.id);
        if (original) onSelect?.(original);
      })
      .on('dblclick', (_event, d) => {
        const original = nodes.find(n => n.id === d.id);
        if (original) (onActivate ?? onSelect)?.(original);
      });

    nodeSelection.append('title').text(d => `${d.label}\n${d.id}`);

    const labelSelection = showLabels
      ? labelLayer
          .selectAll<SVGTextElement, SimNode>('text')
          .data(subgraph.nodes, d => d.id)
          .enter()
          .append('text')
          .attr('font-size', LABEL_FONT_SIZE)
          .attr('font-family', 'system-ui, sans-serif')
          .attr('fill', isDark ? '#f1f5f9' : '#0f172a')
          .attr('paint-order', 'stroke')
          .attr('stroke', isDark ? '#0f172a' : '#ffffff')
          .attr('stroke-width', 3)
          .attr('text-anchor', 'middle')
          .attr('dy', d => -focusedRadius(d) - 4)
          .text(d => d.label)
      : null;

    // Drag behavior.
    const drag = d3
      .drag<SVGCircleElement, SimNode>()
      .on('start', (event, d) => {
        if (!event.active) simulationRef.current?.alphaTarget(0.3).restart();
        d.fx = d.x;
        d.fy = d.y;
      })
      .on('drag', (event, d) => {
        d.fx = event.x;
        d.fy = event.y;
      })
      .on('end', (event, d) => {
        if (!event.active) simulationRef.current?.alphaTarget(0);
        d.fx = null;
        d.fy = null;
      });
    nodeSelection.call(drag);

    // Force simulation tuned for small/medium subgraphs.
    const simulation = d3
      .forceSimulation<SimNode>(subgraph.nodes)
      .force(
        'link',
        d3.forceLink<SimNode, SimLink>(subgraph.links)
          .id(d => d.id)
          .distance(d => 50 + 30 * Math.max(1, edgeWeight(d)))
          .strength(0.6)
      )
      .force('charge', d3.forceManyBody<SimNode>().strength(d => -90 - 18 * d.degree))
      .force('center', d3.forceCenter(width / 2, h / 2).strength(0.05))
      .force(
        'collide',
        d3.forceCollide<SimNode>().radius(d => focusedRadius(d) + 4).strength(0.9)
      )
      .force('focus', focusForce(focusNodeId, width, h))
      .alpha(1)
      .alphaDecay(0.045);

    simulationRef.current = simulation;
    if (paused) simulation.stop();

    simulation.on('tick', () => {
      linkSelection
        .attr('x1', d => (d.source as SimNode).x ?? 0)
        .attr('y1', d => (d.source as SimNode).y ?? 0)
        .attr('x2', d => (d.target as SimNode).x ?? 0)
        .attr('y2', d => (d.target as SimNode).y ?? 0);
      nodeSelection.attr('cx', d => d.x ?? 0).attr('cy', d => d.y ?? 0);
      labelSelection?.attr('x', d => d.x ?? 0).attr('y', d => d.y ?? 0);
    });

    return () => {
      simulation.stop();
      simulationRef.current = null;
    };
  }, [subgraph, size, focusNodeId, nodes, onSelect, onActivate, paused, showLabels, isDark]);

  // ----- Update hover highlighting without rebuilding the simulation -----
  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return;
    const sel = d3.select(svg);
    if (!hoveredId) {
      sel.selectAll<SVGCircleElement, SimNode>('.lg-nodes circle').attr('opacity', 1);
      sel.selectAll<SVGTextElement, SimNode>('.lg-labels text').attr('opacity', 1);
      sel.selectAll<SVGLineElement, SimLink>('.lg-links line').attr('stroke-opacity', 0.6);
      return;
    }
    const neighbors = new Set<string>([hoveredId]);
    for (const link of subgraph.links) {
      const sourceId = typeof link.source === 'string' ? link.source : link.source.id;
      const targetId = typeof link.target === 'string' ? link.target : link.target.id;
      if (sourceId === hoveredId) neighbors.add(targetId);
      if (targetId === hoveredId) neighbors.add(sourceId);
    }

    sel.selectAll<SVGCircleElement, SimNode>('.lg-nodes circle')
      .attr('opacity', d => (neighbors.has(d.id) ? 1 : 0.15));
    sel.selectAll<SVGTextElement, SimNode>('.lg-labels text')
      .attr('opacity', d => (neighbors.has(d.id) ? 1 : 0.15));
    sel.selectAll<SVGLineElement, SimLink>('.lg-links line')
      .attr('stroke-opacity', (d) => {
        const sourceId = typeof d.source === 'string' ? d.source : d.source.id;
        const targetId = typeof d.target === 'string' ? d.target : d.target.id;
        return sourceId === hoveredId || targetId === hoveredId ? 0.95 : 0.08;
      });
  }, [hoveredId, subgraph.links]);

  // ----- Pause / resume external control ---------------------------------
  useEffect(() => {
    if (!simulationRef.current) return;
    if (paused) simulationRef.current.stop();
    else simulationRef.current.alpha(0.4).restart();
  }, [paused]);

  // ----- Reset & fit ------------------------------------------------------
  const handleReset = useCallback(() => {
    const sim = simulationRef.current;
    if (!sim) return;
    for (const node of sim.nodes()) {
      node.fx = null;
      node.fy = null;
    }
    sim.alpha(1).restart();
    if (svgRef.current && zoomRef.current) {
      d3.select(svgRef.current).transition().duration(300).call(zoomRef.current.transform, d3.zoomIdentity);
    }
  }, []);

  // ----- Render -----------------------------------------------------------
  return (
    <div
      ref={containerRef}
      data-testid="local-graph-view"
      style={{
        position: 'relative',
        width: '100%',
        height,
        backgroundColor: 'var(--bg)',
        border: '1px solid var(--border)',
        borderRadius: 6,
        overflow: 'hidden',
        display: 'flex',
        flexDirection: 'column'
      }}
    >
      {showToolbar && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '6px 10px',
            borderBottom: '1px solid var(--border)',
            backgroundColor: 'var(--surface-1)',
            fontSize: 12,
            color: 'var(--text-primary)',
            flexShrink: 0
          }}
        >
          <Crosshair size={14} />
          <span style={{ fontWeight: 600 }}>Local graph</span>
          <span style={{ color: 'var(--text-secondary)', fontSize: 11 }}>OntoCode</span>
          <span style={{ color: 'var(--text-secondary)' }}>·</span>
          <label style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            depth
            <input
              type="range"
              min={1}
              max={maxDepth}
              value={depth}
              onChange={(e) => setDepth(Number.parseInt(e.target.value, 10))}
              style={{ width: 80 }}
            />
            <span style={{ width: 14, textAlign: 'right' }}>{depth}</span>
          </label>
          <div style={{ flex: 1 }} />
          <button
            type="button"
            onClick={() => setShowLabels(prev => !prev)}
            title={showLabels ? 'Hide labels' : 'Show labels'}
            style={iconBtn}
          >
            {showLabels ? <Eye size={14} /> : <EyeOff size={14} />}
          </button>
          <button
            type="button"
            onClick={() => setPaused(prev => !prev)}
            title={paused ? 'Resume layout' : 'Pause layout'}
            style={iconBtn}
          >
            {paused ? <PlayCircle size={14} /> : <PauseCircle size={14} />}
          </button>
          <button
            type="button"
            onClick={handleReset}
            title="Reset layout & zoom"
            style={iconBtn}
          >
            <RotateCcw size={14} />
          </button>
        </div>
      )}
      <div style={{ flex: 1, position: 'relative' }}>
        {!focusNodeId || subgraph.nodes.length === 0 ? (
          <div
            style={{
              position: 'absolute',
              inset: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'var(--text-secondary)',
              fontSize: 12,
              padding: 16,
              textAlign: 'center'
            }}
          >
            {focusNodeId
              ? 'No connected entities within this depth.'
              : 'Select a node to explore its neighborhood.'}
          </div>
        ) : (
          <svg
            ref={svgRef}
            data-testid="local-graph-svg"
            width={size.width}
            height={size.height}
            style={{ display: 'block', width: '100%', height: '100%' }}
            role="img"
            aria-label="Local graph view"
          />
        )}
      </div>
    </div>
  );
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function pushAdj(
  map: Map<string, Array<{ neighbor: string; edge: OntologyEdge }>>,
  from: string,
  to: string,
  edge: OntologyEdge
): void {
  const list = map.get(from);
  if (list) list.push({ neighbor: to, edge });
  else map.set(from, [{ neighbor: to, edge }]);
}

/** Cheap weight per edge type; structural edges get a shorter spring. */
function edgeWeight(link: SimLink): number {
  switch (link.type) {
    case 'subClassOf':
    case 'subPropertyOf':
      return 1;
    case 'instanceOf':
      return 1.2;
    case 'equivalentClass':
      return 0.6;
    case 'disjointWith':
      return 1.4;
    default:
      return 1.5;
  }
}

/**
 * Custom force pulling the focus node to the center of the viewport.
 * Keeps the focused entity visually anchored even as neighbors push it.
 */
function focusForce(focusId: string | null, width: number, height: number) {
  let nodes: SimNode[] = [];
  const force = ((alpha: number) => {
    if (!focusId) return;
    const k = 0.25 * alpha;
    for (const node of nodes) {
      if (node.id !== focusId) continue;
      const targetX = width / 2;
      const targetY = height / 2;
      node.vx = (node.vx ?? 0) + (targetX - (node.x ?? targetX)) * k;
      node.vy = (node.vy ?? 0) + (targetY - (node.y ?? targetY)) * k;
    }
  }) as d3.Force<SimNode, SimLink>;
  force.initialize = (n) => {
    nodes = n;
  };
  return force;
}

const iconBtn: React.CSSProperties = {
  border: '1px solid var(--border)',
  background: 'var(--surface-1)',
  borderRadius: 4,
  padding: '2px 4px',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  cursor: 'pointer'
};

export default LocalGraphView;

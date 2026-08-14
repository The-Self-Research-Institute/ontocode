/**
 * WebGL renderer spike — Sigma.js v3 over graphology, with the d3-force layout
 * running headless. Feature-flagged via settings.renderer === 'webgl'.
 *
 * In scope: render at 10k+ nodes, zoom/pan, hover neighbor-dim, click-select,
 * node drag, label density LOD (Sigma built-in), dark mode, ?bench=N synthetic mode.
 * Out of scope (stays on the SVG renderer): edit-on-canvas, VOWL notation shapes,
 * collaborative cursors, matrix view, export.
 */

import React, { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';
import Sigma from 'sigma';
import { drawDiscNodeHover } from 'sigma/rendering';
import Graph from 'graphology';
import { OntologyNode, OntologyEdge } from '../types';
import { buildGraphologyGraph } from './graphAdapter';
import { useFA2Layout } from './fa2Layout';
import { analyzeGraph } from './graphAnalysis';
import { InsightChips, InsightKind } from './InsightChips';
import { askGraph, AskResult, getUnmatchedTerms } from './askGraph';
import { isSparqlQuery, runSparqlHighlight } from './sparqlAsk';
import { generateSparql, mapTermsToLabels, answerQuestion } from '../services/LocalTermMapper';

/** Imperative camera / export API for the parent toolbar. */
export type WebGLCameraHandle = {
  fitAll: () => void;
  fitToNodes: (ids: string[]) => void;
  zoomBy: (factor: number) => void;
  reset: () => void;
  capturePng: () => Promise<Blob | null>;
};

// Distinct hues for Louvain module coloring (colorblind-conscious, both themes)
const COMMUNITY_PALETTE = [
  '#4e79a7', '#f28e2b', '#59a14f', '#e15759', '#b07aa1', '#76b7b2',
  '#edc948', '#ff9da7', '#9c755f', '#86bcb6', '#d37295', '#a0cbe8'
];

// Saturated palette for user-pinned color groups — must read over dimmed nodes
const GROUP_PALETTE = ['#16a34a', '#ea580c', '#7c3aed', '#0891b2', '#db2777', '#ca8a04', '#dc2626', '#2563eb'];
const COLOR_GROUPS_KEY = 'ontocode-graph-color-groups';

export interface ColorGroup {
  query: string;
  color: string;
}

/** Substring match with optional * wildcards ("sensor*quality"). */
function matchesQuery(label: string, query: string): boolean {
  const l = label.toLowerCase();
  const q = query.toLowerCase();
  if (!q.includes('*')) return l.includes(q);
  const parts = q.split('*').filter(Boolean);
  let idx = 0;
  for (const part of parts) {
    const found = l.indexOf(part, idx);
    if (found === -1) return false;
    idx = found + part.length;
  }
  return true;
}

function loadColorGroups(): ColorGroup[] {
  try {
    const raw = localStorage.getItem(COLOR_GROUPS_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter(g => typeof g?.query === 'string' && typeof g?.color === 'string') : [];
  } catch {
    return [];
  }
}

/** Cycle-safe BFS: the focus node plus everything within `depth` hops. */
function bfsNeighborhood(graph: Graph, start: string, depth: number): Set<string> {
  const seen = new Set([start]);
  let frontier = [start];
  for (let d = 0; d < depth; d++) {
    const next: string[] = [];
    for (const node of frontier) {
      graph.forEachNeighbor(node, n => {
        if (!seen.has(n)) { seen.add(n); next.push(n); }
      });
    }
    frontier = next;
    if (frontier.length === 0) break;
  }
  return seen;
}

export interface WebGLGraphViewProps {
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  dark: boolean;
  nodeSize?: number;
  positions?: Map<string, { x: number; y: number }>;
  selectedNodeIds?: Set<string>;
  /** Enables SPARQL-in-search-box against the editor backend. */
  projectId?: string;
  onNodeClick?: (nodeId: string) => void;
  onNodeRightClick?: (nodeId: string, event: { x: number; y: number }) => void;
  onNodeDoubleClick?: (nodeId: string) => void;
  /** Hierarchy expansion state/actions for the hover-card quick actions. */
  hasNodeChildren?: (nodeId: string) => boolean;
  isNodeExpanded?: (nodeId: string) => boolean;
  onToggleNodeChildren?: (nodeId: string) => void;
  /** Navigate to the entity in the host app (Entities tab). */
  onGoToEntity?: (nodeId: string) => void;
  /** Class edit actions for the hover card (rename = rdfs:label only). */
  canEdit?: boolean;
  onRenameNode?: (nodeId: string, newLabel: string) => void | Promise<void>;
  onDeleteNode?: (nodeId: string) => void;
  onAddChildNode?: (nodeId: string) => void;
  /**
   * Search-panel Dim mode: fade everything outside this focus set.
   * Hide mode is handled upstream by filtering `nodes`/`edges`.
   */
  dimFocusIds?: Set<string> | null;
  /**
   * Bump after bulk hierarchy changes (Expand All / deep dive) so the camera
   * re-frames once layout has nodes to measure — same role as SVG auto-fit
   * after Tree↔Network.
   */
  viewportFitToken?: number;
  /** CSS background grid on the wrapper when true. */
  showGrid?: boolean;
  /**
   * When false, skip ForceAtlas2 even without saved positions.
   * When true (default), run FA2 only if positions are absent/empty.
   */
  physicsEnabled?: boolean;
  /**
   * The host's search/filter panel (AdvancedGraphView's styles.searchPanel)
   * covers the bottom-left corner when open — suppress the hover card
   * instead of repositioning it, so it never renders on top of that panel.
   */
  searchPanelOpen?: boolean;
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

export const WebGLGraphView = forwardRef<WebGLCameraHandle, WebGLGraphViewProps>(function WebGLGraphView({
  nodes,
  edges,
  dark,
  nodeSize,
  positions,
  selectedNodeIds,
  projectId,
  onNodeClick,
  onNodeRightClick,
  onNodeDoubleClick,
  hasNodeChildren,
  isNodeExpanded,
  onToggleNodeChildren,
  onGoToEntity,
  canEdit,
  onRenameNode,
  onDeleteNode,
  onAddChildNode,
  dimFocusIds = null,
  viewportFitToken = 0,
  showGrid = false,
  physicsEnabled = true,
  searchPanelOpen = false
}, ref) {
  const containerRef = useRef<HTMLDivElement>(null);
  const sigmaRef = useRef<Sigma | null>(null);
  const [hoveredNode, setHoveredNode] = useState<string | null>(null);
  const hoveredNodeStateRef = useRef<string | null>(null);
  const hoveredNeighborsRef = useRef<Set<string>>(new Set());
  // Delay hiding the hover card so the pointer can travel onto its action buttons
  const hoverClearTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Inline rename in the hover card: node id being renamed + draft label
  const [renaming, setRenaming] = useState<{ id: string; value: string } | null>(null);
  const renamingRef = useRef(renaming);
  renamingRef.current = renaming;
  const draggedNodeRef = useRef<string | null>(null);
  const [activeInsight, setActiveInsight] = useState<InsightKind | null>(null);
  const emphasizedIdsRef = useRef<Set<string> | null>(null);
  /** Search-panel Dim focus — separate from insight/focus emphasis so they don't clobber each other. */
  const dimFocusIdsRef = useRef<Set<string> | null>(null);
  dimFocusIdsRef.current = dimFocusIds && dimFocusIds.size > 0 ? dimFocusIds : null;
  const [communitiesOn, setCommunitiesOn] = useState(false);
  const communitiesOnRef = useRef(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [askResult, setAskResult] = useState<AskResult | null>(null);
  const askAnswersRef = useRef<Set<string> | null>(null);
  // Focus mode (absorbed from LocalGraphView): center a node, show its N-hop
  // neighborhood, walk by clicking, follow external selection.
  const [focus, setFocus] = useState<{ id: string; depth: number } | null>(null);
  const focusRef = useRef(focus);
  focusRef.current = focus;
  const [colorGroups, setColorGroups] = useState<ColorGroup[]>(loadColorGroups);
  const colorGroupsRef = useRef(colorGroups);

  // Ref sync + repaint must happen after the state commit, not before
  useEffect(() => {
    colorGroupsRef.current = colorGroups;
    sigmaRef.current?.refresh({ skipIndexation: true });
  }, [colorGroups]);

  const graph = useMemo<Graph>(
    () => buildGraphologyGraph(nodes, edges, { dark, positions, nodeSize }),
    [nodes, edges, dark, positions, nodeSize]
  );

  const insights = useMemo(() => analyzeGraph(graph), [graph]);
  const insightsRef = useRef(insights);
  insightsRef.current = insights;

  // Layout: ForceAtlas2 in a worker when physics is on and no saved positions
  useFA2Layout(graph, physicsEnabled && (!positions || positions.size === 0));

  useEffect(() => {
    if (!containerRef.current) return;

    const renderer = new Sigma(graph, containerRef.current, {
      allowInvalidContainer: true,
      renderEdgeLabels: true,
      defaultEdgeType: 'arrow',
      edgeLabelSize: 10,
      edgeLabelColor: { color: dark ? '#9aa7b8' : '#64748b' },
      // Grid cell size below Sigma's own default (100) packs labels in tighter
      // than they can fit — long entity names like "GreenPepperTopping" then
      // overlap their neighbors. Widen the cell and tighten density so the
      // decluttering grid actually wins ties instead of drawing both.
      // Large graphs (GO Network): hide most labels until zoomed — otherwise
      // thousands of labels paint over the FA2 blob.
      labelDensity: nodes.length > 400 ? 0.25 : 0.7,
      labelGridCellSize: nodes.length > 400 ? 220 : 160,
      labelRenderedSizeThreshold: nodes.length > 400 ? 12 : 5,
      labelColor: { color: dark ? '#e7e9f0' : '#1f2430' },
      defaultEdgeColor: dark ? '#556274' : '#aab8cc',
      zIndex: true,
      // Sigma's built-in hover renderer always fills the label box with white,
      // then draws the label text using `labelColor` — in dark theme that's a
      // light color, so the text disappears on its own white background. Force
      // the hover label text to a fixed dark color to match the fixed white box.
      defaultDrawNodeHover: (context, data, settings) => {
        drawDiscNodeHover(context, data, { ...settings, labelColor: { color: '#1f2430' } });
      }
    });
    sigmaRef.current = renderer;
    // Test hook: lets harnesses resolve node viewport coordinates
    (containerRef.current as unknown as { __sigma?: Sigma }).__sigma = renderer;

    // Hover: dim everything outside the hovered node's neighborhood.
    // Insight emphasis / search Dim: dim everything outside the focus set.
    renderer.setSetting('nodeReducer', (node, data) => {
      const res: Record<string, unknown> = { ...data };
      const searchDim = dimFocusIdsRef.current;
      const emphasized = emphasizedIdsRef.current;
      const hovered = hoveredNodeStateRef.current;
      if (communitiesOnRef.current) {
        const community = insightsRef.current.communities[node];
        if (community != null) res.color = COMMUNITY_PALETTE[community % COMMUNITY_PALETTE.length];
      }
      // Pinned color groups: explicit user intent beats the automatic module overlay
      const label = String(data.label ?? '');
      for (const group of colorGroupsRef.current) {
        if (matchesQuery(label, group.query)) {
          res.color = group.color;
          break;
        }
      }
      // Search-panel Dim takes priority over insight/focus emphasis when active
      const focusSet = searchDim ?? emphasized;
      if (focusSet && !focusSet.has(node)) {
        res.color = dark ? '#2a3341' : '#e5e7eb';
        res.label = '';
        res.zIndex = 0;
      } else if (focusSet) {
        if (searchDim) {
          res.zIndex = 2;
        } else {
          // Ask mode: only answer nodes glow; path nodes stay normal for readability
          const answers = askAnswersRef.current;
          if (!answers || answers.has(node)) {
            res.highlighted = true;
            res.zIndex = 2;
          }
        }
      } else if (hovered && node !== hovered && !hoveredNeighborsRef.current.has(node)) {
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
      const searchDim = dimFocusIdsRef.current;
      const emphasized = emphasizedIdsRef.current;
      const hovered = hoveredNodeStateRef.current;
      // Typed edges are the ontology advantage — but only label the hovered neighborhood to avoid clutter
      if (!hovered || !graph.hasExtremity(edge, hovered)) {
        res.label = '';
      }
      const focusSet = searchDim ?? emphasized;
      if (focusSet) {
        const [s, t] = graph.extremities(edge);
        if (!focusSet.has(s) || !focusSet.has(t)) res.hidden = true;
      } else if (hovered && !graph.hasExtremity(edge, hovered)) {
        res.hidden = true;
      }
      return res;
    });

    renderer.on('enterNode', ({ node }) => {
      if (hoverClearTimerRef.current) { clearTimeout(hoverClearTimerRef.current); hoverClearTimerRef.current = null; }
      hoveredNeighborsRef.current = new Set(graph.neighbors(node));
      setHoveredNode(node);
    });
    renderer.on('leaveNode', () => {
      if (hoverClearTimerRef.current) clearTimeout(hoverClearTimerRef.current);
      hoverClearTimerRef.current = setTimeout(() => {
        if (renamingRef.current) return; // keep the card while typing a rename
        hoveredNeighborsRef.current = new Set();
        setHoveredNode(null);
      }, 450);
    });
    renderer.on('clickNode', ({ node }) => {
      // In focus mode a click walks the neighborhood hop by hop
      if (focusRef.current) setFocus({ id: node, depth: focusRef.current.depth });
      onNodeClick?.(node);
    });
    renderer.on('doubleClickNode', ({ node, event }) => {
      (event as any).preventSigmaDefault?.();
      setFocus(prev => ({ id: node, depth: prev?.depth ?? 1 }));
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
      if (hoverClearTimerRef.current) clearTimeout(hoverClearTimerRef.current);
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

  // Keep search-panel Dim focus in sync with Sigma reducers
  useEffect(() => {
    dimFocusIdsRef.current = dimFocusIds && dimFocusIds.size > 0 ? dimFocusIds : null;
    sigmaRef.current?.refresh({ skipIndexation: true });
  }, [dimFocusIds]);

  // Fly the camera to frame a set of nodes (framed-graph coords: ratio 1 ≈ whole graph)
  const fitToNodes = useCallback((ids: string[]) => {
    const renderer = sigmaRef.current;
    if (!renderer || ids.length === 0) return;
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    let found = 0;
    ids.forEach(id => {
      const d = renderer.getNodeDisplayData(id);
      if (!d) return;
      found++;
      minX = Math.min(minX, d.x); maxX = Math.max(maxX, d.x);
      minY = Math.min(minY, d.y); maxY = Math.max(maxY, d.y);
    });
    if (found === 0) return;
    const span = Math.max(maxX - minX, maxY - minY);
    // Single node / tight cluster: keep context visible instead of extreme zoom
    const ratio = Math.min(1.3, Math.max(found <= 2 ? 0.3 : 0.08, span * 1.4 + 0.05));
    renderer.getCamera().animate(
      { x: (minX + maxX) / 2, y: (minY + maxY) / 2, ratio },
      { duration: 600 }
    );
  }, []);

  const fitAll = useCallback(() => {
    const renderer = sigmaRef.current;
    if (!renderer) return;
    fitToNodes(renderer.getGraph().nodes());
  }, [fitToNodes]);

  // Sigma: smaller ratio = zoom in. factor > 1 means zoom in for the toolbar.
  const zoomBy = useCallback((factor: number) => {
    const camera = sigmaRef.current?.getCamera();
    if (!camera) return;
    const next = Math.max(0.02, Math.min(3, camera.ratio / factor));
    camera.animate({ ratio: next }, { duration: 250 });
  }, []);

  const resetCamera = useCallback(() => {
    sigmaRef.current?.getCamera().animate({ x: 0.5, y: 0.5, ratio: 1.1 }, { duration: 500 });
  }, []);

  const capturePng = useCallback((): Promise<Blob | null> => {
    return new Promise((resolve) => {
      const renderer = sigmaRef.current;
      const container = containerRef.current;
      try {
        const canvases = renderer?.getCanvases?.();
        if (canvases) {
          const layers = Object.values(canvases).filter(
            (c): c is HTMLCanvasElement => !!c && c instanceof HTMLCanvasElement && c.width > 0
          );
          if (layers.length > 0) {
            const w = Math.max(...layers.map(c => c.width));
            const h = Math.max(...layers.map(c => c.height));
            const out = document.createElement('canvas');
            out.width = w;
            out.height = h;
            const ctx = out.getContext('2d');
            if (ctx) {
              ctx.fillStyle = dark ? '#0f172a' : '#ffffff';
              ctx.fillRect(0, 0, w, h);
              for (const layer of layers) ctx.drawImage(layer, 0, 0);
              out.toBlob((blob) => resolve(blob), 'image/png');
              return;
            }
          }
        }
        const canvas = container?.querySelector('canvas');
        if (canvas) {
          canvas.toBlob((blob) => resolve(blob), 'image/png');
          return;
        }
      } catch (err) {
        console.warn('[WebGLGraphView] capturePng failed', err);
      }
      resolve(null);
    });
  }, [dark]);

  useImperativeHandle(ref, () => ({
    fitAll,
    fitToNodes,
    zoomBy,
    reset: resetCamera,
    capturePng
  }), [fitAll, fitToNodes, zoomBy, resetCamera, capturePng]);

  // Bulk expand/collapse: nodes spread under FA2 while the camera stays put —
  // re-frame all nodes on the same cadence as SVG Tree↔Network auto-fit, plus
  // a late pass after FA2 has had more time on larger graphs.
  useEffect(() => {
    if (!viewportFitToken) return;
    const t1 = setTimeout(fitAll, 450);
    const t2 = setTimeout(fitAll, 1800);
    const t3 = setTimeout(fitAll, 4500);
    return () => {
      clearTimeout(t1);
      clearTimeout(t2);
      clearTimeout(t3);
    };
  }, [viewportFitToken, fitAll]);

  const handleInsightSelect = useCallback((kind: InsightKind, ids: string[]) => {
    setFocus(null);
    if (kind === 'communities') {
      communitiesOnRef.current = !communitiesOnRef.current;
      setCommunitiesOn(communitiesOnRef.current);
      sigmaRef.current?.refresh({ skipIndexation: true });
      return;
    }
    emphasizedIdsRef.current = new Set(ids);
    setActiveInsight(kind);
    sigmaRef.current?.refresh({ skipIndexation: true });
    fitToNodes(ids);
  }, [fitToNodes]);

  const handleInsightClear = useCallback(() => {
    setFocus(null);
    emphasizedIdsRef.current = null;
    setActiveInsight(null);
    sigmaRef.current?.refresh({ skipIndexation: true });
    sigmaRef.current?.getCamera().animate({ x: 0.5, y: 0.5, ratio: 1.1 }, { duration: 500 });
  }, []);

  // Focus neighborhood drives the emphasis machinery whenever focus is active
  const focusNodes = useMemo(
    () => (focus && graph.hasNode(focus.id) ? bfsNeighborhood(graph, focus.id, focus.depth) : null),
    [graph, focus]
  );
  useEffect(() => {
    if (!focus || !focusNodes) return;
    emphasizedIdsRef.current = focusNodes;
    askAnswersRef.current = new Set([focus.id]);
    setAskResult(null);
    setSearchQuery('');
    sigmaRef.current?.refresh({ skipIndexation: true });
    fitToNodes([...focusNodes]);
  }, [focus, focusNodes, fitToNodes]);

  const exitFocus = useCallback(() => {
    setFocus(null);
    emphasizedIdsRef.current = null;
    askAnswersRef.current = null;
    sigmaRef.current?.refresh({ skipIndexation: true });
    sigmaRef.current?.getCamera().animate({ x: 0.5, y: 0.5, ratio: 1.1 }, { duration: 500 });
  }, []);

  // Applies an ask/SPARQL result: highlight subgraph, glow answers, fly camera
  const applyAskResult = useCallback((result: AskResult | null) => {
    setAskResult(result);
    if (result && result.subgraph.size > 0) {
      emphasizedIdsRef.current = result.subgraph;
      askAnswersRef.current = result.answers.size > 0 ? result.answers : null;
      sigmaRef.current?.refresh({ skipIndexation: true });
      fitToNodes([...result.subgraph]);
    }
  }, [fitToNodes]);

  const handleRunSparql = useCallback(async (query: string) => {
    if (!projectId) return;
    setAskResult({ subgraph: new Set(), answers: new Set(), summary: 'Running SPARQL…', predicates: [] });
    applyAskResult(await runSparqlHighlight(graph, query, projectId));
  }, [graph, projectId, applyAskResult]);

  // Enter on a question: escalating AI cascade, every step falling back to the
  // deterministic answer already on screen.
  //   1. local LLM writes SPARQL from the rendered schema → backend executes
  //   2. local LLM maps unmatched terms to real labels ("sensors"→SensingDevice) → rerun structural ask
  //   3. deterministic askGraph result stays
  const handleAskAi = useCallback(async (question: string) => {
    if (!projectId) return;
    const fallback = askGraph(graph, question);
    setAskResult({
      subgraph: fallback?.subgraph ?? new Set(),
      answers: fallback?.answers ?? new Set(),
      predicates: fallback?.predicates ?? [],
      summary: 'Local AI thinking…'
    });
    const classes = graph.filterNodes((_id, attrs) => attrs.nodeType === 'class')
      .map(id => ({ iri: id, label: String(graph.getNodeAttribute(id, 'label') ?? id) }));
    const predicateNames = [...new Set<string>(
      graph.mapEdges((_e, attrs) => String(attrs.label || attrs.edgeType || '')).filter(Boolean)
    )];

    const query = await generateSparql(question, classes, predicateNames);
    if (query) {
      const result = await runSparqlHighlight(graph, query, projectId);
      // Aggregates (COUNT/"most") return literals — accept any non-empty rows, not just IRIs in view
      if (result.answers.size > 0 || (result.rows?.length ?? 0) > 0) {
        applyAskResult({ ...result, summary: `AI · ${result.summary}`, detail: query });
        // Second pass: let the model read the results (IRIs → labels) and write the answer
        const labeled = (result.rows ?? []).map(row => Object.fromEntries(
          Object.entries(row).map(([k, v]) => [
            k, graph.hasNode(v) ? String(graph.getNodeAttribute(v, 'label') ?? v) : v
          ])
        ));
        const answer = await answerQuestion(question, labeled, query);
        if (answer) {
          setAskResult(prev => (prev && prev.detail === query) ? { ...prev, answer } : prev);
        }
        return;
      }
    }

    const unmatched = getUnmatchedTerms(graph, question);
    if (unmatched.length > 0) {
      const allLabels = graph.mapNodes((_id, attrs) => String(attrs.label ?? '')).filter(Boolean);
      const mapping = await mapTermsToLabels(question, unmatched, allLabels);
      if (mapping) {
        const remapped = askGraph(graph, question, mapping);
        if (remapped && remapped.answers.size > 0) {
          const note = Object.entries(mapping).map(([t, ls]) => `${t} → ${ls.join('/')}`).join(', ');
          applyAskResult({ ...remapped, summary: `AI · ${remapped.summary}`, detail: `matched: ${note}` });
          return;
        }
      }
    }

    applyAskResult(fallback
      ? { ...fallback, summary: `${fallback.summary}${query ? ' (AI query returned nothing)' : ' (local AI unavailable)'}`, detail: query ?? undefined }
      : { subgraph: new Set(), answers: new Set(), predicates: [], summary: 'No matching concepts found' });
  }, [graph, projectId, applyAskResult]);

  // Visual search: matches are emphasized in place — the graph reshapes around the query.
  // Questions ("which qualities are measured by sensors?") route to ask-the-graph.
  // SPARQL ("SELECT ?q WHERE {…}") runs on Enter against the editor backend.
  const handleSearch = useCallback((query: string) => {
    setSearchQuery(query);
    setActiveInsight(null);
    setFocus(null);
    const q = query.trim().toLowerCase();
    if (!q) {
      emphasizedIdsRef.current = null;
      askAnswersRef.current = null;
      setAskResult(null);
      sigmaRef.current?.refresh({ skipIndexation: true });
      return;
    }
    if (isSparqlQuery(query)) {
      setAskResult({
        subgraph: new Set(), answers: new Set(), predicates: [],
        summary: projectId ? 'SPARQL detected — press Enter to run' : 'SPARQL needs a project backend'
      });
      return;
    }
    const isQuestion = /\?\s*$/.test(query) || /^(which|what|who|how|show|list|find|does|is|are)\b/i.test(query.trim());
    if (isQuestion) {
      applyAskResult(askGraph(graph, query));
      return;
    }
    askAnswersRef.current = null;
    setAskResult(null);
    const matches = graph.filterNodes((_id, attrs) => matchesQuery(String(attrs.label ?? ''), q));
    emphasizedIdsRef.current = new Set(matches);
    sigmaRef.current?.refresh({ skipIndexation: true });
    fitToNodes(matches);
  }, [graph, fitToNodes, projectId, applyAskResult]);

  // Invariant: an external selection always ends up centered on screen.
  // In focus mode it retargets the neighborhood instead (follow-selection).
  useEffect(() => {
    if (selectedNodeIds && selectedNodeIds.size === 1) {
      const [id] = [...selectedNodeIds];
      const f = focusRef.current;
      if (f && f.id !== id && graph.hasNode(id)) {
        setFocus({ id, depth: f.depth });
        return;
      }
      fitToNodes([id]);
    }
  }, [selectedNodeIds, fitToNodes, graph]);

  const persistGroups = useCallback((groups: ColorGroup[]) => {
    setColorGroups(groups);
    try { localStorage.setItem(COLOR_GROUPS_KEY, JSON.stringify(groups)); } catch { /* storage full/blocked */ }
  }, []);

  const handlePinGroup = useCallback(() => {
    const query = searchQuery.trim();
    if (!query || colorGroupsRef.current.some(g => g.query === query)) return;
    const used = new Set(colorGroupsRef.current.map(g => g.color));
    const color = GROUP_PALETTE.find(c => !used.has(c)) ?? GROUP_PALETTE[colorGroupsRef.current.length % GROUP_PALETTE.length];
    persistGroups([...colorGroupsRef.current, { query, color }]);
    setSearchQuery('');
    emphasizedIdsRef.current = null;
    sigmaRef.current?.refresh({ skipIndexation: true });
  }, [searchQuery, persistGroups]);

  const handleRemoveGroup = useCallback((query: string) => {
    persistGroups(colorGroupsRef.current.filter(g => g.query !== query));
  }, [persistGroups]);

  const hoverInfo = useMemo(() => {
    // Prefer hover, then single selection, then focus — so Entity / actions stay reachable after click
    const singleSelected =
      selectedNodeIds && selectedNodeIds.size === 1 ? [...selectedNodeIds][0] : null;
    const cardNode = hoveredNode ?? singleSelected ?? focus?.id ?? null;
    if (!cardNode || !graph.hasNode(cardNode)) return null;
    const attrs = graph.getNodeAttributes(cardNode);
    return {
      id: cardNode,
      label: String(attrs.label ?? cardNode),
      type: String(attrs.nodeType ?? ''),
      degree: graph.degree(cardNode),
      module: insights.communities[cardNode]
    };
  }, [hoveredNode, selectedNodeIds, focus, graph, insights]);

  return (
    <div
      style={{
        position: 'relative',
        width: '100%',
        height: '100%',
        minHeight: 400,
        ...(showGrid
          ? {
              backgroundImage:
                'linear-gradient(to right, rgba(148,163,184,0.25) 1px, transparent 1px), linear-gradient(to bottom, rgba(148,163,184,0.25) 1px, transparent 1px)',
              backgroundSize: '20px 20px'
            }
          : {})
      }}
    >
      <InsightChips
        insights={insights}
        active={activeInsight}
        communitiesOn={communitiesOn}
        dark={dark}
        onSelect={handleInsightSelect}
        onClear={handleInsightClear}
      />
      <input
        data-testid="graph-webgl-search"
        type="search"
        placeholder="Search, ask, or SPARQL…"
        value={searchQuery}
        onChange={e => handleSearch(e.target.value)}
        onKeyDown={e => {
          if (e.key !== 'Enter') return;
          const text = searchQuery.trim();
          const isQuestion = /\?\s*$/.test(text) || /^(which|what|who|how|show|list|find|does|is|are)\b/i.test(text);
          if (isSparqlQuery(text)) handleRunSparql(text);
          else if (isQuestion) handleAskAi(text);
          else handlePinGroup();
        }}
        style={{
          position: 'absolute',
          top: 10,
          right: 10,
          zIndex: 20,
          width: 180,
          padding: '4px 10px',
          borderRadius: 12,
          fontSize: 12,
          border: `1px solid ${dark ? '#374151' : '#d1d5db'}`,
          backgroundColor: dark ? '#1f2937cc' : '#ffffffcc',
          color: dark ? '#e5e7eb' : '#1f2937',
          outline: 'none',
          backdropFilter: 'blur(4px)'
        }}
      />
      {/* Search box above is ~26-28px tall (padding + border); 38 left only a
          few px of clearance and the focus chip's own padding ate into it —
          bump the gap so the two never touch regardless of font rendering. */}
      <div style={{ position: 'absolute', top: 48, right: 10, zIndex: 20, display: 'flex', flexDirection: 'column', gap: 4, alignItems: 'flex-end' }}>
        {focus && (
          <div
            data-testid="graph-webgl-focus"
            style={{
              maxWidth: 260,
              padding: '6px 12px',
              borderRadius: 8,
              fontSize: 11.5,
              lineHeight: 1.5,
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              border: `1px solid ${dark ? '#7c3aed' : '#c4b5fd'}`,
              backgroundColor: dark ? '#2e1065e6' : '#f5f3ffe6',
              color: dark ? '#e5e7eb' : '#1f2937',
              backdropFilter: 'blur(4px)'
            }}
          >
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              🎯 {String(graph.hasNode(focus.id) ? graph.getNodeAttribute(focus.id, 'label') : focus.id)}
              {focusNodes && <span style={{ opacity: 0.7 }}> · {focusNodes.size} nodes</span>}
            </span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 3, flexShrink: 0 }}>
              <button
                data-testid="graph-webgl-focus-depth-down"
                onClick={() => setFocus(f => f && { ...f, depth: Math.max(1, f.depth - 1) })}
                disabled={focus.depth <= 1}
                style={{ border: 'none', background: 'transparent', color: 'inherit', cursor: 'pointer', padding: '0 2px', opacity: focus.depth <= 1 ? 0.4 : 1 }}
              >−</button>
              {focus.depth} hop{focus.depth === 1 ? '' : 's'}
              <button
                data-testid="graph-webgl-focus-depth-up"
                onClick={() => setFocus(f => f && { ...f, depth: Math.min(15, f.depth + 1) })}
                disabled={focus.depth >= 15}
                style={{ border: 'none', background: 'transparent', color: 'inherit', cursor: 'pointer', padding: '0 2px', opacity: focus.depth >= 15 ? 0.4 : 1 }}
              >+</button>
              <button
                data-testid="graph-webgl-focus-exit"
                onClick={exitFocus}
                title="Exit focus mode"
                style={{ border: 'none', background: 'transparent', color: 'inherit', cursor: 'pointer', padding: '0 2px' }}
              >✕</button>
            </span>
          </div>
        )}
        {askResult && (
          <div
            data-testid="graph-webgl-answer"
            style={{
              maxWidth: 260,
              padding: '6px 12px',
              borderRadius: 8,
              fontSize: 11.5,
              lineHeight: 1.5,
              border: `1px solid ${dark ? '#4f46e5' : '#a5b4fc'}`,
              backgroundColor: dark ? '#1e1b4be6' : '#eef2ffe6',
              color: dark ? '#e5e7eb' : '#1f2937',
              backdropFilter: 'blur(4px)'
            }}
          >
            {askResult.answer && (
              <div data-testid="graph-webgl-answer-text" style={{ fontWeight: 600, marginBottom: 3 }}>
                {askResult.answer}
              </div>
            )}
            {askResult.summary}
            {askResult.predicates.length > 0 && (
              <div style={{ opacity: 0.75, marginTop: 2 }}>via: {askResult.predicates.join(', ')}</div>
            )}
            {askResult.detail && (
              <pre style={{ opacity: 0.7, marginTop: 4, marginBottom: 0, fontSize: 10, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 120, overflowY: 'auto', fontFamily: 'monospace' }}>
                {askResult.detail}
              </pre>
            )}
          </div>
        )}
        {searchQuery.trim() && !askResult && (
          <button
            data-testid="graph-webgl-pin-group"
            onClick={handlePinGroup}
            title="Pin this search as a permanent color group (Enter)"
            style={{
              padding: '2px 10px',
              borderRadius: 12,
              fontSize: 11,
              cursor: 'pointer',
              border: `1px solid ${dark ? '#374151' : '#d1d5db'}`,
              backgroundColor: dark ? '#1f2937cc' : '#ffffffcc',
              color: dark ? '#e5e7eb' : '#1f2937'
            }}
          >
            📌 Pin as color group
          </button>
        )}
        {colorGroups.map(group => (
          <span
            key={group.query}
            data-testid="graph-webgl-color-group"
            title={`Nodes matching “${group.query}” — click ✕ to remove`}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              padding: '2px 8px',
              borderRadius: 12,
              fontSize: 11,
              border: `1px solid ${dark ? '#374151' : '#d1d5db'}`,
              backgroundColor: dark ? '#1f2937cc' : '#ffffffcc',
              color: dark ? '#e5e7eb' : '#1f2937',
              backdropFilter: 'blur(4px)'
            }}
          >
            <span style={{ width: 9, height: 9, borderRadius: '50%', backgroundColor: group.color }} />
            {group.query}
            <span
              style={{ cursor: 'pointer', opacity: 0.7 }}
              onClick={() => handleRemoveGroup(group.query)}
            >
              ✕
            </span>
          </span>
        ))}
      </div>
      {hoverInfo && !searchPanelOpen && (
        <div
          data-testid="graph-webgl-hovercard"
          onMouseEnter={() => {
            if (hoverClearTimerRef.current) { clearTimeout(hoverClearTimerRef.current); hoverClearTimerRef.current = null; }
          }}
          onMouseLeave={() => {
            if (renamingRef.current) return; // keep the card while typing a rename
            hoveredNeighborsRef.current = new Set();
            setHoveredNode(null);
          }}
          style={{
            // The search/filter panel (AdvancedGraphView's styles.searchPanel)
            // is anchored bottom-left down to nearly the canvas floor — it
            // would sit under this card, so hide the card instead of moving
            // it (searchPanelOpen guard above), rather than fight over the corner.
            position: 'absolute',
            bottom: 10,
            left: 10,
            zIndex: 20,
            padding: '6px 12px',
            borderRadius: 8,
            fontSize: 12,
            lineHeight: 1.5,
            maxWidth: 280,
            border: `1px solid ${dark ? '#374151' : '#d1d5db'}`,
            backgroundColor: dark ? '#1f2937e6' : '#ffffffe6',
            color: dark ? '#e5e7eb' : '#1f2937',
            backdropFilter: 'blur(4px)'
          }}
        >
          {renaming?.id === hoverInfo.id ? (
            <input
              data-testid="graph-webgl-rename-input"
              autoFocus
              value={renaming.value}
              onChange={e => setRenaming({ id: renaming.id, value: e.target.value })}
              onKeyDown={e => {
                if (e.key === 'Enter') {
                  const value = renaming.value.trim();
                  setRenaming(null);
                  if (value && value !== hoverInfo.label) onRenameNode?.(hoverInfo.id, value);
                } else if (e.key === 'Escape') {
                  setRenaming(null);
                }
              }}
              style={{
                width: '100%',
                padding: '2px 6px',
                borderRadius: 6,
                fontSize: 12,
                border: `1px solid ${dark ? '#4b5563' : '#d1d5db'}`,
                backgroundColor: dark ? '#111827' : '#ffffff',
                color: 'inherit',
                outline: 'none'
              }}
            />
          ) : (
            <strong>{hoverInfo.label}</strong>
          )}
          <div style={{ opacity: 0.75 }}>
            {hoverInfo.type}{hoverInfo.type ? ' · ' : ''}{hoverInfo.degree} connection{hoverInfo.degree === 1 ? '' : 's'}
            {hoverInfo.module != null ? ` · module ${hoverInfo.module + 1}` : ''}
          </div>
          <div style={{ display: 'flex', gap: 6, marginTop: 5, flexWrap: 'wrap' }}>
            {[
              {
                testid: 'graph-webgl-hover-focus',
                label: '🎯 Focus',
                title: 'Focus on this node’s neighborhood (also: double-click the node)',
                show: hoverInfo.id !== focus?.id,
                onClick: () => setFocus(prev => ({ id: hoverInfo.id, depth: prev?.depth ?? 1 }))
              },
              {
                testid: 'graph-webgl-hover-children',
                label: isNodeExpanded?.(hoverInfo.id) ? '➖ Collapse' : '➕ Expand',
                title: isNodeExpanded?.(hoverInfo.id) ? 'Collapse children' : 'Expand children',
                show: !!onToggleNodeChildren && (hasNodeChildren?.(hoverInfo.id) ?? false),
                onClick: () => onToggleNodeChildren?.(hoverInfo.id)
              },
              {
                testid: 'graph-webgl-hover-entity',
                label: '📄 Entity',
                title: 'Go to this entity in the Entities tab',
                show: !!onGoToEntity,
                onClick: () => onGoToEntity?.(hoverInfo.id)
              },
              {
                testid: 'graph-webgl-hover-rename',
                label: '✏️ Rename',
                title: 'Rename this class (changes the label, not the IRI)',
                show: !!canEdit && !!onRenameNode && hoverInfo.type === 'class' && renaming?.id !== hoverInfo.id,
                onClick: () => setRenaming({ id: hoverInfo.id, value: hoverInfo.label })
              },
              {
                testid: 'graph-webgl-hover-add-child',
                label: '🌱 Sub',
                title: 'Add a subclass under this class',
                show: !!canEdit && !!onAddChildNode && hoverInfo.type === 'class',
                onClick: () => onAddChildNode?.(hoverInfo.id)
              },
              {
                testid: 'graph-webgl-hover-delete',
                label: '🗑 Delete',
                title: 'Delete this class (asks for confirmation)',
                show: !!canEdit && !!onDeleteNode && hoverInfo.type === 'class',
                onClick: () => onDeleteNode?.(hoverInfo.id)
              }
            ].filter(action => action.show).map(action => (
              <button
                key={action.testid}
                data-testid={action.testid}
                title={action.title}
                onClick={(e) => {
                  e.stopPropagation();
                  e.preventDefault();
                  action.onClick();
                }}
                style={{
                  padding: '2px 8px',
                  borderRadius: 10,
                  fontSize: 11,
                  cursor: 'pointer',
                  border: `1px solid ${dark ? '#4b5563' : '#d1d5db'}`,
                  backgroundColor: dark ? '#111827' : '#f9fafb',
                  color: 'inherit'
                }}
              >
                {action.label}
              </button>
            ))}
          </div>
        </div>
      )}
      <div
        ref={containerRef}
        data-testid="graph-webgl-canvas"
        style={{ width: '100%', height: '100%', minHeight: 400 }}
      />
    </div>
  );
});

WebGLGraphView.displayName = 'WebGLGraphView';


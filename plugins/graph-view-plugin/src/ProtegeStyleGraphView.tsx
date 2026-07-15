/**
 * ============================================================================
 * PROTEGE-STYLE ONTOGRAF VIEW
 * ============================================================================
 *
 * Production rewrite — every toolbar button, search input, context menu and
 * assertion-mode toggle is now wired to a real handler. The class hierarchy
 * panel uses the new <ClassHierarchyPanel /> for full Protege parity
 * (multi-parent, virtualized, keyboard-navigable, etc).
 */

import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState
} from 'react';
import { Network } from 'vis-network';
import type { Options, Data, Node, Edge } from 'vis-network';
import {
  ZoomIn,
  ZoomOut,
  Maximize2,
  Download,
  RefreshCw,
  Search,
  X,
  Settings,
  Grid,
  Layers,
  Filter,
  Image as ImageIcon,
  FileText,
  Save,
  Copy,
  MousePointer,
  Hand,
  Trash2
} from 'lucide-react';
import { applyRadialLayout, applyCircularLayout } from './layouts';
import { ClassHierarchyPanel } from './components/ClassHierarchyPanel';
import type { OntologyNode, OntologyEdge, EdgeType, NodeType } from './types';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface ProtegeStyleGraphViewProps {
  projectId: string;
  /** Optional ontology display name shown in the header. */
  ontologyName?: string;
  /** Optional pre-supplied data; when omitted the component fetches via REST. */
  initialData?: { nodes: OntologyNode[]; edges: OntologyEdge[] };
  /** Optional asserted-edge subset; used for inferred-only highlighting. */
  assertedEdges?: OntologyEdge[];
  /** Read-only mode disables mutation actions in the context menu. */
  readonly?: boolean;
}

type LayoutType =
  | 'hierarchical'
  | 'tree-vertical'
  | 'tree-horizontal'
  | 'radial'
  | 'circular'
  | 'spring'
  | 'force';

type AssertionMode = 'asserted' | 'inferred' | 'all';
type Tool = 'select' | 'pan' | 'add-node' | 'add-edge' | 'remove';

interface BackendGraphResponse {
  nodes?: OntologyNode[];
  edges?: OntologyEdge[];
  ontology?: { name?: string; iri?: string };
  assertedEdges?: OntologyEdge[];
  assertedEdgeIds?: string[];
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const NODE_COLORS: Record<string, string> = {
  class: '#FFD700',
  individual: '#90EE90',
  objectProperty: '#87CEEB',
  dataProperty: '#DDA0DD',
  annotation: '#F0E68C',
  property: '#F0E68C',
  datatype: '#FFA07A'
};

const NODE_SHAPES: Record<string, string> = {
  class: 'box',
  individual: 'ellipse',
  objectProperty: 'diamond',
  dataProperty: 'star',
  annotation: 'triangle',
  property: 'triangle',
  datatype: 'dot'
};

const HIERARCHY_LAYOUTS: ReadonlySet<LayoutType> = new Set([
  'hierarchical',
  'tree-vertical',
  'tree-horizontal'
]);
const PRECOMPUTED_LAYOUTS: ReadonlySet<LayoutType> = new Set(['radial', 'circular']);

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export const ProtegeStyleGraphView: React.FC<ProtegeStyleGraphViewProps> = ({
  projectId,
  ontologyName,
  initialData,
  assertedEdges: assertedEdgesProp,
  readonly = false
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const networkRef = useRef<Network | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);

  const [nodes, setNodes] = useState<OntologyNode[]>(initialData?.nodes ?? []);
  const [edges, setEdges] = useState<OntologyEdge[]>(initialData?.edges ?? []);
  const [assertedEdges, setAssertedEdges] = useState<OntologyEdge[] | undefined>(
    assertedEdgesProp
  );
  const [ontologyMeta, setOntologyMeta] = useState<{ name?: string; iri?: string }>(
    ontologyName ? { name: ontologyName } : {}
  );

  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const [selectedNode, setSelectedNode] = useState<OntologyNode | null>(null);
  const [hoveredNode, setHoveredNode] = useState<OntologyNode | null>(null);
  const [tooltipPosition, setTooltipPosition] = useState<{ x: number; y: number } | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [showClassTree, setShowClassTree] = useState(true);
  const [showLayoutMenu, setShowLayoutMenu] = useState(false);
  const [layoutType, setLayoutType] = useState<LayoutType>('hierarchical');
  const [assertionMode, setAssertionMode] = useState<AssertionMode>('asserted');
  const [activeTool, setActiveTool] = useState<Tool>('select');
  const searchInputRef = useRef<HTMLInputElement>(null);

  // ----- Data fetch -------------------------------------------------------
  const fetchGraphData = useCallback(async () => {
    if (!projectId || initialData) return;
    setLoading(true);
    setErrorMsg(null);
    try {
      const baseUrl = (window as unknown as { API_BASE_URL?: string }).API_BASE_URL ?? '';
      const url = `${baseUrl}/api/ontology/${projectId}/graph`;
      const token = typeof localStorage !== 'undefined' ? localStorage.getItem('authToken') : null;
      const response = await fetch(url, {
        headers: token ? { Authorization: `Bearer ${token}` } : {}
      });
      if (!response.ok) {
        throw new Error(`Failed to load graph (HTTP ${response.status})`);
      }
      const data: BackendGraphResponse = await response.json();
      const nextNodes = data.nodes ?? [];
      const nextEdges = data.edges ?? [];
      setNodes(nextNodes);
      setEdges(nextEdges);
      if (data.ontology) setOntologyMeta(data.ontology);
      if (data.assertedEdges) {
        setAssertedEdges(data.assertedEdges);
      } else if (data.assertedEdgeIds) {
        const ids = new Set(data.assertedEdgeIds);
        setAssertedEdges(nextEdges.filter(e => ids.has(e.id)));
      }
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Unknown error');
    } finally {
      setLoading(false);
    }
  }, [projectId, initialData]);

  useEffect(() => {
    if (initialData) return;
    void fetchGraphData();
  }, [fetchGraphData, initialData]);

  // Sync external prop changes.
  useEffect(() => {
    if (assertedEdgesProp) setAssertedEdges(assertedEdgesProp);
  }, [assertedEdgesProp]);
  useEffect(() => {
    if (ontologyName) setOntologyMeta(prev => ({ ...prev, name: ontologyName }));
  }, [ontologyName]);

  // ----- Filtering: assertion mode + search ------------------------------
  const inferredOnlyEdgeIds = useMemo(() => {
    if (!assertedEdges) return new Set<string>();
    const assertedKey = new Set<string>();
    for (const e of assertedEdges) assertedKey.add(`${e.from}>${e.to}>${e.type}`);
    const out = new Set<string>();
    for (const e of edges) {
      if (!assertedKey.has(`${e.from}>${e.to}>${e.type}`)) out.add(e.id);
    }
    return out;
  }, [edges, assertedEdges]);

  const visibleEdges = useMemo(() => {
    if (assertionMode === 'all') return edges;
    if (!assertedEdges) return edges;
    if (assertionMode === 'asserted') return assertedEdges;
    return edges.filter(e => inferredOnlyEdgeIds.has(e.id));
  }, [edges, assertedEdges, assertionMode, inferredOnlyEdgeIds]);

  const matchedNodeIds = useMemo(() => {
    if (!searchTerm) return null;
    const q = searchTerm.toLowerCase();
    const out = new Set<string>();
    for (const node of nodes) {
      if (
        (node.label ?? '').toLowerCase().includes(q) ||
        node.id.toLowerCase().includes(q) ||
        (node.description ?? '').toLowerCase().includes(q)
      ) {
        out.add(node.id);
      }
    }
    return out;
  }, [nodes, searchTerm]);

  // ----- Vis network instance --------------------------------------------
  useEffect(() => {
    if (!containerRef.current) return;
    if (nodes.length === 0) return;

    const visNodes: Node[] = nodes.map(node => {
      const isMatch = !matchedNodeIds || matchedNodeIds.has(node.id);
      return {
        id: node.id,
        label: node.label || extractLocalName(node.id),
        title: createNodeTooltip(node),
        shape: NODE_SHAPES[node.type] ?? 'dot',
        color: {
          background: NODE_COLORS[node.type] ?? '#D3D3D3',
          border: '#000000',
          highlight: { background: NODE_COLORS[node.type] ?? '#D3D3D3', border: '#22c55e' },
          hover: { background: NODE_COLORS[node.type] ?? '#D3D3D3', border: '#2563eb' }
        },
        opacity: isMatch ? 1 : 0.18,
        size: 25,
        borderWidth: 2,
        borderWidthSelected: 3,
        font: { size: 14, color: '#000000', face: 'Arial', bold: { color: '#000000' } }
      };
    });

    const visEdges: Edge[] = visibleEdges.map(edge => {
      const inferredOnly = inferredOnlyEdgeIds.has(edge.id);
      const style = getEdgeStyle(edge.type, inferredOnly);
      const endpointVisible =
        (!matchedNodeIds || matchedNodeIds.has(edge.from)) &&
        (!matchedNodeIds || matchedNodeIds.has(edge.to));
      return {
        id: edge.id,
        from: edge.from,
        to: edge.to,
        label: edge.label || edge.type || '',
        ...style,
        font: {
          size: 11,
          color: inferredOnly ? '#b45309' : '#333333',
          align: 'middle',
          background: inferredOnly ? '#fef3c7' : '#ffffff'
        },
        width: 2,
        smooth: {
          enabled: true,
          type: 'cubicBezier',
          forceDirection: HIERARCHY_LAYOUTS.has(layoutType) ? 'vertical' : 'none',
          roundness: 0.4
        },
        // Dim non-matching edges when search is active.
        color: endpointVisible ? style.color : '#cbd5e1'
      } as Edge;
    });

    // Pre-compute custom positions for radial / circular.
    if (PRECOMPUTED_LAYOUTS.has(layoutType) && containerRef.current) {
      const width = containerRef.current.clientWidth || 800;
      const height = containerRef.current.clientHeight || 600;
      const layoutNodes = nodes.map(n => ({ ...n }));
      const layoutEdges = visibleEdges.map(e => ({ ...e }));
      const positions =
        layoutType === 'radial'
          ? applyRadialLayout(layoutNodes, layoutEdges, { width, height })
          : applyCircularLayout(layoutNodes, layoutEdges, { width, height });
      for (const visNode of visNodes) {
        const pos = positions.get(visNode.id as string);
        if (pos) {
          (visNode as Node & { x: number; y: number; fixed?: boolean }).x = pos.x;
          (visNode as Node & { x: number; y: number; fixed?: boolean }).y = pos.y;
          (visNode as Node & { x: number; y: number; fixed?: boolean }).fixed = true;
        }
      }
    }

    const layoutOptions = buildLayoutOptions(layoutType);
    const isHierarchical = HIERARCHY_LAYOUTS.has(layoutType);
    const isCustom = PRECOMPUTED_LAYOUTS.has(layoutType);

    const options: Options = {
      autoResize: true,
      layout: layoutOptions,
      physics: {
        enabled: !isHierarchical && !isCustom,
        barnesHut: {
          gravitationalConstant: layoutType === 'spring' ? -3000 : -2000,
          springConstant: layoutType === 'spring' ? 0.08 : 0.04,
          springLength: layoutType === 'spring' ? 200 : 150,
          damping: 0.09,
          centralGravity: 0.3
        },
        stabilization: { iterations: 200, updateInterval: 25 }
      },
      interaction: {
        hover: true,
        zoomView: true,
        // Always allow canvas pan; the active tool only changes the cursor and
        // the click-vs-drag-to-select default — we want pan to work in both.
        dragView: true,
        dragNodes: activeTool !== 'pan',
        tooltipDelay: 100,
        hideEdgesOnDrag: true,
        hideEdgesOnZoom: true,
        multiselect: true
      },
      nodes: {
        borderWidth: 2,
        borderWidthSelected: 4
      },
      edges: {
        width: 2,
        selectionWidth: 4
      }
    };

    const network = new Network(
      containerRef.current,
      { nodes: visNodes, edges: visEdges } as Data,
      options
    );
    networkRef.current = network;

    network.on('selectNode', (params: { nodes: Array<string> }) => {
      const nodeId = params.nodes[0];
      const node = nodes.find(n => n.id === nodeId);
      if (node) setSelectedNode(node);
    });
    network.on('deselectNode', () => setSelectedNode(null));
    network.on('hoverNode', (params: { node: string; event: { center: { x: number; y: number } } }) => {
      const node = nodes.find(n => n.id === params.node);
      if (node) {
        setHoveredNode(node);
        if (containerRef.current) {
          const canvasPos = network.canvasToDOM({
            x: params.event.center.x,
            y: params.event.center.y
          });
          setTooltipPosition({ x: canvasPos.x, y: canvasPos.y });
        }
      }
    });
    network.on('blurNode', () => {
      setHoveredNode(null);
      setTooltipPosition(null);
    });

    return () => {
      network.destroy();
      networkRef.current = null;
    };
  }, [nodes, visibleEdges, matchedNodeIds, layoutType, activeTool, inferredOnlyEdgeIds]);

  // ----- Selection sync ---------------------------------------------------
  useEffect(() => {
    if (!networkRef.current) return;
    if (!selectedNode) return;
    networkRef.current.selectNodes([selectedNode.id], false);
  }, [selectedNode]);

  // ----- Focus / show / mutation handlers --------------------------------
  const focusNode = useCallback(
    (nodeId: string) => {
      const network = networkRef.current;
      if (!network) return;
      network.selectNodes([nodeId]);
      network.focus(nodeId, { scale: 1.5, animation: { duration: 400, easingFunction: 'easeInOutCubic' } });
    },
    []
  );

  const handleShowInGraph = useCallback(
    (node: OntologyNode) => {
      setSelectedNode(node);
      focusNode(node.id);
    },
    [focusNode]
  );

  const handleShowSubclasses = useCallback(
    (node: OntologyNode) => {
      const network = networkRef.current;
      if (!network) return;
      const subIds = visibleEdges
        .filter(e => e.to === node.id && (e.type === 'subClassOf' || e.type === 'subPropertyOf'))
        .map(e => e.from);
      const ids = [node.id, ...subIds];
      network.selectNodes(ids);
      network.fit({ nodes: ids, animation: { duration: 400, easingFunction: 'easeInOutCubic' } });
    },
    [visibleEdges]
  );

  const handleShowSuperclasses = useCallback(
    (node: OntologyNode) => {
      const network = networkRef.current;
      if (!network) return;
      const superIds = visibleEdges
        .filter(e => e.from === node.id && (e.type === 'subClassOf' || e.type === 'subPropertyOf'))
        .map(e => e.to);
      const ids = [node.id, ...superIds];
      network.selectNodes(ids);
      network.fit({ nodes: ids, animation: { duration: 400, easingFunction: 'easeInOutCubic' } });
    },
    [visibleEdges]
  );

  const handleShowIndividuals = useCallback(
    (node: OntologyNode) => {
      const network = networkRef.current;
      if (!network) return;
      const individualIds = visibleEdges
        .filter(e => e.to === node.id && e.type === 'instanceOf')
        .map(e => e.from);
      if (individualIds.length === 0) return;
      const ids = [node.id, ...individualIds];
      network.selectNodes(ids);
      network.fit({ nodes: ids, animation: { duration: 400, easingFunction: 'easeInOutCubic' } });
    },
    [visibleEdges]
  );

  // ----- Toolbar handlers -------------------------------------------------
  const handleZoomIn = useCallback(() => {
    const net = networkRef.current;
    if (!net) return;
    net.moveTo({ scale: net.getScale() * 1.2, animation: { duration: 300 } });
  }, []);
  const handleZoomOut = useCallback(() => {
    const net = networkRef.current;
    if (!net) return;
    net.moveTo({ scale: net.getScale() * 0.8, animation: { duration: 300 } });
  }, []);
  const handleFit = useCallback(() => {
    networkRef.current?.fit({ animation: { duration: 500, easingFunction: 'easeInOutQuad' } });
  }, []);

  const handleExportPng = useCallback(() => {
    if (!containerRef.current) return;
    const canvas = containerRef.current.querySelector('canvas');
    if (!canvas) return;
    const link = document.createElement('a');
    link.download = `ontograf-${projectId}-${Date.now()}.png`;
    link.href = (canvas as HTMLCanvasElement).toDataURL('image/png');
    link.click();
  }, [projectId]);

  const handleExportData = useCallback(() => {
    const payload = {
      ontology: ontologyMeta,
      assertionMode,
      nodes,
      edges: visibleEdges
    };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.download = `ontograf-${projectId}-${Date.now()}.json`;
    link.href = url;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }, [nodes, visibleEdges, ontologyMeta, projectId, assertionMode]);

  const handleCopyView = useCallback(() => {
    if (!selectedNode) return;
    const text = selectedNode.id;
    if (navigator.clipboard) {
      void navigator.clipboard.writeText(text);
    }
  }, [selectedNode]);

  const handleSaveView = useCallback(() => {
    const net = networkRef.current;
    if (!net) return;
    const positions = net.getPositions();
    try {
      localStorage.setItem(
        `ontograf-view-${projectId}`,
        JSON.stringify({ positions, layoutType, assertionMode })
      );
    } catch {
      /* storage may be unavailable in webview sandbox */
    }
  }, [projectId, layoutType, assertionMode]);

  const handleDeleteSelected = useCallback(() => {
    if (readonly) return;
    if (!selectedNode) return;
    setNodes(prev => prev.filter(n => n.id !== selectedNode.id));
    setEdges(prev => prev.filter(e => e.from !== selectedNode.id && e.to !== selectedNode.id));
    setSelectedNode(null);
  }, [readonly, selectedNode]);

  // ----- Keyboard shortcuts (component-scoped) ---------------------------
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    const handler = (e: KeyboardEvent): void => {
      const target = e.target as HTMLElement | null;
      // Ignore typing inside text inputs.
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
        return;
      }
      const meta = e.ctrlKey || e.metaKey;
      if (meta && e.key.toLowerCase() === 'f') {
        e.preventDefault();
        searchInputRef.current?.focus();
      } else if (meta && e.key.toLowerCase() === 'r') {
        e.preventDefault();
        void fetchGraphData();
      } else if (meta && e.key.toLowerCase() === 'e') {
        e.preventDefault();
        handleExportPng();
      } else if (meta && e.key.toLowerCase() === '0') {
        e.preventDefault();
        handleFit();
      } else if (meta && (e.key === '+' || e.key === '=')) {
        e.preventDefault();
        handleZoomIn();
      } else if (meta && e.key === '-') {
        e.preventDefault();
        handleZoomOut();
      } else if (e.key === 'Delete' && !readonly) {
        e.preventDefault();
        handleDeleteSelected();
      } else if (e.key === 'Escape') {
        setSearchTerm('');
        setShowLayoutMenu(false);
        setSelectedNode(null);
      }
    };
    root.addEventListener('keydown', handler);
    return () => root.removeEventListener('keydown', handler);
  }, [
    fetchGraphData,
    handleExportPng,
    handleFit,
    handleZoomIn,
    handleZoomOut,
    handleDeleteSelected,
    readonly
  ]);

  // ----- Title computed from ontology meta -------------------------------
  const sidebarTitle = useMemo(() => {
    if (ontologyMeta.name) return `Class hierarchy: ${ontologyMeta.name}`;
    if (ontologyMeta.iri) return `Class hierarchy: ${extractLocalName(ontologyMeta.iri)}`;
    return 'Class hierarchy';
  }, [ontologyMeta]);

  // ----- Render ----------------------------------------------------------
  return (
    <div
      ref={rootRef}
      tabIndex={0}
      style={{
        width: '100%',
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        backgroundColor: '#f5f5f5',
        fontFamily: 'system-ui, -apple-system, "Segoe UI", Arial, sans-serif',
        outline: 'none'
      }}
    >
      {/* Top toolbar */}
      <div
        style={{
          backgroundColor: '#ffffff',
          borderBottom: '1px solid #d4d4d8',
          padding: '6px 12px',
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          boxShadow: '0 1px 2px rgba(0,0,0,0.04)'
        }}
      >
        <select
          value={assertionMode}
          onChange={(e) => setAssertionMode(e.target.value as AssertionMode)}
          style={topbarSelectStyle}
          title="Switch between asserted-only edges, inferred-only edges, and both"
        >
          <option value="asserted">Asserted</option>
          <option value="inferred" disabled={!assertedEdges}>Inferred</option>
          <option value="all">All</option>
        </select>

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            flex: 1,
            maxWidth: 420,
            border: '1px solid #d4d4d8',
            borderRadius: 4,
            backgroundColor: '#ffffff',
            padding: '2px 8px'
          }}
        >
          <Search size={14} color="#64748b" />
          <input
            ref={searchInputRef}
            type="text"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder="Search… (Ctrl/Cmd+F)"
            aria-label="Search graph"
            style={{
              border: 'none',
              outline: 'none',
              flex: 1,
              padding: '4px 6px',
              fontSize: 13,
              background: 'transparent'
            }}
          />
          {searchTerm && (
            <button
              type="button"
              onClick={() => setSearchTerm('')}
              title="Clear"
              style={iconBtnGhost}
            >
              <X size={12} />
            </button>
          )}
        </div>

        <div style={{ flex: 1 }} />

        {errorMsg && (
          <span style={{ color: '#b91c1c', fontSize: 12 }} title={errorMsg}>
            ⚠ {errorMsg}
          </span>
        )}

        <button onClick={handleZoomIn} title="Zoom in (Ctrl/Cmd +)" style={iconBtn}>
          <ZoomIn size={16} />
        </button>
        <button onClick={handleZoomOut} title="Zoom out (Ctrl/Cmd -)" style={iconBtn}>
          <ZoomOut size={16} />
        </button>
        <button onClick={handleFit} title="Fit to screen (Ctrl/Cmd 0)" style={iconBtn}>
          <Maximize2 size={16} />
        </button>
        <button onClick={handleExportPng} title="Export as PNG (Ctrl/Cmd E)" style={iconBtn}>
          <Download size={16} />
        </button>
        <button
          onClick={() => void fetchGraphData()}
          disabled={loading}
          title="Refresh (Ctrl/Cmd R)"
          style={iconBtn}
        >
          <RefreshCw
            size={16}
            style={{ animation: loading ? 'ontograf-spin 1s linear infinite' : 'none' }}
          />
        </button>
        <button
          onClick={() => setShowClassTree(prev => !prev)}
          title="Toggle class hierarchy"
          style={{
            ...iconBtn,
            backgroundColor: showClassTree ? '#2563eb' : '#ffffff',
            color: showClassTree ? '#ffffff' : 'inherit'
          }}
        >
          <Grid size={16} />
        </button>
      </div>

      {/* Secondary toolbar — tools and layout */}
      <div
        style={{
          backgroundColor: '#f8fafc',
          borderBottom: '1px solid #e4e4e7',
          padding: '4px 8px',
          display: 'flex',
          gap: 4,
          alignItems: 'center'
        }}
      >
        {([
          { id: 'select' as Tool, icon: <MousePointer size={14} />, title: 'Select tool' },
          { id: 'pan' as Tool, icon: <Hand size={14} />, title: 'Pan tool' }
        ]).map(tool => (
          <button
            key={tool.id}
            onClick={() => setActiveTool(tool.id)}
            title={tool.title}
            style={{
              ...iconBtnSmall,
              backgroundColor: activeTool === tool.id ? '#dbeafe' : '#ffffff',
              borderColor: activeTool === tool.id ? '#3b82f6' : '#d4d4d8'
            }}
          >
            {tool.icon}
          </button>
        ))}

        <div style={{ width: 1, height: 18, background: '#e4e4e7', margin: '0 4px' }} />

        {!readonly && selectedNode && (
          <button
            onClick={handleDeleteSelected}
            title="Delete selected (Del)"
            style={{ ...iconBtnSmall, color: '#b91c1c' }}
          >
            <Trash2 size={14} />
          </button>
        )}

        <button onClick={handleZoomIn} title="Zoom in" style={iconBtnSmall}>
          <ZoomIn size={14} />
        </button>
        <button onClick={handleZoomOut} title="Zoom out" style={iconBtnSmall}>
          <ZoomOut size={14} />
        </button>
        <button onClick={handleFit} title="Fit" style={iconBtnSmall}>
          <Maximize2 size={14} />
        </button>

        <div style={{ width: 1, height: 18, background: '#e4e4e7', margin: '0 4px' }} />

        <div style={{ position: 'relative' }}>
          <button
            onClick={() => setShowLayoutMenu(prev => !prev)}
            title="Layout"
            style={{
              ...iconBtnSmall,
              backgroundColor: showLayoutMenu ? '#dbeafe' : '#ffffff'
            }}
          >
            <Layers size={14} />
          </button>
          {showLayoutMenu && (
            <div
              style={{
                position: 'absolute',
                top: '100%',
                left: 0,
                marginTop: 2,
                backgroundColor: '#ffffff',
                border: '1px solid #d4d4d8',
                borderRadius: 4,
                boxShadow: '0 6px 20px rgba(15,23,42,0.12)',
                zIndex: 1000,
                minWidth: 180,
                padding: '4px 0'
              }}
              onMouseLeave={() => setShowLayoutMenu(false)}
            >
              {(
                [
                  { key: 'hierarchical', label: 'Hierarchical' },
                  { key: 'tree-vertical', label: 'Tree (vertical)' },
                  { key: 'tree-horizontal', label: 'Tree (horizontal)' },
                  { key: 'radial', label: 'Radial' },
                  { key: 'spring', label: 'Spring (force)' },
                  { key: 'force', label: 'Force-directed' },
                  { key: 'circular', label: 'Circular' }
                ] as Array<{ key: LayoutType; label: string }>
              ).map(item => (
                <button
                  key={item.key}
                  onClick={() => {
                    setLayoutType(item.key);
                    setShowLayoutMenu(false);
                  }}
                  style={{
                    width: '100%',
                    padding: '6px 14px',
                    border: 'none',
                    background: layoutType === item.key ? '#eef2ff' : 'transparent',
                    textAlign: 'left',
                    cursor: 'pointer',
                    fontSize: 13,
                    fontWeight: layoutType === item.key ? 600 : 400,
                    color: '#0f172a'
                  }}
                >
                  {item.label}
                </button>
              ))}
            </div>
          )}
        </div>

        <button onClick={() => setShowClassTree(prev => !prev)} title="Filters" style={iconBtnSmall}>
          <Filter size={14} />
        </button>
        <button
          onClick={handleSaveView}
          title="Save view (positions + layout)"
          style={iconBtnSmall}
        >
          <Save size={14} />
        </button>
        <button onClick={handleCopyView} title="Copy selected IRI" style={iconBtnSmall}>
          <Copy size={14} />
        </button>
        <button onClick={handleExportPng} title="Export image" style={iconBtnSmall}>
          <ImageIcon size={14} />
        </button>
        <button onClick={handleExportData} title="Export data (JSON)" style={iconBtnSmall}>
          <FileText size={14} />
        </button>

        <div style={{ flex: 1 }} />

        <span style={{ fontSize: 11, color: '#64748b' }}>
          {nodes.length} nodes · {visibleEdges.length} edges
          {assertionMode === 'inferred' && (
            <span style={{ color: '#b45309', marginLeft: 6, fontWeight: 600 }}>· inferred only</span>
          )}
        </span>
      </div>

      {/* Main content */}
      <div style={{ flex: 1, display: 'flex', overflow: 'hidden', minHeight: 0 }}>
        {showClassTree && (
          <div style={{ width: 320, flexShrink: 0, height: '100%' }}>
            <ClassHierarchyPanel
              nodes={nodes}
              edges={edges}
              assertedEdges={assertedEdges}
              title={sidebarTitle}
              selectedNodeId={selectedNode?.id ?? null}
              onSelect={(node) => setSelectedNode(node)}
              onActivate={(node) => handleShowInGraph(node)}
              onShowInGraph={handleShowInGraph}
              onFocusInGraph={handleShowInGraph}
              onShowSubclasses={handleShowSubclasses}
              onShowSuperclasses={handleShowSuperclasses}
              onShowIndividuals={handleShowIndividuals}
              onDelete={readonly ? undefined : (n) => {
                setSelectedNode(n);
                handleDeleteSelected();
              }}
              readonly={readonly}
              initialAssertionMode={assertionMode}
            />
          </div>
        )}

        <div style={{ flex: 1, position: 'relative', backgroundColor: '#ffffff' }}>
          <div ref={containerRef} style={{ width: '100%', height: '100%' }} />

          {hoveredNode && tooltipPosition && (
            <div
              style={{
                position: 'absolute',
                left: Math.min(tooltipPosition.x + 12, (containerRef.current?.clientWidth ?? 0) - 320),
                top: Math.min(tooltipPosition.y + 12, (containerRef.current?.clientHeight ?? 0) - 200),
                backgroundColor: '#FFFACD',
                border: '1px solid #1e293b',
                borderRadius: 4,
                padding: '8px 12px',
                boxShadow: '0 6px 16px rgba(0,0,0,0.18)',
                zIndex: 1000,
                maxWidth: 320,
                fontSize: 12,
                pointerEvents: 'none'
              }}
            >
              <div style={{ fontWeight: 600, marginBottom: 4 }}>
                {hoveredNode.label || hoveredNode.id}
              </div>
              <div style={{ color: '#475569', fontSize: 11, lineHeight: 1.4 }}>
                <div><strong>IRI:</strong> {hoveredNode.id}</div>
                <div><strong>Type:</strong> {hoveredNode.type}</div>
                {hoveredNode.description && (
                  <div style={{ marginTop: 4 }}>{hoveredNode.description}</div>
                )}
              </div>
            </div>
          )}

          {!assertedEdges && (
            <div
              role="status"
              style={{
                position: 'absolute',
                bottom: 0,
                left: 0,
                right: 0,
                backgroundColor: '#FEF3C7',
                borderTop: '1px solid #FCD34D',
                padding: '6px 12px',
                fontSize: 12,
                color: '#92400E',
                display: 'flex',
                alignItems: 'center',
                gap: 8
              }}
            >
              <Settings size={14} />
              <span>
                No reasoner output supplied — inferred view is unavailable. Run a reasoner from the
                Reasoner menu to enable inferred-edge highlighting.
              </span>
            </div>
          )}

          {loading && (
            <div
              style={{
                position: 'absolute',
                inset: 0,
                backgroundColor: 'rgba(255,255,255,0.85)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                zIndex: 100
              }}
            >
              <div style={{ textAlign: 'center' }}>
                <RefreshCw size={28} style={{ animation: 'ontograf-spin 1s linear infinite' }} />
                <div style={{ marginTop: 8, fontSize: 13, color: '#475569' }}>Loading graph…</div>
              </div>
            </div>
          )}
        </div>
      </div>

      <style>{`
        @keyframes ontograf-spin {
          from { transform: rotate(0deg); }
          to { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  );
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function buildLayoutOptions(layout: LayoutType): Options['layout'] {
  if (layout === 'hierarchical' || layout === 'tree-vertical') {
    return {
      hierarchical: {
        enabled: true,
        direction: 'UD',
        sortMethod: 'directed',
        levelSeparation: 150,
        nodeSpacing: 200,
        treeSpacing: 200,
        blockShifting: true,
        edgeMinimization: true,
        parentCentralization: true
      }
    };
  }
  if (layout === 'tree-horizontal') {
    return {
      hierarchical: {
        enabled: true,
        direction: 'LR',
        sortMethod: 'directed',
        levelSeparation: 200,
        nodeSpacing: 100,
        treeSpacing: 150,
        blockShifting: true,
        edgeMinimization: true,
        parentCentralization: true
      }
    };
  }
  return { randomSeed: 2 };
}

function getEdgeStyle(
  type: EdgeType | string,
  inferredOnly: boolean
): { dashes: false | number[]; color: string; arrows: { to: { enabled: boolean; type?: string } } } {
  if (inferredOnly) {
    // Mimic Protege's inferred highlight: dashed yellow.
    return {
      dashes: [4, 3],
      color: '#b45309',
      arrows: { to: { enabled: true, type: 'arrow' } }
    };
  }
  switch (type) {
    case 'subClassOf':
    case 'subPropertyOf':
      return { dashes: false, color: '#1f2937', arrows: { to: { enabled: true, type: 'arrow' } } };
    case 'instanceOf':
      return { dashes: [5, 5], color: '#475569', arrows: { to: { enabled: true, type: 'arrow' } } };
    case 'equivalentClass':
      return { dashes: [2, 2], color: '#dc2626', arrows: { to: { enabled: false } } };
    case 'disjointWith':
      return { dashes: [10, 5], color: '#b91c1c', arrows: { to: { enabled: false } } };
    case 'domain':
      return { dashes: false, color: '#0d9488', arrows: { to: { enabled: true, type: 'arrow' } } };
    case 'range':
      return { dashes: false, color: '#7c3aed', arrows: { to: { enabled: true, type: 'arrow' } } };
    case 'inverseOf':
      return { dashes: [4, 2], color: '#ea580c', arrows: { to: { enabled: false } } };
    default:
      return { dashes: false, color: '#4338ca', arrows: { to: { enabled: true, type: 'arrow' } } };
  }
}

function createNodeTooltip(node: OntologyNode): string {
  const safeLabel = escapeHtml(node.label || node.id);
  let tip = `<div style="padding:8px;max-width:300px;font-family:system-ui;">`;
  tip += `<strong>${safeLabel}</strong><br/>`;
  tip += `<div style="font-size:11px;color:#475569;margin-top:4px;line-height:1.45;">`;
  tip += `<strong>IRI:</strong> ${escapeHtml(node.id)}<br/>`;
  tip += `<strong>Type:</strong> ${escapeHtml(node.type as string)}`;
  if (node.description) {
    tip += `<div style="margin-top:4px;">${escapeHtml(node.description)}</div>`;
  }
  if (node.superClasses && node.superClasses.length > 0) {
    tip += `<div style="margin-top:4px;"><strong>Superclasses:</strong>`;
    for (const sc of node.superClasses.slice(0, 5)) {
      tip += `<div style="padding-left:8px;">${escapeHtml(extractLocalName(sc))}</div>`;
    }
    if (node.superClasses.length > 5) {
      tip += `<div style="padding-left:8px;">…and ${node.superClasses.length - 5} more</div>`;
    }
    tip += `</div>`;
  }
  tip += `</div></div>`;
  return tip;
}

function extractLocalName(iri: string): string {
  if (!iri) return '';
  const hashIdx = iri.lastIndexOf('#');
  if (hashIdx >= 0) return iri.slice(hashIdx + 1);
  const slashIdx = iri.lastIndexOf('/');
  if (slashIdx >= 0) return iri.slice(slashIdx + 1);
  return iri;
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

// Re-export for convenience.
export type { NodeType };

const topbarSelectStyle: React.CSSProperties = {
  padding: '4px 10px',
  border: '1px solid #d4d4d8',
  borderRadius: 4,
  fontSize: 13,
  backgroundColor: '#ffffff'
};

const iconBtn: React.CSSProperties = {
  padding: '6px',
  border: '1px solid #d4d4d8',
  borderRadius: 4,
  backgroundColor: '#ffffff',
  cursor: 'pointer',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center'
};

const iconBtnSmall: React.CSSProperties = {
  padding: '4px 6px',
  border: '1px solid #d4d4d8',
  borderRadius: 3,
  backgroundColor: '#ffffff',
  cursor: 'pointer',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  transition: 'all 0.15s ease'
};

const iconBtnGhost: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  padding: 4,
  cursor: 'pointer',
  display: 'flex'
};

export default ProtegeStyleGraphView;

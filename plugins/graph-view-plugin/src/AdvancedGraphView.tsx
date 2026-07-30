import React, { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import * as d3 from 'd3';
import {
  RefreshCw,
  ZoomIn,
  ZoomOut,
  Maximize2,
  Search,
  Filter,
  Settings,
  FileText,
  Download,
  AlertTriangle,
  Edit3,
  Zap,
  Grid,
  LayoutGrid,
  Orbit,
  Home,
  Camera,
  Box,
  GripVertical,
  Minus,
  X,
  ChevronUp,
  ChevronDown,
  ChevronRight,
  Plus,
  GitBranch,
  Trash2,
  Maximize,
  MinusSquare,
  Save,
  TrendingUp,
  Crosshair
} from 'lucide-react';
import type {
  OntologyNode,
  OntologyEdge,
  GraphSettings,
  GraphFilters,
  NodeType,
  EdgeType,
  ExportFormat,
  VisualizationType
} from './types';
import PluginUpdateService from './PluginUpdateService';
import { authHeaders } from './utils/authHeaders';
import { NODE_ACCENTS, nodeFill, nodeStroke } from './utils/nodePalette';
import { vowlNotationService } from './services/VOWLNotationService';
import { UnifiedSidebar } from './components/UnifiedSidebar';
import { GraphViewSidebar } from './components/GraphViewSidebar';
import { LocalGraphView } from './components/LocalGraphView';
import { AnalyticsPanel } from './components/AnalyticsPanel';
import { computeGraphAnalytics, getClusterColor } from './services/GraphAnalyticsService';
import { createGraphDataFetchService } from './services/GraphDataFetchService';
import { MatrixView } from './components/MatrixView';
import * as layouts from './layouts';
import {
  getRootNodes,
  getChildren,
  getParents,
  hasChildren,
  toggleNodeExpansion as toggleExpansion,
  searchNodesWithPaths,
  expandAll as expandAllNodes,
  collapseAll as collapseAllNodes,
  initialGraphVisibility,
  smartInitialGraphVisibility,
  getExpansionStats,
  findPathToNode
} from './HierarchicalLazyLoading';
import { useIsDarkTheme } from './hooks/useIsDarkTheme';
import { usePrefersReducedMotion } from './hooks/usePrefersReducedMotion';
import { loadLastView, saveLastView, loadUiPrefs, saveUiPrefs, OntographLayoutType, VowlDisplayOptions, DEFAULT_VOWL_OPTIONS } from './viewMemory';
import { applyVowlTransform, isThingIri, vowlOriginalNodeId, buildVowlNeighborhoods, placeVowlNeighborhoods } from './vowlTransform';
import { GraphToolbar, RELATIONSHIP_VISIBILITY_CONTROLS } from './components/GraphToolbar';
import { WebGLGraphView, buildBenchmarkData } from './renderers/WebGLGraphView';
import { isWebGLAvailable } from './renderers/graphAdapter';

// D3 types
interface D3Node extends OntologyNode, d3.SimulationNodeDatum {
  x?: number;
  y?: number;
  z?: number;
  projectedX?: number;
  projectedY?: number;
  depthScale?: number;
  fx?: number | null;
  fy?: number | null;
  vx?: number;
  vy?: number;
}

interface D3Edge extends OntologyEdge {
  source: D3Node | string;
  target: D3Node | string;
}

interface AdvancedGraphViewProps {
  projectId: string;
  context?: any;
  onNodeClick?: (nodeId: string) => void;
  onEdgeClick?: (edgeId: string) => void;
  readonly?: boolean;
}

type AssertionViewMode = 'asserted' | 'inferred' | 'all';

// Node count above which large-graph LOD/culling behaviors activate (all visualization modes)
const LARGE_GRAPH_THRESHOLD = 800;
// Entrance animation tiers (visible node counts): full staggered bloom, calmer
// single fade, or static (instant) above that.
const BLOOM_MAX_VISIBLE = 300;
const CALM_MAX_VISIBLE = 1500;
// Camera feel: one duration/easing for every programmatic camera move
const CAMERA_MS = 650;
// rAF-interpolated wheel zoom (flip to false to fall back to native d3 wheel steps)
const SMOOTH_WHEEL_ZOOM = true;

// Type normalization helpers
const normalizeNodeType = (type: string): NodeType => {
  if (!type) return 'class';
  const normalized = type === 'CLASS' ? 'class' :
        type === 'INDIVIDUAL' ? 'individual' :
        type === 'PROPERTY' ? 'property' :
        type === 'DATA_PROPERTY' ? 'dataProperty' :
        type === 'OBJECT_PROPERTY' ? 'objectProperty' :
        type === 'ANNOTATION' ? 'annotation' :
        type === 'DATATYPE' ? 'datatype' :
        type.toLowerCase();
  return normalized as NodeType;
};

const normalizeEdgeType = (type: string): EdgeType => {
  if (!type) return 'custom';
  let normalized = type;
  if (type === 'SUBCLASS_OF') normalized = 'subClassOf';
  else if (type === 'INSTANCE_OF') normalized = 'instanceOf';
  else if (type === 'PROPERTY_RELATION') normalized = 'propertyRelation';
  else if (type === 'EQUIVALENT_CLASS') normalized = 'equivalentClass';
  else if (type === 'DISJOINT_WITH') normalized = 'disjointWith';
  else if (type.includes('_')) {
    normalized = type.replace(/_([a-z])/gi, (_match: string, letter: string) => letter.toUpperCase())
                     .replace(/^[A-Z]/, (c: string) => c.toLowerCase());
  }
  return normalized as EdgeType;
};

// Color schemes — all node color derives from the validated NODE_ACCENTS
// palette (utils/nodePalette). One entity type = one hue, everywhere:
// fill, stroke, legend, tooltip, selection glow.
const TYPE_COLORS: Record<NodeType, string> = {
  class: NODE_ACCENTS.class,
  individual: NODE_ACCENTS.individual,
  property: NODE_ACCENTS.property,
  dataProperty: NODE_ACCENTS.dataProperty,
  objectProperty: NODE_ACCENTS.objectProperty,
  annotation: NODE_ACCENTS.annotation,
  datatype: NODE_ACCENTS.datatype,
  setOperator: NODE_ACCENTS.class // operator nodes are anonymous classes — class blue
};

// VOWL set-operator glyphs rendered inside operator nodes
const SET_OPERATOR_SYMBOLS: Record<string, string> = {
  union: '∪',
  intersection: '∩',
  complement: '¬',
  oneOf: '{…}'
};

const EDGE_TYPE_COLORS: Record<EdgeType, string> = {
  subClassOf: '#2563eb', // Blue for OntoCode style
  instanceOf: '#f59e0b', // Gold/Orange for OntoCode style
  propertyRelation: '#059669', // Green for properties
  equivalentClass: '#3b82f6', // Blue — was pink here / green in VOWLNotationService; unified
  disjointWith: '#f97316', // Orange — was red, paired with equivalentClass's old green (a
  // red/green combination color-vision-deficiency guidance warns against for two
  // opposite-meaning edge types); matches VOWLNotationService.edgeToVOWLEdge now
  domain: '#06b6d4',
  range: '#8b5cf6',
  inverseOf: '#fbbf24',
  custom: '#6b7280',
  temporal: '#34d399',
  spatial: '#3b82f6',
  probabilistic: '#fb923c',
  subPropertyOf: '#2563eb',
  operand: '#64748b', // set-operator member links (plain gray, no arrowhead)
  restriction: '#d97706', // someValuesFrom/allValuesFrom/hasValue/cardinality — amber
  propertyChain: '#ec4899' // owl:propertyChainAxiom composition — pink (was violet, same hue
  // as annotationProperty edges elsewhere and only distinguishable by dash spacing)
};

// Per-type accent colors shared by node rendering, selection styling, and tooltips
const ACCENT_COLORS: Record<string, string> = { ...NODE_ACCENTS };

const hexToRgba = (hex: string, alpha: number): string => {
  const c = d3.color(hex);
  if (!c) return `rgba(99,102,241,${alpha})`;
  const { r, g, b } = c.rgb();
  return `rgba(${r},${g},${b},${alpha})`;
};

// Picks black or white label text from a node's ACTUAL rendered fill (perceived
// brightness, ITU-R BT.601). Callers must pass the same color the shape was
// painted with — a mismatched guess (e.g. a raw accent instead of its pastel
// tint) silently produces unreadable white-on-pale-blue text.
const getReadableTextColor = (hex: string): string => {
  const clean = hex.replace('#', '');
  if (clean.length !== 6) return '#111111';
  const r = parseInt(clean.substring(0, 2), 16);
  const g = parseInt(clean.substring(2, 4), 16);
  const b = parseInt(clean.substring(4, 6), 16);
  const brightness = (r * 299 + g * 587 + b * 114) / 1000;
  return brightness > 140 ? '#111111' : '#ffffff';
};

// Evicts the graph-view sessionStorage cache the moment the ontology changes elsewhere
// (Code View save, entity-editor mutations, etc.) — registered at MODULE scope rather
// than inside the component's mount effect. Switching to another top-level tab (Code
// View, Entities, ...) fully unmounts this component (Dashboard.tsx renders exactly one
// active tab), so a mount-time listener would miss any mutation that happens while this
// view isn't the active tab — the very common "edit in Code View, switch back to Graph"
// path. A module-level listener stays registered for the whole page session regardless
// of mount state, so by the time the component next mounts, the stale cache is already
// gone and its plain (non-bypassing) initial fetch reads fresh data instead.
if (typeof window !== 'undefined') {
  window.addEventListener('ontology:mutated', ((e: CustomEvent) => {
    try {
      const mutatedProjectId = e?.detail?.projectId;
      if (mutatedProjectId) {
        sessionStorage.removeItem(`ontocode:graphView:${mutatedProjectId}`);
      } else {
        // No projectId on the event — clear every graph-view cache entry to be safe.
        Object.keys(sessionStorage)
          .filter((k) => k.startsWith('ontocode:graphView:'))
          .forEach((k) => sessionStorage.removeItem(k));
      }
    } catch {
      /* sessionStorage unavailable/full — non-fatal, next Refresh click still bypasses it */
    }
  }) as EventListener);
}

// Default settings
const DEFAULT_SETTINGS: GraphSettings = {
  layout: 'force',
  showLabels: true,
  showArrows: true,
  physics: true,
  nodeSize: 16,  // Increased for better visibility and expand icons
  edgeWidth: 1,
  showConfidence: false,
  showTemporal: false,
  showProvenance: false,
  colorByType: true,
  colorByConfidence: false,
  maxNodes: 5000,
  clusterNodes: false,
  lazyLoad: true,
  multiSelect: true,
  contextMenu: true,
  tooltips: true
};

const DEFAULT_FILTERS: GraphFilters = {
  nodeTypes: new Set(['class', 'individual', 'datatype', 'objectProperty', 'dataProperty', 'annotation', 'setOperator']), // All node types enabled by default
  edgeTypes: new Set(['subClassOf', 'instanceOf', 'propertyRelation', 'equivalentClass', 'domain', 'range', 'disjointWith', 'inverseOf', 'custom', 'subPropertyOf', 'operand'])
};


type HierarchyState = {
  visible: Set<string>;
  expanded: Set<string>;
};

type OntologyMutationOp = {
  type: string;
  iri: string;
  label?: string;
  parent?: string;
  property?: string;
  value?: string;
  target?: string;
  classIri?: string;
};

const extractNamespace = (iri?: string | null): string | null => {
  if (!iri) return null;
  const hashIndex = iri.lastIndexOf('#');
  if (hashIndex >= 0) {
    return iri.substring(0, hashIndex + 1);
  }
  const slashIndex = iri.lastIndexOf('/');
  if (slashIndex >= 0) {
    return iri.substring(0, slashIndex + 1);
  }
  return null;
};

const isLikelyAbsoluteIri = (value?: string | null): boolean => {
  if (!value) return false;
  return /^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(value);
};

const resolveNamespaceFromNode = (node?: OntologyNode | null): string | null => {
  if (!node) return null;
  const candidateIris: Array<string | null | undefined> = [
    node.metadata?.iri,
    node.metadata?.originalIri,
    node.metadata?.baseIri,
    node.metadata?.baseIRI,
    node.uri,
    node.id
  ];

  for (const candidate of candidateIris) {
    if (!candidate) continue;
    
    // Decode URL-encoded IRIs first
    let iriToUse = candidate;
    if (candidate.startsWith('http_') || candidate.startsWith('https_')) {
      iriToUse = candidate
        .replace(/^http___/, 'http://')
        .replace(/^https___/, 'https://')
        .replace(/_/g, '/');
    }
    
    const namespace = extractNamespace(iriToUse);
    if (isLikelyAbsoluteIri(namespace)) {
      return namespace;
    }
  }

  return null;
};

const hashToUnit = (value: string): number => {
  let hash = 0;
  for (let i = 0; i < value.length; i++) {
    hash = ((hash << 5) - hash + value.charCodeAt(i)) | 0;
  }
  return (Math.abs(hash) % 1000) / 1000;
};

const getNodeDegree = (nodeId: string, edges: OntologyEdge[]): number => {
  return edges.reduce((count, edge) => count + (edge.from === nodeId || edge.to === nodeId ? 1 : 0), 0);
};

/** Precomputed once per graph render — avoids O(N×E) inside force ticks. */
const buildDegreeMap = (edges: OntologyEdge[]): Map<string, number> => {
  const map = new Map<string, number>();
  for (const edge of edges) {
    map.set(edge.from, (map.get(edge.from) || 0) + 1);
    map.set(edge.to, (map.get(edge.to) || 0) + 1);
  }
  return map;
};

// Gate for noisy per-render diagnostics — enable via localStorage 'ontocode.graphView.debug' = 'true'
const GRAPH_DEBUG = typeof window !== 'undefined' && window.localStorage?.getItem('ontocode.graphView.debug') === 'true';
const graphLog = (...args: unknown[]) => {
  if (GRAPH_DEBUG) console.log(...args);
};

const isInferredEntity = (item: { metadata?: Record<string, any>; [key: string]: any }) => {
  return item?.metadata?.inferred === true || item?.metadata?.assertion === 'inferred' || item?.inferred === true || item?.isInferred === true;
};

const resolveNodeIri = (node?: OntologyNode | null): string | null => {
  if (!node) return null;
  const candidateIris: Array<string | null | undefined> = [
    node.metadata?.iri,
    node.metadata?.originalIri,
    node.metadata?.iriValue,
    node.uri,
    node.id
  ];

  for (const candidate of candidateIris) {
    if (!candidate) continue;
    if (isLikelyAbsoluteIri(candidate)) {
      return candidate;
    }
    
    // Handle URL-encoded IRIs (e.g., http___example_org_Class -> http://example.org/Class)
    if (candidate.startsWith('http_') || candidate.startsWith('https_')) {
      // Convert underscores back to proper IRI format
      const decoded = candidate
        .replace(/^http___/, 'http://')
        .replace(/^https___/, 'https://')
        .replace(/_/g, '/');
      
      if (isLikelyAbsoluteIri(decoded)) {
        console.log(`[resolveNodeIri] Decoded URL-encoded IRI: ${candidate} -> ${decoded}`);
        return decoded;
      }
    }
  }

  return null;
};

const dispatchHostEvent = (name: string, detail: Record<string, any>): boolean => {
  if (typeof window === 'undefined' || typeof window.dispatchEvent !== 'function') {
    return false;
  }
  try {
    window.dispatchEvent(new CustomEvent(name, { detail }));
    return true;
  } catch (eventError) {
    console.error(`[Graph Dialog] Failed to dispatch ${name}`, eventError);
    return false;
  }
};

type VowlLayoutRadii = {
  class: number;
  individual: number;
  datatype: number;
};

type VowlLayoutResult = {
  positions: Map<string, { x: number; y: number }>;
  radii: VowlLayoutRadii;
};

const computeVowlLayout = (
  nodes: D3Node[], 
  width: number, 
  height: number,
  classDistanceParam: number = 100,
  datatypeDistanceParam: number = 100
): VowlLayoutResult => {
  const safeWidth = Math.max(width, 1);
  const safeHeight = Math.max(height, 1);
  const centerX = safeWidth / 2;
  const centerY = safeHeight / 2;
  
  // Scale the radii based on the distance parameters
  // Range: 20-200 maps to 0.2-2.0 scale (100 is default)
  const classScale = Math.max(0.2, Math.min(2.0, classDistanceParam / 100));
  const datatypeScale = Math.max(0.2, Math.min(2.0, datatypeDistanceParam / 100));
  
  const baseMaxRadius = Math.max(120, Math.min(centerX, centerY) - 60);
  const maxRadius = baseMaxRadius * classScale;
  const classRadius = maxRadius * 0.85;
  const individualRadius = Math.max(80 * classScale, classRadius * 0.55);
  const datatypeRadius = Math.min(maxRadius * datatypeScale, classRadius * 1.25 * datatypeScale);

  const positions = new Map<string, { x: number; y: number }>();
  const classes = nodes.filter(node => node.type === 'class');
  const individuals = nodes.filter(node => node.type === 'individual');
  const datatypes = nodes.filter(node => node.type === 'datatype');

  const assignRing = (
    ringNodes: D3Node[],
    radius: number,
    span: number,
    offset: number
  ) => {
    if (ringNodes.length === 0) {
      return;
    }
    const step = ringNodes.length === 1 ? 0 : span / ringNodes.length;
    ringNodes.forEach((node, index) => {
      const angle = offset + (ringNodes.length === 1 ? 0 : index * step);
      const x = centerX + Math.cos(angle) * radius;
      const y = centerY + Math.sin(angle) * radius;
      positions.set(node.id, { x, y });
    });
  };

  assignRing(classes, classRadius, Math.PI * 2, -Math.PI / 2);
  assignRing(individuals, individualRadius, Math.PI * 1.8, -Math.PI / 2);
  assignRing(datatypes, datatypeRadius, Math.PI * 1.2, -Math.PI / 2 + Math.PI / 6);

  // Pin only a genuine shared Thing hub. Per-edge Thing clones from the VOWL
  // transform (id contains "__vowl__") must stay next to their single neighbor.
  const thingNode = classes.find(
    node => !node.id.includes('__vowl__') &&
      (node.label === 'Thing' || node.id === 'owl:Thing' || node.id.includes('owl#Thing'))
  );
  if (thingNode) {
    positions.set(thingNode.id, {
      x: centerX,
      y: Math.max(80, centerY - classRadius - 40)
    });
  }

  nodes.forEach((node, index) => {
    if (!positions.has(node.id)) {
      positions.set(node.id, {
        x: centerX + (index % 5) * 14 - 28,
        y: centerY + ((index % 7) - 3) * 12
      });
    }
  });

  return {
    positions,
    radii: {
      class: classRadius,
      individual: individualRadius,
      datatype: datatypes.length ? datatypeRadius : classRadius * 1.15
    }
  };
};

export const AdvancedGraphView: React.FC<AdvancedGraphViewProps> = ({
  projectId,
  context,
  onNodeClick,
  onEdgeClick,
  readonly = false
}) => {
  // Reactive theme + motion preferences (recolor on theme switch, no animation when reduced)
  const isDarkTheme = useIsDarkTheme();
  const prefersReducedMotion = usePrefersReducedMotion();

  // Refs
  const svgRef = useRef<SVGSVGElement>(null);
  const gRef = useRef<SVGGElement>(null);
  const simulationRef = useRef<d3.Simulation<D3Node, D3Edge> | null>(null);
  const zoomRef = useRef<d3.ZoomBehavior<SVGSVGElement, unknown> | null>(null);
  const currentTransformRef = useRef<d3.ZoomTransform>(d3.zoomIdentity);
  const assertedGraphRef = useRef<{ nodes: OntologyNode[]; edges: OntologyEdge[] } | null>(null);
  const inferredGraphRef = useRef<{ nodes: OntologyNode[]; edges: OntologyEdge[] } | null>(null);
  // Persist node positions across expand/collapse re-renders (OntoCode stability)
  const nodePositionsRef = useRef<Map<string, { x: number; y: number }>>(new Map());
  // Hard-pinned VOWL neighborhood hubs — Fit uses these so zoom-out can't re-squash clusters
  const vowlPinnedHubIdsRef = useRef<Set<string>>(new Set());
  // When the hub set changes (async data load, project switch), force a fresh
  // neighborhood seed — otherwise saved coords from an empty/partial first paint
  // skip placement and the graph settles into a hairball.
  const vowlHubFingerprintRef = useRef<string>('');
  // Latest culling pass for the current render — callable from zoom/drag/programmatic camera moves
  const applyViewportCullingRef = useRef<() => void>(() => {});
  // Un-hides culled elements and recomputes all edge paths so exports always contain the full graph
  const uncullForExportRef = useRef<() => void>(() => {});

  // State - Hierarchical Lazy Loading
  const [allNodes, setAllNodes] = useState<OntologyNode[]>([]);  // All data from API
  const [allEdges, setAllEdges] = useState<OntologyEdge[]>([]);  // All edges from API
  const [assertionView, setAssertionView] = useState<AssertionViewMode>('asserted');
  const [inferredGraphStatus, setInferredGraphStatus] = useState<'idle' | 'loading' | 'ready' | 'unavailable'>('idle');
  const [hierarchyState, setHierarchyState] = useState<HierarchyState>(() => ({
    visible: new Set<string>(),
    expanded: new Set<string>()
  }));
  const visibleNodeIds = hierarchyState.visible;
  const expandedNodeIds = hierarchyState.expanded;
  const canEdit = !readonly && (context?.permissions?.canEdit ?? true);
  const updateHierarchyState = useCallback((updater: (prev: HierarchyState) => HierarchyState) => {
    setHierarchyState(prev => updater({
      visible: new Set(prev.visible),
      expanded: new Set(prev.expanded)
    }));
  }, []);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [ontologyMetadata, setOntologyMetadata] = useState<any | null>(null);
  const [classActionLoading, setClassActionLoading] = useState(false);
  const [classActionFeedback, setClassActionFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  type PendingClassAction =
    | {
        kind: 'create';
        relation: 'child' | 'sibling';
        targetNode: OntologyNode;
        parentNode: OntologyNode;
        label: string;
      }
    | {
        kind: 'delete';
        targetNode: OntologyNode;
        childCount: number;
      };
  const [pendingClassAction, setPendingClassAction] = useState<PendingClassAction | null>(null);
  const handlePendingActionCancel = useCallback(() => {
    if (classActionLoading) return;
    setPendingClassAction(null);
  }, [classActionLoading]);
  const handlePendingLabelChange = useCallback((value: string) => {
    setPendingClassAction(prev => {
      if (!prev || prev.kind !== 'create') return prev;
      return { ...prev, label: value };
    });
  }, []);
  const requestHostClassDialog = useCallback((action: 'child' | 'sibling', targetNode: OntologyNode, parentNode: OntologyNode | null) => {
    const dispatched = dispatchHostEvent('graph-view:add-class', {
      action: action === 'child' ? 'subclass' : 'sibling',
      targetNodeId: targetNode.id,
      targetNodeLabel: targetNode.label,
      parentId: parentNode?.id ?? null,
      parentLabel: parentNode?.label ?? null,
      projectId
    });
    if (dispatched) {
      setClassActionFeedback({
        type: 'success',
        message: 'Opening full ontology editor dialog. Finish the operation from the Entities tab.'
      });
    } else {
      setClassActionFeedback({ type: 'error', message: 'Unable to reach the main editor dialog.' });
    }
  }, [projectId]);
  const requestHostDeleteDialog = useCallback((targetNode: OntologyNode) => {
    const dispatched = dispatchHostEvent('graph-view:delete-class', {
      nodeId: targetNode.id,
      nodeLabel: targetNode.label,
      projectId
    });
    if (dispatched) {
      setClassActionFeedback({
        type: 'success',
        message: 'Delete request sent to main editor. Confirm from the Entities tab.'
      });
    } else {
      setClassActionFeedback({ type: 'error', message: 'Unable to open delete dialog in main editor.' });
    }
  }, [projectId]);

  // New project → drop previous layout memory (otherwise VOWL skips reseeding)
  useEffect(() => {
    nodePositionsRef.current.clear();
    vowlHubFingerprintRef.current = '';
    vowlPinnedHubIdsRef.current = new Set();
  }, [projectId]);

  // New project → clear the hierarchy navigator too. Its content is now shown
  // inline in the sidebar by default (not just the opt-in floating popup), so a
  // stale root node from the previous project would otherwise render immediately
  // instead of sitting invisibly closed like before.
  useEffect(() => {
    setHierarchyRootNode(null);
    setShowHierarchyDialog(false);
  }, [projectId]);

  // UI State
  const [selectedNodes, setSelectedNodes] = useState<Set<string>>(new Set());
  const selectedNodesRef = useRef(selectedNodes);
  useEffect(() => {
    selectedNodesRef.current = selectedNodes;
  }, [selectedNodes]);

  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const [hoveredEdgeId, setHoveredEdgeId] = useState<string | null>(null);
  const [hoveredNode, setHoveredNode] = useState<string | null>(null);
  const [editMode, setEditMode] = useState(false);
  // Latest-value refs so edit-mode toggles and handler-prop changes don't tear down
  // and rebuild the whole D3 scene (the heavy render effect reads these instead).
  const editModeRef = useRef(editMode);
  useEffect(() => { editModeRef.current = editMode; }, [editMode]);
  const onNodeClickRef = useRef(onNodeClick);
  const onEdgeClickRef = useRef(onEdgeClick);
  useEffect(() => {
    onNodeClickRef.current = onNodeClick;
    onEdgeClickRef.current = onEdgeClick;
  }, [onNodeClick, onEdgeClick]);
  // Cursor is the only visual editMode affects — update it in place
  useEffect(() => {
    if (!gRef.current) return;
    d3.select(gRef.current).selectAll<SVGGElement, unknown>('.node')
      .style('cursor', editModeRef.current ? 'move' : 'pointer');
  }, [editMode]);
  // Default chrome: Explorer closed (opens via toolbar or the right-edge rail), legend on.
  // Both remembered per user across sessions.
  const [showPropertyPanel, setShowPropertyPanel] = useState(() => loadUiPrefs()?.showPropertyPanel ?? false);
  const [selectedNodeInfo, setSelectedNodeInfo] = useState<OntologyNode | null>(null);
  
  // Per-user view memory: first-ever open gets the animated force "bloom";
  // afterwards the tab reopens in whatever mode/layout the user last used.
  const initialViewMemory = useMemo(() => loadLastView(projectId), [projectId]);
  const isFirstEverOpen = initialViewMemory === null;

  // Visualization Type (combines both view mode and visualization type)
  const [visualizationType, setVisualizationType] = useState<VisualizationType>(
    () => initialViewMemory?.visualizationType ?? 'force'
  );
  const [ontographLayoutType, setOntographLayoutType] = useState<OntographLayoutType>(
    () => initialViewMemory?.ontographLayoutType ?? 'tree'
  );
  const [showLegend, setShowLegend] = useState(() => loadUiPrefs()?.showLegend ?? true);

  // One subtle pulse on the View-options button for the first few sessions after the
  // toolbar restructure, so existing users find where everything moved.
  const [showToolbarHint, setShowToolbarHint] = useState(false);
  useEffect(() => {
    const prefs = loadUiPrefs();
    const sessions = prefs?.toolbarHintSessions ?? 0;
    if (sessions < 3) {
      setShowToolbarHint(true);
      saveUiPrefs({
        showLegend: prefs?.showLegend ?? true,
        showPropertyPanel: prefs?.showPropertyPanel ?? false,
        toolbarHintSessions: sessions + 1,
        vowlOptions: prefs?.vowlOptions
      });
    }
  }, []);

  // Persist chrome preferences (legend / Explorer visibility) whenever the user changes them
  const chromePrefsMountedRef = useRef(false);
  useEffect(() => {
    if (!chromePrefsMountedRef.current) {
      chromePrefsMountedRef.current = true;
      return;
    }
    const prefs = loadUiPrefs();
    saveUiPrefs({
      showLegend,
      showPropertyPanel,
      toolbarHintSessions: prefs?.toolbarHintSessions ?? 0,
      vowlOptions: prefs?.vowlOptions
    });
  }, [showLegend, showPropertyPanel]);

  // Entrance animation ("bloom") — decided once per mount, on the first render with data
  const entranceRef = useRef<{ phase: 'pending' | 'playing' | 'done'; mode: 'bloom' | 'calm' | 'static' }>({
    phase: 'pending',
    mode: 'static'
  });
  const [entrancePhase, setEntrancePhase] = useState<'pending' | 'playing' | 'done'>('pending');
  const userInteractedRef = useRef(false);
  // Existing users (a stored view exists) persist mode changes immediately; first-time
  // users only start persisting once the entrance has played or they changed the view.
  const hasSeenGraphRef = useRef(!isFirstEverOpen);

  useEffect(() => {
    if (!hasSeenGraphRef.current) {
      const initialType = initialViewMemory?.visualizationType ?? 'force';
      const initialLayout = initialViewMemory?.ontographLayoutType ?? 'tree';
      if (visualizationType === initialType && ontographLayoutType === initialLayout) return;
      hasSeenGraphRef.current = true;
    }
    saveLastView(projectId, { visualizationType, ontographLayoutType });
  }, [projectId, visualizationType, ontographLayoutType, initialViewMemory]);
  
  // VOWL Filters State (integrated into main sidebar)
  const [vowlFilters, setVowlFilters] = useState({
    showExternalClasses: true,
    showInternalClasses: true,
    showDatatypes: true,
    showObjectProperties: true,
    showDataProperties: true,
    showSubClassOf: true,
    showFunctionalProperties: true
  });
  const [showClassHierarchy, setShowClassHierarchy] = useState(false);
  const [hierarchySelectedClass, setHierarchySelectedClass] = useState<OntologyNode | null>(null);

  // Notation & density options — persisted per user.
  const [vowlOptions, setVowlOptions] = useState<VowlDisplayOptions>(
    () => ({ ...DEFAULT_VOWL_OPTIONS, ...(loadUiPrefs()?.vowlOptions ?? {}) })
  );
  const vowlOptionsMountedRef = useRef(false);
  useEffect(() => {
    if (!vowlOptionsMountedRef.current) {
      vowlOptionsMountedRef.current = true;
      return;
    }
    const prefs = loadUiPrefs();
    saveUiPrefs({
      showLegend: prefs?.showLegend ?? true,
      showPropertyPanel: prefs?.showPropertyPanel ?? false,
      toolbarHintSessions: prefs?.toolbarHintSessions ?? 0,
      vowlOptions
    });
  }, [vowlOptions]);
  
  // Save-view name prompt — replaces window.prompt(), which Electron's renderer
  // (the desktop shell this plugin also runs in) does not implement.
  const [showSaveViewPrompt, setShowSaveViewPrompt] = useState(false);
  const [saveViewNameDraft, setSaveViewNameDraft] = useState('');

  // Hierarchy Dialog State
  const [showHierarchyDialog, setShowHierarchyDialog] = useState(false);
  const [hierarchyDialogPosition, setHierarchyDialogPosition] = useState({ x: 100, y: 100 });
  const [hierarchyRootNode, setHierarchyRootNode] = useState<OntologyNode | null>(null);
  const [isDialogMinimized, setIsDialogMinimized] = useState(false);

  // Context menu state
  const [contextMenu, setContextMenu] = useState<{
    visible: boolean;
    x: number;
    y: number;
    nodeId: string | null;
  }>({ visible: false, x: 0, y: 0, nodeId: null });

  // Viewport tracking for large graph optimization — a ref, not state: culling/LOD
  // decisions read it imperatively, and pan/zoom must not re-render React.
  const viewportBoundsRef = useRef({ x: 0, y: 0, width: 0, height: 0, scale: 1 });

  // Settings & Filters
  const [settings, setSettings] = useState<GraphSettings>(DEFAULT_SETTINGS);

  // WebGL renderer spike (feature-flagged): settings.renderer or ?webgl=1, with a
  // silent SVG fallback when the host has no WebGL (GPU blocklist, remote desktop).
  const webglSupported = useMemo(() => isWebGLAvailable(), []);
  const webglActive = webglSupported && (
    settings.renderer === 'webgl' ||
    (typeof window !== 'undefined' && new URLSearchParams(window.location.search).get('webgl') === '1')
  );
  const [webglBannerDismissed, setWebglBannerDismissed] = useState(false);
  // ?bench=N renders a synthetic graph directly in the WebGL renderer (benchmark harness)
  const benchData = useMemo(() => {
    if (typeof window === 'undefined') return null;
    const n = parseInt(new URLSearchParams(window.location.search).get('bench') || '', 10);
    return Number.isFinite(n) && n > 0 ? buildBenchmarkData(Math.min(n, 100000)) : null;
  }, []);
  const [filters, setFilters] = useState<GraphFilters>(DEFAULT_FILTERS);
  const [searchQuery, setSearchQuery] = useState('');
  const [sidebarSearchTerm, setSidebarSearchTerm] = useState('');

  // VOWL Controls
  // VOWL-first defaults: more breathing room than VOWL's tight packing
  const [classDistance, setClassDistance] = useState(170);
  const [datatypeDistance, setDatatypeDistance] = useState(85);
  const [isLayoutPaused, setIsLayoutPaused] = useState(false);

  // Panels
  const [showSearch, setShowSearch] = useState(false);
  const [showFilters, setShowFilters] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showGrid, setShowGrid] = useState(false);

  // Advanced features
  const [zoomLevel, setZoomLevel] = useState(1);
  type SavedGraphView = {
    id: string;
    name: string;
    createdAt: string;
    visualizationType: VisualizationType;
    ontographLayoutType: typeof ontographLayoutType;
    zoomTransform: { x: number; y: number; k: number };
    hierarchy: { visible: string[]; expanded: string[] };
    selectedNodeIds: string[];
    filters: {
      nodeTypes: NodeType[];
      edgeTypes: EdgeType[];
      searchQuery?: string;
      namespaceFilter?: string[];
      contextFilter?: string[];
    };
    vowlFilters: typeof vowlFilters;
    settings: Pick<GraphSettings, 'showLabels' | 'showArrows' | 'physics' | 'nodeSize' | 'edgeWidth'>;
    focusedNodeId: string | null;
    nodePositions: Array<[string, { x: number; y: number }]>;
  };
  const savedViewsStorageKey = useMemo(() => `ontocode.graphView.savedViews.${projectId}`, [projectId]);
  const [savedViews, setSavedViews] = useState<SavedGraphView[]>([]);
  const [selectedSavedViewId, setSelectedSavedViewId] = useState('');

  const toggleRelationshipVisibility = useCallback((edgeTypes: EdgeType[], visible?: boolean) => {
    setFilters(prev => {
      const nextEdgeTypes = new Set(prev.edgeTypes);
      const shouldShow = visible ?? edgeTypes.some(type => !nextEdgeTypes.has(type));

      edgeTypes.forEach(type => {
        if (shouldShow) {
          nextEdgeTypes.add(type);
        } else {
          nextEdgeTypes.delete(type);
        }
      });

      return {
        ...prev,
        edgeTypes: nextEdgeTypes
      };
    });
  }, []);

  const showAllRelationshipTypes = useCallback(() => {
    toggleRelationshipVisibility(
      RELATIONSHIP_VISIBILITY_CONTROLS.flatMap(control => control.edgeTypes),
      true
    );
  }, [toggleRelationshipVisibility]);

  const hideAllRelationshipTypes = useCallback(() => {
    toggleRelationshipVisibility(
      RELATIONSHIP_VISIBILITY_CONTROLS.flatMap(control => control.edgeTypes),
      false
    );
  }, [toggleRelationshipVisibility]);

  useEffect(() => {
    try {
      const raw = localStorage.getItem(savedViewsStorageKey);
      setSavedViews(raw ? JSON.parse(raw) : []);
      setSelectedSavedViewId('');
    } catch (error) {
      console.warn('[GraphView] Failed to load saved graph views', error);
      setSavedViews([]);
      setSelectedSavedViewId('');
    }
  }, [savedViewsStorageKey]);

  const persistSavedViews = useCallback((views: SavedGraphView[]) => {
    setSavedViews(views);
    localStorage.setItem(savedViewsStorageKey, JSON.stringify(views));
  }, [savedViewsStorageKey]);

  const applyGraphForAssertionView = useCallback((
    mode: AssertionViewMode,
    assertedGraph: { nodes: OntologyNode[]; edges: OntologyEdge[] } | null,
    inferredGraph: { nodes: OntologyNode[]; edges: OntologyEdge[] } | null
  ) => {
    const asserted = assertedGraph || { nodes: [], edges: [] };
    const inferred = inferredGraph || { nodes: [], edges: [] };

    let nextNodes: OntologyNode[];
    let nextEdges: OntologyEdge[];

    if (mode === 'inferred') {
      nextNodes = inferred.nodes;
      nextEdges = inferred.edges;
    } else if (mode === 'all') {
      const nodeMap = new Map<string, OntologyNode>();
      asserted.nodes.forEach(node => nodeMap.set(node.id, node));
      inferred.nodes.forEach(node => {
        const existing = nodeMap.get(node.id);
        nodeMap.set(node.id, existing ? {
          ...existing,
          metadata: { ...existing.metadata, hasInferredPlacement: true }
        } : node);
      });

      const edgeMap = new Map<string, OntologyEdge>();
      asserted.edges.forEach(edge => edgeMap.set(edge.id, edge));
      inferred.edges.forEach(edge => {
        const existing = edgeMap.get(edge.id);
        edgeMap.set(edge.id, existing ? {
          ...existing,
          metadata: { ...existing.metadata, hasInferredSupport: true }
        } : edge);
      });

      nextNodes = Array.from(nodeMap.values());
      nextEdges = Array.from(edgeMap.values());
    } else {
      nextNodes = asserted.nodes.filter(node => !isInferredEntity(node));
      nextEdges = asserted.edges.filter(edge => !isInferredEntity(edge));
    }

    setAllNodes(nextNodes);
    setAllEdges(nextEdges);

    const { newExpandedIds, newVisibleIds } = smartInitialGraphVisibility(nextNodes, nextEdges);
    updateHierarchyState(() => ({
      visible: newVisibleIds,
      expanded: newExpandedIds
    }));
  }, [updateHierarchyState]);

  const buildInferredGraphFromHierarchy = useCallback((hierarchy: any[]): { nodes: OntologyNode[]; edges: OntologyEdge[] } => {
    const nodeMap = new Map<string, OntologyNode>();
    const edgeMap = new Map<string, OntologyEdge>();

    const visit = (item: any, parentId?: string) => {
      const id = item?.id || item?.iri;
      if (!id) return;

      nodeMap.set(id, {
        id,
        label: item.label || item.name || id.split(/[#/]/).pop() || id,
        type: 'class',
        uri: id,
        metadata: {
          ...(item.metadata || {}),
          inferred: true,
          assertion: 'inferred',
          reasonerUnsatisfiable: item.isUnsatisfiable === true
        }
      });

      if (parentId && id !== parentId) {
        edgeMap.set(`${id}-inferred-subClassOf-${parentId}`, {
          id: `${id}-inferred-subClassOf-${parentId}`,
          from: id,
          to: parentId,
          type: 'subClassOf',
          label: 'inferred subClassOf',
          metadata: { inferred: true, assertion: 'inferred' }
        });
      }

      const equivalents = Array.isArray(item.equivalentClasses) ? item.equivalentClasses : [];
      equivalents.forEach((equivalent: any) => {
        const equivId = equivalent?.iri || equivalent?.id;
        if (!equivId || equivId === id) return;
        nodeMap.set(equivId, {
          id: equivId,
          label: equivalent.label || equivalent.name || equivId.split(/[#/]/).pop() || equivId,
          type: 'class',
          uri: equivId,
          metadata: { inferred: true, assertion: 'inferred', equivalentSource: id }
        });
        edgeMap.set(`${id}-inferred-equivalentClass-${equivId}`, {
          id: `${id}-inferred-equivalentClass-${equivId}`,
          from: id,
          to: equivId,
          type: 'equivalentClass',
          label: 'inferred equivalentClass',
          metadata: { inferred: true, assertion: 'inferred' }
        });
      });

      (Array.isArray(item.children) ? item.children : []).forEach((child: any) => visit(child, id));
    };

    hierarchy.forEach(root => visit(root));
    return { nodes: Array.from(nodeMap.values()), edges: Array.from(edgeMap.values()) };
  }, []);

  const fetchInferredGraphData = useCallback(async (apiBaseUrl: string, authToken: string | null) => {
    setInferredGraphStatus('loading');
    try {
      const response = await fetch(`${apiBaseUrl}/api/ontology/${encodeURIComponent(projectId)}/reasoner/inferred-class-hierarchy?reasonerType=OPENLLET`, {
        headers: authHeaders(authToken)
      });
      if (!response.ok) {
        throw new Error(`Reasoner hierarchy request failed: ${response.status}`);
      }
      const data = await response.json();
      const inferred = buildInferredGraphFromHierarchy(Array.isArray(data?.hierarchy) ? data.hierarchy : []);
      inferredGraphRef.current = inferred;
      setInferredGraphStatus(inferred.nodes.length > 0 ? 'ready' : 'unavailable');
      return inferred;
    } catch (error) {
      console.warn('[GraphView] Inferred graph data unavailable', error);
      inferredGraphRef.current = { nodes: [], edges: [] };
      setInferredGraphStatus('unavailable');
      return inferredGraphRef.current;
    }
  }, [buildInferredGraphFromHierarchy, projectId]);

  // ---------------------------------------------------------------------------
  // FOCUS MODE — isolate a single class plus its N-hop parents/children
  //
  // ---------------------------------------------------------------------------
  const [focusedNodeId, setFocusedNodeId] = useState<string | null>(null);
  const [focusParentDepth, setFocusParentDepth] = useState<number>(2);
  const [focusChildDepth, setFocusChildDepth] = useState<number>(2);
  const [focusIncludeProperties, setFocusIncludeProperties] = useState<boolean>(true);
  const [focusIncludeIndividuals, setFocusIncludeIndividuals] = useState<boolean>(false);

  // OntoCode local graph + OntoCode insights
  const [showLocalGraph, setShowLocalGraph] = useState(false);
  const [showAnalytics, setShowAnalytics] = useState(false);
  const [colorByCluster, setColorByCluster] = useState(false);
  const [sizeByInfluence, setSizeByInfluence] = useState(true);
  const [localGraphFollowSelection, setLocalGraphFollowSelection] = useState(true);
  const [localFocusId, setLocalFocusId] = useState<string | null>(null);
  const [localGraphHeight, setLocalGraphHeight] = useState(260);
  const [centralityThreshold, setCentralityThreshold] = useState(100);

  // Compute neighborhood of focused node via BFS over subClassOf, instanceOf,
  // domain/range and propertyRelation edges.
  const focusedNodeIds = useMemo<Set<string> | null>(() => {
    if (!focusedNodeId) return null;
    const result = new Set<string>([focusedNodeId]);

    // BFS upwards via subClassOf (child --from--> parent --to-->)
    let frontier = new Set<string>([focusedNodeId]);
    for (let d = 0; d < focusParentDepth && frontier.size > 0; d++) {
      const next = new Set<string>();
      for (const e of allEdges) {
        if (e.type === 'subClassOf' && frontier.has(e.from) && !result.has(e.to)) {
          result.add(e.to);
          next.add(e.to);
        }
      }
      frontier = next;
    }

    // BFS downwards via subClassOf
    frontier = new Set<string>([focusedNodeId]);
    for (let d = 0; d < focusChildDepth && frontier.size > 0; d++) {
      const next = new Set<string>();
      for (const e of allEdges) {
        if (e.type === 'subClassOf' && frontier.has(e.to) && !result.has(e.from)) {
          result.add(e.from);
          next.add(e.from);
        }
      }
      frontier = next;
    }

    // Include directly connected individuals (instanceOf into the focused node)
    if (focusIncludeIndividuals) {
      for (const e of allEdges) {
        if (e.type === 'instanceOf' && result.has(e.to)) {
          result.add(e.from);
        }
      }
    }

    // Include directly connected properties / domain / range neighbors
    if (focusIncludeProperties) {
      for (const e of allEdges) {
        if (
          (e.type === 'propertyRelation' || e.type === 'domain' || e.type === 'range') &&
          (result.has(e.from) || result.has(e.to))
        ) {
          result.add(e.from);
          result.add(e.to);
        }
      }
    }

    return result;
  }, [focusedNodeId, focusParentDepth, focusChildDepth, focusIncludeProperties, focusIncludeIndividuals, allEdges]);

  const enterFocusMode = useCallback((nodeId: string) => {
    setFocusedNodeId(nodeId);
  }, []);
  const exitFocusMode = useCallback(() => {
    setFocusedNodeId(null);
  }, []);

  // Compute visible nodes and edges based on hierarchy
  const visibleNodes = useMemo(() => {
    // Focus mode short-circuits hierarchical visibility — show only the
    // computed neighborhood for laser-focus class inspection.
    if (focusedNodeIds) {
      const filtered = allNodes.filter(n => focusedNodeIds.has(n.id));
      console.log('[AdvancedGraphView] 🎯 FOCUS MODE active — showing', filtered.length, 'nodes around', focusedNodeId);
      return filtered;
    }
    const filtered = allNodes.filter(n => visibleNodeIds.has(n.id));
    graphLog('[AdvancedGraphView] visibleNodes:', filtered.length, '/', allNodes.length);
    return filtered;
  }, [allNodes, visibleNodeIds, focusedNodeIds, focusedNodeId]);

  const visibleEdges = useMemo(() => {
    // Focus mode: only edges between visible (neighborhood) nodes
    if (focusedNodeIds) {
      const edges = allEdges.filter(e => focusedNodeIds.has(e.from) && focusedNodeIds.has(e.to));
      console.log('[AdvancedGraphView] 🎯 FOCUS MODE edges:', edges.length);
      return edges;
    }
    if (visibleNodeIds.size === 0) return [];
    if (visibleNodeIds.size === allNodes.length) return allEdges; // All visible
    
    // Debug: Check Thing in allEdges FIRST
    const thingNode = allNodes.find(n => 
      n.label === 'Thing' || 
      n.id.includes('Thing') || 
      n.id === 'owl:Thing' ||
      n.id.includes('owl#Thing')
    );
    
    if (thingNode) {
      const allThingEdges = allEdges.filter(e => e.from === thingNode.id || e.to === thingNode.id);
      console.log('[DEBUG Thing] Found Thing node:', thingNode.id, 'label:', thingNode.label);
      console.log('[DEBUG Thing] Total edges in allEdges involving Thing:', allThingEdges.length);
      console.log('[DEBUG Thing] Thing edges in allEdges:', allThingEdges.map(e => ({
        type: e.type,
        from: allNodes.find(n => n.id === e.from)?.label || e.from,
        to: allNodes.find(n => n.id === e.to)?.label || e.to,
        fromVisible: visibleNodeIds.has(e.from),
        toVisible: visibleNodeIds.has(e.to)
      })));
      
      // Check if Thing's children are visible
      const thingChildren = allEdges
        .filter(e => e.type === 'subClassOf' && e.to === thingNode.id)
        .map(e => e.from);
      console.log('[DEBUG Thing] Thing has', thingChildren.length, 'children in allEdges');
      console.log('[DEBUG Thing] Children visibility:', thingChildren.map(childId => ({
        id: childId,
        label: allNodes.find(n => n.id === childId)?.label,
        visible: visibleNodeIds.has(childId)
      })));
      console.log('[DEBUG Thing] Thing itself visible:', visibleNodeIds.has(thingNode.id));
    }
    
    // Fast path: filter using Set lookups (O(1) per lookup)
    const edges = allEdges.filter(e => 
      visibleNodeIds.has(e.from) && visibleNodeIds.has(e.to)
    );
    
    console.log('[AdvancedGraphView] Visible edges:', edges.length, 'from total:', allEdges.length);
    
    // Debug: Check Thing edges specifically in filtered result
    if (thingNode) {
      const thingEdges = edges.filter(e => e.from === thingNode.id || e.to === thingNode.id);
      console.log('[AdvancedGraphView] Thing node - Visible edges AFTER filtering:', thingEdges.length);
      if (thingEdges.length > 0) {
        console.log('[AdvancedGraphView] Thing visible edges:', thingEdges.map(e => ({
          type: e.type,
          from: allNodes.find(n => n.id === e.from)?.label || e.from,
          to: allNodes.find(n => n.id === e.to)?.label || e.to
        })));
      } else {
        console.warn('[WARNING] Thing has NO visible edges! This is the problem.');
      }
    }
    
    return edges;
  }, [allEdges, visibleNodeIds, allNodes.length, allNodes, focusedNodeIds]);

  /**
   * ========================================================================
   * FILTERING
   * ========================================================================
   */
  // External = class whose namespace differs from the ontology's dominant namespace
  // (VOWL "color externals" semantics). The dominant namespace is the most common
  // namespace among class IRIs — more reliable than comparing against projectId.
  const namespaceOfIri = (iri: string): string | null => {
    if (!iri || !iri.startsWith('http')) return null;
    const cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
    return cut > 'https://'.length ? iri.substring(0, cut + 1) : null;
  };
  const dominantNamespace = useMemo(() => {
    const counts = new Map<string, number>();
    for (const n of allNodes) {
      if (n.type !== 'class') continue;
      const iri = n.uri || n.id;
      if (isThingIri(iri)) continue;
      const ns = namespaceOfIri(iri);
      if (!ns) continue;
      counts.set(ns, (counts.get(ns) ?? 0) + 1);
    }
    let best: string | null = null;
    let bestCount = 0;
    counts.forEach((count, ns) => {
      if (count > bestCount) { best = ns; bestCount = count; }
    });
    return best;
  }, [allNodes]);

  const isExternalNode = useCallback((node: any) => {
    if (!node || !dominantNamespace) return false;
    if (isThingIri(node.uri || node.id)) return false;
    const ns = namespaceOfIri(node.uri || node.id || '');
    return !!ns && ns !== dominantNamespace;
  }, [dominantNamespace]);

  // Helper to determine if an edge is functional
  const isFunctionalEdge = useCallback((edge: any) => {
    if (!edge) return false;
    return (
      edge.metadata?.functional === true || 
      edge.metadata?.characteristics?.includes('functional') ||
      edge.label?.toLowerCase().includes('functional')
    );
  }, []);

  const filteredNodes = useMemo(() => {

    let filtered = visibleNodes.filter(node => {
      if (!filters.nodeTypes.has(node.type)) return false;
      if (assertionView === 'asserted') return !isInferredEntity(node);
      if (assertionView === 'inferred') return isInferredEntity(node);
      return true;
    });

    // **VOWL/OntoGraph Mode: Apply specific entity filters**
    if (visualizationType === 'vowl' || visualizationType === 'ontograph') {
      const beforeFilter = filtered.length;
      
      // In VOWL mode, we hide property nodes (they are edges).
      // Set-operator nodes (∪ ∩ ¬ {…}) are VOWL notation — shown unless filtered off.
      if (visualizationType === 'vowl') {
        filtered = filtered.filter(node =>
          node.type === 'class' ||
          node.type === 'individual' ||
          node.type === 'datatype' ||
          (node.type === 'setOperator' && vowlOptions.showSetOperators)
        );
      }
      
      // Apply Sidebar Filters
      filtered = filtered.filter(node => {
        // Check external vs internal classes
        const isExternal = isExternalNode(node);
        const isThing = node.label === 'Thing' || node.id === 'owl:Thing' || node.id.includes('owl#Thing');
        
        if (node.type === 'class') {
          if (isThing) return vowlFilters.showInternalClasses; // Thing is internal
          if (isExternal) return vowlFilters.showExternalClasses;
          return vowlFilters.showInternalClasses;
        }
        
        if (node.type === 'datatype') {
          return vowlFilters.showDatatypes;
        }
        
        return true; // individuals always shown
      });
      
      if (GRAPH_DEBUG) console.log(`[Filtering] ${visualizationType} mode: After vowlFilters, showing ${filtered.length} nodes (from ${beforeFilter})`);
    }

    // **Force Mode: Hide property nodes (objectProperty, dataProperty, annotation)**
    // Properties should only appear as edges, not as nodes in force mode
    if (visualizationType === 'force') {
      filtered = filtered.filter(node =>
        node.type === 'class' ||
        node.type === 'individual' ||
        node.type === 'datatype'
      );
      if (GRAPH_DEBUG) console.log(`[Filtering] Force mode: Filtered to classes, individuals, datatypes - ${filtered.length} nodes`);
    }

    // Datatype nodes that only exist for VOWL fallback edges (missing domain/range
    // defaults) would float disconnected in non-VOWL modes — hide them there.
    if (visualizationType !== 'vowl') {
      const nonVowlConnected = new Set<string>();
      for (const e of allEdges) {
        if (e.metadata?.vowlOnly) continue;
        nonVowlConnected.add(e.from);
        nonVowlConnected.add(e.to);
      }
      filtered = filtered.filter(node => node.type !== 'datatype' || nonVowlConnected.has(node.id));
    }

    // OntoGraph Mode: focus on class hierarchy and individuals for clean OntoCode layout
    if (visualizationType === 'ontograph') {
      const beforeFilter = filtered.length;
      filtered = filtered.filter(node => node.type === 'class' || node.type === 'individual');
      if (GRAPH_DEBUG) console.log(`[Filtering] OntoGraph mode: Focused on classes and individuals ${beforeFilter} -> ${filtered.length}`);
    }

    // Density options (network views only — the ontograph hierarchy must stay complete):
    // degree-of-collapsing hides weakly-connected nodes, solitary-subclasses hides
    // classes whose only relationship is a single subClassOf.
    if (
      (visualizationType === 'vowl' || visualizationType === 'force') &&
      (vowlOptions.degreeCollapsing > 0 || vowlOptions.hideSolitarySubclasses)
    ) {
      const inSet = new Set(filtered.map(n => n.id));
      const degree = new Map<string, number>();
      const hasNonSubClassEdge = new Set<string>();
      for (const e of allEdges) {
        if (!inSet.has(e.from) || !inSet.has(e.to)) continue;
        degree.set(e.from, (degree.get(e.from) ?? 0) + 1);
        degree.set(e.to, (degree.get(e.to) ?? 0) + 1);
        if (e.type !== 'subClassOf') {
          hasNonSubClassEdge.add(e.from);
          hasNonSubClassEdge.add(e.to);
        }
      }
      filtered = filtered.filter(node => {
        const deg = degree.get(node.id) ?? 0;
        if (vowlOptions.degreeCollapsing > 0 && deg < vowlOptions.degreeCollapsing) return false;
        if (
          vowlOptions.hideSolitarySubclasses &&
          node.type === 'class' &&
          deg === 1 &&
          !hasNonSubClassEdge.has(node.id)
        ) return false;
        return true;
      });
    }

    if (GRAPH_DEBUG) console.log(`[Filtering] visibleNodes: ${visibleNodes.length}, after type filter: ${filtered.length}`);

    // Search filter: instead of hiding non-matching nodes, keep all visible nodes.
    // Matching nodes are highlighted via the visual update effect (glow/stroke).
    // The searchQuery is tracked in state and applied as a visual highlight only.

    // Sidebar search filter - filters the visible graph
    if (sidebarSearchTerm) {
      const query = sidebarSearchTerm.toLowerCase();
      filtered = filtered.filter(node =>
        node.label.toLowerCase().includes(query) ||
        node.id.toLowerCase().includes(query) ||
        node.description?.toLowerCase().includes(query)
      );
      if (GRAPH_DEBUG) console.log(`[Filtering] After sidebar search "${sidebarSearchTerm}": ${filtered.length} nodes`);
    }

    if (GRAPH_DEBUG) console.log(`[Filtering] Final filtered nodes: ${filtered.length}`);

    return filtered;
  }, [visibleNodes, filters, searchQuery, sidebarSearchTerm, visualizationType, vowlFilters, assertionView, vowlOptions, allEdges]);

  const filteredEdges = useMemo(() => {
    const nodeIds = new Set(filteredNodes.map(n => n.id));
    let filtered = visibleEdges.filter(edge =>
      filters.edgeTypes.has(edge.type) &&
      nodeIds.has(edge.from) &&
      nodeIds.has(edge.to) &&
      // vowlOnly = fallback edges (missing domain/range defaulted to
      // owl:Thing / rdfs:Literal). Other modes have no per-edge Thing splitting, so
      // these would just bloat the Thing hub there.
      !(edge.metadata?.vowlOnly && visualizationType !== 'vowl') &&
      (assertionView === 'all' ||
        (assertionView === 'inferred' ? isInferredEntity(edge) : !isInferredEntity(edge)))
    );
    
    // **VOWL/OntoGraph Mode: Apply specific relationship filters**
    if (visualizationType === 'vowl' || visualizationType === 'ontograph') {
      const beforeFilter = filtered.length;
      
      // In VOWL mode, we hide domain/range edges (they are metadata)
      if (visualizationType === 'vowl') {
        filtered = filtered.filter(edge => 
          edge.type !== 'domain' && edge.type !== 'range'
        );
      }
      
      // Apply Sidebar Edge Filters
      filtered = filtered.filter(edge => {
        if (edge.type === 'subClassOf') {
          return vowlFilters.showSubClassOf;
        }
        if (edge.type === 'propertyRelation') {
          // Check if functional property
          const isFunctional = isFunctionalEdge(edge);
          
          if (isFunctional && !vowlFilters.showFunctionalProperties) {
            return false;
          }
          
          // Determine property type (check source node type and target node type)
          const sourceNode = allNodes.find(n => n.id === edge.from);
          const targetNode = allNodes.find(n => n.id === edge.to);
          
          // Annotation property edges
          if (sourceNode?.type === 'annotation') {
            return true; // Always show annotation properties when enabled
          }
          
          // Data property edges (data properties or edges to datatypes)
          if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') {
            return vowlFilters.showDataProperties;
          }
          
          // Object property edges - check filter
          if (sourceNode?.type === 'objectProperty') {
            return vowlFilters.showObjectProperties;
          }
          
          // Default: check object properties filter for unidentified property edges
          return vowlFilters.showObjectProperties;
        }
        
        // Domain and Range edges - filter based on property type
        if (edge.type === 'domain' || edge.type === 'range') {
          const sourceNode = allNodes.find(n => n.id === edge.from);
          
          // If source is object property, check object properties filter
          if (sourceNode?.type === 'objectProperty') {
            return vowlFilters.showObjectProperties;
          }
          
          // If source is data property, check data properties filter
          if (sourceNode?.type === 'dataProperty') {
            return vowlFilters.showDataProperties;
          }
          
          // If source is annotation, always show
          if (sourceNode?.type === 'annotation') {
            return true;
          }
          
          // Default: hide domain/range in VOWL mode unless they are direct relations
          return visualizationType !== 'vowl';
        }
        
        return true;
      });
      
      console.log(`[Filtering] ${visualizationType} mode: Filtered ${beforeFilter} -> ${filtered.length} edges`);
    }
    
    // **Force/OntoGraph Mode: Show property relation edges if filters allow them**
    if (visualizationType === 'force') {
      const beforeFilter = filtered.length;
      filtered = filtered.filter(edge => {
        if (edge.type === 'propertyRelation') {
          // Check the source node type - determine property type
          const sourceNode = allNodes.find(n => n.id === edge.from);
          const targetNode = allNodes.find(n => n.id === edge.to);
          
          // Annotation properties - always show if annotation filter is enabled
          if (sourceNode?.type === 'annotation') {
            return filters.nodeTypes.has('annotation');
          }
          
          // Data properties - show if dataProperty filter is enabled
          if (sourceNode?.type === 'dataProperty' || targetNode?.type === 'datatype') {
            return filters.nodeTypes.has('dataProperty');
          }
          
          // Object properties - show if objectProperty filter is enabled
          if (sourceNode?.type === 'objectProperty') {
            return filters.nodeTypes.has('objectProperty');
          }
          
          // Default: show the edge if we can't determine type
          return true;
        }
        return true;
      });
      
      console.log(`[Filtering] ${visualizationType} mode: Filtered property edges ${beforeFilter} -> ${filtered.length}`);
    }

    if (visualizationType === 'ontograph') {
      const beforeFilter = filtered.length;
      // OntoGraph should honor OntoCode relationship visibility controls
      // instead of silently dropping non-hierarchy axiom edges.
      const ontographRelationshipTypes = new Set(
        RELATIONSHIP_VISIBILITY_CONTROLS.flatMap(control => control.edgeTypes)
      );
      filtered = filtered.filter(edge => {
        return ontographRelationshipTypes.has(edge.type);
      });
      console.log(`[Filtering] OntoGraph mode: Applied relationship visibility ${beforeFilter} -> ${filtered.length}`);
    }

    // VOWL density toggles (beyond the classic sidebar filters)
    if (visualizationType === 'vowl') {
      if (!vowlOptions.showDisjointness) {
        filtered = filtered.filter(e => e.type !== 'disjointWith');
      }
      if (!vowlOptions.showPropertyLoops) {
        filtered = filtered.filter(e => !(e.type === 'propertyRelation' && e.from === e.to));
      }
    }
    
    console.log('[AdvancedGraphView] Filtered edges:', filtered.length);
    if (filtered.length === 0 && visibleEdges.length > 0) {
      console.warn('[AdvancedGraphView] ⚠️ No edges after filtering! Check edge types.');
      console.warn('[AdvancedGraphView] Edge types in data:', [...new Set(visibleEdges.map(e => e.type))]);
    }
    
    return filtered;
  }, [visibleEdges, filteredNodes, filters, visualizationType, vowlFilters, allNodes, assertionView, vowlOptions]);

  const graphAnalytics = useMemo(
    () => computeGraphAnalytics(filteredNodes, filteredEdges),
    [filteredNodes, filteredEdges]
  );

  // Same density rule as the render pass: VOWL chips stay always-visible below
  // this many labeled edges (mirrored in the hover/selection update effect).
  const vowlChipDensityOk = useMemo(() =>
    visualizationType === 'vowl' &&
    filteredEdges.filter(e => e.type === 'propertyRelation' || e.type === 'subClassOf').length <= 400,
  [visualizationType, filteredEdges]);

  const activeLocalFocusId = useMemo(() => {
    if (localGraphFollowSelection && selectedNodeInfo?.id) return selectedNodeInfo.id;
    if (localFocusId) return localFocusId;
    const thing = filteredNodes.find(n => n.id.includes('owl#Thing'));
    const firstClass = filteredNodes.find(n => n.type === 'class');
    return thing?.id || firstClass?.id || filteredNodes[0]?.id || null;
  }, [localGraphFollowSelection, selectedNodeInfo?.id, localFocusId, filteredNodes]);

  useEffect(() => {
    if (selectedNodeInfo?.id) setLocalFocusId(selectedNodeInfo.id);
  }, [selectedNodeInfo?.id]);

  // Generate dynamic legend based on current graph
  const dynamicLegend = useMemo(() => {
    console.log('[Legend] Computing dynamic legend - Mode:', visualizationType, 'Filtered nodes:', filteredNodes.length, 'Filtered edges:', filteredEdges.length);
    
    if (visualizationType === 'vowl') {
      // VOWL Mode - dynamic legend based on filtered nodes in the current graph
      const legend: Array<{ name: string; type: string; nodeType?: string; color?: string; stroke?: string; strokeDasharray?: string }> = [];
      
      // Use filteredNodes (after all filters) for accurate legend
      const nodeTypes = new Set(filteredNodes.map(n => n.type));
      const edgeTypes = new Set(filteredEdges.map(e => e.type));
      
      console.log('[Legend] VOWL - Node types:', Array.from(nodeTypes), 'Edge types:', Array.from(edgeTypes));
      
      // Check for specific node types in filtered nodes
      const hasThing = filteredNodes.some(n => n.label === 'Thing' || n.id === 'owl:Thing' || n.id.includes('owl#Thing'));
      const hasClass = nodeTypes.has('class') && filteredNodes.some(n => n.type === 'class' && n.label !== 'Thing');
      const hasDatatype = nodeTypes.has('datatype');
      const hasIndividual = nodeTypes.has('individual');
      
      // Distinguish internal vs external classes if present
      const externalClasses = filteredNodes.filter(n => 
        n.type === 'class' && isExternalNode(n)
      );
      const internalClasses = filteredNodes.filter(n => 
        n.type === 'class' && !isExternalNode(n) && n.label !== 'Thing'
      );
      
      // VOWL renders on the fixed light VOWL canvas — legend matches it
      const isDark = false;
      
      // Add node type legends with counts - theme aware
      if (hasThing) legend.push({ name: 'Thing', type: 'node', nodeType: 'class', color: isDark ? '#374151' : '#ffffff' });
      if (externalClasses.length > 0) legend.push({ name: `External Class (${externalClasses.length})`, type: 'node', nodeType: 'class', color: isDark ? '#60a5fa' : '#36c' });
      if (internalClasses.length > 0) legend.push({ name: `Internal Class (${internalClasses.length})`, type: 'node', nodeType: 'class', color: isDark ? '#6b92c4' : '#69c' });
      if (hasDatatype) legend.push({ name: `Datatype (${filteredNodes.filter(n => n.type === 'datatype').length})`, type: 'node', nodeType: 'datatype', color: isDark ? '#d97706' : '#fc3' });
      if (hasIndividual) legend.push({ name: `Individual (${filteredNodes.filter(n => n.type === 'individual').length})`, type: 'node', nodeType: 'individual', color: isDark ? '#fbb6ce' : '#cfc' });
      
      // Add edge type legends based on filtered edges
      if (edgeTypes.has('propertyRelation')) {
        const objProps = filteredEdges.filter(e => {
          const target = allNodes.find(n => n.id === e.to);
          const source = allNodes.find(n => n.id === e.from);
          return e.type === 'propertyRelation' && target?.type !== 'datatype' && source?.type !== 'annotation';
        }).length;
        
        const dataProps = filteredEdges.filter(e => {
          const target = allNodes.find(n => n.id === e.to);
          const source = allNodes.find(n => n.id === e.from);
          return e.type === 'propertyRelation' && (target?.type === 'datatype' || source?.type === 'dataProperty');
        }).length;
        
        const annoProps = filteredEdges.filter(e => {
          const source = allNodes.find(n => n.id === e.from);
          return e.type === 'propertyRelation' && source?.type === 'annotation';
        }).length;

        // Property kind is now encoded in the label CHIP color (VOWL),
        // not the line — legend swatches show the chip palette.
        if (objProps > 0) legend.push({ name: `Object Property (${objProps})`, type: 'edge', stroke: isDark ? '#60a5fa' : '#69c', strokeDasharray: '0' });
        if (dataProps > 0) legend.push({ name: `Data Property (${dataProps})`, type: 'edge', stroke: isDark ? '#a3e635' : '#99cc66', strokeDasharray: '0' });
        if (annoProps > 0) legend.push({ name: `Annotation Property (${annoProps})`, type: 'edge', stroke: isDark ? '#c4b5fd' : '#a78bfa', strokeDasharray: '0' });
      }
      
      if (edgeTypes.has('subClassOf')) legend.push({ name: `SubClass Of (${filteredEdges.filter(e => e.type === 'subClassOf').length})`, type: 'edge', stroke: isDark ? '#9ca3af' : '#000000', strokeDasharray: '5,5' });

      // Restriction edges carry VOWL badges: ∃ some, ∀ only, ∋ hasValue, ≥/≤/= cardinality
      if (edgeTypes.has('restriction')) {
        legend.push({
          name: `Restriction ∃ ∀ ∋ ≥ ≤ = (${filteredEdges.filter(e => e.type === 'restriction').length})`,
          type: 'edge',
          stroke: '#d97706',
          strokeDasharray: '0',
        });
      }

      // Characteristics render as a label suffix (no longer a chip color variant)
      if (edgeTypes.has('propertyRelation')) {
        legend.push({ name: 'Characteristics suffix (F, IF, S, T…)', type: 'label', color: isDark ? '#374151' : '#e5e7eb' });
      }
      
      return legend;
    } else {
      // Standard Mode (Force-directed & OntoGraph) - dynamic legend based on filtered nodes/edges
      const legend: Array<{ name: string; type: string; nodeType?: string; color?: string; stroke?: string; strokeDasharray?: string }> = [];
      
      // Node types from filtered nodes in current graph
      const nodeTypes = new Set(filteredNodes.map(n => n.type));
      
      // For force mode, use special colors for classes
      if (visualizationType === 'force') {
        if (nodeTypes.has('class')) legend.push({ name: `Class (${filteredNodes.filter(n => n.type === 'class').length})`, type: 'node', nodeType: 'class', color: nodeFill('class', isDarkTheme) });
        if (nodeTypes.has('individual')) legend.push({ name: `Individual (${filteredNodes.filter(n => n.type === 'individual').length})`, type: 'node', nodeType: 'individual', color: '#a78bfa' });
        if (nodeTypes.has('datatype')) legend.push({ name: `Datatype (${filteredNodes.filter(n => n.type === 'datatype').length})`, type: 'node', nodeType: 'datatype', color: '#FFFFFF' });
      } else {
        // OntoGraph mode
        if (nodeTypes.has('class')) legend.push({ name: `Class (${filteredNodes.filter(n => n.type === 'class').length})`, type: 'node', nodeType: 'class', color: '#FFF9C4' });
        if (nodeTypes.has('individual')) legend.push({ name: `Individual (${filteredNodes.filter(n => n.type === 'individual').length})`, type: 'node', nodeType: 'individual', color: '#E1F5FE' });
      }
      
      // Edge types from filtered edges
      const edgeTypes = new Set(filteredEdges.map(e => e.type));
      if (edgeTypes.has('subClassOf')) legend.push({ name: 'SubClass Of', type: 'edge', stroke: '#1976D2', strokeDasharray: '0' });
      if (edgeTypes.has('propertyRelation')) legend.push({ name: 'Property Relation', type: 'edge', stroke: '#059669', strokeDasharray: '0' });
      if (filteredNodes.some(isInferredEntity) || filteredEdges.some(isInferredEntity)) {
        legend.push({ name: 'Inferred', type: 'edge', stroke: '#10b981', strokeDasharray: '8 4' });
      }
      
      return legend;
    }
  }, [visualizationType, filteredNodes, filteredEdges, isExternalNode, allNodes, isDarkTheme]);

  // Performance tracking (disabled for production performance)
  const renderTime = useRef(0);

  /**
   * ========================================================================
   * DATA FETCHING - Optimized GraphDB Direct Approach
   * ========================================================================
   */
  const fetchGraphData = useCallback(async (opts?: { bypassCache?: boolean }) => {
    // Guard: Don't fetch if projectId is not set
    if (!projectId) {
      console.warn('[AdvancedGraphView D3] ⚠️ No projectId provided, skipping data fetch');
      setError('No project selected. Please select a project to view its ontology graph.');
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);

    console.log('[AdvancedGraphView D3] 📡 Fetching graph data for project:', projectId);

    try {
      const cacheKey = `ontocode:graphView:${projectId}`;
      const CACHE_TTL_MS = 2 * 60 * 1000; // 2 minutes
      const CACHE_MAX_BYTES = 4 * 1024 * 1024; // 4MB safety cap

      // Try sessionStorage cache first. Session-scoped (cleared on tab close).
      // Short TTL means users editing the ontology pick up changes quickly.
      // Explicit Refresh and ontology mutations bypass the cache entirely.
      let graphData: any = null;
      if (opts?.bypassCache) {
        try { sessionStorage.removeItem(cacheKey); } catch { /* ignore */ }
      } else {
        try {
          const cachedRaw = sessionStorage.getItem(cacheKey);
          if (cachedRaw) {
            const cached = JSON.parse(cachedRaw);
            if (cached && typeof cached.timestamp === 'number' && Date.now() - cached.timestamp < CACHE_TTL_MS && cached.data) {
              console.log('[AdvancedGraphView D3] ⚡ Using cached graph data (age:', Math.round((Date.now() - cached.timestamp) / 1000), 's)');
              graphData = cached.data;
            }
          }
        } catch (cacheErr) {
          console.warn('[AdvancedGraphView D3] Cache read failed, fetching fresh:', cacheErr);
        }
      }

      const apiBaseUrl = (window as any).__DESKTOP_API_URL__ || (window as any).API_BASE_URL;
      const authToken = localStorage.getItem('authToken');

      if (!graphData) {
        console.log('[AdvancedGraphView D3] 🔵 Cache miss — fetching from GraphDB...');
        const fetchService = createGraphDataFetchService(apiBaseUrl, projectId, authToken);
        graphData = await fetchService.fetchGraphData();

        // Save to sessionStorage (best-effort, skip if payload too large).
        try {
          const serialized = JSON.stringify({ timestamp: Date.now(), data: graphData });
          if (serialized.length <= CACHE_MAX_BYTES) {
            sessionStorage.setItem(cacheKey, serialized);
          } else {
            console.log('[AdvancedGraphView D3] Skipping cache — payload exceeds', CACHE_MAX_BYTES, 'bytes');
          }
        } catch (cacheErr) {
          console.warn('[AdvancedGraphView D3] Cache write failed (non-fatal):', cacheErr);
        }
      }
        
        console.log('[GraphDB Fetch] ✅ Fetched from GraphDB:', {
          nodes: graphData.nodes.length,
          edges: graphData.edges.length,
          byType: {
            classes: graphData.nodes.filter((n: any) => n.type === 'class').length,
            individuals: graphData.nodes.filter((n: any) => n.type === 'individual').length,
            objectProperties: graphData.nodes.filter((n: any) => n.type === 'objectProperty').length,
            dataProperties: graphData.nodes.filter((n: any) => n.type === 'dataProperty').length,
            datatypes: graphData.nodes.filter((n: any) => n.type === 'datatype').length,
            annotations: graphData.nodes.filter((n: any) => n.type === 'annotation').length
          }
        });
        
        if (!graphData.nodes || graphData.nodes.length === 0) {
          console.warn('[AdvancedGraphView D3] ⚠️ WARNING: GraphDB returned zero nodes! The ontology may be empty.');
          setError('No ontology data found in GraphDB. Please ensure the ontology is loaded.');
        }

        // Debug: Show edge type distribution
        const edgeTypeCount: Record<string, number> = {};
        graphData.edges.forEach((e: OntologyEdge) => {
          edgeTypeCount[e.type] = (edgeTypeCount[e.type] || 0) + 1;
        });
        console.log('[AdvancedGraphView] 📊 Edge Types:', edgeTypeCount);
        console.log('[AdvancedGraphView] 📝 Sample propertyRelation edges:', 
          graphData.edges.filter((e: OntologyEdge) => e.type === 'propertyRelation').slice(0, 5)
        );

        const assertedGraph = {
          nodes: graphData.nodes.map((node: OntologyNode) => ({
            ...node,
            metadata: { ...node.metadata, assertion: isInferredEntity(node) ? 'inferred' : 'asserted' }
          })),
          edges: graphData.edges.map((edge: OntologyEdge) => ({
            ...edge,
            metadata: { ...edge.metadata, assertion: isInferredEntity(edge) ? 'inferred' : 'asserted' }
          }))
        };
        assertedGraphRef.current = assertedGraph;

        // Paint asserted graph immediately; reasoner/inferred data loads in background (no blocking spinner).
        applyGraphForAssertionView(assertionView, assertedGraph, inferredGraphRef.current);
        setLoading(false);

        fetchInferredGraphData(apiBaseUrl, authToken)
          .then((inferredGraph) => {
            if (assertedGraphRef.current) {
              applyGraphForAssertionView(assertionView, assertedGraphRef.current, inferredGraph);
            }
          })
          .catch((err) => {
            console.warn('[AdvancedGraphView] Inferred graph prefetch failed (non-fatal):', err);
          });
        
        graphLog('[AdvancedGraphView] ✅ Initial hierarchy — visible subset, expand with toolbar or double-click');
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      setError(errorMessage);
      console.error('[AdvancedGraphView D3] ❌ Error fetching graph data:', errorMessage, error);
      setLoading(false);
    }
  }, [projectId, updateHierarchyState, assertionView, applyGraphForAssertionView, fetchInferredGraphData]);

  useEffect(() => {
    if (!assertedGraphRef.current) return;
    applyGraphForAssertionView(assertionView, assertedGraphRef.current, inferredGraphRef.current);
  }, [assertionView, applyGraphForAssertionView]);

  /**
   * ========================================================================
   * D3 VISUALIZATION - OPTIMIZED FOR PERFORMANCE
   * ========================================================================
   */
  useEffect(() => {
    if (!svgRef.current || filteredNodes.length === 0) return;

    const startTime = performance.now();

    // VOWL topology transform: split shared Thing/Literal hubs into per-edge
    // duplicates, drop subClassOf→Thing, merge equivalent classes. Only the render
    // graph changes — sidebar/legend/analytics stay on the filtered graph.
    const vowlGraph = visualizationType === 'vowl'
      ? applyVowlTransform(filteredNodes, filteredEdges, {
          mergeEquivalents: vowlOptions.mergeEquivalents
        })
      : null;
    const renderNodes = vowlGraph ? vowlGraph.nodes : filteredNodes;
    const renderEdges = vowlGraph ? vowlGraph.edges : filteredEdges;

    // VOWL mode always renders on the VOWL light canvas (user-preferred look),
    // so all theme-dependent colors inside this effect use effectiveDark instead
    // of isDarkTheme. Other modes keep following the app theme.
    const effectiveDark = visualizationType === 'vowl' ? false : isDarkTheme;

    // Derived per render, mode-independent — replaces the former isLargeGraph state,
    // which was only ever set in the ontograph branch and went stale on mode switch.
    const isLargeGraph = renderNodes.length > LARGE_GRAPH_THRESHOLD;

    if (GRAPH_DEBUG) {
      console.log('[AdvancedGraphView D3] 🎨 Initializing D3 visualization');
      console.log('[AdvancedGraphView D3] 📊 Nodes:', filteredNodes.length, 'Edges:', filteredEdges.length);

      // Log edge types distribution
      const edgesByType: Record<string, number> = {};
      filteredEdges.forEach(e => {
        edgesByType[e.type] = (edgesByType[e.type] || 0) + 1;
      });
      console.log('[AdvancedGraphView D3] 🔗 Edges by type:', edgesByType);

      // Log propertyRelation edges
      const propEdges = filteredEdges.filter(e => e.type === 'propertyRelation');
      if (propEdges.length > 0) {
        console.log('[AdvancedGraphView D3] 🔥 PropertyRelation edges found:', propEdges.length);
        console.log('[AdvancedGraphView D3] Sample edges:', propEdges.slice(0, 3).map(e => ({
          from: filteredNodes.find(n => n.id === e.from)?.label,
          to: filteredNodes.find(n => n.id === e.to)?.label,
          label: e.label
        })));
      } else {
        console.warn('[AdvancedGraphView D3] ⚠️ NO propertyRelation edges found!');
      }
    }

    const isSpatial3D = visualizationType === 'spatial3d';
    const nodeDegreeMap = buildDegreeMap(renderEdges);
    const maxBetweenness = Math.max(0.001, ...graphAnalytics.betweenness.values());
    const clusterFor = (nodeId: string) => graphAnalytics.communities.get(nodeId);
    const influenceSize = (nodeId: string, base: number): number => {
      if (!sizeByInfluence || visualizationType === 'ontograph') return base;
      const b = graphAnalytics.betweenness.get(nodeId) ?? 0;
      return base + Math.round(Math.sqrt(b / maxBetweenness) * 14);
    };

    const svg = d3.select(svgRef.current)
      .on('click', () => {
        setSelectedNodes(new Set());
        setSelectedEdgeId(null);
        setSelectedNodeInfo(null);
      });
    // VOWL's signature light blue-gray canvas — slightly cooler/cleaner than VOWL
    svg.style('background', (visualizationType === 'vowl' ? '#ecf0f1' : '') as string);
    const g = d3.select(gRef.current);

    const width = svgRef.current.clientWidth;
    const height = svgRef.current.clientHeight;

    const project3DNode = (node: D3Node) => {
      const z = node.z || 0;
      const perspective = 900;
      const scale = perspective / (perspective + z);
      const x = width / 2 + ((node.x || width / 2) - width / 2) * scale;
      const y = height / 2 + ((node.y || height / 2) - height / 2) * scale + z * 0.06;
      node.projectedX = x;
      node.projectedY = y;
      node.depthScale = scale;
      return { x, y, scale };
    };

    const getRenderPoint = (node: D3Node) => {
      if (!isSpatial3D) {
        return { x: node.x || 0, y: node.y || 0, scale: 1 };
      }
      if (node.projectedX == null || node.projectedY == null || node.depthScale == null) {
        return project3DNode(node);
      }
      return { x: node.projectedX, y: node.projectedY, scale: node.depthScale };
    };

    // Clear existing content
    g.selectAll('*').remove();

    // Create or update arrow markers for each edge type
    let defs = svg.select<SVGDefsElement>('defs');
    if (defs.empty()) {
      defs = svg.append('defs');
    }
    
    // Remove existing markers to ensure clean state
    defs.selectAll('marker').remove();
    
    const isDark = effectiveDark;

    // VOWL-specific arrow markers — neutral filled triangles matching the neutral
    // property lines (VOWL: the property kind lives in the label chip,
    // not in line/arrow color). One id per kind is kept so hover styling can still
    // target them individually.
    // Object property / axiom markers use black like VOWL
    const vowlArrowFill = isDark ? '#94a3b8' : '#000000';
    (['arrow-vowl-object', 'arrow-vowl-data', 'arrow-vowl-annotation'] as const).forEach(id => {
      defs.append('marker')
        .attr('id', id)
        .attr('viewBox', '0 0 10 10')
        .attr('refX', 10).attr('refY', 5)
        .attr('markerWidth', 8).attr('markerHeight', 8)
        .attr('orient', 'auto')
        .append('path').attr('d', 'M0,0 L10,5 L0,10 Z')
        .attr('fill', vowlArrowFill);
    });

    // VOWL arrowhead convention: subClassOf/isA edges get a HOLLOW outlined
    // triangle; type/property edges get filled triangles. The shape difference (not
    // just color) is what makes relationship kinds readable at a glance.
    const canvasBg = visualizationType === 'vowl' ? '#ecf0f1' : (isDark ? '#111827' : '#ffffff');
    const appendArrowMarker = (
      id: string,
      color: string,
      hollow: boolean
    ) => {
      const marker = defs
        .append('marker')
        .attr('id', id)
        .attr('viewBox', '-1 -1 12 12')
        .attr('refX', hollow ? 11 : 10)
        .attr('refY', 5)
        .attr('markerWidth', hollow ? 11 : 8)
        .attr('markerHeight', hollow ? 11 : 8)
        .attr('orient', 'auto');
      marker
        .append('path')
        .attr('d', 'M0,0 L10,5 L0,10 Z')
        .attr('fill', hollow ? canvasBg : color)
        .attr('stroke', color)
        .attr('stroke-width', hollow ? 1.4 : 0);
    };

    // Generic VOWL markers for other types
    Object.entries(EDGE_TYPE_COLORS).forEach(([type, color]) => {
      // Determine VOWL color for this edge type
      let vowlColor = color;
      if (type === 'subClassOf') vowlColor = isDark ? '#9ca3af' : '#374151';
      // Blue/orange, not green/red — matches VOWLNotationService.edgeToVOWLEdge (avoids the
      // red-green color-vision-deficiency pairing for these two opposite-meaning edge types).
      else if (type === 'equivalentClass') vowlColor = '#2563eb';
      else if (type === 'disjointWith') vowlColor = '#f97316';
      else if (type === 'domain') vowlColor = '#666666';
      else if (type === 'range') vowlColor = '#666666';

      appendArrowMarker(`arrow-vowl-${type}`, vowlColor, type === 'subClassOf' || type === 'subPropertyOf');
    });

    // Standard arrow markers using TRIANGLES for force and ontograph modes
    Object.entries(EDGE_TYPE_COLORS).forEach(([type, color]) => {
      // Determine color for each edge type in force mode
      let forceColor = color;
      if (type === 'subClassOf') forceColor = isDark ? '#fbbf24' : '#FFA500';
      else if (type === 'instanceOf') forceColor = isDark ? '#cbd5e1' : '#000000';
      else if (type === 'propertyRelation') forceColor = isDark ? '#cbd5e1' : '#000000';
      else if (type === 'domain') forceColor = '#666666';
      else if (type === 'range') forceColor = '#666666';

      appendArrowMarker(`arrow-${type}`, forceColor, type === 'subClassOf' || type === 'subPropertyOf');
    });

    // OntoGraph specific arrow markers
    const ontographColors = {
      subClassOf: isDark ? '#64B5F6' : '#1976D2',
      instanceOf: isDark ? '#fbbf24' : '#FFA726',
      objectProperty: isDark ? '#81C784' : '#388E3C',
      dataProperty: isDark ? '#f472b6' : '#ec4899',
      annotationProperty: isDark ? '#a78bfa' : '#8b5cf6'
    };

    Object.entries(ontographColors).forEach(([type, color]) => {
      appendArrowMarker(`arrow-ontograph-${type}`, color, type === 'subClassOf');
    });

    if (GRAPH_DEBUG) console.log('[AdvancedGraphView] ✅ Arrow markers created (hollow subClassOf, filled properties)');

    // ── Premium visual defs: gradients & glow filters ─────────────────────
    defs.selectAll('.premium-def').remove();

    // Card background gradient (ontograph mode) — subtle vertical sheen
    const cardGrad = defs.append('linearGradient')
      .attr('class', 'premium-def')
      .attr('id', 'card-grad')
      .attr('x1', '0').attr('y1', '0').attr('x2', '0').attr('y2', '1');
    cardGrad.append('stop').attr('offset', '0%').attr('stop-color', isDark ? '#2a3850' : '#ffffff');
    cardGrad.append('stop').attr('offset', '100%').attr('stop-color', isDark ? '#1b2436' : '#eef2f9');

    // Glow filters: selection (indigo), hover (per-node accent via per-type filters)
    const makeGlow = (id: string, color: string, blur: number, opacity: number) => {
      const f = defs.append('filter')
        .attr('class', 'premium-def')
        .attr('id', id)
        .attr('x', '-40%').attr('y', '-40%')
        .attr('width', '180%').attr('height', '180%');
      f.append('feDropShadow')
        .attr('dx', 0).attr('dy', 0)
        .attr('stdDeviation', blur)
        .attr('flood-color', color)
        .attr('flood-opacity', opacity);
    };
    makeGlow('sel-glow', '#818cf8', 5, 0.85);
    Object.entries(ACCENT_COLORS).forEach(([type, color]) => makeGlow(`hover-glow-${type}`, color, 4, 0.7));

    // Glossy radial gradient factory — gives flat fills gentle 3D depth.
    // Cached per color so defs stay small no matter how many nodes render.
    const glossyCache = new Map<string, string>();
    const glossyFill = (color: string | undefined): string => {
      if (!color || color.startsWith('url(')) return color || '';
      const c = d3.color(color);
      if (!c) return color;
      const key = color.replace(/[^a-zA-Z0-9]/g, '');
      if (glossyCache.has(key)) return glossyCache.get(key)!;
      const grad = defs.append('radialGradient')
        .attr('class', 'premium-def')
        .attr('id', `ng-${key}`)
        .attr('cx', '35%').attr('cy', '30%').attr('r', '80%');
      grad.append('stop').attr('offset', '0%').attr('stop-color', c.brighter(0.55).formatHex());
      grad.append('stop').attr('offset', '55%').attr('stop-color', color);
      grad.append('stop').attr('offset', '100%').attr('stop-color', c.darker(0.35).formatHex());
      const url = `url(#ng-${key})`;
      glossyCache.set(key, url);
      return url;
    };

    // Linear accent gradient factory (stripes, badges, expanders)
    const accentCache = new Map<string, string>();
    const accentGrad = (color: string): string => {
      const c = d3.color(color);
      if (!c) return color;
      const key = color.replace(/[^a-zA-Z0-9]/g, '');
      if (accentCache.has(key)) return accentCache.get(key)!;
      const grad = defs.append('linearGradient')
        .attr('class', 'premium-def')
        .attr('id', `ag-${key}`)
        .attr('x1', '0').attr('y1', '0').attr('x2', '1').attr('y2', '1');
      grad.append('stop').attr('offset', '0%').attr('stop-color', c.brighter(0.5).formatHex());
      grad.append('stop').attr('offset', '100%').attr('stop-color', c.darker(0.25).formatHex());
      const url = `url(#ag-${key})`;
      accentCache.set(key, url);
      return url;
    };

    // Prepare D3 data — reuse saved positions for stable expand/collapse
    const savedPositions = nodePositionsRef.current;
    const hasSavedPositions = savedPositions.size > 0;
    const d3Nodes: D3Node[] = renderNodes.map((node, index) => {
      let baseSize = influenceSize(node.id, node.size || settings.nodeSize);
      // VOWL-style sizing: mostly UNIFORM class circles (VOWL). Degree
      // ballooning made hubs huge and leaves tiny — when Fit zooms out, everything
      // becomes an unreadable pale hairball.
      if (visualizationType === 'vowl') {
        const isThingNode = isThingIri(node.id) || node.label === 'Thing';
        if (isThingNode) {
          baseSize = Math.max(10, Math.round(settings.nodeSize * 0.72));
        } else if (node.type === 'class') {
          // Real VOWL convention: the circle grows to fit the label, not the
          // other way around — the previous flat multiplier left the text-fit
          // budget (below) at ~6 characters, truncating "Elevation" to "Ele..".
          // Grow to comfortably fit up to ~13 characters of the real label;
          // longer names still truncate gracefully since the text callback
          // derives its own budget from this same size. Capped (not degree-
          // based) deliberately — see the comment above this block explaining
          // why unbounded hub-degree sizing was reverted. The 13-char/2.5x
          // caps (down from an initial 16/3x) were retuned after verifying in
          // the graph-view-plugin test harness: vowlTransform.ts's packing
          // spacing (CLASS_NODE_R) is a single flat constant approximating
          // "typical" class-circle radius, so keeping less size variance
          // across nodes matters more than maximizing any one label's fit.
          const charWidthPx = vowlOptions.labelFontSize * (7 / 11);
          const avgScale = (vowlOptions.nodeWidthScale + vowlOptions.nodeHeightScale) / 2;
          const visibleChars = Math.min((node.label || '').length, 13);
          const desiredDiameter = visibleChars * charWidthPx + 8;
          const fittedSize = desiredDiameter / (2.7 * avgScale);
          const minSize = Math.round(settings.nodeSize * 1.15);
          const maxSize = Math.round(settings.nodeSize * 2.5);
          baseSize = Math.max(minSize, Math.min(Math.round(fittedSize), maxSize));
        } else if (node.type === 'datatype') {
          baseSize = Math.round(settings.nodeSize * 1.05);
        } else if (node.type === 'individual') {
          baseSize = Math.round(settings.nodeSize * 1.05);
        }
      }
      const sizedNode = { ...node, size: baseSize };
      // Reuse previous position if the node was already rendered (OntoCode stability)
      const saved = savedPositions.get(node.id);
      if (saved) {
        return { ...sizedNode, x: saved.x, y: saved.y };
      }

      // New node — place near its parent if we have a parent position
      if (hasSavedPositions) {
        const parentEdge = allEdges.find(e => e.from === node.id && (e.type === 'subClassOf' || e.type === 'subPropertyOf' || e.type === 'instanceOf'));
        const parentPos = parentEdge ? savedPositions.get(parentEdge.to) : undefined;
        if (parentPos) {
          const angle = Math.random() * Math.PI * 2;
          const dist = 80 + Math.random() * 60;
          return { ...sizedNode, x: parentPos.x + Math.cos(angle) * dist, y: parentPos.y + Math.sin(angle) * dist };
        }
      }

      let x: number, y: number;
      
      if (visualizationType === 'vowl') {
        // Better initial spread for VOWL mode to reduce initial overlapping
        const cols = Math.ceil(Math.sqrt(renderNodes.length));
        const col = index % cols;
        const row = Math.floor(index / cols);
        const cellWidth = Math.max(150, (width - 300) / cols);
        const cellHeight = Math.max(150, (height - 300) / cols);
        
        x = 150 + col * cellWidth + (Math.random() - 0.5) * 120;
        y = 150 + row * cellHeight + (Math.random() - 0.5) * 120;
      } else if (visualizationType === 'spatial3d') {
        const angle = hashToUnit(node.id) * Math.PI * 2;
        const ring = 140 + (index % 9) * 32;
        const jitter = (hashToUnit(`${node.id}:jitter`) - 0.5) * 90;
        x = width / 2 + Math.cos(angle) * (ring + jitter);
        y = height / 2 + Math.sin(angle) * (ring + jitter);
      } else if (visualizationType === 'ontograph' && ontographLayoutType === 'spring') {
        x = width / 2 + (Math.random() - 0.5) * width * 0.6;
        y = height / 2 + (Math.random() - 0.5) * height * 0.6;
      } else {
        x = width / 2 + (Math.random() - 0.5) * 100;
        y = height / 2 + (Math.random() - 0.5) * 100;
      }
      
      const z = visualizationType === 'spatial3d'
        ? Math.max(-260, Math.min(420, (hashToUnit(`${node.id}:depth`) - 0.35) * 680 - (nodeDegreeMap.get(node.id) || 0) * 8))
        : undefined;
      return { ...sizedNode, x, y, z };
    });

    if (isSpatial3D) {
      d3Nodes.forEach(node => {
        if (node.z == null) {
          node.z = Math.max(-260, Math.min(420, (hashToUnit(`${node.id}:depth`) - 0.35) * 680 - (nodeDegreeMap.get(node.id) || 0) * 8));
        }
        project3DNode(node);
      });
    }

    let vowlLayout: VowlLayoutResult | null = null;
    if (visualizationType === 'vowl') {
      // Compute radii for a *very* weak optional radial hint only. Do NOT pin
      // nodes onto rings — that artificial halo is what made our layout look
      // worse than VOWL's organic neighborhood clusters.
      vowlLayout = computeVowlLayout(d3Nodes, width, height, classDistance, datatypeDistance);
    }

    // Apply OntoGraph hierarchical layout if selected
    if (visualizationType === 'ontograph') {
      const nodeCount = filteredNodes.length;
      const isLarge = isLargeGraph;

      if (GRAPH_DEBUG) console.log(`[OntoGraph] Applying ${ontographLayoutType} layout for ${nodeCount} nodes`);
      
      let positionMap: Map<string, { x: number; y: number }>;
      
      switch (ontographLayoutType) {
        case 'grid':
          positionMap = layouts.applyGridLayout(filteredNodes, filteredEdges, { width, height });
          break;
        case 'radial':
          positionMap = layouts.applyRadialLayout(filteredNodes, filteredEdges, { width, height });
          break;
        case 'tree':
          positionMap = layouts.applyTreeLayout(filteredNodes, filteredEdges, {
            width,
            height,
            orientation: 'vertical',
            nodeSpacing: isLarge ? 140 : 220,
            levelSpacing: isLarge ? 220 : 340
          });
          break;
        case 'horizontal':
          positionMap = layouts.applyTreeLayout(filteredNodes, filteredEdges, {
            width,
            height,
            orientation: 'horizontal',
            nodeSpacing: isLarge ? 140 : 220,
            levelSpacing: isLarge ? 240 : 360
          });
          break;
        case 'spring':
          // Spring layout uses the force simulation
          // We return an empty map here and let the force simulation handle it
          positionMap = new Map();
          break;
        default:
          positionMap = layouts.applyOntoGraphLayout(filteredNodes, filteredEdges, {
            width,
            height,
            horizontalSpacing: isLarge ? 240 : 360,
            verticalSpacing: isLarge ? 110 : 160,
            centerX: width / 2,
            centerY: height / 2
          });
      }
      
      // Skip refinement for very large graphs or non-hierarchical layouts
      const refinedMap = (isLarge || ontographLayoutType === 'grid' || ontographLayoutType === 'radial' || ontographLayoutType === 'tree' || ontographLayoutType === 'horizontal' || ontographLayoutType === 'spring') 
        ? positionMap 
        : layouts.refineOntoGraphLayout(positionMap, filteredNodes, filteredEdges, 30);
      
      // Apply positions from layout and fix them
      d3Nodes.forEach(node => {
        const pos = refinedMap.get(node.id);
        if (pos) {
          node.x = pos.x;
          node.y = pos.y;
          // Fix positions for all OntoGraph layouts EXCEPT spring to prevent force simulation from moving them
          if (ontographLayoutType !== 'spring') {
            node.fx = pos.x; 
            node.fy = pos.y;
          }
        }
      });
      
      console.log(`[OntoGraph] ${ontographLayoutType} layout applied to`, d3Nodes.length, 'nodes');
    }

    const nodeMap = new Map(d3Nodes.map(n => [n.id, n]));

    const d3Edges: D3Edge[] = renderEdges.map(edge => ({
      ...edge,
      source: nodeMap.get(edge.from)!,
      target: nodeMap.get(edge.to)!
    })).filter(e => e.source && e.target);

    // VOWL neighborhood placement: seed clusters FAR APART so the graph
    // reads as separate hubs (Person / Document / Agent…) instead of one blob.
    // Re-seed whenever the hub set changes (async FOAF load after an empty first
    // paint used to skip this and leave a hairball of long arcs).
    const nodeHubId = new Map<string, string>();
    vowlPinnedHubIdsRef.current = new Set();
    if (visualizationType === 'vowl') {
      const neighborhoods = buildVowlNeighborhoods(renderNodes, renderEdges);
      for (const nb of neighborhoods) {
        for (const id of nb.memberIds) nodeHubId.set(id, nb.hubId);
      }
      const fingerprint = `v26-measured-aabb|${neighborhoods.map(n => n.hubId).sort().join('|')}`;
      const hubsChanged = fingerprint !== vowlHubFingerprintRef.current;
      if (hubsChanged) {
        vowlHubFingerprintRef.current = fingerprint;
        nodePositionsRef.current.clear();
      }
      if (hubsChanged || !hasSavedPositions) {
        const seeded = placeVowlNeighborhoods(neighborhoods, width, height, renderNodes, renderEdges);
        d3Nodes.forEach(node => {
          const p = seeded.get(node.id);
          if (p) {
            node.x = p.x;
            node.y = p.y;
            // Pin EVERY seeded node so force can't collapse circles into a fan
            node.fx = p.x;
            node.fy = p.y;
            nodePositionsRef.current.set(node.id, { x: p.x, y: p.y });
          }
        });
      } else {
        // Restore pins on previously seeded ring positions
        d3Nodes.forEach(node => {
          const saved = nodePositionsRef.current.get(node.id);
          if (!saved) return;
          node.x = saved.x;
          node.y = saved.y;
          node.fx = saved.x;
          node.fy = saved.y;
        });
      }
      d3Nodes.forEach(node => {
        if (node.type !== 'class' || isThingIri(node.id)) return;
        const hub = nodeHubId.get(node.id);
        if (hub === node.id) vowlPinnedHubIdsRef.current.add(node.id);
      });
      if (GRAPH_DEBUG) {
        console.log(`[VOWL+] ${hubsChanged || !hasSavedPositions ? 'Seeded' : 'Kept'} ${neighborhoods.length} neighborhoods:`,
          neighborhoods.map(n => `${n.hubId.split(/[#/]/).pop()}(${n.memberIds.length})`).join(', '));
      }
    }
    
    // Calculate curvature for edges with multiple connections between same nodes.
    // VOWL also needs this — Agent→Document alone has interest/weblog/tipjar/openid.
    const edgeCurvature = new Map<string, number>();
    const nodePairEdges = new Map<string, D3Edge[]>();
    d3Edges.forEach(edge => {
      const sourceId = (edge.source as D3Node).id;
      const targetId = (edge.target as D3Node).id;
      const pairKey = [sourceId, targetId].sort().join('|');
      if (!nodePairEdges.has(pairKey)) nodePairEdges.set(pairKey, []);
      nodePairEdges.get(pairKey)!.push(edge);
    });
    nodePairEdges.forEach(edges => {
      if (edges.length <= 1) {
        edgeCurvature.set(edges[0].id, visualizationType === 'force' ? 15 : 0);
        return;
      }
      const curveStrength = visualizationType === 'vowl' ? 42 : 40;
      edges.forEach((edge, index) => {
        const offset = Math.ceil((index + 1) / 2);
        const sign = index % 2 === 0 ? 1 : -1;
        edgeCurvature.set(edge.id, sign * offset * curveStrength);
      });
    });

    console.log('[AdvancedGraphView D3] ✅ Prepared D3 data - Nodes:', d3Nodes.length, 'Edges:', d3Edges.length);

    const nodeCount = d3Nodes.length;

    // ── VOWL chip physics ─────────────────────────────────────────────────
    // In VOWL the property-label rectangle IS a node in the force simulation,
    // sitting mid-edge and linked to both endpoints. That's what spaces edges
    // apart and keeps labels from overlapping. Here: each chip edge gets an
    // invisible physics node; the edge is drawn as a curve through it and the
    // label chip is glued to it. Capped at the same density threshold as chips.
    const isVowlChipEdge = (d: any) => d.type === 'propertyRelation' || d.type === 'subClassOf';
    const vowlChipsAlwaysVisible = visualizationType === 'vowl'
      && d3Edges.filter(isVowlChipEdge).length <= 400;

    const chipNodes: D3Node[] = [];
    if (visualizationType === 'vowl' && vowlChipsAlwaysVisible) {
      // Group by endpoint pair so parallel properties (openid/weblog/tipjar…)
      // get distinct along-edge + perpendicular slots like VOWL.
      const pairBuckets = new Map<string, D3Edge[]>();
      d3Edges.forEach(e => {
        if (!isVowlChipEdge(e)) return;
        const s = e.source as D3Node;
        const t = e.target as D3Node;
        const key = [s.id, t.id].sort().join('|');
        if (!pairBuckets.has(key)) pairBuckets.set(key, []);
        pairBuckets.get(key)!.push(e);
      });

      // Per-hub spoke counter so chips on adjacent spokes stagger along-edge
      const hubSpokeCount = new Map<string, number>();

      pairBuckets.forEach(group => {
        const n = group.length;
        group.forEach((e, index) => {
          const s = e.source as D3Node;
          const t = e.target as D3Node;
          const chipId = `__vowlchip__${e.id}`;
          const labelLen = String(e.label || (e.type === 'subClassOf' ? 'Subclass of' : '')).length;
          // Half-width at ~11px font ≈ len·2.8+10; capping at 48 made the
          // repulsion pass blind to long labels ("has observation temporal
          // interval" is ~100px half-width) — cap at 96 instead.
          const chipRadius = Math.max(18, Math.min(96, labelLen * 2.8 + 10));

          const sx = s.x ?? width / 2;
          const sy = s.y ?? height / 2;
          const tx = t.x ?? width / 2;
          const ty = t.y ?? height / 2;
          const dx = tx - sx;
          const dy = ty - sy;
          const len = Math.sqrt(dx * dx + dy * dy) || 1;
          const ux = dx / len;
          const uy = dy / len;
          const px = -uy;
          const py = ux;

          // Chip slot from ABSOLUTE clearances, not fixed fractions:
          //   t ∈ [ (hubR + chipH) / L , 1 − (endR + chipH) / L ]
          // Adjacent spokes of the same hub alternate near/far inside that
          // window (staggered labeling) so short-fan chips never share a ring.
          let tAlong = 0.5;
          let side = 0;
          const touchesLiteral =
            s.type === 'datatype' || t.type === 'datatype' ||
            s.label === 'Literal' || t.label === 'Literal';
          const sIsLit = s.type === 'datatype' || s.label === 'Literal';
          const hubEnd = touchesLiteral ? (sIsLit ? t : s) : s;
          const spokeIdx = hubSpokeCount.get(hubEnd.id) ?? 0;
          hubSpokeCount.set(hubEnd.id, spokeIdx + 1);

          const CLASS_R = 30;      // class circle + stroke
          const LIT_CLEAR = 40;    // half literal box + margin
          const chipHalfH = 13;
          const sClear = (s.type === 'datatype' || s.label === 'Literal') ? LIT_CLEAR : CLASS_R;
          const tClear = (t.type === 'datatype' || t.label === 'Literal') ? LIT_CLEAR : CLASS_R;
          const lo = Math.min(0.45, (sClear + chipHalfH) / len);
          const hi = Math.max(0.55, 1 - (tClear + chipHalfH) / len);

          if (n > 1) {
            const slot = index - (n - 1) / 2;
            tAlong = Math.min(hi, Math.max(lo, 0.45 + slot * Math.min(0.14, 0.5 / n)));
            side = slot * Math.max(34, 18 + chipRadius * 0.55);
          } else if (e.type === 'subClassOf') {
            tAlong = lo + (hi - lo) * 0.5;
          } else {
            // Stagger along + alternate side of spoke so long horizontal chips
            // on neighboring spokes never stack (AABB still cleans residuals).
            tAlong = lo + (hi - lo) * (spokeIdx % 2 === 0 ? 0.28 : 0.72);
            side = (spokeIdx % 2 === 0 ? 1 : -1) * Math.max(18, chipRadius * 0.35);
          }

          const cx0 = sx + dx * tAlong + px * side;
          const cy0 = sy + dy * tAlong + py * side;

          // Seeded, not pinned: (cx0, cy0) is a good starting guess (staggered
          // by spoke so parallel chips don't start exactly on top of each
          // other), but real charge + rigid half-links now do the separating
          // at simulation runtime instead of freezing this one-shot geometry.
          const chip: D3Node = {
            id: chipId,
            label: '',
            type: 'class',
            size: chipRadius,
            x: cx0,
            y: cy0
          };
          (chip as any).__isChip = true;
          (chip as any).__chipRadius = chipRadius;
          (chip as any).__chipEdge = e;
          (chip as any).__chipAlong = tAlong;
          (chip as any).__chipSide = side;
          (e as any).__chipNode = chip;
          chipNodes.push(chip);
        });
      });

      // AABB (box) repulsion — chips are wide horizontal rectangles, NOT circles.
      // Circular separation let two 180px labels stack with centers only 40px apart.
      const CHIP_HH = 12;
      for (let pass = 0; pass < 24; pass++) {
        let moved = false;
        for (let i = 0; i < chipNodes.length; i++) {
          for (let j = i + 1; j < chipNodes.length; j++) {
            const a = chipNodes[i];
            const b = chipNodes[j];
            const ax = a.x ?? 0;
            const ay = a.y ?? 0;
            const bx = b.x ?? 0;
            const by = b.y ?? 0;
            const hwA = (a as any).__chipRadius || 20;
            const hwB = (b as any).__chipRadius || 20;
            let dx = bx - ax;
            let dy = by - ay;
            const overlapX = hwA + hwB + 14 - Math.abs(dx);
            const overlapY = CHIP_HH + CHIP_HH + 10 - Math.abs(dy);
            if (overlapX <= 0 || overlapY <= 0) continue;
            // Separate on the shallow axis (classic AABB resolve)
            if (overlapX < overlapY) {
              const sx = (dx === 0 ? (i % 2 === 0 ? 1 : -1) : Math.sign(dx)) * (overlapX / 2);
              a.x = ax - sx;
              b.x = bx + sx;
            } else {
              const sy = (dy === 0 ? (i % 2 === 0 ? 1 : -1) : Math.sign(dy)) * (overlapY / 2);
              a.y = ay - sy;
              b.y = by + sy;
            }
            // Seed refinement only — no fx/fy pin. Charge + rigid half-links
            // keep this separation (and correct it further) once ticking starts.
            moved = true;
          }
        }
        if (!moved) break;
      }

      // Chips must also clear node glyphs (yellow Literal boxes AND class circles)
      // using AABB against each glyph's bounding box.
      const glyphNodes = d3Nodes.filter(
        nd => nd.type === 'datatype' || nd.label === 'Literal' || nd.type === 'class'
      );
      for (let pass = 0; pass < 14; pass++) {
        let movedAny = false;
        for (const chip of chipNodes) {
          const e = (chip as any).__chipEdge as D3Edge | undefined;
          if (!e) continue;
          const s = e.source as D3Node;
          const t = e.target as D3Node;
          const chipHw = (chip as any).__chipRadius || 20;
          for (const g of glyphNodes) {
            const isLit = g.type === 'datatype' || g.label === 'Literal';
            const gHw = isLit ? 48 : 30;
            const gHh = isLit ? 16 : 30;
            const dx = (chip.x ?? 0) - (g.x ?? 0);
            const dy = (chip.y ?? 0) - (g.y ?? 0);
            const overlapX = chipHw + gHw + 10 - Math.abs(dx);
            const overlapY = CHIP_HH + gHh + 8 - Math.abs(dy);
            if (overlapX <= 0 || overlapY <= 0) continue;

            if (g === s || g === t) {
              // Own endpoint: slide toward the opposite end of the spoke
              const other = g === s ? t : s;
              const hx = (other.x ?? 0) - (chip.x ?? 0);
              const hy = (other.y ?? 0) - (chip.y ?? 0);
              const hl = Math.hypot(hx, hy) || 1;
              const step = Math.min(overlapX, overlapY) * 0.85;
              chip.x = (chip.x ?? 0) + (hx / hl) * step;
              chip.y = (chip.y ?? 0) + (hy / hl) * step;
            } else if (overlapX < overlapY) {
              const sx = (dx === 0 ? 1 : Math.sign(dx)) * overlapX;
              chip.x = (chip.x ?? 0) + sx;
            } else {
              const sy = (dy === 0 ? 1 : Math.sign(dy)) * overlapY;
              chip.y = (chip.y ?? 0) + sy;
            }
            movedAny = true;
          }
        }
        if (!movedAny) break;
      }

      // Bake resolved positions back into (along, side) — used as a fallback by
      // downstream code that still reads __chipAlong/__chipSide directly; the
      // free chip itself is now driven by the simulation, not this projection.
      chipNodes.forEach(chip => {
        const e = (chip as any).__chipEdge as D3Edge | undefined;
        if (!e) return;
        const s = e.source as D3Node;
        const t = e.target as D3Node;
        const sx = s.x ?? 0;
        const sy = s.y ?? 0;
        const tx = t.x ?? 0;
        const ty = t.y ?? 0;
        const dx = tx - sx;
        const dy = ty - sy;
        const len = Math.hypot(dx, dy) || 1;
        const ux = dx / len;
        const uy = dy / len;
        const px = -uy;
        const py = ux;
        const cx = chip.x ?? sx;
        const cy = chip.y ?? sy;
        const along = ((cx - sx) * ux + (cy - sy) * uy) / len;
        const side = (cx - sx) * px + (cy - sy) * py;
        (chip as any).__chipAlong = Math.max(0.12, Math.min(0.88, along));
        (chip as any).__chipSide = side;
      });
    }

    // Simulation links: chip edges are replaced by two half-links through their
    // chip node (the direct link would fight the chip's midpoint position).
    const buildSimLinks = (): any[] => {
      if (chipNodes.length === 0) return d3Edges;
      const links: any[] = [];
      d3Edges.forEach(e => {
        const chip = (e as any).__chipNode as D3Node | undefined;
        if (chip) {
          links.push({ source: e.source, target: chip, __halfOf: e });
          links.push({ source: chip, target: e.target, __halfOf: e });
        } else {
          links.push(e);
        }
      });
      return links;
    };
    const simLinks = buildSimLinks();
    const simNodes = chipNodes.length > 0 ? [...d3Nodes, ...chipNodes] : d3Nodes;
    
    // Border-to-border radius estimate so a half-link's target distance is the
    // visible gap between node edges, not a center-to-center constant that lets
    // a big class ellipse (or a long chip label) "eat" its own edge.
    const nodeRadiusFor = (n: D3Node): number => {
      const size = n.size || settings.nodeSize || 20;
      if ((n as any).__isChip) return (n as any).__chipRadius || size;
      if (n.type === 'datatype' || n.label === 'Literal') return size * 1.8;
      return size * 1.8; // VOWL class circle (matches computeEdgePath's r*1.8+4 trim)
    };

    // Calculate link distance based on node types and VOWL distance controls
    const linkDistance = (edge: D3Edge): number => {
      // Half-links to a chip node cover half the parent edge each, plus the
      // actual radii of the two endpoints it spans (WebVOWL's
      // linkPartDistance = full/2 + domain.actualRadius() + range.actualRadius()).
      const half = (edge as any).__halfOf as D3Edge | undefined;
      if (half) {
        const s = edge.source as D3Node;
        const t = edge.target as D3Node;
        return linkDistance(half) / 2 + nodeRadiusFor(s) + nodeRadiusFor(t);
      }
      const source = edge.source as D3Node;
      const target = edge.target as D3Node;

      // VOWL: short arrows inside a neighborhood (Literals close, subclasses
      // a bit further). Do NOT multiply by 1.85× — that made long overrides.
      if (visualizationType === 'vowl') {
        // Match concentric-ring math (subclassR ≈ propR+95 ≈ 195–240)
        if (edge.type === 'subClassOf') return 200;
        if (edge.type === 'propertyRelation') {
          // Parallel edges between the same pair no longer need a hand-tuned
          // boost — free chips with real charge fan them out on their own.
          if (source.type === 'datatype' || target.type === 'datatype') return 100;
          if (isThingIri(source.id) || isThingIri(target.id) ||
              source.label === 'Thing' || target.label === 'Thing') return 110;
          return 150;
        }
        if (source.type === 'datatype' || target.type === 'datatype') return 100;
        if (edge.type === 'domain' || edge.type === 'range') return 110;
        return 140;
      }

      const distanceMultiplier = visualizationType === 'spatial3d' ? 1.45 : 1.0;
      
      if (edge.type === 'subClassOf') {
        return classDistance * 1.05 * distanceMultiplier;
      }
      
      if (edge.type === 'propertyRelation') {
        if (source.type === 'datatype' || target.type === 'datatype') {
          return Math.max(70, datatypeDistance * 0.95);
        }
        return classDistance * 3.2 * distanceMultiplier;
      }
      
      if (source.type === 'dataProperty' || target.type === 'dataProperty' ||
          source.type === 'datatype' || target.type === 'datatype') {
        return Math.max(70, datatypeDistance * 0.95);
      }
      
      if (edge.type === 'domain' || edge.type === 'range') {
        return classDistance * 0.9 * distanceMultiplier;
      }
      
      return classDistance * distanceMultiplier;
    };
    
    // Calculate link strength - hierarchy edges are stronger
    const linkStrength = (edge: D3Edge): number => {
      const half = (edge as any).__halfOf as D3Edge | undefined;
      if (half) {
        // Rigid half-links (WebVOWL uses strength 1 for exactly this reason):
        // a free chip with real charge needs a stiff link on both sides or it
        // gets flung, not fanned. The chip's own charge (below) does the
        // separating; the link's job is only to hold it near the edge line.
        if (visualizationType === 'vowl') return 0.95;
        return Math.min(1.0, linkStrength(half) * 1.35);
      }
      if (edge.type === 'subClassOf') {
        return visualizationType === 'vowl' ? 0.12 : 0.85;
      }
      if (edge.type === 'propertyRelation') {
        const source = edge.source as D3Node;
        const target = edge.target as D3Node;
        // Datatype stars: strong (Literals hug their class)
        if (source?.type === 'datatype' || target?.type === 'datatype') {
          return 0.95;
        }
        // Object properties between hubs: near-zero — geometry is disk-packed
        return visualizationType === 'vowl' ? 0.01 : (nodeCount > 100 ? 0.4 : 0.6);
      }
      return nodeCount > 100 ? 0.3 : 0.5;
    };
    
    // Calculate average node size for collision detection
    const avgNodeSize = settings.nodeSize || 20;
    
    // Force simulation configuration
    // Use physics for force, vowl, and ontograph spring mode
    const usePhysics = visualizationType !== 'ontograph' || ontographLayoutType === 'spring';

    // Entrance decision — once per mount, on the first data render. The bloom only
    // plays on a user's first-ever open (no persisted view), with visible physics.
    if (entranceRef.current.phase === 'pending') {
      let entranceMode: 'bloom' | 'calm' | 'static' = 'static';
      if (isFirstEverOpen && usePhysics && !prefersReducedMotion && !isLayoutPaused && !hasSavedPositions) {
        if (nodeCount <= BLOOM_MAX_VISIBLE) entranceMode = 'bloom';
        else if (nodeCount <= CALM_MAX_VISIBLE) entranceMode = 'calm';
      }
      entranceRef.current = { phase: entranceMode === 'static' ? 'done' : 'playing', mode: entranceMode };
      setEntrancePhase(entranceRef.current.phase);
      if (entranceMode === 'static' && isFirstEverOpen) {
        // First open rendered statically (large graph / reduced motion / tree mode):
        // the user has seen the graph, start persisting their view from here on.
        hasSeenGraphRef.current = true;
        saveLastView(projectId, { visualizationType, ontographLayoutType });
      }
    }
    const entrancePlaying = entranceRef.current.phase === 'playing' && !hasSavedPositions;


    const simulation = d3.forceSimulation<D3Node>(simNodes)
      .force('link', usePhysics ? d3.forceLink<D3Node, D3Edge>(simLinks)
        .id(d => d.id)
        .distance(linkDistance)
        .strength(linkStrength)
        .iterations(nodeCount > 2000 ? 1 : 4) : null)
      .force('charge', usePhysics ? d3.forceManyBody()
        .strength(d => {
          const node = d as D3Node;
          // Chip label nodes repel by their label footprint — long chips keep clear.
          // Chips are free now (rigid half-links hold them near the edge line —
          // see linkStrength — so charge alone fans them out instead of flinging
          // them; that earlier regression came from charge WITHOUT rigid links).
          if ((node as any).__isChip) {
            const chipR = (node as any).__chipRadius || 20;
            return -Math.min(260, chipR * 3.2);
          }
          // Seeded+pinned geometry — charge must not stretch the lattice.
          // Tiny residual only for unpinned chips/orphans.
          if (visualizationType === 'vowl') {
            if (node.fx != null && node.fy != null) return 0;
            if (isThingIri(node.id) || node.label === 'Thing') return -20;
            if (node.type === 'datatype') return -15;
            return -10;
          }
          // Force mode - adaptive strength for large graphs (100k nodes)
          if (visualizationType === 'force') {
            // Reduce charge strength for very large graphs to improve performance
            if (nodeCount > 50000) {
              return node.type === 'class' ? -300 : -200;
            } else if (nodeCount > 10000) {
              return node.type === 'class' ? -600 : -400;
            } else if (nodeCount > 1000) {
              return node.type === 'class' ? -1000 : -600;
            }
            return node.type === 'class' ? -1500 : -800;
          }
          if (visualizationType === 'spatial3d') {
            const degree = nodeDegreeMap.get(node.id) || 0;
            return node.type === 'class' ? -1200 - degree * 25 : -700 - degree * 15;
          }
          // Standard mode repulsion
          if (node.type === 'class') {
            return nodeCount > 100 ? -1000 : -1500;
          }
          return nodeCount > 100 ? -500 : -800;
        })
        .distanceMax(visualizationType === 'vowl' ? Math.min(width, height) * 1.2 : (nodeCount > 10000 ? 800 : 1200))
        .theta(nodeCount > 50000 ? 0.9 : 0.7) : null)
      .force('center', usePhysics && visualizationType !== 'vowl'
        ? d3.forceCenter(width / 2, height / 2).strength(visualizationType === 'spatial3d' ? 0.018 : 0.03)
        : null)
      .force('collision', usePhysics ? d3.forceCollide()
        .radius(d => {
          const node = d as D3Node;
          // Chip nodes reserve room for their actual label rectangle
          if ((node as any).__isChip) return ((node as any).__chipRadius || 28) * 1.35;
          const size = node.size || avgNodeSize;
          const isVeryLargeGraph = nodeCount > 10000;
          
          // Generous collision in VOWL mode — the gap that makes VOWL readable
          if (visualizationType === 'vowl') {
            // Soft collision — hard radii were shoving unpinned leftovers apart.
            // Seeded nodes are pinned; chips are mid-edge pinned.
            if ((node as any).__isChip) return ((node as any).__chipRadius || 20) * 0.9;
            if (isThingIri(node.id) || node.label === 'Thing') return size * 1.6;
            if (node.type === 'class') return size * 2.2;
            if (node.type === 'datatype') return size * 1.8;
            return size * 1.6;
          }
          // Force mode - adaptive collision radius for scalability
          if (visualizationType === 'force') {
            // Reduce collision radius for very large graphs to fit more nodes
            const scaleFactor = isVeryLargeGraph ? 0.7 : 1.0;
            if (node.type === 'class') {
              return size * 4.5 * scaleFactor; // Larger for class ellipses
            } else if (node.type === 'individual') {
              return size * 4.0 * scaleFactor; // Larger for individual rectangles
            }
            return size * 3.5 * scaleFactor;
          }
          if (visualizationType === 'spatial3d') {
            return size * (node.type === 'class' ? 4.2 : 3.4);
          }
          // OntoGraph mode - larger collision to prevent overlap with card nodes
          if (visualizationType === 'ontograph') {
            if (node.type === 'class') {
              return size * 8.5;
            } else if (node.type === 'datatype') {
              return size * 7.5;
            }
            return size * 7.0;
          }
          return size * 3.5; // Standard mode
        })
        .strength(1.0)
        .iterations(
          visualizationType === 'vowl'
            ? (nodeCount > 1000 ? 2 : 3)
            : (nodeCount > 2000 ? 3 : 6)
        ) : null)
      .force('y', usePhysics && visualizationType !== 'vowl' ? d3.forceY(d => {
        const node = d as D3Node;
        if (visualizationType === 'spatial3d') {
          return height / 2 + ((node.z || 0) * 0.08);
        }
        // Standard mode positioning
        if (node.type === 'class') {
          return height * 0.4;
        } else if (node.type === 'individual') {
          return height * 0.7;
        } else if (node.type === 'datatype') {
          return height * 0.6;
        }
        return height / 2;
      }).strength(0.15) : null)
      .force('x', usePhysics && visualizationType !== 'vowl' ? d3.forceX(d => {
        const node = d as D3Node;
        if (visualizationType === 'force') {
          if (node.type === 'class') {
            return width * 0.35;
          }
          if (node.type === 'individual') {
            return width * 0.62;
          }
          if (node.type === 'datatype') {
            return width * 0.78;
          }
        }
        if (visualizationType === 'spatial3d') {
          const sideBias = node.type === 'class' ? -0.04 : node.type === 'individual' ? 0.05 : 0;
          return width / 2 + width * sideBias;
        }
        return width / 2;
      }).strength(visualizationType === 'force' ? 0.14 : (visualizationType === 'spatial3d' ? 0.035 : 0.02)) : null)
      // Large graphs settle in ~100 ticks instead of ~230 (each tick is O(n log n))
      .alphaDecay(usePhysics ? (nodeCount > 2000 ? 0.05 : (visualizationType === 'vowl' ? 0.02 : (visualizationType === 'spatial3d' ? 0.025 : 0.03))) : 1)
      .velocityDecay(usePhysics ? (visualizationType === 'vowl' ? 0.6 : (visualizationType === 'spatial3d' ? 0.58 : 0.5)) : 0.8) // Increased for more damping
      .alpha(isLayoutPaused || !usePhysics ? 0 : (hasSavedPositions ? 0.15 : 1.0))
      .alphaMin(nodeCount > 2000 ? 0.005 : 0.001)
      .alphaTarget(0);

    // NOTE: vowl no longer pins nodes to concentric rings or a canvas center —
    // that was re-gluing the separate VOWL neighborhoods into one blob.
    // Instead: pull members toward their hub, push hubs apart, anchor hubs to seed.
    if (visualizationType === 'vowl' && usePhysics && nodeHubId.size > 0) {
      // Chip nodes: only inherit hub when both ends share one (local edges)
      d3Edges.forEach(e => {
        const chip = (e as any).__chipNode as D3Node | undefined;
        if (!chip) return;
        const hubFrom = nodeHubId.get(e.from);
        const hubTo = nodeHubId.get(e.to);
        if (hubFrom && hubFrom === hubTo) nodeHubId.set(chip.id, hubFrom);
      });

      simulation.force('vowlCluster', () => {
        // Hub centroids from free (non-chip) members + pinned hubs
        const sums = new Map<string, { x: number; y: number; n: number }>();
        for (const node of simNodes) {
          if (node.x == null || node.y == null) continue;
          if ((node as any).__isChip) continue;
          const hub = nodeHubId.get(node.id);
          if (!hub) continue;
          const s = sums.get(hub) || { x: 0, y: 0, n: 0 };
          s.x += node.x;
          s.y += node.y;
          s.n += 1;
          sums.set(hub, s);
        }
        const centroids = new Map<string, { x: number; y: number }>();
        sums.forEach((s, hub) => {
          if (s.n > 0) centroids.set(hub, { x: s.x / s.n, y: s.y / s.n });
        });

        // Pull non-hub members toward their neighborhood centroid (hubs stay hard-pinned)
        for (const node of simNodes) {
          if ((node as any).__isChip) continue;
          if (node.fx != null || node.fy != null) continue; // pinned hub
          if (node.x == null || node.y == null) continue;
          const hub = nodeHubId.get(node.id);
          if (!hub || hub === node.id) continue;
          const c = centroids.get(hub);
          if (!c) continue;
          node.vx = (node.vx || 0) + (c.x - node.x) * 0.1;
          node.vy = (node.vy || 0) + (c.y - node.y) * 0.1;
        }
      });
    }

    simulationRef.current = simulation;

    // Pre-calculate stable positions before rendering (run simulation silently)
    // Skip heavy pre-ticks when resuming from saved positions (expand/collapse)
    if (usePhysics && !isLayoutPaused) {
      // Cap silent pre-ticks on large graphs: 50+ synchronous ticks at 10k nodes
      // freeze the main thread for seconds before first paint.
      // During the entrance, most of the settle stays VISIBLE: just enough pre-ticks
      // to break the initial spiral, then a high alpha so the graph blooms on screen.
      const preTicks = hasSavedPositions
        ? 4
        : entrancePlaying
          ? (entranceRef.current.mode === 'bloom' ? 8 : 16)
          : nodeCount > 2000
            ? 15
            // VOWL is seed+pin — a few ticks only for chip settle, not 140 of chaos
            : (visualizationType === 'vowl' ? 12 : (visualizationType === 'spatial3d' ? 70 : 50));
      for (let i = 0; i < preTicks; i++) {
        simulation.tick();
      }
      simulation.alpha(
        visualizationType === 'vowl'
          ? 0.02
          : hasSavedPositions ? 0.05 : entrancePlaying ? (entranceRef.current.mode === 'bloom' ? 0.8 : 0.4) : 0.3
      );
      if (GRAPH_DEBUG) console.log(`[AdvancedGraphView] Pre-calculated ${preTicks} ticks ${hasSavedPositions ? '(incremental, positions preserved)' : 'for stable initial positions'}`);
    }

    // Soft bounding force for VOWL: the graph may occupy up to ~1.5x the viewport
    // (user can zoom/fit), pulled back gently instead of hard-clamped — a hard
    // clamp used to stack every overflow node onto the exact boundary coordinate.
    if (usePhysics && visualizationType === 'vowl') {
      const minX = -width * 0.25;
      const maxX = width * 1.25;
      const minY = -height * 0.25;
      const maxY = height * 1.25;
      simulation.force('bound', () => {
        simNodes.forEach(node => {
          if (node.x == null || node.y == null) return;
          if (node.x < minX) node.x += (minX - node.x) * 0.2;
          else if (node.x > maxX) node.x += (maxX - node.x) * 0.2;
          if (node.y < minY) node.y += (minY - node.y) * 0.2;
          else if (node.y > maxY) node.y += (maxY - node.y) * 0.2;
        });
      });
    }

    if (isSpatial3D) {
      simulation.force('depthOrbit', () => {
        d3Nodes.forEach(node => {
          const degree = nodeDegreeMap.get(node.id) || 0;
          const targetZ = Math.max(-300, Math.min(460, (hashToUnit(`${node.id}:depth`) - 0.35) * 700 - degree * 10));
          node.z = (node.z || 0) + (targetZ - (node.z || 0)) * 0.08;
          project3DNode(node);
        });
      });
    }

    // Add hit areas for edges (transparent wider paths for easier clicking).
    // Skipped above 2,000 edges: the extra path per edge doubles edge DOM and per-tick
    // path updates, and the visible paths carry the same click/hover handlers anyway.
    const edgeHitArea = d3Edges.length <= 2000 ? g.append('g')
      .attr('class', 'edge-hit-areas')
      .selectAll('path')
      .data(d3Edges)
      .join('path')
      .attr('class', 'edge-hit-path')
      .attr('fill', 'none')
      .attr('stroke', 'transparent')
      .attr('stroke-width', 20) // Extra wide for easy clicking including arrows
      .style('cursor', 'pointer')
      .on('click', (event, d) => {
        event.stopPropagation();
        setSelectedEdgeId(d.id);
        onEdgeClickRef.current?.(d.id);
      })
      .on('mouseover', (event, d) => {
        setHoveredEdgeId(d.id);
      })
      .on('mouseout', () => {
        setHoveredEdgeId(null);
      }) : null;

    // Draw edges (with VOWL styling support and curved paths for multiple edges)
    const link = g.append('g')
      .attr('class', 'links')
      .selectAll('path')
      .data(d3Edges)
      .join('path')
      .attr('class', 'edge-path')
      .attr('fill', 'none')
      .attr('stroke', d => {
        if (isInferredEntity(d)) return '#10b981';
        const isDark = effectiveDark;
        
        if (visualizationType === 'vowl') {
          // VOWL: black edges (not pale blue spaghetti that blends into nodes)
          if (d.type === 'subClassOf') {
            return '#000000';
          }
          if (d.type === 'operand') {
            return '#000000';
          }
          if (d.type === 'propertyRelation') {
            return '#000000';
          }
          const vowlEdge = vowlNotationService.edgeToVOWLEdge(d);
          // Keep special axiom colors (disjoint orange, equivalent blue); else black
          if (d.type === 'disjointWith' || d.type === 'equivalentClass') {
            return vowlEdge.stroke || '#000000';
          }
          return '#000000';
        }
        if (visualizationType === 'ontograph') {
          // Modern edge colors matching node accent palette
          if (d.type === 'subClassOf') return isDark ? '#60a5fa' : '#3b82f6'; // Blue for hierarchy
          if (d.type === 'instanceOf') return isDark ? '#a78bfa' : '#7c3aed'; // Purple
          if (d.type === 'propertyRelation') {
            const sourceNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.from));
            const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));
            if (sourceNode?.type === 'annotation') return isDark ? '#818cf8' : '#4f46e5'; // Indigo
            if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') return isDark ? '#f472b6' : '#db2777'; // Pink
            return isDark ? '#34d399' : '#059669'; // Emerald for object properties
          }
          return isDark ? '#60a5fa' : '#3b82f6';
        }
        // Force mode - with dark mode support
        if (d.type === 'subClassOf') return isDark ? '#fbbf24' : '#FFA500';
        if (d.type === 'instanceOf') return isDark ? '#cbd5e1' : '#000000';
        if (d.type === 'propertyRelation') {
          const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));
          if (targetNode?.type === 'datatype' || targetNode?.label?.startsWith('"')) return isDark ? '#94a3b8' : '#999999';
          return isDark ? '#cbd5e1' : '#000000';
        }
        return isDark ? '#cbd5e1' : '#000000';
      })
      .attr('stroke-width', d => {
        const baseWidth = visualizationType === 'vowl'
          ? (d.type === 'subClassOf' ? 1.35 : 1.25)
          : (visualizationType === 'ontograph' ? 1.5 : (visualizationType === 'spatial3d' ? 1.2 : 2));
        const inferredBoost = isInferredEntity(d) ? 0.8 : 0;
        return selectedEdgeId === d.id ? baseWidth + 2 : baseWidth + inferredBoost;
      })
      .attr('stroke-opacity', d => {
        if (selectedEdgeId) {
          return selectedEdgeId === d.id ? 1 : 0.3;
        }
        if (isInferredEntity(d)) return 0.9;
        if (visualizationType === 'force') return 1; // Full opacity for force mode
        if (visualizationType === 'spatial3d') {
          const source = d.source as D3Node;
          const target = d.target as D3Node;
          const averageScale = ((source.depthScale || 1) + (target.depthScale || 1)) / 2;
          return Math.max(0.28, Math.min(0.9, averageScale * 0.72));
        }
        if (d.type === 'propertyRelation') return 1;
        return visualizationType === 'vowl' ? 1 : 0.6;
      })
      .attr('stroke-dasharray', d => {
        if (isInferredEntity(d)) return '8 4';
        if (visualizationType === 'vowl') {
          const vowlEdge = vowlNotationService.edgeToVOWLEdge(d);
          return vowlEdge.strokeDasharray || null;
        }
        if (visualizationType === 'ontograph') {
          // Clean dashing: solid for hierarchy, subtle dash for properties
          if (d.type === 'instanceOf') return '6 3';
          if (d.type === 'propertyRelation') return '4 3';
          return null; // Solid for subClassOf
        }
        // Force mode - match reference image
        if (d.type === 'subClassOf') return '5 3'; // Dashed for subClassOf
        if (d.type === 'propertyRelation') {
          const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));
          if (targetNode?.type === 'datatype' || targetNode?.label?.startsWith('"')) return '4 2'; // Dashed for data properties
        }
        return null; // Solid for instanceOf and object properties
      })
      .attr('marker-end', d => {
        // In VOWL mode, always show arrows with specific colors for properties
        if (visualizationType === 'vowl') {
          if (d.type === 'operand') return null; // member links carry no arrowheads (VOWL)
          if (d.type === 'propertyRelation') {
            const sourceNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.from));
            const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));
            
            if (sourceNode?.type === 'annotation') return 'url(#arrow-vowl-annotation)';
            if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') return 'url(#arrow-vowl-data)';
            return 'url(#arrow-vowl-object)';
          }
          return `url(#arrow-vowl-${d.type})`;
        }
        // OntoGraph specific markers
        if (visualizationType === 'ontograph') {
          if (d.type === 'subClassOf') return 'url(#arrow-ontograph-subClassOf)';
          if (d.type === 'instanceOf') return 'url(#arrow-ontograph-instanceOf)';
          if (d.type === 'propertyRelation') {
            const sourceNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.from));
            const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));
            if (sourceNode?.type === 'annotation') return 'url(#arrow-ontograph-annotationProperty)';
            if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') return 'url(#arrow-ontograph-dataProperty)';
            return 'url(#arrow-ontograph-objectProperty)';
          }
          return 'url(#arrow-ontograph-subClassOf)';
        }
        // For other visualization types, show based on settings
        if (settings.showArrows || d.type === 'propertyRelation') {
          return `url(#arrow-${d.type})`;
        }
        return null;
      })
      .style('cursor', 'pointer')
      .on('click', (event, d) => {
        event.stopPropagation();
        setSelectedEdgeId(d.id);
        onEdgeClickRef.current?.(d.id);
      })
      .on('mouseover', (event, d) => {
        setHoveredEdgeId(d.id);
      })
      .on('mouseout', () => {
        setHoveredEdgeId(null);
      });

    // Draw edge label backgrounds (colored boxes for VOWL - varied colors based on property)
    // VOWL chips: in vowl mode, property and subclass labels sit in
    // always-visible colored chips on the edge — the VOWL look
    // (blue = object property, green = data property, white dashed = Subclass of).
    // isVowlChipEdge / vowlChipsAlwaysVisible are defined above the simulation:
    // chip edges also get invisible mid-edge physics nodes (VOWL label nodes).
    const linkLabelBg = g.append('g')
      .attr('class', 'link-label-backgrounds')
      .selectAll('rect')
      .data(d3Edges)
      .join('rect')
      .attr('class', 'edge-label-bg')
      .attr('fill', d => {
        // Theme-aware chips: dark surfaces in dark mode so the light label text stays
        // readable (light-on-light chips were the "invisible subClassOf" bug).
        const isDark = effectiveDark;
        if (visualizationType === 'vowl') {
          // VOWL-spec chip palette: the property KIND is encoded
          // in the chip color, not the edge line. Functional etc. show as the
          // "(F,…)" suffix, not as a color variant.
          const sourceNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.from));
          const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));

          if (d.type === 'subClassOf') {
            return isDark ? '#1f2937' : '#ffffff'; // white chip, dashed border (spec)
          }

          if (d.type === 'propertyRelation') {
            // Annotation properties - violet (no VOWL spec color; kept distinct)
            if (sourceNode?.type === 'annotation') {
              return isDark ? '#5b21b6' : '#ddd6fe';
            }
            // Data properties - VOWL green
            if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') {
              return isDark ? '#4d7c0f' : '#99cc66';
            }
            // Object properties — match class blue (VOWL chip style)
            return isDark ? '#1d4ed8' : '#69c';
          }

          return isDark ? '#1e3a8a' : '#69c';
        }

        // Add background colors for other modes too
        if (d.type === 'propertyRelation') {
          const sourceNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.from));
          const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));
          const isFunctional = d.metadata?.functional;

          // Annotation properties - purple
          if (sourceNode?.type === 'annotation') {
            return isDark ? (isFunctional ? '#5b21b6' : '#4c1d95') : (isFunctional ? '#E9D5FF' : '#F3E8FF');
          }
          // Data properties - pink
          if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') {
            return isDark ? (isFunctional ? '#9d174d' : '#831843') : (isFunctional ? '#FCE7F3' : '#FDF2F8');
          }
          // Object properties - cyan
          return isDark ? (isFunctional ? '#065f46' : '#155e75') : (isFunctional ? '#A7F3D0' : '#CFFAFE');
        }

        return isDark ? '#1f2937' : '#ffffff';
      })
      .attr('opacity', d => {
        if (visualizationType === 'vowl') {
          // Disjointness is drawn as the VOWL twin-circle symbol, not a text chip
          if (d.type === 'disjointWith') return 0;
          // Chips are always visible (VOWL look) below the density threshold;
          // everything else reveals on selection/hover.
          if (vowlChipsAlwaysVisible && isVowlChipEdge(d)) return 1;
          return (selectedEdgeId === d.id || hoveredEdgeId === d.id) ? 1 : 0;
        }
        return (visualizationType as string) === 'vowl' ? 1 : 0.85;
      })
      .attr('stroke', d => {
        if (visualizationType === 'vowl') {
          // Chip borders by property kind (VOWL palette) — the old version keyed
          // colors off substrings of the label text, which looked arbitrary.
          const sourceNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.from));
          const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));
          if (d.type === 'subClassOf') return effectiveDark ? '#9ca3af' : '#6b7280';
          if (d.type === 'propertyRelation') {
            if (sourceNode?.type === 'annotation') return effectiveDark ? '#c4b5fd' : '#7c3aed';
            if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') {
              return effectiveDark ? '#a3e635' : '#4d7c0f';
            }
            return effectiveDark ? '#93c5fd' : '#34608d';
          }
          return effectiveDark ? '#93c5fd' : '#2196F3';
        }
        return 'none';
      })
      .attr('stroke-dasharray', d =>
        visualizationType === 'vowl' && d.type === 'subClassOf' ? '3 2' : null)
      .attr('stroke-width', visualizationType === 'vowl' ? 0.85 : 0)
      .attr('rx', visualizationType === 'vowl' ? 1.5 : 3)
      .attr('ry', visualizationType === 'vowl' ? 1.5 : 3)
      .style('pointer-events', 'none');

    // Draw edge labels (more prominent in VOWL mode)
    const linkLabel = g.append('g')
      .attr('class', 'link-labels')
      .selectAll('text')
      .data(d3Edges)
      .join('text')
      .attr('class', 'edge-label')
      .attr('font-size', visualizationType === 'vowl' ? 9.5 : 10)
      .attr('font-weight', visualizationType === 'vowl' ? '500' : '400')
      .attr('font-family', visualizationType === 'vowl' ? 'Helvetica Neue, Helvetica, Arial, sans-serif' : 'inherit')
      .attr('fill', d => {
        const isDark = effectiveDark;
        
        if (visualizationType === 'vowl') {
          // Text colors matched to the VOWL chip fills above — dark ink on the
          // light spec-color chips, light ink on their dark-mode counterparts.
          const sourceNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.from));
          const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));

          if (d.type === 'subClassOf') {
            return isDark ? '#e5e7eb' : '#374151';
          }

          if (d.type === 'propertyRelation') {
            // Annotation properties - violet chip
            if (sourceNode?.type === 'annotation') {
              return isDark ? '#ede9fe' : '#4c1d95';
            }
            // Data properties - VOWL green chip
            if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') {
              return isDark ? '#f7fee7' : '#1a2e05';
            }
            // Object properties — white on class-blue chip (VOWL)
            return '#ffffff';
          }

          return '#ffffff';
        }
        // Color labels by property type with dark mode support
        if (d.type === 'propertyRelation') {
          const sourceNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.from));
          const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));
          if (sourceNode?.type === 'annotation') return isDark ? '#c4b5fd' : '#7c3aed';
          if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') return isDark ? '#f9a8d4' : '#db2777';
          return isDark ? '#67e8f9' : '#0891b2';
        }
        if (d.type === 'subClassOf') return isDark ? '#c4b5fd' : '#7c3aed';
        if (d.type === 'instanceOf') return isDark ? '#fdba74' : '#ea580c';
        return isDark ? '#d1d5db' : '#666';
      })
      .attr('text-anchor', 'middle')
      .attr('dominant-baseline', 'middle')
      .text(d => {
        // Build characteristic suffix from all OWL property characteristics on the edge
        // Format: "(F,IF,S,T,A,R,Ir)" — only includes characteristics actually present
        const buildCharSuffix = (edge: any): string => {
          const m = edge.metadata || {};
          const flags: string[] = [];
          if (isFunctionalEdge(edge)) flags.push('F');
          if (m.inverseFunctional) flags.push('IF');
          if (m.symmetric) flags.push('S');
          if (m.transitive) flags.push('T');
          if (m.asymmetric) flags.push('A');
          if (m.reflexive) flags.push('R');
          if (m.irreflexive) flags.push('Ir');
          return flags.length > 0 ? ` (${flags.join(',')})` : '';
        };

        // Show full label in VOWL mode with characteristic indicators
        if (visualizationType === 'vowl') {
          if (d.type === 'disjointWith') return ''; // rendered as the VOWL twin-circle symbol
          // VOWL labels inheritance edges "Subclass of", not the raw type name
          const baseLabel = d.type === 'subClassOf' ? 'Subclass of' : (d.label || d.type || '');
          return vowlOptions.compactNotation ? baseLabel : `${baseLabel}${buildCharSuffix(d)}`;
        }

        // Add property type prefix and characteristic indicators for clarity
        if (settings.showLabels && d.type === 'propertyRelation') {
          const sourceNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.from));
          const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));
          const label = d.label || '';

          let prefix = '';
          if (sourceNode?.type === 'annotation') prefix = '📝'; // Annotation property
          else if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') prefix = '📊'; // Data property
          else prefix = '🔗'; // Object property

          return `${prefix} ${label}${buildCharSuffix(d)}`;
        }

        return settings.showLabels ? (d.label || '') : '';
      })
      .style('pointer-events', 'none')
      .style('opacity', d => {
        if (visualizationType === 'vowl') {
          // Chips always visible below the density threshold (VOWL look);
          // everything else reveals on selection/hover.
          if (vowlChipsAlwaysVisible && isVowlChipEdge(d)) return 1;
          return (selectedEdgeId === d.id || hoveredEdgeId === d.id) ? 1 : 0;
        }
        return settings.showLabels ? 1 : 0;
      });

    // VOWL disjointness symbol (per the VOWL spec / VOWL's OwlDisjointWith):
    // a small rect containing two separated circles, captioned "(disjoint)" —
    // always visible on the dashed edge, replacing the text chip.
    const disjointSymbol = g.append('g')
      .attr('class', 'disjoint-symbols')
      .selectAll('g')
      .data(visualizationType === 'vowl' ? d3Edges.filter(d => d.type === 'disjointWith') : [])
      .join('g')
      .attr('class', 'disjoint-symbol')
      .style('pointer-events', 'none');
    disjointSymbol.append('rect')
      .attr('x', -22).attr('y', -11)
      .attr('width', 44).attr('height', 22)
      .attr('rx', 2)
      .attr('fill', isDark ? '#1e3a8a' : '#b9d4f5')
      .attr('stroke', isDark ? '#60a5fa' : '#1f2937')
      .attr('stroke-width', 0.8);
    ([-9, 9] as const).forEach(cx => {
      disjointSymbol.append('circle')
        .attr('cx', cx).attr('cy', 0).attr('r', 7)
        .attr('fill', isDark ? '#60a5fa' : '#6699cc')
        .attr('stroke', isDark ? '#0b1220' : '#1f2937')
        .attr('stroke-width', 0.8);
    });
    if (!vowlOptions.compactNotation) {
      disjointSymbol.append('text')
        .text('(disjoint)')
        .attr('y', 21)
        .attr('text-anchor', 'middle')
        .attr('font-size', 7.5)
        .attr('fill', isDark ? '#cbd5e1' : '#1f2937');
    }

    const updateDisjointSymbols = () => {
      disjointSymbol.attr('transform', (d: any) => {
        const source = d.source as D3Node;
        const target = d.target as D3Node;
        if (source.x == null || source.y == null || target.x == null || target.y == null) return null;
        const mx = (source.x + target.x) / 2;
        const my = (source.y + target.y) / 2;
        const dx = target.x - source.x;
        const dy = target.y - source.y;
        const len = Math.hypot(dx, dy) || 1;
        // Offset perpendicular so the badge doesn't sit on crossing edges / labels
        const px = -dy / len;
        const py = dx / len;
        const side = 36;
        return `translate(${mx + px * side},${my + py * side})`;
      });
    };
    updateDisjointSymbols();

    // VOWL cardinality / quantifier badges: restriction edges
    // always show their kind — "≥ n" (min), "≤ n" (max), "= n" (exactly),
    // "∃" (someValuesFrom), "∀" (allValuesFrom), "∋" (hasValue) — as a small
    // always-visible label at the edge midpoint, VOWL-spec style. The full
    // "property kind n" text remains on the hover/selection label above.
    const restrictionBadgeText = (d: any): string => {
      const m = d.metadata || {};
      const n = m.cardinality;
      switch (m.restrictionType) {
        case 'min': return n != null ? `≥ ${n}` : '∃';
        case 'max': return n != null ? `≤ ${n}` : '∀';
        case 'exactly': return n != null ? `= ${n}` : '=';
        case 'only': return '∀';
        case 'value': return '∋';
        case 'some':
        default: return '∃';
      }
    };
    const restrictionBadge = g.append('g')
      .attr('class', 'restriction-badges')
      .selectAll('text')
      .data(visualizationType === 'vowl' && !vowlOptions.compactNotation
        ? d3Edges.filter(d => d.type === 'restriction')
        : [])
      .join('text')
      .attr('class', 'restriction-badge')
      .text(restrictionBadgeText)
      .attr('text-anchor', 'middle')
      .attr('dominant-baseline', 'middle')
      .attr('font-size', 9.5)
      .attr('font-weight', 600)
      .attr('fill', isDark ? '#fbbf24' : '#b45309')
      .attr('stroke', isDark ? '#0b1220' : '#ffffff')
      .attr('stroke-width', 3)
      .attr('paint-order', 'stroke')
      .style('pointer-events', 'none');

    const updateRestrictionBadges = () => {
      restrictionBadge.attr('transform', (d: any) => {
        const source = d.source as D3Node;
        const target = d.target as D3Node;
        if (source.x == null || source.y == null || target.x == null || target.y == null) return null;
        return `translate(${(source.x + target.x) / 2},${(source.y + target.y) / 2})`;
      });
    };
    updateRestrictionBadges();

    const updateLinkLabelBackgrounds = () => {
      linkLabelBg.each(function(_d, i) {
        const label = linkLabel.nodes()[i];
        if (!label) return;
        const bbox = (label as SVGTextElement).getBBox();
        const padding = visualizationType === 'vowl' ? 2.5 : 3;
        // Cache chip dimensions so updateLinkLabelPositions can move the chip
        // every tick without re-measuring (getBBox per tick thrashes layout).
        (_d as any).__chipW = bbox.width + padding * 2;
        (_d as any).__chipH = bbox.height + padding * 2;
        d3.select(this)
          .attr('x', bbox.x - padding)
          .attr('y', bbox.y - padding)
          .attr('width', bbox.width + padding * 2)
          .attr('height', bbox.height + padding * 2);
      });
    };

    /** AABB resolve using MEASURED chip boxes (__chipW/H) — estimates under-size long labels. */
    const resolveMeasuredChipOverlaps = () => {
      if (!vowlChipsAlwaysVisible || chipNodes.length === 0) return;
      for (let pass = 0; pass < 20; pass++) {
        let moved = false;
        for (let i = 0; i < chipNodes.length; i++) {
          for (let j = i + 1; j < chipNodes.length; j++) {
            const a = chipNodes[i];
            const b = chipNodes[j];
            const ea = (a as any).__chipEdge;
            const eb = (b as any).__chipEdge;
            const hwA = ((ea?.__chipW ?? ((a as any).__chipRadius || 20) * 2) / 2) + 6;
            const hwB = ((eb?.__chipW ?? ((b as any).__chipRadius || 20) * 2) / 2) + 6;
            const hhA = ((ea?.__chipH ?? 24) / 2) + 5;
            const hhB = ((eb?.__chipH ?? 24) / 2) + 5;
            const ax = a.x ?? 0;
            const ay = a.y ?? 0;
            const bx = b.x ?? 0;
            const by = b.y ?? 0;
            const overlapX = hwA + hwB - Math.abs(bx - ax);
            const overlapY = hhA + hhB - Math.abs(by - ay);
            if (overlapX <= 0 || overlapY <= 0) continue;
            if (overlapX < overlapY) {
              const sx = ((bx - ax) === 0 ? (i % 2 ? 1 : -1) : Math.sign(bx - ax)) * (overlapX / 2);
              a.x = ax - sx;
              b.x = bx + sx;
            } else {
              const sy = ((by - ay) === 0 ? (i % 2 ? 1 : -1) : Math.sign(by - ay)) * (overlapY / 2);
              a.y = ay - sy;
              b.y = by + sy;
            }
            a.fx = a.x;
            a.fy = a.y;
            b.fx = b.x;
            b.fy = b.y;
            moved = true;
          }
        }
        if (!moved) break;
      }
      // Clear chips off class/literal glyphs with measured half-sizes
      const glyphs = d3Nodes.filter(
        nd => nd.type === 'datatype' || nd.label === 'Literal' || nd.type === 'class'
      );
      for (let pass = 0; pass < 10; pass++) {
        let movedAny = false;
        for (const chip of chipNodes) {
          const e = (chip as any).__chipEdge as D3Edge | undefined;
          if (!e) continue;
          const hw = ((e as any).__chipW ?? 40) / 2 + 4;
          const hh = ((e as any).__chipH ?? 24) / 2 + 3;
          const s = e.source as D3Node;
          const t = e.target as D3Node;
          for (const g of glyphs) {
            const isLit = g.type === 'datatype' || g.label === 'Literal';
            const gHw = isLit ? 48 : 30;
            const gHh = isLit ? 16 : 30;
            const dx = (chip.x ?? 0) - (g.x ?? 0);
            const dy = (chip.y ?? 0) - (g.y ?? 0);
            const ox = hw + gHw - Math.abs(dx);
            const oy = hh + gHh - Math.abs(dy);
            if (ox <= 0 || oy <= 0) continue;
            if (g === s || g === t) {
              const other = g === s ? t : s;
              const hx = (other.x ?? 0) - (chip.x ?? 0);
              const hy = (other.y ?? 0) - (chip.y ?? 0);
              const hl = Math.hypot(hx, hy) || 1;
              const step = Math.min(ox, oy) * 0.9;
              chip.x = (chip.x ?? 0) + (hx / hl) * step;
              chip.y = (chip.y ?? 0) + (hy / hl) * step;
            } else if (ox < oy) {
              chip.x = (chip.x ?? 0) + (dx === 0 ? 1 : Math.sign(dx)) * ox;
            } else {
              chip.y = (chip.y ?? 0) + (dy === 0 ? 1 : Math.sign(dy)) * oy;
            }
            chip.fx = chip.x;
            chip.fy = chip.y;
            movedAny = true;
          }
        }
        if (!movedAny) break;
      }
      // Re-bake along/side so tick keeps measured separation
      chipNodes.forEach(chip => {
        const e = (chip as any).__chipEdge as D3Edge | undefined;
        if (!e) return;
        const s = e.source as D3Node;
        const t = e.target as D3Node;
        const sx = s.x ?? 0;
        const sy = s.y ?? 0;
        const dx = (t.x ?? 0) - sx;
        const dy = (t.y ?? 0) - sy;
        const len = Math.hypot(dx, dy) || 1;
        const ux = dx / len;
        const uy = dy / len;
        const px = -uy;
        const py = ux;
        const cx = chip.x ?? sx;
        const cy = chip.y ?? sy;
        (chip as any).__chipAlong = Math.max(0.12, Math.min(0.88, ((cx - sx) * ux + (cy - sy) * uy) / len));
        (chip as any).__chipSide = (cx - sx) * px + (cy - sy) * py;
      });
    };

    if (vowlChipsAlwaysVisible) {
      // Size chips immediately so they're visible during the initial settle,
      // not only after the first simulation 'end'.
      updateLinkLabelBackgrounds();
    }

    // Filter nodes by viewport for large OntoGraph (virtualization)
    let visibleD3Nodes = d3Nodes;
    if (isLargeGraph && visualizationType === 'ontograph' && d3Nodes.length > 5000) {
      const buffer = 500; // Buffer zone around viewport
      const vp = viewportBoundsRef.current;
      visibleD3Nodes = d3Nodes.filter(node => {
        if (node.x == null || node.y == null) return true; // Include if no position yet (0 is a valid coordinate)
        return node.x >= vp.x - buffer &&
               node.x <= vp.x + vp.width + buffer &&
               node.y >= vp.y - buffer &&
               node.y <= vp.y + vp.height + buffer;
      });
      if (GRAPH_DEBUG) console.log(`[OntoGraph Virtualization] Rendering ${visibleD3Nodes.length} of ${d3Nodes.length} nodes`);
    }
    
    // Draw nodes
    const node = g.append('g')
      .attr('class', 'nodes')
      .selectAll('g')
      .data(visibleD3Nodes)
      .join('g')
      .attr('class', 'node')
      .attr('data-testid', 'graph-node')
      .attr('data-graph-node-id', d => d.id)
      .style('cursor', editModeRef.current ? 'move' : 'pointer')
      .call(d3.drag<SVGGElement, D3Node>()
        .on('start', dragStarted)
        .on('drag', dragged)
        .on('end', dragEnded) as any)
      .on('dblclick', (event: any, d: D3Node) => {
        event.stopPropagation();
        event.preventDefault();
        // Shift+double-click → enter focus mode
        if (event.shiftKey) {
          enterFocusMode(d.id);
          return;
        }
        // Double-click to expand/collapse children
        if (hasChildren(d.id, allEdges, allNodes)) {
          handleToggleExpansion(d.id);
        }
      });

    // Node shapes (VOWL-style: circles for classes/properties, rectangles for datatypes/individuals)
    node.each(function(d) {
      const nodeGroup = d3.select(this);
      const size = d.size || settings.nodeSize;
      let nodeType = d.type;
      // User-configurable box scale (Notation & density panel) — vowl/force only,
      // where shape size is otherwise a fixed multiple of `size` regardless of label length.
      const widthScale = (visualizationType === 'vowl' || visualizationType === 'force') ? vowlOptions.nodeWidthScale : 1;
      const heightScale = (visualizationType === 'vowl' || visualizationType === 'force') ? vowlOptions.nodeHeightScale : 1;

      // Check if this is owl:Thing or external/internal node
      const isThing = d.label === 'Thing' || d.id.includes('owl#Thing');
      const isExternal = isExternalNode(d);
      // XSD / rdfs Literal must stay VOWL yellow boxes — never steel-blue
      // "external class" circles (Patient ontologies often ship xsd:integer as class).
      const nodeIri = String(d.uri || d.id || '');
      const isXsdOrLiteralIri =
        nodeIri.includes('XMLSchema') ||
        nodeIri.includes('rdf-schema#Literal') ||
        nodeIri.endsWith('#Literal') ||
        /#(string|integer|decimal|float|double|boolean|date|dateTime|int|long|short)$/i.test(nodeIri);
      if (nodeType !== 'datatype' && isXsdOrLiteralIri) {
        nodeType = 'datatype';
      }
      
      // Detect dark mode for theme-aware colors (VOWL is pinned to the light palette)
      const isDark = effectiveDark;
      
      // VOWL color scheme — medium blue classes with white labels (stock look)
      let fill = isDark ? '#6b92c4' : '#69c';
      
      if (visualizationType === 'vowl' && nodeType === 'datatype') {
        fill = isDark ? '#d97706' : '#fc3';
      } else if (visualizationType === 'vowl' && nodeType === 'class') {
        if (isThing) {
          fill = isDark ? '#374151' : '#ffffff';
        } else if (isExternal && vowlOptions.colorExternals) {
          fill = isDark ? '#60a5fa' : '#36c';
        } else {
          fill = isDark ? '#6b92c4' : '#69c';
        }
      } else if (visualizationType === 'vowl' && nodeType === 'setOperator') {
        fill = isDark ? '#6b92c4' : '#69c';
      } else if (visualizationType === 'vowl' && nodeType === 'individual') {
        fill = isDark ? '#fbb6ce' : '#cfc';
      } else if (visualizationType === 'vowl') {
        fill = vowlNotationService.getVOWLNodeColor(d.type, isDark);
      } else {
        fill = d.color || TYPE_COLORS[d.type];
      }

      if (isInferredEntity(d)) {
        fill = isDark ? '#064e3b' : '#d1fae5';
      }

      // Record the color actually used to paint this node so the label-text
      // callback further down can pick a readable black/white against it
      // instead of re-deriving (and risking a mismatched) guess.
      (d as any).__nodeFillColor = fill;

      const stroke = visualizationType === 'vowl'
        ? (isInferredEntity(d) ? '#10b981' : (isDark ? '#d1d5db' : '#000000'))
        : (isInferredEntity(d) ? '#10b981' : '#fff');
      
      const strokeWidth = visualizationType === 'vowl'
        ? (isInferredEntity(d) ? 2.5 : (isThing ? 1.25 : 1.35))
        : (isInferredEntity(d) ? 3 : (hasChildren(d.id, allEdges, allNodes) && visualizationType !== 'vowl' ? 3 : 2));
      
      // Thing and anonymous set-operator nodes get dashed borders in VOWL
      const strokeDasharray = isInferredEntity(d)
        ? '8 4'
        : (visualizationType === 'vowl'
          ? (isThing || nodeType === 'setOperator' ? '5 3' : null)
          : null);

      // Flat VOWL fills (VOWL is flat — glossy + drop-shadow made ours look muddy).
      // Other modes keep the premium glossy treatment.
      const fillPaint = visualizationType === 'vowl' ? fill : glossyFill(fill);
      const softShadow = visualizationType === 'vowl' || visibleNodes.length > 100
        ? 'none'
        : 'drop-shadow(0 2px 5px rgba(0,0,0,0.22)) drop-shadow(0 6px 14px rgba(0,0,0,0.10))';

      // Render different shapes based on node type (VOWL style)
      if (nodeType === 'dataProperty' || nodeType === 'property') {
        // Pink Rectangle for data properties
        nodeGroup.append('rect')
          .attr('class', 'node-shape')
          .attr('x', -size)
          .attr('y', -size)
          .attr('width', size * 2)
          .attr('height', size * 2)
          .attr('rx', 5)
          .attr('fill', fillPaint)
          .attr('stroke', stroke)
          .attr('stroke-width', strokeWidth)
          .attr('stroke-dasharray', strokeDasharray || null)
          .style('filter', softShadow)
          .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
          .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
          .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
          .on('mouseout', handleNodeMouseOut);
      } else if (nodeType === 'datatype') {
        // Force mode: White rectangle for datatypes/literals
        if (visualizationType === 'force') {
          const rectWidth = size * 2.2 * widthScale;
          const rectHeight = size * 1.1 * heightScale;
          nodeGroup.append('rect')
            .attr('class', 'node-shape')
            .attr('x', -rectWidth / 2)
            .attr('y', -rectHeight / 2)
            .attr('width', rectWidth)
            .attr('height', rectHeight)
            .attr('rx', 5)
            .attr('ry', 5)
            .attr('fill', isDark ? glossyFill('#334155') : glossyFill('#f8fafc'))
            .attr('stroke', isDark ? '#64748b' : '#cbd5e1')
            .attr('stroke-width', 1.25)
            .style('filter', softShadow)
            .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
            .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
            .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
            .on('mouseout', handleNodeMouseOut);
        } else {
          // VOWL: compact yellow Literal boxes (VOWL proportions — flat, not glossy)
          const rectWidth = (visualizationType === 'vowl' ? size * 3.2 * widthScale : (visualizationType === 'ontograph' ? size * 3.5 : size * 3));
          const rectHeight = (visualizationType === 'vowl' ? size * 1.55 * heightScale : (visualizationType === 'ontograph' ? size * 1.8 : size * 1.6));
          nodeGroup.append('rect')
            .attr('class', 'node-shape')
            .attr('x', -rectWidth / 2)
            .attr('y', -rectHeight / 2)
            .attr('width', rectWidth)
            .attr('height', rectHeight)
            .attr('rx', size * 0.35)
            .attr('fill', visualizationType === 'vowl' ? (isDark ? '#d97706' : '#fc3') : fillPaint)
            .attr('stroke', visualizationType === 'vowl' ? (isDark ? '#d1d5db' : '#333333') : stroke)
            .attr('stroke-width', visualizationType === 'vowl' ? 1.25 : strokeWidth)
            .attr('stroke-dasharray', visualizationType === 'vowl' ? '4 2.5' : (strokeDasharray || null))
            .style('filter', softShadow)
            .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
            .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
            .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
            .on('mouseout', handleNodeMouseOut);
        }
      } else if (nodeType === 'individual') {
        // Force mode: Blue rounded rectangle for individuals
        if (visualizationType === 'force') {
          const rectWidth = size * 3.2 * widthScale;
          const rectHeight = size * 1.6 * heightScale;
          nodeGroup.append('rect')
            .attr('class', 'node-shape')
            .attr('x', -rectWidth / 2)
            .attr('y', -rectHeight / 2)
            .attr('width', rectWidth)
            .attr('height', rectHeight)
            .attr('rx', 7)
            .attr('ry', 7)
            .attr('fill', glossyFill('#B0C4DE'))
            .attr('stroke', '#64748b')
            .attr('stroke-width', 1.5)
            .style('filter', softShadow)
            .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
            .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
            .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
            .on('mouseout', handleNodeMouseOut);
        } else {
          // VOWL/OntoGraph: Rectangle for individuals (purple/pink) - dynamic width based on label length
          const label = d.label || '';
          const baseWidth = (visualizationType === 'vowl' ? size * 2.8 * widthScale : (visualizationType === 'ontograph' ? size * 2.8 : size * 2.4));
          // Calculate width based on label length (approximately 7 pixels per character), capped
          const labelWidth = Math.min(label.length * 7, 180);
          const rectWidth = Math.max(baseWidth, labelWidth + 16); // Add padding
          const maxWidth = visualizationType === 'vowl' ? size * 5.0 * widthScale : size * 4.5;
          const finalWidth = Math.min(rectWidth, maxWidth);
          const rectHeight = (visualizationType === 'vowl' ? size * 1.8 * heightScale : (visualizationType === 'ontograph' ? size * 1.8 : size * 1.6));
          nodeGroup.append('rect')
            .attr('class', 'node-shape')
            .attr('x', -finalWidth / 2)
            .attr('y', -rectHeight / 2)
            .attr('width', finalWidth)
            .attr('height', rectHeight)
            .attr('rx', 6)
            .attr('ry', 6)
            .attr('fill', fillPaint)
            .attr('stroke', stroke)
            .attr('stroke-width', strokeWidth)
            .attr('stroke-dasharray', strokeDasharray || null)
            .style('filter', softShadow)
            .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
            .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
            .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
            .on('mouseout', handleNodeMouseOut);
        }
      } else if (nodeType === 'annotation' && visualizationType !== 'force') {
        // Hexagon for annotation properties (distinctive shape) - but not in force mode
        // In force mode, annotations are shown only as edges, not nodes
        const hexSize = visualizationType === 'vowl' ? size * 1.5 : size * 1.2;
        const angle = Math.PI / 3; // 60 degrees
        const hexPoints = Array.from({ length: 6 }, (_, i) => {
          const x = hexSize * Math.cos(angle * i - Math.PI / 2);
          const y = hexSize * Math.sin(angle * i - Math.PI / 2);
          return `${x},${y}`;
        }).join(' ');
        
        nodeGroup.append('polygon')
          .attr('class', 'node-shape')
          .attr('points', hexPoints)
          .attr('fill', visualizationType === 'vowl' ? glossyFill(isDark ? '#9333ea' : '#e8d5f2') : fillPaint) // Darker purple in dark mode
          .attr('stroke', stroke)
          .attr('stroke-width', strokeWidth)
          .attr('stroke-dasharray', strokeDasharray || null)
          .style('filter', softShadow)
          .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
          .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
          .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
          .on('mouseout', handleNodeMouseOut);
      } else if (visualizationType === 'ontograph') {
        // Modern hierarchical graph: clean card-style nodes with accent stripe
        const simplifiedLOD = isLargeGraph && viewportBoundsRef.current.scale < 0.5;
        const rectWidth = simplifiedLOD ? size * 5 : size * 9;
        const rectHeight = simplifiedLOD ? size * 1.8 : size * 2.6;
        const cornerRadius = 8;
        
        // Modern color palette per type
        const nodeColors: Record<string, { bg: string; border: string; accent: string; icon: string; text: string }> = {
          class:      { bg: isDark ? '#1e293b' : '#f8fafc', border: isDark ? '#3b82f6' : '#3b82f6', accent: isDark ? '#3b82f6' : '#3b82f6', icon: '#3b82f6', text: isDark ? '#e2e8f0' : '#1e293b' },
          individual: { bg: isDark ? '#1e293b' : '#f8fafc', border: isDark ? '#8b5cf6' : '#7c3aed', accent: isDark ? '#8b5cf6' : '#7c3aed', icon: '#7c3aed', text: isDark ? '#e2e8f0' : '#1e293b' },
          datatype:   { bg: isDark ? '#1e293b' : '#f8fafc', border: isDark ? '#f59e0b' : '#d97706', accent: isDark ? '#f59e0b' : '#d97706', icon: '#d97706', text: isDark ? '#e2e8f0' : '#1e293b' },
          property:   { bg: isDark ? '#1e293b' : '#f8fafc', border: isDark ? '#10b981' : '#059669', accent: isDark ? '#10b981' : '#059669', icon: '#059669', text: isDark ? '#e2e8f0' : '#1e293b' },
          dataProperty: { bg: isDark ? '#1e293b' : '#f8fafc', border: isDark ? '#ec4899' : '#db2777', accent: isDark ? '#ec4899' : '#db2777', icon: '#db2777', text: isDark ? '#e2e8f0' : '#1e293b' },
          annotation: { bg: isDark ? '#1e293b' : '#f8fafc', border: isDark ? '#6366f1' : '#4f46e5', accent: isDark ? '#6366f1' : '#4f46e5', icon: '#4f46e5', text: isDark ? '#e2e8f0' : '#1e293b' },
        };
        const colors = isInferredEntity(d)
          ? { bg: isDark ? '#052e2b' : '#ecfdf5', border: '#10b981', accent: '#10b981', icon: '#10b981', text: isDark ? '#d1fae5' : '#064e3b' }
          : (colorByCluster && clusterFor(d.id) !== undefined
            ? (() => {
                const cc = getClusterColor(clusterFor(d.id), graphAnalytics.clusterColors) || '#3b82f6';
                return { bg: isDark ? '#1e293b' : '#f8fafc', border: cc, accent: cc, icon: cc, text: isDark ? '#e2e8f0' : '#1e293b' };
              })()
            : (nodeColors[d.type] || nodeColors['class']));
        
        // Main card background — gradient sheen + colored ambient shadow
        nodeGroup.append('rect')
          .attr('class', 'node-shape')
          .attr('x', -rectWidth / 2)
          .attr('y', -rectHeight / 2)
          .attr('width', rectWidth)
          .attr('height', rectHeight)
          .attr('rx', cornerRadius)
          .attr('fill', (simplifiedLOD || isInferredEntity(d)) ? colors.bg : 'url(#card-grad)')
          .attr('stroke', colors.border)
          .attr('stroke-width', simplifiedLOD ? 1 : 1.5)
          .attr('stroke-dasharray', isInferredEntity(d) ? '8 4' : null)
          .style('filter', simplifiedLOD
            ? 'none'
            : `drop-shadow(0 2px 8px ${hexToRgba(colors.accent, isDark ? 0.35 : 0.22)}) drop-shadow(0 1px 2px rgba(0,0,0,0.10))`)
          .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
          .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
          .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
          .on('mouseout', handleNodeMouseOut);

        if (!simplifiedLOD) {
          // Left accent stripe — gradient for depth
          nodeGroup.append('rect')
            .attr('x', -rectWidth / 2)
            .attr('y', -rectHeight / 2)
            .attr('width', 5)
            .attr('height', rectHeight)
            .attr('rx', 0)
            .attr('fill', accentGrad(colors.accent))
            .style('pointer-events', 'none')
            // Clip to card's left rounded corners
            .attr('clip-path', `inset(0 0 0 0 round ${cornerRadius}px 0 0 ${cornerRadius}px)`);

          // Type badge (small gradient pill)
          const badgeText = d.type === 'class' ? 'C' : (d.type === 'individual' ? 'I' : (d.type === 'datatype' ? 'D' : (d.type === 'dataProperty' ? 'DP' : 'P')));
          const badgeWidth = badgeText.length > 1 ? 22 : 16;

          nodeGroup.append('rect')
            .attr('x', -rectWidth / 2 + 11)
            .attr('y', -rectHeight / 2 + 5)
            .attr('width', badgeWidth)
            .attr('height', 16)
            .attr('rx', 8)
            .attr('fill', accentGrad(colors.accent))
            .style('filter', `drop-shadow(0 1px 2px ${hexToRgba(colors.accent, 0.4)})`)
            .style('pointer-events', 'none');

          nodeGroup.append('text')
            .attr('x', -rectWidth / 2 + 11 + badgeWidth / 2)
            .attr('y', -rectHeight / 2 + 14)
            .attr('text-anchor', 'middle')
            .attr('font-size', '9px')
            .attr('font-weight', '700')
            .attr('fill', '#ffffff')
            .attr('letter-spacing', '0.5px')
            .text(badgeText)
            .style('pointer-events', 'none');

          // Expander icon on the right — accent-filled with white glyph
          if (hasChildren(d.id, allEdges, allNodes)) {
            const isExpanded = expandedNodeIds.has(d.id);
            const expanderGroup = nodeGroup.append('g')
              .attr('class', 'expander-icon')
              .attr('cursor', 'pointer')
              .on('click', (event: any) => {
                event.stopPropagation();
                handleToggleExpansion(d.id);
              });

            expanderGroup.append('circle')
              .attr('cx', rectWidth / 2 - 14)
              .attr('cy', 0)
              .attr('r', 10)
              .attr('fill', accentGrad(colors.accent))
              .attr('stroke', isDark ? 'rgba(255,255,255,0.25)' : 'rgba(255,255,255,0.8)')
              .attr('stroke-width', 1.5)
              .style('filter', `drop-shadow(0 1px 3px ${hexToRgba(colors.accent, 0.45)})`);

            expanderGroup.append('text')
              .attr('x', rectWidth / 2 - 14)
              .attr('y', 4.5)
              .attr('text-anchor', 'middle')
              .attr('font-size', '13px')
              .attr('font-weight', '700')
              .attr('fill', '#ffffff')
              .text(isExpanded ? '−' : '+')
              .style('pointer-events', 'none');
          }
        }
      } else {
        // Force mode classes OR default circle rendering
        if (visualizationType === 'force' && nodeType === 'class') {
          // Classes as light orange/peach ovals (ellipse) - larger size for better label fit
          const ellipseWidth = size * 3.5 * widthScale;  // Wider for text
          const ellipseHeight = size * 2.0 * heightScale; // Taller oval
          nodeGroup.append('ellipse')
            .attr('class', 'node-shape')
            .attr('cx', 0)
            .attr('cy', 0)
            .attr('rx', ellipseWidth)
            .attr('ry', ellipseHeight)
            .attr('fill', () => {
              // Flat tint + accent stroke + soft shadow — no glossy gradient.
              const paletteFill = nodeFill(d.type, isDark);
              const finalFill = colorByCluster && clusterFor(d.id) !== undefined
                ? (getClusterColor(clusterFor(d.id), graphAnalytics.clusterColors) || paletteFill)
                : paletteFill;
              // Overrides the generic stash above — this ellipse ignores the
              // outer `fill` var (that holds the raw accent, not this tint).
              (d as any).__nodeFillColor = finalFill;
              return finalFill;
            })
            .attr('stroke', isInferredEntity(d) ? '#10b981' : (
              colorByCluster && clusterFor(d.id) !== undefined
                ? (d3.color(getClusterColor(clusterFor(d.id), graphAnalytics.clusterColors)
                    || nodeStroke(d.type, isDark))?.darker(1.2).formatHex() || '#b45309')
                : nodeStroke(d.type, isDark)))
            .attr('stroke-width', isInferredEntity(d) ? 3 : 1.75)
            .attr('stroke-dasharray', isInferredEntity(d) ? '8 4' : null)
            .style('filter', softShadow)
            .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
            .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
            .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
            .on('mouseout', handleNodeMouseOut);
        } else {
          // Circle for classes, object properties, and other types - larger for OntoGraph.
          // A circle can't have independent width/height without becoming an ellipse, so
          // the two user scale sliders are averaged here — vowl class truncation below
          // uses the same average so the label budget always matches this radius.
          const circleRadius = visualizationType === 'vowl'
            ? size * 1.35 * ((widthScale + heightScale) / 2)
            // @ts-ignore - Type narrowing limitation: visualizationType can be 'ontograph' in other code paths
            : (visualizationType === 'ontograph' ? size * 1.6 : size * 1.2 * ((widthScale + heightScale) / 2));
          nodeGroup.append('circle')
            .attr('class', 'node-shape')
            .attr('r', circleRadius)
            .attr('fill', fillPaint)
            .attr('stroke', stroke)
            .attr('stroke-width', strokeWidth)
            .attr('stroke-dasharray', strokeDasharray || null)
            .style('filter', softShadow)
            .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
            .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
            .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
            .on('mouseout', handleNodeMouseOut);

          // VOWL: merged equivalent classes get the double border (inner ring)
          if (visualizationType === 'vowl' && (d as any).metadata?.vowlEquivalent) {
            nodeGroup.append('circle')
              .attr('r', Math.max(4, circleRadius - 4))
              .attr('fill', 'none')
              .attr('stroke', stroke)
              .attr('stroke-width', 1.2)
              .style('pointer-events', 'none');
          }
        }
      }

      if (isInferredEntity(d) && !isLargeGraph) {
        nodeGroup.append('text')
          .attr('x', visualizationType === 'ontograph' ? size * 3.2 : size * 1.4)
          .attr('y', visualizationType === 'ontograph' ? -size * 1.05 : -size * 1.1)
          .attr('text-anchor', 'middle')
          .attr('font-size', '9px')
          .attr('font-weight', '700')
          .attr('fill', '#10b981')
          .text('INF')
          .style('pointer-events', 'none');
      }

      // Expander +/- icons: Network/Force only. VOWL stays clean — expand/collapse
      // via right-click (and double-click) instead of on-node chrome.
      if (visualizationType !== 'ontograph' && visualizationType !== 'vowl' && hasChildren(d.id, allEdges, allNodes)) {
        const isExpanded = expandedNodeIds.has(d.id);
        const isDark = effectiveDark;
        
        // Position the expander at the bottom-right of the node shape
        let expanderX = 0;
        let expanderY = 0;
        
        if (visualizationType === 'vowl') {
          if (nodeType === 'class') {
            const r = size * 1.8;
            expanderX = r * 0.7;
            expanderY = r * 0.7;
          } else if (nodeType === 'datatype') {
            expanderX = (size * 4.2) / 2 - 2;
            expanderY = (size * 2.0) / 2 - 2;
          } else if (nodeType === 'individual') {
            const label = d.label || '';
            const baseWidth = size * 2.8;
            const labelWidth = Math.min(label.length * 7, 180);
            const rectWidth = Math.max(baseWidth, labelWidth + 16);
            const maxWidth = size * 5.0;
            const finalW = Math.min(rectWidth, maxWidth);
            expanderX = finalW / 2 - 2;
            expanderY = (size * 1.8) / 2 - 2;
          } else {
            expanderX = size * 1.2;
            expanderY = size * 0.5;
          }
        } else if (visualizationType === 'force') {
          if (nodeType === 'class') {
            expanderX = size * 3.0;
            expanderY = size * 1.5;
          } else if (nodeType === 'datatype') {
            expanderX = size * 0.8;
            expanderY = size * 0.3;
          } else if (nodeType === 'individual') {
            expanderX = size * 1.3;
            expanderY = size * 0.5;
          } else {
            expanderX = size * 1.0;
            expanderY = size * 0.5;
          }
        }

        const expanderGroup = nodeGroup.append('g')
          .attr('class', 'expander-icon')
          .attr('cursor', 'pointer')
          .on('click', (event: any) => {
            event.stopPropagation();
            handleToggleExpansion(d.id);
          });

        expanderGroup.append('circle')
          .attr('cx', expanderX)
          .attr('cy', expanderY)
          .attr('r', 8)
          .attr('fill', isDark ? '#374151' : '#ffffff')
          .attr('stroke', isDark ? '#9ca3af' : '#6b7280')
          .attr('stroke-width', 1.5);

        expanderGroup.append('text')
          .attr('x', expanderX)
          .attr('y', expanderY + 4)
          .attr('text-anchor', 'middle')
          .attr('font-size', '12px')
          .attr('font-weight', 'bold')
          .attr('fill', isDark ? '#d1d5db' : '#374151')
          .text(isExpanded ? '−' : '+')
          .style('pointer-events', 'none');
      }
    });

    // Remember each shape's base styling so the selection/hover effect can
    // layer highlights on top and restore the original look afterwards
    node.selectAll<SVGGraphicsElement, D3Node>('.node-shape').each(function() {
      const el = d3.select(this);
      el.attr('data-base-fill', el.attr('fill') || 'none');
      el.attr('data-base-stroke', el.attr('stroke') || 'none');
      el.attr('data-base-stroke-width', el.attr('stroke-width') || '1');
      el.attr('data-base-filter', this.style.filter || 'none');
    });

    // Node labels - INSIDE for VOWL and OntoGraph, outside for force mode
    // Hide labels when zoomed out on large graphs (LOD)
    const showLabels = !isLargeGraph || viewportBoundsRef.current.scale >= 0.5;
    
    // Surface-colored halo keeps labels readable over crossing edges at density
    // without label boxes. VOWL mode is exempt: its notation rendering (text
    // inside semantically-shaped nodes) follows VOWL label conventions.
    const labelHalo = effectiveDark ? '#1b1e2b' : '#ffffff';
    node.append('text')
      .attr('class', 'node-label-text')
      .attr('data-base-font-size', d => {
        // Stashed so the zoom handler can counter-scale against zoom-out
        // without recomputing the whole per-type font-size formula on every
        // zoom tick — VOWL mode only (see the zoom handler below); other
        // modes already handle legibility differently (ontograph hides
        // labels below a zoom threshold instead).
        if (visualizationType !== 'vowl') return null;
        return d.type === 'setOperator' ? 16 : Math.min(11, vowlOptions.labelFontSize);
      })
      .attr('paint-order', 'stroke')
      .attr('stroke', visualizationType === 'vowl' ? 'none' : labelHalo)
      .attr('stroke-width', visualizationType === 'vowl' ? 0 : 3)
      .attr('stroke-linejoin', 'round')
      .attr('dx', d => {
        if (visualizationType === 'vowl') return 0;
        if (visualizationType === 'ontograph') {
          const size = d.size || settings.nodeSize;
          const simplifiedLOD = isLargeGraph && viewportBoundsRef.current.scale < 0.5;
          const rectWidth = simplifiedLOD ? size * 5 : size * 9;
          return -rectWidth / 2 + 16; // Aligned after accent stripe
        }
        if (visualizationType === 'force') return 0; // Centered labels for force mode
        if (visualizationType === 'spatial3d') return 0; // Centered inside circle for 3D
        return (d.size || settings.nodeSize) + 8;
      })
      .attr('dy', d => {
        if (visualizationType === 'vowl') return 5;
        if (visualizationType === 'ontograph') return 6; // Vertically centered in taller card
        if (visualizationType === 'force') return 4; // Vertically centered for force mode
        if (visualizationType === 'spatial3d') return 4; // Centered in circle
        return 4;
      })
      .attr('text-anchor', d => {
        if (visualizationType === 'vowl') return 'middle';
        if (visualizationType === 'ontograph') return 'start'; // Left-aligned for OntoGraph
        if (visualizationType === 'force') return 'middle'; // Center text for force mode
        if (visualizationType === 'spatial3d') return 'middle'; // Center text in 3D node
        return 'start';
      })
      .attr('font-size', d => {
        if (visualizationType === 'vowl') return d.type === 'setOperator' ? 16 : Math.min(11, vowlOptions.labelFontSize);
        if (visualizationType === 'ontograph') return 12;
        if (visualizationType === 'force') return vowlOptions.labelFontSize;
        if (visualizationType === 'spatial3d') return 10; // Compact to fit inside circle
        return 13;
      })
      .attr('font-family', d => {
        if (visualizationType === 'ontograph') return '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif';
        if (visualizationType === 'vowl') return '"Helvetica Neue", Helvetica, Arial, sans-serif';
        if (visualizationType === 'force') return 'Arial, sans-serif';
        return 'inherit';
      })
      .attr('font-weight', d => {
        if (visualizationType === 'vowl') return d.type === 'class' ? '500' : '400';
        if (visualizationType === 'ontograph') return '500';
        if (visualizationType === 'force') return '600';
        if (visualizationType === 'spatial3d') return '600';
        return '500';
      })
      .attr('fill', d => {
        if (visualizationType === 'vowl' || visualizationType === 'force') {
          // Read the color the shape was ACTUALLY painted with (stashed in
          // node.each above) instead of re-deriving a guess from TYPE_COLORS —
          // that guess used the raw saturated accent, not the pastel tint most
          // node types render with, which picked white text on light node fills.
          const painted = (d as any).__nodeFillColor || (effectiveDark ? '#6b92c4' : '#69c');
          if (visualizationType === 'vowl') {
            // VOWL: white labels on blue classes; black on yellow/green/white
            const t = d.type;
            if (t === 'class' || t === 'setOperator') {
              const isThingNode = d.label === 'Thing' || isThingIri(d.id);
              return isThingNode ? '#111111' : '#ffffff';
            }
            if (t === 'individual') return '#111111';
            if (t === 'datatype') return '#111111';
          }
          return getReadableTextColor(painted);
        }
        if (visualizationType === 'ontograph') {
          return effectiveDark ? '#e2e8f0' : '#1e293b'; // Slate tones for modern look
        }
        if (visualizationType === 'spatial3d') {
          return '#ffffff'; // White text — always readable against node fills
        }
        return '#333';
      })
      .attr('stroke', d => {
        if (visualizationType === 'spatial3d') return 'rgba(0,0,0,0.75)';
        if (visualizationType === 'vowl') return 'none';
        return labelHalo;
      })
      .attr('stroke-width', d => {
        if (visualizationType === 'spatial3d') return 3;
        if (visualizationType === 'vowl') return 0;
        return 3;
      })
      .style('paint-order', 'stroke')
      .text(d => {
        if (visualizationType === 'vowl') {
          // Set-operator nodes render their VOWL glyph, not a text label
          if (d.type === 'setOperator') {
            return SET_OPERATOR_SYMBOLS[(d as any).metadata?.setOperator] || '∪';
          }
          // Truncate labels in VOWL mode to fit within their shapes. Char budget
          // derives from the actual (scaled) shape width and chosen font size, so
          // text can never spill outside the box regardless of the Notation &
          // density panel's width/height/font-size sliders.
          const label = d.label || '';
          const size = d.size || settings.nodeSize;
          const charWidthPx = vowlOptions.labelFontSize * (7 / 11); // 7px/char was calibrated at 11px font
          // Recomputed here (not read from node.each's closure — this is a sibling D3
          // callback on the same selection, not a nested function, so those locals
          // aren't in scope).
          const widthScale = vowlOptions.nodeWidthScale;
          const heightScale = vowlOptions.nodeHeightScale;
          let maxChars = 18;

          if (d.type === 'datatype') {
            // Rect width = size * 4.2 * widthScale, minus padding
            maxChars = Math.max(4, Math.floor((size * 4.2 * widthScale - 12) / charWidthPx));
          } else if (d.type === 'individual') {
            // Mirrors the rect sizing above (base/label-driven width, capped at size * 5.0 * widthScale)
            const label2 = d.label || '';
            const baseWidth = size * 2.8 * widthScale;
            const labelWidth = Math.min(label2.length * 7, 180);
            const rectWidth = Math.max(baseWidth, labelWidth + 16);
            const finalWidth = Math.min(rectWidth, size * 5.0 * widthScale);
            maxChars = Math.max(4, Math.floor((finalWidth - 10) / charWidthPx));
          } else if (d.type === 'class') {
            // Circle radius = size * 1.8 * avg(scale) → diameter-based budget
            const diameter = size * 2.7 * ((widthScale + heightScale) / 2);
            maxChars = Math.max(6, Math.floor((diameter - 8) / charWidthPx));
          }

          // User-set cap layers on top of the shape-fitted budget
          maxChars = Math.min(maxChars, vowlOptions.maxLabelChars);
          return label.length > maxChars ? label.substring(0, Math.max(1, maxChars - 2)) + '..' : label;
        }
        if (visualizationType === 'ontograph' && !showLabels) {
          return ''; // Hide labels when zoomed out on large graphs
        }
        if (visualizationType === 'ontograph') {
          // Card width = size*9, padding 32px, font ~7px/char → max = floor((size*9-32)/7)
          const s = d.size || settings.nodeSize;
          const maxChars = Math.max(8, Math.floor((s * 9 - 32) / 7));
          const label = d.label || '';
          return label.length > maxChars ? label.substring(0, maxChars - 2) + '..' : label;
        }
        if (visualizationType === 'force') {
          // Shape-aware budget mirroring the vowl branch above — force-mode box sizes
          // are fixed (not label-length-driven), so without this a long label simply
          // spilled outside its oval/rect. Char budget derives from the actual (scaled)
          // shape width and chosen font size.
          const label = d.label || '';
          const size = d.size || settings.nodeSize;
          const charWidthPx = vowlOptions.labelFontSize * (7 / 11);
          // Recomputed here — sibling D3 callback, not nested inside node.each.
          const widthScale = vowlOptions.nodeWidthScale;
          let maxChars = 18;

          if (d.type === 'datatype') {
            maxChars = Math.max(4, Math.floor((size * 2.2 * widthScale - 10) / charWidthPx));
          } else if (d.type === 'individual') {
            maxChars = Math.max(4, Math.floor((size * 3.2 * widthScale - 10) / charWidthPx));
          } else if (d.type === 'class') {
            maxChars = Math.max(6, Math.floor((size * 7.0 * widthScale - 10) / charWidthPx));
          }

          maxChars = Math.min(maxChars, vowlOptions.maxLabelChars);
          return label.length > maxChars ? label.substring(0, Math.max(1, maxChars - 3)) + '...' : label;
        }
        if (visualizationType === 'spatial3d') {
          // Short truncation — text is centered inside the circle, so space is limited
          const label = d.label || '';
          return label.length > 12 ? label.substring(0, 10) + '..' : label;
        }
        return settings.showLabels ? d.label : '';
      })
      .style('pointer-events', 'none')
      .style('user-select', 'none');

    // Stack equivalent-class names when the comma label won't fit (VOWL style)
    if (visualizationType === 'vowl') {
      node.select('text').each(function(d: any) {
        const equivParts: string[] | undefined = d?.metadata?.vowlEquivalent
          ? d.metadata?.equivalentLabels
          : undefined;
        if (!equivParts || equivParts.length < 2 || d.type !== 'class') return;
        const size = d.size || settings.nodeSize;
        const charWidthPx = vowlOptions.labelFontSize * (7 / 11);
        const diameter = size * 3.6 * ((vowlOptions.nodeWidthScale + vowlOptions.nodeHeightScale) / 2);
        const maxChars = Math.min(
          vowlOptions.maxLabelChars,
          Math.max(6, Math.floor((diameter - 8) / charWidthPx))
        );
        const joined = d.label || equivParts.join(', ');
        if (joined.length <= maxChars) return; // single-line fits
        const el = d3.select(this);
        el.text(null);
        const perLine = Math.max(4, Math.floor(maxChars * 0.9));
        const startDy = -((equivParts.length - 1) * 0.55);
        equivParts.forEach((part: string, i: number) => {
          const text = part.length > perLine ? part.substring(0, perLine - 2) + '..' : part;
          el.append('tspan')
            .attr('x', 0)
            .attr('dy', i === 0 ? `${startDy}em` : '1.1em')
            .text(text);
        });
      });
    }

    // Node type badge — skip entirely in VOWL (shape/color IS the type; letters clutter)
    // Skip for ontograph mode since badges are built into the card design
    // Skip badge for spatial3d — label is already centered inside the node
    if (visualizationType !== 'ontograph' && visualizationType !== 'spatial3d' && visualizationType !== 'vowl') {
    node.append('text')
      .attr('dx', 0)
      .attr('dy', d => {
        const size = d.size || settings.nodeSize;
        return size + 15;
      })
      .attr('text-anchor', 'middle')
      .attr('font-size', 9)
      .attr('fill', effectiveDark ? '#cbd5e1' : '#666')
      .attr('font-weight', '600')
      .text(d => {
        if (d.type === 'setOperator') {
          if (vowlOptions.compactNotation) return '';
          const kind = (d as any).metadata?.setOperator;
          return kind ? `(${kind})` : '';
        }
        return d.type.substring(0, 1).toUpperCase();
      })
      .style('pointer-events', 'none')
      .style('opacity', 0.7);
    } else if (visualizationType === 'vowl') {
      // Set-operator caption only — VOWL shows "(union)" under operator glyphs
      node.filter(d => d.type === 'setOperator' && !vowlOptions.compactNotation)
        .append('text')
        .attr('dx', 0)
        .attr('dy', d => (d.size || settings.nodeSize) * 2 + 12)
        .attr('text-anchor', 'middle')
        .attr('font-size', 9)
        .attr('fill', '#374151')
        .attr('font-weight', '500')
        .text(d => {
          const kind = (d as any).metadata?.setOperator;
          return kind ? `(${kind})` : '';
        })
        .style('pointer-events', 'none');
    }

    // Simulation tick with aggressive throttling for performance
    let rafId: number | null = null;
    let ticking = false;
    let tickCount = 0;
    // Skip more frames as graphs grow — node motion stays smooth, ancillary work staggers
    const updateInterval = nodeCount > 5000 ? 5 : nodeCount > 2000 ? 4 : nodeCount > 100 ? 3 : 2;

    // Update edge paths with curvature for multiple edges between same nodes.
    // Computed ONCE per edge per pass and cached on the datum — the hit-area layer
    // reuses the cached string instead of recomputing the boundary trig.
    const computeEdgePath = (d: D3Edge): string => {
      const source = d.source as D3Node;
      const target = d.target as D3Node;

      if (source.x == null || source.y == null || target.x == null || target.y == null) {
        return '';
      }

      const sourcePoint = getRenderPoint(source);
      const targetPoint = getRenderPoint(target);
      const curve = edgeCurvature.get(d.id) || 0;

      // VOWL chip physics: the edge bends through its label node's position.
      const chip = (d as any).__chipNode as D3Node | undefined;
      if (chip && chip.x != null && chip.y != null) {
        // Self-loop: draw a cubic loop out through the chip and back
        if (source === target) {
          const loopDx = chip.x - sourcePoint.x;
          const loopDy = chip.y - sourcePoint.y;
          const loopDist = Math.sqrt(loopDx * loopDx + loopDy * loopDy) || 1;
          const px = -loopDy / loopDist;
          const py = loopDx / loopDist;
          const spread = 45;
          return `M${sourcePoint.x},${sourcePoint.y}` +
            `C${chip.x + px * spread},${chip.y + py * spread}` +
            ` ${chip.x - px * spread},${chip.y - py * spread}` +
            ` ${sourcePoint.x},${sourcePoint.y}`;
        }
        // Quadratic through the chip: control = 2*chip - midpoint (curve passes
        // exactly through the chip at t=0.5, where the label rect sits)
        const cx = 2 * chip.x - (sourcePoint.x + targetPoint.x) / 2;
        const cy = 2 * chip.y - (sourcePoint.y + targetPoint.y) / 2;
        // Trim at the target boundary along the incoming curve direction
        const inDx = targetPoint.x - cx;
        const inDy = targetPoint.y - cy;
        const inDist = Math.sqrt(inDx * inDx + inDy * inDy) || 1;
        let tr = (target.size || settings.nodeSize);
        if (target.type === 'datatype' || target.type === 'dataProperty') {
          const w = tr * 2.8;
          const h = tr * 1.8;
          const absCos = Math.abs(inDx / inDist);
          const absSin = Math.abs(inDy / inDist);
          tr = Math.min((w / 2) / Math.max(absCos, 1e-6), (h / 2) / Math.max(absSin, 1e-6)) + 2;
        } else {
          // Matches the actual rendered VOWL class circle radius (size * 1.35 *
          // avgScale, see the node.type === 'class' shape-drawing code) — this
          // used to assume 1.8x, well past the true ~1.35x boundary, so the
          // arrowhead stopped short in empty space instead of touching the circle.
          const avgScale = (vowlOptions.nodeWidthScale + vowlOptions.nodeHeightScale) / 2;
          tr = tr * 1.35 * avgScale + 4;
        }
        const tx = targetPoint.x - (inDx / inDist) * tr;
        const ty = targetPoint.y - (inDy / inDist) * tr;
        return `M${sourcePoint.x},${sourcePoint.y}Q${cx},${cy},${tx},${ty}`;
      }

      // Calculate edge endpoints
      const dx = targetPoint.x - sourcePoint.x;
      const dy = targetPoint.y - sourcePoint.y;
      const dist = Math.sqrt(dx * dx + dy * dy);

      if (dist === 0) {
        return `M${sourcePoint.x},${sourcePoint.y}L${targetPoint.x},${targetPoint.y}`;
      }

      // Shorten edge to stop at node boundary - dynamic based on visualization type and node shape
      let r = (target.size || settings.nodeSize);

      if (visualizationType === 'vowl') {
        if (target.type === 'datatype' || target.type === 'dataProperty') {
          // Rectangle boundary for VOWL datatypes (approx 2.8x by 1.8x)
          const w = r * 2.8;
          const h = r * 1.8;
          const absCos = Math.abs(dx / dist);
          const absSin = Math.abs(dy / dist);
          r = Math.min((w / 2) / absCos, (h / 2) / absSin) + 2;
        } else {
          // Matches the actual rendered VOWL class circle radius (size * 1.35 *
          // avgScale, see the node.type === 'class' shape-drawing code) — this
          // used to assume 1.8x, well past the true ~1.35x boundary, so the
          // arrowhead stopped short in empty space instead of touching the circle.
          const avgScale = (vowlOptions.nodeWidthScale + vowlOptions.nodeHeightScale) / 2;
          r = r * 1.35 * avgScale + 4;
        }
      } else if (visualizationType === 'ontograph') {
        // Rounded rectangle boundary for OntoGraph (approx 7.5x by 2.0x)
        const w = r * 7.5;
        const h = r * 2.0;
        const absCos = Math.abs(dx / dist);
        const absSin = Math.abs(dy / dist);
        r = Math.min((w / 2) / absCos, (h / 2) / absSin) + 2;
      } else if (visualizationType === 'force') {
        if (target.type === 'class') {
          // Ellipse boundary approximation (3.5x by 2.0x)
          const w = r * 3.5 * 2;
          const h = r * 2.0 * 2;
          const absCos = Math.abs(dx / dist);
          const absSin = Math.abs(dy / dist);
          r = Math.min((w / 2) / absCos, (h / 2) / absSin) + 2;
        } else {
          r = r * 1.2 + 4;
        }
      } else {
        r = r + 5;
      }

      const targetX = targetPoint.x - (dx / dist) * r;
      const targetY = targetPoint.y - (dy / dist) * r;

      if (curve === 0) {
        // Straight line
        return `M${sourcePoint.x},${sourcePoint.y}L${targetX},${targetY}`;
      } else {
        // Curved path - quadratic bezier curve
        // Calculate control point perpendicular to the line
        const midX = (sourcePoint.x + targetX) / 2;
        const midY = (sourcePoint.y + targetY) / 2;

        // Perpendicular offset
        const perpX = -dy / dist;
        const perpY = dx / dist;

        const controlX = midX + perpX * curve;
        const controlY = midY + perpY * curve;

        return `M${sourcePoint.x},${sourcePoint.y}Q${controlX},${controlY},${targetX},${targetY}`;
      }
    };

    const updateEdgePaths = (includeHitAreas: boolean) => {
      link.attr('d', (d: any) => {
        if (d.__culled) return d.__path || '';
        d.__path = computeEdgePath(d);
        return d.__path;
      });
      if (includeHitAreas) {
        edgeHitArea?.attr('d', (d: any) => d.__path ?? computeEdgePath(d));
      }
    };

    const updateLinkLabelPositions = () => {
      linkLabel.each(function(d: any, i: number) {
        if (d.__culled) return;

        // Chip physics: the label lives exactly at its simulation node
        const chip = d.__chipNode as D3Node | undefined;
        if (chip && chip.x != null && chip.y != null) {
          d3.select(this).attr('x', chip.x).attr('y', chip.y);
          if (vowlChipsAlwaysVisible && (d.__chipW ?? 0) > 0) {
            const bg = linkLabelBg.nodes()[i] as SVGRectElement | undefined;
            if (bg) {
              bg.setAttribute('x', String(chip.x - d.__chipW / 2));
              bg.setAttribute('y', String(chip.y - d.__chipH / 2));
            }
          }
          return;
        }

        const sourcePoint = getRenderPoint(d.source as D3Node);
        const targetPoint = getRenderPoint(d.target as D3Node);
        const sourceX = sourcePoint.x;
        const sourceY = sourcePoint.y;
        const targetX = targetPoint.x;
        const targetY = targetPoint.y;
        const dx = targetX - sourceX;
        const dy = targetY - sourceY;
        const dist = Math.sqrt(dx * dx + dy * dy);

        const curve = edgeCurvature.get(d.id) || 0;

        // Position label at the MIDDLE of the edge
        let labelX: number;
        let labelY: number;

        if (curve === 0) {
          // Straight line
          const t = 0.5;
          labelX = sourceX + dx * t;
          labelY = sourceY + dy * t;

          // Small perpendicular offset for readability
          if (dist > 0) {
            const perpX = -dy / dist;
            const perpY = dx / dist;
            const perpOffset = visualizationType === 'vowl' ? 0 : 10; // Centered for VOWL
            labelX += perpX * perpOffset;
            labelY += perpY * perpOffset;
          }
        } else {
          // Curved path - position label on the curve at t=0.5
          const midX = (sourceX + targetX) / 2;
          const midY = (sourceY + targetY) / 2;

          // Perpendicular offset for curve control point — guard dist===0 (source and
          // target coincide, e.g. nodes not yet separated by the simulation), matching
          // computeEdgePath's dist===0 early return; otherwise this divides by zero and
          // cascades NaN into labelX/labelY, spamming "<text> attribute x/y: NaN" errors.
          const perpX = dist > 0 ? -dy / dist : 0;
          const perpY = dist > 0 ? dx / dist : 0;

          const controlX = midX + perpX * curve;
          const controlY = midY + perpY * curve;

          // Quadratic bezier point at t=0.5
          const t = 0.5;
          labelX = (1-t)*(1-t)*sourceX + 2*(1-t)*t*controlX + t*t*targetX;
          labelY = (1-t)*(1-t)*sourceY + 2*(1-t)*t*controlY + t*t*targetY;

          // Additional small offset for label readability
          const labelOffset = 10;
          labelX += perpX * labelOffset;
          labelY += perpY * labelOffset;
        }

        d3.select(this)
          .attr('x', labelX)
          .attr('y', labelY);

        // Keep the always-visible VOWL chip glued to its label during the force
        // simulation using dimensions cached by updateLinkLabelBackgrounds —
        // getBBox per tick would thrash layout. Text is middle-anchored, so the
        // chip centers on (labelX, labelY).
        if (vowlChipsAlwaysVisible && (d.__chipW ?? 0) > 0) {
          const bg = linkLabelBg.nodes()[i] as SVGRectElement | undefined;
          if (bg) {
            bg.setAttribute('x', String(labelX - d.__chipW / 2));
            bg.setAttribute('y', String(labelY - d.__chipH / 2));
          }
        }
      });
    };

    // Live viewport culling — all visualization modes, activates above the large-graph
    // threshold. Runs at gesture/sim ends only (never per tick): offscreen node groups
    // get display:none; edges/labels whose endpoints are both culled are hidden and
    // skipped by the tick handler. Positions keep persisting from sim data, not DOM.
    const cullingEnabled = nodeCount > LARGE_GRAPH_THRESHOLD;
    const applyViewportCulling = () => {
      if (!cullingEnabled) return;
      const vp = viewportBoundsRef.current;
      if (!vp.width || !vp.height) return;
      const buffer = 400;
      const minX = vp.x - buffer;
      const maxX = vp.x + vp.width + buffer;
      const minY = vp.y - buffer;
      const maxY = vp.y + vp.height + buffer;
      node.style('display', (d: any) => {
        const visible = d.x == null || d.y == null ||
          (d.x >= minX && d.x <= maxX && d.y >= minY && d.y <= maxY);
        d.__nodeCulled = !visible;
        return visible ? null : 'none';
      });
      const edgeCulled = (d: any) => {
        d.__culled = Boolean((d.source as any).__nodeCulled && (d.target as any).__nodeCulled);
        return d.__culled ? 'none' : null;
      };
      link.style('display', edgeCulled);
      edgeHitArea?.style('display', (d: any) => (d.__culled ? 'none' : null));
      linkLabel.style('display', (d: any) => (d.__culled ? 'none' : null));
      linkLabelBg.style('display', (d: any) => (d.__culled ? 'none' : null));
    };
    applyViewportCullingRef.current = applyViewportCulling;
    uncullForExportRef.current = () => {
      node.style('display', null);
      link.style('display', null);
      edgeHitArea?.style('display', null);
      linkLabel.style('display', null);
      linkLabelBg.style('display', null);
      d3Edges.forEach((d: any) => {
        d.__nodeCulled = false;
        d.__culled = false;
      });
      d3Nodes.forEach((d: any) => { d.__nodeCulled = false; });
      updateEdgePaths(true);
      updateLinkLabelPositions();
    };

    // Force initial render to show nodes immediately
    if (visualizationType === 'ontograph' && ontographLayoutType !== 'spring') {
      simulation.alpha(0.01).restart(); // Minimal alpha since positions are fixed
    } else if (visualizationType === 'ontograph') {
      simulation.alpha(1).restart();
    }

    simulation.on('tick', () => {
      tickCount++;
      // Skip frames for better performance
      if (tickCount % updateInterval !== 0) return;

      if (!ticking) {
        ticking = true;
        rafId = requestAnimationFrame(() => {
          if (isSpatial3D) {
            d3Nodes.forEach(project3DNode);
          }

          // Chips are free simulation nodes now (real charge + rigid half-links,
          // see linkStrength/linkDistance below) — no per-tick re-pin to a static
          // spoke slot. D3's own integrator already updated chip.x/y this tick.

          // Hit-area paths are pointer targets, not visuals — they sync on sim/gesture end
          updateEdgePaths(false);

          // Edge labels: VOWL labels are hover-only (positioned on 'end'); other modes
          // skip the bezier math entirely when labels are switched off.
          if ((visualizationType !== 'vowl' && settings.showLabels !== false) || vowlChipsAlwaysVisible) {
            updateLinkLabelPositions();
          }
          // Disjointness symbols and restriction badges are always visible and
          // few — track the edge midpoint live
          if (visualizationType === 'vowl') {
            updateDisjointSymbols();
            updateRestrictionBadges();
          }
          // Label backgrounds sized once after layout — getBBox in tick forces layout thrash

          node.attr('transform', d => {
            const point = getRenderPoint(d);
            return `translate(${point.x},${point.y}) scale(${visualizationType === 'spatial3d' ? Math.max(0.72, Math.min(1.28, point.scale)) : 1})`;
          });
          if (isSpatial3D) {
            // Depth-based opacity only applies in 3D — writing opacity per tick in other
            // modes would stomp the entrance fade transition.
            node.style('opacity', d => {
              const point = getRenderPoint(d);
              return Math.max(0.58, Math.min(1, point.scale * 0.92));
            });
          }

          // Persist node positions so expand/collapse doesn't scramble the graph
          // (simNodes also covers chip label nodes so edges keep their bends)
          simNodes.forEach(n => {
            if (n.x != null && n.y != null) {
              nodePositionsRef.current.set(n.id, { x: n.x, y: n.y });
            }
          });

          ticking = false;
        });
      }
    });

    simulation.on('end.render', () => {
      updateEdgePaths(true);
      updateLinkLabelPositions();
      updateDisjointSymbols();
      updateRestrictionBadges();
      updateLinkLabelBackgrounds();
      resolveMeasuredChipOverlaps();
      updateLinkLabelPositions();
      applyViewportCulling();
    });

    // First measured AABB pass once labels have real getBBox sizes
    if (vowlChipsAlwaysVisible) {
      requestAnimationFrame(() => {
        updateLinkLabelBackgrounds();
        resolveMeasuredChipOverlaps();
        updateLinkLabelPositions();
      });
    }

    // Drag functions
    function dragStarted(event: any, d: D3Node) {
      userInteractedRef.current = true; // dragging a node cancels pending entrance refits
      if (!event.active) simulation.alphaTarget(0.3).restart();
      d.fx = d.x;
      d.fy = d.y;
    }

    function dragged(event: any, d: D3Node) {
      d.fx = event.x;
      d.fy = event.y;
    }

    function dragEnded(event: any, d: D3Node) {
      if (!event.active) simulation.alphaTarget(0);
      if (!editModeRef.current) {
        // Keep VOWL neighborhood hubs pinned after drag (new position becomes the pin)
        if (visualizationType === 'vowl' && vowlPinnedHubIdsRef.current.has(d.id)) {
          d.fx = d.x;
          d.fy = d.y;
        } else {
          d.fx = null;
          d.fy = null;
        }
      }
      // Hit-areas skipped path updates during the drag; sync them now, then re-cull
      updateEdgePaths(true);
      applyViewportCulling();
    }

    // Node interaction handlers
    // VOWL clones (per-edge Thing/Literal duplicates) resolve back to their
    // original node for selection details, callbacks, and context-menu actions.
    function resolveVowlClone(d: D3Node): OntologyNode {
      const originalId = (d as any).metadata?.cloneOf as string | undefined;
      if (!originalId) return d as OntologyNode;
      return allNodes.find(n => n.id === originalId) ?? (d as OntologyNode);
    }

    function handleNodeClick(event: any, d: D3Node) {
      event.stopPropagation();
      // The hover tooltip has no mouseout to clear it until the pointer actually
      // leaves the node — a click doesn't move the pointer, so the tooltip box
      // was sitting on top of the freshly-highlighted node/edges it just caused.
      d3.selectAll('.graph-tooltip').remove();

      if (event.ctrlKey || event.metaKey) {
        // Multi-select
        const newSelected = new Set(selectedNodesRef.current);
        if (newSelected.has(d.id)) {
          newSelected.delete(d.id);
        } else {
          newSelected.add(d.id);
        }
        setSelectedNodes(newSelected);
      } else {
        // Single click — just select the node; navigation to entity details is
        // deliberately NOT triggered here (right-click > Go to Entity Details).
        const original = resolveVowlClone(d);
        setSelectedNodes(new Set([d.id]));
        setSelectedNodeInfo(original);
        // Focus the inline hierarchy navigator too (sidebar-only — this does not
        // open the floating popup, see onToggleNavigator/context menu for that).
        if (original.type === 'class') {
          setHierarchyRootNode(original);
        }
      }
    }

    function handleNodeRightClick(event: any, d: D3Node) {
      event.preventDefault();
      event.stopPropagation();
      d3.selectAll('.graph-tooltip').remove();

      const original = resolveVowlClone(d);

      // Show context menu
      setContextMenu({
        visible: true,
        x: event.pageX,
        y: event.pageY,
        nodeId: original.id
      });

      // Also select the node
      setSelectedNodes(new Set([d.id]));
      setSelectedNodeInfo(original);
    }

    function handleNodeMouseOver(event: any, d: D3Node) {
      setHoveredNode(d.id);

      if (settings.tooltips) {
        // Remove any existing tooltips first
        d3.selectAll('.graph-tooltip').remove();

        const isDarkTip = effectiveDark;
        const accent = ACCENT_COLORS[d.type] || '#3b82f6';
        const tooltip = d3.select('body').append('div')
          .attr('class', 'graph-tooltip')
          .style('position', 'absolute')
          .style('background', isDarkTip ? 'rgba(15,23,42,0.92)' : 'rgba(255,255,255,0.96)')
          .style('color', isDarkTip ? '#e2e8f0' : '#1e293b')
          .style('backdrop-filter', 'blur(10px)')
          .style('-webkit-backdrop-filter', 'blur(10px)')
          .style('border', `1px solid ${hexToRgba(accent, 0.4)}`)
          .style('border-left', `3px solid ${accent}`)
          .style('box-shadow', `0 8px 24px rgba(0,0,0,0.22), 0 0 12px ${hexToRgba(accent, 0.18)}`)
          .style('padding', '10px 14px')
          .style('border-radius', '10px')
          .style('font-size', '12px')
          .style('line-height', '1.5')
          .style('max-width', '280px')
          .style('pointer-events', 'none')
          .style('z-index', '1000')
          .style('left', `${event.pageX + 12}px`)
          .style('top', `${event.pageY + 12}px`)
          .style('opacity', '0')
          .style('transform', 'translateY(4px)')
          .style('transition', 'opacity 160ms ease, transform 160ms ease')
          .html(`
            <div style="font-weight:600;font-size:13px;margin-bottom:4px;">${d.label}</div>
            <span style="display:inline-block;padding:1px 8px;border-radius:9999px;font-size:10px;font-weight:600;letter-spacing:0.5px;text-transform:uppercase;background:${hexToRgba(accent, 0.15)};color:${accent};">${d.type}</span>
            ${d.description ? `<div style="margin-top:6px;opacity:0.75;">${d.description.substring(0, 140)}${d.description.length > 140 ? '…' : ''}</div>` : ''}
          `);
        requestAnimationFrame(() => {
          tooltip.style('opacity', '1').style('transform', 'translateY(0)');
        });
      }
    }

    function handleNodeMouseOut() {
      setHoveredNode(null);
      d3.selectAll('.graph-tooltip').remove();
    }

    // Zoom behavior
    const zoom = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.1, 10])
      // ~25% finer than d3's default wheel step — noticeably less jumpy on mice
      .wheelDelta((event: WheelEvent) =>
        -event.deltaY * (event.deltaMode === 1 ? 0.05 : event.deltaMode ? 1 : 0.002) * (event.ctrlKey ? 10 : 1) * 0.75
      )
      .on('zoom', (event) => {
        // A real user gesture (wheel/drag, not a programmatic fit) cancels entrance refits
        if (event.sourceEvent) userInteractedRef.current = true;
        g.attr('transform', event.transform);
        currentTransformRef.current = event.transform;
        // Avoid React re-render on every pan frame — update zoom label on gesture end only

        // VOWL labels otherwise shrink with everything else on zoom-out (the
        // whole graph sits in one transformed group). Two failure modes,
        // verified in the test harness at 35 visible nodes:
        //  - Naively counter-scaling to a fixed legible size at ANY zoom: once
        //    "Fit to screen" needs k below ~0.4-0.5 to show every node (which
        //    it does for 30+ nodes), the circles themselves shrink smaller
        //    than the now-fixed-size text, so labels overflow their circles
        //    and collide with neighbors — readable in isolation, messy as a
        //    whole.
        //  - Not counter-scaling at all: text shrinks to the ~3px illegible
        //    mush this whole fix was for.
        // So: counter-scale to stay pinned at the base size between 1:1 and
        // HIDE_LABEL_ZOOM_THRESHOLD (labels still fit their now similarly-
        // shrunk-but-not-tiny circles there); below that, hide labels
        // entirely for a clean structure-only view — same idea as
        // ontograph's existing showLabels zoom cutoff, just not shared code
        // since the two modes' text rendering is a fully separate branch.
        if (visualizationType === 'vowl') {
          const k = event.transform.k;
          const HIDE_LABEL_ZOOM_THRESHOLD = 0.5;
          const labelSelection = g.selectAll<SVGTextElement, unknown>('text.node-label-text');
          labelSelection.style('display', k < HIDE_LABEL_ZOOM_THRESHOLD ? 'none' : 'inline');
          labelSelection.attr('font-size', function () {
            const base = parseFloat(this.getAttribute('data-base-font-size') || '');
            if (!base) return null;
            return k <= 1 ? base / k : base;
          });
        }
      })
      .on('end', (event) => {
        setZoomLevel(event.transform.k);
        // Always track the viewport (cheap ref write) so culling/LOD reads are never stale
        const transform = event.transform;
        viewportBoundsRef.current = {
          x: -transform.x / transform.k,
          y: -transform.y / transform.k,
          width: width / transform.k,
          height: height / transform.k,
          scale: transform.k
        };
        // Re-evaluate which nodes/edges are on screen after every camera move
        applyViewportCullingRef.current();
      });

    zoomRef.current = zoom;
    svg.call(zoom as any);
    // Disable default double-click zoom so dblclick on nodes triggers expand/collapse
    svg.on('dblclick.zoom', null);

    // Buttery wheel zoom: accumulate a target scale per wheel event, then rAF-lerp
    // toward it anchored at the pointer. Applied via zoom.transform so d3's internal
    // gesture state stays consistent. Falls back to native d3 steps when disabled
    // or when the OS asks for reduced motion.
    let smoothWheelRaf: number | null = null;
    if (SMOOTH_WHEEL_ZOOM && !prefersReducedMotion) {
      svg.on('wheel.zoom', null); // take over from d3's discrete wheel handler
      let kTarget: number | null = null;
      let px = 0;
      let py = 0;
      const stepSmooth = () => {
        if (kTarget == null || !zoomRef.current || !svgRef.current) { smoothWheelRaf = null; return; }
        const cur = currentTransformRef.current;
        const dk = kTarget - cur.k;
        if (Math.abs(dk) < 0.001) { kTarget = null; smoothWheelRaf = null; return; }
        const k = cur.k + dk * 0.28;
        // Keep the graph point under the pointer fixed while the scale eases in
        const gx = (px - cur.x) / cur.k;
        const gy = (py - cur.y) / cur.k;
        const t = d3.zoomIdentity.translate(px - gx * k, py - gy * k).scale(k);
        d3.select(svgRef.current).call(zoomRef.current.transform as any, t);
        smoothWheelRaf = requestAnimationFrame(stepSmooth);
      };
      svg.on('wheel.smooth', (event: WheelEvent) => {
        event.preventDefault();
        userInteractedRef.current = true;
        svg.interrupt('entranceFit'); // the user owns the camera now
        const factor = Math.pow(
          2,
          -event.deltaY * (event.deltaMode === 1 ? 0.05 : event.deltaMode ? 1 : 0.002) * (event.ctrlKey ? 8 : 1) * 0.75
        );
        const base = kTarget ?? currentTransformRef.current.k;
        kTarget = Math.max(0.1, Math.min(10, base * factor));
        const pt = d3.pointer(event, svgRef.current);
        px = pt[0];
        py = pt[1];
        if (smoothWheelRaf == null) smoothWheelRaf = requestAnimationFrame(stepSmooth);
      });
    }

    // Entrance fades: nodes pop in with a slight stagger, edges connect up just after.
    // Only on a fresh first-open layout — never on incremental re-renders.
    if (entrancePlaying) {
      if (entranceRef.current.mode === 'bloom') {
        node
          .style('opacity', 0)
          .transition('entrance')
          .delay((_d, i) => Math.min(i * 8, 400))
          .duration(350)
          .ease(d3.easeCubicOut)
          .style('opacity', 1);
        g.select<SVGGElement>('g.links')
          .style('opacity', 0)
          .transition('entrance')
          .delay(250)
          .duration(500)
          .style('opacity', 1);
      } else {
        // Calm entrance: one quiet layer fade, no stagger
        g.style('opacity', 0)
          .transition('entrance')
          .duration(250)
          .style('opacity', 1);
      }
    }

    const computeFitTransform = () => {
      if (!svgRef.current || !gRef.current) return null;
      const w = svgRef.current.clientWidth || width;
      const h = svgRef.current.clientHeight || height;
      if (!w || !h) return null;

      // VOWL: fit around pinned hubs (not full bbox) so Fit can't re-squash clusters
      const pinned = vowlPinnedHubIdsRef.current;
      if (visualizationType === 'vowl' && pinned.size >= 2) {
        let hubs = simNodes.filter(
          n => pinned.has(n.id) && n.x != null && n.y != null && Number.isFinite(n.x) && Number.isFinite(n.y)
        );
        if (hubs.length >= 2) {
          if (hubs.length > 6) {
            const deg = (id: string) => nodeDegreeMap.get(id) || 0;
            hubs = [...hubs].sort((a, b) => deg(b.id) - deg(a.id)).slice(0, 5);
          }
          let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
          const localR = 220;
          for (const hub of hubs) {
            minX = Math.min(minX, hub.x! - localR);
            minY = Math.min(minY, hub.y! - localR);
            maxX = Math.max(maxX, hub.x! + localR);
            maxY = Math.max(maxY, hub.y! + localR);
          }
          for (const n of simNodes) {
            if ((n as any).__isChip || n.x == null || n.y == null) continue;
            for (const hub of hubs) {
              if (Math.hypot(n.x - hub.x!, n.y - hub.y!) <= localR) {
                minX = Math.min(minX, n.x - 36);
                minY = Math.min(minY, n.y - 36);
                maxX = Math.max(maxX, n.x + 36);
                maxY = Math.max(maxY, n.y + 36);
                break;
              }
            }
          }
          let minHubDist = Infinity;
          for (let i = 0; i < hubs.length; i++) {
            for (let j = i + 1; j < hubs.length; j++) {
              minHubDist = Math.min(minHubDist, Math.hypot(hubs[i].x! - hubs[j].x!, hubs[i].y! - hubs[j].y!));
            }
          }
          const bw = Math.max(1, maxX - minX);
          const bh = Math.max(1, maxY - minY);
          let scale = Math.min(1.15 / Math.max(bw / w, bh / h), 2.2);
          if (minHubDist < Infinity && minHubDist > 1) {
            scale = Math.max(scale, Math.min(220 / minHubDist, 2.0));
          }
          scale = Math.max(scale, 0.55);
          const tx = w / 2 - scale * ((minX + maxX) / 2);
          const ty = h / 2 - scale * ((minY + maxY) / 2);
          return d3.zoomIdentity.translate(tx, ty).scale(scale);
        }
      }

      const bounds = (gRef.current as any).getBBox();
      if (bounds.width < 1 || bounds.height < 1) return null;
      const scale = Math.min(0.9 / Math.max(bounds.width / w, bounds.height / h), 2);
      const tx = w / 2 - scale * (bounds.x + bounds.width / 2);
      const ty = h / 2 - scale * (bounds.y + bounds.height / 2);
      return d3.zoomIdentity.translate(tx, ty).scale(scale);
    };

    // Auto-fit graph to viewport after DOM renders using getBBox for accuracy
    setTimeout(() => {
      if (!svgRef.current || !zoomRef.current) return;
      const svgEl = d3.select(svgRef.current);
      const target = computeFitTransform();
      if (!target) return;
      if (entrancePlaying && !prefersReducedMotion) {
        // Instant rough fit slightly zoomed out (the settle needs room), then the hero fit
        const rough = target.scale(0.85);
        svgEl.call(zoomRef.current.transform as any, rough);
        svgEl.transition('entranceFit')
          .duration(650)
          .ease(d3.easeCubicOut)
          .call(zoomRef.current.transform as any, target);
      } else {
        svgEl.call(zoomRef.current.transform as any, target);
      }
    }, 50);

    // Entrance completion: when the visible settle ends (or a hard timeout as a
    // fallback), do one final gentle fit — unless the user has taken the camera.
    let entranceTimeout: ReturnType<typeof setTimeout> | null = null;
    if (entranceRef.current.phase === 'playing') {
      const finishEntrance = () => {
        if (entranceRef.current.phase !== 'playing') return;
        entranceRef.current.phase = 'done';
        setEntrancePhase('done');
        hasSeenGraphRef.current = true;
        saveLastView(projectId, { visualizationType, ontographLayoutType });
        if (!userInteractedRef.current && svgRef.current && zoomRef.current) {
          const target = computeFitTransform();
          if (target) {
            d3.select(svgRef.current)
              .transition('entranceFit')
              .duration(600)
              .ease(d3.easeCubicOut)
              .call(zoomRef.current.transform as any, target);
          }
        }
      };
      simulation.on('end.entranceFit', finishEntrance);
      entranceTimeout = setTimeout(finishEntrance, 6000);
    }

    // Log performance metrics
    const endTime = performance.now();
    const renderTimeMs = endTime - startTime;
    renderTime.current = renderTimeMs;
    if (GRAPH_DEBUG) {
      console.log(`[AdvancedGraphView D3] ⚡ Render completed in ${renderTimeMs.toFixed(2)}ms`);
      console.log(`[AdvancedGraphView D3] 📊 Performance: ${(filteredNodes.length / renderTimeMs * 1000).toFixed(0)} nodes/sec`);
    }
    // Cleanup
    return () => {
      simulation.stop();
      if (entranceTimeout) clearTimeout(entranceTimeout);
      if (smoothWheelRaf != null) cancelAnimationFrame(smoothWheelRaf);
    };
  // Deliberately granular deps: edit-mode toggles, click-handler identity, and label/
  // tooltip toggles update in place (refs + visual-update effect) instead of tearing
  // down and rebuilding the whole scene.
  }, [filteredNodes, filteredEdges, settings.nodeSize, settings.showArrows, settings.tooltips, allEdges, allNodes, expandedNodeIds, classDistance, datatypeDistance, isLayoutPaused, visualizationType, ontographLayoutType, graphAnalytics, colorByCluster, sizeByInfluence, isDarkTheme, prefersReducedMotion, projectId, vowlOptions]);

  // Auto-fit the viewport when switching to ontograph or changing its layout type
  // This ensures nodes are always in view after a layout recalculation
  useEffect(() => {
    if (visualizationType !== 'ontograph' || ontographLayoutType === 'spring') return;

    const doFit = () => {
      if (!svgRef.current || !gRef.current || !zoomRef.current) return false;
      const bounds = (gRef.current as any).getBBox();
      if (!bounds.width || !bounds.height) return false;
      const width = svgRef.current.clientWidth;
      const height = svgRef.current.clientHeight;
      if (!width || !height) return false;
      const scale = Math.min(0.9, 0.9 / Math.max(bounds.width / width, bounds.height / height));
      const translate = [
        width / 2 - scale * (bounds.x + bounds.width / 2),
        height / 2 - scale * (bounds.y + bounds.height / 2)
      ];
      d3.select(svgRef.current).transition().duration(prefersReducedMotion ? 0 : 600).ease(d3.easeCubicOut).call(
        zoomRef.current.transform as any,
        d3.zoomIdentity.translate(translate[0], translate[1]).scale(scale)
      );
      return true;
    };

    // Try at 350ms, retry at 800ms if SVG hasn't rendered yet (getBBox returns 0)
    const t1 = setTimeout(() => { if (!doFit()) { setTimeout(doFit, 450); } }, 350);
    return () => clearTimeout(t1);
  }, [visualizationType, ontographLayoutType, filteredNodes.length, prefersReducedMotion]);

  // Visual update effect to prevent graph movement on selection/hover
  useEffect(() => {
    if (!gRef.current) return;
    const g = d3.select(gRef.current);

    // One pass over edges instead of an allEdges.some() scan per node:
    // the opacity callback below runs for every node on every hover change,
    // which made hovering O(nodes × edges) on large graphs.
    const hoverNeighborIds = new Set<string>();
    if (hoveredNode) {
      for (const e of allEdges) {
        if (e.from === hoveredNode) hoverNeighborIds.add(e.to);
        else if (e.to === hoveredNode) hoverNeighborIds.add(e.from);
      }
    }

    // Selection focus: the selected class + everything directly attached to it
    // (subclasses, properties, Literals, Things) stays bright; the rest dims.
    // Read the RENDERED edges (post-VOWL transform) — allEdges still holds
    // pre-split ids, which left Literal/Thing clones dimmed.
    const selectionActive = selectedNodes.size > 0;
    const focusNodeIds = new Set<string>();
    const focusEdgeIds = new Set<string>();
    if (selectionActive) {
      selectedNodes.forEach(id => focusNodeIds.add(id));
      // Use RENDERED edge endpoints only (post-VOWL Literal/Thing clones).
      // Do not expand via shared cloneOf originals — that would light up every
      // Literal in the graph when one data-property neighbor is selected.
      g.selectAll('.edge-path').each((d: any) => {
        if (!d) return;
        const sourceId = typeof d.source === 'string' ? d.source : d.source?.id ?? d.from;
        const targetId = typeof d.target === 'string' ? d.target : d.target?.id ?? d.to;
        if (selectedNodes.has(sourceId) || selectedNodes.has(targetId)) {
          focusEdgeIds.add(d.id);
          if (sourceId) focusNodeIds.add(sourceId);
          if (targetId) focusNodeIds.add(targetId);
        }
      });
    }

    // Update edges
    g.selectAll('.edge-path')
      .attr('stroke-width', (d: any) => {
        const baseWidth = visualizationType === 'vowl' ? 1 : 2;
        if (selectedEdgeId === d.id) return baseWidth + 2;
        if (selectionActive && focusEdgeIds.has(d.id)) return baseWidth + 0.6;
        return baseWidth;
      })
      .attr('stroke-opacity', (d: any) => {
        if (selectedEdgeId) return selectedEdgeId === d.id ? 1 : 0.3;
        if (selectionActive) return focusEdgeIds.has(d.id) ? 1 : 0.08;
        if (hoveredNode) {
          const sourceId = typeof d.source === 'string' ? d.source : d.source.id;
          const targetId = typeof d.target === 'string' ? d.target : d.target.id;
          return (sourceId === hoveredNode || targetId === hoveredNode) ? 1 : 0.2;
        }
        if (visualizationType === 'force') return 1;
        if (d.type === 'propertyRelation') return 1;
        return visualizationType === 'vowl' ? 1 : 0.6;
      })
      .attr('marker-end', (d: any) => {
        if (visualizationType === 'vowl') {
          if (d.type === 'propertyRelation') {
            const sourceNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.from));
            const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));
            
            if (sourceNode?.type === 'annotation') return 'url(#arrow-vowl-annotation)';
            if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') return 'url(#arrow-vowl-data)';
            return 'url(#arrow-vowl-object)';
          }
          return `url(#arrow-vowl-${d.type})`;
        }
        if (settings.showArrows || d.type === 'propertyRelation') {
          return `url(#arrow-${d.type})`;
        }
        return null;
      });
      
    // Update labels — keep always-visible VOWL chips visible (same density rule
    // as the main render pass), only hover-gate the rest
    const chipAlwaysVisible = (d: any) =>
      vowlChipDensityOk && (d.type === 'propertyRelation' || d.type === 'subClassOf');
    g.selectAll('.edge-label')
      .style('opacity', (d: any) => {
        if (visualizationType === 'vowl') {
          if (chipAlwaysVisible(d)) {
            return selectionActive && !focusEdgeIds.has(d.id) ? 0.12 : 1;
          }
          return (selectedEdgeId === d.id || hoveredEdgeId === d.id) ? 1 : 0;
        }
        if (!settings.showLabels) return 0;
        return selectionActive && !focusEdgeIds.has(d.id) ? 0.12 : 1;
      });
      
    g.selectAll('.edge-label-bg')
      .attr('opacity', (d: any) => {
        if (visualizationType === 'vowl') {
          if (d.type === 'disjointWith') return 0;
          if (chipAlwaysVisible(d)) {
            return selectionActive && !focusEdgeIds.has(d.id) ? 0.12 : 1;
          }
          return (selectedEdgeId === d.id || hoveredEdgeId === d.id) ? 1 : 0;
        }
        return selectionActive && !focusEdgeIds.has(d.id) ? 0.12 : 0.85;
      });
      
    // Update nodes — layered highlights (search > selection > hover) on top of
    // each shape's remembered base styling, so per-type accents survive updates
    const searchLower = searchQuery ? searchQuery.toLowerCase() : '';
    const isSearchMatch = (d: any) => !!searchLower && (
      d.label?.toLowerCase().includes(searchLower) ||
      d.id?.toLowerCase().includes(searchLower)
    );
    g.selectAll<SVGGraphicsElement, any>('.node-shape')
      .attr('fill', function(d: any) {
        const base = this.getAttribute('data-base-fill') || this.getAttribute('fill');
        if (!selectionActive || !focusNodeIds.has(d.id) || selectedNodes.has(d.id)) {
          return base;
        }
        // Punch up pale Literals / hollow Things so the neighborhood reads as focused
        if (d.type === 'datatype') return '#f5c842';
        if (isThingIri(d.id) || d.label === 'Thing') return '#eef2ff';
        return base;
      })
      .attr('stroke', function(d: any) {
        if (isSearchMatch(d)) return '#f59e0b'; // amber highlight for search match
        if (selectedNodes.has(d.id)) return '#818cf8'; // indigo selection ring
        if (selectionActive && focusNodeIds.has(d.id)) return '#4f46e5';
        return this.getAttribute('data-base-stroke');
      })
      .attr('stroke-width', function(d: any) {
        const base = parseFloat(this.getAttribute('data-base-stroke-width') || '2');
        if (isSearchMatch(d)) return base + 2;
        if (selectedNodes.has(d.id)) return base + 1.5;
        if (selectionActive && focusNodeIds.has(d.id)) {
          if (d.type === 'datatype' || isThingIri(d.id) || d.label === 'Thing') return base + 2;
          return base + 1.25;
        }
        return base;
      })
      // style() (not attr()) so highlight glows reliably win over base shadows
      .style('filter', function(d: any) {
        if (isSearchMatch(d)) return 'url(#search-glow)';
        if (selectedNodes.has(d.id)) return 'url(#sel-glow)';
        if (selectionActive && focusNodeIds.has(d.id)) return 'url(#sel-glow)';
        if (hoveredNode === d.id) {
          return `url(#hover-glow-${ACCENT_COLORS[d.type] ? d.type : 'class'})`;
        }
        const base = this.getAttribute('data-base-filter');
        return base && base !== 'none' ? base : null;
      })
      .style('opacity', (n: any) => {
        if (searchLower) {
          return isSearchMatch(n) ? 1 : 0.2;
        }
        if (hoveredNode) {
          return n.id === hoveredNode || hoverNeighborIds.has(n.id) ? 1 : 0.3;
        }
        return 1;
      });

    // Whole node group (shape + label + badges) dims when a class is selected
    // and this node isn't in its immediate neighborhood.
    g.selectAll<SVGGElement, any>('.node')
      .style('opacity', (n: any) => {
        if (!selectionActive) return 1;
        return focusNodeIds.has(n.id) ? 1 : 0.1;
      });

    // Disjoint badge follows the same focus rule
    g.selectAll('.disjoint-symbol')
      .style('opacity', (d: any) => {
        if (!selectionActive) return 1;
        return focusEdgeIds.has(d.id) ? 1 : 0.1;
      });

  }, [selectedNodes, selectedEdgeId, hoveredEdgeId, hoveredNode, visualizationType, settings.showLabels, allEdges, searchQuery, vowlChipDensityOk]);

  // Pulse animation class on selected node groups (CSS keyframes in svg <style>).
  // Separate effect: this sweep only depends on selection, so hover moves
  // don't re-touch every node group's class list.
  useEffect(() => {
    if (!gRef.current) return;
    d3.select(gRef.current)
      .selectAll<SVGGElement, any>('.node')
      .classed('node-selected', (d: any) => selectedNodes.has(d.id));
  }, [selectedNodes, filteredNodes]);

  /**
   * ========================================================================
   * HIERARCHICAL NAVIGATION HANDLERS
   * ========================================================================
   */
  // The initial graph load caps out at maxNodes (5000) and only ships a subset of
  // edges, so a node's backend-computed `hasChildren: true` can outlive its actual
  // children being present in allNodes/allEdges — the arrow shows but expand finds
  // nothing locally. Fetch the missing subclasses on demand in that case, using the
  // STRUCTURAL (asserted-only) reasoner so this stays fast regardless of ontology size.
  const fetchMissingChildren = useCallback(async (nodeId: string): Promise<{ nodes: OntologyNode[]; edges: OntologyEdge[] }> => {
    const apiBaseUrl = (window as any).__DESKTOP_API_URL__ || (window as any).API_BASE_URL;
    const authToken = localStorage.getItem('authToken');
    try {
      const response = await fetch(
        `${apiBaseUrl}/api/ontology/${encodeURIComponent(projectId)}/reasoner/inferred-subclasses`
        + `?classIri=${encodeURIComponent(nodeId)}&direct=true&reasonerType=STRUCTURAL`,
        { headers: authHeaders(authToken) }
      );
      if (!response.ok) {
        console.warn(`[Lazy Expand] inferred-subclasses request failed (${response.status}) for`, nodeId);
        return { nodes: [], edges: [] };
      }
      const payload = await response.json();
      const subClasses: Array<{ iri: string; label?: string; hasChildren?: boolean }> = payload?.inferredSubClasses || [];
      const existingIds = new Set(allNodes.map(n => n.id));
      const newNodes: OntologyNode[] = [];
      const newEdges: OntologyEdge[] = [];
      for (const sc of subClasses) {
        if (existingIds.has(sc.iri)) continue;
        newNodes.push({
          id: sc.iri,
          label: sc.label || sc.iri,
          type: 'class',
          namespace: extractNamespace(sc.iri) || undefined
        } as OntologyNode);
        newEdges.push({
          id: `edge-lazy-${sc.iri}`,
          from: sc.iri,
          to: nodeId,
          label: 'subClassOf',
          type: 'subClassOf' as const
        });
      }
      return { nodes: newNodes, edges: newEdges };
    } catch (error) {
      console.error('[Lazy Expand] Failed to fetch children for', nodeId, error);
      return { nodes: [], edges: [] };
    }
  }, [projectId, allNodes]);

  const handleToggleExpansion = useCallback(async (nodeId: string) => {
    const nodeBefore = allNodes.find(n => n.id === nodeId);
    console.log(`[UI] User clicked to toggle expansion for: ${nodeBefore?.label || nodeId}`);
    console.log(`[UI] Current state - Visible: ${visibleNodeIds.size}, Expanded: ${expandedNodeIds.size}`);

    let effectiveNodes = allNodes;
    let effectiveEdges = allEdges;

    const isExpanding = !expandedNodeIds.has(nodeId);
    if (isExpanding
        && hasChildren(nodeId, allEdges, allNodes)
        && getChildren(nodeId, allEdges, allNodes).length === 0) {
      console.log(`[Lazy Expand] ${nodeBefore?.label || nodeId} has children per backend but none loaded locally — fetching on demand`);
      const { nodes: fetchedNodes, edges: fetchedEdges } = await fetchMissingChildren(nodeId);
      if (fetchedNodes.length > 0) {
        effectiveNodes = [...allNodes, ...fetchedNodes];
        effectiveEdges = [...allEdges, ...fetchedEdges];
        setAllNodes(effectiveNodes);
        setAllEdges(effectiveEdges);
      }
    }

    updateHierarchyState(prev => {
      const { newExpandedIds, newVisibleIds, action } = toggleExpansion(
        nodeId,
        prev.expanded,
        prev.visible,
        effectiveEdges,
        effectiveNodes
      );

      console.log(`[UI] Action: ${action}`);
      console.log(`[UI] New state - Visible: ${newVisibleIds.size}, Expanded: ${newExpandedIds.size}`);
      console.log(`[UI] Newly visible nodes: ${Array.from(newVisibleIds)
        .filter(id => !prev.visible.has(id))
        .map(id => effectiveNodes.find(n => n.id === id)?.label || id)
        .join(', ')}`);
      console.log(`[User Action] ${action} node:`, effectiveNodes.find(n => n.id === nodeId)?.label);

      return {
        visible: newVisibleIds,
        expanded: newExpandedIds
      };
    });
  }, [allNodes, allEdges, expandedNodeIds, visibleNodeIds, updateHierarchyState, fetchMissingChildren]);

  const handleExpandParents = useCallback((nodeId: string) => {
    const node = allNodes.find(n => n.id === nodeId);
    if (!node) {
      console.log('[User Action] Node not found:', nodeId);
      return;
    }

    // Get parent IRIs from edges
    const parentIds = getParents(nodeId, allEdges, allNodes);
    
    if (parentIds.length === 0) {
      console.log('[User Action] No parents to expand for:', node.label);
      return;
    }

    console.log(`[User Action] Expanding parents for:`, node.label);

    updateHierarchyState(prev => {
      const newVisibleIds = new Set(prev.visible);
      const newExpandedIds = new Set(prev.expanded);

      parentIds.forEach((parentId: string) => {
        if (!prev.visible.has(parentId)) {
          const parentNode = allNodes.find(n => n.id === parentId);
          console.log('  - Added parent:', parentNode?.label || parentId);
        }
        newVisibleIds.add(parentId);
        newExpandedIds.add(parentId);
      });

      return {
        visible: newVisibleIds,
        expanded: newExpandedIds
      };
    });
    
    // If dialog is open, update it to show the topmost parent as root
    if (showHierarchyDialog && parentIds.length > 0) {
      const topmostParent = allNodes.find(n => n.id === parentIds[0]);
      if (topmostParent) {
        setHierarchyRootNode(topmostParent);
      }
    }
  }, [allNodes, allEdges, showHierarchyDialog, updateHierarchyState]);

  const handleSearch = useCallback((query: string) => {
    if (!query) {
      // Clear search — restore full expanded view
      const { newExpandedIds, newVisibleIds } = expandAllNodes(allNodes);
      updateHierarchyState(() => ({
        visible: newVisibleIds,
        expanded: newExpandedIds
      }));
      setSearchQuery('');
      return;
    }

    const { nodesToShow, nodesToExpand } = searchNodesWithPaths(
      query,
      allNodes,
      allEdges
    );

    updateHierarchyState(() => ({
      visible: new Set(nodesToShow),
      expanded: new Set(nodesToExpand)
    }));
    setSearchQuery(query);

    console.log(`[Search] Found ${nodesToShow.size} nodes for query: "${query}"`);
  }, [allNodes, allEdges, updateHierarchyState]);

  const applyOntologyMutations = useCallback(async (ops: OntologyMutationOp[]) => {
    if (!projectId) {
      throw new Error('Missing project context for ontology mutation');
    }

    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    const token = localStorage.getItem('authToken');
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }

    const draftMode = context?.draftMode ?? false;
    const response = await fetch(`${(window as any).__DESKTOP_API_URL__ || (window as any).API_BASE_URL}/api/ontology/mutations/${projectId}?draft=${draftMode}`, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        ops,
        userId: context?.userId || 'graph-view-plugin',
        username: context?.username || 'Graph View Plugin',
        sessionId: context?.sessionId || `graph-view-${Date.now()}`
      })
    });

    if (!response.ok) {
      const payload = await response.json().catch(() => null);
      throw new Error(payload?.message || `Ontology mutation failed (${response.status})`);
    }
  }, [context, projectId]);

  const sanitizeLabelFragment = useCallback((label: string) => {
    return label
      .trim()
      .replace(/[^A-Za-z0-9]+/g, '_')
      .replace(/_+/g, '_')
      .replace(/^_+|_+$/g, '');
  }, []);

  const buildClassIri = useCallback((label: string, referenceNode?: OntologyNode | null) => {
    const fragment = sanitizeLabelFragment(label) || `Class_${Date.now()}`;
    let namespace = resolveNamespaceFromNode(referenceNode);

    if (!namespace) {
      const metadataCandidates: Array<string | null | undefined> = [
        ontologyMetadata?.defaultNamespace,
        ontologyMetadata?.baseIRI,
        ontologyMetadata?.baseIri,
        ontologyMetadata?.ontologyIRI
      ];

      for (const candidate of metadataCandidates) {
        if (!candidate) continue;
        const derived = extractNamespace(candidate) || candidate;
        if (isLikelyAbsoluteIri(derived)) {
          namespace = derived;
          break;
        }
      }
    }

    if (!namespace || !isLikelyAbsoluteIri(namespace)) {
      namespace = 'http://example.com/ontology#';
    }

    if (!namespace.endsWith('#') && !namespace.endsWith('/')) {
      namespace = `${namespace}#`;
    }

    return `${namespace}${fragment}`;
  }, [ontologyMetadata, sanitizeLabelFragment]);

  const startCreateClassAction = useCallback((type: 'child' | 'sibling', nodeId: string) => {
    if (readonly || !projectId || !canEdit || classActionLoading) {
      console.warn('[Graph Dialog] Edit action blocked');
      return;
    }

    const targetNode = allNodes.find(n => n.id === nodeId);
    if (!targetNode) {
      console.warn('[Graph Dialog] Node not found for add-class action:', nodeId);
      return;
    }

    const parentIds = getParents(nodeId, allEdges, allNodes);
    const parentId = type === 'child' ? targetNode.id : parentIds[0];
    const parentNode = parentId ? allNodes.find(n => n.id === parentId) : null;

    if (!parentNode) {
      setClassActionFeedback({ type: 'error', message: 'Unable to determine parent class for the new node.' });
      return;
    }

    setPendingClassAction({
      kind: 'create',
      relation: type,
      targetNode,
      parentNode,
      label: ''
    });
  }, [allNodes, allEdges, canEdit, classActionLoading, projectId, readonly]);

  const executeCreateClass = useCallback(async (action: Extract<PendingClassAction, { kind: 'create' }>) => {
    if (!projectId) {
      console.error('[Graph Dialog] No projectId available');
      return;
    }
    
    const parentId = action.parentNode.id;
    const parentIriForMutation = resolveNodeIri(action.parentNode);
    const newLabel = action.label.trim();
    
    console.log('[Graph Dialog] executeCreateClass called:', {
      relation: action.relation,
      targetNode: action.targetNode.label,
      parentNode: action.parentNode.label,
      newLabel,
      parentId,
      parentIriForMutation
    });
    
    if (!newLabel) {
      setClassActionFeedback({ type: 'error', message: 'Class name cannot be empty.' });
      return;
    }

    if (!parentIriForMutation) {
      console.warn('[Graph Dialog] Parent IRI could not be resolved, opening main editor dialog');
      requestHostClassDialog(action.relation, action.targetNode, action.parentNode);
      setPendingClassAction(null);
      return;
    }

    try {
      setClassActionLoading(true);
      setClassActionFeedback(null);
      const newIri = buildClassIri(newLabel, action.parentNode || action.targetNode);
      
      console.log('[Graph Dialog] Sending createClass mutation to backend:', {
        type: 'createClass',
        iri: newIri,
        label: newLabel,
        parent: parentIriForMutation
      });

      const apiBaseUrl = (window as any).__DESKTOP_API_URL__ || (window as any).API_BASE_URL;
      const authToken = localStorage.getItem('authToken');
      const draftMode = context?.draftMode ?? false;
      
      const response = await fetch(`${apiBaseUrl}/api/ontology/mutations/${projectId}?draft=${draftMode}`, {
        method: 'POST',
        headers: authHeaders(authToken),
        body: JSON.stringify({
          ops: [{
            type: 'createClass',
            iri: newIri,
            label: newLabel,
            parent: parentIriForMutation
          }],
          userId: context?.userId || 'graph-view-plugin',
          username: context?.username || 'Graph View Plugin',
          sessionId: context?.sessionId || `graph-view-${Date.now()}`
        })
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => null);
        throw new Error(errorData?.message || `Failed to create class (${response.status})`);
      }

      const result = await response.json();
      console.log('[Graph Dialog] Backend response:', result);

      const newNode: OntologyNode = {
        id: newIri,
        label: newLabel,
        type: 'class',
        namespace: extractNamespace(newIri) || extractNamespace(action.parentNode.id) || undefined,
        metadata: { createdBy: 'graph-view-plugin' }
      };

      const newEdge = {
        id: `edge-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        from: newIri,
        to: parentId,
        label: 'subClassOf',
        type: 'subClassOf' as const
      };

      console.log('[Graph Dialog] Adding new node and edge to local state:', {
        node: newNode,
        edge: newEdge
      });

      // Update nodes and edges
      setAllNodes(prev => {
        const updated = [...prev, newNode];
        console.log('[Graph Dialog] Updated allNodes count:', updated.length);
        return updated;
      });
      
      setAllEdges(prev => {
        const updated = [...prev, newEdge];
        console.log('[Graph Dialog] Updated allEdges count:', updated.length);
        return updated;
      });

      // Update hierarchy state - make parent expanded and new node visible
      updateHierarchyState(prev => {
        const visible = new Set(prev.visible);
        const expanded = new Set(prev.expanded);
        visible.add(newIri);
        visible.add(parentId); // Ensure parent is also visible
        expanded.add(parentId); // Expand parent to show new child
        
        console.log('[Graph Dialog] Updated hierarchy state:', {
          visibleCount: visible.size,
          expandedCount: expanded.size,
          newNodeVisible: visible.has(newIri),
          parentExpanded: expanded.has(parentId)
        });
        
        return { visible, expanded };
      });

      // Select the new node
      setSelectedNodes(new Set([newIri]));
      setSelectedNodeInfo(newNode);
      
      // Show success feedback
      setClassActionFeedback({ type: 'success', message: `✓ Created class "${newLabel}"` });
      setPendingClassAction(null);
      
      // Notify the host (Dashboard) that a class was created so it can update its hierarchy
      window.dispatchEvent(new CustomEvent('graph-view:class-created', {
        detail: {
          id: newIri,
          label: newLabel,
          parentId: parentId,
          projectId
        }
      }));
      
      console.log('[Graph Dialog] Class creation completed successfully');
    } catch (actionError) {
      const message = actionError instanceof Error ? actionError.message : 'Failed to create class';
      setClassActionFeedback({ type: 'error', message });
      console.error('[Graph Dialog] Create class failed:', actionError);
    } finally {
      setClassActionLoading(false);
    }
  }, [buildClassIri, context, projectId, requestHostClassDialog, updateHierarchyState]);

  const startDeleteClassAction = useCallback((nodeId: string) => {
    if (readonly || !projectId || !canEdit || classActionLoading) {
      console.warn('[Graph Dialog] Delete action blocked');
      return;
    }

    const node = allNodes.find(n => n.id === nodeId);
    if (!node) {
      console.warn('[Graph Dialog] Node not found for delete action:', nodeId);
      return;
    }

    const childCount = getChildren(nodeId, allEdges, allNodes).length;
    setPendingClassAction({ kind: 'delete', targetNode: node, childCount });
  }, [allNodes, allEdges, canEdit, classActionLoading, projectId, readonly]);

  const executeDeleteClass = useCallback(async (action: Extract<PendingClassAction, { kind: 'delete' }>) => {
    const nodeId = action.targetNode.id;
    const nodeIriForMutation = resolveNodeIri(action.targetNode);
    const parentIds = getParents(nodeId, allEdges, allNodes);
    const fallbackRoot = parentIds.length > 0 ? allNodes.find(n => n.id === parentIds[0]) || null : null;
    const shouldCloseDialog = hierarchyRootNode?.id === nodeId && !fallbackRoot;

    if (!nodeIriForMutation) {
      requestHostDeleteDialog(action.targetNode);
      setPendingClassAction(null);
      return;
    }

    try {
      setClassActionLoading(true);
      setClassActionFeedback(null);

      await applyOntologyMutations([{ type: 'deleteClass', iri: nodeIriForMutation }]);

      setAllNodes(prev => prev.filter(n => n.id !== nodeId));
      setAllEdges(prev => prev.filter(e => e.from !== nodeId && e.to !== nodeId));

      updateHierarchyState(prev => {
        const visible = new Set(prev.visible);
        const expanded = new Set(prev.expanded);
        visible.delete(nodeId);
        expanded.delete(nodeId);
        return { visible, expanded };
      });

      setSelectedNodes(prev => {
        if (!prev.has(nodeId)) {
          return prev;
        }
        const updated = new Set(prev);
        updated.delete(nodeId);
        return updated;
      });

      setSelectedNodeInfo(info => (info?.id === nodeId ? null : info));
      setHierarchyRootNode(current => (current?.id === nodeId ? (fallbackRoot || null) : current));
      if (shouldCloseDialog) {
        setShowHierarchyDialog(false);
      }

      setClassActionFeedback({ type: 'success', message: `Deleted class "${action.targetNode.label}"` });
      setPendingClassAction(null);
    } catch (actionError) {
      const message = actionError instanceof Error ? actionError.message : 'Failed to delete class';
      setClassActionFeedback({ type: 'error', message });
      console.error('[Graph Dialog] Delete class failed', actionError);
    } finally {
      setClassActionLoading(false);
    }
  }, [allEdges, allNodes, applyOntologyMutations, hierarchyRootNode, requestHostDeleteDialog, updateHierarchyState]);

  const handleConfirmPendingAction = useCallback(async () => {
    if (!pendingClassAction) return;
    if (pendingClassAction.kind === 'create') {
      await executeCreateClass(pendingClassAction);
    } else {
      await executeDeleteClass(pendingClassAction);
    }
  }, [executeCreateClass, executeDeleteClass, pendingClassAction]);

  /**
   * ========================================================================
   * CONTROL FUNCTIONS
   * ========================================================================
   */
  // Every programmatic camera move shares one duration and easing (instant under reduced motion)
  const cameraTransition = (svg: d3.Selection<SVGSVGElement, unknown, null, undefined>, ms: number = CAMERA_MS) =>
    svg.transition().duration(prefersReducedMotion ? 0 : ms).ease(d3.easeCubicOut);

  const handleZoomIn = () => {
    if (svgRef.current && zoomRef.current) {
      const svg = d3.select(svgRef.current);
      cameraTransition(svg, 300).call(
        zoomRef.current.scaleBy as any, 1.3
      );
    }
  };

  const handleZoomOut = () => {
    if (svgRef.current && zoomRef.current) {
      const svg = d3.select(svgRef.current);
      cameraTransition(svg, 300).call(
        zoomRef.current.scaleBy as any, 0.7
      );
    }
  };

  /**
   * Fit camera to the graph. In VOWL mode we MUST NOT fit the full SVG bbox:
   * spreading hubs farther just zooms out more (screen gap stays constant). Instead
   * fit around pinned class hubs and enforce a minimum on-screen hub gap.
   */
  const computeSmartFitTransform = useCallback((): d3.ZoomTransform | null => {
    if (!svgRef.current || !gRef.current) return null;
    const w = svgRef.current.clientWidth;
    const h = svgRef.current.clientHeight;
    if (!w || !h) return null;

    const pinned = vowlPinnedHubIdsRef.current;
    const simNodes = simulationRef.current?.nodes() as D3Node[] | undefined;

    if (visualizationType === 'vowl' && pinned.size >= 2 && simNodes) {
      let hubs = simNodes.filter(
        n => pinned.has(n.id) && n.x != null && n.y != null && Number.isFinite(n.x) && Number.isFinite(n.y)
      );
      if (hubs.length >= 2) {
        // VOWL shows a local neighborhood clearly — when we have many hubs,
        // fit around the densest few so nodes stay readable (not a zoomed-out hairball).
        if (hubs.length > 6) {
          const deg = (id: string) => {
            let c = 0;
            for (const e of filteredEdges) {
              if (e.from === id || e.to === id) c++;
            }
            return c;
          };
          hubs = [...hubs].sort((a, b) => deg(b.id) - deg(a.id)).slice(0, 5);
        }
        let minX = Infinity;
        let minY = Infinity;
        let maxX = -Infinity;
        let maxY = -Infinity;
        const localR = 220;
        for (const hub of hubs) {
          minX = Math.min(minX, hub.x! - localR);
          minY = Math.min(minY, hub.y! - localR);
          maxX = Math.max(maxX, hub.x! + localR);
          maxY = Math.max(maxY, hub.y! + localR);
        }
        // Include nearby members (Literal fans) so the fit isn't hub-circles-only
        for (const n of simNodes) {
          if ((n as any).__isChip || n.x == null || n.y == null) continue;
          for (const hub of hubs) {
            if (Math.hypot(n.x - hub.x!, n.y - hub.y!) <= localR) {
              minX = Math.min(minX, n.x - 36);
              minY = Math.min(minY, n.y - 36);
              maxX = Math.max(maxX, n.x + 36);
              maxY = Math.max(maxY, n.y + 36);
              break;
            }
          }
        }

        let minHubDist = Infinity;
        for (let i = 0; i < hubs.length; i++) {
          for (let j = i + 1; j < hubs.length; j++) {
            minHubDist = Math.min(
              minHubDist,
              Math.hypot(hubs[i].x! - hubs[j].x!, hubs[i].y! - hubs[j].y!)
            );
          }
        }

        const bw = Math.max(1, maxX - minX);
        const bh = Math.max(1, maxY - minY);
        // Prefer a closer camera so class labels stay readable like VOWL
        let scale = Math.min(1.15 / Math.max(bw / w, bh / h), 2.2);
        if (minHubDist < Infinity && minHubDist > 1) {
          scale = Math.max(scale, Math.min(220 / minHubDist, 2.0));
        }
        // Never zoom out so far that a class circle is < ~28px on screen
        scale = Math.max(scale, 0.55);

        const tx = w / 2 - scale * ((minX + maxX) / 2);
        const ty = h / 2 - scale * ((minY + maxY) / 2);
        return d3.zoomIdentity.translate(tx, ty).scale(scale);
      }
    }

    const bounds = (gRef.current as any).getBBox();
    if (!bounds || bounds.width < 1 || bounds.height < 1) return null;
    const scale = Math.min(0.9 / Math.max(bounds.width / w, bounds.height / h), 2);
    const tx = w / 2 - scale * (bounds.x + bounds.width / 2);
    const ty = h / 2 - scale * (bounds.y + bounds.height / 2);
    return d3.zoomIdentity.translate(tx, ty).scale(scale);
  }, [visualizationType, filteredEdges]);

  const handleFit = () => {
    if (svgRef.current && zoomRef.current) {
      const target = computeSmartFitTransform();
      if (!target) return;
      const svg = d3.select(svgRef.current);
      cameraTransition(svg).call(zoomRef.current.transform as any, target);
    }
  };

  const applySavedZoomTransform = useCallback((transform: { x: number; y: number; k: number }) => {
    if (!svgRef.current || !zoomRef.current) return;
    const svg = d3.select(svgRef.current);
    const zoomTransform = d3.zoomIdentity.translate(transform.x, transform.y).scale(transform.k);
    svg.transition().duration(prefersReducedMotion ? 0 : CAMERA_MS).ease(d3.easeCubicOut)
      .call(zoomRef.current.transform as any, zoomTransform);
  }, [prefersReducedMotion]);

  const handleSaveCurrentView = useCallback(() => {
    const defaultName = `${visualizationType}${visualizationType === 'ontograph' ? ` / ${ontographLayoutType}` : ''}`;
    setSaveViewNameDraft(defaultName);
    setShowSaveViewPrompt(true);
  }, [ontographLayoutType, visualizationType]);

  const confirmSaveCurrentView = useCallback((rawName: string) => {
    const name = rawName.trim();
    if (!name) return;

    const transform = currentTransformRef.current;
    const view: SavedGraphView = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      name,
      createdAt: new Date().toISOString(),
      visualizationType,
      ontographLayoutType,
      zoomTransform: { x: transform.x, y: transform.y, k: transform.k },
      hierarchy: {
        visible: Array.from(visibleNodeIds),
        expanded: Array.from(expandedNodeIds)
      },
      selectedNodeIds: Array.from(selectedNodes),
      filters: {
        nodeTypes: Array.from(filters.nodeTypes),
        edgeTypes: Array.from(filters.edgeTypes),
        searchQuery: filters.searchQuery,
        namespaceFilter: filters.namespaceFilter,
        contextFilter: filters.contextFilter
      },
      vowlFilters,
      settings: {
        showLabels: settings.showLabels,
        showArrows: settings.showArrows,
        physics: settings.physics,
        nodeSize: settings.nodeSize,
        edgeWidth: settings.edgeWidth
      },
      focusedNodeId,
      nodePositions: Array.from(nodePositionsRef.current.entries())
    };

    const nextViews = [view, ...savedViews.filter(existing => existing.name !== view.name)].slice(0, 20);
    persistSavedViews(nextViews);
    setSelectedSavedViewId(view.id);
    setShowSaveViewPrompt(false);
  }, [
    expandedNodeIds,
    filters,
    focusedNodeId,
    ontographLayoutType,
    persistSavedViews,
    savedViews,
    selectedNodes,
    settings.edgeWidth,
    settings.nodeSize,
    settings.physics,
    settings.showArrows,
    settings.showLabels,
    vowlFilters,
    visibleNodeIds,
    visualizationType
  ]);

  const handleLoadSavedView = useCallback((viewId: string) => {
    const view = savedViews.find(item => item.id === viewId);
    if (!view) return;

    setSelectedSavedViewId(view.id);
    setVisualizationType(view.visualizationType);
    setOntographLayoutType(view.ontographLayoutType || 'vertical');
    setFilters(prev => ({
      ...prev,
      nodeTypes: new Set(view.filters.nodeTypes),
      edgeTypes: new Set(view.filters.edgeTypes),
      searchQuery: view.filters.searchQuery,
      namespaceFilter: view.filters.namespaceFilter,
      contextFilter: view.filters.contextFilter
    }));
    setVowlFilters(view.vowlFilters);
    setSettings(prev => ({ ...prev, ...view.settings }));
    setSearchQuery(view.filters.searchQuery || '');
    setSelectedNodes(new Set(view.selectedNodeIds));
    setFocusedNodeId(view.focusedNodeId);
    nodePositionsRef.current = new Map(view.nodePositions || []);
    updateHierarchyState(() => ({
      visible: new Set(view.hierarchy.visible),
      expanded: new Set(view.hierarchy.expanded)
    }));

    window.setTimeout(() => applySavedZoomTransform(view.zoomTransform), 150);
  }, [applySavedZoomTransform, savedViews, updateHierarchyState]);

  const handleDeleteSavedView = useCallback((viewId: string) => {
    if (!viewId) return;
    const nextViews = savedViews.filter(view => view.id !== viewId);
    persistSavedViews(nextViews);
    setSelectedSavedViewId('');
  }, [persistSavedViews, savedViews]);

  const handleExport = (format: ExportFormat) => {
    // Exports serialize the live DOM — make sure viewport culling never truncates them
    uncullForExportRef.current();
    if (format === 'svg' && svgRef.current) {
      const svgData = new XMLSerializer().serializeToString(svgRef.current);
      const blob = new Blob([svgData], { type: 'image/svg+xml' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `ontology-graph-${projectId}.svg`;
      link.click();
      URL.revokeObjectURL(url);
    } else if (format === 'png' && svgRef.current) {
      try {
        const svgElement = svgRef.current;
        
        // Get SVG dimensions
        const bbox = svgElement.getBBox();
        const width = Math.max(bbox.width + 40, 800);
        const height = Math.max(bbox.height + 40, 600);
        
        // Clone SVG and set explicit dimensions
        const clonedSvg = svgElement.cloneNode(true) as SVGSVGElement;
        clonedSvg.setAttribute('width', width.toString());
        clonedSvg.setAttribute('height', height.toString());
        clonedSvg.setAttribute('viewBox', `${bbox.x - 20} ${bbox.y - 20} ${width} ${height}`);
        
        // Serialize SVG
        const svgData = new XMLSerializer().serializeToString(clonedSvg);
        
        // Create canvas
        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext('2d');
        
        if (!ctx) {
          console.error('Failed to get canvas context');
          return;
        }
        
        // Create image
        const img = new Image();
        
        img.onload = () => {
          // Fill white background
          ctx.fillStyle = '#ffffff';
          ctx.fillRect(0, 0, width, height);
          
          // Draw SVG
          ctx.drawImage(img, 0, 0);
          
          // Convert to PNG and download
          canvas.toBlob(blob => {
            if (blob) {
              const url = URL.createObjectURL(blob);
              const link = document.createElement('a');
              link.href = url;
              link.download = `ontology-graph-${projectId}.png`;
              link.click();
              URL.revokeObjectURL(url);
            }
          }, 'image/png');
        };
        
        img.onerror = (e) => {
          console.error('Failed to load SVG image for PNG export:', e);
        };
        
        // Encode SVG data properly (handle Unicode)
        const svgBlob = new Blob([svgData], { type: 'image/svg+xml;charset=utf-8' });
        const url = URL.createObjectURL(svgBlob);
        img.src = url;
        
        // Clean up after image loads
        img.onload = () => {
          URL.revokeObjectURL(url);
          
          // Fill white background
          ctx.fillStyle = '#ffffff';
          ctx.fillRect(0, 0, width, height);
          
          // Draw SVG
          ctx.drawImage(img, 0, 0);
          
          // Convert to PNG and download
          canvas.toBlob(blob => {
            if (blob) {
              const pngUrl = URL.createObjectURL(blob);
              const link = document.createElement('a');
              link.href = pngUrl;
              link.download = `ontology-graph-${projectId}.png`;
              link.click();
              URL.revokeObjectURL(pngUrl);
            }
          }, 'image/png');
        };
      } catch (error) {
        console.error('PNG export failed:', error);
      }
    }
    // Serialization is synchronous in both branches (PNG captures svgData up front),
    // so it's safe to restore viewport culling immediately.
    applyViewportCullingRef.current();
  };

  const togglePhysics = () => {
    setSettings(prev => ({ ...prev, physics: !prev.physics }));
    if (simulationRef.current) {
      if (settings.physics) {
        simulationRef.current.stop();
      } else {
        simulationRef.current.alphaTarget(0.3).restart();
      }
    }
  };

  // Load data on mount and when projectId changes
  useEffect(() => {
    // Guard: Don't fetch if no projectId
    if (!projectId) {
      console.warn('[AdvancedGraphView] No projectId, skipping initial fetch');
      return;
    }
    
    // Clear existing data when projectId changes to prevent showing stale data
    setAllNodes([]);
    setAllEdges([]);
    updateHierarchyState(() => ({
      visible: new Set<string>(),
      expanded: new Set<string>()
    }));
    
    // Fetch new data for the current project
    console.log('[AdvancedGraphView] 🚀 Initial mount or projectId changed, fetching graph data for:', projectId);
    fetchGraphData();
  }, [projectId]); // Remove fetchGraphData from dependencies to avoid stale closure issues

  // Refetch (bypassing the sessionStorage cache) when classes are created/deleted
  // elsewhere in the app — the entity editor dispatches these window events.
  useEffect(() => {
    if (!projectId) return;
    let refetchQueued = false;
    const handleMutation = () => {
      if (refetchQueued) return;
      refetchQueued = true;
      // Small delay so rapid successive mutations coalesce into one refetch.
      setTimeout(() => {
        refetchQueued = false;
        console.log('[AdvancedGraphView] 🔄 Ontology mutated elsewhere — refetching graph data');
        fetchGraphData({ bypassCache: true });
      }, 500);
    };
    window.addEventListener('graph-view:class-created', handleMutation as EventListener);
    window.addEventListener('graph-view:class-deleted', handleMutation as EventListener);
    window.addEventListener('ontology:mutated', handleMutation as EventListener);
    return () => {
      window.removeEventListener('graph-view:class-created', handleMutation as EventListener);
      window.removeEventListener('graph-view:class-deleted', handleMutation as EventListener);
      window.removeEventListener('ontology:mutated', handleMutation as EventListener);
    };
  }, [projectId, fetchGraphData]);

  useEffect(() => {
    if (!projectId) return;
    let cancelled = false;

    const loadMetadata = async () => {
      try {
        const token = localStorage.getItem('authToken');
        const headers: Record<string, string> = { 'Content-Type': 'application/json' };
        if (token) {
          headers['Authorization'] = `Bearer ${token}`;
        }
        const response = await fetch(`${(window as any).__DESKTOP_API_URL__ || (window as any).API_BASE_URL}/api/ontology/metadata/${projectId}`, {
          method: 'GET',
          headers
        });
        if (!response.ok) {
          throw new Error(`Failed to fetch ontology metadata (${response.status})`);
        }
        const payload = await response.json();
        if (!cancelled) {
          setOntologyMetadata(payload?.data || payload);
        }
      } catch (metadataError) {
        console.error('[AdvancedGraphView] Metadata fetch failed', metadataError);
      }
    };

    loadMetadata();
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  useEffect(() => {
    if (!classActionFeedback) return;
    const timer = setTimeout(() => setClassActionFeedback(null), 5000);
    return () => clearTimeout(timer);
  }, [classActionFeedback]);

  // Close context menu on outside click
  useEffect(() => {
    const handleClickOutside = () => {
      if (contextMenu.visible) {
        setContextMenu({ ...contextMenu, visible: false });
      }
    };

    document.addEventListener('click', handleClickOutside);
    return () => document.removeEventListener('click', handleClickOutside);
  }, [contextMenu]);

  /**
   * ========================================================================
   * HIERARCHY DIALOG HELPERS
   * ========================================================================
   */
  const handleDialogExpand = useCallback((nodeId: string) => {
    const label = allNodes.find(n => n.id === nodeId)?.label || nodeId;
    console.log(`[Dialog] User clicked to expand/collapse node:`, label);
    console.log(`[Dialog] Node currently visible in graph?`, visibleNodeIds.has(nodeId));

    updateHierarchyState(prev => {
      const isCurrentlyExpanded = prev.expanded.has(nodeId);
      const isVisible = prev.visible.has(nodeId);

      if (isCurrentlyExpanded) {
        console.log('[Dialog] Node already expanded, collapsing');
        const { newExpandedIds, newVisibleIds } = toggleExpansion(
          nodeId,
          prev.expanded,
          prev.visible,
          allEdges,
          allNodes
        );

        return {
          visible: newVisibleIds,
          expanded: newExpandedIds
        };
      }

      if (isVisible) {
        console.log('[Dialog] Node is visible, expanding via standard toggle');
        const { newExpandedIds, newVisibleIds } = toggleExpansion(
          nodeId,
          prev.expanded,
          prev.visible,
          allEdges,
          allNodes
        );

        return {
          visible: newVisibleIds,
          expanded: newExpandedIds
        };
      }

      console.log(`[Dialog] Node is NOT visible in graph yet. Need to expand parent path.`);
      const path = findPathToNode(nodeId, allEdges, allNodes);
      console.log(`[Dialog] Path to node:`, path.map(id => allNodes.find(n => n.id === id)?.label));

      const newVisibleIds = new Set(prev.visible);
      const newExpandedIds = new Set(prev.expanded);

      for (let i = 0; i < path.length - 1; i++) {
        const currentNodeId = path[i];
        if (!newExpandedIds.has(currentNodeId)) {
          const children = getChildren(currentNodeId, allEdges, allNodes);
          children.forEach(childId => newVisibleIds.add(childId));
          newExpandedIds.add(currentNodeId);
          console.log(`[Dialog] Auto-expanded ancestor:`, allNodes.find(n => n.id === currentNodeId)?.label);
        }
      }

      const children = getChildren(nodeId, allEdges, allNodes);
      children.forEach(childId => newVisibleIds.add(childId));
      newExpandedIds.add(nodeId);

      console.log(`[Dialog] Expanded target node and ancestors. New visible count:`, newVisibleIds.size);
      return {
        visible: newVisibleIds,
        expanded: newExpandedIds
      };
    });
  }, [allNodes, allEdges, updateHierarchyState, visibleNodeIds]);

  // Memoize node children and parents map for better performance
  const nodeRelationsMap = useMemo(() => {
    const relations = new Map<string, { children: string[]; parents: string[]; hasChildren: boolean; hasParents: boolean }>();
    
    allNodes.forEach(node => {
      const childIds = getChildren(node.id, allEdges, allNodes);
      const parentIds = getParents(node.id, allEdges, allNodes);
      
      relations.set(node.id, {
        children: childIds,
        parents: parentIds,
        hasChildren: childIds.length > 0,
        hasParents: parentIds.length > 0
      });
    });
    
    return relations;
  }, [allNodes, allEdges]);

  const renderHierarchyTree = useCallback((node: OntologyNode, level: number = 0): JSX.Element => {
    const relations = nodeRelationsMap.get(node.id) || { children: [], parents: [], hasChildren: false, hasParents: false };
    const children = relations.children.map(id => allNodes.find(n => n.id === id)).filter(Boolean) as OntologyNode[];
    const isExpanded = expandedNodeIds.has(node.id);
    const { hasChildren: hasChildNodes, hasParents } = relations;
    const parentIds = getParents(node.id, allEdges, allNodes);
    const canAddChild = canEdit && !classActionLoading;
    const canAddSibling = canEdit && parentIds.length > 0 && !classActionLoading;
    const canDeleteNode = canEdit && !classActionLoading;

    return (
      <div 
        key={node.id} 
        style={{ 
          marginLeft: level > 0 ? '16px' : '0',
          borderLeft: level > 0 ? '2px solid var(--border, #e5e7eb)' : 'none',
          paddingLeft: level > 0 ? '8px' : '0',
          marginTop: '2px'
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            padding: '8px 10px',
            cursor: 'pointer',
            borderRadius: '6px',
            backgroundColor: selectedNodes.has(node.id) ? 'var(--accent-tint)' : 'var(--surface-1)',
            border: selectedNodes.has(node.id) ? '1px solid var(--accent)' : '1px solid transparent',
            transition: 'all 0.2s ease',
            marginBottom: '2px',
            boxShadow: selectedNodes.has(node.id) ? '0 1px 3px var(--accent-tint)' : 'none'
          }}
          onMouseEnter={(e) => {
            if (!selectedNodes.has(node.id)) {
              e.currentTarget.style.backgroundColor = 'var(--surface-2)';
              e.currentTarget.style.borderColor = 'var(--border)';
            }
          }}
          onMouseLeave={(e) => {
            if (!selectedNodes.has(node.id)) {
              e.currentTarget.style.backgroundColor = 'var(--surface-1)';
              e.currentTarget.style.borderColor = 'transparent';
            }
          }}
        >
          {/* Expand/Collapse Icon */}
          <div style={{ width: '24px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            {hasChildNodes ? (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  handleDialogExpand(node.id);
                }}
                style={{
                  cursor: 'pointer',
                  color: '#667eea',
                  backgroundColor: 'transparent',
                  border: 'none',
                  borderRadius: '4px',
                  width: '20px',
                  height: '20px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  padding: 0,
                  transition: 'all 0.2s ease'
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.backgroundColor = '#eef2ff';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.backgroundColor = 'transparent';
                }}
                title={isExpanded ? 'Collapse children' : 'Expand children'}
              >
                {isExpanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
              </button>
            ) : (
              <span style={{ width: '20px' }} />
            )}
          </div>
          
          {/* Node Type Icon & Label */}
          <div
            style={{
              flex: 1,
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
              overflow: 'hidden'
            }}
            onClick={() => {
              setSelectedNodes(new Set([node.id]));
              setSelectedNodeInfo(node);
            }}
          >
            {/* Type Indicator Circle */}
            <div
              style={{
                width: '8px',
                height: '8px',
                borderRadius: '50%',
                backgroundColor: TYPE_COLORS[node.type],
                flexShrink: 0
              }}
              title={node.type}
            />
            
            <span
              style={{
                fontSize: '13px',
                fontWeight: selectedNodes.has(node.id) ? '600' : '400',
                // Theme variable, not a hardcoded dark hex — rows sit on var(--surface-1),
                // which is dark in dark mode (hardcoded #1f2937 made labels invisible there)
                color: 'var(--text-primary, #1f2937)',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap'
              }}
            >
              {node.label}
            </span>
          </div>
          
          {/* Type Badge */}
          <span
            style={{
              fontSize: '10px',
              fontWeight: '600',
              color: TYPE_COLORS[node.type],
              padding: '3px 8px',
              backgroundColor: TYPE_COLORS[node.type] + '15',
              border: `1px solid ${TYPE_COLORS[node.type]}40`,
              borderRadius: '12px',
              textTransform: 'uppercase',
              letterSpacing: '0.5px',
              flexShrink: 0
            }}
          >
            {node.type === 'objectProperty' ? 'Obj' : 
             node.type === 'dataProperty' ? 'Data' :
             node.type === 'class' ? 'Class' :
             node.type === 'individual' ? 'Ind' :
             node.type.substring(0, 4)}
          </span>
          
          {/* Action Buttons */}
          {canEdit && (
            <div style={{ display: 'flex', gap: '4px', marginLeft: '6px', opacity: 0.7 }}
              onMouseEnter={(e) => e.currentTarget.style.opacity = '1'}
              onMouseLeave={(e) => e.currentTarget.style.opacity = '0.7'}
            >
              {hasParents && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    handleExpandParents(node.id);
                  }}
                  style={{
                    border: '1px solid #c7d2fe',
                    backgroundColor: '#eef2ff',
                    color: '#667eea',
                    borderRadius: '4px',
                    padding: '3px',
                    width: '24px',
                    height: '24px',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    cursor: 'pointer',
                    transition: 'all 0.2s ease'
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.backgroundColor = '#e0e7ff';
                    e.currentTarget.style.borderColor = '#a5b4fc';
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.backgroundColor = '#eef2ff';
                    e.currentTarget.style.borderColor = '#c7d2fe';
                  }}
                  title="Show parents in graph"
                >
                  <ChevronUp size={14} />
                </button>
              )}
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  startCreateClassAction('child', node.id);
                }}
                disabled={!canAddChild}
                style={{
                  border: `1px solid ${canAddChild ? '#c7d2fe' : '#e5e7eb'}`,
                  backgroundColor: canAddChild ? '#eef2ff' : '#f9fafb',
                  color: canAddChild ? '#667eea' : '#9ca3af',
                  borderRadius: '4px',
                  padding: '3px',
                  width: '24px',
                  height: '24px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  cursor: canAddChild ? 'pointer' : 'not-allowed',
                  transition: 'all 0.2s ease'
                }}
                onMouseEnter={(e) => {
                  if (canAddChild) {
                    e.currentTarget.style.backgroundColor = '#e0e7ff';
                    e.currentTarget.style.borderColor = '#a5b4fc';
                  }
                }}
                onMouseLeave={(e) => {
                  if (canAddChild) {
                    e.currentTarget.style.backgroundColor = '#eef2ff';
                    e.currentTarget.style.borderColor = '#c7d2fe';
                  }
                }}
                title={canAddChild ? 'Add child class' : 'Action disabled'}
              >
                <Plus size={14} />
              </button>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  startCreateClassAction('sibling', node.id);
                }}
                disabled={!canAddSibling}
                style={{
                  border: `1px solid ${canAddSibling ? '#a7f3d0' : '#e5e7eb'}`,
                  backgroundColor: canAddSibling ? '#ecfdf5' : '#f9fafb',
                  color: canAddSibling ? '#10b981' : '#9ca3af',
                  borderRadius: '4px',
                  padding: '3px',
                  width: '24px',
                  height: '24px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  cursor: canAddSibling ? 'pointer' : 'not-allowed',
                  transition: 'all 0.2s ease'
                }}
                onMouseEnter={(e) => {
                  if (canAddSibling) {
                    e.currentTarget.style.backgroundColor = '#d1fae5';
                    e.currentTarget.style.borderColor = '#6ee7b7';
                  }
                }}
                onMouseLeave={(e) => {
                  if (canAddSibling) {
                    e.currentTarget.style.backgroundColor = '#ecfdf5';
                    e.currentTarget.style.borderColor = '#a7f3d0';
                  }
                }}
                title={canAddSibling ? 'Add sibling class' : 'Sibling requires a parent'}
              >
                <GitBranch size={14} />
              </button>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  startDeleteClassAction(node.id);
                }}
                disabled={!canDeleteNode}
                style={{
                  border: `1px solid ${canDeleteNode ? '#fecaca' : '#e5e7eb'}`,
                  backgroundColor: canDeleteNode ? '#fef2f2' : '#f9fafb',
                  color: canDeleteNode ? '#ef4444' : '#9ca3af',
                  borderRadius: '4px',
                  padding: '3px',
                  width: '24px',
                  height: '24px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  cursor: canDeleteNode ? 'pointer' : 'not-allowed',
                  transition: 'all 0.2s ease'
                }}
                onMouseEnter={(e) => {
                  if (canDeleteNode) {
                    e.currentTarget.style.backgroundColor = '#fee2e2';
                    e.currentTarget.style.borderColor = '#fca5a5';
                  }
                }}
                onMouseLeave={(e) => {
                  if (canDeleteNode) {
                    e.currentTarget.style.backgroundColor = '#fef2f2';
                    e.currentTarget.style.borderColor = '#fecaca';
                  }
                }}
                title={canDeleteNode ? 'Delete class' : 'Action disabled'}
              >
                <Trash2 size={14} />
              </button>
            </div>
          )}
        </div>
        
        {/* Expanded Children with Smooth Animation */}
        {isExpanded && hasChildNodes && (
          <div
            style={{
              overflow: 'hidden',
              animation: 'slideDown 0.3s ease-out',
              marginTop: '4px'
            }}
          >
            {children.map(child => renderHierarchyTree(child, level + 1))}
          </div>
        )}
      </div>
    );
  }, [nodeRelationsMap, allNodes, allEdges, expandedNodeIds, selectedNodes, handleExpandParents, handleDialogExpand, startCreateClassAction, startDeleteClassAction, canEdit, classActionLoading]);

  // Shared navigator body — the same content (root node info, tree, action
  // feedback, footer legend) rendered both inline in the sidebar and inside the
  // floating "pop out" dialog, so the two never drift apart.
  const hierarchyNavigatorBody = useMemo(() => {
    if (!hierarchyRootNode) return null;
    return (
      <>
        {/* Root Node Info */}
        <div
          style={{
            padding: '8px 12px',
            backgroundColor: 'var(--surface-2, #f9fafb)',
            borderBottom: '1px solid var(--border, #e5e7eb)'
          }}
        >
          <div style={{ fontSize: '13px', fontWeight: '600', color: 'var(--text-primary, #1f2937)', marginBottom: '2px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {hierarchyRootNode.label}
          </div>
          <div style={{ fontSize: '10px', color: 'var(--text-secondary, #9ca3af)', fontFamily: 'monospace', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {hierarchyRootNode.id}
          </div>
        </div>

        {/* Hierarchy Tree */}
        <div
          style={{
            flex: 1,
            overflow: 'auto',
            padding: '8px 10px',
            backgroundColor: 'var(--surface-2, #fafafa)'
          }}
        >
          <div style={{ backgroundColor: 'var(--surface-1, #ffffff)', borderRadius: '6px', padding: '4px' }}>
            {renderHierarchyTree(hierarchyRootNode)}
          </div>
        </div>

        {classActionFeedback && (
          <div style={{ padding: '0 16px 12px' }}>
            <div
              style={{
                backgroundColor: classActionFeedback.type === 'success' ? '#ecfdf5' : '#fef2f2',
                border: `1px solid ${classActionFeedback.type === 'success' ? '#a7f3d0' : '#fecdd3'}`,
                color: classActionFeedback.type === 'success' ? '#065f46' : '#991b1b',
                borderRadius: '6px',
                padding: '8px 10px',
                fontSize: '12px'
              }}
            >
              {classActionFeedback.message}
            </div>
          </div>
        )}

        {/* Footer legend */}
        <div
          style={{
            padding: '5px 10px',
            backgroundColor: 'var(--surface-2, #f9fafb)',
            borderTop: '1px solid var(--border, #e5e7eb)',
            fontSize: '10px',
            color: 'var(--text-secondary, #9ca3af)',
            display: 'flex',
            gap: '10px',
            flexWrap: 'wrap'
          }}
        >
          <span>▸ expand/collapse</span>
          <span><Plus size={10} style={{ display: 'inline', verticalAlign: 'middle' }} /> child &nbsp;<GitBranch size={10} style={{ display: 'inline', verticalAlign: 'middle' }} /> sibling &nbsp;<Trash2 size={10} style={{ display: 'inline', verticalAlign: 'middle' }} /> delete</span>
        </div>
      </>
    );
  }, [hierarchyRootNode, expandedNodeIds, selectedNodes, classActionFeedback, canEdit, classActionLoading, renderHierarchyTree]);

  /**
   * ========================================================================
   * RENDER
   * ========================================================================
   */
  return (
    <div
      className="advanced-graph-view-d3"
      style={styles.container}
      data-testid="graph-view"
      data-graph-loading={loading ? 'true' : 'false'}
      data-graph-mode={visualizationType}
      data-graph-entrance={entrancePhase}
    >
      {/* Plugin Update Service */}
      <PluginUpdateService
        currentVersion="3.1.0"
        pluginId="graph-view-plugin"
        checkInterval={60 * 60 * 1000}
      />

      {/* FOCUS MODE BANNER — appears when a class is isolated to its neighborhood */}
      {focusedNodeId && (() => {
        const focusNode = allNodes.find(n => n.id === focusedNodeId);
        return (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 12,
              padding: '8px 14px',
              background: 'linear-gradient(90deg, #ede9fe 0%, #ddd6fe 100%)',
              borderBottom: '1px solid #a78bfa',
              fontSize: 13,
              color: '#4c1d95',
              flexShrink: 0
            }}
          >
            <span style={{ fontWeight: 700, display: 'flex', alignItems: 'center', gap: 6 }}>
              🎯 Focus:
            </span>
            <span style={{ fontWeight: 600, color: '#1e1b4b' }}>
              {focusNode?.label || focusedNodeId.split(/[#/]/).pop()}
            </span>
            <span style={{ color: '#6b7280', fontSize: 12 }}>
              showing {focusedNodeIds?.size ?? 0} nodes (↑{focusParentDepth} parents · ↓{focusChildDepth} children)
            </span>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginLeft: 12 }}>
              <label style={{ fontSize: 11, color: '#6b21a8' }}>↑</label>
              <input
                type="number"
                min={0}
                max={10}
                value={focusParentDepth}
                onChange={(e) => setFocusParentDepth(Math.max(0, Math.min(10, parseInt(e.target.value) || 0)))}
                style={{ width: 44, padding: '2px 4px', border: '1px solid #a78bfa', borderRadius: 4, fontSize: 12 }}
                title="Parent depth"
              />
              <label style={{ fontSize: 11, color: '#6b21a8' }}>↓</label>
              <input
                type="number"
                min={0}
                max={10}
                value={focusChildDepth}
                onChange={(e) => setFocusChildDepth(Math.max(0, Math.min(10, parseInt(e.target.value) || 0)))}
                style={{ width: 44, padding: '2px 4px', border: '1px solid #a78bfa', borderRadius: 4, fontSize: 12 }}
                title="Child depth"
              />
              <label style={{ fontSize: 11, color: '#6b21a8', display: 'flex', alignItems: 'center', gap: 3, marginLeft: 6 }}>
                <input
                  type="checkbox"
                  checked={focusIncludeProperties}
                  onChange={(e) => setFocusIncludeProperties(e.target.checked)}
                />
                props
              </label>
              <label style={{ fontSize: 11, color: '#6b21a8', display: 'flex', alignItems: 'center', gap: 3 }}>
                <input
                  type="checkbox"
                  checked={focusIncludeIndividuals}
                  onChange={(e) => setFocusIncludeIndividuals(e.target.checked)}
                />
                individuals
              </label>
            </div>
            <button
              onClick={exitFocusMode}
              style={{
                marginLeft: 'auto',
                padding: '4px 10px',
                background: '#7c3aed',
                color: '#fff',
                border: 'none',
                borderRadius: 4,
                cursor: 'pointer',
                fontSize: 12,
                fontWeight: 600
              }}
              title="Exit focus mode (show full graph)"
            >
              ✕ Exit Focus
            </button>
          </div>
        );
      })()}

      {/* Main Row with Two Columns */}
      <div style={styles.mainRow}>
        {/* First Column: Toolbar + Graph Area */}
        <div style={styles.firstColumn}>
          {/* Toolbar — minimal primary bar; everything else lives in the View-options popover */}
          <GraphToolbar
            styles={styles}
            loading={loading}
            hasNodes={allNodes.length > 0}
            visualizationType={visualizationType}
            ontographLayoutType={ontographLayoutType}
            assertionView={assertionView}
            inferredGraphStatus={inferredGraphStatus}
            edgeTypeFilters={filters.edgeTypes}
            savedViews={savedViews}
            selectedSavedViewId={selectedSavedViewId}
            canEdit={canEdit}
            editMode={editMode}
            focusedNodeId={focusedNodeId}
            selectedNodeInfo={selectedNodeInfo}
            showSearch={showSearch}
            showFilters={showFilters}
            showSettings={showSettings}
            showPropertyPanel={showPropertyPanel}
            showLocalGraph={showLocalGraph}
            showAnalytics={showAnalytics}
            localGraphFollowSelection={localGraphFollowSelection}
            showGrid={showGrid}
            physicsEnabled={settings.physics}
            showLegend={showLegend}
            showHierarchyDialog={showHierarchyDialog}
            statsLabel={`${getExpansionStats(allNodes.length, visibleNodeIds.size, expandedNodeIds.size)} · ${zoomLevel.toFixed(1)}x`}
            statsData={{ visible: visibleNodeIds.size, total: allNodes.length, expanded: expandedNodeIds.size }}
            lazyLoadingActive={allNodes.length > 1000}
            webglRenderer={webglActive}
            webglSupported={webglSupported}
            vowlDisplayOptions={vowlOptions}
            onChangeVowlOptions={(patch) => setVowlOptions(prev => ({ ...prev, ...patch }))}
            onToggleWebGL={() => setSettings(prev => ({ ...prev, renderer: prev.renderer === 'webgl' ? 'svg' : 'webgl' }))}
            showOverflowHint={showToolbarHint}
            onRefresh={() => fetchGraphData({ bypassCache: true })}
            onPresetNetwork={() => {
              setVisualizationType('force');
              setOntographLayoutType('spring');
              const { newExpandedIds, newVisibleIds } = expandAllNodes(allNodes);
              updateHierarchyState(() => ({ visible: newVisibleIds, expanded: newExpandedIds }));
            }}
            onPresetTree={() => {
              setVisualizationType('ontograph');
              setOntographLayoutType('tree');
              const { newExpandedIds, newVisibleIds } = expandAllNodes(allNodes);
              updateHierarchyState(() => ({ visible: newVisibleIds, expanded: newExpandedIds }));
            }}
            onSetVisualizationType={(next) => {
              setVisualizationType(next);
              if (next === 'ontograph' && ontographLayoutType === 'spring') {
                setOntographLayoutType('tree');
              }
              // Fresh settle when entering VOWL — don't inherit force-mode positions
              // that fight the per-edge Thing/Literal topology.
              if (next === 'vowl') {
                nodePositionsRef.current.clear();
                setClassDistance(170);
                setDatatypeDistance(85);
              }
            }}
            onSetOntographLayout={setOntographLayoutType}
            onSetAssertionView={setAssertionView}
            onToggleRelationship={toggleRelationshipVisibility}
            onShowAllRelations={showAllRelationshipTypes}
            onHideAllRelations={hideAllRelationshipTypes}
            onSaveView={handleSaveCurrentView}
            onLoadView={handleLoadSavedView}
            onDeleteView={handleDeleteSavedView}
            onZoomIn={handleZoomIn}
            onZoomOut={handleZoomOut}
            onFit={handleFit}
            onResetZoom={() => {
              if (svgRef.current && zoomRef.current) {
                cameraTransition(d3.select(svgRef.current)).call(zoomRef.current.transform as any, d3.zoomIdentity);
              }
            }}
            onExpandAll={() => {
              const { newExpandedIds, newVisibleIds } = expandAllNodes(allNodes);
              updateHierarchyState(() => ({ visible: newVisibleIds, expanded: newExpandedIds }));
            }}
            onCollapseAll={() => {
              const { newExpandedIds, newVisibleIds } = collapseAllNodes(allNodes, allEdges);
              updateHierarchyState(() => ({ visible: newVisibleIds, expanded: newExpandedIds }));
            }}
            onToggleEdit={() => setEditMode(!editMode)}
            onToggleSearch={() => setShowSearch(!showSearch)}
            onToggleFilters={() => setShowFilters(!showFilters)}
            onToggleSettings={() => {
              setShowSettings(!showSettings);
              setShowPropertyPanel(true); // Always show sidebar when settings clicked
            }}
            onToggleExplorer={() => setShowPropertyPanel(!showPropertyPanel)}
            onToggleLocal={() => setShowLocalGraph(v => !v)}
            onToggleInsights={() => setShowAnalytics(v => !v)}
            onToggleGrid={() => setShowGrid(!showGrid)}
            onTogglePhysics={togglePhysics}
            onToggleLegend={() => setShowLegend(!showLegend)}
            onToggleNavigator={() => {
              if (showHierarchyDialog) {
                setShowHierarchyDialog(false);
              } else {
                const target = selectedNodeInfo?.type === 'class' ? selectedNodeInfo : allNodes.find(n => n.type === 'class');
                if (target) {
                  setHierarchyRootNode(target);
                  setIsDialogMinimized(false);
                  setHierarchyDialogPosition({ x: 20, y: 120 });
                  setShowHierarchyDialog(true);
                }
              }
            }}
            onSetFollowSelection={setLocalGraphFollowSelection}
            onEnterFocus={() => { if (selectedNodeInfo) enterFocusMode(selectedNodeInfo.id); }}
            onExitFocus={exitFocusMode}
            onExport={handleExport}
          />

          {/* Suggest the WebGL renderer once graphs outgrow what SVG can keep smooth */}
          {!webglActive && webglSupported && filteredNodes.length > 5000 && !webglBannerDismissed && (
            <div
              data-testid="graph-webgl-banner"
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: '6px 12px',
                fontSize: 12.5,
                backgroundColor: 'var(--surface-2)',
                borderBottom: '1px solid var(--border)',
                color: 'var(--text-primary)'
              }}
            >
              <Zap size={14} />
              <span>
                Large graph ({filteredNodes.length.toLocaleString()} nodes) — the WebGL renderer keeps pan and zoom smooth at this size.
              </span>
              <button
                onClick={() => setSettings(prev => ({ ...prev, renderer: 'webgl' }))}
                style={{ ...styles.btn, padding: '2px 10px', fontSize: 12 }}
              >
                Enable WebGL
              </button>
              <button
                onClick={() => setWebglBannerDismissed(true)}
                style={{ ...styles.btn, padding: '2px 10px', fontSize: 12, opacity: 0.7 }}
              >
                Dismiss
              </button>
            </div>
          )}

          {/* Graph workspace: main canvas OR OntoCode local graph (toggled, never both) */}
          <div style={styles.graphWorkspace}>
          <div style={{ ...styles.graphContentArea, flex: 1, position: 'relative', display: showLocalGraph ? 'none' : undefined }}>
            {/* Slim rail to reopen the Explorer sidebar when it's collapsed */}
            {!showPropertyPanel && (
              <button
                data-testid="graph-explorer-rail"
                onClick={() => setShowPropertyPanel(true)}
                title="Open Explorer sidebar"
                style={{
                  position: 'absolute',
                  right: 0,
                  top: '50%',
                  transform: 'translateY(-50%)',
                  zIndex: 30,
                  padding: '12px 3px',
                  borderRadius: '6px 0 0 6px',
                  border: '1px solid var(--border)',
                  borderRight: 'none',
                  backgroundColor: 'var(--surface-2)',
                  color: 'var(--text-primary)',
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center'
                }}
              >
                <ChevronRight size={14} style={{ transform: 'rotate(180deg)' }} />
              </button>
            )}
            {/* WebGL renderer (feature-flagged spike) — high-performance path for large graphs */}
            {webglActive && (
              <WebGLGraphView
                nodes={benchData?.nodes ?? filteredNodes}
                edges={benchData?.edges ?? filteredEdges}
                dark={isDarkTheme}
                nodeSize={settings.nodeSize}
                selectedNodeIds={selectedNodes}
                onNodeClick={(id) => {
                  const found = allNodes.find(n => n.id === id);
                  setSelectedNodes(new Set([id]));
                  if (found) setSelectedNodeInfo(found);
                  onNodeClickRef.current?.(id);
                }}
                onNodeRightClick={(id, pos) => {
                  if (canEdit) setContextMenu({ visible: true, x: pos.x, y: pos.y, nodeId: id });
                }}
              />
            )}
            {/* SVG Canvas for Force, VOWL, OntoGraph, and projected 3D Spatial mode */}
            {!webglActive && (
            <svg
              ref={svgRef}
              data-testid="graph-svg"
              style={visualizationType === 'spatial3d' ? { ...styles.svg, ...styles.spatial3dSvg } : styles.svg}
            >
                <defs>
                  {/* Grid pattern */}
                  {showGrid && (
                    <pattern id="grid" width="20" height="20" patternUnits="userSpaceOnUse">
                      <path d="M 20 0 L 0 0 0 20" fill="none" stroke="#e5e5e5" strokeWidth="0.5" />
                    </pattern>
                  )}
                  {/* Search highlight glow filter */}
                  <filter id="search-glow" x="-30%" y="-30%" width="160%" height="160%">
                    <feGaussianBlur stdDeviation="3" result="coloredBlur" />
                    <feFlood floodColor="#f59e0b" floodOpacity="0.6" result="glowColor" />
                    <feComposite in="glowColor" in2="coloredBlur" operator="in" result="softGlow" />
                    <feMerge>
                      <feMergeNode in="softGlow" />
                      <feMergeNode in="SourceGraphic" />
                    </feMerge>
                  </filter>
                </defs>
                {/* Micro-interactions: hover lift, smooth highlight transitions,
                    label halos for readability, selection pulse */}
                <style>{`
                  [data-testid="graph-svg"] .node-shape {
                    transition: stroke 160ms ease, stroke-width 160ms ease, opacity 220ms ease;
                  }
                  [data-testid="graph-svg"] .node:hover > * {
                    transform: scale(1.03);
                    transition: transform 180ms ease;
                  }
                  [data-testid="graph-svg"] .node > text {
                    transition: opacity 220ms ease;
                    paint-order: stroke;
                    /* VOWL light canvas: flat labels like VOWL (no washed-out white halo) */
                    stroke: ${visualizationType === 'vowl'
                      ? 'none'
                      : (isDarkTheme ? 'rgba(10,15,28,0.85)' : 'rgba(255,255,255,0.75)')};
                    stroke-width: ${visualizationType === 'vowl' ? '0' : '2.5px'};
                    stroke-linejoin: round;
                  }
                  [data-testid="graph-svg"] .edge-path {
                    transition: stroke-opacity 200ms ease, stroke-width 160ms ease;
                  }
                  @keyframes ontocode-node-pulse {
                    0%, 100% { stroke-opacity: 1; }
                    50% { stroke-opacity: 0.45; }
                  }
                  [data-testid="graph-svg"] .node-selected > .node-shape {
                    animation: ontocode-node-pulse 1.8s ease-in-out infinite;
                  }
                `}</style>
                {visualizationType === 'spatial3d' && (
                  <>
                    <radialGradient id="spatial3d-bg" cx="50%" cy="45%" r="75%">
                      <stop offset="0%" stopColor="#312e81" stopOpacity="0.45" />
                      <stop offset="55%" stopColor="#111827" stopOpacity="0.35" />
                      <stop offset="100%" stopColor="#020617" stopOpacity="0.12" />
                    </radialGradient>
                    <rect width="100%" height="100%" fill="url(#spatial3d-bg)" />
                  </>
                )}
                {showGrid && <rect width="100%" height="100%" fill="url(#grid)" />}
                <g ref={gRef} />
            </svg>
            )}

            {visualizationType === 'spatial3d' && (
              <div style={styles.spatial3dHint}>
                <Box size={14} />
                3D Spatial Graph: wheel to zoom, drag to pan, drag nodes to reshape the graph, double-click to expand branches.
              </div>
            )}

            {/* Empty state overlay */}
            {!loading && filteredNodes.length === 0 && (
              <div style={{
                position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
                display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
                background: 'rgba(249,250,251,0.95)', zIndex: 10, gap: 12
              }}>
                <div style={{ fontSize: 48 }}>🌐</div>
                <div style={{ fontSize: 18, fontWeight: 600, color: '#374151' }}>No graph data to display</div>
                <div style={{ fontSize: 14, color: '#6b7280', textAlign: 'center', maxWidth: 360 }}>
                  {allNodes.length === 0
                    ? 'Click Refresh to load the ontology graph, or check that this project has ontology data.'
                    : 'No nodes match the current filters or search query.'}
                </div>
                {allNodes.length === 0 && (
                  <button onClick={() => fetchGraphData()} style={{ padding: '8px 20px', background: '#4f46e5', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer', fontSize: 14 }}>
                    Load Graph
                  </button>
                )}
                {allNodes.length > 0 && searchQuery && (
                  <button onClick={() => handleSearch('')} style={{ padding: '8px 20px', background: '#6b7280', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer', fontSize: 14 }}>
                    Clear Search
                  </button>
                )}
              </div>
            )}

            {/* Search Panel */}
            {showSearch && (
          <div style={styles.searchPanel}>
            <div style={styles.panelHeader}>
              <Search size={18} />
              <h3 style={styles.panelTitle}>Search</h3>
              <button onClick={() => setShowSearch(false)} style={styles.closeBtn}>×</button>
            </div>
            <input
              type="text"
              placeholder="Search node (shows path and children)..."
              value={searchQuery}
              onChange={(e) => handleSearch(e.target.value)}
              style={styles.searchInput}
            />
            {searchQuery && (
              <div style={{ padding: '12px', background: '#f0f9ff', borderRadius: '8px', margin: '12px' }}>
                <div style={{ fontSize: '12px', color: '#0369a1', marginBottom: '4px' }}>
                  Search Results
                </div>
                <div style={{ fontSize: '13px', color: '#0c4a6e' }}>
                  {getExpansionStats(allNodes.length, visibleNodeIds.size, expandedNodeIds.size)}
                </div>
                <button
                  onClick={() => handleSearch('')}
                  style={{
                    marginTop: '8px',
                    padding: '6px 12px',
                    background: '#667eea',
                    color: 'white',
                    border: 'none',
                    borderRadius: '6px',
                    cursor: 'pointer',
                    fontSize: '12px'
                  }}
                >
                  Clear & Show Roots
                </button>
              </div>
            )}
            {!searchQuery && (
              <div style={styles.searchResults}>
                {visibleNodeIds.size} visible nodes
              </div>
            )}
          </div>
        )}

        </div>

        {showLocalGraph && (
          <div
            data-testid="graph-local-pane"
            style={{
              flex: 1,
              minHeight: 0,
              display: 'flex',
              flexDirection: 'column'
            }}
          >
            <LocalGraphView
              nodes={allNodes}
              edges={allEdges}
              focusNodeId={activeLocalFocusId}
              onSelect={(node) => {
                setSelectedNodes(new Set([node.id]));
                setSelectedNodeInfo(node);
                setLocalFocusId(node.id);
              }}
              onActivate={(node) => {
                setLocalFocusId(node.id);
                setSelectedNodes(new Set([node.id]));
                setSelectedNodeInfo(node);
                enterFocusMode(node.id);
              }}
              showToolbar
              height="100%"
            />
          </div>
        )}

          </div>
      </div>

      {/* OntoCode Insights Panel — right-side column */}
      {showAnalytics && (
        <AnalyticsPanel
          analytics={graphAnalytics}
          nodes={filteredNodes}
          edges={filteredEdges}
          selectedNode={selectedNodeInfo}
          colorByCluster={colorByCluster}
          onToggleColorByCluster={setColorByCluster}
          centralityThreshold={centralityThreshold}
          onCentralityThresholdChange={setCentralityThreshold}
          onSelectNode={(node) => {
            setSelectedNodes(new Set([node.id]));
            setSelectedNodeInfo(node);
            setLocalFocusId(node.id);
          }}
          onHighlightGap={(gap) => {
            const nodeA = filteredNodes.find(n => n.label === gap.labelA);
            const nodeB = filteredNodes.find(n => n.label === gap.labelB);
            if (nodeA) {
              setSelectedNodeInfo(nodeA);
              setLocalFocusId(nodeA.id);
            } else if (nodeB) {
              setSelectedNodeInfo(nodeB);
              setLocalFocusId(nodeB.id);
            }
          }}
          onClose={() => setShowAnalytics(false)}
        />
      )}

        {/* Graph View Sidebar - Second Column */}
        {showPropertyPanel && (
          <div style={{ position: 'relative', display: 'flex', minWidth: 0 }}>
          {/* Collapse chevron on the panel's left edge — mirrors the reopen rail on the canvas */}
          <button
            data-testid="graph-explorer-collapse"
            onClick={() => setShowPropertyPanel(false)}
            title="Collapse Explorer sidebar"
            style={{
              position: 'absolute',
              left: -17,
              top: '50%',
              transform: 'translateY(-50%)',
              zIndex: 30,
              padding: '12px 3px',
              borderRadius: '6px 0 0 6px',
              border: '1px solid var(--border)',
              borderRight: 'none',
              backgroundColor: 'var(--surface-2)',
              color: 'var(--text-primary)',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center'
            }}
          >
            <ChevronRight size={14} />
          </button>
          <GraphViewSidebar
            nodes={allNodes}
            edges={allEdges}
            selectedNode={selectedNodeInfo}
            onNodeSelect={(node) => {
              if (node) {
                setSelectedNodes(new Set([node.id]));
                setSelectedNodeInfo(node);

                // Focus the hierarchy navigator for class nodes — shows inline in
                // the sidebar by default; the floating popup is opt-in only (see
                // onPopOutHierarchyNavigator/onToggleNavigator/context menu).
                if (node.type === 'class') {
                  setHierarchyRootNode(node);
                }

                // Highlight node in graph
                d3.selectAll('.node')
                  .classed('highlighted', false)
                  .filter((d: any) => d.id === node.id)
                  .classed('highlighted', true);
              } else {
                setSelectedNodes(new Set());
                setSelectedNodeInfo(null);
                d3.selectAll('.node').classed('highlighted', false);
              }
            }}
            hierarchyNavigatorContent={hierarchyRootNode ? hierarchyNavigatorBody : null}
            hierarchyNavigatorLabel={hierarchyRootNode?.label}
            onCloseHierarchyNavigator={() => setHierarchyRootNode(null)}
            onPopOutHierarchyNavigator={() => {
              setIsDialogMinimized(false);
              setHierarchyDialogPosition({ x: 20, y: 100 });
              setShowHierarchyDialog(true);
            }}
            onNodeHighlight={(nodeId) => {
              d3.selectAll('.node')
                .classed('hover-highlight', false)
                .filter((d: any) => d.id === nodeId)
                .classed('hover-highlight', true);
            }}
            filters={filters}
            onFilterChange={(newFilters) => setFilters(newFilters)}
            projectId={projectId}
            viewMode={visualizationType}
            showFilterSidebar={showFilters}
            showSettings={showSettings}
            onSearchChange={(term) => setSidebarSearchTerm(term)}
            classDistance={classDistance}
            datatypeDistance={datatypeDistance}
            onClassDistanceChange={(distance) => {
              setClassDistance(distance);
              // Restart simulation with new distance
              if (simulationRef.current) {
                simulationRef.current.alpha(0.3).restart();
              }
            }}
            onDatatypeDistanceChange={(distance) => {
              setDatatypeDistance(distance);
              // Restart simulation with new distance
              if (simulationRef.current) {
                simulationRef.current.alpha(0.3).restart();
              }
            }}
            onPauseLayout={() => {
              setIsLayoutPaused(!isLayoutPaused);
              if (simulationRef.current) {
                if (!isLayoutPaused) {
                  simulationRef.current.stop();
                } else {
                  simulationRef.current.alpha(0.3).restart();
                }
              }
            }}
            onResetLayout={() => {
              setClassDistance(100);
              setDatatypeDistance(100);
              if (simulationRef.current) {
                simulationRef.current.alpha(1).restart();
              }
            }}
            isLayoutPaused={isLayoutPaused}
            vowlFilters={vowlFilters}
            onVowlFilterChange={(newFilters) => setVowlFilters(newFilters)}
            hierarchySelectedClass={hierarchySelectedClass}
            onHierarchyClassSelect={(node) => {
              setHierarchySelectedClass(node);
              if (node) {
                setShowClassHierarchy(true);
              }
            }}
            showClassHierarchy={showClassHierarchy}
            vowlLegend={dynamicLegend}
            onGraphNodeExpand={(nodeId) => {
              handleToggleExpansion(nodeId);
            }}
            onGraphNodeCollapse={(nodeId) => {
              handleToggleExpansion(nodeId);
            }}
            graphExpandedNodeIds={expandedNodeIds}
            graphVisibleNodeIds={visibleNodeIds}
            focusedNodeId={focusedNodeId}
            onFocusNode={enterFocusMode}
            onClearFocus={exitFocusMode}
            ontologyMetadata={ontologyMetadata}
          />
          </div>
        )}
      </div>

        {/* Hierarchy Dialog */}
        {showHierarchyDialog && hierarchyRootNode && (
          <div
            style={{
              position: 'fixed',
              left: `${hierarchyDialogPosition.x}px`,
              top: `${hierarchyDialogPosition.y}px`,
              width: isDialogMinimized ? 'auto' : '300px',
              minWidth: isDialogMinimized ? '220px' : 'auto',
              maxHeight: isDialogMinimized ? 'auto' : '340px',
              backgroundColor: 'var(--surface-1, #fff)',
              border: '1px solid var(--border, #d1d5db)',
              borderRadius: '8px',
              boxShadow: '0 10px 25px rgba(0,0,0,0.15)',
              zIndex: 1000,
              display: 'flex',
              flexDirection: 'column',
              overflow: 'hidden',
              transition: 'all 0.2s ease-in-out'
            }}
            onClick={(e) => e.stopPropagation()}
          >
            {/* Dialog Header - Draggable */}
            <div
              style={{
                padding: '8px 12px',
                background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
                color: '#fff',
                fontWeight: '600',
                fontSize: '13px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                cursor: 'move',
                userSelect: 'none',
                boxShadow: '0 4px 12px rgba(102, 126, 234, 0.3)',
                borderTopLeftRadius: '8px',
                borderTopRightRadius: '8px'
              }}
              onMouseDown={(e) => {
                // Don't start drag if clicking on buttons
                if ((e.target as HTMLElement).tagName === 'BUTTON' || (e.target as HTMLElement).closest('button')) {
                  return;
                }
                
                const startX = e.clientX - hierarchyDialogPosition.x;
                const startY = e.clientY - hierarchyDialogPosition.y;

                const handleMouseMove = (moveEvent: MouseEvent) => {
                  setHierarchyDialogPosition({
                    x: moveEvent.clientX - startX,
                    y: moveEvent.clientY - startY
                  });
                };

                const handleMouseUp = () => {
                  document.removeEventListener('mousemove', handleMouseMove);
                  document.removeEventListener('mouseup', handleMouseUp);
                };

                document.addEventListener('mousemove', handleMouseMove);
                document.addEventListener('mouseup', handleMouseUp);
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <GripVertical size={18} style={{ opacity: 0.8 }} />
                <Box size={16} />
                <span>Class Hierarchy Navigator</span>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    setIsDialogMinimized(!isDialogMinimized);
                  }}
                  style={{
                    background: 'transparent',
                    border: 'none',
                    color: '#fff',
                    cursor: 'pointer',
                    padding: '4px 6px',
                    lineHeight: '1',
                    borderRadius: '4px',
                    display: 'flex',
                    alignItems: 'center',
                    transition: 'background-color 0.15s'
                  }}
                  onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'rgba(255,255,255,0.1)'}
                  onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
                  title="Minimize"
                >
                  <Minus size={16} />
                </button>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    setShowHierarchyDialog(false);
                  }}
                  style={{
                    background: 'transparent',
                    border: 'none',
                    color: '#fff',
                    cursor: 'pointer',
                    padding: '4px 6px',
                    lineHeight: '1',
                    borderRadius: '4px',
                    display: 'flex',
                    alignItems: 'center',
                    transition: 'background-color 0.15s'
                  }}
                  onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'rgba(255,255,255,0.2)'}
                  onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
                  title="Close"
                >
                  <X size={16} />
                </button>
              </div>
            </div>

            {/* Dialog Content - Hidden when minimized */}
            {!isDialogMinimized && hierarchyNavigatorBody}
          </div>
        )}

        {pendingClassAction && (
          <div style={styles.modalOverlay} onClick={handlePendingActionCancel}>
            <div
              style={styles.modal}
              onClick={e => e.stopPropagation()}
            >
              <div style={styles.modalHeader}>
                {pendingClassAction.kind === 'create'
                  ? `Create ${pendingClassAction.relation === 'child' ? 'Child' : 'Sibling'} Class`
                  : 'Delete Class'}
              </div>
              <div style={styles.modalBody}>
                {pendingClassAction.kind === 'create' ? (
                  <>
                    <div style={{ marginBottom: '12px', fontSize: '13px', color: '#4b5563' }}>
                      Add a {pendingClassAction.relation === 'child' ? 'child of' : 'sibling next to'} <strong>{pendingClassAction.targetNode.label}</strong>.
                    </div>
                    <div style={{ marginBottom: '8px', fontSize: '12px', color: '#6b7280' }}>
                      Parent: <strong>{pendingClassAction.parentNode.label}</strong>
                    </div>
                    <label style={{ display: 'block', fontSize: '12px', color: '#374151', marginBottom: '4px' }}>Class Name</label>
                    <input
                      type="text"
                      value={pendingClassAction.label}
                      onChange={e => handlePendingLabelChange(e.target.value)}
                      placeholder="Enter class label"
                      style={styles.modalInput}
                      autoFocus
                      disabled={classActionLoading}
                    />
                    <button
                      style={styles.modalLinkButton}
                      onClick={() => {
                        requestHostClassDialog(pendingClassAction.relation, pendingClassAction.targetNode, pendingClassAction.parentNode);
                        setPendingClassAction(null);
                      }}
                      disabled={classActionLoading}
                    >
                      Use main editor dialog
                    </button>
                  </>
                ) : (
                  <>
                    <div style={{ marginBottom: '12px', fontSize: '13px', color: '#4b5563' }}>
                      Are you sure you want to delete <strong>{pendingClassAction.targetNode.label}</strong>?
                    </div>
                    {pendingClassAction.childCount > 0 && (
                      <div style={{ fontSize: '12px', color: '#b45309', background: '#fff7ed', padding: '8px', borderRadius: '6px' }}>
                        This class has {pendingClassAction.childCount} child class{pendingClassAction.childCount === 1 ? '' : 'es'} that will be detached.
                      </div>
                    )}
                    <button
                      style={styles.modalLinkButton}
                      onClick={() => {
                        requestHostDeleteDialog(pendingClassAction.targetNode);
                        setPendingClassAction(null);
                      }}
                      disabled={classActionLoading}
                    >
                      Use main editor delete flow
                    </button>
                  </>
                )}
              </div>
              <div style={styles.modalActions}>
                <button
                  style={styles.modalButton}
                  onClick={handlePendingActionCancel}
                  disabled={classActionLoading}
                >
                  Cancel
                </button>
                <button
                  style={{
                    ...styles.modalButtonPrimary,
                    backgroundColor: pendingClassAction.kind === 'delete' ? '#dc2626' : '#2563eb',
                    borderColor: pendingClassAction.kind === 'delete' ? '#b91c1c' : '#2563eb'
                  }}
                  onClick={handleConfirmPendingAction}
                  disabled={classActionLoading || (pendingClassAction.kind === 'create' && pendingClassAction.label.trim().length === 0)}
                >
                  {pendingClassAction.kind === 'create' ? 'Create Class' : 'Delete Class'}
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Loading */}
        {loading && (
          <div style={styles.loadingOverlay} data-testid="graph-loading">
            <RefreshCw size={32} className="spinning" />
            <div style={{ marginTop: '12px' }}>Loading graph...</div>
          </div>
        )}

        {/* Error */}
        {error && (
          <div style={styles.errorPanel}>
            <AlertTriangle size={20} color="#ef4444" />
            <div>{error}</div>
          </div>
        )}

        {/* Context Menu */}
        {contextMenu.visible && contextMenu.nodeId && (
          <div
            style={{
              ...styles.contextMenu,
              left: contextMenu.x,
              top: contextMenu.y
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={styles.contextMenuHeader}>
              {allNodes.find(n => n.id === contextMenu.nodeId)?.label || 'Node'}
            </div>
            {hasChildren(contextMenu.nodeId, allEdges, allNodes) && (
              <>
                {!expandedNodeIds.has(contextMenu.nodeId) ? (
                  <button
                    style={styles.contextMenuItem}
                    onClick={() => {
                      handleToggleExpansion(contextMenu.nodeId!);
                      setContextMenu({ ...contextMenu, visible: false });
                    }}
                  >
                    Expand children
                  </button>
                ) : (
                  <button
                    style={styles.contextMenuItem}
                    onClick={() => {
                      handleToggleExpansion(contextMenu.nodeId!);
                      setContextMenu({ ...contextMenu, visible: false });
                    }}
                  >
                    Collapse children
                  </button>
                )}
              </>
            )}
            <button
              style={styles.contextMenuItem}
              onClick={() => {
                setSelectedNodeInfo(allNodes.find(n => n.id === contextMenu.nodeId) || null);
                setShowPropertyPanel(true);
                setContextMenu({ ...contextMenu, visible: false });
              }}
            >
              ℹ️ View Properties
            </button>
            <button
              style={styles.contextMenuItem}
              onClick={() => {
                if (contextMenu.nodeId) onNodeClickRef.current?.(contextMenu.nodeId);
                setContextMenu({ ...contextMenu, visible: false });
              }}
            >
              📄 Go to Entity Details
            </button>
            {allNodes.find(n => n.id === contextMenu.nodeId)?.type === 'class' && (
              <button
                style={styles.contextMenuItem}
                onClick={() => {
                  const node = allNodes.find(n => n.id === contextMenu.nodeId);
                  if (node) {
                    setHierarchyRootNode(node);
                    setIsDialogMinimized(false);
                    const padding = 16;
                    const dialogWidth = 300;
                    const dialogHeight = 340;
                    const viewportWidth = window.innerWidth;
                    const viewportHeight = window.innerHeight;
                    let posX = contextMenu.x + padding;
                    let posY = contextMenu.y - 80;
                    if (posX + dialogWidth > viewportWidth - padding) posX = contextMenu.x - dialogWidth - padding;
                    if (posX < padding) posX = padding;
                    if (posY < padding) posY = padding;
                    if (posY + dialogHeight > viewportHeight - padding) posY = viewportHeight - dialogHeight - padding;
                    setHierarchyDialogPosition({ x: posX, y: posY });
                    setShowHierarchyDialog(true);
                  }
                  setContextMenu({ ...contextMenu, visible: false });
                }}
              >
                🌿 Edit in Hierarchy Navigator
              </button>
            )}
            <button
              style={{ ...styles.contextMenuItem, background: focusedNodeId === contextMenu.nodeId ? '#ede9fe' : undefined, fontWeight: focusedNodeId === contextMenu.nodeId ? 600 : 400 }}
              onClick={() => {
                if (contextMenu.nodeId) enterFocusMode(contextMenu.nodeId);
                setContextMenu({ ...contextMenu, visible: false });
              }}
            >
              🎯 Focus on this Class (parents + children)
            </button>
            <button
              style={styles.contextMenuItem}
              onClick={() => {
                const { newExpandedIds, newVisibleIds } = expandAllNodes(allNodes);
                updateHierarchyState(() => ({
                  visible: newVisibleIds,
                  expanded: newExpandedIds
                }));
                setContextMenu({ ...contextMenu, visible: false });
              }}
            >
              🌳 Expand All
            </button>
            <button
              style={styles.contextMenuItem}
              onClick={() => {
                const { newExpandedIds, newVisibleIds } = collapseAllNodes(allNodes, allEdges);
                updateHierarchyState(() => ({
                  visible: newVisibleIds,
                  expanded: newExpandedIds
                }));
                setContextMenu({ ...contextMenu, visible: false });
              }}
            >
              📁 Collapse All
            </button>
          </div>
        )}

      {/* Styles */}
      <style>{`
        .advanced-graph-view-d3 {
          font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', sans-serif;
        }
        .spinning {
          animation: spin 1s linear infinite;
        }
        @keyframes spin {
          from { transform: rotate(0deg); }
          to { transform: rotate(360deg); }
        }
        @keyframes slideDown {
          from {
            opacity: 0;
            max-height: 0;
            transform: translateY(-10px);
          }
          to {
            opacity: 1;
            max-height: 2000px;
            transform: translateY(0);
          }
        }
        .graph-tooltip {
          box-shadow: 0 4px 12px rgba(0,0,0,0.3);
        }
        button[style*="contextMenuItem"]:hover {
          background: #f3f4f6 !important;
        }
        .node.highlighted circle,
        .node.highlighted rect,
        .node.highlighted polygon {
          stroke: #667eea !important;
          stroke-width: 4 !important;
          filter: drop-shadow(0 0 8px rgba(102, 126, 234, 0.6)) !important;
        }
        .node.hover-highlight circle,
        .node.hover-highlight rect,
        .node.hover-highlight polygon {
          stroke: #10b981 !important;
          stroke-width: 3 !important;
          filter: drop-shadow(0 0 6px rgba(16, 185, 129, 0.4)) !important;
        }
      `}</style>

      {/* Save-view name prompt — see showSaveViewPrompt declaration for why this
          replaces window.prompt() */}
      {showSaveViewPrompt && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 10000,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'rgba(0,0,0,0.5)'
          }}
          onClick={() => setShowSaveViewPrompt(false)}
        >
          <div
            style={{
              background: '#fff',
              borderRadius: 8,
              boxShadow: '0 10px 40px rgba(0,0,0,0.25)',
              padding: 24,
              width: '100%',
              maxWidth: 420
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <h3 style={{ margin: '0 0 12px', fontSize: 16, fontWeight: 600, color: '#1f2937' }}>
              Save graph view as
            </h3>
            <input
              type="text"
              autoFocus
              value={saveViewNameDraft}
              onChange={(e) => setSaveViewNameDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') confirmSaveCurrentView(saveViewNameDraft);
                if (e.key === 'Escape') setShowSaveViewPrompt(false);
              }}
              style={{
                width: '100%',
                boxSizing: 'border-box',
                padding: '8px 12px',
                fontSize: 14,
                border: '1px solid #d1d5db',
                borderRadius: 6
              }}
            />
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 16 }}>
              <button
                onClick={() => setShowSaveViewPrompt(false)}
                style={{
                  padding: '8px 16px',
                  fontSize: 14,
                  borderRadius: 6,
                  border: 'none',
                  background: '#e5e7eb',
                  color: '#111827',
                  cursor: 'pointer'
                }}
              >
                Cancel
              </button>
              <button
                onClick={() => confirmSaveCurrentView(saveViewNameDraft)}
                style={{
                  padding: '8px 16px',
                  fontSize: 14,
                  borderRadius: 6,
                  border: 'none',
                  background: '#7c3aed',
                  color: '#fff',
                  cursor: 'pointer'
                }}
              >
                Save
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

// Styles
const styles: Record<string, React.CSSProperties> = {
  container: {
    position: 'relative',
    width: '100%',
    height: '100%',
    display: 'flex',
    flexDirection: 'column',
    backgroundColor: 'var(--bg)',
    overflow: 'hidden'
  },
  mainRow: {
    flex: 1,
    display: 'flex',
    flexDirection: 'row',
    overflow: 'hidden',
    backgroundColor: 'var(--surface-1)'
  },
  firstColumn: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden'
  },
  toolbar: {
    padding: '10px 12px',
    backgroundColor: 'var(--surface-1)',
    borderBottom: '1px solid var(--border)',
    display: 'flex',
    gap: '6px',
    alignItems: 'center',
    flexWrap: 'wrap',
    boxShadow: 'none'
  },
  btn: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    padding: '7px 11px',
    background: 'var(--surface-1)',
    border: '1px solid var(--border)',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '13px',
    color: 'var(--text-primary)',
    fontWeight: '500',
    transition: 'all 0.2s'
  },
  btnPrimary: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    padding: '7px 11px',
    background: 'var(--accent)',
    border: '1px solid var(--accent)',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '13px',
    color: 'var(--on-accent)',
    fontWeight: '500',
    transition: 'all 0.2s'
  },
  btnActive: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    padding: '7px 11px',
    background: 'var(--accent-tint)',
    border: '1px solid var(--accent)',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '13px',
    color: 'var(--accent)',
    fontWeight: '500',
    transition: 'all 0.2s'
  },
  divider: {
    width: '1px',
    height: '24px',
    backgroundColor: 'var(--divider)'
  },
  miniDivider: {
    width: '1px',
    height: '16px',
    backgroundColor: 'var(--divider)',
    margin: '0 2px'
  },
  toolbarIconBtn: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: '26px',
    height: '26px',
    background: 'transparent',
    border: 'none',
    borderRadius: '3px',
    cursor: 'pointer',
    color: 'var(--text-primary)',
    transition: 'all 0.1s'
  },
  toolbarIconBtnActive: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: '26px',
    height: '26px',
    background: 'var(--accent-tint)',
    border: '1px solid var(--accent)',
    borderRadius: '3px',
    cursor: 'pointer',
    color: 'var(--accent)',
    transition: 'all 0.1s'
  },
  toolbarIconBtnDisabled: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: '26px',
    height: '26px',
    background: 'transparent',
    border: 'none',
    borderRadius: '3px',
    cursor: 'not-allowed',
    color: 'var(--text-tertiary)',
    opacity: 0.45
  },
  savedViewsGroup: {
    display: 'flex',
    alignItems: 'center',
    gap: '3px',
    padding: '2px',
    borderRadius: '6px',
    border: '1px solid var(--border)',
    backgroundColor: 'var(--surface-2)'
  },
  savedViewsSelect: {
    minWidth: '150px',
    maxWidth: '220px',
    padding: '4px 8px',
    border: '1px solid var(--border)',
    borderRadius: '4px',
    backgroundColor: 'var(--surface-1)',
    color: 'var(--text-primary)',
    fontSize: '12px',
    fontWeight: 500
  },
  relationshipControlsGroup: {
    display: 'flex',
    alignItems: 'center',
    gap: '4px',
    padding: '3px 4px',
    borderRadius: '6px',
    border: '1px solid var(--border)',
    backgroundColor: 'var(--surface-2)'
  },
  relationshipControlsLabel: {
    padding: '0 4px',
    fontSize: '11px',
    fontWeight: 700,
    color: 'var(--text-secondary)',
    textTransform: 'uppercase',
    letterSpacing: '0.04em'
  },
  relationshipPill: {
    padding: '3px 7px',
    borderRadius: '999px',
    border: '1px solid var(--border)',
    backgroundColor: 'var(--surface-1)',
    color: 'var(--text-secondary)',
    fontSize: '11px',
    fontWeight: 700,
    cursor: 'pointer'
  },
  relationshipPillActive: {
    padding: '3px 7px',
    borderRadius: '999px',
    border: '1px solid var(--accent)',
    backgroundColor: 'var(--accent-tint)',
    color: 'var(--accent)',
    fontSize: '11px',
    fontWeight: 700,
    cursor: 'pointer'
  },
  relationshipMiniAction: {
    padding: '3px 6px',
    borderRadius: '4px',
    border: '1px solid var(--border)',
    backgroundColor: 'transparent',
    color: 'var(--text-secondary)',
    fontSize: '11px',
    fontWeight: 600,
    cursor: 'pointer'
  },
  stats: {
    fontSize: '13px',
    color: 'var(--text-secondary)',
    padding: '0 12px'
  },
  graphWorkspace: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    minHeight: 0,
    overflow: 'hidden'
  },
  graphContentArea: {
    flex: 1,
    position: 'relative',
    overflow: 'hidden',
    backgroundColor: 'var(--bg)'
  },
  content: {
    flex: 1,
    display: 'flex',
    flexDirection: 'row',
    position: 'relative',
    overflow: 'hidden',
    backgroundColor: 'var(--surface-1)',
    gap: '0'
  },
  graphArea: {
    flex: 1,
    position: 'relative',
    overflow: 'hidden',
    backgroundColor: 'var(--bg)',
    borderRight: '2px solid var(--border)'
  },
  svg: {
    width: '100%',
    height: '100%',
    cursor: 'grab'
  },
  spatial3dSvg: {
    background: 'linear-gradient(135deg, #020617 0%, #111827 48%, #312e81 100%)'
  },
  spatial3dHint: {
    position: 'absolute',
    left: '16px',
    bottom: '16px',
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    padding: '8px 12px',
    borderRadius: '999px',
    color: '#dbeafe',
    background: 'rgba(15, 23, 42, 0.78)',
    border: '1px solid rgba(147, 197, 253, 0.35)',
    boxShadow: '0 10px 30px rgba(15, 23, 42, 0.35)',
    fontSize: '12px',
    zIndex: 9,
    pointerEvents: 'none'
  },
  searchPanel: {
    position: 'absolute',
    top: '20px',
    left: '20px',
    width: '300px',
    background: 'var(--surface-1)',
    borderRadius: '12px',
    boxShadow: '0 10px 40px rgba(0,0,0,0.1)',
    overflow: 'hidden',
    zIndex: 10
  },
  vowlLegendPanel: {
    position: 'absolute',
    bottom: '20px',
    left: '20px',
    width: '280px',
    maxHeight: '80vh',
    background: 'var(--surface-1)',
    borderRadius: '12px',
    boxShadow: '0 10px 40px rgba(0,0,0,0.1)',
    overflow: 'hidden',
    zIndex: 10
  },
  propertyPanel: {
    position: 'absolute',
    top: '20px',
    right: '20px',
    width: '320px',
    background: 'var(--surface-1)',
    borderRadius: '12px',
    boxShadow: '0 10px 40px rgba(0,0,0,0.1)',
    overflow: 'hidden',
    maxHeight: '80vh',
    display: 'flex',
    flexDirection: 'column'
  },
  panelHeader: {
    padding: '16px 20px',
    background: 'var(--surface-2)',
    borderBottom: '1px solid var(--border)',
    display: 'flex',
    alignItems: 'center',
    gap: '8px'
  },
  panelTitle: {
    flex: 1,
    margin: 0,
    fontSize: '16px',
    fontWeight: '600',
    color: 'var(--text-primary)'
  },
  closeBtn: {
    background: 'transparent',
    border: 'none',
    color: 'var(--text-tertiary)',
    cursor: 'pointer',
    fontSize: '24px',
    lineHeight: '1',
    padding: '0',
    width: '24px',
    height: '24px'
  },
  searchInput: {
    width: '100%',
    padding: '12px 16px',
    border: 'none',
    borderBottom: '1px solid var(--border)',
    fontSize: '14px',
    outline: 'none',
    boxSizing: 'border-box',
    backgroundColor: 'var(--surface-1)',
    color: 'var(--text-primary)'
  },
  searchResults: {
    padding: '12px 16px',
    fontSize: '13px',
    color: 'var(--text-secondary)'
  },
  propertyContent: {
    padding: '16px',
    overflowY: 'auto',
    flex: 1
  },
  propertyItem: {
    marginBottom: '16px'
  },
  propertyLabel: {
    fontSize: '11px',
    fontWeight: '600',
    color: 'var(--text-secondary)',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
    marginBottom: '4px'
  },
  propertyValue: {
    fontSize: '14px',
    color: 'var(--text-primary)',
    lineHeight: '1.5'
  },
  loadingOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    background: 'var(--overlay)',
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 100
  },
  errorPanel: {
    position: 'absolute',
    top: '50%',
    left: '50%',
    transform: 'translate(-50%, -50%)',
    background: 'var(--surface-1)',
    border: '2px solid var(--error)',
    borderRadius: '8px',
    padding: '20px',
    boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    zIndex: 100
  },
  contextMenu: {
    position: 'fixed',
    background: 'var(--surface-1)',
    borderRadius: '8px',
    boxShadow: '0 4px 20px rgba(0,0,0,0.15)',
    padding: '8px 0',
    zIndex: 1000,
    minWidth: '200px',
    border: '1px solid var(--border)'
  },
  contextMenuHeader: {
    padding: '8px 16px',
    fontSize: '13px',
    fontWeight: '600',
    color: 'var(--text-primary)',
    borderBottom: '1px solid var(--border)',
    marginBottom: '4px'
  },
  contextMenuItem: {
    width: '100%',
    padding: '10px 16px',
    background: 'transparent',
    border: 'none',
    textAlign: 'left',
    fontSize: '14px',
    color: 'var(--text-primary)',
    cursor: 'pointer',
    transition: 'background 0.2s',
    display: 'flex',
    alignItems: 'center',
    gap: '8px'
  },
  modalOverlay: {
    position: 'fixed',
    inset: 0,
    backgroundColor: 'var(--overlay)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 1500,
    padding: '16px'
  },
  modal: {
    width: '100%',
    maxWidth: '420px',
    backgroundColor: 'var(--surface-1)',
    borderRadius: '10px',
    boxShadow: '0 20px 45px rgba(0,0,0,0.25)',
    overflow: 'hidden',
    border: '1px solid var(--border)'
  },
  modalHeader: {
    padding: '14px 18px',
    borderBottom: '1px solid var(--border)',
    fontSize: '15px',
    fontWeight: 600,
    color: 'var(--text-primary)'
  },
  modalBody: {
    padding: '18px'
  },
  modalActions: {
    display: 'flex',
    justifyContent: 'flex-end',
    gap: '8px',
    padding: '12px 18px',
    borderTop: '1px solid var(--border)',
    backgroundColor: 'var(--surface-2)'
  },
  modalButton: {
    padding: '8px 16px',
    borderRadius: '6px',
    border: '1px solid var(--border)',
    backgroundColor: 'var(--surface-1)',
    color: 'var(--text-primary)',
    cursor: 'pointer',
    fontSize: '14px'
  },
  modalButtonPrimary: {
    padding: '8px 16px',
    borderRadius: '6px',
    border: '1px solid var(--accent)',
    backgroundColor: 'var(--accent)',
    color: 'var(--on-accent)',
    cursor: 'pointer',
    fontSize: '14px'
  },
  modalInput: {
    width: '100%',
    padding: '8px 10px',
    borderRadius: '6px',
    border: '1px solid var(--border)',
    backgroundColor: 'var(--surface-1)',
    color: 'var(--text-primary)',
    fontSize: '14px'
  },
  modalLinkButton: {
    marginTop: '12px',
    background: 'none',
    border: 'none',
    color: 'var(--accent)',
    fontSize: '13px',
    padding: 0,
    cursor: 'pointer',
    textDecoration: 'underline',
    alignSelf: 'flex-start'
  }
};

export default AdvancedGraphView;

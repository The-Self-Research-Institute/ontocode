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
  buildChildrenParentsIndex,
  toggleNodeExpansion as toggleExpansion,
  searchNodesWithPaths,
  expandAll as expandAllNodes,
  collapseAll as collapseAllNodes,
  expandSeedsOneLevel,
  collapseSeedsOneLevel,
  expandSeedsToDepth,
  initialGraphVisibility,
  smartInitialGraphVisibility,
  networkGraphVisibility,
  NETWORK_VISIBILITY_NODE_BUDGET,
  getExpansionStats,
  findPathToNode
} from './HierarchicalLazyLoading';
import { useIsDarkTheme } from './hooks/useIsDarkTheme';
import { usePrefersReducedMotion } from './hooks/usePrefersReducedMotion';
import { loadLastView, saveLastView, loadUiPrefs, saveUiPrefs, OntographLayoutType, VowlDisplayOptions, DEFAULT_VOWL_OPTIONS } from './viewMemory';
import { applyVowlTransform, isThingIri, vowlOriginalNodeId, buildVowlNeighborhoods, placeVowlNeighborhoods } from './vowlTransform';
import { GraphToolbar, RELATIONSHIP_VISIBILITY_CONTROLS } from './components/GraphToolbar';
import { WebGLGraphView, buildBenchmarkData, type WebGLCameraHandle } from './renderers/WebGLGraphView';
import { isWebGLAvailable } from './renderers/graphAdapter';

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

const LARGE_GRAPH_THRESHOLD = 800;

const BLOOM_MAX_VISIBLE = 300;
const CALM_MAX_VISIBLE = 1500;

const CAMERA_MS = 650;

const SMOOTH_WHEEL_ZOOM = true;

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

const ACCENT_COLORS: Record<string, string> = { ...NODE_ACCENTS };

const hexToRgba = (hex: string, alpha: number): string => {
  const c = d3.color(hex);
  if (!c) return `rgba(99,102,241,${alpha})`;
  const { r, g, b } = c.rgb();
  return `rgba(${r},${g},${b},${alpha})`;
};

const getReadableTextColor = (hex: string): string => {
  const clean = hex.replace('#', '');
  if (clean.length !== 6) return '#111111';
  const r = parseInt(clean.substring(0, 2), 16);
  const g = parseInt(clean.substring(2, 4), 16);
  const b = parseInt(clean.substring(4, 6), 16);
  const brightness = (r * 299 + g * 587 + b * 114) / 1000;
  return brightness > 140 ? '#111111' : '#ffffff';
};

if (typeof window !== 'undefined') {
  window.addEventListener('ontology:mutated', ((e: CustomEvent) => {
    try {
      const mutatedProjectId = e?.detail?.projectId;
      if (mutatedProjectId) {
        sessionStorage.removeItem(`ontocode:graphView:${mutatedProjectId}`);
      } else {

        Object.keys(sessionStorage)
          .filter((k) => k.startsWith('ontocode:graphView:'))
          .forEach((k) => sessionStorage.removeItem(k));
      }
    } catch {
      /* sessionStorage unavailable/full — non-fatal, next Refresh click still bypasses it */
    }
  }) as EventListener);
}

const DEFAULT_SETTINGS: GraphSettings = {
  layout: 'force',
  renderer: 'webgl',  // WebGL engine is the default view; silently falls back to SVG when unsupported
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

const buildDegreeMap = (edges: OntologyEdge[]): Map<string, number> => {
  const map = new Map<string, number>();
  for (const edge of edges) {
    map.set(edge.from, (map.get(edge.from) || 0) + 1);
    map.set(edge.to, (map.get(edge.to) || 0) + 1);
  }
  return map;
};

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

    if (candidate.startsWith('http_') || candidate.startsWith('https_')) {

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

  const isDarkTheme = useIsDarkTheme();
  const prefersReducedMotion = usePrefersReducedMotion();

  const svgRef = useRef<SVGSVGElement>(null);
  const gRef = useRef<SVGGElement>(null);
  const simulationRef = useRef<d3.Simulation<D3Node, D3Edge> | null>(null);
  const zoomRef = useRef<d3.ZoomBehavior<SVGSVGElement, unknown> | null>(null);
  const currentTransformRef = useRef<d3.ZoomTransform>(d3.zoomIdentity);
  const assertedGraphRef = useRef<{ nodes: OntologyNode[]; edges: OntologyEdge[] } | null>(null);
  const inferredGraphRef = useRef<{ nodes: OntologyNode[]; edges: OntologyEdge[] } | null>(null);

  const nodePositionsRef = useRef<Map<string, { x: number; y: number }>>(new Map());

  const vowlPinnedHubIdsRef = useRef<Set<string>>(new Set());

  const vowlHubFingerprintRef = useRef<string>('');

  const applyViewportCullingRef = useRef<() => void>(() => {});

  const uncullForExportRef = useRef<() => void>(() => {});

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

  useEffect(() => {
    nodePositionsRef.current.clear();
    vowlHubFingerprintRef.current = '';
    vowlPinnedHubIdsRef.current = new Set();
  }, [projectId]);

  useEffect(() => {
    setHierarchyRootNode(null);
    setShowHierarchyDialog(false);
  }, [projectId]);

  const [selectedNodes, setSelectedNodes] = useState<Set<string>>(new Set());
  const selectedNodesRef = useRef(selectedNodes);
  useEffect(() => {
    selectedNodesRef.current = selectedNodes;
  }, [selectedNodes]);

  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const [hoveredEdgeId, setHoveredEdgeId] = useState<string | null>(null);
  const [hoveredNode, setHoveredNode] = useState<string | null>(null);
  const [editMode, setEditMode] = useState(false);

  const editModeRef = useRef(editMode);
  useEffect(() => { editModeRef.current = editMode; }, [editMode]);
  const onNodeClickRef = useRef(onNodeClick);
  const onEdgeClickRef = useRef(onEdgeClick);
  useEffect(() => {
    onNodeClickRef.current = onNodeClick;
    onEdgeClickRef.current = onEdgeClick;
  }, [onNodeClick, onEdgeClick]);

  useEffect(() => {
    if (!gRef.current) return;
    d3.select(gRef.current).selectAll<SVGGElement, unknown>('.node')
      .style('cursor', editModeRef.current ? 'move' : 'pointer');
  }, [editMode]);

  const [showPropertyPanel, setShowPropertyPanel] = useState(() => loadUiPrefs()?.showPropertyPanel ?? false);
  const [selectedNodeInfo, setSelectedNodeInfo] = useState<OntologyNode | null>(null);

  const initialViewMemory = useMemo(() => loadLastView(projectId), [projectId]);
  const isFirstEverOpen = initialViewMemory === null;

  const [visualizationType, setVisualizationType] = useState<VisualizationType>(
    () => initialViewMemory?.visualizationType ?? 'vowl'
  );
  const [ontographLayoutType, setOntographLayoutType] = useState<OntographLayoutType>(
    () => initialViewMemory?.ontographLayoutType ?? 'tree'
  );
  const [showLegend, setShowLegend] = useState(() => loadUiPrefs()?.showLegend ?? true);

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

  const entranceRef = useRef<{ phase: 'pending' | 'playing' | 'done'; mode: 'bloom' | 'calm' | 'static' }>({
    phase: 'pending',
    mode: 'static'
  });
  const [entrancePhase, setEntrancePhase] = useState<'pending' | 'playing' | 'done'>('pending');
  const userInteractedRef = useRef(false);

  const pendingToggleFrameRef = useRef<string | null>(null);

  const hasSeenGraphRef = useRef(!isFirstEverOpen);

  useEffect(() => {
    if (!hasSeenGraphRef.current) {
      const initialType = initialViewMemory?.visualizationType ?? 'vowl';
      const initialLayout = initialViewMemory?.ontographLayoutType ?? 'tree';
      if (visualizationType === initialType && ontographLayoutType === initialLayout) return;
      hasSeenGraphRef.current = true;
    }
    saveLastView(projectId, { visualizationType, ontographLayoutType });
  }, [projectId, visualizationType, ontographLayoutType, initialViewMemory]);

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

  const [showSaveViewPrompt, setShowSaveViewPrompt] = useState(false);
  const [saveViewNameDraft, setSaveViewNameDraft] = useState('');

  const [showHierarchyDialog, setShowHierarchyDialog] = useState(false);
  const [hierarchyDialogPosition, setHierarchyDialogPosition] = useState({ x: 100, y: 100 });
  const [hierarchyDialogSize, setHierarchyDialogSize] = useState({ width: 360, height: 480 });
  const [hierarchyRootNode, setHierarchyRootNode] = useState<OntologyNode | null>(null);
  const [isDialogMinimized, setIsDialogMinimized] = useState(false);

  const [svgHoverCard, setSvgHoverCard] = useState<{
    id: string;
    label: string;
    type: string;
    x: number;
    y: number;
  } | null>(null);
  const svgHoverClearTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [svgRenaming, setSvgRenaming] = useState<{ id: string; value: string } | null>(null);
  const svgRenamingRef = useRef(svgRenaming);
  svgRenamingRef.current = svgRenaming;

  const [contextMenu, setContextMenu] = useState<{
    visible: boolean;
    x: number;
    y: number;
    nodeId: string | null;
  }>({ visible: false, x: 0, y: 0, nodeId: null });

  const viewportBoundsRef = useRef({ x: 0, y: 0, width: 0, height: 0, scale: 1 });

  const [settings, setSettings] = useState<GraphSettings>(DEFAULT_SETTINGS);

  const webglSupported = useMemo(() => isWebGLAvailable(), []);
  const webglActive = webglSupported && (
    settings.renderer === 'webgl' ||
    (typeof window !== 'undefined' && new URLSearchParams(window.location.search).get('webgl') === '1')
  );
  const [webglBannerDismissed, setWebglBannerDismissed] = useState(false);
  const webglCamRef = useRef<WebGLCameraHandle>(null);

  const [viewportFitToken, setViewportFitToken] = useState(0);
  const requestViewportFitAfterBulkExpand = useCallback(() => {
    setViewportFitToken((t) => t + 1);
  }, []);

  const benchData = useMemo(() => {
    if (typeof window === 'undefined') return null;
    const n = parseInt(new URLSearchParams(window.location.search).get('bench') || '', 10);
    return Number.isFinite(n) && n > 0 ? buildBenchmarkData(Math.min(n, 100000)) : null;
  }, []);
  const [filters, setFilters] = useState<GraphFilters>(DEFAULT_FILTERS);
  const [searchQuery, setSearchQuery] = useState('');

  const [searchFilterDepth, setSearchFilterDepth] = useState(5);

  const [searchFilterMode, setSearchFilterMode] = useState<'dim' | 'hide'>('hide');

  const [searchFocusIds, setSearchFocusIds] = useState<Set<string>>(() => new Set());
  const preFilterVisibilityRef = useRef<{ visible: Set<string>; expanded: Set<string> } | null>(null);

  const [searchActionHint, setSearchActionHint] = useState<string | null>(null);
  const searchActionHintTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const flashSearchHint = useCallback((msg: string) => {
    setSearchActionHint(msg);
    if (searchActionHintTimerRef.current) clearTimeout(searchActionHintTimerRef.current);
    searchActionHintTimerRef.current = setTimeout(() => setSearchActionHint(null), 2200);
  }, []);
  const [sidebarSearchTerm, setSidebarSearchTerm] = useState('');

  const [classDistance, setClassDistance] = useState(170);
  const [datatypeDistance, setDatatypeDistance] = useState(85);
  const [isLayoutPaused, setIsLayoutPaused] = useState(false);

  const [showSearch, setShowSearch] = useState(false);
  const [showFilters, setShowFilters] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showGrid, setShowGrid] = useState(false);

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

  const [focusedNodeId, setFocusedNodeId] = useState<string | null>(null);
  const [focusParentDepth, setFocusParentDepth] = useState<number>(2);
  const [focusChildDepth, setFocusChildDepth] = useState<number>(2);
  const [focusIncludeProperties, setFocusIncludeProperties] = useState<boolean>(true);
  const [focusIncludeIndividuals, setFocusIncludeIndividuals] = useState<boolean>(false);

  const [showAnalytics, setShowAnalytics] = useState(false);
  const [colorByCluster, setColorByCluster] = useState(false);
  const [sizeByInfluence, setSizeByInfluence] = useState(true);
  const [centralityThreshold, setCentralityThreshold] = useState(100);

  const focusedNodeIds = useMemo<Set<string> | null>(() => {
    if (!focusedNodeId) return null;
    const result = new Set<string>([focusedNodeId]);

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

    if (focusIncludeIndividuals) {
      for (const e of allEdges) {
        if (e.type === 'instanceOf' && result.has(e.to)) {
          result.add(e.from);
        }
      }
    }

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

  const visibleNodes = useMemo(() => {

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

    if (focusedNodeIds) {
      const edges = allEdges.filter(e => focusedNodeIds.has(e.from) && focusedNodeIds.has(e.to));
      console.log('[AdvancedGraphView] 🎯 FOCUS MODE edges:', edges.length);
      return edges;
    }
    if (visibleNodeIds.size === 0) return [];
    if (visibleNodeIds.size === allNodes.length) return allEdges; // All visible

    const edges = allEdges.filter(e =>
      visibleNodeIds.has(e.from) && visibleNodeIds.has(e.to)
    );

    graphLog('[AdvancedGraphView] Visible edges:', edges.length, 'from total:', allEdges.length);

    if (GRAPH_DEBUG) {
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
    }

    return edges;
  }, [allEdges, visibleNodeIds, allNodes.length, allNodes, focusedNodeIds]);

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

    if (visualizationType === 'vowl' || visualizationType === 'ontograph') {
      const beforeFilter = filtered.length;

      if (visualizationType === 'vowl') {
        filtered = filtered.filter(node =>
          node.type === 'class' ||
          node.type === 'individual' ||
          node.type === 'datatype' ||
          (node.type === 'setOperator' && vowlOptions.showSetOperators)
        );
      }

      filtered = filtered.filter(node => {

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

    if (visualizationType === 'force') {
      filtered = filtered.filter(node =>
        node.type === 'class' ||
        node.type === 'individual' ||
        node.type === 'datatype'
      );
      if (GRAPH_DEBUG) console.log(`[Filtering] Force mode: Filtered to classes, individuals, datatypes - ${filtered.length} nodes`);
    }

    if (visualizationType !== 'vowl') {
      const nonVowlConnected = new Set<string>();
      for (const e of allEdges) {
        if (e.metadata?.vowlOnly) continue;
        nonVowlConnected.add(e.from);
        nonVowlConnected.add(e.to);
      }
      filtered = filtered.filter(node => node.type !== 'datatype' || nonVowlConnected.has(node.id));
    }

    if (visualizationType === 'ontograph') {
      const beforeFilter = filtered.length;
      filtered = filtered.filter(node => node.type === 'class' || node.type === 'individual');
      if (GRAPH_DEBUG) console.log(`[Filtering] OntoGraph mode: Focused on classes and individuals ${beforeFilter} -> ${filtered.length}`);
    }

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

      !(edge.metadata?.vowlOnly && visualizationType !== 'vowl') &&
      (assertionView === 'all' ||
        (assertionView === 'inferred' ? isInferredEntity(edge) : !isInferredEntity(edge)))
    );

    if (visualizationType === 'vowl' || visualizationType === 'ontograph') {
      const beforeFilter = filtered.length;

      if (visualizationType === 'vowl') {
        filtered = filtered.filter(edge => 
          edge.type !== 'domain' && edge.type !== 'range'
        );
      }

      filtered = filtered.filter(edge => {
        if (edge.type === 'subClassOf') {
          return vowlFilters.showSubClassOf;
        }
        if (edge.type === 'propertyRelation') {

          const isFunctional = isFunctionalEdge(edge);

          if (isFunctional && !vowlFilters.showFunctionalProperties) {
            return false;
          }

          const sourceNode = allNodes.find(n => n.id === edge.from);
          const targetNode = allNodes.find(n => n.id === edge.to);

          if (sourceNode?.type === 'annotation') {
            return true; // Always show annotation properties when enabled
          }

          if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') {
            return vowlFilters.showDataProperties;
          }

          if (sourceNode?.type === 'objectProperty') {
            return vowlFilters.showObjectProperties;
          }

          return vowlFilters.showObjectProperties;
        }

        if (edge.type === 'domain' || edge.type === 'range') {
          const sourceNode = allNodes.find(n => n.id === edge.from);

          if (sourceNode?.type === 'objectProperty') {
            return vowlFilters.showObjectProperties;
          }

          if (sourceNode?.type === 'dataProperty') {
            return vowlFilters.showDataProperties;
          }

          if (sourceNode?.type === 'annotation') {
            return true;
          }

          return visualizationType !== 'vowl';
        }

        return true;
      });

      console.log(`[Filtering] ${visualizationType} mode: Filtered ${beforeFilter} -> ${filtered.length} edges`);
    }

    if (visualizationType === 'force') {
      const beforeFilter = filtered.length;
      filtered = filtered.filter(edge => {
        if (edge.type === 'propertyRelation') {

          const sourceNode = allNodes.find(n => n.id === edge.from);
          const targetNode = allNodes.find(n => n.id === edge.to);

          if (sourceNode?.type === 'annotation') {
            return filters.nodeTypes.has('annotation');
          }

          if (sourceNode?.type === 'dataProperty' || targetNode?.type === 'datatype') {
            return filters.nodeTypes.has('dataProperty');
          }

          if (sourceNode?.type === 'objectProperty') {
            return filters.nodeTypes.has('objectProperty');
          }

          return true;
        }
        return true;
      });

      console.log(`[Filtering] ${visualizationType} mode: Filtered property edges ${beforeFilter} -> ${filtered.length}`);
    }

    if (visualizationType === 'ontograph') {
      const beforeFilter = filtered.length;

      const ontographRelationshipTypes = new Set(
        RELATIONSHIP_VISIBILITY_CONTROLS.flatMap(control => control.edgeTypes)
      );
      filtered = filtered.filter(edge => {
        return ontographRelationshipTypes.has(edge.type);
      });
      console.log(`[Filtering] OntoGraph mode: Applied relationship visibility ${beforeFilter} -> ${filtered.length}`);
    }

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

  const vowlChipDensityOk = useMemo(() =>
    visualizationType === 'vowl' &&
    filteredEdges.filter(e => e.type === 'propertyRelation' || e.type === 'subClassOf').length <= 400,
  [visualizationType, filteredEdges]);

  const dynamicLegend = useMemo(() => {
    console.log('[Legend] Computing dynamic legend - Mode:', visualizationType, 'Filtered nodes:', filteredNodes.length, 'Filtered edges:', filteredEdges.length);

    if (visualizationType === 'vowl') {

      const legend: Array<{ name: string; type: string; nodeType?: string; color?: string; stroke?: string; strokeDasharray?: string }> = [];

      const nodeTypes = new Set(filteredNodes.map(n => n.type));
      const edgeTypes = new Set(filteredEdges.map(e => e.type));

      console.log('[Legend] VOWL - Node types:', Array.from(nodeTypes), 'Edge types:', Array.from(edgeTypes));

      const hasThing = filteredNodes.some(n => n.label === 'Thing' || n.id === 'owl:Thing' || n.id.includes('owl#Thing'));
      const hasClass = nodeTypes.has('class') && filteredNodes.some(n => n.type === 'class' && n.label !== 'Thing');
      const hasDatatype = nodeTypes.has('datatype');
      const hasIndividual = nodeTypes.has('individual');

      const externalClasses = filteredNodes.filter(n => 
        n.type === 'class' && isExternalNode(n)
      );
      const internalClasses = filteredNodes.filter(n => 
        n.type === 'class' && !isExternalNode(n) && n.label !== 'Thing'
      );

      const isDark = false;

      if (hasThing) legend.push({ name: 'Thing', type: 'node', nodeType: 'class', color: isDark ? '#374151' : '#ffffff' });
      if (externalClasses.length > 0) legend.push({ name: `External Class (${externalClasses.length})`, type: 'node', nodeType: 'class', color: isDark ? '#60a5fa' : '#36c' });
      if (internalClasses.length > 0) legend.push({ name: `Internal Class (${internalClasses.length})`, type: 'node', nodeType: 'class', color: isDark ? '#6b92c4' : '#69c' });
      if (hasDatatype) legend.push({ name: `Datatype (${filteredNodes.filter(n => n.type === 'datatype').length})`, type: 'node', nodeType: 'datatype', color: isDark ? '#d97706' : '#fc3' });
      if (hasIndividual) legend.push({ name: `Individual (${filteredNodes.filter(n => n.type === 'individual').length})`, type: 'node', nodeType: 'individual', color: isDark ? '#fbb6ce' : '#cfc' });

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

        if (objProps > 0) legend.push({ name: `Object Property (${objProps})`, type: 'edge', stroke: isDark ? '#60a5fa' : '#69c', strokeDasharray: '0' });
        if (dataProps > 0) legend.push({ name: `Data Property (${dataProps})`, type: 'edge', stroke: isDark ? '#a3e635' : '#99cc66', strokeDasharray: '0' });
        if (annoProps > 0) legend.push({ name: `Annotation Property (${annoProps})`, type: 'edge', stroke: isDark ? '#c4b5fd' : '#a78bfa', strokeDasharray: '0' });
      }

      if (edgeTypes.has('subClassOf')) legend.push({ name: `SubClass Of (${filteredEdges.filter(e => e.type === 'subClassOf').length})`, type: 'edge', stroke: isDark ? '#9ca3af' : '#000000', strokeDasharray: '5,5' });

      if (edgeTypes.has('restriction')) {
        legend.push({
          name: `Restriction ∃ ∀ ∋ ≥ ≤ = (${filteredEdges.filter(e => e.type === 'restriction').length})`,
          type: 'edge',
          stroke: '#d97706',
          strokeDasharray: '0',
        });
      }

      if (edgeTypes.has('propertyRelation')) {
        legend.push({ name: 'Characteristics suffix (F, IF, S, T…)', type: 'label', color: isDark ? '#374151' : '#e5e7eb' });
      }

      return legend;
    } else {

      const legend: Array<{ name: string; type: string; nodeType?: string; color?: string; stroke?: string; strokeDasharray?: string }> = [];

      const nodeTypes = new Set(filteredNodes.map(n => n.type));

      if (visualizationType === 'force') {
        if (nodeTypes.has('class')) legend.push({ name: `Class (${filteredNodes.filter(n => n.type === 'class').length})`, type: 'node', nodeType: 'class', color: nodeFill('class', isDarkTheme) });
        if (nodeTypes.has('individual')) legend.push({ name: `Individual (${filteredNodes.filter(n => n.type === 'individual').length})`, type: 'node', nodeType: 'individual', color: '#a78bfa' });
        if (nodeTypes.has('datatype')) legend.push({ name: `Datatype (${filteredNodes.filter(n => n.type === 'datatype').length})`, type: 'node', nodeType: 'datatype', color: '#FFFFFF' });
      } else {

        if (nodeTypes.has('class')) legend.push({ name: `Class (${filteredNodes.filter(n => n.type === 'class').length})`, type: 'node', nodeType: 'class', color: '#FFF9C4' });
        if (nodeTypes.has('individual')) legend.push({ name: `Individual (${filteredNodes.filter(n => n.type === 'individual').length})`, type: 'node', nodeType: 'individual', color: '#E1F5FE' });
      }

      const edgeTypes = new Set(filteredEdges.map(e => e.type));
      if (edgeTypes.has('subClassOf')) legend.push({ name: 'SubClass Of', type: 'edge', stroke: '#1976D2', strokeDasharray: '0' });
      if (edgeTypes.has('propertyRelation')) legend.push({ name: 'Property Relation', type: 'edge', stroke: '#059669', strokeDasharray: '0' });
      if (filteredNodes.some(isInferredEntity) || filteredEdges.some(isInferredEntity)) {
        legend.push({ name: 'Inferred', type: 'edge', stroke: '#10b981', strokeDasharray: '8 4' });
      }

      return legend;
    }
  }, [visualizationType, filteredNodes, filteredEdges, isExternalNode, allNodes, isDarkTheme]);

  const renderTime = useRef(0);

  const fetchGraphData = useCallback(async (opts?: { bypassCache?: boolean }) => {

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

  useEffect(() => {
    if (!svgRef.current || filteredNodes.length === 0) return;

    const startTime = performance.now();

    const vowlGraph = visualizationType === 'vowl'
      ? applyVowlTransform(filteredNodes, filteredEdges, {
          mergeEquivalents: vowlOptions.mergeEquivalents
        })
      : null;
    const renderNodes = vowlGraph ? vowlGraph.nodes : filteredNodes;
    const renderEdges = vowlGraph ? vowlGraph.edges : filteredEdges;

    const effectiveDark = visualizationType === 'vowl' ? false : isDarkTheme;

    const isLargeGraph = renderNodes.length > LARGE_GRAPH_THRESHOLD;

    if (GRAPH_DEBUG) {
      console.log('[AdvancedGraphView D3] 🎨 Initializing D3 visualization');
      console.log('[AdvancedGraphView D3] 📊 Nodes:', filteredNodes.length, 'Edges:', filteredEdges.length);

      const edgesByType: Record<string, number> = {};
      filteredEdges.forEach(e => {
        edgesByType[e.type] = (edgesByType[e.type] || 0) + 1;
      });
      console.log('[AdvancedGraphView D3] 🔗 Edges by type:', edgesByType);

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

    g.selectAll('*').remove();

    let defs = svg.select<SVGDefsElement>('defs');
    if (defs.empty()) {
      defs = svg.append('defs');
    }

    defs.selectAll('marker').remove();

    const isDark = effectiveDark;

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

    Object.entries(EDGE_TYPE_COLORS).forEach(([type, color]) => {

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

    Object.entries(EDGE_TYPE_COLORS).forEach(([type, color]) => {

      let forceColor = color;
      if (type === 'subClassOf') forceColor = isDark ? '#fbbf24' : '#FFA500';
      else if (type === 'instanceOf') forceColor = isDark ? '#cbd5e1' : '#000000';
      else if (type === 'propertyRelation') forceColor = isDark ? '#cbd5e1' : '#000000';
      else if (type === 'domain') forceColor = '#666666';
      else if (type === 'range') forceColor = '#666666';

      appendArrowMarker(`arrow-${type}`, forceColor, type === 'subClassOf' || type === 'subPropertyOf');
    });

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

    defs.selectAll('.premium-def').remove();

    const cardGrad = defs.append('linearGradient')
      .attr('class', 'premium-def')
      .attr('id', 'card-grad')
      .attr('x1', '0').attr('y1', '0').attr('x2', '0').attr('y2', '1');
    cardGrad.append('stop').attr('offset', '0%').attr('stop-color', isDark ? '#2a3850' : '#ffffff');
    cardGrad.append('stop').attr('offset', '100%').attr('stop-color', isDark ? '#1b2436' : '#eef2f9');

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

    const savedPositions = nodePositionsRef.current;
    const hasSavedPositions = savedPositions.size > 0;
    const d3Nodes: D3Node[] = renderNodes.map((node, index) => {
      let baseSize = influenceSize(node.id, node.size || settings.nodeSize);

      if (visualizationType === 'vowl') {
        const isThingNode = isThingIri(node.id) || node.label === 'Thing';
        if (isThingNode) {
          baseSize = Math.max(10, Math.round(settings.nodeSize * 0.72));
        } else if (node.type === 'class') {

          const charWidthPx = vowlOptions.labelFontSize * (7 / 11);
          const avgScale = (vowlOptions.nodeWidthScale + vowlOptions.nodeHeightScale) / 2;

          const longestWord = (node.label || '').split(/[\s_]+/)
            .reduce((m, w) => Math.max(m, w.length), 0);
          const visibleChars = Math.min(Math.max(longestWord, 6), 14);
          const desiredDiameter = (visibleChars * charWidthPx + 8) / 0.86;
          const fittedSize = desiredDiameter / (2.7 * avgScale);
          const minSize = Math.round(settings.nodeSize * 1.15);

          const maxSize = Math.round(settings.nodeSize * 2.9);
          baseSize = Math.max(minSize, Math.min(Math.round(fittedSize), maxSize));
        } else if (node.type === 'datatype') {
          baseSize = Math.round(settings.nodeSize * 1.05);
        } else if (node.type === 'individual') {
          baseSize = Math.round(settings.nodeSize * 1.05);
        }
      }
      const sizedNode = { ...node, size: baseSize };

      const saved = savedPositions.get(node.id);
      if (saved) {
        return { ...sizedNode, x: saved.x, y: saved.y };
      }

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

      vowlLayout = computeVowlLayout(d3Nodes, width, height, classDistance, datatypeDistance);
    }

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

          positionMap = new Map();
          break;
        case 'cluster':

          positionMap = layouts.applyClusterLayout(filteredNodes, filteredEdges, {
            width,
            height,
            communities: graphAnalytics.communities
          });
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

      const refinedMap = (isLarge || ontographLayoutType === 'grid' || ontographLayoutType === 'radial' || ontographLayoutType === 'tree' || ontographLayoutType === 'horizontal' || ontographLayoutType === 'spring' || ontographLayoutType === 'cluster')
        ? positionMap 
        : layouts.refineOntoGraphLayout(positionMap, filteredNodes, filteredEdges, 30);

      d3Nodes.forEach(node => {
        const pos = refinedMap.get(node.id);
        if (pos) {
          node.x = pos.x;
          node.y = pos.y;

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

    const nodeHubId = new Map<string, string>();
    vowlPinnedHubIdsRef.current = new Set();
    if (visualizationType === 'vowl') {
      const neighborhoods = buildVowlNeighborhoods(renderNodes, renderEdges);
      for (const nb of neighborhoods) {
        for (const id of nb.memberIds) nodeHubId.set(id, nb.hubId);
      }
      const fingerprint = `v28-compact-webvowl|${neighborhoods.map(n => n.hubId).sort().join('|')}`;
      const hubsChanged = fingerprint !== vowlHubFingerprintRef.current;
      if (hubsChanged) {
        vowlHubFingerprintRef.current = fingerprint;
        nodePositionsRef.current.clear();
      }
      if (hubsChanged || !hasSavedPositions) {

        const LARGE_SEED_THRESHOLD = 500;
        let seeded: Map<string, { x: number; y: number }>;
        if (renderNodes.length > LARGE_SEED_THRESHOLD) {
          seeded = new Map();
          const golden = Math.PI * (3 - Math.sqrt(5));
          const hubSpacing = 120 + Math.sqrt(neighborhoods.length) * 30;
          neighborhoods.forEach((nb, i) => {
            const r = hubSpacing * Math.sqrt(i + 0.5);
            const a = i * golden;
            const hx = width / 2 + Math.cos(a) * r;
            const hy = height / 2 + Math.sin(a) * r;
            seeded.set(nb.hubId, { x: hx, y: hy });
            const members = nb.memberIds.filter(id => id !== nb.hubId);
            members.forEach((id, j) => {
              const ma = (j / Math.max(1, members.length)) * Math.PI * 2;
              const mr = 70 + 26 * Math.sqrt(j);
              seeded.set(id, { x: hx + Math.cos(ma) * mr, y: hy + Math.sin(ma) * mr });
            });
          });
        } else {
          seeded = placeVowlNeighborhoods(neighborhoods, width, height, renderNodes, renderEdges);
        }
        d3Nodes.forEach(node => {
          const p = seeded.get(node.id);
          if (p) {
            node.x = p.x;
            node.y = p.y;
            nodePositionsRef.current.set(node.id, { x: p.x, y: p.y });
          }
        });
      } else {

        d3Nodes.forEach(node => {
          const saved = nodePositionsRef.current.get(node.id);
          if (!saved) return;
          node.x = saved.x;
          node.y = saved.y;
        });
      }
      if (GRAPH_DEBUG) {
        console.log(`[VOWL+] ${hubsChanged || !hasSavedPositions ? 'Seeded' : 'Kept'} ${neighborhoods.length} neighborhoods:`,
          neighborhoods.map(n => `${n.hubId.split(/[#/]/).pop()}(${n.memberIds.length})`).join(', '));
      }
    }

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

    const isVowlChipEdge = (d: any) => d.type === 'propertyRelation' || d.type === 'subClassOf';
    const vowlChipsAlwaysVisible = visualizationType === 'vowl'
      && d3Edges.filter(isVowlChipEdge).length <= 600;

    const chipNodes: D3Node[] = [];
    if (visualizationType === 'vowl' && vowlChipsAlwaysVisible) {

      const pairBuckets = new Map<string, D3Edge[]>();
      d3Edges.forEach(e => {
        if (!isVowlChipEdge(e)) return;
        const s = e.source as D3Node;
        const t = e.target as D3Node;
        const key = [s.id, t.id].sort().join('|');
        if (!pairBuckets.has(key)) pairBuckets.set(key, []);
        pairBuckets.get(key)!.push(e);
      });

      const hubSpokeCount = new Map<string, number>();

      pairBuckets.forEach(group => {
        const n = group.length;
        group.forEach((e, index) => {
          const s = e.source as D3Node;
          const t = e.target as D3Node;
          const chipId = `__vowlchip__${e.id}`;
          const labelLen = String(e.label || (e.type === 'subClassOf' ? 'Subclass of' : '')).length;

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

            tAlong = lo + (hi - lo) * (spokeIdx % 2 === 0 ? 0.28 : 0.72);
            side = (spokeIdx % 2 === 0 ? 1 : -1) * Math.max(18, chipRadius * 0.35);
          }

          const cx0 = sx + dx * tAlong + px * side;
          const cy0 = sy + dy * tAlong + py * side;

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

            if (overlapX < overlapY) {
              const sx = (dx === 0 ? (i % 2 === 0 ? 1 : -1) : Math.sign(dx)) * (overlapX / 2);
              a.x = ax - sx;
              b.x = bx + sx;
            } else {
              const sy = (dy === 0 ? (i % 2 === 0 ? 1 : -1) : Math.sign(dy)) * (overlapY / 2);
              a.y = ay - sy;
              b.y = by + sy;
            }

            moved = true;
          }
        }
        if (!moved) break;
      }

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

    const nodeRadiusFor = (n: D3Node): number => {
      const size = n.size || settings.nodeSize || 20;
      if ((n as any).__isChip) return (n as any).__chipRadius || size;
      if (n.type === 'datatype' || n.label === 'Literal') return size * 1.65;

      const avgScale = (vowlOptions.nodeWidthScale + vowlOptions.nodeHeightScale) / 2;
      return size * 1.35 * avgScale + 4;
    };

    const linkDistance = (edge: D3Edge): number => {

      const half = (edge as any).__halfOf as D3Edge | undefined;
      if (half) {
        const s = edge.source as D3Node;
        const t = edge.target as D3Node;

        const radForDist = (n: D3Node) => ((n as any).__isChip ? 14 : nodeRadiusFor(n));
        return linkDistance(half) / 2 + radForDist(s) + radForDist(t);
      }
      const source = edge.source as D3Node;
      const target = edge.target as D3Node;

      if (visualizationType === 'vowl') {
        const kC = Math.max(0.4, Math.min(2, classDistance / 100));
        const kD = Math.max(0.4, Math.min(2, datatypeDistance / 100));
        if (source.type === 'datatype' || target.type === 'datatype' ||
            source.type === 'dataProperty' || target.type === 'dataProperty' ||
            edge.type === 'domain' || edge.type === 'range') return 120 * kD;
        return 200 * kC;
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

    const linkStrength = (edge: D3Edge): number => {

      if (visualizationType === 'vowl') return 1.0;
      const half = (edge as any).__halfOf as D3Edge | undefined;
      if (half) {
        return Math.min(1.0, linkStrength(half) * 1.35);
      }
      if (edge.type === 'subClassOf') {
        return 0.85;
      }
      if (edge.type === 'propertyRelation') {
        const source = edge.source as D3Node;
        const target = edge.target as D3Node;

        if (source?.type === 'datatype' || target?.type === 'datatype') {
          return 0.95;
        }
        return nodeCount > 100 ? 0.4 : 0.6;
      }
      return nodeCount > 100 ? 0.3 : 0.5;
    };

    const avgNodeSize = settings.nodeSize || 20;

    const usePhysics =
      settings.physics &&
      (visualizationType !== 'ontograph' || ontographLayoutType === 'spring');

    if (entranceRef.current.phase === 'pending') {
      let entranceMode: 'bloom' | 'calm' | 'static' = 'static';
      if (isFirstEverOpen && usePhysics && !prefersReducedMotion && !isLayoutPaused && !hasSavedPositions) {
        if (nodeCount <= BLOOM_MAX_VISIBLE) entranceMode = 'bloom';
        else if (nodeCount <= CALM_MAX_VISIBLE) entranceMode = 'calm';
      }
      entranceRef.current = { phase: entranceMode === 'static' ? 'done' : 'playing', mode: entranceMode };
      setEntrancePhase(entranceRef.current.phase);
      if (entranceMode === 'static' && isFirstEverOpen) {

        hasSeenGraphRef.current = true;
        saveLastView(projectId, { visualizationType, ontographLayoutType });
      }
    }
    const entrancePlaying = entranceRef.current.phase === 'playing' && !hasSavedPositions;

    const newNodeRatio = hasSavedPositions
      ? d3Nodes.filter(n => !savedPositions.has(n.id)).length / Math.max(1, d3Nodes.length)
      : 1;
    const bigExpansion = visualizationType === 'vowl' && hasSavedPositions && newNodeRatio > 0.3;

    if (visualizationType === 'vowl' && usePhysics && (!hasSavedPositions || bigExpansion)) {
      const placed = simNodes.filter(n => n.x != null && n.y != null);
      if (placed.length > 2) {
        const cx = placed.reduce((s, n) => s + n.x!, 0) / placed.length;
        const cy = placed.reduce((s, n) => s + n.y!, 0) / placed.length;
        let maxR = 0;
        for (const n of placed) maxR = Math.max(maxR, Math.hypot(n.x! - cx, n.y! - cy));
        const targetR = Math.max(300, Math.sqrt(placed.length) * 45);
        if (maxR > targetR) {
          const k = targetR / maxR;
          for (const n of placed) {
            n.x = cx + (n.x! - cx) * k;
            n.y = cy + (n.y! - cy) * k;
          }
        }

        for (const n of placed) {
          n.x! += (hashToUnit(`${n.id}:jx`) - 0.5) * 14;
          n.y! += (hashToUnit(`${n.id}:jy`) - 0.5) * 14;
        }
      }
    }

    const simulation = d3.forceSimulation<D3Node>(simNodes)
      .force('link', usePhysics && visualizationType !== 'vowl' ? d3.forceLink<D3Node, D3Edge>(simLinks)
        .id(d => d.id)
        .distance(linkDistance)
        .strength(linkStrength)
        .iterations(nodeCount > 2000 ? 1 : 4) : null)
      .force('charge', usePhysics && visualizationType !== 'vowl' ? d3.forceManyBody()
        .strength(d => {
          const node = d as D3Node;

          if ((node as any).__isChip) {
            return -Math.min(180, ((node as any).__chipRadius || 20) * 1.8);
          }

          if (visualizationType === 'force') {

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

          if (node.type === 'class') {
            return nodeCount > 100 ? -1000 : -1500;
          }
          return nodeCount > 100 ? -500 : -800;
        })
        .distanceMax(nodeCount > 10000 ? 800 : 1200)
        .theta(nodeCount > 50000 ? 0.9 : 0.7) : null)
      .force('center', usePhysics && visualizationType !== 'vowl'
        ? d3.forceCenter(width / 2, height / 2).strength(visualizationType === 'spatial3d' ? 0.018 : 0.03)
        : null)
      .force('collision', usePhysics && visualizationType !== 'vowl' ? d3.forceCollide()
        .radius(d => {
          const node = d as D3Node;

          if ((node as any).__isChip) return ((node as any).__chipRadius || 28) * 1.35;
          const size = node.size || avgNodeSize;
          const isVeryLargeGraph = nodeCount > 10000;

          if (visualizationType === 'force') {

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
        .iterations(nodeCount > 2000 ? 3 : 6) : null)
      .force('y', usePhysics && visualizationType !== 'vowl' ? d3.forceY(d => {
        const node = d as D3Node;
        if (visualizationType === 'spatial3d') {
          return height / 2 + ((node.z || 0) * 0.08);
        }

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
      // VOWL: alphaDecay 0.01 + alphaMin 0.05 ≡ v3's alpha 0.1 × 0.99/tick
      // stopping at 0.005 over ~300 ticks (v3alpha = v7alpha × 0.1).
      .alphaDecay(usePhysics ? (nodeCount > 2000 ? 0.03 : (visualizationType === 'vowl' ? 0.01 : (visualizationType === 'spatial3d' ? 0.025 : 0.03))) : 1)
      // VOWL integration is owned by the d3 v3 port below (velocities zeroed)
      .velocityDecay(usePhysics ? (visualizationType === 'spatial3d' ? 0.58 : 0.5) : 0.8)
      .alpha(isLayoutPaused || !usePhysics ? 0 : (hasSavedPositions ? 0.15 : 1.0))
      .alphaMin(visualizationType === 'vowl' ? 0.05 : (nodeCount > 2000 ? 0.005 : 0.001))
      .alphaTarget(0);

    if (visualizationType === 'vowl' && usePhysics) {
      const cx = width / 2;
      const cy = height / 2;

      const mainCenter = { x: cx, y: cy };

      const friction = 0.9;
      const theta2 = 0.64; // v3 default theta 0.8
      const chargeDist2 = nodeCount > 1500 ? 1000 * 1000 : Infinity;
      const chargeFor = (n: any): number =>
        n.__isChip ? -400 : (nodeCount > 1500 ? -350 : -500); // labels ×0.8 of -500
      type V3 = { px: number; py: number; weight: number };
      const v3 = new Map<D3Node, V3>();
      simNodes.forEach(n => v3.set(n, { px: n.x ?? cx, py: n.y ?? cy, weight: 0 }));
      simLinks.forEach((l: any) => {
        const s = v3.get(l.source as D3Node);
        const t = v3.get(l.target as D3Node);
        if (s) s.weight++;
        if (t) t.weight++;
      });
      const restLen = simLinks.map((l: any) => linkDistance(l));

      simulation.force('vowlGravity', (v7alpha: number) => {

        const alpha = v7alpha * 0.1;

        for (let i = 0; i < simLinks.length; i++) {
          const o: any = simLinks[i];
          const s = o.source as D3Node;
          const t = o.target as D3Node;
          const sSt = v3.get(s);
          const tSt = v3.get(t);
          if (!sSt || !tSt || s.x == null || t.x == null) continue;
          let x = t.x! - s.x!;
          let y = t.y! - s.y!;
          let l = x * x + y * y;
          if (l) {
            l = Math.sqrt(l);
            const f = (alpha * (l - restLen[i])) / l;
            x *= f;
            y *= f;
            let k = sSt.weight / (tSt.weight + sSt.weight);
            t.x! -= x * k;
            t.y! -= y * k;
            k = 1 - k;
            s.x! += x * k;
            s.y! += y * k;
          }
        }

        const gk = alpha * (nodeCount > 1500 ? 0.045 : 0.025);
        for (const node of simNodes) {
          if (node.x == null || node.y == null) continue;
          node.x += (mainCenter.x - node.x) * gk;
          node.y += (mainCenter.y - node.y) * gk;
        }

        const q = d3.quadtree(simNodes as any[], (d: any) => d.x, (d: any) => d.y);
        q.visitAfter((quad: any) => {
          let charge = 0;
          let qx = 0;
          let qy = 0;
          if (quad.length) {
            for (let i = 0; i < 4; i++) {
              const c = quad[i];
              if (c && c.charge) {
                charge += c.charge;
                qx += c.charge * c.cx;
                qy += c.charge * c.cy;
              }
            }
          } else {
            let leaf: any = quad;
            do {
              const k = chargeFor(leaf.data) * alpha;
              charge += k;
              qx += k * leaf.data.x;
              qy += k * leaf.data.y;
              leaf = leaf.next;
            } while (leaf);
          }
          quad.charge = charge;
          quad.cx = charge ? qx / charge : 0;
          quad.cy = charge ? qy / charge : 0;
        });
        for (const node of simNodes) {
          if (node.fx != null || node.x == null) continue;
          const st = v3.get(node)!;
          q.visit((quad: any, x1: number, _y1: number, x2: number) => {
            if (!quad.charge) return true;
            const dx = quad.cx - node.x!;
            const dy = quad.cy - node.y!;
            const dw = x2 - x1;
            const dn = dx * dx + dy * dy;
            if ((dw * dw) / theta2 < dn) {
              if (dn && dn < chargeDist2) {
                const k = quad.charge / dn;
                st.px -= dx * k;
                st.py -= dy * k;
              }
              return true;
            }
            if (!quad.length && dn && dn < chargeDist2) {
              let leaf: any = quad;
              do {
                if (leaf.data !== node) {
                  const k = (chargeFor(leaf.data) * alpha) / dn;
                  st.px -= dx * k;
                  st.py -= dy * k;
                }
                leaf = leaf.next;
              } while (leaf);
              return true;
            }
            return false;
          });
        }

        for (const node of simNodes) {
          const st = v3.get(node)!;
          if (node.fx != null) {
            node.x = node.fx;
            node.y = node.fy!;
            st.px = node.fx;
            st.py = node.fy!;
          } else if (node.x != null && node.y != null) {
            const tx = st.px;
            st.px = node.x;
            node.x -= (tx - node.x) * friction;
            const ty = st.py;
            st.py = node.y;
            node.y -= (ty - node.y) * friction;
          }
          node.vx = 0;
          node.vy = 0;
        }

        {
          const solidNodes = simNodes.filter((n: any) => !n.__isChip);
          const cq = d3.quadtree(solidNodes as any[], (d: any) => d.x, (d: any) => d.y);
          for (const node of solidNodes) {
            if (node.x == null || node.y == null) continue;
            const r1 = nodeRadiusFor(node);
            cq.visit((quad: any, x1: number, y1: number, x2: number, y2: number) => {
              if (!quad.length) {
                let leaf: any = quad;
                do {
                  const other = leaf.data as D3Node;
                  if (other !== node && other.x != null && other.y != null) {
                    const r = r1 + nodeRadiusFor(other);
                    let dx = node.x! - other.x;
                    let dy = node.y! - other.y;
                    let l = dx * dx + dy * dy;
                    if (l < r * r) {
                      l = Math.sqrt(l) || 1e-6;
                      const push = ((r - l) / l) * 0.5;
                      dx *= push;
                      dy *= push;
                      const nSt = v3.get(node)!;
                      const oSt = v3.get(other)!;
                      if (node.fx == null) { node.x! += dx; node.y! += dy; nSt.px += dx; nSt.py += dy; }
                      if (other.fx == null) { other.x -= dx; other.y! -= dy; oSt.px -= dx; oSt.py -= dy; }
                    }
                  }
                  leaf = leaf.next;
                } while (leaf);
                return false;
              }
              const pad = r1 + 90;
              return x1 > node.x! + pad || x2 < node.x! - pad || y1 > node.y! + pad || y2 < node.y! - pad;
            });
          }
        }
      });
    }

    simulationRef.current = simulation;

    if (usePhysics && !isLayoutPaused) {

      const vowlFullReheat = visualizationType === 'vowl' && hasSavedPositions &&
        newNodeRatio > 0 && nodeCount <= 1500;
      const preTicks = hasSavedPositions
        ? ((bigExpansion || vowlFullReheat) ? (nodeCount > 2000 ? 60 : 250) : 4)
        : entrancePlaying
          ? (entranceRef.current.mode === 'bloom' ? 8 : 16)
          : visualizationType === 'vowl'

            ? (nodeCount > 2000 ? 60 : 250)
            : nodeCount > 2000
              ? 15
              : (visualizationType === 'spatial3d' ? 70 : 50);
      if (bigExpansion || vowlFullReheat) simulation.alpha(1.0);
      for (let i = 0; i < preTicks; i++) {
        simulation.tick();
      }
      simulation.alpha(
        visualizationType === 'vowl'
          ? (hasSavedPositions ? ((bigExpansion || vowlFullReheat) ? 0.2 : 0.1) : nodeCount > 5000 ? 0.08 : 0.2)
          : hasSavedPositions ? 0.05 : entrancePlaying ? (entranceRef.current.mode === 'bloom' ? 0.8 : 0.4) : 0.3
      );
      if (GRAPH_DEBUG) console.log(`[AdvancedGraphView] Pre-calculated ${preTicks} ticks ${hasSavedPositions ? '(incremental, positions preserved)' : 'for stable initial positions'}`);
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

          if (d.type === 'disjointWith' || d.type === 'equivalentClass') {
            return vowlEdge.stroke || '#000000';
          }
          return '#000000';
        }
        if (visualizationType === 'ontograph') {

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
          ? (d.type === 'subClassOf' ? 1.6 : 1.5)
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

          if (d.type === 'instanceOf') return '6 3';
          if (d.type === 'propertyRelation') return '4 3';
          return null; // Solid for subClassOf
        }

        if (d.type === 'subClassOf') return '5 3'; // Dashed for subClassOf
        if (d.type === 'propertyRelation') {
          const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));
          if (targetNode?.type === 'datatype' || targetNode?.label?.startsWith('"')) return '4 2'; // Dashed for data properties
        }
        return null; // Solid for instanceOf and object properties
      })
      .attr('marker-end', d => {

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

    const linkLabelBg = g.append('g')
      .attr('class', 'link-label-backgrounds')
      .selectAll('rect')
      .data(d3Edges)
      .join('rect')
      .attr('class', 'edge-label-bg')
      .attr('fill', d => {

        const isDark = effectiveDark;
        if (visualizationType === 'vowl') {

          const sourceNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.from));
          const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));

          if (d.type === 'subClassOf') {
            return isDark ? '#1f2937' : '#ffffff'; // white chip, dashed border (spec)
          }

          if (d.type === 'propertyRelation') {

            if (sourceNode?.type === 'annotation') {
              return isDark ? '#5b21b6' : '#ddd6fe';
            }

            if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') {
              return isDark ? '#4d7c0f' : '#99cc66';
            }

            return isDark ? '#1d4ed8' : '#69c';
          }

          return isDark ? '#1e3a8a' : '#69c';
        }

        if (d.type === 'propertyRelation') {
          const sourceNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.from));
          const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));
          const isFunctional = d.metadata?.functional;

          if (sourceNode?.type === 'annotation') {
            return isDark ? (isFunctional ? '#5b21b6' : '#4c1d95') : (isFunctional ? '#E9D5FF' : '#F3E8FF');
          }

          if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') {
            return isDark ? (isFunctional ? '#9d174d' : '#831843') : (isFunctional ? '#FCE7F3' : '#FDF2F8');
          }

          return isDark ? (isFunctional ? '#065f46' : '#155e75') : (isFunctional ? '#A7F3D0' : '#CFFAFE');
        }

        return isDark ? '#1f2937' : '#ffffff';
      })
      .attr('opacity', d => {
        if (visualizationType === 'vowl') {

          if (d.type === 'disjointWith') return 0;

          if (vowlChipsAlwaysVisible && isVowlChipEdge(d)) return 1;
          return (selectedEdgeId === d.id || hoveredEdgeId === d.id) ? 1 : 0;
        }
        return (visualizationType as string) === 'vowl' ? 1 : 0.85;
      })
      .attr('stroke', d => {
        if (visualizationType === 'vowl') {

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

          const sourceNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.from));
          const targetNode = allNodes.find(n => n.id === vowlOriginalNodeId(d.to));

          if (d.type === 'subClassOf') {
            return isDark ? '#e5e7eb' : '#374151';
          }

          if (d.type === 'propertyRelation') {

            if (sourceNode?.type === 'annotation') {
              return isDark ? '#ede9fe' : '#4c1d95';
            }

            if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') {
              return isDark ? '#f7fee7' : '#1a2e05';
            }

            return '#ffffff';
          }

          return '#ffffff';
        }

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

        if (visualizationType === 'vowl') {
          if (d.type === 'disjointWith') return ''; // rendered as the VOWL twin-circle symbol

          const baseLabel = d.type === 'subClassOf' ? 'Subclass of' : (d.label || d.type || '');
          return vowlOptions.compactNotation ? baseLabel : `${baseLabel}${buildCharSuffix(d)}`;
        }

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

          if (vowlChipsAlwaysVisible && isVowlChipEdge(d)) return 1;
          return (selectedEdgeId === d.id || hoveredEdgeId === d.id) ? 1 : 0;
        }
        return settings.showLabels ? 1 : 0;
      });

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

        const px = -dy / len;
        const py = dx / len;
        const side = 36;
        return `translate(${mx + px * side},${my + py * side})`;
      });
    };
    updateDisjointSymbols();

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

        (_d as any).__chipW = bbox.width + padding * 2;
        (_d as any).__chipH = bbox.height + padding * 2;
        d3.select(this)
          .attr('x', bbox.x - padding)
          .attr('y', bbox.y - padding)
          .attr('width', bbox.width + padding * 2)
          .attr('height', bbox.height + padding * 2);
      });
    };

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

      updateLinkLabelBackgrounds();
    }

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

    const node = g.append('g')
      .attr('class', 'nodes')
      .selectAll('g')
      .data(visibleD3Nodes)
      .join('g')
      .attr('class', 'node')
      .attr('data-testid', 'graph-node')
      .attr('data-graph-node-id', d => d.id)
      // Paint at the already-computed position immediately instead of waiting for a
      // simulation tick — OntoGraph layouts (tree/radial/grid) fix x/y before this join
      // runs, but with zero ticks in between the group transform never gets set and
      // every node/label collapses onto the same point until something else ticks it.
      .attr('transform', d => `translate(${d.x || 0},${d.y || 0})`)
      .style('cursor', editModeRef.current ? 'move' : 'pointer')
      .call(d3.drag<SVGGElement, D3Node>()
        .on('start', dragStarted)
        .on('drag', dragged)
        .on('end', dragEnded) as any)
      .on('dblclick', (event: any, d: D3Node) => {
        event.stopPropagation();
        event.preventDefault();

        if (event.shiftKey) {
          enterFocusMode(d.id);
          return;
        }

        if (nodeRelationsMap.get(d.id)?.hasChildren) {
          handleToggleExpansion(d.id);
        }
      });

    node.each(function(d) {
      const nodeGroup = d3.select(this);
      const size = d.size || settings.nodeSize;
      let nodeType = d.type;

      const widthScale = (visualizationType === 'vowl' || visualizationType === 'force') ? vowlOptions.nodeWidthScale : 1;
      const heightScale = (visualizationType === 'vowl' || visualizationType === 'force') ? vowlOptions.nodeHeightScale : 1;

      const isThing = d.label === 'Thing' || d.id.includes('owl#Thing');
      const isExternal = isExternalNode(d);

      const nodeIri = String(d.uri || d.id || '');
      const isXsdOrLiteralIri =
        nodeIri.includes('XMLSchema') ||
        nodeIri.includes('rdf-schema#Literal') ||
        nodeIri.endsWith('#Literal') ||
        /#(string|integer|decimal|float|double|boolean|date|dateTime|int|long|short)$/i.test(nodeIri);
      if (nodeType !== 'datatype' && isXsdOrLiteralIri) {
        nodeType = 'datatype';
      }

      const isDark = effectiveDark;

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

      (d as any).__nodeFillColor = fill;

      const stroke = visualizationType === 'vowl'
        ? (isInferredEntity(d) ? '#10b981' : (isDark ? '#d1d5db' : '#000000'))
        : (isInferredEntity(d) ? '#10b981' : '#fff');

      const strokeWidth = visualizationType === 'vowl'
        ? (isInferredEntity(d) ? 2.5 : (isThing ? 1.25 : 1.35))
        : (isInferredEntity(d) ? 3 : (nodeRelationsMap.get(d.id)?.hasChildren && visualizationType !== 'vowl' ? 3 : 2));

      const strokeDasharray = isInferredEntity(d)
        ? '8 4'
        : (visualizationType === 'vowl'
          ? (isThing || nodeType === 'setOperator' ? '5 3' : null)
          : null);

      const fillPaint = visualizationType === 'vowl' ? fill : glossyFill(fill);
      const softShadow = visualizationType === 'vowl' || visibleNodes.length > 100
        ? 'none'
        : 'drop-shadow(0 2px 5px rgba(0,0,0,0.22)) drop-shadow(0 6px 14px rgba(0,0,0,0.10))';

      if (nodeType === 'dataProperty' || nodeType === 'property') {

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

          const label = d.label || '';
          const baseWidth = (visualizationType === 'vowl' ? size * 2.8 * widthScale : (visualizationType === 'ontograph' ? size * 2.8 : size * 2.4));

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

        const simplifiedLOD = isLargeGraph && viewportBoundsRef.current.scale < 0.5;
        const rectWidth = simplifiedLOD ? size * 5 : size * 9;
        const rectHeight = simplifiedLOD ? size * 1.8 : size * 2.6;
        const cornerRadius = 8;

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

          if (nodeRelationsMap.get(d.id)?.hasChildren) {
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

        if (visualizationType === 'force' && nodeType === 'class') {

          const ellipseWidth = size * 3.5 * widthScale;  // Wider for text
          const ellipseHeight = size * 2.0 * heightScale; // Taller oval
          nodeGroup.append('ellipse')
            .attr('class', 'node-shape')
            .attr('cx', 0)
            .attr('cy', 0)
            .attr('rx', ellipseWidth)
            .attr('ry', ellipseHeight)
            .attr('fill', () => {

              const paletteFill = nodeFill(d.type, isDark);
              const finalFill = colorByCluster && clusterFor(d.id) !== undefined
                ? (getClusterColor(clusterFor(d.id), graphAnalytics.clusterColors) || paletteFill)
                : paletteFill;

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

      if (visualizationType !== 'ontograph' && visualizationType !== 'vowl' && nodeRelationsMap.get(d.id)?.hasChildren) {
        const isExpanded = expandedNodeIds.has(d.id);
        const isDark = effectiveDark;

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

    node.selectAll<SVGGraphicsElement, D3Node>('.node-shape').each(function() {
      const el = d3.select(this);
      el.attr('data-base-fill', el.attr('fill') || 'none');
      el.attr('data-base-stroke', el.attr('stroke') || 'none');
      el.attr('data-base-stroke-width', el.attr('stroke-width') || '1');
      el.attr('data-base-filter', this.style.filter || 'none');
    });

    const showLabels = !isLargeGraph || viewportBoundsRef.current.scale >= 0.5;

    const labelHalo = effectiveDark ? '#1b1e2b' : '#ffffff';
    node.append('text')
      .attr('class', 'node-label-text')
      .attr('data-base-font-size', d => {

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

          const painted = (d as any).__nodeFillColor || (effectiveDark ? '#6b92c4' : '#69c');
          if (visualizationType === 'vowl') {

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

          if (d.type === 'setOperator') {
            return SET_OPERATOR_SYMBOLS[(d as any).metadata?.setOperator] || '∪';
          }

          const label = d.label || '';
          const size = d.size || settings.nodeSize;
          const charWidthPx = vowlOptions.labelFontSize * (7 / 11); // 7px/char was calibrated at 11px font

          const widthScale = vowlOptions.nodeWidthScale;
          const heightScale = vowlOptions.nodeHeightScale;
          let maxChars = 18;

          if (d.type === 'datatype') {

            maxChars = Math.max(4, Math.floor((size * 4.2 * widthScale - 12) / charWidthPx));
          } else if (d.type === 'individual') {

            const label2 = d.label || '';
            const baseWidth = size * 2.8 * widthScale;
            const labelWidth = Math.min(label2.length * 7, 180);
            const rectWidth = Math.max(baseWidth, labelWidth + 16);
            const finalWidth = Math.min(rectWidth, size * 5.0 * widthScale);
            maxChars = Math.max(4, Math.floor((finalWidth - 10) / charWidthPx));
          } else if (d.type === 'class') {

            const diameter = size * 2.7 * ((widthScale + heightScale) / 2);
            maxChars = Math.max(6, Math.floor((diameter - 8) / charWidthPx));
          }

          maxChars = Math.min(maxChars, vowlOptions.maxLabelChars);
          return label.length > maxChars ? label.substring(0, Math.max(1, maxChars - 2)) + '..' : label;
        }
        if (visualizationType === 'ontograph' && !showLabels) {
          return ''; // Hide labels when zoomed out on large graphs
        }
        if (visualizationType === 'ontograph') {

          const s = d.size || settings.nodeSize;
          const maxChars = Math.max(8, Math.floor((s * 9 - 32) / 7));
          const label = d.label || '';
          return label.length > maxChars ? label.substring(0, maxChars - 2) + '..' : label;
        }
        if (visualizationType === 'force') {

          const label = d.label || '';
          const size = d.size || settings.nodeSize;
          const charWidthPx = vowlOptions.labelFontSize * (7 / 11);

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

          const label = d.label || '';
          return label.length > 12 ? label.substring(0, 10) + '..' : label;
        }
        return settings.showLabels ? d.label : '';
      })
      .style('pointer-events', 'none')
      .style('user-select', 'none');

    if (visualizationType === 'vowl') {
      node.select('text').each(function(d: any) {
        if (d.type !== 'class' && d.type !== 'datatype') return;
        if (d.type === 'class' && (d.label === 'Thing' || isThingIri(d.id))) return;
        const size = d.size || settings.nodeSize;
        const charWidthPx = vowlOptions.labelFontSize * (7 / 11);
        const avgScale = (vowlOptions.nodeWidthScale + vowlOptions.nodeHeightScale) / 2;
        const isDatatype = d.type === 'datatype';

        const lineBudget = isDatatype
          ? Math.max(4, Math.floor((size * 4.2 * vowlOptions.nodeWidthScale - 12) / charWidthPx))
          : Math.max(6, Math.floor((size * 2.7 * avgScale * 0.86 - 6) / charWidthPx));

        const equivParts: string[] | undefined = d?.metadata?.vowlEquivalent
          ? d.metadata?.equivalentLabels
          : undefined;
        const isEquivStack = !!equivParts && equivParts.length >= 2 && d.type === 'class';
        const fullLabel = d.label || '';
        if (!isEquivStack && fullLabel.length <= lineBudget) return; // fits one line

        let lines: string[];
        if (isEquivStack) {
          lines = equivParts!;
        } else {
          const maxLines = isDatatype ? 2 : 3;
          const words: string[] = [];
          for (const raw of fullLabel.split(/[\s_]+/).filter(Boolean)) {
            let w = raw;
            while (w.length > lineBudget) {
              words.push(w.substring(0, Math.max(2, lineBudget - 1)) + '-');
              w = w.substring(Math.max(2, lineBudget - 1));
            }
            words.push(w);
          }
          lines = [];
          let cur = '';
          let overflow = false;
          for (const w of words) {
            const candidate = cur ? cur + ' ' + w : w;
            if (cur && candidate.length > lineBudget) {
              if (lines.length === maxLines - 1) { overflow = true; break; }
              lines.push(cur);
              cur = w;
            } else {
              cur = candidate;
            }
          }
          if (cur) {
            if (overflow) cur = cur.substring(0, Math.max(1, lineBudget - 2)) + '..';
            lines.push(cur);
          }
          if (lines.length === 0) lines = [fullLabel];
        }
        lines = lines.map(l =>
          l.length > lineBudget ? l.substring(0, Math.max(1, lineBudget - 2)) + '..' : l
        );

        const el = d3.select(this);
        el.text(null);
        const startDy = -((lines.length - 1) * 0.55);
        lines.forEach((text: string, i: number) => {
          el.append('tspan')
            .attr('x', 0)
            .attr('dy', i === 0 ? `${startDy}em` : '1.1em')
            .text(text);
        });
      });
    }

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

    let rafId: number | null = null;
    let ticking = false;
    let tickCount = 0;

    const updateInterval = nodeCount > 5000 ? 5 : nodeCount > 2000 ? 4 : nodeCount > 100 ? 3 : 2;

    const computeEdgePath = (d: D3Edge): string => {
      const source = d.source as D3Node;
      const target = d.target as D3Node;

      if (source.x == null || source.y == null || target.x == null || target.y == null) {
        return '';
      }

      const sourcePoint = getRenderPoint(source);
      const targetPoint = getRenderPoint(target);
      const curve = edgeCurvature.get(d.id) || 0;

      const chip = (d as any).__chipNode as D3Node | undefined;
      if (chip && chip.x != null && chip.y != null) {

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

        const inDx = targetPoint.x - chip.x;
        const inDy = targetPoint.y - chip.y;
        const inDist = Math.sqrt(inDx * inDx + inDy * inDy) || 1;
        let tr = (target.size || settings.nodeSize);
        if (target.type === 'datatype' || target.type === 'dataProperty') {
          const w = tr * 2.8;
          const h = tr * 1.8;
          const absCos = Math.abs(inDx / inDist);
          const absSin = Math.abs(inDy / inDist);
          tr = Math.min((w / 2) / Math.max(absCos, 1e-6), (h / 2) / Math.max(absSin, 1e-6)) + 2;
        } else {

          const avgScale = (vowlOptions.nodeWidthScale + vowlOptions.nodeHeightScale) / 2;
          tr = tr * 1.35 * avgScale + 4;
        }
        const tx = targetPoint.x - (inDx / inDist) * tr;
        const ty = targetPoint.y - (inDy / inDist) * tr;
        return `M${sourcePoint.x},${sourcePoint.y}L${chip.x},${chip.y}L${tx},${ty}`;
      }

      const dx = targetPoint.x - sourcePoint.x;
      const dy = targetPoint.y - sourcePoint.y;
      const dist = Math.sqrt(dx * dx + dy * dy);

      if (dist === 0) {
        return `M${sourcePoint.x},${sourcePoint.y}L${targetPoint.x},${targetPoint.y}`;
      }

      let r = (target.size || settings.nodeSize);

      if (visualizationType === 'vowl') {
        if (target.type === 'datatype' || target.type === 'dataProperty') {

          const w = r * 2.8;
          const h = r * 1.8;
          const absCos = Math.abs(dx / dist);
          const absSin = Math.abs(dy / dist);
          r = Math.min((w / 2) / absCos, (h / 2) / absSin) + 2;
        } else {

          const avgScale = (vowlOptions.nodeWidthScale + vowlOptions.nodeHeightScale) / 2;
          r = r * 1.35 * avgScale + 4;
        }
      } else if (visualizationType === 'ontograph') {

        const w = r * 7.5;
        const h = r * 2.0;
        const absCos = Math.abs(dx / dist);
        const absSin = Math.abs(dy / dist);
        r = Math.min((w / 2) / absCos, (h / 2) / absSin) + 2;
      } else if (visualizationType === 'force') {
        if (target.type === 'class') {

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

        return `M${sourcePoint.x},${sourcePoint.y}L${targetX},${targetY}`;
      } else {

        const midX = (sourcePoint.x + targetX) / 2;
        const midY = (sourcePoint.y + targetY) / 2;

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

        let labelX: number;
        let labelY: number;

        if (curve === 0) {

          const t = 0.5;
          labelX = sourceX + dx * t;
          labelY = sourceY + dy * t;

          if (dist > 0) {
            const perpX = -dy / dist;
            const perpY = dx / dist;
            const perpOffset = visualizationType === 'vowl' ? 0 : 10; // Centered for VOWL
            labelX += perpX * perpOffset;
            labelY += perpY * perpOffset;
          }
        } else {

          const midX = (sourceX + targetX) / 2;
          const midY = (sourceY + targetY) / 2;

          const perpX = dist > 0 ? -dy / dist : 0;
          const perpY = dist > 0 ? dx / dist : 0;

          const controlX = midX + perpX * curve;
          const controlY = midY + perpY * curve;

          const t = 0.5;
          labelX = (1-t)*(1-t)*sourceX + 2*(1-t)*t*controlX + t*t*targetX;
          labelY = (1-t)*(1-t)*sourceY + 2*(1-t)*t*controlY + t*t*targetY;

          const labelOffset = 10;
          labelX += perpX * labelOffset;
          labelY += perpY * labelOffset;
        }

        d3.select(this)
          .attr('x', labelX)
          .attr('y', labelY);

        if (vowlChipsAlwaysVisible && (d.__chipW ?? 0) > 0) {
          const bg = linkLabelBg.nodes()[i] as SVGRectElement | undefined;
          if (bg) {
            bg.setAttribute('x', String(labelX - d.__chipW / 2));
            bg.setAttribute('y', String(labelY - d.__chipH / 2));
          }
        }
      });
    };

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

    if (visualizationType === 'ontograph' && ontographLayoutType !== 'spring') {
      simulation.alpha(0.01).restart(); // Minimal alpha since positions are fixed
    } else if (visualizationType === 'ontograph') {
      simulation.alpha(1).restart();
    }

    simulation.on('tick', () => {
      tickCount++;

      if (tickCount % updateInterval !== 0) return;

      if (!ticking) {
        ticking = true;
        rafId = requestAnimationFrame(() => {
          if (isSpatial3D) {
            d3Nodes.forEach(project3DNode);
          }

          updateEdgePaths(false);

          if ((visualizationType !== 'vowl' && settings.showLabels !== false) || vowlChipsAlwaysVisible) {
            updateLinkLabelPositions();
          }

          if (visualizationType === 'vowl') {
            updateDisjointSymbols();
            updateRestrictionBadges();
          }

          node.attr('transform', d => {
            const point = getRenderPoint(d);
            return `translate(${point.x},${point.y}) scale(${visualizationType === 'spatial3d' ? Math.max(0.72, Math.min(1.28, point.scale)) : 1})`;
          });
          if (isSpatial3D) {

            node.style('opacity', d => {
              const point = getRenderPoint(d);
              return Math.max(0.58, Math.min(1, point.scale * 0.92));
            });
          }

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

    if (vowlChipsAlwaysVisible) {
      requestAnimationFrame(() => {
        updateLinkLabelBackgrounds();
        resolveMeasuredChipOverlaps();
        updateLinkLabelPositions();
      });
    }

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

        if (visualizationType === 'vowl' && vowlPinnedHubIdsRef.current.has(d.id)) {
          d.fx = d.x;
          d.fy = d.y;
        } else {
          d.fx = null;
          d.fy = null;
        }
      }

      updateEdgePaths(true);
      applyViewportCulling();
    }

    function resolveVowlClone(d: D3Node): OntologyNode {
      const originalId = (d as any).metadata?.cloneOf as string | undefined;
      if (!originalId) return d as OntologyNode;
      return allNodes.find(n => n.id === originalId) ?? (d as OntologyNode);
    }

    function handleNodeClick(event: any, d: D3Node) {
      event.stopPropagation();

      d3.selectAll('.graph-tooltip').remove();

      if (event.ctrlKey || event.metaKey) {

        const newSelected = new Set(selectedNodesRef.current);
        if (newSelected.has(d.id)) {
          newSelected.delete(d.id);
        } else {
          newSelected.add(d.id);
        }
        setSelectedNodes(newSelected);
      } else {

        const original = resolveVowlClone(d);
        setSelectedNodes(new Set([d.id]));
        setSelectedNodeInfo(original);

        if (vowlOptions.isolateOnSelect && visualizationType === 'vowl') {
          enterFocusMode(original.id);
        }

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

      setContextMenu({
        visible: true,
        x: event.pageX,
        y: event.pageY,
        nodeId: original.id
      });

      setSelectedNodes(new Set([d.id]));
      setSelectedNodeInfo(original);
    }

    function handleNodeMouseOver(event: any, d: D3Node) {
      setHoveredNode(d.id);
      if (svgHoverClearTimerRef.current) {
        clearTimeout(svgHoverClearTimerRef.current);
        svgHoverClearTimerRef.current = null;
      }
      const original = resolveVowlClone(d);
      setSvgHoverCard({
        id: original.id,
        label: original.label || d.label,
        type: original.type || d.type,
        x: event.clientX + 12,
        y: event.clientY + 12
      });

      if (settings.tooltips) {

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
      if (svgHoverClearTimerRef.current) clearTimeout(svgHoverClearTimerRef.current);
      svgHoverClearTimerRef.current = setTimeout(() => {
        if (svgRenamingRef.current) return;
        setSvgHoverCard(null);
      }, 450);
    }

    const zoom = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.1, 10])
      // ~25% finer than d3's default wheel step — noticeably less jumpy on mice
      .wheelDelta((event: WheelEvent) =>
        -event.deltaY * (event.deltaMode === 1 ? 0.05 : event.deltaMode ? 1 : 0.002) * (event.ctrlKey ? 10 : 1) * 0.75
      )
      .on('zoom', (event) => {

        if (event.sourceEvent) userInteractedRef.current = true;
        g.attr('transform', event.transform);
        currentTransformRef.current = event.transform;

        if (visualizationType === 'vowl') {
          const k = event.transform.k;

          const HIDE_LABEL_ZOOM_THRESHOLD = 0.14;
          const labelSelection = g.selectAll<SVGTextElement, unknown>('text.node-label-text');
          labelSelection.style('display', k < HIDE_LABEL_ZOOM_THRESHOLD ? 'none' : 'inline');
          labelSelection.attr('font-size', function () {
            const base = parseFloat(this.getAttribute('data-base-font-size') || '');
            if (!base) return null;
            return k <= 1 ? base * Math.min(1 / k, 1.6) : base;
          });
        }
      })
      .on('end', (event) => {
        setZoomLevel(event.transform.k);

        const transform = event.transform;
        viewportBoundsRef.current = {
          x: -transform.x / transform.k,
          y: -transform.y / transform.k,
          width: width / transform.k,
          height: height / transform.k,
          scale: transform.k
        };

        applyViewportCullingRef.current();
      });

    zoomRef.current = zoom;
    svg.call(zoom as any);

    svg.on('dblclick.zoom', null);

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
      let scale = Math.min(0.9 / Math.max(bounds.width / w, bounds.height / h), 2);

      if (visualizationType === 'vowl') {
        scale = Math.min(Math.max(scale, 0.55), 1);
        let wx = 0;
        let wy = 0;
        let wSum = 0;
        for (const n of simNodes) {
          if ((n as any).__isChip || n.x == null || n.y == null) continue;
          const wt = (nodeDegreeMap.get(n.id) || 0) + 1;
          wx += n.x * wt;
          wy += n.y * wt;
          wSum += wt;
        }
        if (wSum > 0) {

          const solid = simNodes.filter(
            n => !(n as any).__isChip && n.x != null && n.y != null
          );
          const R = Math.min(w, h) / (2 * scale);
          let best: D3Node | null = null;
          let bestScore = -Infinity;
          for (const n of solid) {
            let count = 0;
            for (const m of solid) {
              if (m !== n && Math.hypot(m.x! - n.x!, m.y! - n.y!) <= R) count++;
            }
            const score = count * 1000 + (nodeDegreeMap.get(n.id) || 0);
            if (score > bestScore) { bestScore = score; best = n; }
          }
          const cx = best ? best.x! : wx / wSum;
          const cy = best ? best.y! : wy / wSum;
          return d3.zoomIdentity.translate(w / 2 - scale * cx, h / 2 - scale * cy).scale(scale);
        }
      }
      const tx = w / 2 - scale * (bounds.x + bounds.width / 2);
      const ty = h / 2 - scale * (bounds.y + bounds.height / 2);
      return d3.zoomIdentity.translate(tx, ty).scale(scale);
    };

    setTimeout(() => {
      if (!svgRef.current || !zoomRef.current) return;
      const svgEl = d3.select(svgRef.current);
      const target = computeFitTransform();
      if (!target) return;
      if (entrancePlaying && !prefersReducedMotion) {

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

    if (visualizationType === 'vowl' && pendingToggleFrameRef.current) {
      const frameNodeId = pendingToggleFrameRef.current;
      pendingToggleFrameRef.current = null;

      userInteractedRef.current = true;
      let framed = false;
      const frameToggled = () => {
        if (framed) return;
        framed = true;
        simulation.on('end.toggleFrame', null);
        glideToNodeIds(new Set([frameNodeId]), 0.9);
      };
      simulation.on('end.toggleFrame', frameToggled);
      setTimeout(frameToggled, 5000);
    }

    if (visualizationType === 'vowl' && usePhysics && !isLayoutPaused) {
      let recentered = false;
      const recenter = () => {
        if (recentered) return;
        recentered = true;
        simulation.on('end.vowlRecenter', null);
        if (userInteractedRef.current || !svgRef.current || !zoomRef.current) return;
        const target = computeFitTransform();
        if (target) {
          d3.select(svgRef.current)
            .transition('vowlRecenter')
            .duration(600)
            .ease(d3.easeCubicOut)
            .call(zoomRef.current.transform as any, target);
        }
      };
      simulation.on('end.vowlRecenter', recenter);
      setTimeout(recenter, 8000);
    }

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

    const endTime = performance.now();
    const renderTimeMs = endTime - startTime;
    renderTime.current = renderTimeMs;
    if (GRAPH_DEBUG) {
      console.log(`[AdvancedGraphView D3] ⚡ Render completed in ${renderTimeMs.toFixed(2)}ms`);
      console.log(`[AdvancedGraphView D3] 📊 Performance: ${(filteredNodes.length / renderTimeMs * 1000).toFixed(0)} nodes/sec`);
    }

    return () => {
      simulation.stop();
      if (entranceTimeout) clearTimeout(entranceTimeout);
      if (smoothWheelRaf != null) cancelAnimationFrame(smoothWheelRaf);
    };
  // Deliberately granular deps: edit-mode toggles, click-handler identity, and label/
  // tooltip toggles update in place (refs + visual-update effect) instead of tearing
  // down and rebuilding the whole scene.
  }, [filteredNodes, filteredEdges, settings.nodeSize, settings.showArrows, settings.tooltips, settings.physics, allEdges, allNodes, expandedNodeIds, classDistance, datatypeDistance, isLayoutPaused, visualizationType, ontographLayoutType, graphAnalytics, colorByCluster, sizeByInfluence, isDarkTheme, prefersReducedMotion, projectId, vowlOptions]);

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

    const t1 = setTimeout(() => { if (!doFit()) { setTimeout(doFit, 450); } }, 350);
    return () => clearTimeout(t1);
  }, [visualizationType, ontographLayoutType, filteredNodes.length, prefersReducedMotion]);

  useEffect(() => {
    if (!gRef.current) return;
    const g = d3.select(gRef.current);

    const hoverNeighborIds = new Set<string>();
    if (hoveredNode) {
      for (const e of allEdges) {
        if (e.from === hoveredNode) hoverNeighborIds.add(e.to);
        else if (e.to === hoveredNode) hoverNeighborIds.add(e.from);
      }
    }

    const selectionActive = selectedNodes.size > 0;
    const focusNodeIds = new Set<string>();
    const focusEdgeIds = new Set<string>();
    if (selectionActive) {
      selectedNodes.forEach(id => focusNodeIds.add(id));

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

    g.selectAll('.edge-path')
      .attr('stroke-width', (d: any) => {
        const baseWidth = visualizationType === 'vowl' ? 1 : 2;
        if (selectedEdgeId === d.id) return baseWidth + 2;
        if (selectionActive && focusEdgeIds.has(d.id)) return baseWidth + 0.6;
        return baseWidth;
      })
      .attr('stroke-opacity', (d: any) => {
        if (searchFilterMode === 'dim' && searchFocusIds.size > 0) {
          const sourceId = typeof d.source === 'string' ? d.source : d.source?.id;
          const targetId = typeof d.target === 'string' ? d.target : d.target?.id;
          const inFocus = searchFocusIds.has(sourceId) && searchFocusIds.has(targetId);
          return inFocus ? 1 : 0.08;
        }
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

        if (searchFilterMode === 'dim' && searchFocusIds.size > 0) {
          return searchFocusIds.has(n.id) ? 1 : 0.12;
        }
        if (searchLower) {
          return isSearchMatch(n) ? 1 : 0.2;
        }
        if (hoveredNode) {
          return n.id === hoveredNode || hoverNeighborIds.has(n.id) ? 1 : 0.3;
        }
        return 1;
      });

    g.selectAll<SVGGElement, any>('.node')
      .style('opacity', (n: any) => {
        if (searchFilterMode === 'dim' && searchFocusIds.size > 0) {
          return searchFocusIds.has(n.id) ? 1 : 0.12;
        }
        if (!selectionActive) return 1;
        return focusNodeIds.has(n.id) ? 1 : 0.1;
      });

    g.selectAll('.disjoint-symbol')
      .style('opacity', (d: any) => {
        if (!selectionActive) return 1;
        return focusEdgeIds.has(d.id) ? 1 : 0.1;
      });

  }, [selectedNodes, selectedEdgeId, hoveredEdgeId, hoveredNode, visualizationType, settings.showLabels, allEdges, searchQuery, searchFilterMode, searchFocusIds, vowlChipDensityOk]);

  useEffect(() => {
    if (!gRef.current) return;
    d3.select(gRef.current)
      .selectAll<SVGGElement, any>('.node')
      .classed('node-selected', (d: any) => selectedNodes.has(d.id));
  }, [selectedNodes, filteredNodes]);

  const glideToNodeIds = useCallback((ids: Set<string>, maxScale = 1.4) => {
    if (!svgRef.current || !zoomRef.current || !gRef.current || ids.size === 0) return;
    const pts: Array<{ x: number; y: number }> = [];
    d3.select(gRef.current).selectAll<SVGGElement, any>('.node').each((d: any) => {
      if (d && ids.has(d.id) && d.x != null && d.y != null) pts.push({ x: d.x, y: d.y });
    });
    if (!pts.length) return;
    const w = svgRef.current.clientWidth;
    const h = svgRef.current.clientHeight;
    if (!w || !h) return;
    const minX = Math.min(...pts.map(p => p.x)) - 130;
    const maxX = Math.max(...pts.map(p => p.x)) + 130;
    const minY = Math.min(...pts.map(p => p.y)) - 110;
    const maxY = Math.max(...pts.map(p => p.y)) + 110;
    const scale = Math.max(0.35, Math.min(maxScale, Math.min(w / (maxX - minX), h / (maxY - minY))));
    const t = d3.zoomIdentity
      .translate(w / 2 - scale * (minX + maxX) / 2, h / 2 - scale * (minY + maxY) / 2)
      .scale(scale);
    d3.select(svgRef.current)
      .transition()
      .duration(650)
      .ease(d3.easeCubicOut)
      .call(zoomRef.current.transform as any, t);
  }, []);

  useEffect(() => {
    if (!focusedNodeId) return;
    const ids = focusedNodeIds ?? new Set([focusedNodeId]);
    const timer = window.setTimeout(() => {
      if (webglActive) {
        webglCamRef.current?.fitToNodes([...ids]);
      } else {
        requestViewportFitAfterBulkExpand();
        glideToNodeIds(ids, 1.2);
      }
    }, 220);
    return () => clearTimeout(timer);
  }, [focusedNodeId, focusedNodeIds, webglActive, glideToNodeIds, requestViewportFitAfterBulkExpand]);

  useEffect(() => {
    if (visualizationType !== 'vowl' || selectedNodes.size === 0 || !gRef.current) return;
    if (focusedNodeId || vowlOptions.isolateOnSelect) return;
    const ids = new Set<string>(selectedNodes);
    d3.select(gRef.current).selectAll('.edge-path').each((d: any) => {
      if (!d) return;
      const s = typeof d.source === 'string' ? d.source : d.source?.id ?? d.from;
      const t = typeof d.target === 'string' ? d.target : d.target?.id ?? d.to;
      if (selectedNodes.has(s) || selectedNodes.has(t)) {
        if (s) ids.add(s);
        if (t) ids.add(t);
      }
    });

    userInteractedRef.current = true;
    const timer = setTimeout(() => glideToNodeIds(ids), 120);
    return () => clearTimeout(timer);
  }, [selectedNodes, visualizationType, glideToNodeIds, focusedNodeId, vowlOptions.isolateOnSelect]);

  useEffect(() => {
    if (!searchQuery || visualizationType !== 'vowl' || !gRef.current) return;
    const q = searchQuery.toLowerCase();
    const timer = setTimeout(() => {
      if (!gRef.current) return;
      const ids = new Set<string>();
      d3.select(gRef.current).selectAll<SVGGElement, any>('.node').each((d: any) => {
        if (d && (d.label?.toLowerCase().includes(q) || d.id?.toLowerCase().includes(q))) ids.add(d.id);
      });
      if (ids.size) {
        userInteractedRef.current = true;
        glideToNodeIds(ids, 1.2);
      }
    }, 700);
    return () => clearTimeout(timer);
  }, [searchQuery, visualizationType, glideToNodeIds]);

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

    if (visualizationType === 'vowl') {
      pendingToggleFrameRef.current = nodeId;
    }
  }, [allNodes, allEdges, expandedNodeIds, visibleNodeIds, updateHierarchyState, fetchMissingChildren, visualizationType]);

  const handleExpandParents = useCallback((nodeId: string) => {
    const node = allNodes.find(n => n.id === nodeId);
    if (!node) {
      console.log('[User Action] Node not found:', nodeId);
      return;
    }

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

    if (showHierarchyDialog && parentIds.length > 0) {
      const topmostParent = allNodes.find(n => n.id === parentIds[0]);
      if (topmostParent) {
        setHierarchyRootNode(topmostParent);
      }
    }
  }, [allNodes, allEdges, showHierarchyDialog, updateHierarchyState]);

  const handleSearch = useCallback((query: string, depth: number = searchFilterDepth, mode: 'dim' | 'hide' = searchFilterMode) => {
    if (!query.trim()) {
      const restored = preFilterVisibilityRef.current;
      preFilterVisibilityRef.current = null;
      if (restored) {
        updateHierarchyState(() => ({
          visible: new Set(restored.visible),
          expanded: new Set(restored.expanded)
        }));
      } else {
        const { newExpandedIds, newVisibleIds } = smartInitialGraphVisibility(allNodes, allEdges);
        updateHierarchyState(() => ({
          visible: newVisibleIds,
          expanded: newExpandedIds
        }));
      }
      setSearchQuery('');
      setSearchFocusIds(new Set());
      return;
    }

    const { matchingNodes, nodesToShow, nodesToExpand } = searchNodesWithPaths(
      query,
      allNodes,
      allEdges,
      { includeAncestors: true, childDepth: depth }
    );

    setSearchFocusIds(new Set(nodesToShow));
    setSearchQuery(query);

    if (mode === 'hide') {
      if (!preFilterVisibilityRef.current) {
        preFilterVisibilityRef.current = {
          visible: new Set(visibleNodeIds),
          expanded: new Set(expandedNodeIds)
        };
      }
      updateHierarchyState(() => ({
        visible: new Set(nodesToShow),
        expanded: new Set(nodesToExpand)
      }));
    } else {
      updateHierarchyState((prev) => {
        const visible = new Set(prev.visible);
        const expanded = new Set(prev.expanded);
        for (const id of nodesToShow) visible.add(id);
        for (const id of nodesToExpand) expanded.add(id);
        return { visible, expanded };
      });
    }

    console.log(
      `[Search] mode=${mode} matches=${matchingNodes.length} focus=${nodesToShow.size} depth=${depth}`
    );
    requestViewportFitAfterBulkExpand();
  }, [allNodes, allEdges, updateHierarchyState, searchFilterDepth, searchFilterMode, visibleNodeIds, expandedNodeIds, requestViewportFitAfterBulkExpand]);

  const getDepthSeeds = useCallback((): string[] => {
    if (selectedNodes.size > 0) return Array.from(selectedNodes);
    if (searchFocusIds.size > 0) {
      const q = searchQuery.trim().toLowerCase();
      if (q) {
        const matches = allNodes
          .filter((n) => searchFocusIds.has(n.id) && (
            (n.label?.toLowerCase().includes(q)) || n.id.toLowerCase().includes(q)
          ))
          .map((n) => n.id);
        if (matches.length > 0) return matches;
      }
      return Array.from(searchFocusIds);
    }
    return [];
  }, [selectedNodes, searchFocusIds, searchQuery, allNodes]);

  const handleExpandOneDepth = useCallback(() => {
    const seeds = getDepthSeeds();
    if (seeds.length === 0) return;
    const { newExpandedIds, newVisibleIds } = expandSeedsOneLevel(
      seeds, expandedNodeIds, visibleNodeIds, allEdges, allNodes
    );
    updateHierarchyState(() => ({ visible: newVisibleIds, expanded: newExpandedIds }));
    setSearchFocusIds((prev) => {
      const next = new Set(searchFilterMode === 'hide' ? newVisibleIds : prev);
      if (searchFilterMode === 'dim') {
        for (const id of seeds) next.add(id);
        for (const id of newVisibleIds) {
          if (!visibleNodeIds.has(id)) next.add(id);
        }
      }
      return next;
    });
    requestViewportFitAfterBulkExpand();
  }, [getDepthSeeds, expandedNodeIds, visibleNodeIds, allEdges, allNodes, updateHierarchyState, searchFilterMode, requestViewportFitAfterBulkExpand]);

  const handleCollapseOneDepth = useCallback(() => {
    const seeds = getDepthSeeds();
    if (seeds.length === 0) return;
    const { newExpandedIds, newVisibleIds } = collapseSeedsOneLevel(
      seeds, expandedNodeIds, visibleNodeIds, allEdges, allNodes
    );
    updateHierarchyState(() => ({ visible: newVisibleIds, expanded: newExpandedIds }));
    if (searchFilterMode === 'hide') {
      setSearchFocusIds(new Set(newVisibleIds));
    } else {
      setSearchFocusIds((prev) => {
        const next = new Set(prev);
        for (const id of prev) {
          if (!newVisibleIds.has(id) && !seeds.includes(id)) next.delete(id);
        }
        return next;
      });
    }
    requestViewportFitAfterBulkExpand();
  }, [getDepthSeeds, expandedNodeIds, visibleNodeIds, allEdges, allNodes, updateHierarchyState, searchFilterMode, requestViewportFitAfterBulkExpand]);

  const handleDeepDive = useCallback((depth: number = searchFilterDepth) => {
    const seeds = getDepthSeeds();
    if (seeds.length === 0) return;
    const { newExpandedIds, newVisibleIds } = expandSeedsToDepth(
      seeds, depth, expandedNodeIds, visibleNodeIds, allEdges, allNodes
    );
    updateHierarchyState(() => ({ visible: newVisibleIds, expanded: newExpandedIds }));
    setSearchFocusIds((prev) => {
      if (searchFilterMode === 'hide') return new Set(newVisibleIds);
      const next = new Set(prev);
      for (const id of newVisibleIds) next.add(id);
      return next;
    });
    requestViewportFitAfterBulkExpand();
  }, [getDepthSeeds, searchFilterDepth, expandedNodeIds, visibleNodeIds, allEdges, allNodes, updateHierarchyState, searchFilterMode, requestViewportFitAfterBulkExpand]);

  const handleFilterModeChange = useCallback((mode: 'dim' | 'hide') => {
    setSearchFilterMode(mode);
    if (!searchQuery.trim()) return;
    if (mode === 'hide') {
      if (!preFilterVisibilityRef.current) {
        preFilterVisibilityRef.current = {
          visible: new Set(visibleNodeIds),
          expanded: new Set(expandedNodeIds)
        };
      }
      const focus = searchFocusIds.size > 0
        ? searchFocusIds
        : searchNodesWithPaths(searchQuery, allNodes, allEdges, {
            includeAncestors: true,
            childDepth: searchFilterDepth
          }).nodesToShow;
      updateHierarchyState((prev) => ({
        visible: new Set(focus),
        expanded: new Set([...prev.expanded].filter((id) => focus.has(id)))
      }));
      setSearchFocusIds(new Set(focus));
    } else {
      const restored = preFilterVisibilityRef.current;
      if (restored) {
        updateHierarchyState(() => ({
          visible: new Set([...restored.visible, ...searchFocusIds]),
          expanded: new Set(restored.expanded)
        }));
      } else {
        updateHierarchyState((prev) => {
          const visible = new Set(prev.visible);
          for (const id of searchFocusIds) visible.add(id);
          return { visible, expanded: prev.expanded };
        });
      }
    }
    requestViewportFitAfterBulkExpand();
  }, [searchQuery, visibleNodeIds, expandedNodeIds, searchFocusIds, allNodes, allEdges, searchFilterDepth, updateHierarchyState, requestViewportFitAfterBulkExpand]);

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

      setSelectedNodes(new Set([newIri]));
      setSelectedNodeInfo(newNode);
      window.setTimeout(() => {
        if (webglActive) {
          webglCamRef.current?.fitToNodes([newIri]);
        } else {
          glideToNodeIds(new Set([newIri]), 1.4);
        }
      }, 180);

      setClassActionFeedback({ type: 'success', message: `✓ Created class "${newLabel}"` });
      setPendingClassAction(null);

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
  }, [buildClassIri, context, projectId, requestHostClassDialog, updateHierarchyState, webglActive, glideToNodeIds]);

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

  const renameClassLabel = useCallback(async (nodeId: string, newLabel: string) => {
    if (readonly || !projectId || !canEdit || classActionLoading) return;
    const node = allNodes.find(n => n.id === nodeId);
    const label = newLabel.trim();
    if (!node || !label || label === node.label) return;
    setClassActionLoading(true);
    try {
      const iri = resolveNodeIri(node) || node.id;
      await applyOntologyMutations([{ type: 'updateClassLabel', iri, label }]);
      setAllNodes(prev => prev.map(n => n.id === nodeId ? { ...n, label } : n));
      setClassActionFeedback({ type: 'success', message: `Renamed to "${label}"` });
    } catch (actionError) {
      const message = actionError instanceof Error ? actionError.message : 'Rename failed';
      setClassActionFeedback({ type: 'error', message });
      console.error('[Graph Dialog] Rename failed:', actionError);
    } finally {
      setClassActionLoading(false);
    }
  }, [allNodes, applyOntologyMutations, canEdit, classActionLoading, projectId, readonly]);

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

  const cameraTransition = (svg: d3.Selection<SVGSVGElement, unknown, null, undefined>, ms: number = CAMERA_MS) =>
    svg.transition().duration(prefersReducedMotion ? 0 : ms).ease(d3.easeCubicOut);

  const handleZoomIn = () => {
    if (webglActive) {
      webglCamRef.current?.zoomBy(1.3);
      return;
    }
    if (svgRef.current && zoomRef.current) {
      const svg = d3.select(svgRef.current);
      cameraTransition(svg, 300).call(
        zoomRef.current.scaleBy as any, 1.3
      );
    }
  };

  const handleZoomOut = () => {
    if (webglActive) {
      webglCamRef.current?.zoomBy(0.7);
      return;
    }
    if (svgRef.current && zoomRef.current) {
      const svg = d3.select(svgRef.current);
      cameraTransition(svg, 300).call(
        zoomRef.current.scaleBy as any, 0.7
      );
    }
  };

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
    if (!bounds || bounds.width < 1 || bounds.height < 1) return null;

    let minX = bounds.x;
    let minY = bounds.y;
    let maxX = bounds.x + bounds.width;
    let maxY = bounds.y + bounds.height;
    if (simNodes && simNodes.length > 0) {
      let found = 0;
      let nMinX = Infinity, nMinY = Infinity, nMaxX = -Infinity, nMaxY = -Infinity;
      const pad = Math.max(24, (settings.nodeSize || 20) * 1.2);
      for (const n of simNodes) {
        if ((n as any).__isChip || n.x == null || n.y == null) continue;
        if (!Number.isFinite(n.x) || !Number.isFinite(n.y)) continue;
        found++;
        nMinX = Math.min(nMinX, n.x - pad);
        nMinY = Math.min(nMinY, n.y - pad);
        nMaxX = Math.max(nMaxX, n.x + pad);
        nMaxY = Math.max(nMaxY, n.y + pad);
      }
      if (found > 0) {
        minX = nMinX; minY = nMinY; maxX = nMaxX; maxY = nMaxY;
      }
    }
    const bw = Math.max(1, maxX - minX);
    const bh = Math.max(1, maxY - minY);
    let scale = Math.min(0.9 / Math.max(bw / w, bh / h), 2);

    scale = Math.max(scale, 0.55);
    const tx = w / 2 - scale * ((minX + maxX) / 2);
    const ty = h / 2 - scale * ((minY + maxY) / 2);
    return d3.zoomIdentity.translate(tx, ty).scale(scale);
  }, [visualizationType, filteredEdges, settings.nodeSize]);

  const handleFit = () => {
    if (webglActive) {
      webglCamRef.current?.fitAll();
      return;
    }
    if (svgRef.current && zoomRef.current) {
      const target = computeSmartFitTransform();
      if (!target) return;
      const svg = d3.select(svgRef.current);
      cameraTransition(svg).call(zoomRef.current.transform as any, target);
    }
  };
  const handleFitRef = useRef<() => void>(() => {});
  handleFitRef.current = handleFit;

  useEffect(() => {
    if (!viewportFitToken || webglActive) return;
    userInteractedRef.current = false;
    const t1 = setTimeout(() => handleFitRef.current(), 450);
    const t2 = setTimeout(() => handleFitRef.current(), 1800);
    return () => { clearTimeout(t1); clearTimeout(t2); };
  }, [viewportFitToken, webglActive]);

  const autoFitKey = `${visualizationType}:${ontographLayoutType}:${webglActive}`;
  useEffect(() => {
    if (webglActive) return;
    const t1 = setTimeout(() => handleFitRef.current(), 450);
    const t2 = setTimeout(() => handleFitRef.current(), 1800);
    return () => { clearTimeout(t1); clearTimeout(t2); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoFitKey]);

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

  const handleExport = async (format: ExportFormat) => {

    if (webglActive) {
      if (format === 'svg') {
        console.info('[Graph Export] WebGL has no SVG export — downloading PNG instead.');
      }
      const blob = await webglCamRef.current?.capturePng();
      if (!blob) {
        console.error('[Graph Export] WebGL PNG capture failed');
        return;
      }
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `ontology-graph-${projectId}.png`;
      link.click();
      URL.revokeObjectURL(url);
      return;
    }

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

        const bbox = svgElement.getBBox();
        const width = Math.max(bbox.width + 40, 800);
        const height = Math.max(bbox.height + 40, 600);

        const clonedSvg = svgElement.cloneNode(true) as SVGSVGElement;
        clonedSvg.setAttribute('width', width.toString());
        clonedSvg.setAttribute('height', height.toString());
        clonedSvg.setAttribute('viewBox', `${bbox.x - 20} ${bbox.y - 20} ${width} ${height}`);

        const inlineComputedStyles = (source: Element, target: Element) => {
          if (source instanceof Element && target instanceof Element) {
            try {
              const cs = window.getComputedStyle(source);
              const styleBits: string[] = [];
              for (const prop of ['fill', 'stroke', 'stroke-width', 'opacity', 'font-size', 'font-family', 'color'] as const) {
                const v = cs.getPropertyValue(prop);
                if (v) styleBits.push(`${prop}:${v}`);
              }
              if (styleBits.length) {
                const prev = target.getAttribute('style') || '';
                target.setAttribute('style', `${prev};${styleBits.join(';')}`);
              }
            } catch { /* ignore */ }
          }
          const sChildren = source.children;
          const tChildren = target.children;
          for (let i = 0; i < sChildren.length && i < tChildren.length; i++) {
            inlineComputedStyles(sChildren[i], tChildren[i]);
          }
        };
        inlineComputedStyles(svgElement, clonedSvg);

        const svgData = new XMLSerializer().serializeToString(clonedSvg);

        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext('2d');

        if (!ctx) {
          console.error('Failed to get canvas context');
          return;
        }

        const img = new Image();
        const svgBlob = new Blob([svgData], { type: 'image/svg+xml;charset=utf-8' });
        const url = URL.createObjectURL(svgBlob);

        img.onerror = (e) => {
          console.error('Failed to load SVG image for PNG export:', e);
          URL.revokeObjectURL(url);
        };

        img.onload = () => {
          URL.revokeObjectURL(url);

          ctx.fillStyle = '#ffffff';
          ctx.fillRect(0, 0, width, height);

          ctx.drawImage(img, 0, 0);

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

        img.src = url;
      } catch (error) {
        console.error('PNG export failed:', error);
      }
    }

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

  useEffect(() => {

    if (!projectId) {
      console.warn('[AdvancedGraphView] No projectId, skipping initial fetch');
      return;
    }

    setAllNodes([]);
    setAllEdges([]);
    updateHierarchyState(() => ({
      visible: new Set<string>(),
      expanded: new Set<string>()
    }));

    console.log('[AdvancedGraphView] 🚀 Initial mount or projectId changed, fetching graph data for:', projectId);
    fetchGraphData();
  }, [projectId]); // Remove fetchGraphData from dependencies to avoid stale closure issues

  useEffect(() => {
    if (!projectId) return;
    let refetchQueued = false;
    const handleMutation = () => {
      if (refetchQueued) return;
      refetchQueued = true;

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

  useEffect(() => {
    const handleClickOutside = () => {
      if (contextMenu.visible) {
        setContextMenu({ ...contextMenu, visible: false });
      }
    };

    document.addEventListener('click', handleClickOutside);
    return () => document.removeEventListener('click', handleClickOutside);
  }, [contextMenu]);

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

  const nodeRelationsMap = useMemo(() => {
    const index = buildChildrenParentsIndex(allNodes, allEdges);
    const relations = new Map<string, { children: string[]; parents: string[]; hasChildren: boolean; hasParents: boolean }>();

    allNodes.forEach(node => {
      const entry = index.get(node.id) || { children: [], parents: [] };
      relations.set(node.id, {
        children: entry.children,
        parents: entry.parents,
        hasChildren: entry.children.length > 0,
        hasParents: entry.parents.length > 0
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
          {}
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

          {}
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
            {}
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
              title={node.label}
              style={{
                fontSize: '13px',
                fontWeight: selectedNodes.has(node.id) ? '600' : '400',

                color: 'var(--text-primary, #1f2937)',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap'
              }}
            >
              {node.label}
            </span>
          </div>

          {}
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

          {}
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

        {}
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

  const hierarchyNavigatorBody = useMemo(() => {
    if (!hierarchyRootNode) return null;
    return (
      <>
        {}
        <div
          style={{
            padding: '8px 12px',
            backgroundColor: 'var(--surface-2, #f9fafb)',
            borderBottom: '1px solid var(--border, #e5e7eb)'
          }}
        >
          <div style={{ fontSize: '13px', fontWeight: '600', color: 'var(--text-primary, #1f2937)', marginBottom: '2px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={hierarchyRootNode.label}>
            {hierarchyRootNode.label}
          </div>
          <div style={{ fontSize: '10px', color: 'var(--text-secondary, #9ca3af)', fontFamily: 'monospace', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={hierarchyRootNode.id}>
            {hierarchyRootNode.id}
          </div>
        </div>

        {}
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

        {}
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

  return (
    <div
      className="advanced-graph-view-d3"
      style={styles.container}
      data-testid="graph-view"
      data-graph-loading={loading ? 'true' : 'false'}
      data-graph-mode={visualizationType}
      data-graph-entrance={entrancePhase}
    >
      {}
      <PluginUpdateService
        currentVersion="3.1.0"
        pluginId="graph-view-plugin"
        checkInterval={60 * 60 * 1000}
      />

      {}
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

      {}
      <div style={styles.mainRow}>
        {}
        <div style={styles.firstColumn}>
          {}
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
            showAnalytics={showAnalytics}
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

              setSettings(prev => prev.renderer === 'webgl' ? prev : { ...prev, renderer: 'webgl' });
              const { newExpandedIds, newVisibleIds, capped } = networkGraphVisibility(allNodes, allEdges);
              updateHierarchyState(() => ({ visible: newVisibleIds, expanded: newExpandedIds }));
              requestViewportFitAfterBulkExpand();
              if (capped) {
                setClassActionFeedback({
                  type: 'success',
                  message: `Network shows ~${NETWORK_VISIBILITY_NODE_BUDGET} of ${allNodes.length} nodes — expand branches or use Expand All for more.`
                });
              }
            }}
            onPresetTree={() => {
              setSettings(prev => prev.renderer === 'webgl' ? { ...prev, renderer: 'svg' } : prev);
              setVisualizationType('ontograph');
              setOntographLayoutType('tree');

              const { newExpandedIds, newVisibleIds, capped } = networkGraphVisibility(allNodes, allEdges);
              updateHierarchyState(() => ({ visible: newVisibleIds, expanded: newExpandedIds }));
              requestViewportFitAfterBulkExpand();
              if (capped) {
                setClassActionFeedback({
                  type: 'success',
                  message: `Tree shows ~${NETWORK_VISIBILITY_NODE_BUDGET} of ${allNodes.length} nodes — expand branches for more.`
                });
              }
            }}
            onSetVisualizationType={(next) => {

              setSettings(prev => prev.renderer === 'webgl' ? { ...prev, renderer: 'svg' } : prev);
              setVisualizationType(next);
              if (next === 'ontograph' && ontographLayoutType === 'spring') {
                setOntographLayoutType('tree');
              }

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
              if (webglActive) {
                webglCamRef.current?.reset();
                return;
              }
              if (svgRef.current && zoomRef.current) {
                cameraTransition(d3.select(svgRef.current)).call(zoomRef.current.transform as any, d3.zoomIdentity);
              }
            }}
            onExpandAll={() => {
              const { newExpandedIds, newVisibleIds } = expandAllNodes(allNodes);
              updateHierarchyState(() => ({ visible: newVisibleIds, expanded: newExpandedIds }));
              requestViewportFitAfterBulkExpand();
              if (allNodes.length > NETWORK_VISIBILITY_NODE_BUDGET) {
                setClassActionFeedback({
                  type: 'success',
                  message: `Showing all ${allNodes.length} nodes — expect a dense layout; zoom in or Collapse All + expand branches for clarity.`
                });
              }
            }}
            onCollapseAll={() => {
              const { newExpandedIds, newVisibleIds } = collapseAllNodes(allNodes, allEdges);
              updateHierarchyState(() => ({ visible: newVisibleIds, expanded: newExpandedIds }));
              requestViewportFitAfterBulkExpand();
            }}
            onToggleEdit={() => setEditMode(!editMode)}
            onToggleSearch={() => setShowSearch(!showSearch)}
            onToggleFilters={() => {
              setShowFilters((prev) => {
                const next = !prev;
                if (next) setShowPropertyPanel(true);
                return next;
              });
            }}
            onToggleSettings={() => {
              setShowSettings(!showSettings);
              setShowPropertyPanel(true); // Always show sidebar when settings clicked
            }}
            onToggleExplorer={() => setShowPropertyPanel(!showPropertyPanel)}
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
            onEnterFocus={() => { if (selectedNodeInfo) enterFocusMode(selectedNodeInfo.id); }}
            onExitFocus={exitFocusMode}
            onExport={handleExport}
          />

          {}
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

          {}
          <div style={styles.graphWorkspace}>
          <div style={{ ...styles.graphContentArea, flex: 1, position: 'relative' }}>
            {}
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
            {}
            {webglActive && (
              <WebGLGraphView
                ref={webglCamRef}
                nodes={benchData?.nodes ?? filteredNodes}
                edges={benchData?.edges ?? filteredEdges}
                dark={isDarkTheme}
                nodeSize={settings.nodeSize}
                selectedNodeIds={selectedNodes}
                projectId={projectId}
                showGrid={showGrid}
                physicsEnabled={settings.physics}
                onNodeClick={(id) => {

                  const found = allNodes.find(n => n.id === id);
                  setSelectedNodes(new Set([id]));
                  if (found) setSelectedNodeInfo(found);
                }}
                onNodeRightClick={(id, pos) => {

                  setContextMenu({ visible: true, x: pos.x, y: pos.y, nodeId: id });
                }}
                hasNodeChildren={(id) => nodeRelationsMap.get(id)?.hasChildren ?? false}
                isNodeExpanded={(id) => expandedNodeIds.has(id)}
                onToggleNodeChildren={(id) => handleToggleExpansion(id)}
                onGoToEntity={onNodeClick ? (id) => onNodeClickRef.current?.(id) : undefined}
                canEdit={canEdit && !readonly}
                onRenameNode={renameClassLabel}
                onDeleteNode={startDeleteClassAction}
                onAddChildNode={(id) => startCreateClassAction('child', id)}
                dimFocusIds={searchFilterMode === 'dim' && searchFocusIds.size > 0 ? searchFocusIds : null}
                viewportFitToken={viewportFitToken}
                searchPanelOpen={showSearch}
              />
            )}
            {}
            {!webglActive && (
            <>
            {svgHoverCard && (
              <div
                data-testid="graph-svg-hovercard"
                onMouseEnter={() => {
                  if (svgHoverClearTimerRef.current) {
                    clearTimeout(svgHoverClearTimerRef.current);
                    svgHoverClearTimerRef.current = null;
                  }
                }}
                onMouseLeave={() => {
                  if (svgRenamingRef.current) return;
                  setSvgHoverCard(null);
                }}
                style={{
                  position: 'fixed',
                  left: Math.min(svgHoverCard.x, window.innerWidth - 300),
                  top: Math.min(svgHoverCard.y, window.innerHeight - 140),
                  zIndex: 40,
                  padding: '6px 12px',
                  borderRadius: 8,
                  fontSize: 12,
                  lineHeight: 1.5,
                  maxWidth: 280,
                  border: '1px solid var(--border, #d1d5db)',
                  backgroundColor: isDarkTheme ? '#1f2937e6' : '#ffffffe6',
                  color: isDarkTheme ? '#e5e7eb' : '#1f2937',
                  backdropFilter: 'blur(4px)',
                  boxShadow: '0 8px 20px rgba(0,0,0,0.18)'
                }}
              >
                {svgRenaming?.id === svgHoverCard.id ? (
                  <input
                    autoFocus
                    value={svgRenaming.value}
                    onChange={(e) => setSvgRenaming({ id: svgRenaming.id, value: e.target.value })}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        const value = svgRenaming.value.trim();
                        setSvgRenaming(null);
                        if (value && value !== svgHoverCard.label) renameClassLabel(svgHoverCard.id, value);
                      } else if (e.key === 'Escape') {
                        setSvgRenaming(null);
                      }
                    }}
                    style={{
                      width: '100%',
                      padding: '2px 6px',
                      borderRadius: 6,
                      fontSize: 12,
                      border: '1px solid var(--border, #d1d5db)',
                      backgroundColor: isDarkTheme ? '#111827' : '#ffffff',
                      color: 'inherit',
                      outline: 'none'
                    }}
                  />
                ) : (
                  <strong title={svgHoverCard.label}>{svgHoverCard.label}</strong>
                )}
                <div style={{ opacity: 0.75 }}>{svgHoverCard.type}</div>
                <div style={{ display: 'flex', gap: 6, marginTop: 5, flexWrap: 'wrap' }}>
                  {[
                    {
                      label: expandedNodeIds.has(svgHoverCard.id) ? '➖ Collapse' : '➕ Expand',
                      show: nodeRelationsMap.get(svgHoverCard.id)?.hasChildren ?? false,
                      onClick: () => handleToggleExpansion(svgHoverCard.id)
                    },
                    {
                      label: '📄 Entity',
                      show: !!onNodeClick,
                      onClick: () => onNodeClickRef.current?.(svgHoverCard.id)
                    },
                    {
                      label: '✏️ Rename',
                      show: canEdit && !readonly && svgHoverCard.type === 'class' && svgRenaming?.id !== svgHoverCard.id,
                      onClick: () => setSvgRenaming({ id: svgHoverCard.id, value: svgHoverCard.label })
                    },
                    {
                      label: '🌱 Sub',
                      show: canEdit && !readonly && svgHoverCard.type === 'class',
                      onClick: () => startCreateClassAction('child', svgHoverCard.id)
                    },
                    {
                      label: '🗑 Delete',
                      show: canEdit && !readonly && svgHoverCard.type === 'class',
                      onClick: () => startDeleteClassAction(svgHoverCard.id)
                    }
                  ].filter((a) => a.show).map((action) => (
                    <button
                      key={action.label}
                      onClick={(e) => {
                        e.stopPropagation();
                        action.onClick();
                      }}
                      style={{
                        padding: '2px 8px',
                        borderRadius: 10,
                        fontSize: 11,
                        cursor: 'pointer',
                        border: '1px solid var(--border, #d1d5db)',
                        backgroundColor: isDarkTheme ? '#111827' : '#f9fafb',
                        color: 'inherit'
                      }}
                    >
                      {action.label}
                    </button>
                  ))}
                </div>
              </div>
            )}
            <svg
              ref={svgRef}
              data-testid="graph-svg"
              style={visualizationType === 'spatial3d' ? { ...styles.svg, ...styles.spatial3dSvg } : styles.svg}
            >
                <defs>
                  {}
                  {showGrid && (
                    <pattern id="grid" width="20" height="20" patternUnits="userSpaceOnUse">
                      <path d="M 20 0 L 0 0 0 20" fill="none" stroke="#e5e5e5" strokeWidth="0.5" />
                    </pattern>
                  )}
                  {}
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
                {}
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
            </>
            )}

            {visualizationType === 'spatial3d' && (
              <div style={styles.spatial3dHint}>
                <Box size={14} />
                3D Spatial Graph: wheel to zoom, drag to pan, drag nodes to reshape the graph, double-click to expand branches.
              </div>
            )}

            {}
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

            {}
            {showSearch && (() => {
              const depthSeeds = getDepthSeeds();
              const hasSeeds = depthSeeds.length > 0;
              const seedStatus = selectedNodes.size > 0
                ? `Using: ${selectedNodes.size} selected node${selectedNodes.size === 1 ? '' : 's'}`
                : (searchQuery.trim() && searchFocusIds.size > 0)
                  ? `Using: search matches (${searchFocusIds.size})`
                  : searchFocusIds.size > 0
                    ? `Using: ${searchFocusIds.size} focus node${searchFocusIds.size === 1 ? '' : 's'}`
                    : 'Nothing selected';
              const selectHint = webglActive
                ? 'Click a node on the graph to select it.'
                : 'Click a node on the graph to select it. Ctrl/Cmd+click to multi-select.';
              const sectionLabel: React.CSSProperties = {
                fontSize: 11,
                fontWeight: 600,
                letterSpacing: '0.04em',
                textTransform: 'uppercase',
                color: 'var(--text-tertiary, var(--text-secondary))',
                marginBottom: 6
              };
              const actionBtn = (enabled: boolean): React.CSSProperties => ({
                flex: '1 1 auto',
                minWidth: 0,
                padding: '7px 8px',
                borderRadius: 6,
                border: '1px solid var(--border)',
                background: enabled ? 'var(--surface-2)' : 'var(--surface-1)',
                color: enabled ? 'var(--text-primary)' : 'var(--text-tertiary, var(--text-secondary))',
                fontSize: 12,
                fontWeight: 500,
                cursor: enabled ? 'pointer' : 'not-allowed',
                opacity: enabled ? 1 : 0.55,
                textAlign: 'left' as const
              });
              return (
          <div style={styles.searchPanel}>
            <div style={styles.panelHeader}>
              <Search size={18} />
              <h3 style={styles.panelTitle}>Search</h3>
              <button onClick={() => setShowSearch(false)} style={styles.closeBtn}>×</button>
            </div>
            <input
              type="text"
              placeholder="Search / filter nodes…"
              value={searchQuery}
              onChange={(e) => handleSearch(e.target.value)}
              style={styles.searchInput}
            />

            {}
            <div style={{ padding: '10px 12px 8px' }}>
              <div style={sectionLabel}>Filter mode</div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                {([
                  { id: 'dim' as const, label: 'Fade others', desc: 'Keep the full graph; fade non-matches' },
                  { id: 'hide' as const, label: 'Hide others', desc: 'Show matches only; hide the rest' }
                ]).map((opt) => {
                  const selected = searchFilterMode === opt.id;
                  return (
                    <button
                      key={opt.id}
                      type="button"
                      onClick={() => handleFilterModeChange(opt.id)}
                      style={{
                        display: 'flex',
                        alignItems: 'flex-start',
                        gap: 10,
                        width: '100%',
                        textAlign: 'left',
                        padding: '8px 10px',
                        borderRadius: 8,
                        border: selected ? '1px solid var(--accent)' : '1px solid var(--border)',
                        background: selected ? 'var(--accent-tint)' : 'var(--surface-1)',
                        color: 'var(--text-primary)',
                        cursor: 'pointer',
                        boxShadow: selected ? 'inset 0 0 0 1px var(--accent)' : 'none'
                      }}
                    >
                      <span
                        aria-hidden
                        style={{
                          width: 14,
                          height: 14,
                          marginTop: 2,
                          borderRadius: '50%',
                          flexShrink: 0,
                          border: selected ? '4px solid var(--accent)' : '2px solid var(--border)',
                          background: 'var(--surface-1)',
                          boxSizing: 'border-box'
                        }}
                      />
                      <span style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
                        <span style={{ fontWeight: 600, fontSize: 13, color: selected ? 'var(--accent)' : 'var(--text-primary)' }}>
                          {opt.label}
                        </span>
                        <span style={{ fontSize: 11, color: 'var(--text-secondary)', lineHeight: 1.35 }}>
                          {opt.desc}
                        </span>
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>

            {}
            <div style={{ padding: '4px 12px 10px' }}>
              <div style={sectionLabel}>Depth</div>
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--text-secondary)' }}>
                <span style={{ flex: 1, lineHeight: 1.35 }}>
                  Levels to expand with “Expand to depth” (also used when searching)
                </span>
                <select
                  value={searchFilterDepth}
                  onChange={(e) => {
                    const next = Number(e.target.value);
                    setSearchFilterDepth(next);
                    if (searchQuery.trim()) {
                      handleSearch(searchQuery, next, searchFilterMode);
                      flashSearchHint(`Search depth set to ${next}`);
                    } else if (getDepthSeeds().length > 0) {
                      handleDeepDive(next);
                      flashSearchHint(`Expanded to depth ${next}`);
                    } else {
                      flashSearchHint(`Depth set to ${next}`);
                    }
                  }}
                  style={{
                    border: '1px solid var(--border)',
                    borderRadius: 4,
                    padding: '4px 8px',
                    background: 'var(--surface-1)',
                    color: 'var(--text-primary)',
                    flexShrink: 0
                  }}
                >
                  {Array.from({ length: 16 }, (_, d) => d).map((d) => (
                    <option key={d} value={d} style={{ background: 'var(--surface-1)', color: 'var(--text-primary)' }}>{d}</option>
                  ))}
                </select>
              </label>
            </div>

            {}
            <div style={{ padding: '4px 12px 10px', borderTop: '1px solid var(--border)' }}>
              <div style={{ ...sectionLabel, marginTop: 8 }}>Expand around selection</div>
              <div style={{ fontSize: 11, color: 'var(--text-secondary)', lineHeight: 1.4, marginBottom: 8 }}>
                {selectHint}
                {' '}Or type a search above — buttons then use search matches.
              </div>
              <div style={{
                fontSize: 11,
                fontWeight: 600,
                color: hasSeeds ? 'var(--accent)' : 'var(--text-tertiary, var(--text-secondary))',
                marginBottom: 8
              }}>
                {seedStatus}
              </div>
              {!hasSeeds && (
                <div style={{
                  fontSize: 12,
                  color: 'var(--text-secondary)',
                  background: 'var(--surface-2)',
                  border: '1px dashed var(--border)',
                  borderRadius: 6,
                  padding: '8px 10px',
                  marginBottom: 8
                }}>
                  Select a node on the graph first — then expand or collapse its neighborhood.
                </div>
              )}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <button
                  type="button"
                  onClick={() => {
                    handleDeepDive(searchFilterDepth);
                    flashSearchHint(`Expanded to depth ${searchFilterDepth}`);
                  }}
                  disabled={!hasSeeds}
                  title={`Expand around selection / matches out to depth ${searchFilterDepth}`}
                  style={actionBtn(hasSeeds)}
                >
                  <div style={{ fontWeight: 600 }}>Expand to depth {searchFilterDepth}</div>
                  <div style={{ fontSize: 10, fontWeight: 400, color: 'var(--text-secondary)', marginTop: 2 }}>
                    Show all neighbors within {searchFilterDepth} hop{searchFilterDepth === 1 ? '' : 's'}
                  </div>
                </button>
                <div style={{ display: 'flex', gap: 6 }}>
                  <button
                    type="button"
                    onClick={() => {
                      handleCollapseOneDepth();
                      flashSearchHint('Collapsed one level');
                    }}
                    disabled={!hasSeeds}
                    title="Hide one level of children under the selection / matches"
                    style={actionBtn(hasSeeds)}
                  >
                    <div style={{ fontWeight: 600 }}>Collapse one level</div>
                    <div style={{ fontSize: 10, fontWeight: 400, color: 'var(--text-secondary)', marginTop: 2 }}>
                      −1 hop
                    </div>
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      handleExpandOneDepth();
                      flashSearchHint('Expanded one level');
                    }}
                    disabled={!hasSeeds}
                    title="Reveal one more level of children under the selection / matches"
                    style={actionBtn(hasSeeds)}
                  >
                    <div style={{ fontWeight: 600 }}>Expand one level</div>
                    <div style={{ fontSize: 10, fontWeight: 400, color: 'var(--text-secondary)', marginTop: 2 }}>
                      +1 hop
                    </div>
                  </button>
                </div>
              </div>
              {searchActionHint && (
                <div style={{ marginTop: 8, fontSize: 11, color: 'var(--accent)' }}>
                  {searchActionHint}
                </div>
              )}
            </div>

            {searchQuery && (
              <div style={{
                padding: 12,
                background: 'var(--accent-tint)',
                borderRadius: 8,
                margin: '0 12px 12px',
                border: '1px solid var(--border)'
              }}>
                <div style={{ fontSize: 12, color: 'var(--accent)', marginBottom: 4, fontWeight: 600 }}>
                  {searchFilterMode === 'hide'
                    ? 'Hide others — only focused nodes shown'
                    : 'Fade others — non-matches faded'}
                </div>
                <div style={{ fontSize: 13, color: 'var(--text-primary)' }}>
                  {getExpansionStats(allNodes.length, visibleNodeIds.size, expandedNodeIds.size)}
                  {searchFocusIds.size > 0 ? ` · focus ${searchFocusIds.size}` : ''}
                </div>
                <button
                  onClick={() => handleSearch('')}
                  style={{
                    marginTop: 8,
                    padding: '6px 12px',
                    background: 'var(--accent)',
                    color: '#fff',
                    border: 'none',
                    borderRadius: 6,
                    cursor: 'pointer',
                    fontSize: 12,
                    fontWeight: 600
                  }}
                >
                  Clear filter
                </button>
              </div>
            )}
            {!searchQuery && (
              <div style={styles.searchResults}>
                {visibleNodeIds.size} visible nodes
              </div>
            )}
          </div>
              );
            })()}

        </div>

          </div>
      </div>

      {}
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
          }}
          onHighlightGap={(gap) => {
            const nodeA = filteredNodes.find(n => n.label === gap.labelA);
            const nodeB = filteredNodes.find(n => n.label === gap.labelB);
            if (nodeA) {
              setSelectedNodeInfo(nodeA);
            } else if (nodeB) {
              setSelectedNodeInfo(nodeB);
            }
          }}
          onClose={() => setShowAnalytics(false)}
        />
      )}

        {}
        {showPropertyPanel && (
          <div style={{ position: 'relative', display: 'flex', minWidth: 0 }}>
          {}
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

                if (!visibleNodeIds.has(node.id)) {
                  const path = findPathToNode(node.id, allEdges, allNodes);
                  updateHierarchyState(prev => {
                    const newVisible = new Set(prev.visible);
                    const newExpanded = new Set(prev.expanded);
                    for (let i = 0; i < path.length - 1; i++) {
                      const ancestorId = path[i];
                      if (!newExpanded.has(ancestorId)) {
                        getChildren(ancestorId, allEdges, allNodes).forEach(cid => newVisible.add(cid));
                        newExpanded.add(ancestorId);
                      }
                    }
                    newVisible.add(node.id);
                    return { visible: newVisible, expanded: newExpanded };
                  });
                  if (visualizationType === 'vowl') {
                    pendingToggleFrameRef.current = node.id;
                  }
                }

                if (node.type === 'class') {
                  setHierarchyRootNode(node);
                }

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

              if (simulationRef.current) {
                simulationRef.current.alpha(0.3).restart();
              }
            }}
            onDatatypeDistanceChange={(distance) => {
              setDatatypeDistance(distance);

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
            vowlLegend={showLegend ? dynamicLegend : []}
            showLegend={showLegend}
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

        {}
        {showHierarchyDialog && hierarchyRootNode && (
          <div
            style={{
              position: 'fixed',
              left: `${hierarchyDialogPosition.x}px`,
              top: `${hierarchyDialogPosition.y}px`,
              width: isDialogMinimized ? 'auto' : `${hierarchyDialogSize.width}px`,
              height: isDialogMinimized ? 'auto' : `${hierarchyDialogSize.height}px`,
              minWidth: isDialogMinimized ? '220px' : '280px',
              minHeight: isDialogMinimized ? 'auto' : '240px',
              maxWidth: 'calc(100vw - 16px)',
              maxHeight: 'calc(100vh - 16px)',
              backgroundColor: 'var(--surface-1, #fff)',
              border: '1px solid var(--border, #d1d5db)',
              borderRadius: '8px',
              boxShadow: '0 10px 25px rgba(0,0,0,0.15)',
              zIndex: 1000,
              display: 'flex',
              flexDirection: 'column',
              overflow: 'hidden'
            }}
            onClick={(e) => e.stopPropagation()}
          >
            {}
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

                if ((e.target as HTMLElement).tagName === 'BUTTON' || (e.target as HTMLElement).closest('button')) {
                  return;
                }

                const startX = e.clientX - hierarchyDialogPosition.x;
                const startY = e.clientY - hierarchyDialogPosition.y;

                const handleMouseMove = (moveEvent: MouseEvent) => {
                  const maxX = Math.max(0, window.innerWidth - (isDialogMinimized ? 220 : hierarchyDialogSize.width) - 8);
                  const maxY = Math.max(0, window.innerHeight - (isDialogMinimized ? 48 : hierarchyDialogSize.height) - 8);
                  setHierarchyDialogPosition({
                    x: Math.min(Math.max(0, moveEvent.clientX - startX), maxX),
                    y: Math.min(Math.max(0, moveEvent.clientY - startY), maxY)
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

            {}
            {!isDialogMinimized && hierarchyNavigatorBody}
            {!isDialogMinimized && (
              <div
                title="Resize"
                onMouseDown={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  const startX = e.clientX;
                  const startY = e.clientY;
                  const startW = hierarchyDialogSize.width;
                  const startH = hierarchyDialogSize.height;
                  const startLeft = hierarchyDialogPosition.x;
                  const startTop = hierarchyDialogPosition.y;
                  const handleMouseMove = (moveEvent: MouseEvent) => {
                    const nextW = Math.min(
                      Math.max(280, startW + (moveEvent.clientX - startX)),
                      Math.max(280, window.innerWidth - startLeft - 8)
                    );
                    const nextH = Math.min(
                      Math.max(240, startH + (moveEvent.clientY - startY)),
                      Math.max(240, window.innerHeight - startTop - 8)
                    );
                    setHierarchyDialogSize({ width: nextW, height: nextH });
                  };
                  const handleMouseUp = () => {
                    document.removeEventListener('mousemove', handleMouseMove);
                    document.removeEventListener('mouseup', handleMouseUp);
                  };
                  document.addEventListener('mousemove', handleMouseMove);
                  document.addEventListener('mouseup', handleMouseUp);
                }}
                style={{
                  position: 'absolute',
                  right: 0,
                  bottom: 0,
                  width: 16,
                  height: 16,
                  cursor: 'se-resize',
                  background:
                    'linear-gradient(135deg, transparent 50%, rgba(100,116,139,0.55) 50%)',
                  borderBottomRightRadius: 8
                }}
              />
            )}
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

        {}
        {loading && (
          <div style={styles.loadingOverlay} data-testid="graph-loading">
            <RefreshCw size={32} className="spinning" />
            <div style={{ marginTop: '12px' }}>Loading graph...</div>
          </div>
        )}

        {}
        {error && (
          <div style={styles.errorPanel}>
            <AlertTriangle size={20} color="#ef4444" />
            <div>{error}</div>
          </div>
        )}

        {}
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
            <button
              style={styles.contextMenuItem}
              onClick={() => {
                if (contextMenu.nodeId) onNodeClickRef.current?.(contextMenu.nodeId);
                setContextMenu({ ...contextMenu, visible: false });
              }}
            >
              📄 Go to Entity
            </button>
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
                🌿 Hierarchy Navigator
              </button>
            )}
          </div>
        )}

      {}
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

      {}
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
    width: '320px',
    maxHeight: 'calc(100% - 40px)',
    overflowX: 'hidden',
    overflowY: 'auto',
    background: 'var(--surface-1)',
    borderRadius: '12px',
    border: '1px solid var(--border)',
    boxShadow: '0 10px 40px rgba(0,0,0,0.1)',
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

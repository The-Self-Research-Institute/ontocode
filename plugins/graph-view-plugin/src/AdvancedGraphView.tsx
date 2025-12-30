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
  Users,
  Maximize,
  MinusSquare
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
import { vowlNotationService } from './services/VOWLNotationService';
import { UnifiedSidebar } from './components/UnifiedSidebar';
import { GraphViewSidebar } from './components/GraphViewSidebar';
import { createGraphDataFetchService } from './services/GraphDataFetchService';
import { MatrixView } from './components/MatrixView';
import { StatsDashboard } from './components/StatsDashboard';
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
  getExpansionStats,
  findPathToNode
} from './HierarchicalLazyLoading';

// D3 types
interface D3Node extends OntologyNode, d3.SimulationNodeDatum {
  x?: number;
  y?: number;
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

// Color schemes
const TYPE_COLORS: Record<NodeType, string> = {
  class: '#667eea',
  individual: '#10b981',
  property: '#f59e0b',
  dataProperty: '#ec4899',
  objectProperty: '#06b6d4',
  annotation: '#8b5cf6',
  datatype: '#FFA500'
};

const EDGE_TYPE_COLORS: Record<EdgeType, string> = {
  subClassOf: '#2563eb', // Blue for Protégé style
  instanceOf: '#f59e0b', // Gold/Orange for Protégé style
  propertyRelation: '#059669', // Green for properties
  equivalentClass: '#ec4899',
  disjointWith: '#ef4444',
  domain: '#06b6d4',
  range: '#8b5cf6',
  inverseOf: '#fbbf24',
  custom: '#6b7280',
  temporal: '#34d399',
  spatial: '#3b82f6',
  probabilistic: '#fb923c',
  subPropertyOf: '#2563eb'
};

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
  tooltips: false  // Disabled by default to avoid tooltip issues
};

const DEFAULT_FILTERS: GraphFilters = {
  nodeTypes: new Set(['class', 'individual', 'datatype', 'objectProperty', 'dataProperty', 'annotation']), // All node types enabled by default
  edgeTypes: new Set(['subClassOf', 'instanceOf', 'propertyRelation', 'equivalentClass', 'domain', 'range', 'disjointWith', 'inverseOf', 'custom', 'subPropertyOf'])
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

  const thingNode = classes.find(
    node => node.label === 'Thing' || node.id === 'owl:Thing' || node.id.includes('owl#Thing')
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
  // Refs
  const svgRef = useRef<SVGSVGElement>(null);
  const gRef = useRef<SVGGElement>(null);
  const simulationRef = useRef<d3.Simulation<D3Node, D3Edge> | null>(null);
  const zoomRef = useRef<d3.ZoomBehavior<SVGSVGElement, unknown> | null>(null);

  // State - Hierarchical Lazy Loading
  const [allNodes, setAllNodes] = useState<OntologyNode[]>([]);  // All data from API
  const [allEdges, setAllEdges] = useState<OntologyEdge[]>([]);  // All edges from API
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
  const requestHostCollaborationPanel = useCallback(() => {
    dispatchHostEvent('graph-view:show-collaboration', { projectId });
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
  const [showPropertyPanel, setShowPropertyPanel] = useState(true);
  const [selectedNodeInfo, setSelectedNodeInfo] = useState<OntologyNode | null>(null);
  
  // Visualization Type (combines both view mode and visualization type)
  const [visualizationType, setVisualizationType] = useState<VisualizationType>('vowl');
  const [ontographLayoutType, setOntographLayoutType] = useState<'vertical' | 'horizontal' | 'radial' | 'grid' | 'tree' | 'spring'>('vertical');
  const [showLegend, setShowLegend] = useState(true);
  
  // WebVOWL Filters State (integrated into main sidebar)
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

  // Viewport state for large graph optimization (100k+ nodes)
  const [viewportBounds, setViewportBounds] = useState({ x: 0, y: 0, width: 0, height: 0, scale: 1 });
  const [isLargeGraph, setIsLargeGraph] = useState(false);

  // Settings & Filters
  const [settings, setSettings] = useState<GraphSettings>(DEFAULT_SETTINGS);
  const [filters, setFilters] = useState<GraphFilters>(DEFAULT_FILTERS);
  const [searchQuery, setSearchQuery] = useState('');
  const [sidebarSearchTerm, setSidebarSearchTerm] = useState('');

  // VOWL Controls
  const [classDistance, setClassDistance] = useState(100);
  const [datatypeDistance, setDatatypeDistance] = useState(100);
  const [isLayoutPaused, setIsLayoutPaused] = useState(false);

  // Panels
  const [showSearch, setShowSearch] = useState(false);
  const [showFilters, setShowFilters] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showGrid, setShowGrid] = useState(false);

  // Advanced features
  const [zoomLevel, setZoomLevel] = useState(1);

  // Compute visible nodes and edges based on hierarchy
  const visibleNodes = useMemo(() => {
    const filtered = allNodes.filter(n => visibleNodeIds.has(n.id));
    console.log('[AdvancedGraphView] 🔍 visibleNodes memo - allNodes:', allNodes.length, 'visibleNodeIds:', visibleNodeIds.size, 'filtered:', filtered.length);
    console.log('[AdvancedGraphView] 🔍 Visible node types:', filtered.reduce((acc, n) => {
      acc[n.type] = (acc[n.type] || 0) + 1;
      return acc;
    }, {} as Record<string, number>));
    return filtered;
  }, [allNodes, visibleNodeIds]);

  const visibleEdges = useMemo(() => {
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
  }, [allEdges, visibleNodeIds, allNodes.length, allNodes]);

  /**
   * ========================================================================
   * FILTERING
   * ========================================================================
   */
  // Helper to determine if a node is external
  const isExternalNode = useCallback((node: any) => {
    if (!node) return false;
    return (
      node.label?.toLowerCase().includes('external') || 
      ['Item', 'UserAccount', 'Concept'].includes(node.label || '') ||
      (node.metadata?.iri && !node.metadata.iri.includes(projectId))
    );
  }, [projectId]);

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

    let filtered = visibleNodes.filter(node => filters.nodeTypes.has(node.type));

    // **WebVOWL/OntoGraph Mode: Apply specific entity filters**
    if (visualizationType === 'vowl' || visualizationType === 'ontograph') {
      const beforeFilter = filtered.length;
      
      // In VOWL mode, we hide property nodes (they are edges)
      if (visualizationType === 'vowl') {
        filtered = filtered.filter(node => 
          node.type === 'class' || 
          node.type === 'individual' || 
          node.type === 'datatype'
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
      
      console.log(`[Filtering] ${visualizationType} mode: After vowlFilters, showing ${filtered.length} nodes (from ${beforeFilter})`);
    }
    
    // **Force Mode: Hide property nodes (objectProperty, dataProperty, annotation)**
    // Properties should only appear as edges, not as nodes in force mode
    if (visualizationType === 'force') {
      filtered = filtered.filter(node => 
        node.type === 'class' || 
        node.type === 'individual' || 
        node.type === 'datatype'
      );
      console.log(`[Filtering] Force mode: Filtered to classes, individuals, datatypes - ${filtered.length} nodes`);
    }

    // OntoGraph Mode: focus on class hierarchy and individuals for clean Protégé-style layout
    if (visualizationType === 'ontograph') {
      const beforeFilter = filtered.length;
      filtered = filtered.filter(node => node.type === 'class' || node.type === 'individual');
      console.log(`[Filtering] OntoGraph mode: Focused on classes and individuals ${beforeFilter} -> ${filtered.length}`);
    }

    console.log(`[Filtering] visibleNodes: ${visibleNodes.length}, after type filter: ${filtered.length}`);

    // Search filter (Note: Search now handled by handleSearch with path expansion)
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      filtered = filtered.filter(node =>
        node.label.toLowerCase().includes(query) ||
        node.id.toLowerCase().includes(query) ||
        node.description?.toLowerCase().includes(query)
      );
    }

    // Sidebar search filter - filters the visible graph
    if (sidebarSearchTerm) {
      const query = sidebarSearchTerm.toLowerCase();
      filtered = filtered.filter(node =>
        node.label.toLowerCase().includes(query) ||
        node.id.toLowerCase().includes(query) ||
        node.description?.toLowerCase().includes(query)
      );
      console.log(`[Filtering] After sidebar search "${sidebarSearchTerm}": ${filtered.length} nodes`);
    }

    console.log(`[Filtering] Final filtered nodes: ${filtered.length}`);

    return filtered;
  }, [visibleNodes, filters, searchQuery, sidebarSearchTerm, visualizationType, vowlFilters]);

  const filteredEdges = useMemo(() => {
    const nodeIds = new Set(filteredNodes.map(n => n.id));
    let filtered = visibleEdges.filter(edge =>
      filters.edgeTypes.has(edge.type) &&
      nodeIds.has(edge.from) &&
      nodeIds.has(edge.to)
    );
    
    // **WebVOWL/OntoGraph Mode: Apply specific relationship filters**
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
      // In OntoGraph, we want to see all relations between visible nodes
      filtered = filtered.filter(edge => {
        return edge.type === 'subClassOf' || 
               edge.type === 'propertyRelation' || 
               edge.type === 'instanceOf' ||
               edge.type === 'domain' ||
               edge.type === 'range';
      });
      console.log(`[Filtering] OntoGraph mode: Ensuring all edges are visible ${beforeFilter} -> ${filtered.length}`);
    }
    
    console.log('[AdvancedGraphView] Filtered edges:', filtered.length);
    if (filtered.length === 0 && visibleEdges.length > 0) {
      console.warn('[AdvancedGraphView] ⚠️ No edges after filtering! Check edge types.');
      console.warn('[AdvancedGraphView] Edge types in data:', [...new Set(visibleEdges.map(e => e.type))]);
    }
    
    return filtered;
  }, [visibleEdges, filteredNodes, filters, visualizationType, vowlFilters, allNodes]);

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
      
      // Detect dark mode for theme-aware legend colors
      const isDark = document.documentElement.classList.contains('dark');
      
      // Add node type legends with counts - theme aware
      if (hasThing) legend.push({ name: 'Thing', type: 'node', nodeType: 'class', color: isDark ? '#374151' : '#ffffff' });
      if (externalClasses.length > 0) legend.push({ name: `External Class (${externalClasses.length})`, type: 'node', nodeType: 'class', color: isDark ? '#60a5fa' : '#4682b4' });
      if (internalClasses.length > 0) legend.push({ name: `Internal Class (${internalClasses.length})`, type: 'node', nodeType: 'class', color: isDark ? '#6b92c4' : '#acd5f2' });
      if (hasDatatype) legend.push({ name: `Datatype (${filteredNodes.filter(n => n.type === 'datatype').length})`, type: 'node', nodeType: 'datatype', color: isDark ? '#d97706' : '#FFD9B3' });
      if (hasIndividual) legend.push({ name: `Individual (${filteredNodes.filter(n => n.type === 'individual').length})`, type: 'node', nodeType: 'individual', color: isDark ? '#fbb6ce' : '#dcd5f7' });
      
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

        if (objProps > 0) legend.push({ name: `Object Property (${objProps})`, type: 'edge', stroke: isDark ? '#22d3ee' : '#0891b2', strokeDasharray: '0' });
        if (dataProps > 0) legend.push({ name: `Data Property (${dataProps})`, type: 'edge', stroke: isDark ? '#f472b6' : '#db2777', strokeDasharray: '0' });
        if (annoProps > 0) legend.push({ name: `Annotation Property (${annoProps})`, type: 'edge', stroke: isDark ? '#a78bfa' : '#7c3aed', strokeDasharray: '0' });
      }
      
      if (edgeTypes.has('subClassOf')) legend.push({ name: `SubClass Of (${filteredEdges.filter(e => e.type === 'subClassOf').length})`, type: 'edge', stroke: isDark ? '#9ca3af' : '#374151', strokeDasharray: '5,5' });
      
      // Property label colors (if we have propertyRelation edges)
      if (edgeTypes.has('propertyRelation')) {
        legend.push({ name: 'Functional Property (F)', type: 'label', color: '#C8E6C9' });
      }
      
      return legend;
    } else {
      // Standard Mode (Force-directed & OntoGraph) - dynamic legend based on filtered nodes/edges
      const legend: Array<{ name: string; type: string; nodeType?: string; color?: string; stroke?: string; strokeDasharray?: string }> = [];
      
      // Node types from filtered nodes in current graph
      const nodeTypes = new Set(filteredNodes.map(n => n.type));
      
      // For force mode, use special colors for classes
      if (visualizationType === 'force') {
        if (nodeTypes.has('class')) legend.push({ name: `Class (${filteredNodes.filter(n => n.type === 'class').length})`, type: 'node', nodeType: 'class', color: '#FFE4B5' });
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
      
      return legend;
    }
  }, [visualizationType, filteredNodes, filteredEdges, isExternalNode, allNodes]);

  // Performance tracking (disabled for production performance)
  const renderTime = useRef(0);

  /**
   * ========================================================================
   * DATA FETCHING - Optimized GraphDB Direct Approach
   * ========================================================================
   */
  const fetchGraphData = useCallback(async () => {
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
      const cacheKey = `graph-${projectId}`;
      
      // ALWAYS fetch fresh data from GraphDB, skip cache for now to ensure real data loads
      console.log('[AdvancedGraphView D3] 🔄 Fetching fresh data (cache disabled for real-time GraphDB data)');

      const apiBaseUrl = (window as any).API_BASE_URL;
      const authToken = localStorage.getItem('authToken');
      
      // ALWAYS use GraphDB direct fetch for WebVOWL compatibility
      // (collaborative endpoint doesn't have property relation edges)
      console.log('[AdvancedGraphView D3] 🔵 Using GraphDB direct fetch for complete data...');
        
        // Fetch data from GraphDB using optimized service (same approach as webVOWL)
        const fetchService = createGraphDataFetchService(apiBaseUrl, projectId, authToken);
        const graphData = await fetchService.fetchGraphData();
        
        console.log('[GraphDB Fetch] ✅ Fetched from GraphDB:', {
          nodes: graphData.nodes.length,
          edges: graphData.edges.length,
          byType: {
            classes: graphData.nodes.filter(n => n.type === 'class').length,
            individuals: graphData.nodes.filter(n => n.type === 'individual').length,
            objectProperties: graphData.nodes.filter(n => n.type === 'objectProperty').length,
            dataProperties: graphData.nodes.filter(n => n.type === 'dataProperty').length,
            datatypes: graphData.nodes.filter(n => n.type === 'datatype').length,
            annotations: graphData.nodes.filter(n => n.type === 'annotation').length
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

        // Update state with fetched data
        setAllNodes(graphData.nodes);
        setAllEdges(graphData.edges);
        
        console.log('[AdvancedGraphView] ✅ Set allNodes:', graphData.nodes.length, 'allEdges:', graphData.edges.length);
        
        // Use collapseAllNodes function for consistent initial state
        // This shows root entities + first-level children for ALL entity types
        const { newExpandedIds, newVisibleIds } = collapseAllNodes(graphData.nodes, graphData.edges);
        
        updateHierarchyState(() => ({
          visible: newVisibleIds,
          expanded: newExpandedIds
        }));
        
        console.log('[AdvancedGraphView] ✅ Initial hierarchy state:', {
          visible: newVisibleIds.size,
          expanded: newExpandedIds.size,
          total: graphData.nodes.length
        });

        setLoading(false);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      setError(errorMessage);
      console.error('[AdvancedGraphView D3] ❌ Error fetching graph data:', errorMessage, error);
      setLoading(false);
    }
  }, [projectId, updateHierarchyState]);

  /**
   * ========================================================================
   * D3 VISUALIZATION - OPTIMIZED FOR PERFORMANCE
   * ========================================================================
   */
  useEffect(() => {
    if (!svgRef.current || filteredNodes.length === 0) return;

    const startTime = performance.now();
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

    const svg = d3.select(svgRef.current)
      .on('click', () => {
        setSelectedNodes(new Set());
        setSelectedEdgeId(null);
        setSelectedNodeInfo(null);
      });
    const g = d3.select(gRef.current);

    const width = svgRef.current.clientWidth;
    const height = svgRef.current.clientHeight;

    // Clear existing content
    g.selectAll('*').remove();

    // Create or update arrow markers for each edge type
    let defs = svg.select<SVGDefsElement>('defs');
    if (defs.empty()) {
      defs = svg.append('defs');
    }
    
    // Remove existing markers to ensure clean state
    defs.selectAll('marker').remove();
    
    const isDark = document.documentElement.classList.contains('dark');

    // VOWL-specific arrow markers - TRIANGLES with specific colors for property types
    // Object Property (Cyan)
    defs.append('marker')
      .attr('id', 'arrow-vowl-object')
      .attr('viewBox', '0 0 10 10')
      .attr('refX', 10).attr('refY', 5)
      .attr('markerWidth', 8).attr('markerHeight', 8)
      .attr('orient', 'auto')
      .append('path').attr('d', 'M0,0 L10,5 L0,10 Z')
      .attr('fill', isDark ? '#22d3ee' : '#0891b2');

    // Data Property (Pink)
    defs.append('marker')
      .attr('id', 'arrow-vowl-data')
      .attr('viewBox', '0 0 10 10')
      .attr('refX', 10).attr('refY', 5)
      .attr('markerWidth', 8).attr('markerHeight', 8)
      .attr('orient', 'auto')
      .append('path').attr('d', 'M0,0 L10,5 L0,10 Z')
      .attr('fill', isDark ? '#f472b6' : '#db2777');

    // Annotation Property (Purple)
    defs.append('marker')
      .attr('id', 'arrow-vowl-annotation')
      .attr('viewBox', '0 0 10 10')
      .attr('refX', 10).attr('refY', 5)
      .attr('markerWidth', 8).attr('markerHeight', 8)
      .attr('orient', 'auto')
      .append('path').attr('d', 'M0,0 L10,5 L0,10 Z')
      .attr('fill', isDark ? '#a78bfa' : '#7c3aed');

    // Generic VOWL markers for other types
    Object.entries(EDGE_TYPE_COLORS).forEach(([type, color]) => {
      // Determine VOWL color for this edge type
      let vowlColor = color;
      if (type === 'subClassOf') vowlColor = isDark ? '#9ca3af' : '#374151';
      else if (type === 'equivalentClass') vowlColor = '#10b981';
      else if (type === 'disjointWith') vowlColor = '#ef4444';
      else if (type === 'domain') vowlColor = '#666666';
      else if (type === 'range') vowlColor = '#666666';
      
      defs
        .append('marker')
        .attr('id', `arrow-vowl-${type}`)
        .attr('viewBox', '0 0 10 10')
        .attr('refX', 10)
        .attr('refY', 5)
        .attr('markerWidth', 8)
        .attr('markerHeight', 8)
        .attr('orient', 'auto')
        .append('path')
        .attr('d', 'M0,0 L10,5 L0,10 Z')
        .attr('fill', vowlColor);
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
      
      defs
        .append('marker')
        .attr('id', `arrow-${type}`)
        .attr('viewBox', '0 0 10 10')
        .attr('refX', 10)
        .attr('refY', 5)
        .attr('markerWidth', 8)
        .attr('markerHeight', 8)
        .attr('orient', 'auto')
        .append('path')
        .attr('d', 'M0,0 L10,5 L0,10 Z')
        .attr('fill', forceColor);
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
      defs.append('marker')
        .attr('id', `arrow-ontograph-${type}`)
        .attr('viewBox', '0 0 10 10')
        .attr('refX', 10).attr('refY', 5)
        .attr('markerWidth', 8).attr('markerHeight', 8)
        .attr('orient', 'auto')
        .append('path').attr('d', 'M0,0 L10,5 L0,10 Z')
        .attr('fill', color);
    });
    
    console.log('[AdvancedGraphView] ✅ Arrow markers created with specific property colors');

    // Prepare D3 data with better initial positioning
    const d3Nodes: D3Node[] = filteredNodes.map((node, index) => {
      let x: number, y: number;
      
      if (visualizationType === 'vowl') {
        // Better initial spread for VOWL mode to reduce initial overlapping
        const cols = Math.ceil(Math.sqrt(filteredNodes.length));
        const col = index % cols;
        const row = Math.floor(index / cols);
        const cellWidth = Math.max(150, (width - 300) / cols); // Increased minimum cell width
        const cellHeight = Math.max(150, (height - 300) / cols); // Increased minimum cell height
        
        // More randomization within each cell to prevent grid-like clustering
        x = 150 + col * cellWidth + (Math.random() - 0.5) * 120; // Increased from 50
        y = 150 + row * cellHeight + (Math.random() - 0.5) * 120; // Increased from 50
      } else if (visualizationType === 'ontograph' && ontographLayoutType === 'spring') {
        // Random spread for spring layout
        x = width / 2 + (Math.random() - 0.5) * width * 0.6;
        y = height / 2 + (Math.random() - 0.5) * height * 0.6;
      } else {
        // Standard random positioning
        x = width / 2 + (Math.random() - 0.5) * 100;
        y = height / 2 + (Math.random() - 0.5) * 100;
      }
      
      return { ...node, x, y };
    });

    let vowlLayout: VowlLayoutResult | null = null;
    if (visualizationType === 'vowl') {
      vowlLayout = computeVowlLayout(d3Nodes, width, height, classDistance, datatypeDistance);
      d3Nodes.forEach(node => {
        const position = vowlLayout?.positions.get(node.id);
        if (position) {
          node.x = position.x;
          node.y = position.y;
        }
      });
    }

    // Apply OntoGraph hierarchical layout if selected
    if (visualizationType === 'ontograph') {
      const nodeCount = filteredNodes.length;
      const isLarge = nodeCount > 1000;
      setIsLargeGraph(isLarge);
      
      console.log(`[OntoGraph] Applying ${ontographLayoutType} layout for ${nodeCount} nodes`);
      
      let positionMap: Map<string, { x: number; y: number }>;
      
      switch (ontographLayoutType) {
        case 'grid':
          positionMap = layouts.applyGridLayout(filteredNodes, filteredEdges, { width, height });
          break;
        case 'radial':
          positionMap = layouts.applyRadialLayout(filteredNodes, filteredEdges, { width, height });
          break;
        case 'tree':
          positionMap = layouts.applyTreeLayout(filteredNodes, filteredEdges, { width, height, orientation: 'vertical' });
          break;
        case 'horizontal':
          positionMap = layouts.applyTreeLayout(filteredNodes, filteredEdges, { width, height, orientation: 'horizontal' });
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
            horizontalSpacing: isLarge ? 150 : 200,
            verticalSpacing: isLarge ? 60 : 80,
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

    const d3Edges: D3Edge[] = filteredEdges.map(edge => ({
      ...edge,
      source: nodeMap.get(edge.from)!,
      target: nodeMap.get(edge.to)!
    })).filter(e => e.source && e.target);
    
    // Calculate curvature for edges with multiple connections between same nodes
    // Apply curves in all modes to prevent overlapping edges
    const edgeCurvature = new Map<string, number>();
    
    if (visualizationType === 'force' || visualizationType === 'ontograph') {
      const nodePairEdges = new Map<string, D3Edge[]>();
      
      d3Edges.forEach(edge => {
        const sourceId = (edge.source as D3Node).id;
        const targetId = (edge.target as D3Node).id;
        // Create a key that's the same for both directions
        const pairKey = [sourceId, targetId].sort().join('|');
        
        if (!nodePairEdges.has(pairKey)) {
          nodePairEdges.set(pairKey, []);
        }
        nodePairEdges.get(pairKey)!.push(edge);
      });
      
      // Assign curvature values for multiple edges between same nodes
      nodePairEdges.forEach((edges, pairKey) => {
        if (edges.length > 1) {
          // Multiple edges between same nodes - apply stronger curvature
          // If VOWL, respect "straight only" request by using 0 curvature
          const curveStrength = (visualizationType as string) === 'vowl' ? 0 : 40; 
          edges.forEach((edge, index) => {
            // Alternate curvature with stronger offset
            let curve = 0;
            if (index > 0 && curveStrength > 0) {
              const offset = Math.ceil(index / 2);
              curve = (index % 2 === 1) ? offset * curveStrength : -offset * curveStrength;
            }
            edgeCurvature.set(edge.id, curve);
          });
          console.log(`[Curved Edges] Node pair ${pairKey} has ${edges.length} edges with curves:`, 
            edges.map(e => ({ label: e.label, curve: edgeCurvature.get(e.id) })));
        } else {
          // Single edge - apply slight curve in force mode, straight in VOWL by default
          const defaultCurve = visualizationType === 'force' ? 15 : 0;
          edgeCurvature.set(edges[0].id, defaultCurve);
        }
      });
    } else {
      // Fallback: all edges straight
      d3Edges.forEach(edge => {
        edgeCurvature.set(edge.id, 0);
      });
    }

    console.log('[AdvancedGraphView D3] ✅ Prepared D3 data - Nodes:', d3Nodes.length, 'Edges:', d3Edges.length);

    // Create force simulation with enhanced layout for better structure
    const nodeCount = d3Nodes.length;
    
    // Calculate link distance based on node types and VOWL distance controls
    const linkDistance = (edge: D3Edge) => {
      const source = edge.source as D3Node;
      const target = edge.target as D3Node;
      
      // Balanced distances in WebVOWL mode - increased for better visibility
      const distanceMultiplier = visualizationType === 'vowl' ? 1.2 : 1.0;
      
      // Hierarchy edges (subClassOf) should be shorter for better tree structure
      if (edge.type === 'subClassOf') {
        return classDistance * 0.8 * distanceMultiplier;
      }
      
      // Property relation edges
      if (edge.type === 'propertyRelation') {
        return classDistance * 1.2 * distanceMultiplier;
      }
      
      // Reduced distance for datatypes to classes - bring them closer
      if (source.type === 'dataProperty' || target.type === 'dataProperty' ||
          source.type === 'datatype' || target.type === 'datatype') {
        return datatypeDistance * 1.0 * distanceMultiplier;
      }
      
      // Domain/Range edges
      if (edge.type === 'domain' || edge.type === 'range') {
        return classDistance * 0.9 * distanceMultiplier;
      }
      
      // Otherwise use class distance
      return classDistance * distanceMultiplier;
    };
    
    // Calculate link strength - hierarchy edges are stronger
    const linkStrength = (edge: D3Edge) => {
      if (edge.type === 'subClassOf') {
        return 1.0; // Very strong connection for hierarchy
      }
      if (edge.type === 'propertyRelation') {
        return nodeCount > 100 ? 0.4 : 0.6; // Moderate strength for properties
      }
      return nodeCount > 100 ? 0.3 : 0.5;
    };
    
    // Calculate average node size for collision detection
    const avgNodeSize = settings.nodeSize || 20;
    
    // Force simulation configuration
    // Use physics for force, vowl, and ontograph spring mode
    const usePhysics = visualizationType !== 'ontograph' || ontographLayoutType === 'spring';
    
    const simulation = d3.forceSimulation<D3Node>(d3Nodes)
      .force('link', usePhysics ? d3.forceLink<D3Node, D3Edge>(d3Edges)
        .id(d => d.id)
        .distance(linkDistance)
        .strength(linkStrength)
        .iterations(4) : null)
      .force('charge', usePhysics ? d3.forceManyBody()
        .strength(d => {
          const node = d as D3Node;
          // Much stronger repulsion in WebVOWL mode for organized layout
          if (visualizationType === 'vowl') {
            if (node.type === 'class') {
              return nodeCount > 200 ? -1400 : -1200;
            }
            if (node.type === 'datatype') {
              return nodeCount > 200 ? -1100 : -950;
            }
            return nodeCount > 200 ? -900 : -800;
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
          // Standard mode repulsion
          if (node.type === 'class') {
            return nodeCount > 100 ? -1000 : -1500;
          }
          return nodeCount > 100 ? -500 : -800;
        })
        .distanceMax(visualizationType === 'vowl' ? Math.min(width, height) * 1.2 : (nodeCount > 10000 ? 800 : 1200))
        .theta(nodeCount > 50000 ? 0.9 : 0.7) : null)
      .force('center', usePhysics ? d3.forceCenter(width / 2, height / 2)
        .strength(visualizationType === 'vowl' ? 0.02 : 0.03) : null)
      .force('collision', usePhysics ? d3.forceCollide()
        .radius(d => {
          const node = d as D3Node;
          const size = node.size || avgNodeSize;
          const isLargeGraph = nodeCount > 10000;
          
          // Much larger collision radius in WebVOWL mode for better spacing
          if (visualizationType === 'vowl') {
            // Different collision radii based on node type - increased to prevent overlap
            if (node.type === 'class') {
              return size * 4.5;
            } else if (node.type === 'datatype') {
              return size * 3.6;
            }
            return size * 3.2;
          }
          // Force mode - adaptive collision radius for scalability
          if (visualizationType === 'force') {
            // Reduce collision radius for very large graphs to fit more nodes
            const scaleFactor = isLargeGraph ? 0.7 : 1.0;
            if (node.type === 'class') {
              return size * 4.5 * scaleFactor; // Larger for class ellipses
            } else if (node.type === 'individual') {
              return size * 4.0 * scaleFactor; // Larger for individual rectangles
            }
            return size * 3.5 * scaleFactor;
          }
          // OntoGraph mode - larger collision to prevent overlap
          if (visualizationType === 'ontograph') {
            if (node.type === 'class') {
              return size * 7.0; // Much larger for OntoGraph classes
            } else if (node.type === 'datatype') {
              return size * 6.5;
            }
            return size * 6.0;
          }
          return size * 3.5; // Standard mode
        })
        .strength(1.0)
        .iterations(visualizationType === 'vowl' ? 15 : 6) : null) // Increased from 10
      .force('y', usePhysics ? d3.forceY(d => {
        const node = d as D3Node;
        // Stronger vertical positioning in VOWL mode
        if (visualizationType === 'vowl') {
          return height / 2;
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
      }).strength(visualizationType === 'vowl' ? 0.18 : 0.15) : null)
      .force('x', usePhysics ? d3.forceX(d => {
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
        if (visualizationType === 'vowl') {
          return width / 2;
        }
        return width / 2;
      }).strength(visualizationType === 'force' ? 0.14 : visualizationType === 'vowl' ? 0.05 : 0.02) : null)
      .alphaDecay(usePhysics ? (visualizationType === 'vowl' ? 0.02 : 0.03) : 1) // Increased for faster settling
      .velocityDecay(usePhysics ? (visualizationType === 'vowl' ? 0.6 : 0.5) : 0.8) // Increased for more damping
      .alpha(isLayoutPaused || !usePhysics ? 0 : 1.0)
      .alphaMin(0.001)
      .alphaTarget(0);

    if (visualizationType === 'vowl' && usePhysics && vowlLayout) {
      simulation.force('radial', d3.forceRadial<D3Node>(node => {
        if (node.type === 'datatype') {
          return vowlLayout!.radii.datatype;
        }
        if (node.type === 'individual') {
          return vowlLayout!.radii.individual;
        }
        return vowlLayout!.radii.class;
      }, width / 2, height / 2).strength(0.75));
    }

    simulationRef.current = simulation;

    // Pre-calculate stable positions before rendering (run simulation silently)
    if (usePhysics && !isLayoutPaused) {
      // Run simulation for several ticks to get stable positions
      const preTicks = visualizationType === 'vowl' ? 60 : 50;
      for (let i = 0; i < preTicks; i++) {
        simulation.tick();
      }
      // Reduce alpha so nodes are nearly stable when rendered
      simulation.alpha(0.3);
      console.log(`[AdvancedGraphView] Pre-calculated ${preTicks} ticks for stable initial positions`);
    }

    // Add bounding box force to keep nodes within viewport (especially important for VOWL)
    if (usePhysics && visualizationType === 'vowl') {
      const padding = 80;
      simulation.force('bound', () => {
        d3Nodes.forEach(node => {
          if (node.x && node.y) {
            node.x = Math.max(padding, Math.min(width - padding, node.x));
            node.y = Math.max(padding, Math.min(height - padding, node.y));
          }
        });
      });
    }

    // Add hit areas for edges (transparent wider paths for easier clicking)
    const edgeHitArea = g.append('g')
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
        onEdgeClick?.(d.id);
      })
      .on('mouseover', (event, d) => {
        setHoveredEdgeId(d.id);
      })
      .on('mouseout', () => {
        setHoveredEdgeId(null);
      });

    // Draw edges (with VOWL styling support and curved paths for multiple edges)
    const link = g.append('g')
      .attr('class', 'links')
      .selectAll('path')
      .data(d3Edges)
      .join('path')
      .attr('class', 'edge-path')
      .attr('fill', 'none')
      .attr('stroke', d => {
        const isDark = document.documentElement.classList.contains('dark');
        
        if (visualizationType === 'vowl') {
          // Determine property type for proper coloring
          const sourceNode = allNodes.find(n => n.id === d.from);
          const targetNode = allNodes.find(n => n.id === d.to);
          
          if (d.type === 'subClassOf') {
            return isDark ? '#9ca3af' : '#374151'; // Light gray in dark mode, dark gray in light mode
          }
          if (d.type === 'propertyRelation') {
            // Annotation properties - purple
            if (sourceNode?.type === 'annotation') {
              return isDark ? '#a78bfa' : '#7c3aed';
            }
            // Data properties - pink
            if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') {
              return isDark ? '#f472b6' : '#db2777';
            }
            // Object properties - cyan
            return isDark ? '#22d3ee' : '#0891b2';
          }
          
          const vowlEdge = vowlNotationService.edgeToVOWLEdge(d);
          return isDark ? '#94a3b8' : (vowlEdge.stroke || '#000000');
        }
        if (visualizationType === 'ontograph') {
          // Protégé-style colors with dark mode support
          if (d.type === 'subClassOf') return isDark ? '#64B5F6' : '#1976D2'; // Blue for hierarchy
          if (d.type === 'instanceOf') return isDark ? '#fbbf24' : '#FFA726'; // Orange
          if (d.type === 'propertyRelation') {
            const sourceNode = allNodes.find(n => n.id === d.from);
            const targetNode = allNodes.find(n => n.id === d.to);
            if (sourceNode?.type === 'annotation') return isDark ? '#a78bfa' : '#8b5cf6'; // Purple
            if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') return isDark ? '#f472b6' : '#ec4899'; // Pink
            return isDark ? '#81C784' : '#388E3C'; // Green for object properties
          }
          return isDark ? '#64B5F6' : '#1976D2';
        }
        // Force mode - with dark mode support
        if (d.type === 'subClassOf') return isDark ? '#fbbf24' : '#FFA500';
        if (d.type === 'instanceOf') return isDark ? '#cbd5e1' : '#000000';
        if (d.type === 'propertyRelation') {
          const targetNode = allNodes.find(n => n.id === d.to);
          if (targetNode?.type === 'datatype' || targetNode?.label?.startsWith('"')) return isDark ? '#94a3b8' : '#999999';
          return isDark ? '#cbd5e1' : '#000000';
        }
        return isDark ? '#cbd5e1' : '#000000';
      })
      .attr('stroke-width', d => {
        const baseWidth = visualizationType === 'vowl' ? 1 : (visualizationType === 'ontograph' ? 2 : 2);
        return selectedEdgeId === d.id ? baseWidth + 2 : baseWidth;
      })
      .attr('stroke-opacity', d => {
        if (selectedEdgeId) {
          return selectedEdgeId === d.id ? 1 : 0.3;
        }
        if (visualizationType === 'force') return 1; // Full opacity for force mode
        if (d.type === 'propertyRelation') return 1;
        return visualizationType === 'vowl' ? 1 : 0.6;
      })
      .attr('stroke-dasharray', d => {
        if (visualizationType === 'vowl') {
          const vowlEdge = vowlNotationService.edgeToVOWLEdge(d);
          return vowlEdge.strokeDasharray || null;
        }
        if (visualizationType === 'ontograph') {
          // Dashed lines for non-hierarchy edges in Protégé style
          if (d.type === 'instanceOf') return '5 3'; // Dashed for instances
          if (d.type === 'propertyRelation') return '8 4'; // Longer dash for properties
          return null; // Solid for subClassOf
        }
        // Force mode - match reference image
        if (d.type === 'subClassOf') return '5 3'; // Dashed for subClassOf
        if (d.type === 'propertyRelation') {
          const targetNode = allNodes.find(n => n.id === d.to);
          if (targetNode?.type === 'datatype' || targetNode?.label?.startsWith('"')) return '4 2'; // Dashed for data properties
        }
        return null; // Solid for instanceOf and object properties
      })
      .attr('marker-end', d => {
        // In VOWL mode, always show arrows with specific colors for properties
        if (visualizationType === 'vowl') {
          if (d.type === 'propertyRelation') {
            const sourceNode = allNodes.find(n => n.id === d.from);
            const targetNode = allNodes.find(n => n.id === d.to);
            
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
            const sourceNode = allNodes.find(n => n.id === d.from);
            const targetNode = allNodes.find(n => n.id === d.to);
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
        onEdgeClick?.(d.id);
      })
      .on('mouseover', (event, d) => {
        setHoveredEdgeId(d.id);
      })
      .on('mouseout', () => {
        setHoveredEdgeId(null);
      });

    // Draw edge label backgrounds (colored boxes for WebVOWL - varied colors based on property)
    const linkLabelBg = g.append('g')
      .attr('class', 'link-label-backgrounds')
      .selectAll('rect')
      .data(d3Edges)
      .join('rect')
      .attr('class', 'edge-label-bg')
      .attr('fill', d => {
        if (visualizationType === 'vowl') {
          // Determine property type for proper background coloring
          const sourceNode = allNodes.find(n => n.id === d.from);
          const targetNode = allNodes.find(n => n.id === d.to);
          const isFunctional = d.metadata?.functional;
          
          if (d.type === 'subClassOf') {
            return '#E5E7EB'; // Light gray for subClassOf
          }
          
          if (d.type === 'propertyRelation') {
            // Annotation properties - light purple
            if (sourceNode?.type === 'annotation') {
              return isFunctional ? '#E9D5FF' : '#F3E8FF'; // Lighter purple
            }
            // Data properties - light pink
            if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') {
              return isFunctional ? '#FCE7F3' : '#FDF2F8'; // Light pink
            }
            // Object properties - light cyan
            return isFunctional ? '#A7F3D0' : '#CFFAFE'; // Light cyan/green for functional
          }
          
          return '#BBDEFB'; // Default light blue
        }
        
        // Add background colors for other modes too
        if (d.type === 'propertyRelation') {
          const sourceNode = allNodes.find(n => n.id === d.from);
          const targetNode = allNodes.find(n => n.id === d.to);
          const isFunctional = d.metadata?.functional;
          
          // Annotation properties - light purple
          if (sourceNode?.type === 'annotation') {
            return isFunctional ? '#E9D5FF' : '#F3E8FF';
          }
          // Data properties - light pink
          if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') {
            return isFunctional ? '#FCE7F3' : '#FDF2F8';
          }
          // Object properties - light cyan
          return isFunctional ? '#A7F3D0' : '#CFFAFE';
        }
        
        return '#ffffff';
      })
      .attr('opacity', d => {
        if (visualizationType === 'vowl') {
          // Show label background when edge is selected or hovered
          return (selectedEdgeId === d.id || hoveredEdgeId === d.id) ? 1 : 0;
        }
        return (visualizationType as string) === 'vowl' ? 1 : 0.85;
      })
      .attr('stroke', d => {
        if (visualizationType === 'vowl') {
          const isFunctional = d.metadata?.functional;
          const label = (d.label || '').toLowerCase();
          
          // Matching border colors
          if (isFunctional) {
            return '#4CAF50'; // Green border for functional
          } else if (label.includes('has') || label.includes('tag')) {
            return '#2196F3'; // Blue border
          } else if (label.includes('creator') || label.includes('access')) {
            return '#2196F3'; // Blue border
          } else if (label.includes('meaning') || label.includes('previous') || label.includes('next')) {
            return '#9C27B0'; // Purple border
          } else {
            return '#2196F3'; // Default blue border
          }
        }
        return 'none';
      })
      .attr('stroke-width', visualizationType === 'vowl' ? 1 : 0)
      .attr('rx', 3)
      .attr('ry', 3)
      .style('pointer-events', 'none');

    // Draw edge labels (more prominent in WebVOWL mode)
    const linkLabel = g.append('g')
      .attr('class', 'link-labels')
      .selectAll('text')
      .data(d3Edges)
      .join('text')
      .attr('class', 'edge-label')
      .attr('font-size', visualizationType === 'vowl' ? 9 : 10)
      .attr('font-weight', visualizationType === 'vowl' ? '500' : '400')
      .attr('font-family', visualizationType === 'vowl' ? 'Arial, sans-serif' : 'inherit')
      .attr('fill', d => {
        const isDark = document.documentElement.classList.contains('dark');
        
        if (visualizationType === 'vowl') {
          // Determine property type for proper label coloring
          const sourceNode = allNodes.find(n => n.id === d.from);
          const targetNode = allNodes.find(n => n.id === d.to);
          
          if (d.type === 'subClassOf') {
            return isDark ? '#d1d5db' : '#1f2937'; // Light gray in dark mode
          }
          
          if (d.type === 'propertyRelation') {
            // Annotation properties - dark purple
            if (sourceNode?.type === 'annotation') {
              return isDark ? '#c4b5fd' : '#6b21a8';
            }
            // Data properties - dark pink
            if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') {
              return isDark ? '#f9a8d4' : '#be185d';
            }
            // Object properties - dark cyan
            const isFunctional = d.metadata?.functional;
            return isDark ? '#67e8f9' : (isFunctional ? '#2E7D32' : '#065f46');
          }
          
          return isDark ? '#93c5fd' : '#1565C0';
        }
        // Color labels by property type with dark mode support
        if (d.type === 'propertyRelation') {
          const sourceNode = allNodes.find(n => n.id === d.from);
          const targetNode = allNodes.find(n => n.id === d.to);
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
        // Show full label in WebVOWL mode with functional indicator
        if (visualizationType === 'vowl') {
          const baseLabel = d.label || d.type || '';
          const isFunctional = isFunctionalEdge(d);
          const isInverseFunctional = d.metadata?.inverseFunctional;
          
          // Format like reference image: "tag of (functional)"
          if (isFunctional && isInverseFunctional) {
            return `${baseLabel} (F, IF)`;
          } else if (isFunctional) {
            return `${baseLabel} (F)`;
          } else if (isInverseFunctional) {
            return `${baseLabel} (IF)`;
          }
          return baseLabel;
        }
        
        // Add property type prefix and functional indicator for clarity
        if (settings.showLabels && d.type === 'propertyRelation') {
          const sourceNode = allNodes.find(n => n.id === d.from);
          const targetNode = allNodes.find(n => n.id === d.to);
          const label = d.label || '';
          const isFunctional = isFunctionalEdge(d);
          const isInverseFunctional = d.metadata?.inverseFunctional;
          
          let prefix = '';
          if (sourceNode?.type === 'annotation') prefix = '📝'; // Annotation property
          else if (targetNode?.type === 'datatype' || sourceNode?.type === 'dataProperty') prefix = '📊'; // Data property
          else prefix = '🔗'; // Object property
          
          let suffix = '';
          if (isFunctional && isInverseFunctional) suffix = ' (F, IF)';
          else if (isFunctional) suffix = ' (F)';
          else if (isInverseFunctional) suffix = ' (IF)';
          
          return `${prefix} ${label}${suffix}`;
        }
        
        return settings.showLabels ? (d.label || '') : '';
      })
      .style('pointer-events', 'none')
      .style('opacity', d => {
        if (visualizationType === 'vowl') {
          // Show label only when edge is selected or hovered
          return (selectedEdgeId === d.id || hoveredEdgeId === d.id) ? 1 : 0;
        }
        return settings.showLabels ? 1 : 0;
      });

    // Filter nodes by viewport for large OntoGraph (virtualization)
    let visibleD3Nodes = d3Nodes;
    if (isLargeGraph && visualizationType === 'ontograph' && d3Nodes.length > 5000) {
      const buffer = 500; // Buffer zone around viewport
      visibleD3Nodes = d3Nodes.filter(node => {
        if (!node.x || !node.y) return true; // Include if no position yet
        return node.x >= viewportBounds.x - buffer &&
               node.x <= viewportBounds.x + viewportBounds.width + buffer &&
               node.y >= viewportBounds.y - buffer &&
               node.y <= viewportBounds.y + viewportBounds.height + buffer;
      });
      console.log(`[OntoGraph Virtualization] Rendering ${visibleD3Nodes.length} of ${d3Nodes.length} nodes`);
    }
    
    // Draw nodes
    const node = g.append('g')
      .attr('class', 'nodes')
      .selectAll('g')
      .data(visibleD3Nodes)
      .join('g')
      .attr('class', 'node')
      .style('cursor', editMode ? 'move' : 'pointer')
      .call(d3.drag<SVGGElement, D3Node>()
        .on('start', dragStarted)
        .on('drag', dragged)
        .on('end', dragEnded) as any);

    // Node shapes (WebVOWL-style: circles for classes/properties, rectangles for datatypes/individuals)
    node.each(function(d) {
      const nodeGroup = d3.select(this);
      const size = d.size || settings.nodeSize;
      const nodeType = d.type;
      
      // Check if this is owl:Thing or external/internal node
      const isThing = d.label === 'Thing' || d.id.includes('owl#Thing');
      const isExternal = isExternalNode(d);
      
      // Detect dark mode for theme-aware colors
      const isDark = document.documentElement.classList.contains('dark');
      
      // WebVOWL color scheme matching reference image - theme aware
      let fill = isDark ? '#6b92c4' : '#acd5f2'; // Default light blue (darker in dark mode)
      
      if (visualizationType === 'vowl' && nodeType === 'class') {
        if (isThing) {
          fill = isDark ? '#374151' : '#ffffff'; // Dark gray in dark mode, white in light mode
        } else if (isExternal) {
          fill = isDark ? '#60a5fa' : '#4682b4'; // Lighter blue in dark mode
        } else {
          fill = isDark ? '#6b92c4' : '#acd5f2'; // Adjusted for dark mode
        }
      } else if (visualizationType === 'vowl') {
        fill = vowlNotationService.getVOWLNodeColor(d.type, isDark);
      } else {
        fill = d.color || TYPE_COLORS[d.type];
      }
      
      const stroke = visualizationType === 'vowl'
        ? (isDark ? '#d1d5db' : '#000000')
        : '#fff';
      
      const strokeWidth = visualizationType === 'vowl'
        ? 2
        : (hasChildren(d.id, allEdges, allNodes) ? 3 : 2);
      
      // Thing nodes get dashed borders in WebVOWL
      const strokeDasharray = visualizationType === 'vowl'
        ? (isThing ? '5 3' : null)
        : null;

      // Render different shapes based on node type (WebVOWL style)
      if (nodeType === 'dataProperty' || nodeType === 'property') {
        // Pink Rectangle for data properties
        nodeGroup.append('rect')
          .attr('class', 'node-shape')
          .attr('x', -size)
          .attr('y', -size)
          .attr('width', size * 2)
          .attr('height', size * 2)
          .attr('rx', 3)
          .attr('fill', fill)
          .attr('stroke', stroke)
          .attr('stroke-width', strokeWidth)
          .attr('stroke-dasharray', strokeDasharray || null)
          .style('filter', visibleNodes.length > 100 ? 'none' : 'drop-shadow(0 2px 4px rgba(0,0,0,0.2))')
          .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
          .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
          .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
          .on('mouseout', handleNodeMouseOut);
      } else if (nodeType === 'datatype') {
        // Force mode: White rectangle for datatypes/literals
        if (visualizationType === 'force') {
          const rectWidth = size * 2.2;
          const rectHeight = size * 1.1;
          nodeGroup.append('rect')
            .attr('class', 'node-shape')
            .attr('x', -rectWidth / 2)
            .attr('y', -rectHeight / 2)
            .attr('width', rectWidth)
            .attr('height', rectHeight)
            .attr('rx', 3)
            .attr('ry', 3)
            .attr('fill', '#FFFFFF')
            .attr('stroke', '#999999')
            .attr('stroke-width', 1)
            .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
            .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
            .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
            .on('mouseout', handleNodeMouseOut);
        } else {
          // VOWL/OntoGraph: Yellow Rounded Rectangle for datatypes - larger for both modes
          const rectWidth = visualizationType === 'vowl' ? size * 4.2 : (visualizationType === 'ontograph' ? size * 3.5 : size * 3);
          const rectHeight = visualizationType === 'vowl' ? size * 2.0 : (visualizationType === 'ontograph' ? size * 1.8 : size * 1.6);
          nodeGroup.append('rect')
            .attr('class', 'node-shape')
            .attr('x', -rectWidth / 2)
            .attr('y', -rectHeight / 2)
            .attr('width', rectWidth)
            .attr('height', rectHeight)
            .attr('rx', size * 0.4)
            .attr('fill', visualizationType === 'vowl' ? (isDark ? '#d97706' : '#FFD9B3') : fill)
            .attr('stroke', visualizationType === 'vowl' ? (isDark ? '#d1d5db' : '#000000') : stroke)
            .attr('stroke-width', visualizationType === 'vowl' ? 2 : strokeWidth)
            .attr('stroke-dasharray', visualizationType === 'vowl' ? '5 3' : (strokeDasharray || null))
            .style('filter', visibleNodes.length > 100 ? 'none' : 'drop-shadow(0 2px 4px rgba(0,0,0,0.2))')
            .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
            .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
            .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
            .on('mouseout', handleNodeMouseOut);
        }
      } else if (nodeType === 'individual') {
        // Force mode: Blue rounded rectangle for individuals
        if (visualizationType === 'force') {
          const rectWidth = size * 3.2;
          const rectHeight = size * 1.6;
          nodeGroup.append('rect')
            .attr('class', 'node-shape')
            .attr('x', -rectWidth / 2)
            .attr('y', -rectHeight / 2)
            .attr('width', rectWidth)
            .attr('height', rectHeight)
            .attr('rx', 5)
            .attr('ry', 5)
            .attr('fill', '#B0C4DE')
            .attr('stroke', '#000000')
            .attr('stroke-width', 1.5)
            .style('filter', visibleNodes.length > 100 ? 'none' : 'drop-shadow(0 2px 4px rgba(0,0,0,0.15))')
            .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
            .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
            .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
            .on('mouseout', handleNodeMouseOut);
        } else {
          // VOWL/OntoGraph: Rectangle for individuals (purple/pink) - dynamic width based on label length
          const label = d.label || '';
          const baseWidth = visualizationType === 'vowl' ? size * 2.8 : (visualizationType === 'ontograph' ? size * 2.8 : size * 2.4);
          // Calculate width based on label length (approximately 7 pixels per character)
          const labelWidth = label.length * 7;
          const rectWidth = Math.max(baseWidth, labelWidth + 16); // Add padding
          const rectHeight = visualizationType === 'vowl' ? size * 1.8 : (visualizationType === 'ontograph' ? size * 1.8 : size * 1.6);
          nodeGroup.append('rect')
            .attr('class', 'node-shape')
            .attr('x', -rectWidth / 2)
            .attr('y', -rectHeight / 2)
            .attr('width', rectWidth)
            .attr('height', rectHeight)
            .attr('rx', 4)
            .attr('ry', 4)
            .attr('fill', fill)
            .attr('stroke', stroke)
            .attr('stroke-width', strokeWidth)
            .attr('stroke-dasharray', strokeDasharray || null)
            .style('filter', visibleNodes.length > 100 ? 'none' : 'drop-shadow(0 2px 4px rgba(0,0,0,0.2))')
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
          .attr('fill', visualizationType === 'vowl' ? (isDark ? '#9333ea' : '#e8d5f2') : fill) // Darker purple in dark mode
          .attr('stroke', stroke)
          .attr('stroke-width', strokeWidth)
          .attr('stroke-dasharray', strokeDasharray || null)
          .style('filter', visibleNodes.length > 100 ? 'none' : 'drop-shadow(0 2px 4px rgba(0,0,0,0.2))')
          .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
          .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
          .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
          .on('mouseout', handleNodeMouseOut);
      } else if (visualizationType === 'ontograph') {
        // Protégé-style: Rounded rectangle for all nodes
        const simplifiedLOD = isLargeGraph && viewportBounds.scale < 0.5;
        const rectWidth = simplifiedLOD ? size * 4 : size * 7.5; // Slightly wider for better label fit
        const rectHeight = simplifiedLOD ? size * 1.5 : size * 2.0; // Slightly taller
        
        nodeGroup.append('rect')
          .attr('class', 'node-shape')
          .attr('x', -rectWidth / 2)
          .attr('y', -rectHeight / 2)
          .attr('width', rectWidth)
          .attr('height', rectHeight)
          .attr('rx', 6) // More rounded
          .attr('fill', d.type === 'class' ? '#FFF9C4' : (d.type === 'individual' ? '#E1F5FE' : '#F5F5F5')) // Light yellow for classes, light blue for individuals
          .attr('stroke', d.type === 'class' ? '#FBC02D' : (d.type === 'individual' ? '#0288D1' : '#9E9E9E')) // Darker borders
          .attr('stroke-width', simplifiedLOD ? 1 : 2)
          .style('filter', simplifiedLOD ? 'none' : 'drop-shadow(0 2px 4px rgba(0,0,0,0.15))')
          .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
          .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
          .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
          .on('mouseout', handleNodeMouseOut);
        
        // Add icon circle for OntoGraph nodes (skip in simplified LOD)
        if (!simplifiedLOD) {
          nodeGroup.append('circle')
            .attr('cx', -rectWidth / 2 + 15)
            .attr('cy', 0)
            .attr('r', 7)
            .attr('fill', d.type === 'class' ? '#FFA000' : (d.type === 'individual' ? '#1976D2' : '#757575')) // Stronger icon colors
            .attr('stroke', '#fff')
            .attr('stroke-width', 1);
            
          // Add letter icon
          nodeGroup.append('text')
            .attr('x', -rectWidth / 2 + 15)
            .attr('y', 4)
            .attr('text-anchor', 'middle')
            .attr('font-size', '10px')
            .attr('font-weight', 'bold')
            .attr('fill', '#fff')
            .text(d.type === 'class' ? 'C' : (d.type === 'individual' ? 'I' : 'P'))
            .style('pointer-events', 'none');

          // Add expander icon [+] on the right (skip in simplified LOD)
          const isExpanded = expandedNodeIds.has(d.id);
          const expanderGroup = nodeGroup.append('g')
            .attr('class', 'expander-icon')
            .attr('cursor', 'pointer')
            .on('click', (event: any) => {
              event.stopPropagation();
              handleToggleExpansion(d.id);
            });

          expanderGroup.append('rect')
            .attr('x', rectWidth / 2 - 18)
            .attr('y', -8)
            .attr('width', 16)
            .attr('height', 16)
            .attr('rx', 3)
            .attr('fill', '#fff')
            .attr('stroke', '#9E9E9E')
            .attr('stroke-width', 1);

          expanderGroup.append('text')
            .attr('x', rectWidth / 2 - 10)
            .attr('y', 4)
            .attr('text-anchor', 'middle')
            .attr('font-size', '14px')
            .attr('font-weight', 'bold')
            .attr('fill', '#616161')
            .text(isExpanded ? '−' : '+')
            .style('pointer-events', 'none');
        }
      } else {
        // Force mode classes OR default circle rendering
        if (visualizationType === 'force' && nodeType === 'class') {
          // Classes as light orange/peach ovals (ellipse) - larger size for better label fit
          const ellipseWidth = size * 3.5;  // Wider for text
          const ellipseHeight = size * 2.0; // Taller oval
          nodeGroup.append('ellipse')
            .attr('class', 'node-shape')
            .attr('cx', 0)
            .attr('cy', 0)
            .attr('rx', ellipseWidth)
            .attr('ry', ellipseHeight)
            .attr('fill', '#FFE4B5')  // Light peach/moccasin color
            .attr('stroke', '#000000')
            .attr('stroke-width', 2)
            .style('filter', visibleNodes.length > 100 ? 'none' : 'drop-shadow(0 2px 4px rgba(0,0,0,0.2))')
            .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
            .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
            .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
            .on('mouseout', handleNodeMouseOut);
        } else {
          // Circle for classes, object properties, and other types - larger for OntoGraph
          const circleRadius = visualizationType === 'vowl' 
            ? size * 1.8 
            // @ts-ignore - Type narrowing limitation: visualizationType can be 'ontograph' in other code paths
            : (visualizationType === 'ontograph' ? size * 1.6 : size * 1.2);
          nodeGroup.append('circle')
            .attr('class', 'node-shape')
            .attr('r', circleRadius)
            .attr('fill', fill)
            .attr('stroke', stroke)
            .attr('stroke-width', strokeWidth)
            .attr('stroke-dasharray', strokeDasharray || null)
            .style('filter', visibleNodes.length > 100 ? 'none' : 'drop-shadow(0 2px 4px rgba(0,0,0,0.2))')
            .on('click', (event: any, d: any) => handleNodeClick(event, d as D3Node))
            .on('contextmenu', (event: any, d: any) => handleNodeRightClick(event, d as D3Node))
            .on('mouseover', (event: any, d: any) => handleNodeMouseOver(event, d as D3Node))
            .on('mouseout', handleNodeMouseOut);
        }
      }
    });

    // Node labels - INSIDE for WebVOWL and OntoGraph, outside for force mode
    // Hide labels when zoomed out on large graphs (LOD)
    const showLabels = !isLargeGraph || viewportBounds.scale >= 0.5;
    
    node.append('text')
      .attr('dx', d => {
        if (visualizationType === 'vowl') return 0;
        if (visualizationType === 'ontograph') {
          const size = d.size || settings.nodeSize;
          const simplifiedLOD = isLargeGraph && viewportBounds.scale < 0.5;
          const rectWidth = simplifiedLOD ? size * 4 : size * 7.5;
          return -rectWidth / 2 + 30; // Offset from icon
        }
        if (visualizationType === 'force') return 0; // Centered labels for force mode
        return (d.size || settings.nodeSize) + 8;
      })
      .attr('dy', d => {
        if (visualizationType === 'vowl') return 5;
        if (visualizationType === 'ontograph') return 5;
        if (visualizationType === 'force') return 4; // Vertically centered for force mode
        return 4;
      })
      .attr('text-anchor', d => {
        if (visualizationType === 'vowl') return 'middle';
        if (visualizationType === 'ontograph') return 'start'; // Left-aligned for OntoGraph
        if (visualizationType === 'force') return 'middle'; // Center text for force mode
        return 'start';
      })
      .attr('font-size', d => {
        if (visualizationType === 'vowl') return 11;
        if (visualizationType === 'ontograph') return 13; // Increased for better readability
        if (visualizationType === 'force') return 11; // Smaller font for better fit in ovals
        return 13;
      })
      .attr('font-weight', d => {
        if (visualizationType === 'vowl' || visualizationType === 'ontograph') return '600';
        if (visualizationType === 'force') return '600'; // Bold for better contrast
        return '500';
      })
      .attr('font-family', d => {
        if (visualizationType === 'vowl' || visualizationType === 'ontograph' || visualizationType === 'force') return 'Arial, sans-serif';
        return 'inherit';
      })
      .attr('fill', d => {
        if (visualizationType === 'vowl') {
          // White text for dark blue external classes, black for others
          const isExternal = isExternalNode(d);
          return isExternal ? '#ffffff' : '#000000';
        }
        if (visualizationType === 'ontograph') {
          return '#1A237E'; // Dark blue text for OntoGraph
        }
        if (visualizationType === 'force') {
          // Black text for good contrast on orange/blue backgrounds
          return '#000000';
        }
        return '#333';
      })
      .text(d => {
        if (visualizationType === 'vowl') {
          // Truncate labels in WebVOWL mode to fit within rectangles
          const label = d.label || '';
          let maxChars = 18; // Increased default for classes
          
          // Adjust max length based on node type and size
          if (d.type === 'datatype') {
            maxChars = 16; // Increased for datatypes
          } else if (d.type === 'individual') {
            maxChars = 12; // Increased for individuals
          } else if (d.type === 'class') {
            maxChars = 18; // Classes can be longer
          }
          
          return label.length > maxChars ? label.substring(0, maxChars - 2) + '..' : label;
        }
        if (visualizationType === 'ontograph' && !showLabels) {
          return ''; // Hide labels when zoomed out on large graphs
        }
        if (visualizationType === 'force') {
          // Truncate long labels to fit within ovals (max 20 characters)
          const label = d.label || '';
          return label.length > 20 ? label.substring(0, 17) + '...' : label;
        }
        return settings.showLabels ? d.label : '';
      })
      .style('pointer-events', 'none')
      .style('user-select', 'none');

    // Node type badge - positioned below node to avoid covering labels
    node.append('text')
      .attr('dx', 0)
      .attr('dy', d => {
        const size = d.size || settings.nodeSize;
        if (visualizationType === 'vowl') {
          // Position below the node shape
          if (d.type === 'individual' || d.type === 'datatype') {
            return size * 2 + 12; // Below rectangle
          }
          return size * 2 + 10; // Below circle
        }
        return size + 15; // Below for other modes
      })
      .attr('text-anchor', 'middle')
      .attr('font-size', 9)
      .attr('fill', '#666')
      .attr('font-weight', '600')
      .text(d => d.type.substring(0, 1).toUpperCase())
      .style('pointer-events', 'none')
      .style('opacity', 0.7);

    // Simulation tick with aggressive throttling for performance
    let rafId: number | null = null;
    let ticking = false;
    let tickCount = 0;
    const updateInterval = nodeCount > 100 ? 3 : 2; // Skip more frames for large graphs
    
    // Force initial render to show nodes immediately
    if (visualizationType === 'ontograph') {
      simulation.alpha(1).restart();
    }
    
    simulation.on('tick', () => {
      tickCount++;
      // Skip frames for better performance
      if (tickCount % updateInterval !== 0) return;
      
      if (!ticking) {
        ticking = true;
        rafId = requestAnimationFrame(() => {
          // Update edge paths with curvature for multiple edges between same nodes
          const updatePath = (d: D3Edge) => {
            const source = d.source as D3Node;
            const target = d.target as D3Node;
            
            if (!source.x || !source.y || !target.x || !target.y) {
              return '';
            }
            
            const curve = edgeCurvature.get(d.id) || 0;
            
            // Calculate edge endpoints
            const dx = target.x - source.x;
            const dy = target.y - source.y;
            const dist = Math.sqrt(dx * dx + dy * dy);
            
            if (dist === 0) {
              return `M${source.x},${source.y}L${target.x},${target.y}`;
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
                // Circle boundary for VOWL classes (1.8x radius)
                r = r * 1.8 + 4;
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
            
            const targetX = target.x - (dx / dist) * r;
            const targetY = target.y - (dy / dist) * r;
            
            if (curve === 0) {
              // Straight line
              return `M${source.x},${source.y}L${targetX},${targetY}`;
            } else {
              // Curved path - quadratic bezier curve
              // Calculate control point perpendicular to the line
              const midX = (source.x + targetX) / 2;
              const midY = (source.y + targetY) / 2;
              
              // Perpendicular offset
              const perpX = -dy / dist;
              const perpY = dx / dist;
              
              const controlX = midX + perpX * curve;
              const controlY = midY + perpY * curve;
              
              return `M${source.x},${source.y}Q${controlX},${controlY},${targetX},${targetY}`;
            }
          };

          link.attr('d', updatePath);
          edgeHitArea.attr('d', updatePath);

          linkLabel.each(function(d, i) {
            const sourceX = (d.source as D3Node).x!;
            const sourceY = (d.source as D3Node).y!;
            const targetX = (d.target as D3Node).x!;
            const targetY = (d.target as D3Node).y!;
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
              
              // Perpendicular offset for curve control point
              const perpX = -dy / dist;
              const perpY = dx / dist;
              
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
          });
          linkLabelBg.each(function(d, i) {
            const label = linkLabel.nodes()[i];
            if (label) {
              const bbox = (label as SVGTextElement).getBBox();
              const padding = visualizationType === 'vowl' ? 3 : 3;
              d3.select(this)
                .attr('x', bbox.x - padding)
                .attr('y', bbox.y - padding)
                .attr('width', bbox.width + padding * 2)
                .attr('height', bbox.height + padding * 2);
            }
          });

          node.attr('transform', d => `translate(${d.x},${d.y})`);
          
          ticking = false;
        });
      }
    });

    // Drag functions
    function dragStarted(event: any, d: D3Node) {
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
      if (!editMode) {
        d.fx = null;
        d.fy = null;
      }
    }

    // Node interaction handlers
    function handleNodeClick(event: any, d: D3Node) {
      event.stopPropagation();

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
        // Single click - select node and show hierarchical navigator for ALL nodes
        setSelectedNodes(new Set([d.id]));
        setSelectedNodeInfo(d as OntologyNode);
        setHierarchyRootNode(d as OntologyNode);
        setIsDialogMinimized(false); // Ensure dialog starts expanded
        
        // Position dialog in viewport - use mouse event position for better placement
        const svgRect = svgRef.current?.getBoundingClientRect();
        if (svgRect && event) {
          // Use click position relative to viewport
          const clickX = event.pageX || event.clientX;
          const clickY = event.pageY || event.clientY;
          
          // Position dialog to the right of click, but keep within viewport
          const dialogWidth = 380;
          const dialogHeight = 500;
          const padding = 20;
          
          let posX = clickX + padding;
          let posY = clickY - 100; // Slightly above click point
          
          // Position on left side of graph area
          const viewportHeight = window.innerHeight;
          const leftMargin = 20; // Distance from left edge
          
          // Set X position to left edge
          posX = leftMargin;
          
          // Adjust Y to keep within viewport
          if (posY < padding) {
            posY = padding;
          }
          if (posY + dialogHeight > viewportHeight - padding) {
            posY = viewportHeight - dialogHeight - padding;
          }
          
          setHierarchyDialogPosition({ x: posX, y: posY });
        } else {
          // Fallback: left side, centered vertically
          setHierarchyDialogPosition({
            x: 20,
            y: 100
          });
        }
        
        setShowHierarchyDialog(true);
        onNodeClick?.(d.id);
      }
    }

    function handleNodeRightClick(event: any, d: D3Node) {
      event.preventDefault();
      event.stopPropagation();

      // Show context menu
      setContextMenu({
        visible: true,
        x: event.pageX,
        y: event.pageY,
        nodeId: d.id
      });

      // Also select the node
      setSelectedNodes(new Set([d.id]));
      setSelectedNodeInfo(d as OntologyNode);
    }

    function handleNodeMouseOver(event: any, d: D3Node) {
      setHoveredNode(d.id);

      if (settings.tooltips) {
        // Remove any existing tooltips first
        d3.selectAll('.graph-tooltip').remove();

        d3.select('body').append('div')
          .attr('class', 'graph-tooltip')
          .style('position', 'absolute')
          .style('background', 'rgba(0,0,0,0.8)')
          .style('color', 'white')
          .style('padding', '8px 12px')
          .style('border-radius', '6px')
          .style('font-size', '12px')
          .style('pointer-events', 'none')
          .style('z-index', '1000')
          .style('left', `${event.pageX + 10}px`)
          .style('top', `${event.pageY + 10}px`)
          .html(`
            <strong>${d.label}</strong><br/>
            <em>${d.type}</em><br/>
            ${d.description ? `<br/>${d.description.substring(0, 100)}...` : ''}
          `);
      }
    }

    function handleNodeMouseOut() {
      setHoveredNode(null);
      d3.selectAll('.graph-tooltip').remove();
    }

    // Zoom behavior
    const zoom = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.1, 10])
      .on('zoom', (event) => {
        g.attr('transform', event.transform);
        setZoomLevel(event.transform.k);
        
        // Update viewport bounds for large graph optimization
        if (isLargeGraph) {
          const transform = event.transform;
          setViewportBounds({
            x: -transform.x / transform.k,
            y: -transform.y / transform.k,
            width: width / transform.k,
            height: height / transform.k,
            scale: transform.k
          });
        }
      });

    zoomRef.current = zoom;
    svg.call(zoom as any);

    // Apply initial transform for small graphs (zoom in closer)
    if (nodeCount < 30) {
      const initialTransform = d3.zoomIdentity
        .translate(-5.47574, -56.0665)
        .scale(1.16629);
      svg.call(zoom.transform as any, initialTransform);
    }

    // Log performance metrics
    const endTime = performance.now();
    const renderTimeMs = endTime - startTime;
    renderTime.current = renderTimeMs;
    console.log(`[AdvancedGraphView D3] ⚡ Render completed in ${renderTimeMs.toFixed(2)}ms`);
    console.log(`[AdvancedGraphView D3] 📊 Performance: ${(filteredNodes.length / renderTimeMs * 1000).toFixed(0)} nodes/sec`);
    // Cleanup
    return () => {
      simulation.stop();
    };
  }, [filteredNodes, filteredEdges, settings, editMode, onNodeClick, onEdgeClick, allEdges, allNodes, expandedNodeIds, classDistance, datatypeDistance, isLayoutPaused, visualizationType]);

  // Visual update effect to prevent graph movement on selection/hover
  useEffect(() => {
    if (!gRef.current) return;
    const g = d3.select(gRef.current);
    
    // Update edges
    g.selectAll('.edge-path')
      .attr('stroke-width', (d: any) => {
        const baseWidth = visualizationType === 'vowl' ? 1 : 2;
        return selectedEdgeId === d.id ? baseWidth + 2 : baseWidth;
      })
      .attr('stroke-opacity', (d: any) => {
        if (selectedEdgeId) return selectedEdgeId === d.id ? 1 : 0.3;
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
            const sourceNode = allNodes.find(n => n.id === d.from);
            const targetNode = allNodes.find(n => n.id === d.to);
            
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
      
    // Update labels
    g.selectAll('.edge-label')
      .style('opacity', (d: any) => {
        if (visualizationType === 'vowl') {
          return (selectedEdgeId === d.id || hoveredEdgeId === d.id) ? 1 : 0;
        }
        return settings.showLabels ? 1 : 0;
      });
      
    g.selectAll('.edge-label-bg')
      .attr('opacity', (d: any) => {
        if (visualizationType === 'vowl') {
          return (selectedEdgeId === d.id || hoveredEdgeId === d.id) ? 1 : 0;
        }
        return 0.85;
      });
      
    // Update nodes
    g.selectAll('.node-shape')
      .attr('stroke', (d: any) => selectedNodes.has(d.id) ? '#667eea' : (visualizationType === 'vowl' ? '#1f2937' : '#fff'))
      .attr('stroke-width', (d: any) => selectedNodes.has(d.id) ? 4 : 2)
      .style('opacity', (n: any) => {
        if (hoveredNode) {
          const isConnected = allEdges.some(e =>
            (e.from === hoveredNode && e.to === n.id) ||
            (e.to === hoveredNode && e.from === n.id)
          );
          return n.id === hoveredNode || isConnected ? 1 : 0.3;
        }
        return 1;
      });
      
  }, [selectedNodes, selectedEdgeId, hoveredEdgeId, hoveredNode, visualizationType, settings.showLabels, allEdges]);

  /**
   * ========================================================================
   * HIERARCHICAL NAVIGATION HANDLERS
   * ========================================================================
   */
  const handleToggleExpansion = useCallback((nodeId: string) => {
    const nodeBefore = allNodes.find(n => n.id === nodeId);
    console.log(`[UI] User clicked to toggle expansion for: ${nodeBefore?.label || nodeId}`);
    console.log(`[UI] Current state - Visible: ${visibleNodeIds.size}, Expanded: ${expandedNodeIds.size}`);

    updateHierarchyState(prev => {
      const { newExpandedIds, newVisibleIds, action } = toggleExpansion(
        nodeId,
        prev.expanded,
        prev.visible,
        allEdges,
        allNodes
      );

      console.log(`[UI] Action: ${action}`);
      console.log(`[UI] New state - Visible: ${newVisibleIds.size}, Expanded: ${newExpandedIds.size}`);
      console.log(`[UI] Newly visible nodes: ${Array.from(newVisibleIds)
        .filter(id => !prev.visible.has(id))
        .map(id => allNodes.find(n => n.id === id)?.label || id)
        .join(', ')}`);
      console.log(`[User Action] ${action} node:`, allNodes.find(n => n.id === nodeId)?.label);

      return {
        visible: newVisibleIds,
        expanded: newExpandedIds
      };
    });
  }, [allNodes, allEdges, expandedNodeIds, visibleNodeIds, updateHierarchyState]);

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
      // Clear search - use collapseAll for consistent behavior (shows roots + first level)
      const { newExpandedIds, newVisibleIds } = collapseAllNodes(allNodes, allEdges);
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
    const response = await fetch(`${(window as any).API_BASE_URL}/api/ontology/mutations/${projectId}?draft=${draftMode}`, {
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

      const apiBaseUrl = (window as any).API_BASE_URL;
      const authToken = localStorage.getItem('authToken');
      const draftMode = context?.draftMode ?? false;
      
      const response = await fetch(`${apiBaseUrl}/api/ontology/mutations/${projectId}?draft=${draftMode}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${authToken}`
        },
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
  const handleZoomIn = () => {
    if (svgRef.current && zoomRef.current) {
      const svg = d3.select(svgRef.current);
      svg.transition().duration(300).call(
        zoomRef.current.scaleBy as any, 1.3
      );
    }
  };

  const handleZoomOut = () => {
    if (svgRef.current && zoomRef.current) {
      const svg = d3.select(svgRef.current);
      svg.transition().duration(300).call(
        zoomRef.current.scaleBy as any, 0.7
      );
    }
  };

  const handleFit = () => {
    if (svgRef.current && gRef.current && zoomRef.current) {
      const svg = d3.select(svgRef.current);
      const bounds = (gRef.current as any).getBBox();
      const width = svgRef.current.clientWidth;
      const height = svgRef.current.clientHeight;

      const scale = 0.9 / Math.max(bounds.width / width, bounds.height / height);
      const translate = [
        width / 2 - scale * (bounds.x + bounds.width / 2),
        height / 2 - scale * (bounds.y + bounds.height / 2)
      ];

      svg.transition().duration(500).call(
        zoomRef.current.transform as any,
        d3.zoomIdentity.translate(translate[0], translate[1]).scale(scale)
      );
    }
  };

  const handleExport = (format: ExportFormat) => {
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
        const response = await fetch(`${(window as any).API_BASE_URL}/api/ontology/metadata/${projectId}`, {
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
          borderLeft: level > 0 ? '2px solid #e5e7eb' : 'none',
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
                color: '#1f2937',
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

  /**
   * ========================================================================
   * RENDER
   * ========================================================================
   */
  return (
    <div className="advanced-graph-view-d3" style={styles.container}>
      {/* Plugin Update Service */}
      <PluginUpdateService
        currentVersion="3.1.0"
        pluginId="graph-view-plugin"
        checkInterval={60 * 60 * 1000}
      />

      {/* Main Row with Two Columns */}
      <div style={styles.mainRow}>
        {/* First Column: Toolbar + Graph Area */}
        <div style={styles.firstColumn}>
          {/* Toolbar */}
          <div style={styles.toolbar}>
        {/* Primary actions */}
        <button onClick={() => fetchGraphData()} disabled={loading} style={styles.btnPrimary} title="Refresh graph">
          <RefreshCw size={16} className={loading ? 'spinning' : ''} />
          Refresh
        </button>

        {/* Visualization Type Selector */}
        <select
          value={visualizationType}
          onChange={(e) => setVisualizationType(e.target.value as VisualizationType)}
          style={{
            padding: '6px 12px',
            border: '1px solid var(--border)',
            borderRadius: '6px',
            fontSize: '13px',
            backgroundColor: 'var(--surface-1)',
            color: 'var(--text-primary)',
            cursor: 'pointer',
            minWidth: '180px',
            fontWeight: '500'
          }}
          title="Select visualization type"
        >
          <option value="force">Force-Directed Graph</option>
          <option value="vowl">WebVOWL Notation</option>
          <option value="ontograph">OntoGraph (Protégé-style)</option>
        </select>

        {/* OntoGraph Specific Toolbar (Protégé Style) */}
        {visualizationType === 'ontograph' && (
          <>
            <div style={styles.divider} />
            <div style={{ display: 'flex', gap: '2px', alignItems: 'center', backgroundColor: 'var(--surface-2)', padding: '2px', borderRadius: '4px', border: '1px solid var(--border)' }}>
              <button 
                onClick={handleFit} 
                style={styles.toolbarIconBtn} 
                title="Home - Reset View"
              >
                <Home size={14} />
              </button>
              <div style={styles.miniDivider} />
              <button 
                onClick={() => setOntographLayoutType('grid')} 
                style={ontographLayoutType === 'grid' ? styles.toolbarIconBtnActive : styles.toolbarIconBtn} 
                title="Grid Layout"
              >
                <LayoutGrid size={14} />
              </button>
              <button 
                onClick={() => setOntographLayoutType('radial')} 
                style={ontographLayoutType === 'radial' ? styles.toolbarIconBtnActive : styles.toolbarIconBtn} 
                title="Radial Layout"
              >
                <Orbit size={14} />
              </button>
              <button 
                onClick={() => setOntographLayoutType('spring')} 
                style={ontographLayoutType === 'spring' ? styles.toolbarIconBtnActive : styles.toolbarIconBtn} 
                title="Spring (Force) Layout"
              >
                <Zap size={14} />
              </button>
              <button 
                onClick={() => setOntographLayoutType('tree')} 
                style={ontographLayoutType === 'tree' ? styles.toolbarIconBtnActive : styles.toolbarIconBtn} 
                title="Tree Layout (Vertical)"
              >
                <div style={{ transform: 'rotate(90deg)', display: 'flex' }}><GitBranch size={14} /></div>
              </button>
              <button 
                onClick={() => setOntographLayoutType('horizontal')} 
                style={ontographLayoutType === 'horizontal' ? styles.toolbarIconBtnActive : styles.toolbarIconBtn} 
                title="Tree Layout (Horizontal)"
              >
                <GitBranch size={14} />
              </button>
              <div style={styles.miniDivider} />
              <button onClick={handleZoomIn} style={styles.toolbarIconBtn} title="Zoom In">
                <ZoomIn size={14} />
              </button>
              <button onClick={() => {
                if (svgRef.current) {
                  const svg = d3.select(svgRef.current);
                  svg.transition().duration(750).call(zoomRef.current!.transform, d3.zoomIdentity);
                }
              }} style={styles.toolbarIconBtn} title="Reset Zoom">
                <Maximize size={14} />
              </button>
              <button onClick={handleZoomOut} style={styles.toolbarIconBtn} title="Zoom Out">
                <ZoomOut size={14} />
              </button>
              <div style={styles.miniDivider} />
              <button 
                onClick={() => setShowSearch(true)} 
                style={styles.toolbarIconBtn} 
                title="Search in Graph"
              >
                <Search size={14} />
              </button>
              <div style={styles.miniDivider} />
              <button 
                onClick={() => {
                  // Expand all nodes in the hierarchy
                  const { newExpandedIds, newVisibleIds } = expandAllNodes(allNodes);
                  updateHierarchyState(() => ({
                    visible: newVisibleIds,
                    expanded: newExpandedIds
                  }));
                }} 
                style={styles.toolbarIconBtn} 
                title="Show All Nodes"
              >
                <Box size={14} />
              </button>
              <button 
                onClick={() => {
                  // Collapse all nodes to roots
                  const { newExpandedIds, newVisibleIds } = collapseAllNodes(allNodes, allEdges);
                  updateHierarchyState(() => ({
                    visible: newVisibleIds,
                    expanded: newExpandedIds
                  }));
                }} 
                style={styles.toolbarIconBtn} 
                title="Collapse All"
              >
                <MinusSquare size={14} />
              </button>
              <div style={styles.miniDivider} />
              <button 
                onClick={() => {
                  // Export to image logic
                  handleExport('svg')
                }} 
                style={styles.toolbarIconBtn} 
                title="Export as SVG"
              >
                <Camera size={14} />
              </button>
            </div>
          </>
        )}

        <div style={styles.divider} />

        {/* View controls */}
        <button onClick={handleZoomIn} style={styles.btn} title="Zoom In">
          <ZoomIn size={16} />
        </button>
        <button onClick={handleZoomOut} style={styles.btn} title="Zoom Out">
          <ZoomOut size={16} />
        </button>
        <button onClick={handleFit} style={styles.btn} title="Fit to Screen">
          <Maximize2 size={16} />
        </button>

        <div style={styles.divider} />

        {/* Hierarchical navigation */}
        <button
          onClick={() => {
            // Use the expandAllNodes function for consistent behavior
            console.log('[Expand All] Using expandAllNodes function');
            const { newExpandedIds, newVisibleIds } = expandAllNodes(allNodes);
            
            updateHierarchyState(() => ({
              visible: newVisibleIds,
              expanded: newExpandedIds
            }));
            
            console.log('[Expand All] ✅ Expanded all', allNodes.length, 'nodes');
          }}
          style={styles.btn}
          title="Expand All - Show full hierarchy of all entity types"
          disabled={loading || allNodes.length === 0}
        >
          Expand All
        </button>
        <button
          onClick={() => {
            // Use the collapseAllNodes function for consistent behavior
            console.log('[Collapse All] Using collapseAllNodes function');
            const { newExpandedIds, newVisibleIds } = collapseAllNodes(allNodes, allEdges);
            
            updateHierarchyState(() => ({
              visible: newVisibleIds,
              expanded: newExpandedIds
            }));
            
            console.log('[Collapse All] ✅ Done');
          }}
          style={styles.btn}
          title="Collapse All - Show root classes with their immediate children"
          disabled={loading || allNodes.length === 0}
        >
          Collapse All
        </button>

        <div style={styles.divider} />

        {/* Edit mode */}
        {!readonly && (
          <>
            <button
              onClick={() => setEditMode(!editMode)}
              style={editMode ? styles.btnActive : styles.btn}
              title="Edit Mode"
            >
              <Edit3 size={16} />
              {editMode ? 'Editing' : 'Edit'}
            </button>
            <div style={styles.divider} />
          </>
        )}

        {/* Feature toggles */}
        <button onClick={() => setShowSearch(!showSearch)} style={showSearch ? styles.btnActive : styles.btn} title="Search">
          <Search size={16} />
        </button>
        <button onClick={() => setShowFilters(!showFilters)} style={showFilters ? styles.btnActive : styles.btn} title="Filters">
          <Filter size={16} />
        </button>
        <button onClick={() => {
          setShowSettings(!showSettings);
          setShowPropertyPanel(true); // Always show sidebar when settings clicked
        }} style={showSettings ? styles.btnActive : styles.btn} title="Settings">
          <Settings size={16} />
        </button>
        <button onClick={() => setShowPropertyPanel(!showPropertyPanel)} style={showPropertyPanel ? styles.btnActive : styles.btn} title="Graph Explorer Sidebar">
          <FileText size={16} />
          <span style={{ marginLeft: 6 }}>Explorer</span>
        </button>
        <button onClick={() => setShowGrid(!showGrid)} style={showGrid ? styles.btnActive : styles.btn} title="Grid">
          <Grid size={16} />
        </button>
        <button onClick={togglePhysics} style={settings.physics ? styles.btnActive : styles.btn} title="Physics">
          <Zap size={16} />
        </button>
        <button onClick={() => setShowLegend(!showLegend)} style={showLegend ? styles.btnActive : styles.btn} title="Toggle Legend">
          <FileText size={16} />
        </button>

        <div style={styles.divider} />

        <button
          onClick={requestHostCollaborationPanel}
          style={styles.btn}
          title="Open collaboration panel in main editor"
        >
          <Users size={16} />
          <span style={{ marginLeft: 6 }}>Collaboration</span>
        </button>

        <div style={{ flex: 1 }} />

        {/* Stats */}
        <div style={styles.stats}>
          {getExpansionStats(allNodes.length, visibleNodeIds.size, expandedNodeIds.size)} · {zoomLevel.toFixed(1)}x
          {allNodes.length > 1000 && <span style={{color: '#10b981', marginLeft: '8px'}}>⚡ Lazy Loading</span>}
        </div>

        {/* Export */}
        <button onClick={() => handleExport('svg')} style={styles.btn} title="Export SVG">
          <Download size={16} />
          SVG
        </button>
        <button onClick={() => handleExport('png')} style={styles.btn} title="Export PNG">
          <Download size={16} />
          PNG
        </button>
      </div>

          {/* Graph Content Area */}
          <div style={styles.graphContentArea}>
            {/* SVG Canvas for Force, WebVOWL, and OntoGraph */}
            <svg ref={svgRef} style={styles.svg}>
                <defs>
                  {/* Grid pattern */}
                  {showGrid && (
                    <pattern id="grid" width="20" height="20" patternUnits="userSpaceOnUse">
                      <path d="M 20 0 L 0 0 0 20" fill="none" stroke="#e5e5e5" strokeWidth="0.5" />
                    </pattern>
                  )}
                </defs>
                {showGrid && <rect width="100%" height="100%" fill="url(#grid)" />}
                <g ref={gRef} />
            </svg>

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
      </div>

        {/* Graph View Sidebar - Second Column */}
        {showPropertyPanel && (
          <GraphViewSidebar
            nodes={allNodes}
            edges={allEdges}
            selectedNode={selectedNodeInfo}
            onNodeSelect={(node) => {
              if (node) {
                setSelectedNodes(new Set([node.id]));
                setSelectedNodeInfo(node);
                
                // Open hierarchy navigator for class nodes
                if (node.type === 'class') {
                  setHierarchyRootNode(node);
                  setIsDialogMinimized(false);
                  setHierarchyDialogPosition({ x: 20, y: 100 });
                  setShowHierarchyDialog(true);
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
          />
        )}
      </div>

        {/* Hierarchy Dialog */}
        {showHierarchyDialog && hierarchyRootNode && (
          <div
            style={{
              position: 'fixed',
              left: `${hierarchyDialogPosition.x}px`,
              top: `${hierarchyDialogPosition.y}px`,
              width: isDialogMinimized ? 'auto' : '380px',
              minWidth: isDialogMinimized ? '280px' : 'auto',
              maxHeight: isDialogMinimized ? 'auto' : '500px',
              backgroundColor: '#fff',
              border: '1px solid #d1d5db',
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
                padding: '12px 18px',
                background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
                color: '#fff',
                fontWeight: '600',
                fontSize: '14px',
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
            {!isDialogMinimized && (
              <>
                {/* Root Node Info */}
                <div
                  style={{
                    padding: '12px 16px',
                    backgroundColor: '#f9fafb',
                    borderBottom: '1px solid #e5e7eb'
                  }}
                >
              <div style={{ fontSize: '16px', fontWeight: '600', color: '#1f2937', marginBottom: '4px' }}>
                {hierarchyRootNode.label}
              </div>
              <div style={{ fontSize: '11px', color: '#6b7280', fontFamily: 'monospace', wordBreak: 'break-all' }}>
                {hierarchyRootNode.id}
              </div>
              {hierarchyRootNode.description && (
                <div style={{ fontSize: '12px', color: '#4b5563', marginTop: '8px', fontStyle: 'italic' }}>
                  {hierarchyRootNode.description}
                </div>
              )}
            </div>

            {/* Hierarchy Tree */}
            <div
              style={{
                flex: 1,
                overflow: 'auto',
                padding: '16px',
                backgroundColor: '#fafafa'
              }}
            >
              <div style={{ 
                marginBottom: '14px', 
                fontSize: '11px', 
                fontWeight: '700', 
                color: '#6b7280',
                letterSpacing: '0.8px',
                textTransform: 'uppercase',
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                paddingBottom: '8px',
                borderBottom: '2px solid #e5e7eb'
              }}>
                <Box size={14} style={{ color: '#667eea' }} />
                Class Hierarchy
              </div>
              <div style={{ backgroundColor: '#ffffff', borderRadius: '6px', padding: '8px' }}>
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

            {/* Dialog Footer */}
            <div
              style={{
                padding: '12px 16px',
                backgroundColor: '#f9fafb',
                borderTop: '1px solid #e5e7eb',
                fontSize: '11px',
                color: '#6b7280'
              }}
            >
              <div style={{ marginBottom: '4px' }}>
                <strong>Navigation:</strong> Click arrow to expand/collapse
              </div>
              <div style={{ marginBottom: '4px' }}>
                <strong>Actions:</strong> Use <Plus size={12} style={{ display: 'inline', verticalAlign: 'middle' }} /> to add child, <GitBranch size={12} style={{ display: 'inline', verticalAlign: 'middle' }} /> for sibling, <Trash2 size={12} style={{ display: 'inline', verticalAlign: 'middle' }} /> to delete
              </div>
              <div>Click class name to select and view properties</div>
            </div>
              </>
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

        {/* Loading */}
        {loading && (
          <div style={styles.loadingOverlay}>
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
                    ➕ Expand Children
                  </button>
                ) : (
                  <button
                    style={styles.contextMenuItem}
                    onClick={() => {
                      handleToggleExpansion(contextMenu.nodeId!);
                      setContextMenu({ ...contextMenu, visible: false });
                    }}
                  >
                    ➖ Collapse Children
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
  stats: {
    fontSize: '13px',
    color: 'var(--text-secondary)',
    padding: '0 12px'
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

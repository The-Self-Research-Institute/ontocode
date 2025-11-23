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
  Users
} from 'lucide-react';
import type {
  OntologyNode,
  OntologyEdge,
  GraphSettings,
  GraphFilters,
  NodeType,
  EdgeType,
  ExportFormat
} from './types';
import PluginUpdateService from './PluginUpdateService';
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
  annotation: '#8b5cf6'
};

const EDGE_TYPE_COLORS: Record<EdgeType, string> = {
  subClassOf: '#667eea',
  instanceOf: '#10b981',
  propertyRelation: '#f59e0b',
  equivalentClass: '#ec4899',
  disjointWith: '#ef4444',
  domain: '#06b6d4',
  range: '#8b5cf6',
  inverseOf: '#fbbf24',
  custom: '#6b7280',
  temporal: '#34d399',
  spatial: '#3b82f6',
  probabilistic: '#fb923c'
};

// Default settings
const DEFAULT_SETTINGS: GraphSettings = {
  layout: 'force',
  showLabels: true,
  showArrows: true,
  physics: true,
  nodeSize: 16,  // Increased for better visibility and expand icons
  edgeWidth: 1.5,
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
  nodeTypes: new Set(['class', 'individual', 'property', 'dataProperty', 'objectProperty', 'annotation']),
  edgeTypes: new Set(['subClassOf', 'instanceOf', 'propertyRelation', 'equivalentClass', 'domain', 'range'])
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
    const namespace = extractNamespace(candidate);
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
  const [hoveredNode, setHoveredNode] = useState<string | null>(null);
  const [editMode, setEditMode] = useState(false);
  const [showPropertyPanel, setShowPropertyPanel] = useState(false);
  const [selectedNodeInfo, setSelectedNodeInfo] = useState<OntologyNode | null>(null);
  
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

  // Settings & Filters
  const [settings, setSettings] = useState<GraphSettings>(DEFAULT_SETTINGS);
  const [filters, setFilters] = useState<GraphFilters>(DEFAULT_FILTERS);
  const [searchQuery, setSearchQuery] = useState('');

  // Panels
  const [showSearch, setShowSearch] = useState(false);
  const [showFilters, setShowFilters] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showGrid, setShowGrid] = useState(false);

  // Advanced features
  const [zoomLevel, setZoomLevel] = useState(1);

  // Compute visible nodes and edges based on hierarchy
  const visibleNodes = useMemo(() =>
    allNodes.filter(n => visibleNodeIds.has(n.id)),
    [allNodes, visibleNodeIds]
  );

  const visibleEdges = useMemo(() => {
    if (visibleNodeIds.size === 0) return [];
    if (visibleNodeIds.size === allNodes.length) return allEdges; // All visible
    
    // Fast path: filter using Set lookups (O(1) per lookup)
    const edges = allEdges.filter(e => 
      visibleNodeIds.has(e.from) && visibleNodeIds.has(e.to)
    );
    
    console.log('[AdvancedGraphView] Visible edges:', edges.length, 'from total:', allEdges.length);
    
    return edges;
  }, [allEdges, visibleNodeIds, allNodes.length]);

  // Performance tracking (disabled for production performance)
  const renderTime = useRef(0);

  /**
   * ========================================================================
   * DATA FETCHING
   * ========================================================================
   */
  const fetchGraphData = useCallback(async () => {
    setLoading(true);
    setError(null);

    console.log('[AdvancedGraphView D3] 📡 Fetching graph data for project:', projectId);

    try {
      const cacheKey = `graph-${projectId}`;
      const cached = localStorage.getItem(cacheKey);
      const cacheTime = localStorage.getItem(`${cacheKey}-time`);

      // Use cache if less than 5 minutes old
      if (cached && cacheTime && Date.now() - parseInt(cacheTime) < 5 * 60 * 1000) {
        console.log('[AdvancedGraphView D3] ⚡ Using cached data');
        const cachedData = JSON.parse(cached);

        const normalizedNodes = (cachedData.nodes || []).map((node: any) => ({
          ...node,
          type: normalizeNodeType(node.type)
        }));

        const normalizedEdges = (cachedData.edges || []).map((edge: any) => ({
          ...edge,
          from: edge.source || edge.from,
          to: edge.target || edge.to,
          type: normalizeEdgeType(edge.type)
        }));

        setAllNodes(normalizedNodes);
        setAllEdges(normalizedEdges);

        // Initialize with root nodes only
        const rootIds = getRootNodes(normalizedNodes, normalizedEdges);
        updateHierarchyState(() => ({
          visible: new Set(rootIds),
          expanded: new Set()
        }));

        console.log(`[Hierarchy] Showing ${rootIds.length} root nodes out of ${normalizedNodes.length} total`);
        setLoading(false);
        return;
      }

      const url = `${(window as any).API_BASE_URL}/api/ontology/${projectId}/graph`;
      console.log('[AdvancedGraphView D3] 🌐 Fetching from:', url);

      const response = await fetch(url, {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('authToken')}`
        }
      });

      if (!response.ok) {
        throw new Error(`Failed to fetch graph data: ${response.statusText}`);
      }

      const data = await response.json();
      console.log('[AdvancedGraphView D3] ✅ Received', data.nodes?.length || 0, 'nodes and', data.edges?.length || 0, 'edges');

      const normalizedNodes = (data.nodes || []).map((node: any) => ({
        ...node,
        // Backend now returns normalized types
        type: node.type
      }));

      const transformedEdges = (data.edges || []).map((edge: any) => ({
        ...edge,
        // Backend now returns from/to and normalized types
        from: edge.from,
        to: edge.to,
        type: edge.type
      }));

      // Cache the result
      localStorage.setItem(cacheKey, JSON.stringify(data));
      localStorage.setItem(`${cacheKey}-time`, Date.now().toString());

      setAllNodes(normalizedNodes);
      setAllEdges(transformedEdges);

      console.log('[AdvancedGraphView D3] 📊 Using edges from API:', transformedEdges.length);

      // Initialize with root nodes only
      const rootIds = getRootNodes(normalizedNodes, transformedEdges);
      updateHierarchyState(() => ({
        visible: new Set(rootIds),
        expanded: new Set()
      }));

      console.log(`[Hierarchy] Showing ${rootIds.length} root nodes out of ${normalizedNodes.length} total`);
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Unknown error';
      setError(errorMessage);
      console.error('[AdvancedGraphView D3] ❌ Error:', errorMessage);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  /**
   * ========================================================================
   * FILTERING
   * ========================================================================
   */
  const filteredNodes = useMemo(() => {
    let filtered = visibleNodes.filter(node => filters.nodeTypes.has(node.type));

    console.log(`[Filtering] visibleNodes: ${visibleNodes.length}, after type filter: ${filtered.length}`);
    console.log(`[Filtering] Visible node labels: ${visibleNodes.map(n => n.label).join(', ')}`);

    // Search filter (Note: Search now handled by handleSearch with path expansion)
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      filtered = filtered.filter(node =>
        node.label.toLowerCase().includes(query) ||
        node.id.toLowerCase().includes(query) ||
        node.description?.toLowerCase().includes(query)
      );
    }

    console.log(`[Filtering] Final filtered nodes: ${filtered.length} - ${filtered.map(n => n.label).join(', ')}`);

    return filtered;
  }, [visibleNodes, filters, searchQuery]);

  const filteredEdges = useMemo(() => {
    const nodeIds = new Set(filteredNodes.map(n => n.id));
    const filtered = visibleEdges.filter(edge =>
      filters.edgeTypes.has(edge.type) &&
      nodeIds.has(edge.from) &&
      nodeIds.has(edge.to)
    );
    
    console.log('[AdvancedGraphView] Filtered edges:', filtered.length);
    console.log('[AdvancedGraphView] Edge types in filters:', Array.from(filters.edgeTypes));
    console.log('[AdvancedGraphView] Sample edges:', visibleEdges.slice(0, 3));
    if (filtered.length === 0 && visibleEdges.length > 0) {
      console.warn('[AdvancedGraphView] ⚠️ No edges after filtering! Check edge types.');
      console.warn('[AdvancedGraphView] Edge types in data:', [...new Set(visibleEdges.map(e => e.type))]);
    }
    
    return filtered;
  }, [visibleEdges, filteredNodes, filters]);

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

    const svg = d3.select(svgRef.current);
    const g = d3.select(gRef.current);

    const width = svgRef.current.clientWidth;
    const height = svgRef.current.clientHeight;

    // Clear existing content
    g.selectAll('*').remove();

    // Create arrow markers for each edge type (only once)
    const defs = svg.select('defs');
    if (defs.empty()) {
      const newDefs = svg.append('defs');
      Object.entries(EDGE_TYPE_COLORS).forEach(([type, color]) => {
        newDefs
          .append('marker')
          .attr('id', `arrow-${type}`)
          .attr('viewBox', '0 -5 10 10')
          .attr('refX', 10) // Tip of the arrow is at (10,0), so this aligns tip with the end of the line
          .attr('refY', 0)
          .attr('markerWidth', 6)
          .attr('markerHeight', 6)
          .attr('orient', 'auto')
          .append('path')
          .attr('d', 'M0,-5L10,0L0,5')
          .attr('fill', color);
      });
    }

    // Prepare D3 data
    const d3Nodes: D3Node[] = filteredNodes.map(node => ({
      ...node,
      x: width / 2 + (Math.random() - 0.5) * 100,
      y: height / 2 + (Math.random() - 0.5) * 100
    }));

    const nodeMap = new Map(d3Nodes.map(n => [n.id, n]));

    const d3Edges: D3Edge[] = filteredEdges.map(edge => ({
      ...edge,
      source: nodeMap.get(edge.from)!,
      target: nodeMap.get(edge.to)!
    })).filter(e => e.source && e.target);

    console.log('[AdvancedGraphView D3] ✅ Prepared D3 data - Nodes:', d3Nodes.length, 'Edges:', d3Edges.length);

    // Create force simulation with highly optimized parameters
    const nodeCount = d3Nodes.length;
    const simulation = d3.forceSimulation<D3Node>(d3Nodes)
      .force('link', d3.forceLink<D3Node, D3Edge>(d3Edges)
        .id(d => d.id)
        .distance(nodeCount > 100 ? 60 : 80) // Tighter layout for large graphs
        .strength(nodeCount > 100 ? 0.3 : 0.5)
        .iterations(1)) // Single iteration for speed
      .force('charge', d3.forceManyBody()
        .strength(nodeCount > 100 ? -200 : -300)
        .distanceMax(nodeCount > 100 ? 300 : 400)
        .theta(0.95)) // More aggressive Barnes-Hut (was 0.9)
      .force('center', d3.forceCenter(width / 2, height / 2)
        .strength(0.05)) // Weaker center force
      .force('collision', d3.forceCollide()
        .radius(d => ((d as D3Node).size || settings.nodeSize) + 5)
        .strength(0.7)
        .iterations(1)) // Single iteration for speed
      .alphaDecay(nodeCount > 100 ? 0.08 : 0.05) // Faster for large graphs
      .velocityDecay(0.4) // More damping (was 0.3)
      .alpha(0.3) // Lower initial energy for faster settle
      .alphaMin(0.001) // Stop earlier
      .alphaTarget(0); // Stop simulation faster

    simulationRef.current = simulation;

    // Draw edges
    const link = g.append('g')
      .attr('class', 'links')
      .selectAll('line')
      .data(d3Edges)
      .join('line')
      .attr('stroke', d => EDGE_TYPE_COLORS[d.type] || '#999')
      .attr('stroke-width', d => d.weight || settings.edgeWidth)
      .attr('stroke-opacity', 0.6)
      .attr('marker-end', d => settings.showArrows ? `url(#arrow-${d.type})` : null)
      .style('cursor', 'pointer')
      .on('click', (event, d) => {
        event.stopPropagation();
        onEdgeClick?.(d.id);
      });

    // Draw edge labels
    const linkLabel = g.append('g')
      .attr('class', 'link-labels')
      .selectAll('text')
      .data(d3Edges)
      .join('text')
      .attr('font-size', 10)
      .attr('fill', '#666')
      .attr('text-anchor', 'middle')
      .text(d => d.label || '')
      .style('pointer-events', 'none')
      .style('opacity', settings.showLabels ? 1 : 0);

    // Draw nodes
    const node = g.append('g')
      .attr('class', 'nodes')
      .selectAll('g')
      .data(d3Nodes)
      .join('g')
      .attr('class', 'node')
      .style('cursor', editMode ? 'move' : 'pointer')
      .call(d3.drag<SVGGElement, D3Node>()
        .on('start', dragStarted)
        .on('drag', dragged)
        .on('end', dragEnded) as any);

    // Node circles
    node.append('circle')
      .attr('r', d => d.size || settings.nodeSize)
      .attr('fill', d => d.color || TYPE_COLORS[d.type])
      .attr('stroke', '#fff')
      .attr('stroke-width', d => hasChildren(d.id, allEdges, allNodes) ? 3 : 2)
      .style('filter', visibleNodes.length > 100 ? 'none' : 'drop-shadow(0 2px 4px rgba(0,0,0,0.2))')
      .on('click', handleNodeClick)
      .on('contextmenu', handleNodeRightClick)
      .on('mouseover', handleNodeMouseOver)
      .on('mouseout', handleNodeMouseOut);

    // Node labels
    node.append('text')
      .attr('dx', d => (d.size || settings.nodeSize) + 8)
      .attr('dy', 4)
      .attr('font-size', 13)
      .attr('font-weight', '500')
      .attr('fill', '#333')
      .text(d => settings.showLabels ? d.label : '')
      .style('pointer-events', 'none')
      .style('user-select', 'none');

    // Node type badge
    node.append('text')
      .attr('dx', d => -(d.size || settings.nodeSize) / 2)
      .attr('dy', d => -(d.size || settings.nodeSize) - 5)
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
    
    simulation.on('tick', () => {
      tickCount++;
      // Skip frames for better performance
      if (tickCount % updateInterval !== 0) return;
      
      if (!ticking) {
        ticking = true;
        rafId = requestAnimationFrame(() => {
          link
            .attr('x1', d => (d.source as D3Node).x!)
            .attr('y1', d => (d.source as D3Node).y!)
            .attr('x2', d => {
              const source = d.source as D3Node;
              const target = d.target as D3Node;
              if (!source.x || !source.y || !target.x || !target.y) return 0;
              
              const dx = target.x - source.x;
              const dy = target.y - source.y;
              const dist = Math.sqrt(dx * dx + dy * dy);
              
              if (dist === 0) return target.x;
              
              // Shorten the edge to stop at the node boundary + padding
              // This ensures the arrow marker is visible and points to the node edge
              const r = (target.size || settings.nodeSize) + 3; 
              return target.x - (dx / dist) * r;
            })
            .attr('y2', d => {
              const source = d.source as D3Node;
              const target = d.target as D3Node;
              if (!source.x || !source.y || !target.x || !target.y) return 0;
              
              const dx = target.x - source.x;
              const dy = target.y - source.y;
              const dist = Math.sqrt(dx * dx + dy * dy);
              
              if (dist === 0) return target.y;
              
              const r = (target.size || settings.nodeSize) + 3;
              return target.y - (dy / dist) * r;
            });

          linkLabel
            .attr('x', d => ((d.source as D3Node).x! + (d.target as D3Node).x!) / 2)
            .attr('y', d => ((d.source as D3Node).y! + (d.target as D3Node).y!) / 2);

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
        const newSelected = new Set(selectedNodes);
        if (newSelected.has(d.id)) {
          newSelected.delete(d.id);
        } else {
          newSelected.add(d.id);
        }
        setSelectedNodes(newSelected);
      } else {
        // Single click - open hierarchy dialog
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

      // Update visual selection
      node.selectAll('circle')
        .attr('stroke', (n: any) => selectedNodes.has(n.id) || n.id === d.id ? '#667eea' : '#fff')
        .attr('stroke-width', (n: any) => selectedNodes.has(n.id) || n.id === d.id ? 3 : 2);
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

      // Highlight connected nodes and edges
      link
        .style('stroke-opacity', l =>
          (l.source as D3Node).id === d.id || (l.target as D3Node).id === d.id ? 1 : 0.2
        )
        .style('stroke-width', l =>
          (l.source as D3Node).id === d.id || (l.target as D3Node).id === d.id ? 2 : 1
        );

      node.style('opacity', n => {
        const isConnected = d3Edges.some(e =>
          ((e.source as D3Node).id === d.id && (e.target as D3Node).id === n.id) ||
          ((e.target as D3Node).id === d.id && (e.source as D3Node).id === n.id)
        );
        return n.id === d.id || isConnected ? 1 : 0.3;
      });
    }

    function handleNodeMouseOut() {
      setHoveredNode(null);
      d3.selectAll('.graph-tooltip').remove();

      // Reset highlighting
      link
        .style('stroke-opacity', 0.6)
        .style('stroke-width', d => d.weight || settings.edgeWidth);

      node.style('opacity', 1);
    }

    // Zoom behavior
    const zoom = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.1, 10])
      .on('zoom', (event) => {
        g.attr('transform', event.transform);
        setZoomLevel(event.transform.k);
      });

    svg.call(zoom as any);

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
  }, [filteredNodes, filteredEdges, settings, editMode, selectedNodes, onNodeClick, onEdgeClick, allEdges, allNodes, expandedNodeIds]);

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
      // Clear search - show only root nodes
      const rootIds = getRootNodes(allNodes, allEdges);
      updateHierarchyState(() => ({
        visible: new Set(rootIds),
        expanded: new Set()
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
    if (!projectId) return;
    const parentId = action.parentNode.id;
    const parentIriForMutation = resolveNodeIri(action.parentNode);
    const newLabel = action.label.trim();
    if (!newLabel) {
      setClassActionFeedback({ type: 'error', message: 'Class name cannot be empty.' });
      return;
    }

    if (!parentIriForMutation) {
      requestHostClassDialog(action.relation, action.targetNode, action.parentNode);
      setPendingClassAction(null);
      return;
    }

    try {
      setClassActionLoading(true);
      setClassActionFeedback(null);
      const newIri = buildClassIri(newLabel, action.parentNode || action.targetNode);

      await applyOntologyMutations([{
        type: 'createClass',
        iri: newIri,
        label: newLabel,
        parent: parentIriForMutation
      }]);

      const newNode: OntologyNode = {
        id: newIri,
        label: newLabel,
        type: 'class',
        namespace: extractNamespace(newIri) || extractNamespace(action.parentNode.id) || undefined,
        metadata: { createdBy: 'graph-view-plugin' }
      };

      setAllNodes(prev => [...prev, newNode]);
      setAllEdges(prev => ([
        ...prev,
        {
          id: `edge-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          from: newIri,
          to: parentId,
          label: 'subClassOf',
          type: 'subClassOf'
        }
      ]));

      updateHierarchyState(prev => {
        const visible = new Set(prev.visible);
        const expanded = new Set(prev.expanded);
        visible.add(newIri);
        expanded.add(parentId);
        return { visible, expanded };
      });

      setSelectedNodes(new Set([newIri]));
      setSelectedNodeInfo(newNode);
      setClassActionFeedback({ type: 'success', message: `Created class "${newLabel}"` });
      setPendingClassAction(null);
    } catch (actionError) {
      const message = actionError instanceof Error ? actionError.message : 'Failed to create class';
      setClassActionFeedback({ type: 'error', message });
      console.error('[Graph Dialog] Create class failed', actionError);
    } finally {
      setClassActionLoading(false);
    }
  }, [applyOntologyMutations, buildClassIri, projectId, requestHostClassDialog, updateHierarchyState]);

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
    if (svgRef.current) {
      const svg = d3.select(svgRef.current);
      svg.transition().duration(300).call(
        (d3.zoom() as any).scaleBy as any, 1.3
      );
    }
  };

  const handleZoomOut = () => {
    if (svgRef.current) {
      const svg = d3.select(svgRef.current);
      svg.transition().duration(300).call(
        (d3.zoom() as any).scaleBy as any, 0.7
      );
    }
  };

  const handleFit = () => {
    if (svgRef.current && gRef.current) {
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
        (d3.zoom() as any).transform as any,
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
      const svgData = new XMLSerializer().serializeToString(svgRef.current);
      const canvas = document.createElement('canvas');
      const ctx = canvas.getContext('2d');
      const img = new Image();

      img.onload = () => {
        canvas.width = img.width;
        canvas.height = img.height;
        ctx?.drawImage(img, 0, 0);
        canvas.toBlob(blob => {
          if (blob) {
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = `ontology-graph-${projectId}.png`;
            link.click();
            URL.revokeObjectURL(url);
          }
        });
      };

      img.src = 'data:image/svg+xml;base64,' + btoa(svgData);
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

  // Load data on mount
  useEffect(() => {
    fetchGraphData();
  }, [fetchGraphData]);

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
      <div key={node.id} style={{ marginLeft: level > 0 ? '20px' : '0' }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            padding: '6px 8px',
            cursor: 'pointer',
            borderRadius: '4px',
            backgroundColor: selectedNodes.has(node.id) ? '#e0e7ff' : 'transparent',
            transition: 'background-color 0.15s'
          }}
          onMouseEnter={(e) => e.currentTarget.style.backgroundColor = selectedNodes.has(node.id) ? '#e0e7ff' : '#f3f4f6'}
          onMouseLeave={(e) => e.currentTarget.style.backgroundColor = selectedNodes.has(node.id) ? '#e0e7ff' : 'transparent'}
        >
          {/* Expand Up (Parents) Icon */}
          {hasParents && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                handleExpandParents(node.id);
              }}
              style={{
                marginRight: '4px',
                cursor: 'pointer',
                fontSize: '12px',
                fontWeight: 'bold',
                color: '#fff',
                backgroundColor: '#667eea',
                border: 'none',
                borderRadius: '50%',
                width: '18px',
                height: '18px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                padding: 0
              }}
              title="Expand parents in graph"
            >
              <ChevronUp size={12} />
            </button>
          )}
          
          {/* Expand Down (Children) Icon */}
          {hasChildNodes && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                handleDialogExpand(node.id);
              }}
              style={{
                marginRight: '6px',
                cursor: 'pointer',
                fontSize: '12px',
                fontWeight: 'bold',
                color: '#fff',
                backgroundColor: '#10b981',
                border: 'none',
                borderRadius: '50%',
                width: '18px',
                height: '18px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                padding: 0
              }}
              title="Expand/collapse children"
            >
              {isExpanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
            </button>
          )}
          
          {/* Spacer if no icons */}
          {!hasChildNodes && !hasParents && <span style={{ width: '22px', display: 'inline-block' }} />}
          
          <span
            style={{
              fontSize: '13px',
              color: '#374151',
              flex: 1
            }}
            onClick={() => {
              setSelectedNodes(new Set([node.id]));
              setSelectedNodeInfo(node);
            }}
          >
            {node.label}
          </span>
          <span
            style={{
              fontSize: '10px',
              color: '#9ca3af',
              marginLeft: '8px',
              padding: '2px 6px',
              backgroundColor: TYPE_COLORS[node.type] + '20',
              borderRadius: '4px'
            }}
          >
            {node.type}
          </span>
          {canEdit && (
            <div style={{ display: 'flex', gap: '4px', marginLeft: '8px' }}>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  startCreateClassAction('child', node.id);
                }}
                disabled={!canAddChild}
                style={{
                  border: '1px solid #cbd5f5',
                  backgroundColor: canAddChild ? '#eef2ff' : '#f3f4f6',
                  color: canAddChild ? '#4c1d95' : '#9ca3af',
                  borderRadius: '4px',
                  padding: '2px',
                  width: '22px',
                  height: '22px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  cursor: canAddChild ? 'pointer' : 'not-allowed'
                }}
                title={canAddChild ? 'Add child class' : 'Action disabled while another request is running'}
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
                  border: '1px solid #cbd5f5',
                  backgroundColor: canAddSibling ? '#ecfeff' : '#f3f4f6',
                  color: canAddSibling ? '#155e75' : '#9ca3af',
                  borderRadius: '4px',
                  padding: '2px',
                  width: '22px',
                  height: '22px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  cursor: canAddSibling ? 'pointer' : 'not-allowed'
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
                  border: '1px solid #fecaca',
                  backgroundColor: canDeleteNode ? '#fef2f2' : '#f3f4f6',
                  color: canDeleteNode ? '#b91c1c' : '#9ca3af',
                  borderRadius: '4px',
                  padding: '2px',
                  width: '22px',
                  height: '22px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  cursor: canDeleteNode ? 'pointer' : 'not-allowed'
                }}
                title={canDeleteNode ? 'Delete class' : 'Action disabled while another request is running'}
              >
                <Trash2 size={14} />
              </button>
            </div>
          )}
        </div>
        {isExpanded && hasChildNodes && (
          <div>
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

      {/* Toolbar */}
      <div style={styles.toolbar}>
        {/* Primary actions */}
        <button onClick={() => fetchGraphData()} disabled={loading} style={styles.btnPrimary} title="Refresh graph">
          <RefreshCw size={16} className={loading ? 'spinning' : ''} />
          Refresh
        </button>

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
          onClick={async () => {
            // Fetch full graph from API for expand all
            setLoading(true);
            try {
              const url = `${(window as any).API_BASE_URL}/api/ontology/${projectId}/graph`;
              const response = await fetch(url, {
                headers: {
                  'Content-Type': 'application/json',
                  'Authorization': `Bearer ${localStorage.getItem('authToken')}`
                }
              });
              
              if (response.ok) {
                const data = await response.json();
                console.log('[Expand All] Loaded', data.nodes?.length, 'nodes and', data.edges?.length, 'edges');
                
                const normalizedNodes = (data.nodes || []).map((node: any) => ({
                  ...node,
                  type: normalizeNodeType(node.type)
                }));
                
                const normalizedEdges = (data.edges || []).map((edge: any) => ({
                  ...edge,
                  from: edge.source || edge.from,
                  to: edge.target || edge.to,
                  type: normalizeEdgeType(edge.type)
                }));
                
                setAllNodes(normalizedNodes);
                setAllEdges(normalizedEdges);
                
                // Show all nodes
                const allNodeIds = normalizedNodes.map((n: any) => n.id);
                updateHierarchyState(() => ({
                  visible: new Set(allNodeIds),
                  expanded: new Set(allNodeIds)
                }));
                
                console.log('[Expand All] Showing all', allNodeIds.length, 'nodes');
              }
            } catch (err) {
              console.error('[Expand All] Error:', err);
            } finally {
              setLoading(false);
            }
          }}
          style={styles.btn}
          title="Expand All Nodes (loads full graph)"
          disabled={loading}
        >
          Expand All
        </button>
        <button
          onClick={() => {
            // Collapse to root nodes using existing data
            console.log('[Collapse All] Collapsing to root nodes');
            
            // Find root nodes from existing data
            const rootIds = getRootNodes(allNodes, allEdges);

            updateHierarchyState(() => ({
              visible: new Set(rootIds),
              expanded: new Set()
            }));
            
            console.log('[Collapse All] Showing', rootIds.length, 'root nodes');
          }}
          style={styles.btn}
          title="Collapse to Root Nodes"
          disabled={loading}
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
        <button onClick={() => setShowSettings(!showSettings)} style={showSettings ? styles.btnActive : styles.btn} title="Settings">
          <Settings size={16} />
        </button>
        <button onClick={() => setShowGrid(!showGrid)} style={showGrid ? styles.btnActive : styles.btn} title="Grid">
          <Grid size={16} />
        </button>
        <button onClick={togglePhysics} style={settings.physics ? styles.btnActive : styles.btn} title="Physics">
          <Zap size={16} />
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

      {/* Main content */}
      <div style={styles.content}>
        {/* SVG Canvas */}
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

        {/* Property Panel */}
        {showPropertyPanel && selectedNodeInfo && (
          <div style={styles.propertyPanel}>
            <div style={styles.panelHeader}>
              <FileText size={18} />
              <h3 style={styles.panelTitle}>{selectedNodeInfo.label}</h3>
              <button onClick={() => setShowPropertyPanel(false)} style={styles.closeBtn}>×</button>
            </div>
            <div style={styles.propertyContent}>
              <div style={styles.propertyItem}>
                <div style={styles.propertyLabel}>Type</div>
                <div style={styles.propertyValue}>{selectedNodeInfo.type}</div>
              </div>
              <div style={styles.propertyItem}>
                <div style={styles.propertyLabel}>IRI</div>
                <div style={{ ...styles.propertyValue, fontFamily: 'monospace', fontSize: '11px', wordBreak: 'break-all' }}>
                  {selectedNodeInfo.id}
                </div>
              </div>
              {selectedNodeInfo.uri && selectedNodeInfo.uri !== selectedNodeInfo.id && (
                <div style={styles.propertyItem}>
                  <div style={styles.propertyLabel}>URI</div>
                  <div style={{ ...styles.propertyValue, fontFamily: 'monospace', fontSize: '11px', wordBreak: 'break-all' }}>
                    {selectedNodeInfo.uri}
                  </div>
                </div>
              )}
              {selectedNodeInfo.description && (
                <div style={styles.propertyItem}>
                  <div style={styles.propertyLabel}>Definition</div>
                  <div style={styles.propertyValue}>{selectedNodeInfo.description}</div>
                </div>
              )}
              {selectedNodeInfo.superClasses && selectedNodeInfo.superClasses.length > 0 && (
                <div style={styles.propertyItem}>
                  <div style={styles.propertyLabel}>Superclasses ({selectedNodeInfo.superClasses.length})</div>
                  <div style={styles.propertyValue}>
                    {selectedNodeInfo.superClasses.slice(0, 5).map((sc, idx) => (
                      <div key={idx} style={{ marginBottom: '4px', fontSize: '11px', padding: '4px', background: '#f3f4f6', borderRadius: '4px' }}>
                        {sc.split('#').pop() || sc.split('/').pop() || sc}
                      </div>
                    ))}
                    {selectedNodeInfo.superClasses.length > 5 && (
                      <div style={{ fontSize: '11px', color: '#666', fontStyle: 'italic' }}>+ {selectedNodeInfo.superClasses.length - 5} more</div>
                    )}
                  </div>
                </div>
              )}
              {selectedNodeInfo.equivalentClasses && selectedNodeInfo.equivalentClasses.length > 0 && (
                <div style={styles.propertyItem}>
                  <div style={styles.propertyLabel}>Equivalent Classes</div>
                  <div style={styles.propertyValue}>
                    {selectedNodeInfo.equivalentClasses.slice(0, 3).map((ec, idx) => (
                      <div key={idx} style={{ marginBottom: '4px', fontSize: '11px', padding: '4px', background: '#f3f4f6', borderRadius: '4px' }}>
                        {ec.split('#').pop() || ec.split('/').pop() || ec}
                      </div>
                    ))}
                  </div>
                </div>
              )}
              {selectedNodeInfo.disjointClasses && selectedNodeInfo.disjointClasses.length > 0 && (
                <div style={styles.propertyItem}>
                  <div style={styles.propertyLabel}>Disjoint With</div>
                  <div style={styles.propertyValue}>
                    {selectedNodeInfo.disjointClasses.slice(0, 3).map((dc, idx) => (
                      <div key={idx} style={{ marginBottom: '4px', fontSize: '11px', padding: '4px', background: '#fef2f2', borderRadius: '4px' }}>
                        {dc.split('#').pop() || dc.split('/').pop() || dc}
                      </div>
                    ))}
                  </div>
                </div>
              )}
              {selectedNodeInfo.annotations && Object.keys(selectedNodeInfo.annotations).length > 0 && (
                <div style={styles.propertyItem}>
                  <div style={styles.propertyLabel}>Annotations ({Object.keys(selectedNodeInfo.annotations).length})</div>
                  <div style={styles.propertyValue}>
                    {Object.entries(selectedNodeInfo.annotations).slice(0, 5).map(([key, value], idx) => (
                      <div key={idx} style={{ marginBottom: '6px', fontSize: '11px' }}>
                        <div style={{ fontWeight: '600', color: '#4b5563', marginBottom: '2px' }}>{key}:</div>
                        <div style={{ padding: '4px', background: '#f9fafb', borderRadius: '4px', wordBreak: 'break-word' }}>
                          {typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value)}
                        </div>
                      </div>
                    ))}
                    {Object.keys(selectedNodeInfo.annotations).length > 5 && (
                      <div style={{ fontSize: '11px', color: '#666', fontStyle: 'italic' }}>+ {Object.keys(selectedNodeInfo.annotations).length - 5} more</div>
                    )}
                  </div>
                </div>
              )}
              {selectedNodeInfo.confidence !== undefined && (
                <div style={styles.propertyItem}>
                  <div style={styles.propertyLabel}>Confidence</div>
                  <div style={styles.propertyValue}>{(selectedNodeInfo.confidence * 100).toFixed(0)}%</div>
                </div>
              )}
              {selectedNodeInfo.namespace && (
                <div style={styles.propertyItem}>
                  <div style={styles.propertyLabel}>Namespace</div>
                  <div style={{ ...styles.propertyValue, fontSize: '11px', fontFamily: 'monospace' }}>{selectedNodeInfo.namespace}</div>
                </div>
              )}
              {selectedNodeInfo.version && (
                <div style={styles.propertyItem}>
                  <div style={styles.propertyLabel}>Version</div>
                  <div style={styles.propertyValue}>{selectedNodeInfo.version}</div>
                </div>
              )}
              {selectedNodeInfo.metadata && Object.keys(selectedNodeInfo.metadata).length > 0 && (
                <div style={styles.propertyItem}>
                  <div style={styles.propertyLabel}>Metadata</div>
                  <div style={{ ...styles.propertyValue, fontSize: '10px', fontFamily: 'monospace', maxHeight: '100px', overflow: 'auto' }}>
                    <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                      {JSON.stringify(selectedNodeInfo.metadata, null, 2)}
                    </pre>
                  </div>
                </div>
              )}
              <div style={{ marginTop: '12px', paddingTop: '12px', borderTop: '1px solid #e5e7eb', fontSize: '11px', color: '#6b7280' }}>
                <div style={{ marginBottom: '4px' }}>
                  <strong>Tip:</strong> Click node to open hierarchy navigator
                </div>
                <div>Right-click for more options</div>
              </div>
            </div>
          </div>
        )}

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
                padding: '10px 16px',
                backgroundColor: '#667eea',
                color: '#fff',
                fontWeight: '600',
                fontSize: '14px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                cursor: 'move',
                userSelect: 'none',
                boxShadow: '0 2px 4px rgba(0,0,0,0.1)'
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
                padding: '12px 16px'
              }}
            >
              <div style={{ marginBottom: '12px', fontSize: '12px', fontWeight: '600', color: '#6b7280' }}>
                HIERARCHY
              </div>
              {renderHierarchyTree(hierarchyRootNode)}
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
                ▶ Click arrow to expand/collapse in both dialog and graph
              </div>
              <div>Click class name to select it</div>
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
      </div>

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
        .graph-tooltip {
          box-shadow: 0 4px 12px rgba(0,0,0,0.3);
        }
        button[style*="contextMenuItem"]:hover {
          background: #f3f4f6 !important;
        }
      `}</style>
    </div>
  );
};

// Styles
const styles: Record<string, React.CSSProperties> = {
  container: {
    width: '100%',
    height: '100%',
    display: 'flex',
    flexDirection: 'column',
    backgroundColor: '#f9fafb',
    overflow: 'hidden'
  },
  toolbar: {
    padding: '12px',
    backgroundColor: '#fff',
    borderBottom: '1px solid #e5e7eb',
    display: 'flex',
    gap: '8px',
    alignItems: 'center',
    flexWrap: 'wrap',
    boxShadow: '0 1px 3px rgba(0,0,0,0.05)'
  },
  btn: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    padding: '8px 12px',
    background: 'white',
    border: '1px solid #e5e7eb',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '14px',
    color: '#374151',
    transition: 'all 0.2s'
  },
  btnPrimary: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    padding: '8px 12px',
    background: '#667eea',
    border: '1px solid #667eea',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '14px',
    color: 'white',
    transition: 'all 0.2s'
  },
  btnActive: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    padding: '8px 12px',
    background: '#667eea',
    border: '1px solid #667eea',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '14px',
    color: 'white',
    transition: 'all 0.2s'
  },
  divider: {
    width: '1px',
    height: '24px',
    backgroundColor: '#e5e7eb'
  },
  stats: {
    fontSize: '13px',
    color: '#6b7280',
    padding: '0 12px'
  },
  content: {
    flex: 1,
    position: 'relative',
    overflow: 'hidden',
    backgroundColor: '#fff'
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
    background: 'white',
    borderRadius: '12px',
    boxShadow: '0 10px 40px rgba(0,0,0,0.1)',
    overflow: 'hidden'
  },
  propertyPanel: {
    position: 'absolute',
    top: '20px',
    right: '20px',
    width: '320px',
    background: 'white',
    borderRadius: '12px',
    boxShadow: '0 10px 40px rgba(0,0,0,0.1)',
    overflow: 'hidden',
    maxHeight: '80vh',
    display: 'flex',
    flexDirection: 'column'
  },
  panelHeader: {
    padding: '16px 20px',
    background: '#f9fafb',
    borderBottom: '1px solid #e5e7eb',
    display: 'flex',
    alignItems: 'center',
    gap: '8px'
  },
  panelTitle: {
    flex: 1,
    margin: 0,
    fontSize: '16px',
    fontWeight: '600',
    color: '#111827'
  },
  closeBtn: {
    background: 'transparent',
    border: 'none',
    color: '#9ca3af',
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
    borderBottom: '1px solid #e5e7eb',
    fontSize: '14px',
    outline: 'none',
    boxSizing: 'border-box'
  },
  searchResults: {
    padding: '12px 16px',
    fontSize: '13px',
    color: '#6b7280'
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
    color: '#6b7280',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
    marginBottom: '4px'
  },
  propertyValue: {
    fontSize: '14px',
    color: '#111827',
    lineHeight: '1.5'
  },
  loadingOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    background: 'rgba(255,255,255,0.9)',
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
    background: 'white',
    border: '2px solid #ef4444',
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
    background: 'white',
    borderRadius: '8px',
    boxShadow: '0 4px 20px rgba(0,0,0,0.15)',
    padding: '8px 0',
    zIndex: 1000,
    minWidth: '200px',
    border: '1px solid #e5e7eb'
  },
  contextMenuHeader: {
    padding: '8px 16px',
    fontSize: '13px',
    fontWeight: '600',
    color: '#111827',
    borderBottom: '1px solid #e5e7eb',
    marginBottom: '4px'
  },
  contextMenuItem: {
    width: '100%',
    padding: '10px 16px',
    background: 'transparent',
    border: 'none',
    textAlign: 'left',
    fontSize: '14px',
    color: '#374151',
    cursor: 'pointer',
    transition: 'background 0.2s',
    display: 'flex',
    alignItems: 'center',
    gap: '8px'
  },
  modalOverlay: {
    position: 'fixed',
    inset: 0,
    backgroundColor: 'rgba(0,0,0,0.35)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 1500,
    padding: '16px'
  },
  modal: {
    width: '100%',
    maxWidth: '420px',
    backgroundColor: '#fff',
    borderRadius: '10px',
    boxShadow: '0 20px 45px rgba(0,0,0,0.25)',
    overflow: 'hidden',
    border: '1px solid #e5e7eb'
  },
  modalHeader: {
    padding: '14px 18px',
    borderBottom: '1px solid #e5e7eb',
    fontSize: '15px',
    fontWeight: 600,
    color: '#111827'
  },
  modalBody: {
    padding: '18px'
  },
  modalActions: {
    display: 'flex',
    justifyContent: 'flex-end',
    gap: '8px',
    padding: '12px 18px',
    borderTop: '1px solid #e5e7eb',
    backgroundColor: '#f9fafb'
  },
  modalButton: {
    padding: '8px 16px',
    borderRadius: '6px',
    border: '1px solid #d1d5db',
    backgroundColor: '#fff',
    cursor: 'pointer',
    fontSize: '14px'
  },
  modalButtonPrimary: {
    padding: '8px 16px',
    borderRadius: '6px',
    border: '1px solid #2563eb',
    backgroundColor: '#2563eb',
    color: '#fff',
    cursor: 'pointer',
    fontSize: '14px'
  },
  modalInput: {
    width: '100%',
    padding: '8px 10px',
    borderRadius: '6px',
    border: '1px solid #d1d5db',
    fontSize: '14px'
  },
  modalLinkButton: {
    marginTop: '12px',
    background: 'none',
    border: 'none',
    color: '#2563eb',
    fontSize: '13px',
    padding: 0,
    cursor: 'pointer',
    textDecoration: 'underline',
    alignSelf: 'flex-start'
  }
};

export default AdvancedGraphView;

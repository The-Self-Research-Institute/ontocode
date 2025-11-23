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
  Grid
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
  hasChildren,
  toggleNodeExpansion as toggleExpansion,
  searchNodesWithPaths,
  expandAll as expandAllNodes,
  collapseAll as collapseAllNodes,
  getExpansionStats
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
  nodeSize: 12,  // Increased from 8 to 12 for better visibility
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
  const [visibleNodeIds, setVisibleNodeIds] = useState<Set<string>>(new Set());  // Currently visible
  const [expandedNodeIds, setExpandedNodeIds] = useState<Set<string>>(new Set());  // Expanded nodes
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // UI State
  const [selectedNodes, setSelectedNodes] = useState<Set<string>>(new Set());
  const [hoveredNode, setHoveredNode] = useState<string | null>(null);
  const [editMode, setEditMode] = useState(false);
  const [showPropertyPanel, setShowPropertyPanel] = useState(false);
  const [selectedNodeInfo, setSelectedNodeInfo] = useState<OntologyNode | null>(null);

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

  const visibleEdges = useMemo(() =>
    allEdges.filter(e =>
      visibleNodeIds.has(e.from) && visibleNodeIds.has(e.to)
    ),
    [allEdges, visibleNodeIds]
  );

  // Performance tracking
  const [fps, setFps] = useState(60);
  const [renderTime, setRenderTime] = useState(0);
  const frameTimesRef = useRef<number[]>([]);
  const animationFrameRef = useRef<number | null>(null);

  /**
   * ========================================================================
   * PERFORMANCE MONITORING
   * ========================================================================
   */
  useEffect(() => {
    let lastTime = performance.now();
    let frameCount = 0;

    const measureFPS = () => {
      const currentTime = performance.now();
      const delta = currentTime - lastTime;

      frameTimesRef.current.push(delta);
      if (frameTimesRef.current.length > 60) {
        frameTimesRef.current.shift();
      }

      frameCount++;
      if (delta >= 1000) {
        const avgFrameTime = frameTimesRef.current.reduce((a, b) => a + b, 0) / frameTimesRef.current.length;
        setFps(Math.round(1000 / avgFrameTime));
        frameCount = 0;
        lastTime = currentTime;
      }

      animationFrameRef.current = requestAnimationFrame(measureFPS);
    };

    animationFrameRef.current = requestAnimationFrame(measureFPS);

    return () => {
      if (animationFrameRef.current) {
        cancelAnimationFrame(animationFrameRef.current);
      }
    };
  }, []);

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
        setVisibleNodeIds(new Set(rootIds));
        setExpandedNodeIds(new Set());

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
        type: normalizeNodeType(node.type)
      }));

      const transformedEdges = (data.edges || []).map((edge: any) => ({
        ...edge,
        from: edge.source || edge.from,
        to: edge.target || edge.to,
        type: normalizeEdgeType(edge.type)
      }));

      // Cache the result
      localStorage.setItem(cacheKey, JSON.stringify(data));
      localStorage.setItem(`${cacheKey}-time`, Date.now().toString());

      setAllNodes(normalizedNodes);
      setAllEdges(transformedEdges);

      // Initialize with root nodes only
      const rootIds = getRootNodes(normalizedNodes, transformedEdges);
      setVisibleNodeIds(new Set(rootIds));
      setExpandedNodeIds(new Set());

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

    // Search filter (Note: Search now handled by handleSearch with path expansion)
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      filtered = filtered.filter(node =>
        node.label.toLowerCase().includes(query) ||
        node.id.toLowerCase().includes(query) ||
        node.description?.toLowerCase().includes(query)
      );
    }

    return filtered;
  }, [visibleNodes, filters, searchQuery]);

  const filteredEdges = useMemo(() => {
    const nodeIds = new Set(filteredNodes.map(n => n.id));
    return visibleEdges.filter(edge =>
      filters.edgeTypes.has(edge.type) &&
      nodeIds.has(edge.from) &&
      nodeIds.has(edge.to)
    );
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

    // Create arrow markers for each edge type
    const defs = svg.select('defs');
    if (defs.empty()) {
      svg.append('defs');
    }

    Object.entries(EDGE_TYPE_COLORS).forEach(([type, color]) => {
      svg.select('defs')
        .append('marker')
        .attr('id', `arrow-${type}`)
        .attr('viewBox', '0 -5 10 10')
        .attr('refX', 20)
        .attr('refY', 0)
        .attr('markerWidth', 6)
        .attr('markerHeight', 6)
        .attr('orient', 'auto')
        .append('path')
        .attr('d', 'M0,-5L10,0L0,5')
        .attr('fill', color);
    });

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

    // Create force simulation with optimized parameters
    const simulation = d3.forceSimulation<D3Node>(d3Nodes)
      .force('link', d3.forceLink<D3Node, D3Edge>(d3Edges)
        .id(d => d.id)
        .distance(80)
        .strength(0.5))
      .force('charge', d3.forceManyBody()
        .strength(-300)
        .distanceMax(400)
        .theta(0.9)) // Barnes-Hut optimization
      .force('center', d3.forceCenter(width / 2, height / 2))
      .force('collision', d3.forceCollide()
        .radius(d => ((d as D3Node).size || settings.nodeSize) + 5)
        .iterations(2)) // Reduce collision iterations for performance
      .alphaDecay(0.02)
      .velocityDecay(0.3)
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
      .attr('stroke-width', 2)
      .style('filter', 'drop-shadow(0 2px 4px rgba(0,0,0,0.2))')
      .on('click', handleNodeClick)
      .on('contextmenu', handleNodeRightClick)
      .on('mouseover', handleNodeMouseOver)
      .on('mouseout', handleNodeMouseOut);

    // Node labels
    node.append('text')
      .attr('dx', d => (d.size || settings.nodeSize) + 5)
      .attr('dy', 4)
      .attr('font-size', 12)
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

    // Add expand/collapse indicator (+/−)
    node.append('text')
      .attr('class', 'expand-indicator')
      .attr('dx', d => (d.size || settings.nodeSize) + 12)
      .attr('dy', 5)
      .attr('font-size', 16)
      .attr('font-weight', 'bold')
      .attr('fill', d => hasChildren(d.id, allEdges, allNodes) ? '#667eea' : '#ccc')
      .attr('cursor', d => hasChildren(d.id, allEdges, allNodes) ? 'pointer' : 'default')
      .text(d => {
        if (!hasChildren(d.id, allEdges, allNodes)) return '';
        return expandedNodeIds.has(d.id) ? '−' : '+';
      })
      .on('click', (event, d) => {
        event.stopPropagation();
        if (hasChildren(d.id, allEdges, allNodes)) {
          handleToggleExpansion(d.id);
        }
      });

    // Style nodes based on expandable state
    node.select('circle')
      .attr('stroke-width', d => hasChildren(d.id, allEdges, allNodes) ? 3 : 2)
      .attr('stroke-dasharray', d =>
        hasChildren(d.id, allEdges, allNodes) && !expandedNodeIds.has(d.id) ? '5,3' : 'none'
      );

    // Simulation tick
    simulation.on('tick', () => {
      link
        .attr('x1', d => (d.source as D3Node).x!)
        .attr('y1', d => (d.source as D3Node).y!)
        .attr('x2', d => (d.target as D3Node).x!)
        .attr('y2', d => (d.target as D3Node).y!);

      linkLabel
        .attr('x', d => ((d.source as D3Node).x! + (d.target as D3Node).x!) / 2)
        .attr('y', d => ((d.source as D3Node).y! + (d.target as D3Node).y!) / 2);

      node.attr('transform', d => `translate(${d.x},${d.y})`);
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
        // Single select
        setSelectedNodes(new Set([d.id]));
        setSelectedNodeInfo(d as OntologyNode);
        setShowPropertyPanel(true);
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
    const renderTime = endTime - startTime;
    setRenderTime(renderTime);
    console.log(`[AdvancedGraphView D3] ⚡ Render completed in ${renderTime.toFixed(2)}ms`);
    console.log(`[AdvancedGraphView D3] 📊 Performance: ${(filteredNodes.length / renderTime * 1000).toFixed(0)} nodes/sec`);

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
    const { newExpandedIds, newVisibleIds, action } = toggleExpansion(
      nodeId,
      expandedNodeIds,
      visibleNodeIds,
      allEdges,
      allNodes  // Pass nodes for parent-based hierarchy
    );

    setExpandedNodeIds(newExpandedIds);
    setVisibleNodeIds(newVisibleIds);

    console.log(`[User Action] ${action} node:`, allNodes.find(n => n.id === nodeId)?.label);
  }, [expandedNodeIds, visibleNodeIds, allEdges, allNodes]);

  const handleSearch = useCallback((query: string) => {
    if (!query) {
      // Clear search - show only root nodes
      const rootIds = getRootNodes(allNodes, allEdges);
      setVisibleNodeIds(new Set(rootIds));
      setExpandedNodeIds(new Set());
      setSearchQuery('');
      return;
    }

    const { nodesToShow, nodesToExpand } = searchNodesWithPaths(
      query,
      allNodes,
      allEdges
    );

    setVisibleNodeIds(nodesToShow);
    setExpandedNodeIds(nodesToExpand);
    setSearchQuery(query);

    console.log(`[Search] Found ${nodesToShow.size} nodes for query: "${query}"`);
  }, [allNodes, allEdges]);

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
          onClick={() => {
            const { newExpandedIds, newVisibleIds } = expandAllNodes(allNodes);
            setExpandedNodeIds(newExpandedIds);
            setVisibleNodeIds(newVisibleIds);
          }}
          style={styles.btn}
          title="Expand All Nodes"
        >
          Expand All
        </button>
        <button
          onClick={() => {
            const { newExpandedIds, newVisibleIds } = collapseAllNodes(allNodes, allEdges);
            setExpandedNodeIds(newExpandedIds);
            setVisibleNodeIds(newVisibleIds);
          }}
          style={styles.btn}
          title="Collapse to Root Nodes"
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

        <div style={{ flex: 1 }} />

        {/* Stats */}
        <div style={styles.stats}>
          {getExpansionStats(allNodes.length, visibleNodeIds.size, expandedNodeIds.size)} · {zoomLevel.toFixed(1)}x · {fps} FPS
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
              {selectedNodeInfo.description && (
                <div style={styles.propertyItem}>
                  <div style={styles.propertyLabel}>Description</div>
                  <div style={styles.propertyValue}>{selectedNodeInfo.description}</div>
                </div>
              )}
              {selectedNodeInfo.confidence !== undefined && (
                <div style={styles.propertyItem}>
                  <div style={styles.propertyLabel}>Confidence</div>
                  <div style={styles.propertyValue}>{(selectedNodeInfo.confidence * 100).toFixed(0)}%</div>
                </div>
              )}
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
                setExpandedNodeIds(newExpandedIds);
                setVisibleNodeIds(newVisibleIds);
                setContextMenu({ ...contextMenu, visible: false });
              }}
            >
              🌳 Expand All
            </button>
            <button
              style={styles.contextMenuItem}
              onClick={() => {
                const { newExpandedIds, newVisibleIds } = collapseAllNodes(allNodes, allEdges);
                setExpandedNodeIds(newExpandedIds);
                setVisibleNodeIds(newVisibleIds);
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
  }
};

export default AdvancedGraphView;

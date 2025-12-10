import React, { useEffect, useRef, useState } from 'react';
import * as d3 from 'd3';
import { ZoomIn, ZoomOut, Maximize2, Download, RefreshCw, Loader2, Filter, Eye, EyeOff } from 'lucide-react';
import apiClient from '../services/apiClient';

interface Node extends d3.SimulationNodeDatum {
  id: string;
  label: string;
  type: string;
  iri: string;
  size: number;
  color: string;
  x?: number;
  y?: number;
}

interface Edge {
  id: string;
  source: string | Node;
  target: string | Node;
  type: string;
  label: string;
  color: string;
  width: number;
}

interface GraphData {
  nodes: Node[];
  edges: Edge[];
  metadata: Record<string, any>;
}

interface OntologyGraphViewerProps {
  projectId: string;
}

const OntologyGraphViewer: React.FC<OntologyGraphViewerProps> = ({ projectId }) => {
  const svgRef = useRef<SVGSVGElement>(null);
  const [graphData, setGraphData] = useState<GraphData | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [includeIndividuals, setIncludeIndividuals] = useState(false);
  const [filters, setFilters] = useState({
    showClasses: true,
    showProperties: true,
    showIndividuals: true,
  });

  // Load graph data
  const loadGraph = async () => {
    setIsLoading(true);
    try {
      const response = await apiClient.get<GraphData>(
        `/api/ontology/${projectId}/visualization/graph`,
        { includeIndividuals }
      );
      setGraphData(response);
    } catch (error) {
      console.error('Failed to load graph', error);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    if (projectId) {
      loadGraph();
    }
  }, [projectId, includeIndividuals]);

  useEffect(() => {
    if (!graphData || !svgRef.current) return;

    // Clear previous visualization
    d3.select(svgRef.current).selectAll('*').remove();

    const svg = d3.select(svgRef.current);
    const width = svgRef.current.clientWidth;
    const height = svgRef.current.clientHeight;

    // Create container group for zoom
    const g = svg.append('g');

    // Setup zoom behavior
    const zoom = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.1, 4])
      .on('zoom', (event) => {
        g.attr('transform', event.transform);
      });

    svg.call(zoom);

    // Filter nodes and edges based on filters
    const filteredNodes = graphData.nodes.filter(node => {
      if (node.type === 'CLASS') return filters.showClasses;
      if (node.type === 'OBJECT_PROPERTY') return filters.showProperties;
      if (node.type === 'INDIVIDUAL') return filters.showIndividuals;
      return true;
    });

    const filteredNodeIds = new Set(filteredNodes.map(n => n.id));
    const filteredEdges = graphData.edges.filter(edge => {
      const sourceId = typeof edge.source === 'string' ? edge.source : edge.source.id;
      const targetId = typeof edge.target === 'string' ? edge.target : edge.target.id;
      return filteredNodeIds.has(sourceId) && filteredNodeIds.has(targetId);
    });

    // Create force simulation
    const simulation = d3.forceSimulation<Node>(filteredNodes)
      .force('link', d3.forceLink<Node, Edge>(filteredEdges)
        .id(d => d.id)
        .distance(100))
      .force('charge', d3.forceManyBody().strength(-300))
      .force('center', d3.forceCenter(width / 2, height / 2))
      .force('collision', d3.forceCollide().radius(30));

    // Create arrow markers for edges
    const defs = svg.append('defs');
    
    ['SUBCLASS_OF', 'INSTANCE_OF', 'DOMAIN', 'RANGE', 'PROPERTY'].forEach(type => {
      defs.append('marker')
        .attr('id', `arrow-${type}`)
        .attr('viewBox', '0 -5 10 10')
        .attr('refX', 20)
        .attr('refY', 0)
        .attr('markerWidth', 6)
        .attr('markerHeight', 6)
        .attr('orient', 'auto')
        .append('path')
        .attr('d', 'M0,-5L10,0L0,5')
        .attr('fill', getEdgeColor(type));
    });

    // Create edges
    const link = g.append('g')
      .selectAll('line')
      .data(filteredEdges)
      .join('line')
      .attr('stroke', d => d.color)
      .attr('stroke-width', d => d.width)
      .attr('marker-end', d => `url(#arrow-${d.type})`)
      .attr('opacity', 0.6);

    // Create edge labels
    const edgeLabels = g.append('g')
      .selectAll('text')
      .data(filteredEdges)
      .join('text')
      .attr('font-size', 10)
      .attr('fill', '#666')
      .attr('text-anchor', 'middle')
      .text(d => d.label);

    // Create nodes
    const node = g.append('g')
      .selectAll('circle')
      .data(filteredNodes)
      .join('circle')
      .attr('r', d => d.size)
      .attr('fill', d => d.color)
      .attr('stroke', '#fff')
      .attr('stroke-width', 2)
      .style('cursor', 'pointer')
      .call(drag(simulation) as any);

    // Add node labels
    const labels = g.append('g')
      .selectAll('text')
      .data(filteredNodes)
      .join('text')
      .attr('font-size', 12)
      .attr('dx', 15)
      .attr('dy', 4)
      .attr('fill', '#333')
      .text(d => d.label)
      .style('pointer-events', 'none');

    // Add tooltips
    node
      .on('mouseover', function(event, d) {
        d3.select(this)
          .transition()
          .duration(200)
          .attr('r', d.size * 1.5)
          .attr('stroke-width', 3);

        // Show tooltip
        const tooltip = d3.select('body').append('div')
          .attr('class', 'graph-tooltip')
          .style('position', 'absolute')
          .style('background', 'rgba(0, 0, 0, 0.8)')
          .style('color', '#fff')
          .style('padding', '8px 12px')
          .style('border-radius', '4px')
          .style('font-size', '12px')
          .style('pointer-events', 'none')
          .style('z-index', 1000)
          .html(`
            <div><strong>${d.label}</strong></div>
            <div>Type: ${d.type}</div>
            <div class="text-xs opacity-75">${d.iri}</div>
          `)
          .style('left', (event.pageX + 10) + 'px')
          .style('top', (event.pageY - 10) + 'px');
      })
      .on('mouseout', function(event, d) {
        d3.select(this)
          .transition()
          .duration(200)
          .attr('r', d.size)
          .attr('stroke-width', 2);

        d3.selectAll('.graph-tooltip').remove();
      })
      .on('click', (event, d) => {
        console.log('Clicked node:', d);
        // Can add navigation or detail panel here
      });

    // Update positions on simulation tick
    simulation.on('tick', () => {
      link
        .attr('x1', d => (d.source as Node).x!)
        .attr('y1', d => (d.source as Node).y!)
        .attr('x2', d => (d.target as Node).x!)
        .attr('y2', d => (d.target as Node).y!);

      edgeLabels
        .attr('x', d => ((d.source as Node).x! + (d.target as Node).x!) / 2)
        .attr('y', d => ((d.source as Node).y! + (d.target as Node).y!) / 2);

      node
        .attr('cx', d => d.x!)
        .attr('cy', d => d.y!);

      labels
        .attr('x', d => d.x!)
        .attr('y', d => d.y!);
    });

    // Cleanup
    return () => {
      simulation.stop();
    };

  }, [graphData, filters]);

  // Drag behavior
  const drag = (simulation: d3.Simulation<Node, undefined>) => {
    function dragstarted(event: any, d: Node) {
      if (!event.active) simulation.alphaTarget(0.3).restart();
      d.x = d.x;
      d.y = d.y;
    }

    function dragged(event: any, d: Node) {
      d.x = event.x;
      d.y = event.y;
    }

    function dragended(event: any, d: Node) {
      if (!event.active) simulation.alphaTarget(0);
      d.x = null;
      d.y = null;
    }

    return d3.drag<SVGCircleElement, Node>()
      .on('start', dragstarted)
      .on('drag', dragged)
      .on('end', dragended);
  };

  // Helper function for edge colors
  function getEdgeColor(type: string): string {
    switch (type) {
      case 'SUBCLASS_OF': return '#3498DB';
      case 'INSTANCE_OF': return '#E74C3C';
      case 'DOMAIN': return '#50C878';
      case 'RANGE': return '#F39C12';
      case 'PROPERTY': return '#9B59B6';
      default: return '#95A5A6';
    }
  }

  // Zoom controls
  const handleZoomIn = () => {
    const svg = d3.select(svgRef.current!);
    svg.transition().call(d3.zoom<SVGSVGElement, unknown>().scaleBy as any, 1.3);
  };

  const handleZoomOut = () => {
    const svg = d3.select(svgRef.current!);
    svg.transition().call(d3.zoom<SVGSVGElement, unknown>().scaleBy as any, 0.7);
  };

  const handleReset = () => {
    const svg = d3.select(svgRef.current!);
    svg.transition().call(
      d3.zoom<SVGSVGElement, unknown>().transform as any,
      d3.zoomIdentity
    );
  };

  // Export as PNG
  const handleExport = () => {
    if (!svgRef.current) return;

    const svgData = new XMLSerializer().serializeToString(svgRef.current);
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d')!;
    const img = new Image();

    img.onload = () => {
      canvas.width = img.width;
      canvas.height = img.height;
      ctx.drawImage(img, 0, 0);
      const pngFile = canvas.toDataURL('image/png');
      const downloadLink = document.createElement('a');
      downloadLink.download = `ontology-graph-${projectId}.png`;
      downloadLink.href = pngFile;
      downloadLink.click();
    };

    img.src = 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(svgData)));
  };

  return (
    <div className="h-full flex flex-col bg-white">
      {/* Header */}
      <header className="p-4 border-b border-gray-200 bg-gray-50">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h1 className="text-2xl font-bold text-gray-800 flex items-center gap-2">
              <Eye size={28} className="text-indigo-600" />
              Ontology Visualization
            </h1>
            <p className="text-sm text-gray-600 mt-1">
              Interactive graph view of your ontology
            </p>
          </div>

          {/* Controls */}
          <div className="flex gap-2">
            <button
              onClick={handleZoomIn}
              className="p-2 bg-white border border-gray-300 rounded-lg hover:bg-gray-50"
              title="Zoom In"
            >
              <ZoomIn size={20} />
            </button>
            <button
              onClick={handleZoomOut}
              className="p-2 bg-white border border-gray-300 rounded-lg hover:bg-gray-50"
              title="Zoom Out"
            >
              <ZoomOut size={20} />
            </button>
            <button
              onClick={handleReset}
              className="p-2 bg-white border border-gray-300 rounded-lg hover:bg-gray-50"
              title="Reset View"
            >
              <Maximize2 size={20} />
            </button>
            <button
              onClick={handleExport}
              className="p-2 bg-white border border-gray-300 rounded-lg hover:bg-gray-50"
              title="Export as PNG"
            >
              <Download size={20} />
            </button>
            <button
              onClick={loadGraph}
              disabled={isLoading}
              className="p-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 disabled:bg-indigo-300"
              title="Refresh"
            >
              {isLoading ? <Loader2 size={20} className="animate-spin" /> : <RefreshCw size={20} />}
            </button>
          </div>
        </div>

        {/* Filters */}
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <Filter size={16} className="text-gray-600" />
            <span className="text-sm font-medium text-gray-700">Show:</span>
          </div>

          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="checkbox"
              checked={filters.showClasses}
              onChange={(e) => setFilters({...filters, showClasses: e.target.checked})}
              className="w-4 h-4"
            />
            <span className="text-sm">Classes</span>
            <span className="w-3 h-3 rounded-full bg-blue-500"></span>
          </label>

          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="checkbox"
              checked={filters.showProperties}
              onChange={(e) => setFilters({...filters, showProperties: e.target.checked})}
              className="w-4 h-4"
            />
            <span className="text-sm">Properties</span>
            <span className="w-3 h-3 rounded-full bg-green-500"></span>
          </label>

          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="checkbox"
              checked={includeIndividuals}
              onChange={(e) => setIncludeIndividuals(e.target.checked)}
              className="w-4 h-4"
            />
            <span className="text-sm">Individuals</span>
            <span className="w-3 h-3 rounded-full bg-red-500"></span>
          </label>

          {graphData && (
            <div className="ml-auto text-sm text-gray-600">
              {graphData.nodes.length} nodes, {graphData.edges.length} edges
            </div>
          )}
        </div>
      </header>

      {/* Visualization */}
      <div className="flex-1 relative">
        {isLoading && (
          <div className="absolute inset-0 flex items-center justify-center bg-white bg-opacity-75 z-10">
            <div className="text-center">
              <Loader2 size={48} className="animate-spin text-indigo-600 mx-auto mb-4" />
              <p className="text-gray-600">Loading ontology graph...</p>
            </div>
          </div>
        )}

        <svg
          ref={svgRef}
          className="w-full h-full"
          style={{ background: '#f9fafb' }}
        />
      </div>

      {/* Legend */}
      <div className="p-4 border-t border-gray-200 bg-gray-50">
        <div className="flex items-center gap-6 text-sm">
          <div className="font-medium text-gray-700">Legend:</div>
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-full bg-blue-500"></div>
            <span>Class</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-full bg-green-500"></div>
            <span>Object Property</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-full bg-orange-500"></div>
            <span>Data Property</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-full bg-red-500"></div>
            <span>Individual</span>
          </div>
          <div className="ml-auto flex items-center gap-4">
            <div className="flex items-center gap-2">
              <div className="w-8 h-0.5 bg-blue-500"></div>
              <span>subClassOf</span>
            </div>
            <div className="flex items-center gap-2">
              <div className="w-8 h-0.5 bg-green-500"></div>
              <span>domain/range</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default OntologyGraphViewer;
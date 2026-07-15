import React, { useEffect, useRef, useState } from 'react';
import * as d3 from 'd3';
import { Download, RefreshCw, Loader2, ZoomIn, ZoomOut, Maximize2 } from 'lucide-react';
import apiClient from '../services/apiClient';

/**
 * VOWL (Visual Notation for OWL Ontologies) Viewer
 * Implements the VOWL specification for ontology visualization
 */

interface VOWLNode extends d3.SimulationNodeDatum {
  id: string;
  label: string;
  type: 'class' | 'datatype' | 'literal';
  iri: string;
  shape: 'circle' | 'rect';
}

interface VOWLProperty {
  id: string;
  source: string;
  target: string;
  label: string;
  type: 'objectProperty' | 'dataProperty' | 'subClassOf';
}

interface VOWLViewerProps {
  projectId: string;
}

const VOWLViewer: React.FC<VOWLViewerProps> = ({ projectId }) => {
  const svgRef = useRef<SVGSVGElement>(null);
  const [data, setData] = useState<any>(null);
  const [isLoading, setIsLoading] = useState(false);

  const loadData = async () => {
    setIsLoading(true);
    try {
      const response = await apiClient.get(`/api/ontology/${projectId}/visualization/graph`);
      setData(response);
    } catch (error) {
      console.error('Failed to load data', error);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    if (projectId) loadData();
  }, [projectId]);

  useEffect(() => {
    if (!data || !svgRef.current) return;

    const svg = d3.select(svgRef.current);
    const width = svgRef.current.clientWidth;
    const height = svgRef.current.clientHeight;

    svg.selectAll('*').remove();

    const g = svg.append('g');

    // Zoom behavior
    const zoom = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.1, 4])
      .on('zoom', (event) => g.attr('transform', event.transform));
    svg.call(zoom);

    // Convert data to VOWL format
    const vowlNodes: VOWLNode[] = data.nodes
      .filter((n: any) => n.type === 'CLASS')
      .map((n: any) => ({
        id: n.id,
        label: n.label,
        type: 'class' as const,
        iri: n.iri,
        shape: 'circle' as const,
      }));

    const vowlLinks = data.edges.map((e: any) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      label: e.label,
      type: e.type === 'SUBCLASS_OF' ? 'subClassOf' : 'objectProperty',
    }));

    // Force simulation with VOWL-specific forces
    const simulation = d3.forceSimulation(vowlNodes)
      .force('link', d3.forceLink(vowlLinks)
        .id((d: any) => d.id)
        .distance(150))
      .force('charge', d3.forceManyBody().strength(-500))
      .force('center', d3.forceCenter(width / 2, height / 2))
      .force('collision', d3.forceCollide().radius(50));

    // Create arrow markers
    const defs = svg.append('defs');
    
    // SubClassOf arrow (hollow triangle)
    defs.append('marker')
      .attr('id', 'arrow-subclass')
      .attr('viewBox', '0 -5 10 10')
      .attr('refX', 25)
      .attr('refY', 0)
      .attr('markerWidth', 8)
      .attr('markerHeight', 8)
      .attr('orient', 'auto')
      .append('path')
      .attr('d', 'M0,-5L10,0L0,5Z')
      .attr('fill', 'none')
      .attr('stroke', '#666')
      .attr('stroke-width', 1.5);

    // Object property arrow
    defs.append('marker')
      .attr('id', 'arrow-property')
      .attr('viewBox', '0 -5 10 10')
      .attr('refX', 25)
      .attr('refY', 0)
      .attr('markerWidth', 8)
      .attr('markerHeight', 8)
      .attr('orient', 'auto')
      .append('path')
      .attr('d', 'M0,-5L10,0L0,5')
      .attr('fill', '#666');

    // Draw edges
    const link = g.append('g')
      .selectAll('path')
      .data(vowlLinks)
      .join('path')
      .attr('stroke', d => d.type === 'subClassOf' ? '#666' : '#999')
      .attr('stroke-width', d => d.type === 'subClassOf' ? 2 : 1.5)
      .attr('fill', 'none')
      .attr('marker-end', d => 
        d.type === 'subClassOf' ? 'url(#arrow-subclass)' : 'url(#arrow-property)'
      );

    // Edge labels with white background
    const edgeLabelGroup = g.append('g')
      .selectAll('g')
      .data(vowlLinks)
      .join('g');

    edgeLabelGroup.append('rect')
      .attr('fill', 'white')
      .attr('stroke', '#ddd')
      .attr('stroke-width', 1)
      .attr('rx', 3);

    edgeLabelGroup.append('text')
      .attr('font-size', 11)
      .attr('fill', '#444')
      .attr('text-anchor', 'middle')
      .attr('dy', '0.35em')
      .text(d => d.label);

    // Draw nodes as circles (VOWL style)
    const node = g.append('g')
      .selectAll('circle')
      .data(vowlNodes)
      .join('circle')
      .attr('r', 20)
      .attr('fill', '#4A90E2')
      .attr('stroke', '#2E5C8A')
      .attr('stroke-width', 3)
      .style('cursor', 'pointer')
      .call(d3.drag<SVGCircleElement, VOWLNode>()
        .on('start', (event, d) => {
          if (!event.active) simulation.alphaTarget(0.3).restart();
          d.fx = d.x;
          d.fy = d.y;
        })
        .on('drag', (event, d) => {
          d.fx = event.x;
          d.fy = event.y;
        })
        .on('end', (event, d) => {
          if (!event.active) simulation.alphaTarget(0);
          d.fx = null;
          d.fy = null;
        }) as any);

    // Node labels
    const labels = g.append('g')
      .selectAll('text')
      .data(vowlNodes)
      .join('text')
      .attr('text-anchor', 'middle')
      .attr('dy', '0.35em')
      .attr('font-size', 12)
      .attr('font-weight', 'bold')
      .attr('fill', 'white')
      .attr('pointer-events', 'none')
      .text(d => d.label.length > 12 ? d.label.substring(0, 10) + '...' : d.label);

    // Tooltips
    node.append('title').text(d => `${d.label}\n${d.iri}`);

    // Update positions on tick
    simulation.on('tick', () => {
      link.attr('d', (d: any) => {
        const source = d.source;
        const target = d.target;
        return `M${source.x},${source.y} L${target.x},${target.y}`;
      });

      edgeLabelGroup.attr('transform', (d: any) => {
        const x = (d.source.x + d.target.x) / 2;
        const y = (d.source.y + d.target.y) / 2;
        return `translate(${x},${y})`;
      });

      edgeLabelGroup.select('rect').each(function(d: any) {
        const textNode = d3.select(this.parentNode).select('text').node() as SVGTextElement;
        if (textNode) {
          const bbox = textNode.getBBox();
          d3.select(this)
            .attr('x', -bbox.width / 2 - 4)
            .attr('y', -bbox.height / 2 - 2)
            .attr('width', bbox.width + 8)
            .attr('height', bbox.height + 4);
        }
      });

      node
        .attr('cx', d => d.x!)
        .attr('cy', d => d.y!);

      labels
        .attr('x', d => d.x!)
        .attr('y', d => d.y!);
    });

    return () => simulation.stop();
  }, [data]);

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

  return (
    <div className="h-full flex flex-col bg-white">
      <header className="p-4 border-b border-gray-200 bg-gray-50">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-800">VOWL Visualization</h1>
            <p className="text-sm text-gray-600 mt-1">
              Visual Notation for OWL Ontologies
            </p>
          </div>
          <div className="flex gap-2">
            <button onClick={handleZoomIn} className="p-2 bg-white border rounded-lg hover:bg-gray-50">
              <ZoomIn size={20} />
            </button>
            <button onClick={handleZoomOut} className="p-2 bg-white border rounded-lg hover:bg-gray-50">
              <ZoomOut size={20} />
            </button>
            <button onClick={handleReset} className="p-2 bg-white border rounded-lg hover:bg-gray-50">
              <Maximize2 size={20} />
            </button>
            <button
              onClick={loadData}
              disabled={isLoading}
              className="p-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700"
            >
              {isLoading ? <Loader2 size={20} className="animate-spin" /> : <RefreshCw size={20} />}
            </button>
          </div>
        </div>
      </header>

      <div className="flex-1 relative">
        {isLoading && (
          <div className="absolute inset-0 flex items-center justify-center bg-white bg-opacity-75 z-10">
            <div className="text-center">
              <Loader2 size={48} className="animate-spin text-indigo-600 mx-auto mb-4" />
              <p className="text-gray-600">Loading VOWL visualization...</p>
            </div>
          </div>
        )}
        <svg ref={svgRef} className="w-full h-full" style={{ background: '#fafafa' }} />
      </div>

      <div className="p-4 border-t border-gray-200 bg-gray-50">
        <div className="flex items-center gap-6 text-sm">
          <div className="font-medium text-gray-700">VOWL Notation:</div>
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-full bg-blue-500 border-2 border-blue-700"></div>
            <span>Class</span>
          </div>
          <div className="flex items-center gap-2">
            <svg width="32" height="16">
              <line x1="0" y1="8" x2="32" y2="8" stroke="#666" strokeWidth="2" markerEnd="url(#demo-subclass)" />
            </svg>
            <span>subClassOf (hollow arrow)</span>
          </div>
          <div className="flex items-center gap-2">
            <svg width="32" height="16">
              <line x1="0" y1="8" x2="32" y2="8" stroke="#999" strokeWidth="1.5" />
            </svg>
            <span>Object Property (solid arrow)</span>
          </div>
        </div>
      </div>
    </div>
  );
};

export default VOWLViewer;
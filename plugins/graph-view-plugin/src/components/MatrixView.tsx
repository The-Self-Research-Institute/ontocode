import React, { useEffect, useRef } from 'react';
import * as d3 from 'd3';
import type { OntologyNode, OntologyEdge } from '../types';
import { generateMatrixVisualization } from '../layouts/MatrixLayout';

interface MatrixViewProps {
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  width: number;
  height: number;
  onNodeClick?: (nodeId: string) => void;
  onEdgeClick?: (edgeId: string) => void;
}

export const MatrixView: React.FC<MatrixViewProps> = ({
  nodes,
  edges,
  width,
  height,
  onNodeClick,
  onEdgeClick
}) => {
  const svgRef = useRef<SVGSVGElement>(null);

  useEffect(() => {
    if (!svgRef.current || nodes.length === 0) return;

    const svg = d3.select(svgRef.current);
    svg.selectAll('*').remove();

    const cellSize = Math.min(20, Math.min(width, height) / (nodes.length + 5));
    const padding = 100;

    const matrixData = generateMatrixVisualization(nodes, edges, {
      width,
      height,
      cellSize,
      padding
    });

    const colorScale = d3.scaleOrdinal<string>()
      .domain(['subClassOf', 'instanceOf', 'propertyRelation', 'domain', 'range', 'custom'])
      .range(['#667eea', '#10b981', '#f59e0b', '#06b6d4', '#8b5cf6', '#6b7280']);

    svg.append('g')
      .selectAll('rect')
      .data(matrixData.cells)
      .join('rect')
      .attr('x', d => d.x)
      .attr('y', d => d.y)
      .attr('width', matrixData.cellSize)
      .attr('height', matrixData.cellSize)
      .attr('fill', d => colorScale(d.edge.type))
      .attr('stroke', '#fff')
      .attr('stroke-width', 1)
      .style('cursor', 'pointer')
      .on('mouseover', function(event, d) {
        d3.select(this)
          .attr('stroke', '#000')
          .attr('stroke-width', 2);

        const tooltip = d3.select('body').append('div')
          .attr('class', 'matrix-tooltip')
          .style('position', 'absolute')
          .style('background', 'rgba(0,0,0,0.8)')
          .style('color', '#fff')
          .style('padding', '8px')
          .style('border-radius', '4px')
          .style('font-size', '12px')
          .style('pointer-events', 'none')
          .style('z-index', '10000')
          .html(`
            <strong>${d.sourceNode.label}</strong> 
            <span style="color: ${colorScale(d.edge.type)}">${d.edge.label}</span> 
            <strong>${d.targetNode.label}</strong>
          `)
          .style('left', (event.pageX + 10) + 'px')
          .style('top', (event.pageY - 10) + 'px');
      })
      .on('mouseout', function() {
        d3.select(this)
          .attr('stroke', '#fff')
          .attr('stroke-width', 1);
        d3.selectAll('.matrix-tooltip').remove();
      })
      .on('click', (event, d) => {
        event.stopPropagation();
        onEdgeClick?.(d.edge.id);
      });

    svg.append('g')
      .attr('class', 'x-labels')
      .selectAll('text')
      .data(matrixData.xLabels)
      .join('text')
      .attr('x', d => d.x)
      .attr('y', padding - 10)
      .attr('text-anchor', 'start')
      .attr('transform', d => `rotate(-45 ${d.x} ${padding - 10})`)
      .attr('font-size', 10)
      .attr('fill', d => getNodeColor(d.node.type))
      .text(d => d.label.length > 15 ? d.label.substring(0, 12) + '...' : d.label)
      .style('cursor', 'pointer')
      .on('click', (event, d) => {
        event.stopPropagation();
        onNodeClick?.(d.node.id);
      });

    svg.append('g')
      .attr('class', 'y-labels')
      .selectAll('text')
      .data(matrixData.yLabels)
      .join('text')
      .attr('x', padding - 10)
      .attr('y', d => d.y)
      .attr('text-anchor', 'end')
      .attr('dominant-baseline', 'middle')
      .attr('font-size', 10)
      .attr('fill', d => getNodeColor(d.node.type))
      .text(d => d.label.length > 15 ? d.label.substring(0, 12) + '...' : d.label)
      .style('cursor', 'pointer')
      .on('click', (event, d) => {
        event.stopPropagation();
        onNodeClick?.(d.node.id);
      });

    const legendData = [
      { type: 'subClassOf', label: 'Subclass' },
      { type: 'instanceOf', label: 'Instance' },
      { type: 'propertyRelation', label: 'Property' },
      { type: 'domain', label: 'Domain' },
      { type: 'range', label: 'Range' }
    ];

    const legend = svg.append('g')
      .attr('class', 'legend')
      .attr('transform', `translate(${width - 150}, 20)`);

    legend.selectAll('rect')
      .data(legendData)
      .join('rect')
      .attr('x', 0)
      .attr('y', (d, i) => i * 25)
      .attr('width', 20)
      .attr('height', 20)
      .attr('fill', d => colorScale(d.type));

    legend.selectAll('text')
      .data(legendData)
      .join('text')
      .attr('x', 25)
      .attr('y', (d, i) => i * 25 + 15)
      .attr('font-size', 12)
      .text(d => d.label);

  }, [nodes, edges, width, height, onNodeClick, onEdgeClick]);

  return (
    <svg
      ref={svgRef}
      width={width}
      height={height}
      style={{ background: '#fff' }}
    />
  );
};

function getNodeColor(type: string): string {
  const colors: Record<string, string> = {
    class: '#667eea',
    individual: '#10b981',
    property: '#f59e0b',
    dataProperty: '#ec4899',
    objectProperty: '#06b6d4',
    annotation: '#8b5cf6',
    datatype: '#FFA500'
  };
  return colors[type] || '#6b7280';
}

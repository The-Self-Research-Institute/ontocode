import React, { useEffect, useRef, useState } from 'react';
import * as d3 from 'd3';
import { ChevronRight, ChevronDown, Box, Loader2 } from 'lucide-react';
import apiClient from '../services/apiClient';

interface TreeNode {
  id: string;
  label: string;
  iri: string;
  children?: TreeNode[];
  _children?: TreeNode[];
  depth?: number;
}

interface ClassHierarchyTreeProps {
  projectId: string;
  rootClassIRI?: string;
}

const ClassHierarchyTree: React.FC<ClassHierarchyTreeProps> = ({ 
  projectId, 
  rootClassIRI = 'http://www.w3.org/2002/07/owl#Thing' 
}) => {
  const svgRef = useRef<SVGSVGElement>(null);
  const [treeData, setTreeData] = useState<TreeNode | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  const loadHierarchy = async () => {
    setIsLoading(true);
    try {
      const response = await apiClient.get(
        `/api/ontology/${projectId}/visualization/class-hierarchy`,
        { classIRI: rootClassIRI, depth: 10 }
      );
      
      // Convert graph to tree structure
      const tree = convertGraphToTree(response);
      setTreeData(tree);
    } catch (error) {
      console.error('Failed to load hierarchy', error);
    } finally {
      setIsLoading(false);
    }
  };

  const convertGraphToTree = (graphData: any): TreeNode => {
    const nodeMap = new Map<string, TreeNode>();
    
    // Create all nodes
    graphData.nodes.forEach((node: any) => {
      nodeMap.set(node.id, {
        id: node.id,
        label: node.label,
        iri: node.iri,
        children: [],
      });
    });

    // Build tree structure from edges
    graphData.edges.forEach((edge: any) => {
      const parent = nodeMap.get(edge.target);
      const child = nodeMap.get(edge.source);
      if (parent && child && parent.children) {
        parent.children.push(child);
      }
    });

    // Find root (node with no parent)
    const roots = Array.from(nodeMap.values()).filter(node => 
      !graphData.edges.some((e: any) => e.source === node.id)
    );

    return roots[0] || nodeMap.values().next().value;
  };

  useEffect(() => {
    if (projectId) loadHierarchy();
  }, [projectId, rootClassIRI]);

  useEffect(() => {
    if (!treeData || !svgRef.current) return;

    const svg = d3.select(svgRef.current);
    const width = svgRef.current.clientWidth;
    const height = svgRef.current.clientHeight;

    svg.selectAll('*').remove();

    const g = svg.append('g').attr('transform', 'translate(40,20)');

    // Create tree layout
    const treeLayout = d3.tree<TreeNode>()
      .size([height - 40, width - 160]);

    const root = d3.hierarchy(treeData);
    treeLayout(root);

    // Draw links
    g.selectAll('.link')
      .data(root.links())
      .join('path')
      .attr('class', 'link')
      .attr('fill', 'none')
      .attr('stroke', '#999')
      .attr('stroke-width', 2)
      .attr('d', d3.linkHorizontal<any, any>()
        .x(d => d.y)
        .y(d => d.x)
      );

    // Draw nodes
    const nodes = g.selectAll('.node')
      .data(root.descendants())
      .join('g')
      .attr('class', 'node')
      .attr('transform', d => `translate(${d.y},${d.x})`)
      .style('cursor', 'pointer');

    nodes.append('circle')
      .attr('r', 6)
      .attr('fill', d => d.children ? '#4A90E2' : '#50C878')
      .attr('stroke', '#fff')
      .attr('stroke-width', 2);

    nodes.append('text')
      .attr('dy', '0.31em')
      .attr('x', d => d.children ? -10 : 10)
      .attr('text-anchor', d => d.children ? 'end' : 'start')
      .text(d => d.data.label)
      .attr('font-size', 12)
      .attr('fill', '#333')
      .clone(true).lower()
      .attr('stroke', 'white')
      .attr('stroke-width', 3);

    // Add tooltips
    nodes.append('title').text(d => d.data.iri);

  }, [treeData]);

  return (
    <div className="h-full flex flex-col bg-white">
      <header className="p-4 border-b border-gray-200 bg-gray-50">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-800 flex items-center gap-2">
              <Box size={28} className="text-indigo-600" />
              Class Hierarchy
            </h1>
            <p className="text-sm text-gray-600 mt-1">Tree view of class relationships</p>
          </div>
        </div>
      </header>

      <div className="flex-1 relative overflow-auto">
        {isLoading && (
          <div className="absolute inset-0 flex items-center justify-center bg-white bg-opacity-75 z-10">
            <div className="text-center">
              <Loader2 size={48} className="animate-spin text-indigo-600 mx-auto mb-4" />
              <p className="text-gray-600">Loading class hierarchy...</p>
            </div>
          </div>
        )}
        <svg ref={svgRef} className="w-full h-full" />
      </div>

      <div className="p-4 border-t border-gray-200 bg-gray-50">
        <div className="flex items-center gap-4 text-sm">
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-full bg-blue-500"></div>
            <span>Has Subclasses</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-full bg-green-500"></div>
            <span>Leaf Class</span>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ClassHierarchyTree;
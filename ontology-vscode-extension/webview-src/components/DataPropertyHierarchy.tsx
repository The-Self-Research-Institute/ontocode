import React, { useState, useCallback } from 'react';
import { ChevronRight, ChevronDown, Database } from 'lucide-react';

interface DataPropertyNode {
  id: string;
  iri: string;
  label: string;
  type: string;
  hasChildren?: boolean;
  children?: DataPropertyNode[];
  characteristics?: string[];
  domain?: string[];
  range?: string[];
}

interface DataPropertyHierarchyProps {
  properties: DataPropertyNode[];
  onSelectProperty: (property: DataPropertyNode) => void;
  selectedPropertyId?: string;
  fetchChildren: (propertyId: string) => Promise<DataPropertyNode[]>;
}

export const DataPropertyHierarchy: React.FC<DataPropertyHierarchyProps> = ({
  properties,
  onSelectProperty,
  selectedPropertyId,
  fetchChildren
}) => {
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [loadedChildren, setLoadedChildren] = useState<Map<string, DataPropertyNode[]>>(new Map());

  const toggleExpand = useCallback(async (nodeId: string, hasChildren: boolean) => {
    if (expandedNodes.includes(nodeId)) {
      setExpandedNodes(prev => prev.filter(id => id !== nodeId));
    } else {
      setExpandedNodes(prev => [...prev, nodeId]);
      
      // Fetch children if not already loaded
      if (hasChildren && !loadedChildren.has(nodeId)) {
        try {
          const children = await fetchChildren(nodeId);
          setLoadedChildren(prev => new Map(prev).set(nodeId, children));
        } catch (error) {
          console.error('Failed to fetch property children:', error);
        }
      }
    }
  }, [expandedNodes, loadedChildren, fetchChildren]);

  const renderPropertyNode = (node: DataPropertyNode, level: number = 0): React.ReactNode => {
    const isExpanded = expandedNodes.includes(node.id);
    const isSelected = selectedPropertyId === node.id;
    const children = loadedChildren.get(node.id) || node.children || [];
    const hasChildren = node.hasChildren || children.length > 0;

    return (
      <div key={node.id}>
        <div
          className={`flex items-center px-2 py-0.5 rounded cursor-pointer ${
            isSelected ? 'bg-blue-200' : 'hover:bg-slate-100'
          }`}
          style={{ paddingLeft: `${level * 16 + 4}px` }}
          onClick={() => onSelectProperty(node)}
        >
          {/* Expander Arrow */}
          <button 
            className="p-0.5 mr-1" 
            onClick={(e) => { 
              e.stopPropagation(); 
              if (hasChildren) toggleExpand(node.id, hasChildren);
            }}
            disabled={!hasChildren} 
          >
            {!hasChildren ? 
              <span className="w-5" /> : 
              (isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />)}
          </button>
          
          {/* Entity Icon */}
          <div 
            title="Data property" 
            className="w-3.5 h-3.5 rounded-sm border bg-green-400 border-green-600 mr-2 flex-shrink-0 flex items-center justify-center"
          >
            <Database size={10} className="text-white"/>
          </div>
          
          {/* Label */}
          <span className={`text-xs select-none text-black ${isSelected ? 'font-semibold' : ''}`}>
            {node.label || node.iri.split(/[/#]/).pop()}
          </span>
        </div>
        {isExpanded && children.map(child => renderPropertyNode(child, level + 1))}
      </div>
    );
  };

  return (
    <aside className="w-80 bg-white border-r border-gray-200 flex flex-col h-full">
      {/* Header */}
      <div className="text-xs font-semibold p-1.5 flex items-center justify-between border-b">
        <span>Data properties hierarchy</span>
      </div>
      
      {/* List View */}
      <div className="flex-1 overflow-y-auto p-1">
        {properties.length === 0 ? (
          <div className="p-4 text-center text-gray-400">No data properties found.</div>
        ) : (
          properties.map(prop => renderPropertyNode(prop))
        )}
      </div>
    </aside>
  );
};

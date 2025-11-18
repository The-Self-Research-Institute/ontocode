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
          className={`flex items-center gap-2 py-1 px-2 cursor-pointer border-b border-gray-100 ${
            isSelected 
              ? 'bg-blue-100' 
              : 'hover:bg-gray-50'
          }`}
          style={{ paddingLeft: `${level * 16 + 8}px` }}
          onClick={() => onSelectProperty(node)}
        >
          {/* Expander Arrow */}
          <button 
            className="w-4 h-4 flex items-center justify-center flex-shrink-0 hover:bg-gray-200 rounded" 
            onClick={(e) => { 
              e.stopPropagation(); 
              if (hasChildren) toggleExpand(node.id, hasChildren);
            }}
            disabled={!hasChildren} 
          >
            {hasChildren && (
              isExpanded ? <ChevronDown size={14} className="text-gray-600" /> : <ChevronRight size={14} className="text-gray-600" />
            )}
          </button>
          
          {/* Entity Icon */}
          <div 
            title="Data property" 
            className="w-4 h-4 rounded border bg-green-500 border-green-600 flex-shrink-0 flex items-center justify-center"
          >
            <Database size={10} className="text-white" strokeWidth={2} />
          </div>
          
          {/* Label */}
          <span className={`text-xs truncate text-gray-900 ${
            isSelected ? 'font-semibold' : 'font-normal'
          }`}>
            {node.label || node.iri.split(/[/#]/).pop()}
          </span>
        </div>
        {isExpanded && children.map(child => renderPropertyNode(child, level + 1))}
      </div>
    );
  };

  return (
    <aside className="w-80 bg-white border-r border-gray-300 flex flex-col h-full">
      {/* Header */}
      <div className="text-xs font-normal text-gray-700 px-2 py-2 border-b border-gray-300 bg-gradient-to-b from-gray-50 to-white">
        <span>Data properties hierarchy</span>
      </div>
      
      {/* List View */}
      <div className="flex-1 overflow-y-auto bg-white">
        {properties.length === 0 ? (
          <div className="p-4 text-center text-gray-400 text-xs">No data properties found.</div>
        ) : (
          properties.map(prop => renderPropertyNode(prop))
        )}
      </div>
    </aside>
  );
};

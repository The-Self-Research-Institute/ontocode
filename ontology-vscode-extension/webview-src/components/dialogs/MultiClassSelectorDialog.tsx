import React, { useState, useEffect, useCallback } from 'react';
import { X, Check, Package, ChevronRight, ChevronDown } from 'lucide-react';
import type { TreeNode } from '../../types';
import apiClient from '../../services/apiClient';

interface MultiClassSelectorDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (nodes: TreeNode[]) => void;
  classHierarchy: TreeNode[];
  projectId?: string;
  onToggleNode?: (nodeId: string) => Promise<void> | void;
  externalExpandedNodes?: string[];
  title?: string;
  excludeClassIds?: string[]; // Classes to exclude from selection (e.g., the current class)
  minSelection?: number; // Minimum number of classes required
  initialSelectedIds?: string[]; // Pre-selected class IRIs for edit mode
}

const MultiClassSelectorDialog: React.FC<MultiClassSelectorDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  classHierarchy,
  projectId,
  onToggleNode,
  externalExpandedNodes,
  title = "Select Classes",
  excludeClassIds = [],
  minSelection = 1,
  initialSelectedIds = []
}) => {
  const [selectedClasses, setSelectedClasses] = useState<TreeNode[]>([]);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [treeData, setTreeData] = useState<TreeNode[]>(classHierarchy);
  const [searchQuery, setSearchQuery] = useState('');
  const [hasInitialized, setHasInitialized] = useState(false);

  useEffect(() => {
    setTreeData(classHierarchy);
  }, [classHierarchy]);

  // Reset state when dialog opens and load initial selections
  useEffect(() => {
    if (isOpen && !hasInitialized) {
      setHasInitialized(true);
      setSearchQuery('');
      
      // Load initial selections if provided
      if (initialSelectedIds.length > 0) {
        const findNodesByIds = (nodes: TreeNode[], ids: string[]): TreeNode[] => {
          const result: TreeNode[] = [];
          const search = (nodeList: TreeNode[]) => {
            for (const node of nodeList) {
              if (ids.includes(node.id)) {
                result.push(node);
              }
              if (node.children && node.children.length > 0) {
                search(node.children);
              }
            }
          };
          search(nodes);
          return result;
        };
        
        const initialNodes = findNodesByIds(classHierarchy, initialSelectedIds);
        setSelectedClasses(initialNodes);
      } else {
        setSelectedClasses([]);
      }
    }
  }, [isOpen, hasInitialized, initialSelectedIds, classHierarchy]);

  // Reset hasInitialized when dialog closes
  useEffect(() => {
    if (!isOpen) {
      setHasInitialized(false);
    }
  }, [isOpen]);

  const loadChildren = useCallback(async (nodeId: string) => {
    if (!projectId) return;
    try {
      const response = await apiClient.get<any>(`/api/ontology/classes/children/${projectId}?parentIri=${encodeURIComponent(nodeId)}`);
      const children = Array.isArray(response) ? response :
        Array.isArray(response?.data) ? response.data :
        Array.isArray(response?.classes) ? response.classes : [];

      const updateTree = (nodes: TreeNode[]): TreeNode[] =>
        nodes.map((n: TreeNode) => {
          if (n.id === nodeId) {
            return {
              ...n,
              children: children.map((c: TreeNode & { hasChildren?: boolean }) => ({
                ...c,
                children: c.hasChildren ? undefined : c.children,
                hasChildren: c.hasChildren
              }))
            };
          }
          if (n.children) {
            return { ...n, children: updateTree(n.children) };
          }
          return n;
        });

      setTreeData(prev => updateTree(prev));
    } catch (error) {
      console.error(`Failed to load children for ${nodeId}`, error);
    }
  }, [projectId]);

  const handleToggleNode = async (nodeId: string) => {
    const currentExpanded = externalExpandedNodes || expandedNodes;
    const isExpanded = currentExpanded.includes(nodeId);
    if (isExpanded) {
      setExpandedNodes(prev => prev.filter(id => id !== nodeId));
    } else {
      setExpandedNodes(prev => [...prev, nodeId]);
      if (onToggleNode) {
        await onToggleNode(nodeId);
      } else {
        await loadChildren(nodeId);
      }
    }
  };

  const handleNodeSelect = (node: TreeNode) => {
    // Don't allow selecting excluded classes
    if (excludeClassIds.includes(node.id)) return;
    
    if (selectedClasses.find(n => n.id === node.id)) {
      setSelectedClasses(prev => prev.filter(n => n.id !== node.id));
    } else {
      setSelectedClasses(prev => [...prev, node]);
    }
  };

  const handleConfirm = () => {
    if (selectedClasses.length < minSelection) {
      alert(`Please select at least ${minSelection} class${minSelection > 1 ? 'es' : ''}.`);
      return;
    }
    onConfirm(selectedClasses);
    setSelectedClasses([]);
    onClose();
  };

  const isSelected = (nodeId: string) => selectedClasses.some(n => n.id === nodeId);
  const isExcluded = (nodeId: string) => excludeClassIds.includes(nodeId);

  // Render a tree node with checkbox for multi-select
  const renderTreeNode = (node: TreeNode, level: number = 0): React.ReactNode => {
    const currentExpanded = externalExpandedNodes || expandedNodes;
    const isExpanded = currentExpanded.includes(node.id);
    const hasChildren = node.hasChildren || (node.children && node.children.length > 0);
    const selected = isSelected(node.id);
    const excluded = isExcluded(node.id);
    
    // Filter by search
    if (searchQuery && !node.label.toLowerCase().includes(searchQuery.toLowerCase())) {
      // Check if any children match
      const hasMatchingChildren = node.children?.some(child => 
        child.label.toLowerCase().includes(searchQuery.toLowerCase())
      );
      if (!hasMatchingChildren) return null;
    }

    return (
      <div key={node.id}>
        <div
          className={`flex items-center px-2 py-1 ${excluded ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer hover:bg-gray-100'} ${selected ? 'bg-purple-100' : ''}`}
          style={{ paddingLeft: `${level * 16 + 8}px` }}
        >
          {/* Expand/Collapse button */}
          <button
            className="p-0.5 mr-1"
            onClick={(e) => {
              e.stopPropagation();
              if (hasChildren) handleToggleNode(node.id);
            }}
            disabled={!hasChildren}
          >
            {!hasChildren ? (
              <span className="w-4" />
            ) : isExpanded ? (
              <ChevronDown size={14} />
            ) : (
              <ChevronRight size={14} />
            )}
          </button>

          {/* Checkbox */}
          <div
            onClick={() => !excluded && handleNodeSelect(node)}
            className={`w-4 h-4 rounded border mr-2 flex items-center justify-center ${
              excluded ? 'bg-gray-200 border-gray-300 cursor-not-allowed' :
              selected ? 'bg-purple-600 border-purple-600 cursor-pointer' : 
              'border-gray-300 hover:border-purple-400 cursor-pointer'
            }`}
            title={excluded ? 'Current class (cannot select)' : undefined}
          >
            {selected && !excluded && <Check size={12} className="text-white" />}
          </div>

          {/* Icon */}
          <div className={`w-4 h-4 rounded ${excluded ? 'bg-gray-400 border-gray-500' : 'bg-amber-400 border-amber-600'} border mr-2 flex items-center justify-center`}>
            <Package size={10} className="text-white" />
          </div>

          {/* Label */}
          <span
            className={`text-sm ${excluded ? 'text-gray-400 italic' : selected ? 'font-semibold text-purple-900' : 'text-gray-800'}`}
            onClick={() => !excluded && handleNodeSelect(node)}
          >
            {node.label}{excluded && ' (current)'}
          </span>
        </div>

        {/* Children */}
        {isExpanded && node.children?.map(child => renderTreeNode(child, level + 1))}
      </div>
    );
  };

  // Early return AFTER all hooks to comply with React Rules of Hooks
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={onClose}>
      <div className="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4 flex flex-col max-h-[80vh]" onClick={e => e.stopPropagation()}>
        <div className="p-4 border-b flex justify-between items-center bg-gray-50">
          <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
            <X size={20} />
          </button>
        </div>
        
        <div className="p-4 flex-1 overflow-hidden flex flex-col min-h-0">
          {/* Selected classes display */}
          <div className="mb-3">
            <div className="text-xs font-medium text-gray-500 uppercase mb-1">Selected Classes ({selectedClasses.length})</div>
            {selectedClasses.length > 0 ? (
              <div className="flex flex-wrap gap-1 max-h-20 overflow-y-auto">
                {selectedClasses.map(c => (
                  <span 
                    key={c.id}
                    className="inline-flex items-center gap-1 px-2 py-1 bg-purple-100 text-purple-800 rounded-full text-xs cursor-pointer hover:bg-purple-200"
                    onClick={() => handleNodeSelect(c)}
                  >
                    {c.label}
                    <X size={12} />
                  </span>
                ))}
              </div>
            ) : (
              <div className="text-sm text-gray-400 italic">Click classes below to select them</div>
            )}
          </div>

          {/* Search */}
          <input
            type="text"
            placeholder="Search classes..."
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-purple-500 mb-3"
          />

          {/* Class tree with checkboxes */}
          <div className="flex-1 border rounded overflow-y-auto min-h-0 bg-white">
            {treeData.length > 0 ? (
              treeData.map(node => renderTreeNode(node))
            ) : (
              <div className="p-4 text-center text-gray-400 text-sm">
                No classes available. Make sure the class hierarchy is loaded.
              </div>
            )}
          </div>
        </div>

        <div className="p-4 border-t bg-gray-50 flex justify-between items-center">
          <div className="text-xs text-gray-500">
            {minSelection > 1 
              ? `Select at least ${minSelection} classes` 
              : 'Select one or more classes'}
          </div>
          <div className="flex gap-2">
            <button onClick={onClose} className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-50">
              Cancel
            </button>
            <button 
              onClick={handleConfirm} 
              disabled={selectedClasses.length < minSelection}
              className="px-4 py-2 text-sm font-medium text-white bg-purple-600 rounded hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Add ({selectedClasses.length})
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default MultiClassSelectorDialog;

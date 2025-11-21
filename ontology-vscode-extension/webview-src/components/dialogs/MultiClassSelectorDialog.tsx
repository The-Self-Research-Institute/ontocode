import React, { useState, useEffect, useCallback } from 'react';
import { X, Check } from 'lucide-react';
import EntityHierarchy from '../EntityHierarchy';
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
}

const MultiClassSelectorDialog: React.FC<MultiClassSelectorDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  classHierarchy,
  projectId,
  onToggleNode,
  externalExpandedNodes,
  title = "Select Classes"
}) => {
  const [selectedClasses, setSelectedClasses] = useState<TreeNode[]>([]);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [treeData, setTreeData] = useState<TreeNode[]>(classHierarchy);

  useEffect(() => {
    setTreeData(classHierarchy);
  }, [classHierarchy]);

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
    const isExpanded = (externalExpandedNodes || expandedNodes).includes(nodeId);
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
    if (selectedClasses.find(n => n.id === node.id)) {
      setSelectedClasses(prev => prev.filter(n => n.id !== node.id));
    } else {
      setSelectedClasses(prev => [...prev, node]);
    }
  };

  const handleConfirm = () => {
    onConfirm(selectedClasses);
    setSelectedClasses([]);
    onClose();
  };

  // Early return AFTER all hooks to comply with React Rules of Hooks
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4 flex flex-col max-h-[80vh]">
        <div className="p-4 border-b flex justify-between items-center">
          <h3 className="text-lg font-semibold">{title}</h3>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-700">
            <X size={20} />
          </button>
        </div>
        
        <div className="p-4 flex-1 overflow-y-auto">
          <div className="mb-2 text-sm text-gray-600">
            Selected: {selectedClasses.map(c => c.label).join(", ") || "None"}
          </div>
          <div className="border rounded h-64 overflow-y-auto">
             {/* We reuse EntityHierarchy but we need to handle multi-select visually. 
                 EntityHierarchy might not support multi-select props. 
                 For now, let's assume we can just click to toggle. 
                 But EntityHierarchy usually has onSelect which takes one node.
             */}
             <EntityHierarchy 
               entitiesTab="Classes"
               filteredData={treeData}
               selectedItem={selectedClasses.length > 0 ? selectedClasses[selectedClasses.length - 1] : null}
               expandedNodes={externalExpandedNodes || expandedNodes}
               searchQuery=""
               onSearchQueryChange={() => {}}
               onSelectItem={(item) => handleNodeSelect(item as TreeNode)}
               onToggleNode={handleToggleNode}
               onAddItem={() => {}}
               onDeleteItem={() => {}}
             />
          </div>
        </div>

        <div className="p-4 border-t flex justify-end gap-2">
          <button onClick={onClose} className="px-4 py-2 border rounded hover:bg-gray-50">Cancel</button>
          <button 
            onClick={handleConfirm} 
            disabled={selectedClasses.length < 2}
            className="px-4 py-2 bg-purple-600 text-white rounded hover:bg-purple-700 disabled:opacity-50"
          >
            Confirm ({selectedClasses.length})
          </button>
        </div>
      </div>
    </div>
  );
};

export default MultiClassSelectorDialog;

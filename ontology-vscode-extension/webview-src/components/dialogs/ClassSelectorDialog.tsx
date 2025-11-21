import React, { useState, useEffect, useCallback } from 'react';
import { X } from 'lucide-react';
import EntityHierarchy from '../EntityHierarchy';
import type { TreeNode } from '../../types';
import apiClient from '../../services/apiClient';

interface ClassSelectorDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSelect: (node: TreeNode) => void;
  classHierarchy: TreeNode[];
  projectId?: string;
  onToggleNode?: (nodeId: string) => Promise<void> | void;
  externalExpandedNodes?: string[];
  title?: string;
}

const ClassSelectorDialog: React.FC<ClassSelectorDialogProps> = ({
  isOpen,
  onClose,
  onSelect,
  classHierarchy,
  projectId,
  onToggleNode,
  externalExpandedNodes,
  title = "Select Class"
}) => {
  const [selectedClass, setSelectedClass] = useState<TreeNode | null>(null);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [treeData, setTreeData] = useState<TreeNode[]>(classHierarchy);

  useEffect(() => {
    setTreeData(classHierarchy);
  }, [classHierarchy]);

  if (!isOpen) return null;

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

  const handleSelect = () => {
    if (selectedClass) {
      onSelect(selectedClass);
      setSelectedClass(null);
      setExpandedNodes([]);
    }
  };

  const handleClose = () => {
    setSelectedClass(null);
    setExpandedNodes([]);
    onClose();
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={handleClose}>
      <div className="bg-white rounded-lg shadow-xl max-w-5xl w-full mx-4 flex flex-col h-[85vh]" onClick={e => e.stopPropagation()}>
        <div className="px-4 py-3 border-b bg-gray-50 flex justify-between items-center">
          <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
          <button onClick={handleClose} className="text-gray-400 hover:text-gray-600">
            <X size={20} />
          </button>
        </div>
        
        <div className="flex-1 overflow-y-auto">
          <div className="h-full">
            <EntityHierarchy
              entitiesTab="Classes"
              filteredData={treeData}
              selectedItem={selectedClass}
              expandedNodes={externalExpandedNodes || expandedNodes}
              searchQuery=""
              onSearchQueryChange={() => {}}
              onSelectItem={(item) => setSelectedClass(item as TreeNode)}
              onToggleNode={handleToggleNode}
              onAddItem={() => {}}
              onDeleteItem={() => {}}
            />
          </div>
        </div>

        <div className="px-4 py-3 border-t bg-gray-50 flex justify-end gap-2">
          <button 
            onClick={handleClose} 
            className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-50"
          >
            Cancel
          </button>
          <button 
            onClick={handleSelect} 
            disabled={!selectedClass}
            className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            OK
          </button>
        </div>
      </div>
    </div>
  );
};

export default ClassSelectorDialog;

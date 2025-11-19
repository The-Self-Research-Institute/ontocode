import React, { useState } from 'react';
import { X, Search } from 'lucide-react';
import EntityHierarchy from '../EntityHierarchy';
import type { TreeNode } from '../../types';

interface ClassSelectorDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSelect: (node: TreeNode) => void;
  classHierarchy: TreeNode[];
  title?: string;
}

const ClassSelectorDialog: React.FC<ClassSelectorDialogProps> = ({
  isOpen,
  onClose,
  onSelect,
  classHierarchy,
  title = "Select Class"
}) => {
  const [selectedClass, setSelectedClass] = useState<TreeNode | null>(null);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);

  if (!isOpen) return null;

  const toggleNode = (nodeId: string) => {
    if (expandedNodes.includes(nodeId)) {
      setExpandedNodes(prev => prev.filter(id => id !== nodeId));
    } else {
      setExpandedNodes(prev => [...prev, nodeId]);
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
      <div className="bg-white rounded-lg shadow-xl max-w-5xl w-full mx-4 flex flex-col max-h-[85vh]" onClick={e => e.stopPropagation()}>
        <div className="px-4 py-3 border-b bg-gray-50 flex justify-between items-center">
          <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
          <button onClick={handleClose} className="text-gray-400 hover:text-gray-600">
            <X size={20} />
          </button>
        </div>
        
        <div className="flex-1 overflow-y-auto min-h-0">
          <div className="h-full">
            <EntityHierarchy
              entitiesTab="Classes"
              filteredData={classHierarchy}
              selectedItem={selectedClass}
              expandedNodes={expandedNodes}
              searchQuery=""
              onSearchQueryChange={() => {}}
              onSelectItem={(item) => setSelectedClass(item as TreeNode)}
              onToggleNode={toggleNode}
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

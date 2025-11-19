import React, { useState } from 'react';
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
      <div className="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4 flex flex-col max-h-[70vh]" onClick={e => e.stopPropagation()}>
        <div className="p-4 border-b">
          <h3 className="text-lg font-semibold text-black">{title}</h3>
        </div>
        
        <div className="flex-1 overflow-y-auto p-4 min-h-0">
          <div className="border rounded">
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

        <div className="p-4 flex justify-end gap-3 border-t">
          <button 
            onClick={handleClose} 
            className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
          >
            Cancel
          </button>
          <button 
            onClick={handleSelect} 
            disabled={!selectedClass}
            className="px-4 py-2 text-sm bg-blue-600 text-white rounded-md hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            OK
          </button>
        </div>
      </div>
    </div>
  );
};

export default ClassSelectorDialog;

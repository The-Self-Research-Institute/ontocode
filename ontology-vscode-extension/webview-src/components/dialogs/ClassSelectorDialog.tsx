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
  onAddClass?: (type: 'subclass' | 'sibling', parentId?: string, name?: string) => Promise<void>;
  onDeleteClass?: () => void;
  metadata?: { ontologyIRI?: string };
}

const ClassSelectorDialog: React.FC<ClassSelectorDialogProps> = ({
  isOpen,
  onClose,
  onSelect,
  classHierarchy,
  projectId,
  onToggleNode,
  externalExpandedNodes,
  title = "Select Class",
  onAddClass,
  onDeleteClass,
  metadata
}) => {
  const [selectedClass, setSelectedClass] = useState<TreeNode | null>(null);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [treeData, setTreeData] = useState<TreeNode[]>(classHierarchy);
  
  // Inline class creation state
  const [showInlineCreate, setShowInlineCreate] = useState(false);
  const [inlineCreateType, setInlineCreateType] = useState<'subclass' | 'sibling'>('subclass');
  const [inlineClassName, setInlineClassName] = useState('');
  const [isCreatingClass, setIsCreatingClass] = useState(false);

  useEffect(() => {
    console.log('[ClassSelectorDialog] Class hierarchy updated, nodes:', classHierarchy.length);
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

  if (!isOpen) return null;

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
    setShowInlineCreate(false);
    setInlineClassName('');
    onClose();
  };

  // Handle inline class creation
  const handleInlineAddClass = (type: 'subclass' | 'sibling' | 'individual') => {
    // EntityHierarchy can pass 'individual' but we only handle 'subclass' and 'sibling' for classes
    if (type === 'individual') return;
    
    setInlineCreateType(type);
    setInlineClassName('');
    setShowInlineCreate(true);
  };

  // Submit inline class creation
  const handleInlineCreateSubmit = async () => {
    if (!inlineClassName.trim() || !onAddClass) return;
    
    console.log('[ClassSelectorDialog] Creating class:', inlineClassName);
    setIsCreatingClass(true);
    try {
      const parentId = selectedClass?.id;
      const type: 'subclass' | 'sibling' = selectedClass ? 'subclass' : 'sibling';
      
      console.log('[ClassSelectorDialog] Parent:', parentId, 'Type:', type);
      
      // Expand parent node to show new class
      if (parentId && !expandedNodes.includes(parentId)) {
        console.log('[ClassSelectorDialog] Expanding parent node:', parentId);
        setExpandedNodes(prev => [...prev, parentId]);
        if (onToggleNode) {
          await onToggleNode(parentId);
        }
      }
      
      // Also expand the top class node if creating at root
      const topNodeId = 'http://www.w3.org/2002/07/owl#Thing';
      if (!selectedClass && !expandedNodes.includes(topNodeId)) {
        console.log('[ClassSelectorDialog] Expanding top node:', topNodeId);
        setExpandedNodes(prev => [...prev, topNodeId]);
        if (onToggleNode) {
          await onToggleNode(topNodeId);
        }
      }
      
      // Call the class creation handler
      console.log('[ClassSelectorDialog] Calling handler...');
      await onAddClass(type, parentId, inlineClassName.trim());
      console.log('[ClassSelectorDialog] Handler completed');
      
      // Reset form
      setShowInlineCreate(false);
      setInlineClassName('');
    } catch (error) {
      console.error('[ClassSelectorDialog] Failed to create class:', error);
      // Don't close the form on error so user can try again
      setShowInlineCreate(true);
    } finally {
      setIsCreatingClass(false);
    }
  };

  // Cancel inline creation
  const handleInlineCreateCancel = () => {
    setShowInlineCreate(false);
    setInlineClassName('');
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) handleClose();
      }}
    >
      <div className="bg-white rounded-lg shadow-xl max-w-5xl w-full mx-4 flex flex-col h-[85vh]" onClick={e => e.stopPropagation()}>
        <div className="px-4 py-3 border-b bg-gray-50 flex justify-between items-center">
          <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
          <button onClick={handleClose} className="text-gray-400 hover:text-gray-600">
            <X size={20} />
          </button>
        </div>
        
        <div className="flex-1 overflow-y-auto">
          <div className="h-full flex flex-col">
            {/* Inline Create Form */}
            {showInlineCreate && (
              <div className="px-3 py-2 bg-amber-50 border-b border-amber-200">
                <div className="flex items-center gap-2">
                  <span className="text-xs text-amber-800 font-medium">
                    New {inlineCreateType === 'subclass' ? 'subclass of' : 'sibling of'} {selectedClass?.label || 'owl:Thing'}:
                  </span>
                  <input
                    type="text"
                    value={inlineClassName}
                    onChange={(e) => setInlineClassName(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && inlineClassName.trim()) {
                        handleInlineCreateSubmit();
                      } else if (e.key === 'Escape') {
                        handleInlineCreateCancel();
                      }
                    }}
                    placeholder="Enter class name..."
                    className="flex-1 px-2 py-1 text-sm border border-amber-300 rounded focus:outline-none focus:ring-1 focus:ring-amber-500 bg-white"
                    autoFocus
                    disabled={isCreatingClass}
                  />
                  <button
                    onClick={handleInlineCreateSubmit}
                    disabled={!inlineClassName.trim() || isCreatingClass}
                    className="px-3 py-1 text-xs bg-amber-600 text-white rounded hover:bg-amber-700 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {isCreatingClass ? 'Creating...' : 'Create'}
                  </button>
                  <button
                    onClick={handleInlineCreateCancel}
                    disabled={isCreatingClass}
                    className="px-2 py-1 text-xs text-gray-600 hover:text-gray-800"
                  >
                    Cancel
                  </button>
                </div>
              </div>
            )}
            
            <div className="flex-1 overflow-hidden">
              <EntityHierarchy
                entitiesTab="Classes"
                filteredData={treeData}
                selectedItem={selectedClass}
                expandedNodes={externalExpandedNodes || expandedNodes}
                searchQuery={searchQuery}
                onSearchQueryChange={setSearchQuery}
                onSelectItem={(item) => setSelectedClass(item as TreeNode)}
                onToggleNode={handleToggleNode}
                onAddItem={projectId && onAddClass ? handleInlineAddClass : undefined}
                onDeleteItem={projectId && onDeleteClass ? onDeleteClass : undefined}
                hideToolbarActions={!projectId || !onAddClass}
              />
            </div>
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

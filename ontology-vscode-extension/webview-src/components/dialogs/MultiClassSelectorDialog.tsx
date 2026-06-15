import React, { useState, useEffect, useCallback } from 'react';
import { X, Check, Package, ChevronRight, ChevronDown } from 'lucide-react';
import type { TreeNode } from '../../types';
import apiClient from '../../services/apiClient';
import EntityHierarchy from '../EntityHierarchy';
import { notificationService } from '../../services/notificationService';

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
  onAddClass?: (type: 'subclass' | 'sibling', parentId?: string, name?: string) => Promise<void>;
  onDeleteClass?: () => void;
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
  initialSelectedIds = [],
  onAddClass,
  onDeleteClass
}) => {
  const [selectedClasses, setSelectedClasses] = useState<TreeNode[]>([]);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [treeData, setTreeData] = useState<TreeNode[]>(classHierarchy);
  const [searchQuery, setSearchQuery] = useState('');
  const [hasInitialized, setHasInitialized] = useState(false);
  const [selectedNode, setSelectedNode] = useState<TreeNode | null>(null);
  
  // Inline class creation state
  const [showInlineClassCreate, setShowInlineClassCreate] = useState(false);
  const [inlineCreateType, setInlineCreateType] = useState<'subclass' | 'sibling'>('subclass');
  const [inlineClassName, setInlineClassName] = useState('');
  const [isCreatingClass, setIsCreatingClass] = useState(false);

  useEffect(() => {
    console.log('[MultiClassSelectorDialog] Class hierarchy updated, nodes:', classHierarchy.length);
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
    
    // Set selected node for inline creation
    setSelectedNode(node);
    
    // Toggle selection for multi-select
    if (selectedClasses.find(n => n.id === node.id)) {
      setSelectedClasses(prev => prev.filter(n => n.id !== node.id));
    } else {
      setSelectedClasses(prev => [...prev, node]);
    }
  };

  // Handle checkbox toggle for multi-select
  const handleToggleClass = (classId: string) => {
    if (excludeClassIds.includes(classId)) return;
    
    const node = findNodeById(treeData, classId);
    if (!node) return;
    
    if (selectedClasses.find(n => n.id === classId)) {
      setSelectedClasses(prev => prev.filter(n => n.id !== classId));
    } else {
      setSelectedClasses(prev => [...prev, node]);
    }
  };

  // Helper to find node by ID
  const findNodeById = (nodes: TreeNode[], id: string): TreeNode | null => {
    for (const node of nodes) {
      if (node.id === id) return node;
      if (node.children) {
        const found = findNodeById(node.children, id);
        if (found) return found;
      }
    }
    return null;
  };

  // Get selected class IDs for EntityHierarchy
  const selectedClassIds = selectedClasses.map(c => c.id);

  // Use external or local expanded nodes
  const effectiveExpandedNodes = externalExpandedNodes || expandedNodes;

  // Inline class creation handlers
  const handleInlineAddClass = (type: 'subclass' | 'sibling' | 'individual') => {
    console.log('[MultiClassSelectorDialog] handleInlineAddClass called with type:', type);
    console.log('[MultiClassSelectorDialog] onAddClass exists:', !!onAddClass);
    console.log('[MultiClassSelectorDialog] selectedNode:', selectedNode?.label);
    
    // EntityHierarchy can pass 'individual' but we only handle 'subclass' and 'sibling' for classes
    if (type === 'individual') {
      console.log('[MultiClassSelectorDialog] Ignoring individual type');
      return;
    }
    
    setInlineCreateType(type);
    setShowInlineClassCreate(true);
    setInlineClassName('');
    console.log('[MultiClassSelectorDialog] Inline form should now be visible');
  };

  const handleInlineClassCreateSubmit = async () => {
    if (!inlineClassName.trim() || !onAddClass) return;
    
    console.log('[MultiClassSelectorDialog] Creating class:', inlineClassName);
    setIsCreatingClass(true);
    try {
      const parentId = selectedNode?.id;
      const type = selectedNode ? 'subclass' : 'sibling';
      
      console.log('[MultiClassSelectorDialog] Parent:', parentId, 'Type:', type);
      
      // Expand parent node to show new class
      if (parentId && !effectiveExpandedNodes.includes(parentId)) {
        console.log('[MultiClassSelectorDialog] Expanding parent node:', parentId);
        await handleToggleNode(parentId);
      }
      
      // Also expand the top class node if creating at root
      const topNodeId = 'http://www.w3.org/2002/07/owl#Thing';
      if (!selectedNode && !effectiveExpandedNodes.includes(topNodeId)) {
        console.log('[MultiClassSelectorDialog] Expanding top node:', topNodeId);
        await handleToggleNode(topNodeId);
      }
      
      // Call the class creation handler
      console.log('[MultiClassSelectorDialog] Calling handler...');
      await onAddClass(type, parentId, inlineClassName.trim());
      console.log('[MultiClassSelectorDialog] Handler completed');
      
      // Reset form
      setShowInlineClassCreate(false);
      setInlineClassName('');
    } catch (error) {
      console.error('[MultiClassSelectorDialog] Failed to create class:', error);
      // Don't close the form on error so user can try again
      setShowInlineClassCreate(true);
    } finally {
      setIsCreatingClass(false);
    }
  };

  const handleInlineClassCreateCancel = () => {
    setShowInlineClassCreate(false);
    setInlineClassName('');
  };

  const handleConfirm = () => {
    if (selectedClasses.length < minSelection) {
      notificationService.warning('Selection Required', `Please select at least ${minSelection} class${minSelection > 1 ? 'es' : ''}.`);
      return;
    }
    onConfirm(selectedClasses);
    setSelectedClasses([]);
    onClose();
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

          {/* Inline Class Creation Form */}
          {showInlineClassCreate && (
            <div className="p-3 bg-amber-50 border border-amber-200 rounded mb-3">{!onAddClass && (
                <div className="mb-2 p-2 bg-red-50 border border-red-200 rounded">
                  <p className="text-xs text-red-600">Cannot create class: handler not available</p>
                </div>
              )}
              <p className="text-xs text-amber-800 font-medium mb-2">
                New {inlineCreateType === 'subclass' ? 'subclass of' : 'sibling of'} {selectedNode?.label || 'owl:Thing'}:
              </p>
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  value={inlineClassName}
                  onChange={(e) => setInlineClassName(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && inlineClassName.trim()) {
                      handleInlineClassCreateSubmit();
                    } else if (e.key === 'Escape') {
                      handleInlineClassCreateCancel();
                    }
                  }}
                  placeholder="Enter class name..."
                  className="flex-1 px-2 py-1 text-sm border border-amber-300 rounded focus:outline-none focus:ring-2 focus:ring-amber-500"
                  autoFocus
                />
                <button
                  onClick={handleInlineClassCreateSubmit}
                  disabled={!inlineClassName.trim() || isCreatingClass}
                  className="px-3 py-1 text-xs font-semibold text-white bg-amber-600 rounded hover:bg-amber-700 disabled:bg-gray-300 disabled:cursor-not-allowed"
                >
                  {isCreatingClass ? 'Creating...' : 'Create'}
                </button>
                <button
                  onClick={handleInlineClassCreateCancel}
                  className="px-3 py-1 text-xs font-semibold text-amber-800 bg-white border border-amber-300 rounded hover:bg-amber-100"
                >
                  Cancel
                </button>
              </div>
              <p className="mt-1 text-xs text-amber-700">Press Enter to create, Escape to cancel</p>
            </div>
          )}

          {/* Class tree with EntityHierarchy component */}
          <div className="flex-1 overflow-hidden flex flex-col min-h-0">
            <EntityHierarchy
              entitiesTab="Classes"
              filteredData={treeData}
              selectedItem={selectedNode}
              expandedNodes={effectiveExpandedNodes}
              searchQuery={searchQuery}
              onSearchQueryChange={setSearchQuery}
              onSelectItem={handleNodeSelect}
              onToggleNode={handleToggleNode}
              onAddItem={handleInlineAddClass}
              onDeleteItem={onDeleteClass || (() => {})}
              hideToolbarActions={!onAddClass && !onDeleteClass}
              selectedProperties={selectedClassIds}
              multiSelectMode={true}
              excludeIds={excludeClassIds}
            />
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

import React, { useState, useEffect, useMemo } from 'react';
import { X, Search, ChevronDown, ChevronRight, Plus, Trash2, RefreshCw, Folder, FolderOpen, Settings } from 'lucide-react';
import type { TreeNode, Property } from '../../types';

interface ObjectPropertyExpressionDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (expression: string, isInverse: boolean) => void;
  objectPropertyHierarchy: TreeNode[];
  title?: string;
  // For add/delete functionality
  projectId?: string;
  onAddSubProperty?: (parentId: string, name: string) => Promise<void>;
  onAddSiblingProperty?: (siblingId: string | undefined, name: string) => Promise<void>;
  onDeleteProperty?: (propertyId: string) => Promise<void>;
  onRefresh?: () => void;
  // Pre-selected property for edit mode
  initialSelectedId?: string;
  initialIsInverse?: boolean;
  showInverseOption?: boolean;
  propertyType?: 'object' | 'data';
}

type ViewMode = 'Asserted' | 'Inferred';

const ObjectPropertyExpressionDialog: React.FC<ObjectPropertyExpressionDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  objectPropertyHierarchy,
  title = "'bearer of'",
  projectId,
  onAddSubProperty,
  onAddSiblingProperty,
  onDeleteProperty,
  onRefresh,
  initialSelectedId,
  initialIsInverse = false,
  showInverseOption = true,
  propertyType = 'object'
}) => {
  // Color scheme based on property type
  const isDataProperty = propertyType === 'data';
  const themeColors = {
    primary: isDataProperty ? 'bg-green-500' : 'bg-blue-500',
    primaryLight: isDataProperty ? 'bg-green-300' : 'bg-blue-300',
    primaryDark: isDataProperty ? 'bg-green-600' : 'bg-blue-600',
    selected: isDataProperty ? 'bg-green-600' : 'bg-blue-600',
    selectedHover: isDataProperty ? 'hover:bg-green-500' : 'hover:bg-blue-500',
    text: isDataProperty ? 'text-green-600' : 'text-blue-600',
    button: isDataProperty ? 'bg-green-600 hover:bg-green-700' : 'bg-blue-600 hover:bg-blue-700',
    focusRing: isDataProperty ? 'focus:ring-green-500' : 'focus:ring-blue-500',
    checkbox: isDataProperty ? 'text-green-600' : 'text-blue-600'
  };

  const [selectedProperty, setSelectedProperty] = useState<TreeNode | null>(null);
  const [isInverseProperty, setIsInverseProperty] = useState(false);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [viewMode, setViewMode] = useState<ViewMode>('Asserted');
  
  // Add property state
  const [showAddInput, setShowAddInput] = useState(false);
  const [addMode, setAddMode] = useState<'subclass' | 'sibling'>('subclass');
  const [newPropertyName, setNewPropertyName] = useState('');
  const [isCreating, setIsCreating] = useState(false);

  // Initialize state when dialog opens
  useEffect(() => {
    if (isOpen) {
      setIsInverseProperty(initialIsInverse);
      setSelectedProperty(null);
      setShowAddInput(false);
      setNewPropertyName('');
      
      // If initial selection provided, find and select it
      if (initialSelectedId) {
        const findNode = (nodes: TreeNode[]): TreeNode | null => {
          for (const node of nodes) {
            if (node.id === initialSelectedId) return node;
            if (node.children) {
              const found = findNode(node.children);
              if (found) return found;
            }
          }
          return null;
        };
        const found = findNode(objectPropertyHierarchy);
        if (found) {
          setSelectedProperty(found);
          // Expand parent nodes to show selection
          expandToNode(initialSelectedId);
        }
      }
      
      // Auto-expand top-level node
      if (objectPropertyHierarchy.length > 0) {
        const topNode = objectPropertyHierarchy[0];
        if (!expandedNodes.includes(topNode.id)) {
          setExpandedNodes(prev => [...prev, topNode.id]);
        }
      }
    }
  }, [isOpen, initialSelectedId, initialIsInverse, objectPropertyHierarchy]);

  // Expand all parent nodes to show a specific node
  const expandToNode = (nodeId: string) => {
    const findPath = (nodes: TreeNode[], path: string[] = []): string[] | null => {
      for (const node of nodes) {
        if (node.id === nodeId) return path;
        if (node.children) {
          const found = findPath(node.children, [...path, node.id]);
          if (found) return found;
        }
      }
      return null;
    };
    const path = findPath(objectPropertyHierarchy);
    if (path) {
      setExpandedNodes(prev => [...new Set([...prev, ...path])]);
    }
  };

  if (!isOpen) return null;

  const handleToggleNode = (nodeId: string) => {
    setExpandedNodes(prev => 
      prev.includes(nodeId) 
        ? prev.filter(id => id !== nodeId) 
        : [...prev, nodeId]
    );
  };

  const handleSelectProperty = (node: TreeNode) => {
    setSelectedProperty(node);
  };

  const handleDoubleClick = (node: TreeNode) => {
    setSelectedProperty(node);
    handleConfirm();
  };

  const handleAddSubProperty = () => {
    setAddMode('subclass');
    setShowAddInput(true);
    setNewPropertyName('');
  };

  const handleAddSiblingProperty = () => {
    setAddMode('sibling');
    setShowAddInput(true);
    setNewPropertyName('');
  };

  const handleCreateProperty = async () => {
    if (!newPropertyName.trim()) return;
    
    setIsCreating(true);
    try {
      if (addMode === 'subclass' && onAddSubProperty && selectedProperty) {
        await onAddSubProperty(selectedProperty.id, newPropertyName.trim());
        // Expand parent to show new child
        if (!expandedNodes.includes(selectedProperty.id)) {
          setExpandedNodes(prev => [...prev, selectedProperty.id]);
        }
      } else if (addMode === 'sibling' && onAddSiblingProperty) {
        await onAddSiblingProperty(selectedProperty?.id, newPropertyName.trim());
      }
      
      setShowAddInput(false);
      setNewPropertyName('');
      
      if (onRefresh) {
        onRefresh();
      }
    } catch (error) {
      console.error('Failed to create property:', error);
    } finally {
      setIsCreating(false);
    }
  };

  const handleDeleteProperty = async () => {
    if (!selectedProperty || !onDeleteProperty) return;
    
    try {
      await onDeleteProperty(selectedProperty.id);
      setSelectedProperty(null);
      if (onRefresh) {
        onRefresh();
      }
    } catch (error) {
      console.error('Failed to delete property:', error);
    }
  };

  const handleConfirm = () => {
    if (selectedProperty) {
      onConfirm(selectedProperty.id, isInverseProperty);
      handleClose();
    }
  };

  const handleClose = () => {
    setSelectedProperty(null);
    setIsInverseProperty(false);
    setShowAddInput(false);
    setNewPropertyName('');
    onClose();
  };

  // Get display label for a property (with inverse notation if needed)
  const getDisplayLabel = (node: TreeNode): string => {
    return node.label || node.id.split('#').pop() || node.id;
  };

  // Recursive tree renderer
  const renderPropertyTree = (nodes: TreeNode[], level: number = 0): React.ReactNode => {
    return nodes.map(node => {
      const isExpanded = expandedNodes.includes(node.id);
      const isSelected = selectedProperty?.id === node.id;
      const hasChildren = node.children && node.children.length > 0;
      const displayLabel = getDisplayLabel(node);
      
      return (
        <div key={node.id}>
          <div
            className={`flex items-center gap-1 px-1 py-1 cursor-pointer transition-colors select-none ${
              isSelected 
                ? `${themeColors.selected} text-white` 
                : 'hover:bg-gray-100 text-gray-900'
            }`}
            style={{ paddingLeft: `${level * 16 + 4}px` }}
            onClick={() => handleSelectProperty(node)}
            onDoubleClick={() => handleDoubleClick(node)}
          >
            {/* Expand/Collapse Arrow */}
            {hasChildren ? (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  handleToggleNode(node.id);
                }}
                className={`p-0.5 rounded flex-shrink-0 ${
                  isSelected ? themeColors.selectedHover : 'hover:bg-gray-200'
                }`}
              >
                {isExpanded ? (
                  <ChevronDown size={14} />
                ) : (
                  <ChevronRight size={14} />
                )}
              </button>
            ) : (
              <span className="w-5 flex-shrink-0" />
            )}
            
            {/* Property Icon (colored rectangle like Protégé) */}
            <span className={`w-3 h-3 rounded-sm flex-shrink-0 ${
              isSelected ? themeColors.primaryLight : themeColors.primary
            }`} />
            
            {/* Property Label */}
            <span className={`text-sm font-mono truncate ${
              isSelected ? 'font-semibold' : ''
            }`}>
              {displayLabel}
              {/* Show equivalence notation if present */}
              {node.type && (node as any).equivalentProperties?.length > 0 && (
                <span className="text-gray-400 ml-1">
                  ≡ {(node as any).equivalentProperties.map((e: string) => e.split('#').pop()).join(' ≡ ')}
                </span>
              )}
            </span>
          </div>
          
          {/* Children */}
          {isExpanded && hasChildren && (
            <div>
              {renderPropertyTree(node.children!, level + 1)}
            </div>
          )}
        </div>
      );
    });
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={handleClose}>
      <div 
        className="bg-white rounded-lg shadow-xl w-full max-w-md mx-4 flex flex-col max-h-[70vh]" 
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <div className="px-4 py-3 border-b bg-gray-100 flex justify-between items-center">
          <div className="flex items-center gap-2">
            <span className={`w-4 h-4 rounded-sm ${themeColors.primary}`} />
            <h3 className="text-sm font-medium text-gray-900">{title}</h3>
          </div>
          <button
            onClick={handleClose}
            className="text-gray-500 hover:text-gray-700 p-1 hover:bg-gray-200 rounded transition-colors"
          >
            <X size={18} />
          </button>
        </div>

        {/* Toolbar */}
        <div className="px-3 py-2 border-b bg-gray-50 flex items-center justify-between gap-2">
          {/* Left side: Add/Delete buttons */}
          <div className="flex items-center gap-1">
            <button
              onClick={handleAddSubProperty}
              disabled={!selectedProperty || !onAddSubProperty}
              className="p-1.5 hover:bg-gray-200 rounded disabled:opacity-30 disabled:cursor-not-allowed"
              title="Add sub property"
            >
              <Plus size={16} className="text-gray-600" />
            </button>
            <button
              onClick={handleAddSiblingProperty}
              disabled={!onAddSiblingProperty}
              className="p-1.5 hover:bg-gray-200 rounded disabled:opacity-30 disabled:cursor-not-allowed"
              title="Add sibling property"
            >
              <Folder size={16} className="text-gray-600" />
            </button>
            <button
              onClick={handleDeleteProperty}
              disabled={!selectedProperty || !onDeleteProperty}
              className="p-1.5 hover:bg-red-100 rounded disabled:opacity-30 disabled:cursor-not-allowed"
              title="Delete selected property"
            >
              <Trash2 size={16} className="text-gray-600" />
            </button>
            {onRefresh && (
              <button
                onClick={onRefresh}
                className="p-1.5 hover:bg-gray-200 rounded"
                title="Refresh"
              >
                <RefreshCw size={16} className="text-gray-600" />
              </button>
            )}
          </div>

          {/* Right side: View mode dropdown */}
          <div className="flex items-center gap-2">
            <select
              value={viewMode}
              onChange={(e) => setViewMode(e.target.value as ViewMode)}
              className="px-2 py-1 text-xs border border-gray-300 rounded bg-white focus:outline-none focus:ring-1 focus:ring-blue-500"
            >
              <option value="Asserted">Asserted</option>
              <option value="Inferred">Inferred</option>
            </select>
          </div>
        </div>

        {/* Add Property Input (shown when adding) */}
        {showAddInput && (
          <div className="px-3 py-2 border-b bg-blue-50 flex items-center gap-2">
            <input
              type="text"
              value={newPropertyName}
              onChange={(e) => setNewPropertyName(e.target.value)}
              placeholder={addMode === 'subclass' ? 'New sub property name...' : 'New sibling property name...'}
              className="flex-1 px-2 py-1 text-sm border border-blue-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500"
              autoFocus
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleCreateProperty();
                if (e.key === 'Escape') {
                  setShowAddInput(false);
                  setNewPropertyName('');
                }
              }}
            />
            <button
              onClick={handleCreateProperty}
              disabled={!newPropertyName.trim() || isCreating}
              className="px-3 py-1 text-xs font-medium text-white bg-blue-600 rounded hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {isCreating ? 'Creating...' : 'Create'}
            </button>
            <button
              onClick={() => {
                setShowAddInput(false);
                setNewPropertyName('');
              }}
              className="px-3 py-1 text-xs font-medium text-gray-600 bg-gray-200 rounded hover:bg-gray-300"
            >
              Cancel
            </button>
          </div>
        )}

        {/* Property Tree */}
        <div className="flex-1 overflow-y-auto min-h-[200px] bg-white border-b">
          {objectPropertyHierarchy.length > 0 ? (
            <div className="py-1">
              {renderPropertyTree(objectPropertyHierarchy)}
            </div>
          ) : (
            <div className="flex items-center justify-center h-full text-sm text-gray-400 italic p-4">
              No {isDataProperty ? 'data' : 'object'} properties available
            </div>
          )}
        </div>

        {/* Inverse Property Checkbox and Preview */}
        <div className="px-4 py-3 border-t bg-gray-50">
          {showInverseOption && (
            <label className="flex items-center gap-2 cursor-pointer mb-2">
              <input
                type="checkbox"
                checked={isInverseProperty}
                onChange={(e) => setIsInverseProperty(e.target.checked)}
                className={`w-4 h-4 ${themeColors.checkbox} border-gray-300 rounded ${themeColors.focusRing}`}
              />
              <span className="text-sm text-gray-700">Inverse Property</span>
            </label>
          )}
          
          {/* Preview of selection */}
          {selectedProperty && (
            <div className="p-2 bg-white border border-gray-200 rounded">
              <span className="text-xs text-gray-500">Selected:</span>
              <div className="flex items-center gap-2 mt-1">
                <span className={`w-3 h-3 rounded-sm flex-shrink-0 ${themeColors.primary}`} />
                <span className="text-sm font-mono text-gray-900">
                  {isInverseProperty ? (
                    <>
                      <span className={themeColors.text}>inverse</span>
                      <span className="text-gray-600"> ('{getDisplayLabel(selectedProperty)}')</span>
                    </>
                  ) : (
                    <span className="text-gray-900">'{getDisplayLabel(selectedProperty)}'</span>
                  )}
                </span>
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-4 py-3 border-t bg-gray-100 flex justify-end gap-2">
          <button
            onClick={handleClose}
            className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-50 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleConfirm}
            disabled={!selectedProperty}
            className={`px-4 py-2 text-sm font-medium text-white rounded disabled:opacity-50 disabled:cursor-not-allowed transition-colors ${themeColors.button}`}
          >
            OK
          </button>
        </div>
      </div>
    </div>
  );
};

export default ObjectPropertyExpressionDialog;

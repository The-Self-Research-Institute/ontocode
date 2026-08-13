import React, { useState, useEffect } from 'react';
import { X, Check, GitBranch, Database } from 'lucide-react';
import type { TreeNode } from '../../types';
import EntityHierarchy from '../EntityHierarchy';

interface MultiPropertySelectorDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (propertyIris: string[]) => void;
  objectProperties: any[];
  dataProperties: any[];
  objectPropertyHierarchy?: TreeNode[]; // Hierarchical structure for object properties
  dataPropertyHierarchy?: TreeNode[]; // Hierarchical structure for data properties
  expandedNodes?: string[];
  onToggleNode?: (nodeId: string) => void;
  title?: string;
  minSelection?: number;
  initialSelectedIds?: string[]; // Pre-selected property IRIs for edit mode
  currentPropertyIri?: string; // Current property IRI for validation (e.g., in disjoint dialog)
  projectId?: string;
  onAddObjectProperty?: (type: 'subclass' | 'sibling', parentId?: string, name?: string) => Promise<void>;
  onAddDataProperty?: (type: 'subclass' | 'sibling', parentId?: string, name?: string) => Promise<void>;
  onDeleteProperty?: () => void;
  onRefreshProperties?: () => void;
}

const MultiPropertySelectorDialog: React.FC<MultiPropertySelectorDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  objectProperties,
  dataProperties,
  objectPropertyHierarchy,
  dataPropertyHierarchy,
  expandedNodes = [],
  onToggleNode,
  title = "Select Properties",
  minSelection = 1,
  initialSelectedIds = [],
  projectId,
  onAddObjectProperty,
  onAddDataProperty,
  onDeleteProperty,
  onRefreshProperties,
  currentPropertyIri
}) => {
  const [selectedProperties, setSelectedProperties] = useState<string[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [activeTab, setActiveTab] = useState<'object' | 'data'>('object');
  const [hasInitialized, setHasInitialized] = useState(false);
  const [selectedProperty, setSelectedProperty] = useState<TreeNode | null>(null);
  const [localExpandedNodes, setLocalExpandedNodes] = useState<string[]>([]);

  const [showInlinePropertyCreate, setShowInlinePropertyCreate] = useState(false);
  const [inlinePropertyName, setInlinePropertyName] = useState('');
  const [isCreatingProperty, setIsCreatingProperty] = useState(false);

  const isDisjointDialog = title?.toLowerCase().includes('disjoint');
  const hasSelfSelection = isDisjointDialog && currentPropertyIri && selectedProperties.includes(currentPropertyIri);

  const [objectPropertiesTree, setObjectPropertiesTree] = useState<TreeNode[]>([]);
  const [dataPropertiesTree, setDataPropertiesTree] = useState<TreeNode[]>([]);

  useEffect(() => {
    const newObjectTree = objectPropertyHierarchy || objectProperties.map(p => ({
      ...p,
      children: [],
      hasChildren: false
    } as TreeNode));
    setObjectPropertiesTree(newObjectTree);
  }, [objectPropertyHierarchy, objectProperties]);

  useEffect(() => {
    const newDataTree = dataPropertyHierarchy || dataProperties.map(p => ({
      ...p,
      children: [],
      hasChildren: false
    } as TreeNode));
    setDataPropertiesTree(newDataTree);
  }, [dataPropertyHierarchy, dataProperties]);

  useEffect(() => {
    if (isOpen && !hasInitialized) {
      setHasInitialized(true);
      setSearchQuery('');

      if (initialSelectedIds.length > 0) {
        setSelectedProperties(initialSelectedIds);

        const hasObjectProperty = objectProperties.some(p => initialSelectedIds.includes(p.id));
        const hasDataProperty = dataProperties.some(p => initialSelectedIds.includes(p.id));
        if (hasDataProperty && !hasObjectProperty) {
          setActiveTab('data');
        } else {
          setActiveTab('object');
        }
      } else {
        setSelectedProperties([]);
      }
    }
  }, [isOpen, hasInitialized, initialSelectedIds, objectProperties, dataProperties]);

  useEffect(() => {
    if (!isOpen) {
      setHasInitialized(false);
    }
  }, [isOpen]);

  if (!isOpen) return null;

  const handleToggleNode = (nodeId: string) => {
    if (onToggleNode) {
      onToggleNode(nodeId);
    } else {
      setLocalExpandedNodes(prev =>
        prev.includes(nodeId) ? prev.filter(id => id !== nodeId) : [...prev, nodeId]
      );
    }
  };

  const effectiveExpandedNodes = onToggleNode ? expandedNodes : localExpandedNodes;

  const handleSelectProperty = (property: TreeNode) => {
    setSelectedProperty(property);

    if (selectedProperties.includes(property.id)) {
      setSelectedProperties(prev => prev.filter(id => id !== property.id));
    } else {
      setSelectedProperties(prev => [...prev, property.id]);
    }
  };

  const handleToggleProperty = (propertyId: string) => {
    if (selectedProperties.includes(propertyId)) {
      setSelectedProperties(prev => prev.filter(id => id !== propertyId));
    } else {
      setSelectedProperties(prev => [...prev, propertyId]);
    }
  };

  const handleConfirm = () => {
    onConfirm(selectedProperties);
    setSelectedProperties([]);
    onClose();
  };

  const getPropertyLabel = (id: string): string => {
    const objProp = objectProperties.find(p => p.id === id);
    if (objProp) return objProp.label || id.split('#').pop() || id;
    const dataProp = dataProperties.find(p => p.id === id);
    if (dataProp) return dataProp.label || id.split('#').pop() || id;
    return id.split('#').pop() || id;
  };

  const filterTree = (nodes: TreeNode[], query: string): TreeNode[] => {
    if (!query) return nodes;
    const lowerQuery = query.toLowerCase();
    return nodes.filter(node => {
      const matches = (node.label || node.id).toLowerCase().includes(lowerQuery);
      const childMatches = node.children && filterTree(node.children, query).length > 0;
      return matches || childMatches;
    });
  };

  const filteredObjectProperties = filterTree(objectPropertiesTree, searchQuery);
  const filteredDataProperties = filterTree(dataPropertiesTree, searchQuery);

  const handleInlineAddProperty = (type: 'subclass' | 'sibling') => {
    setShowInlinePropertyCreate(true);
    setInlinePropertyName('');
  };

  const handleInlinePropertyCreateSubmit = async () => {
    if (!inlinePropertyName.trim()) return;

    const handler = activeTab === 'object' ? onAddObjectProperty : onAddDataProperty;
    if (!handler) return;

    setIsCreatingProperty(true);
    try {
      const parentId = selectedProperty?.id;
      const type = selectedProperty ? 'subclass' : 'sibling';

      if (parentId && !effectiveExpandedNodes.includes(parentId)) {
        handleToggleNode(parentId);
      }

      const topNodeId = activeTab === 'object'
        ? 'http://www.w3.org/2002/07/owl#topObjectProperty'
        : 'http://www.w3.org/2002/07/owl#topDataProperty';

      if (!selectedProperty && !effectiveExpandedNodes.includes(topNodeId)) {
        handleToggleNode(topNodeId);
      }

      await handler(type, parentId, inlinePropertyName.trim());

      setShowInlinePropertyCreate(false);
      setInlinePropertyName('');

      // Note: The handlers already refresh properties via refreshProperties()
      // The hierarchy will update automatically through props
    } catch (error) {
      console.error('[MultiPropertySelectorDialog] Failed to create property:', error);

      setShowInlinePropertyCreate(true);
    } finally {
      setIsCreatingProperty(false);
    }
  };

  const handleInlinePropertyCreateCancel = () => {
    setShowInlinePropertyCreate(false);
    setInlinePropertyName('');
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) onClose();
      }}
    >
      <div className="bg-white rounded-lg shadow-xl max-w-4xl w-full mx-4 flex flex-col max-h-[85vh]" onClick={e => e.stopPropagation()}>
        <div className="p-4 border-b flex justify-between items-center bg-gray-50">
          <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
            <X size={20} />
          </button>
        </div>

        <div className="p-4 flex-1 overflow-hidden flex flex-col min-h-0">
          {}
          <div className="mb-3">
            <div className="text-xs font-medium text-gray-500 uppercase mb-1">Selected Properties ({selectedProperties.length})</div>
            {selectedProperties.length > 0 ? (
              <div className="flex flex-wrap gap-1">
                {selectedProperties.map(id => (
                  <span
                    key={id}
                    className="inline-flex items-center gap-1 px-2 py-1 bg-purple-100 text-purple-800 rounded-full text-xs cursor-pointer hover:bg-purple-200"
                    onClick={() => handleToggleProperty(id)}
                  >
                    {getPropertyLabel(id)}
                    <X size={12} />
                  </span>
                ))}
              </div>
            ) : (
              <div className="text-sm text-gray-400 italic">No properties selected</div>
            )}
          </div>

          {}
          <div className="flex border-b border-gray-200 mb-2">
            <button
              onClick={() => setActiveTab('object')}
              className={`px-4 py-2 text-sm font-medium flex items-center gap-2 border-b-2 transition-colors ${
                activeTab === 'object'
                  ? 'border-blue-600 text-blue-700 bg-white'
                  : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
              }`}
            >
              <GitBranch size={14} />
              Object Properties ({objectProperties.length})
            </button>
            <button
              onClick={() => setActiveTab('data')}
              className={`px-4 py-2 text-sm font-medium flex items-center gap-2 border-b-2 transition-colors ${
                activeTab === 'data'
                  ? 'border-green-600 text-green-700 bg-white'
                  : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
              }`}
            >
              <Database size={14} />
              Data Properties ({dataProperties.length})
            </button>
          </div>

          {}
          <div className="flex-1 overflow-hidden border rounded min-h-0 bg-white flex flex-col">
            {}
            {activeTab === 'object' && showInlinePropertyCreate && (
              <div className="p-3 bg-blue-50 border-b border-blue-200">
                <div className="flex items-center gap-2">
                  <input
                    type="text"
                    value={inlinePropertyName}
                    onChange={(e) => setInlinePropertyName(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && inlinePropertyName.trim()) {
                        handleInlinePropertyCreateSubmit();
                      } else if (e.key === 'Escape') {
                        handleInlinePropertyCreateCancel();
                      }
                    }}
                    placeholder="Enter object property name..."
                    className="flex-1 px-2 py-1 text-sm border border-blue-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500"
                    autoFocus
                  />
                  <button
                    onClick={handleInlinePropertyCreateSubmit}
                    disabled={!inlinePropertyName.trim() || isCreatingProperty}
                    className="px-3 py-1 text-xs font-semibold text-white bg-blue-600 rounded hover:bg-blue-700 disabled:bg-gray-300 disabled:cursor-not-allowed"
                  >
                    {isCreatingProperty ? 'Creating...' : 'Create'}
                  </button>
                  <button
                    onClick={handleInlinePropertyCreateCancel}
                    className="px-3 py-1 text-xs font-semibold text-blue-800 bg-white border border-blue-300 rounded hover:bg-blue-100"
                  >
                    Cancel
                  </button>
                </div>
                <p className="mt-1 text-xs text-blue-700">Press Enter to create, Escape to cancel</p>
              </div>
            )}

            {}
            {activeTab === 'data' && showInlinePropertyCreate && (
              <div className="p-3 bg-green-50 border-b border-green-200">
                <div className="flex items-center gap-2">
                  <input
                    type="text"
                    value={inlinePropertyName}
                    onChange={(e) => setInlinePropertyName(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && inlinePropertyName.trim()) {
                        handleInlinePropertyCreateSubmit();
                      } else if (e.key === 'Escape') {
                        handleInlinePropertyCreateCancel();
                      }
                    }}
                    placeholder="Enter data property name..."
                    className="flex-1 px-2 py-1 text-sm border border-green-300 rounded focus:outline-none focus:ring-2 focus:ring-green-500"
                    autoFocus
                  />
                  <button
                    onClick={handleInlinePropertyCreateSubmit}
                    disabled={!inlinePropertyName.trim() || isCreatingProperty}
                    className="px-3 py-1 text-xs font-semibold text-white bg-green-600 rounded hover:bg-green-700 disabled:bg-gray-300 disabled:cursor-not-allowed"
                  >
                    {isCreatingProperty ? 'Creating...' : 'Create'}
                  </button>
                  <button
                    onClick={handleInlinePropertyCreateCancel}
                    className="px-3 py-1 text-xs font-semibold text-green-800 bg-white border border-green-300 rounded hover:bg-green-100"
                  >
                    Cancel
                  </button>
                </div>
                <p className="mt-1 text-xs text-green-700">Press Enter to create, Escape to cancel</p>
              </div>
            )}

            {activeTab === 'object' && (
              <div className="flex-1 overflow-hidden">
                <EntityHierarchy
                  entitiesTab="ObjectProperties"
                  filteredData={filteredObjectProperties}
                  selectedItem={selectedProperty}
                  expandedNodes={effectiveExpandedNodes}
                  searchQuery={searchQuery}
                  onSearchQueryChange={setSearchQuery}
                  onSelectItem={handleSelectProperty}
                  onToggleNode={handleToggleNode}
                  onAddItem={onAddObjectProperty ? handleInlineAddProperty : undefined}
                  onDeleteItem={onDeleteProperty ? onDeleteProperty : undefined}
                  hideToolbarActions={!onAddObjectProperty && !onDeleteProperty}
                  selectedProperties={selectedProperties}
                  multiSelectMode={true}
                />
              </div>
            )}
            {activeTab === 'data' && (
              <div className="flex-1 overflow-hidden">
                <EntityHierarchy
                  entitiesTab="DataProperties"
                  filteredData={filteredDataProperties}
                  selectedItem={selectedProperty}
                  expandedNodes={effectiveExpandedNodes}
                  searchQuery={searchQuery}
                  onSearchQueryChange={setSearchQuery}
                  onSelectItem={handleSelectProperty}
                  onToggleNode={handleToggleNode}
                  onAddItem={onAddDataProperty ? handleInlineAddProperty : undefined}
                  onDeleteItem={onDeleteProperty ? onDeleteProperty : undefined}
                  hideToolbarActions={!onAddDataProperty && !onDeleteProperty}
                  selectedProperties={selectedProperties}
                  multiSelectMode={true}
                />
              </div>
            )}
          </div>
        </div>

        <div className="p-4 border-t bg-gray-50 flex justify-between items-center">
          <div className="text-xs text-gray-500">
            Select at least {minSelection} propert{minSelection === 1 ? 'y' : 'ies'} for the key
          </div>
          <div className="flex gap-2">
            <button
              onClick={onClose}
              className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              onClick={handleConfirm}
              disabled={selectedProperties.length < minSelection || (isDisjointDialog && hasSelfSelection)}
              className="px-4 py-2 text-sm font-medium text-white bg-purple-600 rounded hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed"
              title={isDisjointDialog && hasSelfSelection ? "Cannot make a property disjoint with itself" : ""}
            >
              Confirm ({selectedProperties.length})
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default MultiPropertySelectorDialog;

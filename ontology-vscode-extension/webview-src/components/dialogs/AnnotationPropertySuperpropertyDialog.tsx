import React, { useState, useEffect } from 'react';
import { X, Search, ChevronDown, ChevronRight, Tag, Plus, Trash2 } from 'lucide-react';
import type { TreeNode, AnnotationProperty } from '../../types';

/**
 * AnnotationPropertySuperpropertyDialog - Protégé-style dialog for selecting annotation property superproperties
 * 
 * Based on Protégé's OWLSubAnnotationPropertyFrameSection which uses OWLAnnotationPropertyEditor
 * - Lists annotation properties in a hierarchy
 * - Allows selecting one or more annotation properties as superproperties
 * - Toolbar with Add subproperty, Add sibling property, Delete property buttons
 */

type FilterMode = 'asserted' | 'inferred' | 'all';

interface AnnotationPropertySuperpropertyDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (iri: string) => void;
  annotationPropertyHierarchy: TreeNode[];
  currentPropertyId?: string; // Current property IRI to exclude from selection
  title?: string;
  selectedSuperproperties?: string[]; // Already selected to exclude/show
  // Toolbar action callbacks - optional, toolbar shown only if at least one is provided
  onAddSubproperty?: (parentIri: string) => void;
  onAddSiblingProperty?: (siblingIri: string) => void;
  onDeleteProperty?: (propertyIri: string) => void;
}

// Built-in OWL/RDF annotation properties
const BUILTIN_ANNOTATION_PROPERTIES = [
  { id: 'http://www.w3.org/2000/01/rdf-schema#label', label: 'rdfs:label' },
  { id: 'http://www.w3.org/2000/01/rdf-schema#comment', label: 'rdfs:comment' },
  { id: 'http://www.w3.org/2000/01/rdf-schema#seeAlso', label: 'rdfs:seeAlso' },
  { id: 'http://www.w3.org/2000/01/rdf-schema#isDefinedBy', label: 'rdfs:isDefinedBy' },
  { id: 'http://www.w3.org/2002/07/owl#deprecated', label: 'owl:deprecated' },
  { id: 'http://www.w3.org/2002/07/owl#versionInfo', label: 'owl:versionInfo' },
  { id: 'http://www.w3.org/2002/07/owl#priorVersion', label: 'owl:priorVersion' },
  { id: 'http://www.w3.org/2002/07/owl#backwardCompatibleWith', label: 'owl:backwardCompatibleWith' },
  { id: 'http://www.w3.org/2002/07/owl#incompatibleWith', label: 'owl:incompatibleWith' },
];

const AnnotationPropertySuperpropertyDialog: React.FC<AnnotationPropertySuperpropertyDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  annotationPropertyHierarchy,
  currentPropertyId,
  title = "Superproperties",
  selectedSuperproperties = [],
  onAddSubproperty,
  onAddSiblingProperty,
  onDeleteProperty
}) => {
  const [activeTab, setActiveTab] = useState<'select-property' | 'edit-iri'>('select-property');
  const [selectedProperty, setSelectedProperty] = useState<TreeNode | null>(null);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [rawIri, setRawIri] = useState('');
  const [filterMode, setFilterMode] = useState<FilterMode>('asserted');

  // Show toolbar if any action callback is provided
  const showToolbar = onAddSubproperty || onAddSiblingProperty || onDeleteProperty;

  // Combine built-in properties with hierarchy
  const allProperties: TreeNode[] = [
    // Built-in properties first
    ...BUILTIN_ANNOTATION_PROPERTIES.map(p => ({
      id: p.id,
      label: p.label,
      type: 'AnnotationProperty' as const,
      children: [],
      hasChildren: false
    })),
    // Then hierarchy properties (excluding duplicates)
    ...annotationPropertyHierarchy.filter(
      h => !BUILTIN_ANNOTATION_PROPERTIES.some(b => b.id === h.id)
    )
  ];

  useEffect(() => {
    if (isOpen) {
      setSelectedProperty(null);
      setRawIri('');
      setSearchQuery('');
      setActiveTab('select-property');
      setExpandedNodes([]);
    }
  }, [isOpen]);

  const handleToggleNode = (nodeId: string) => {
    setExpandedNodes(prev =>
      prev.includes(nodeId)
        ? prev.filter(id => id !== nodeId)
        : [...prev, nodeId]
    );
  };

  const handleConfirm = () => {
    if (activeTab === 'select-property' && selectedProperty) {
      onConfirm(selectedProperty.id);
    } else if (activeTab === 'edit-iri' && rawIri.trim()) {
      onConfirm(rawIri.trim());
    }
    handleClose();
  };

  const handleClose = () => {
    setSelectedProperty(null);
    setRawIri('');
    setExpandedNodes([]);
    setSearchQuery('');
    onClose();
  };

  const isValidSelection = activeTab === 'select-property' 
    ? selectedProperty !== null 
    : rawIri.trim().length > 0;

  // Filter properties by search query
  const filterNodes = (nodes: TreeNode[], query: string): TreeNode[] => {
    if (!query) return nodes;
    const lowerQuery = query.toLowerCase();
    return nodes.filter(node => {
      const label = (node.label || node.id.split('#').pop() || '').toLowerCase();
      const matchesSelf = label.includes(lowerQuery);
      const filteredChildren = node.children ? filterNodes(node.children, query) : [];
      return matchesSelf || filteredChildren.length > 0;
    }).map(node => ({
      ...node,
      children: node.children ? filterNodes(node.children, query) : undefined
    }));
  };

  const filteredProperties = filterNodes(allProperties, searchQuery);

  // Render annotation property tree
  const renderPropertyTree = (nodes: TreeNode[], level: number = 0): React.ReactNode => {
    return nodes.map(node => {
      const isExpanded = expandedNodes.includes(node.id);
      const isSelected = selectedProperty?.id === node.id;
      // Check both actual children and hasChildren flag for lazy loading
      const hasChildren = (node.children && node.children.length > 0) || (node as any).hasChildren;
      const displayLabel = node.label || node.id.split('#').pop() || node.id;
      const isSelf = node.id === currentPropertyId;
      const isAlreadySelected = selectedSuperproperties.includes(node.id);
      const isDisabled = isSelf || isAlreadySelected;

      return (
        <div key={node.id}>
          <div
            className={`flex items-center gap-1 px-1 py-1 cursor-pointer transition-colors select-none ${
              isSelected 
                ? 'bg-blue-600 text-white' 
                : isDisabled
                  ? 'bg-gray-100 text-gray-400'
                  : 'hover:bg-gray-100 text-gray-900'
            }`}
            style={{ paddingLeft: `${level * 16 + 4}px` }}
            onClick={() => !isDisabled && setSelectedProperty(node)}
            onDoubleClick={() => {
              if (!isDisabled) {
                setSelectedProperty(node);
                handleConfirm();
              }
            }}
          >
            {/* Expand/Collapse Arrow */}
            {hasChildren ? (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  handleToggleNode(node.id);
                }}
                className={`p-0.5 rounded flex-shrink-0 ${
                  isSelected ? 'hover:bg-blue-500' : 'hover:bg-gray-200'
                }`}
              >
                {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
              </button>
            ) : (
              <span className="w-5 flex-shrink-0" />
            )}

            {/* Annotation Property Icon (orange tag like Protégé) */}
            <Tag size={12} className={`flex-shrink-0 ${
              isSelected ? 'text-orange-300' : 'text-orange-500'
            }`} />

            {/* Property Label */}
            <span className={`text-sm truncate ${isSelected ? 'font-medium' : ''}`}>
              {displayLabel}
              {isSelf && <span className="ml-1 text-xs">(current property)</span>}
              {isAlreadySelected && <span className="ml-1 text-xs">(already selected)</span>}
            </span>
          </div>

          {/* Children */}
          {isExpanded && node.children && node.children.length > 0 && (
            <div>{renderPropertyTree(node.children!, level + 1)}</div>
          )}
          {/* Loading indicator when expanded but children not loaded yet */}
          {isExpanded && (node as any).hasChildren && (!node.children || node.children.length === 0) && (
            <div style={{ paddingLeft: `${(level + 1) * 16 + 4}px` }} className="px-1 py-1 text-xs text-gray-400 italic">
              Loading...
            </div>
          )}
        </div>
      );
    });
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={handleClose}>
      <div 
        className="bg-white rounded-lg shadow-xl w-full max-w-lg mx-4 flex flex-col" 
        style={{ height: '500px', maxHeight: '85vh' }}
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <div className="px-4 py-3 border-b bg-gray-100 flex justify-between items-center">
          <div className="flex items-center gap-2">
            <Tag size={16} className="text-orange-500" />
            <h3 className="text-sm font-medium text-gray-900">{title}</h3>
          </div>
          <button
            onClick={handleClose}
            className="text-gray-500 hover:text-gray-700 p-1 hover:bg-gray-200 rounded transition-colors"
          >
            <X size={18} />
          </button>
        </div>

        {/* Toolbar - Protégé style: Add subproperty, Add sibling, Delete, Filter dropdown */}
        {showToolbar && (
          <div className="flex items-center gap-1 px-2 py-1.5 border-b bg-gray-50">
            {/* Add Subproperty Button */}
            {onAddSubproperty && (
              <button
                onClick={() => selectedProperty && onAddSubproperty(selectedProperty.id)}
                disabled={!selectedProperty}
                title="Add subproperty"
                className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Plus size={12} />
                <Tag size={12} className="text-orange-500" />
                <span className="sr-only">Add subproperty</span>
              </button>
            )}
            
            {/* Add Sibling Property Button */}
            {onAddSiblingProperty && (
              <button
                onClick={() => selectedProperty && onAddSiblingProperty(selectedProperty.id)}
                disabled={!selectedProperty}
                title="Add sibling property"
                className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Plus size={12} />
                <Tag size={12} className="text-orange-500" />
                <span className="sr-only">Add sibling property</span>
              </button>
            )}
            
            {/* Delete Property Button */}
            {onDeleteProperty && (
              <button
                onClick={() => selectedProperty && onDeleteProperty(selectedProperty.id)}
                disabled={!selectedProperty}
                title="Delete property"
                className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-red-600 bg-white border border-gray-300 rounded hover:bg-red-50 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Trash2 size={12} />
                <span className="sr-only">Delete property</span>
              </button>
            )}

            {/* Spacer */}
            <div className="flex-1" />

            {/* Filter Dropdown - Asserted/Inferred/All */}
            <select
              value={filterMode}
              onChange={(e) => setFilterMode(e.target.value as FilterMode)}
              className="px-2 py-1 text-xs font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-50 focus:outline-none focus:ring-1 focus:ring-orange-500"
            >
              <option value="asserted">Asserted</option>
              <option value="inferred">Inferred</option>
              <option value="all">All</option>
            </select>
          </div>
        )}

        {/* Tabs - Protégé style */}
        <div className="flex border-b border-gray-200 bg-gray-50">
          <button
            onClick={() => setActiveTab('select-property')}
            className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
              activeTab === 'select-property'
                ? 'border-orange-600 text-orange-700 bg-white'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
            }`}
          >
            Select Property
          </button>
          <button
            onClick={() => setActiveTab('edit-iri')}
            className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
              activeTab === 'edit-iri'
                ? 'border-orange-600 text-orange-700 bg-white'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
            }`}
          >
            Edit raw IRI
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-hidden flex flex-col">
          {activeTab === 'select-property' && (
            <>
              {/* Search */}
              <div className="px-3 py-2 border-b bg-gray-50">
                <div className="relative">
                  <Search className="absolute left-2.5 top-1/2 transform -translate-y-1/2 text-gray-400" size={14} />
                  <input
                    type="text"
                    placeholder="Search annotation properties..."
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    className="w-full pl-8 pr-3 py-1.5 text-sm border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-orange-500 focus:border-orange-500"
                  />
                </div>
              </div>

              {/* Property List */}
              <div className="flex-1 overflow-y-auto p-2 bg-white">
                {filteredProperties.length > 0 ? (
                  renderPropertyTree(filteredProperties)
                ) : (
                  <div className="text-sm text-gray-500 text-center py-4">
                    {searchQuery ? 'No properties match your search' : 'No annotation properties available'}
                  </div>
                )}
              </div>

              {/* Selected Property Display */}
              {selectedProperty && (
                <div className="px-3 py-2 border-t bg-gray-50 text-xs">
                  <span className="text-gray-500">Selected: </span>
                  <span className="font-mono text-gray-700">{selectedProperty.label || selectedProperty.id}</span>
                </div>
              )}
            </>
          )}

          {activeTab === 'edit-iri' && (
            <div className="flex-1 p-4 flex flex-col">
              <label className="text-sm font-medium text-gray-700 mb-2">
                Enter IRI for superproperty:
              </label>
              <input
                type="text"
                placeholder="http://www.w3.org/2000/01/rdf-schema#label"
                value={rawIri}
                onChange={(e) => setRawIri(e.target.value)}
                className="w-full px-3 py-2 text-sm font-mono border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-orange-500 focus:border-orange-500"
              />
              <p className="mt-2 text-xs text-gray-500">
                Enter a valid annotation property IRI to add as a superproperty.
              </p>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-4 py-3 border-t bg-gray-50 flex justify-end gap-2">
          <button
            onClick={handleClose}
            className="px-4 py-1.5 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={handleConfirm}
            disabled={!isValidSelection}
            className="px-4 py-1.5 text-sm font-medium text-white bg-orange-600 rounded hover:bg-orange-700 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            OK
          </button>
        </div>
      </div>
    </div>
  );
};

export default AnnotationPropertySuperpropertyDialog;

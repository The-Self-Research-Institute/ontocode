import React, { useState, useEffect, useCallback } from 'react';
import { X, Search, ChevronDown, ChevronRight, Plus, Trash2, ListTree } from 'lucide-react';
import type { TreeNode } from '../../types';
import apiClient from '../../services/apiClient';

/**
 * AnnotationPropertyDomainDialog - Protégé-style dialog for selecting annotation property domains
 * 
 * Based on Protégé's OWLAnnotationPropertyDomainEditor.java:
 * - Two tabs: "Select Class" (class hierarchy selector) and "Edit raw IRI" (direct IRI input)
 * - Size: 500x500 in original
 * - Uses OWLClassSelectorWrapper for class selection
 * - Uses IRITextEditor for raw IRI input
 * - Toolbar with Add subclass, Add sibling, Delete class buttons
 * - Asserted/Inferred/All filter dropdown
 */

type FilterMode = 'asserted' | 'inferred' | 'all';

interface AnnotationPropertyDomainDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (iri: string) => void;
  classHierarchy: TreeNode[];
  projectId?: string;
  onToggleNode?: (nodeId: string) => Promise<void> | void;
  externalExpandedNodes?: string[];
  title?: string;
  selectedDomains?: string[]; // Already selected domains to exclude/show
  // Toolbar action callbacks - optional, toolbar shown only if at least one is provided
  onAddSubclass?: (parentIri: string) => void;
  onAddSiblingClass?: (siblingIri: string) => void;
  onDeleteClass?: (classIri: string) => void;
}

const AnnotationPropertyDomainDialog: React.FC<AnnotationPropertyDomainDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  classHierarchy,
  projectId,
  onToggleNode,
  externalExpandedNodes,
  title = "Domain (intersection)",
  selectedDomains = [],
  onAddSubclass,
  onAddSiblingClass,
  onDeleteClass
}) => {
  const [activeTab, setActiveTab] = useState<'select-class' | 'edit-iri'>('select-class');
  const [selectedClass, setSelectedClass] = useState<TreeNode | null>(null);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [treeData, setTreeData] = useState<TreeNode[]>(classHierarchy);
  const [searchQuery, setSearchQuery] = useState('');
  const [rawIri, setRawIri] = useState('');
  const [filterMode, setFilterMode] = useState<FilterMode>('asserted');

  // Show toolbar if any action callback is provided
  const showToolbar = onAddSubclass || onAddSiblingClass || onDeleteClass;

  useEffect(() => {
    setTreeData(classHierarchy);
  }, [classHierarchy]);

  useEffect(() => {
    if (isOpen) {
      setSelectedClass(null);
      setRawIri('');
      setSearchQuery('');
      setActiveTab('select-class');
      // Auto-expand owl:Thing
      if (classHierarchy.length > 0) {
        const topNode = classHierarchy[0];
        if (!expandedNodes.includes(topNode.id)) {
          setExpandedNodes(prev => [...prev, topNode.id]);
        }
      }
    }
  }, [isOpen, classHierarchy]);

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
                children: c.hasChildren ? [] : (c.children || []),
                hasChildren: c.hasChildren || false
              })),
              hasChildren: true
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

  const handleConfirm = () => {
    if (activeTab === 'select-class' && selectedClass) {
      onConfirm(selectedClass.id);
    } else if (activeTab === 'edit-iri' && rawIri.trim()) {
      onConfirm(rawIri.trim());
    }
    handleClose();
  };

  const handleClose = () => {
    setSelectedClass(null);
    setRawIri('');
    setExpandedNodes([]);
    setSearchQuery('');
    onClose();
  };

  const isValidSelection = activeTab === 'select-class' 
    ? selectedClass !== null 
    : rawIri.trim().length > 0;

  // Filter tree nodes by search query
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

  const filteredTreeData = filterNodes(treeData, searchQuery);

  // Render class hierarchy tree
  const renderClassTree = (nodes: TreeNode[], level: number = 0): React.ReactNode => {
    return nodes.map(node => {
      const isExpanded = expandedNodes.includes(node.id);
      const isSelected = selectedClass?.id === node.id;
      // Check both actual children and hasChildren flag for lazy loading
      const hasChildren = (node.children && node.children.length > 0) || (node as any).hasChildren;
      const displayLabel = node.label || node.id.split('#').pop() || node.id;
      const isAlreadySelected = selectedDomains.includes(node.id);

      return (
        <div key={node.id}>
          <div
            className={`flex items-center gap-1 px-1 py-1 cursor-pointer transition-colors select-none ${
              isSelected 
                ? 'bg-blue-600 text-white' 
                : isAlreadySelected
                  ? 'bg-gray-100 text-gray-400'
                  : 'hover:bg-gray-100 text-gray-900'
            }`}
            style={{ paddingLeft: `${level * 16 + 4}px` }}
            onClick={() => !isAlreadySelected && setSelectedClass(node)}
            onDoubleClick={() => {
              if (!isAlreadySelected) {
                setSelectedClass(node);
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

            {/* Class Icon (yellow circle like Protégé) */}
            <span className={`w-3 h-3 rounded-full flex-shrink-0 ${
              isSelected ? 'bg-amber-300' : 'bg-amber-400'
            }`} />

            {/* Class Label */}
            <span className={`text-sm truncate ${isSelected ? 'font-medium' : ''}`}>
              {displayLabel}
              {isAlreadySelected && <span className="ml-1 text-xs">(already selected)</span>}
            </span>
          </div>

          {/* Children */}
          {isExpanded && node.children && node.children.length > 0 && (
            <div>{renderClassTree(node.children!, level + 1)}</div>
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
            <span className="w-4 h-4 bg-orange-500 rounded-sm" />
            <h3 className="text-sm font-medium text-gray-900">{title}</h3>
          </div>
          <button
            onClick={handleClose}
            className="text-gray-500 hover:text-gray-700 p-1 hover:bg-gray-200 rounded transition-colors"
          >
            <X size={18} />
          </button>
        </div>

        {/* Toolbar - Protégé style: Add subclass, Add sibling, Delete, Filter dropdown */}
        {showToolbar && (
          <div className="flex items-center gap-1 px-2 py-1.5 border-b bg-gray-50">
            {/* Add Subclass Button */}
            {onAddSubclass && (
              <button
                onClick={() => selectedClass && onAddSubclass(selectedClass.id)}
                disabled={!selectedClass}
                title="Add subclass"
                className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Plus size={12} />
                <ListTree size={12} />
                <span className="sr-only">Add subclass</span>
              </button>
            )}
            
            {/* Add Sibling Button */}
            {onAddSiblingClass && (
              <button
                onClick={() => selectedClass && onAddSiblingClass(selectedClass.id)}
                disabled={!selectedClass}
                title="Add sibling class"
                className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Plus size={12} />
                <span className="w-2.5 h-2.5 rounded-full bg-amber-400 flex-shrink-0" />
                <span className="sr-only">Add sibling class</span>
              </button>
            )}
            
            {/* Delete Class Button */}
            {onDeleteClass && (
              <button
                onClick={() => selectedClass && onDeleteClass(selectedClass.id)}
                disabled={!selectedClass}
                title="Delete class"
                className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-red-600 bg-white border border-gray-300 rounded hover:bg-red-50 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Trash2 size={12} />
                <span className="sr-only">Delete class</span>
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
            onClick={() => setActiveTab('select-class')}
            className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
              activeTab === 'select-class'
                ? 'border-orange-600 text-orange-700 bg-white'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
            }`}
          >
            Select Class
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
          {activeTab === 'select-class' && (
            <>
              {/* Search */}
              <div className="px-3 py-2 border-b bg-gray-50">
                <div className="relative">
                  <Search className="absolute left-2.5 top-1/2 transform -translate-y-1/2 text-gray-400" size={14} />
                  <input
                    type="text"
                    placeholder="Search classes..."
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    className="w-full pl-8 pr-3 py-1.5 text-sm border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-orange-500 focus:border-orange-500"
                  />
                </div>
              </div>

              {/* Class Hierarchy */}
              <div className="flex-1 overflow-y-auto p-2 bg-white">
                {filteredTreeData.length > 0 ? (
                  renderClassTree(filteredTreeData)
                ) : (
                  <div className="text-sm text-gray-500 text-center py-4">
                    {searchQuery ? 'No classes match your search' : 'No classes available'}
                  </div>
                )}
              </div>

              {/* Selected Class Display */}
              {selectedClass && (
                <div className="px-3 py-2 border-t bg-gray-50 text-xs">
                  <span className="text-gray-500">Selected: </span>
                  <span className="font-mono text-gray-700">{selectedClass.label || selectedClass.id}</span>
                </div>
              )}
            </>
          )}

          {activeTab === 'edit-iri' && (
            <div className="flex-1 p-4 flex flex-col">
              <label className="text-sm font-medium text-gray-700 mb-2">
                Enter IRI for domain:
              </label>
              <input
                type="text"
                placeholder="http://example.org/ontology#ClassName"
                value={rawIri}
                onChange={(e) => setRawIri(e.target.value)}
                className="w-full px-3 py-2 text-sm font-mono border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-orange-500 focus:border-orange-500"
              />
              <p className="mt-2 text-xs text-gray-500">
                Enter a valid IRI to restrict this annotation property to entities of this type.
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

export default AnnotationPropertyDomainDialog;

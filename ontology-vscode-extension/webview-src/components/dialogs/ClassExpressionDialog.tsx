import React, { useState, useEffect } from 'react';
import { X } from 'lucide-react';
import EntityHierarchy from '../EntityHierarchy';
import ontologyMutationService from '../../services/ontologyMutationService';
import type { TreeNode, Property } from '../../types';

// Structured data for object/data restrictions
export interface RestrictionData {
  type: 'objectRestriction' | 'dataRestriction';
  axiomType: 'EquivalentTo' | 'SubClassOf';
  propertyIri: string;
  restrictionType: 'some' | 'only' | 'min' | 'max' | 'exactly' | 'value';
  fillerIri: string; // Class IRI for object restrictions, datatype IRI for data restrictions
  cardinality?: number;
}

interface ClassExpressionDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (expression: string, restrictionData?: RestrictionData) => void;
  classHierarchy: TreeNode[];
  objectProperties: Property[];
  dataProperties: Property[];
  title?: string;
  initialValue?: string;
  projectId?: string;
  expandedNodes?: string[];
  onToggleNode?: (nodeId: string) => void;
  onAddClass?: (type: 'subclass' | 'sibling') => void;
  onDeleteClass?: () => void;
  // NEW: Optional property hierarchies as TreeNode[] if they have structure
  objectPropertiesTree?: TreeNode[];
  dataPropertiesTree?: TreeNode[];
  // NEW: Property toggle handlers for loading children
  onToggleObjectProperty?: (nodeId: string) => void;
  onToggleDataProperty?: (nodeId: string) => void;
  // NEW: Callbacks for refreshing data after mutations
  onRefreshClasses?: () => void;
  onRefreshProperties?: () => void;
  // NEW: Metadata for generating IRIs
  metadata?: { ontologyIRI?: string };
}

type TabType = 'hierarchy' | 'objectRestriction' | 'classExpression' | 'dataRestriction';

/**
 * ClassExpressionDialog - Protégé desktop-style class expression builder
 *
 * Matches Protégé desktop UI with:
 * - EntityHierarchy for all tree views (classes, properties)
 * - Asserted/Inferred toggles
 * - Compact two-panel layouts for restrictions
 * - Professional toolbar integration
 */
const ClassExpressionDialog: React.FC<ClassExpressionDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  classHierarchy,
  objectProperties,
  dataProperties,
  title = "Class Expression Editor",
  initialValue = "",
  projectId,
  expandedNodes = [],
  onToggleNode,
  onAddClass,
  onDeleteClass,
  objectPropertiesTree: externalObjectPropertiesTree,
  dataPropertiesTree: externalDataPropertiesTree,
  onToggleObjectProperty,
  onToggleDataProperty,
  onRefreshClasses,
  onRefreshProperties,
  metadata
}) => {
  const [activeTab, setActiveTab] = useState<TabType>('hierarchy');

  // Class hierarchy state
  const [selectedClass, setSelectedClass] = useState<TreeNode | null>(null);
  const [classSearchQuery, setClassSearchQuery] = useState('');
  const [localExpandedNodes, setLocalExpandedNodes] = useState<string[]>([]);
  
  // Selected items for restriction panels
  const [selectedFillerClass, setSelectedFillerClass] = useState<TreeNode | null>(null);

  // Object Restriction state
  const [selectedProperty, setSelectedProperty] = useState<Property | null>(null);
  const [restrictionType, setRestrictionType] = useState<'some' | 'only' | 'min' | 'max' | 'exactly' | 'value'>('some');
  const [cardinality, setCardinality] = useState(1);
  const [restrictionFiller, setRestrictionFiller] = useState<TreeNode | null>(null);
  const [fillerSearchQuery, setFillerSearchQuery] = useState('');
  const [propertyExpandedNodes, setPropertyExpandedNodes] = useState<string[]>([]);
  const [fillerExpandedNodes, setFillerExpandedNodes] = useState<string[]>([]);

  // Data Restriction state
  const [selectedDataProperty, setSelectedDataProperty] = useState<Property | null>(null);
  const [dataRestrictionType, setDataRestrictionType] = useState<'some' | 'only' | 'min' | 'max' | 'exactly' | 'value'>('some');
  const [dataCardinality, setDataCardinality] = useState(1);
  const [datatype, setDatatype] = useState('xsd:string');
  const [dataPropertyExpandedNodes, setDataPropertyExpandedNodes] = useState<string[]>([]);

  // Class Expression (Manchester) state
  const [manchesterExpression, setManchesterExpression] = useState(initialValue);

  // Inline class creation state
  const [showInlineCreate, setShowInlineCreate] = useState(false);
  const [inlineCreateType, setInlineCreateType] = useState<'subclass' | 'sibling'>('subclass');
  const [inlineClassName, setInlineClassName] = useState('');
  const [isCreatingClass, setIsCreatingClass] = useState(false);

  // Inline class deletion state
  const [showInlineDelete, setShowInlineDelete] = useState(false);
  const [isDeletingClass, setIsDeletingClass] = useState(false);

  // Reset state when dialog opens with initialValue
  useEffect(() => {
    if (isOpen) {
      setManchesterExpression(initialValue);
      if (initialValue) {
        setActiveTab('classExpression');
      }
    }
  }, [isOpen, initialValue]);

  // Convert flat property list to tree structure with top property
  const propertiesToTree = (properties: Property[], isDataProperty: boolean = false): TreeNode[] => {
    const topPropertyIri = isDataProperty
      ? 'http://www.w3.org/2002/07/owl#topDataProperty'
      : 'http://www.w3.org/2002/07/owl#topObjectProperty';

    const topPropertyLabel = isDataProperty
      ? 'owl:topDataProperty'
      : 'owl:topObjectProperty';

    // If no properties provided, create just the top property
    if (properties.length === 0) {
      return [{
        id: topPropertyIri,
        label: topPropertyLabel,
        hasChildren: false,
        children: []
      }];
    }

    // Build a map of properties by ID for quick lookup
    const propMap = new Map<string, Property>();
    properties.forEach(prop => propMap.set(prop.id, prop));

    // Build children map: parentId -> child properties
    const childrenMap = new Map<string, Property[]>();

    properties.forEach(prop => {
      if (prop.superProperties && prop.superProperties.length > 0) {
        // This property has parents, add it as a child to each parent
        prop.superProperties.forEach(parentId => {
          if (!childrenMap.has(parentId)) {
            childrenMap.set(parentId, []);
          }
          childrenMap.get(parentId)!.push(prop);
        });
      } else {
        // No superProperties means it's a direct child of top property
        if (!childrenMap.has(topPropertyIri)) {
          childrenMap.set(topPropertyIri, []);
        }
        childrenMap.get(topPropertyIri)!.push(prop);
      }
    });

    // Recursive function to build tree nodes
    const buildNode = (prop: Property): TreeNode => {
      const children = childrenMap.get(prop.id) || [];
      return {
        id: prop.id,
        label: prop.label,
        hasChildren: children.length > 0,
        children: children.map(buildNode)
      };
    };

    // Build top property node
    const topPropertyChildren = childrenMap.get(topPropertyIri) || [];

    const result = [{
      id: topPropertyIri,
      label: topPropertyLabel,
      hasChildren: topPropertyChildren.length > 0,
      children: topPropertyChildren.map(buildNode)
    }];

    return result;
  };

  const buildObjectRestriction = (): string => {
    if (!selectedProperty || !restrictionFiller) return '';

    const propName = selectedProperty.label;
    const fillerName = restrictionFiller.label;

    switch (restrictionType) {
      case 'some':
        return `${propName} some ${fillerName}`;
      case 'only':
        return `${propName} only ${fillerName}`;
      case 'min':
        return `${propName} min ${cardinality} ${fillerName}`;
      case 'max':
        return `${propName} max ${cardinality} ${fillerName}`;
      case 'exactly':
        return `${propName} exactly ${cardinality} ${fillerName}`;
      case 'value':
        return `${propName} value ${fillerName}`;
      default:
        return '';
    }
  };

  const buildDataRestriction = (): string => {
    if (!selectedDataProperty) return '';

    const propName = selectedDataProperty.label;

    switch (dataRestrictionType) {
      case 'some':
        return `${propName} some ${datatype}`;
      case 'only':
        return `${propName} only ${datatype}`;
      case 'min':
        return `${propName} min ${dataCardinality} ${datatype}`;
      case 'max':
        return `${propName} max ${dataCardinality} ${datatype}`;
      case 'exactly':
        return `${propName} exactly ${dataCardinality} ${datatype}`;
      default:
        return '';
    }
  };

  const handleConfirm = () => {
    let expression = '';
    let restrictionData: RestrictionData | undefined = undefined;

    switch (activeTab) {
      case 'hierarchy':
        if (selectedClass) expression = selectedClass.id;
        break;
      case 'objectRestriction':
        expression = buildObjectRestriction();
        // Also build structured restriction data for backend
        if (selectedProperty && restrictionFiller) {
          restrictionData = {
            type: 'objectRestriction',
            axiomType: 'SubClassOf', // Default - caller can change this if needed
            propertyIri: selectedProperty.id,
            restrictionType: restrictionType,
            fillerIri: restrictionFiller.id,
            cardinality: ['min', 'max', 'exactly'].includes(restrictionType) ? cardinality : undefined
          };
        }
        break;
      case 'classExpression':
        expression = manchesterExpression.trim();
        break;
      case 'dataRestriction':
        expression = buildDataRestriction();
        // Also build structured restriction data for backend
        if (selectedDataProperty) {
          restrictionData = {
            type: 'dataRestriction',
            axiomType: 'SubClassOf', // Default - caller can change this if needed
            propertyIri: selectedDataProperty.id,
            restrictionType: dataRestrictionType,
            fillerIri: datatype.startsWith('http://') ? datatype : `http://www.w3.org/2001/XMLSchema#${datatype.replace('xsd:', '')}`,
            cardinality: ['min', 'max', 'exactly'].includes(dataRestrictionType) ? dataCardinality : undefined
          };
        }
        break;
    }

    if (expression) {
      onConfirm(expression, restrictionData);
      handleClose();
    }
  };

  const handleClose = () => {
    // Reset all state
    setSelectedClass(null);
    setSelectedProperty(null);
    setRestrictionFiller(null);
    setSelectedDataProperty(null);
    setManchesterExpression('');
    setClassSearchQuery('');
    setFillerSearchQuery('');
    setActiveTab('hierarchy');
    // Reset inline create state
    setShowInlineCreate(false);
    setInlineClassName('');
    // Reset inline delete state
    setShowInlineDelete(false);
    onClose();
  };

  // Handle toggle for hierarchy tab
  const handleHierarchyToggle = async (nodeId: string) => {
    if (onToggleNode) {
      await onToggleNode(nodeId);
    } else {
      // Fallback to local state
      const isExpanded = localExpandedNodes.includes(nodeId);
      setLocalExpandedNodes(
        isExpanded
          ? localExpandedNodes.filter(id => id !== nodeId)
          : [...localExpandedNodes, nodeId]
      );
    }
  };

  // Handle toggle for object properties
  const handleObjectPropertyToggle = async (nodeId: string) => {
    if (onToggleObjectProperty) {
      await onToggleObjectProperty(nodeId);
    }
    // Also update local expanded state
    const isExpanded = propertyExpandedNodes.includes(nodeId);
    setPropertyExpandedNodes(
      isExpanded
        ? propertyExpandedNodes.filter(id => id !== nodeId)
        : [...propertyExpandedNodes, nodeId]
    );
  };

  // Handle toggle for data properties
  const handleDataPropertyToggle = async (nodeId: string) => {
    if (onToggleDataProperty) {
      await onToggleDataProperty(nodeId);
    }
    // Also update local expanded state
    const isExpanded = dataPropertyExpandedNodes.includes(nodeId);
    setDataPropertyExpandedNodes(
      isExpanded
        ? dataPropertyExpandedNodes.filter(id => id !== nodeId)
        : [...dataPropertyExpandedNodes, nodeId]
    );
  };

  // Handle toggle for restriction filler
  const handleFillerToggle = async (nodeId: string) => {
    if (onToggleNode) {
      // Use parent's toggle if available
      await onToggleNode(nodeId);
      // Also update local state for this panel
      const isExpanded = fillerExpandedNodes.includes(nodeId);
      setFillerExpandedNodes(
        isExpanded
          ? fillerExpandedNodes.filter(id => id !== nodeId)
          : [...fillerExpandedNodes, nodeId]
      );
    } else {
      // Fallback to local state only
      const isExpanded = fillerExpandedNodes.includes(nodeId);
      setFillerExpandedNodes(
        isExpanded
          ? fillerExpandedNodes.filter(id => id !== nodeId)
          : [...fillerExpandedNodes, nodeId]
      );
    }
  };

  // Helper to find parent of a node in the hierarchy
  const findParentNode = (nodes: TreeNode[], targetId: string, parent: TreeNode | null = null): TreeNode | null => {
    for (const node of nodes) {
      if (node.id === targetId) return parent;
      if (node.children && node.children.length > 0) {
        const found = findParentNode(node.children, targetId, node);
        if (found !== null) return found;
      }
    }
    return null;
  };

  // Handle inline class creation
  const handleInlineAddClass = (type: 'subclass' | 'sibling') => {
    setInlineCreateType(type);
    setInlineClassName('');
    setShowInlineCreate(true);
  };

  // Submit inline class creation
  const handleInlineCreateSubmit = async () => {
    if (!inlineClassName.trim() || !projectId) return;
    
    setIsCreatingClass(true);
    try {
      let parentIri = 'http://www.w3.org/2002/07/owl#Thing';
      
      if (inlineCreateType === 'subclass' && selectedClass) {
        parentIri = selectedClass.id;
      } else if (inlineCreateType === 'sibling' && selectedClass) {
        const parent = findParentNode(classHierarchy, selectedClass.id);
        parentIri = parent?.id || 'http://www.w3.org/2002/07/owl#Thing';
      }
      
      // Generate IRI from class name
      const baseIri = metadata?.ontologyIRI || 'http://example.org/ontology#';
      const cleanName = inlineClassName.trim().replace(/\s+/g, '_');
      const newClassIri = baseIri.endsWith('#') || baseIri.endsWith('/') 
        ? `${baseIri}${cleanName}` 
        : `${baseIri}#${cleanName}`;
      
      // Ensure parent node is expanded so new class will be visible
      // Add parent to local expanded nodes if not already expanded
      if (!localExpandedNodes.includes(parentIri)) {
        setLocalExpandedNodes(prev => [...prev, parentIri]);
      }
      // Also trigger parent's toggle to ensure it's expanded in external state
      if (onToggleNode && !expandedNodes.includes(parentIri)) {
        await onToggleNode(parentIri);
      }
      
      // Create the class via the mutation service
      await ontologyMutationService.createClass(
        projectId,
        newClassIri,
        inlineClassName.trim(),
        parentIri,
        'anonymous',
        'Anonymous'
      );
      
      // Refresh the class hierarchy
      if (onRefreshClasses) {
        onRefreshClasses();
      }
      
      // Reset inline create state
      setShowInlineCreate(false);
      setInlineClassName('');
    } catch (error) {
      console.error('Failed to create class:', error);
    } finally {
      setIsCreatingClass(false);
    }
  };

  // Cancel inline creation
  const handleInlineCreateCancel = () => {
    setShowInlineCreate(false);
    setInlineClassName('');
  };

  // Show inline delete confirmation
  const handleInlineDeleteStart = () => {
    if (!selectedClass || selectedClass.id.includes('Thing')) return;
    setShowInlineDelete(true);
  };

  // Confirm and execute inline delete
  const handleInlineDeleteConfirm = async () => {
    if (!selectedClass || !projectId) return;
    
    setIsDeletingClass(true);
    try {
      await ontologyMutationService.deleteClass(
        projectId,
        selectedClass.id,
        'anonymous',
        'Anonymous'
      );
      
      // Clear selection and hide confirmation
      setSelectedClass(null);
      setShowInlineDelete(false);
      
      // Refresh the class hierarchy
      if (onRefreshClasses) {
        onRefreshClasses();
      }
    } catch (error) {
      console.error('Failed to delete class:', error);
    } finally {
      setIsDeletingClass(false);
    }
  };

  // Cancel inline delete
  const handleInlineDeleteCancel = () => {
    setShowInlineDelete(false);
  };

  const datatypes = [
    'owl:rational',
    'owl:real',
    'rdf:langString',
    'rdf:PlainLiteral',
    'rdf:XMLLiteral',
    'rdfs:Literal',
    'xsd:anyURI',
    'xsd:base64Binary',
    'xsd:boolean',
    'xsd:byte',
    'xsd:date',
    'xsd:dateTime',
    'xsd:dateTimeStamp',
    'xsd:decimal',
    'xsd:double',
    'xsd:float',
    'xsd:int',
    'xsd:integer',
    'xsd:long',
    'xsd:string'
  ];

  const restrictionTypes = [
    { value: 'some', label: 'Some (existential)' },
    { value: 'only', label: 'Only (universal)' },
    { value: 'min', label: 'Min (minimum cardinality)' },
    { value: 'max', label: 'Max (maximum cardinality)' },
    { value: 'exactly', label: 'Exactly (exact cardinality)' },
    { value: 'value', label: 'Value (has value)' }
  ];

  const manchesterKeywords = ['and', 'or', 'not', 'some', 'only', 'min', 'max', 'exactly', 'value'];

  const isOkEnabled =
    (activeTab === 'hierarchy' && selectedClass) ||
    (activeTab === 'objectRestriction' && selectedProperty && restrictionFiller) ||
    (activeTab === 'classExpression' && manchesterExpression.trim()) ||
    (activeTab === 'dataRestriction' && selectedDataProperty);

  if (!isOpen) return null;

  // Use external property trees if provided, otherwise convert from flat list
  const objectPropertiesTree = externalObjectPropertiesTree || propertiesToTree(objectProperties, false);
  const dataPropertiesTree = externalDataPropertiesTree || propertiesToTree(dataProperties, true);

  // Use parent's expanded nodes if available, otherwise use local
  const effectiveExpandedNodes = onToggleNode ? expandedNodes : localExpandedNodes;
  const effectiveFillerExpandedNodes = onToggleNode ? [...expandedNodes, ...fillerExpandedNodes] : fillerExpandedNodes;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-7xl mx-4 flex flex-col h-[90vh]">
        {/* Header */}
        <div className="px-6 py-3 border-b border-gray-300 flex justify-between items-center bg-gray-50">
          <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
          <button
            onClick={handleClose}
            className="text-gray-400 hover:text-gray-600 transition-colors"
            title="Close"
          >
            <X size={20} />
          </button>
        </div>

        {/* Tabs */}
        <div className="flex border-b border-gray-300 bg-gray-100">
          <button
            onClick={() => setActiveTab('hierarchy')}
            className={`px-4 py-2 text-sm font-medium transition-colors ${
              activeTab === 'hierarchy'
                ? 'bg-white text-gray-900 border-t-2 border-t-blue-500'
                : 'text-gray-600 hover:text-gray-900 hover:bg-gray-200'
            }`}
          >
            Class hierarchy
          </button>
          <button
            onClick={() => setActiveTab('objectRestriction')}
            className={`px-4 py-2 text-sm font-medium transition-colors ${
              activeTab === 'objectRestriction'
                ? 'bg-white text-gray-900 border-t-2 border-t-blue-500'
                : 'text-gray-600 hover:text-gray-900 hover:bg-gray-200'
            }`}
          >
            Object restriction creator
          </button>
          <button
            onClick={() => setActiveTab('classExpression')}
            className={`px-4 py-2 text-sm font-medium transition-colors ${
              activeTab === 'classExpression'
                ? 'bg-white text-gray-900 border-t-2 border-t-blue-500'
                : 'text-gray-600 hover:text-gray-900 hover:bg-gray-200'
            }`}
          >
            Class expression editor
          </button>
          <button
            onClick={() => setActiveTab('dataRestriction')}
            className={`px-4 py-2 text-sm font-medium transition-colors ${
              activeTab === 'dataRestriction'
                ? 'bg-white text-gray-900 border-t-2 border-t-blue-500'
                : 'text-gray-600 hover:text-gray-900 hover:bg-gray-200'
            }`}
          >
            Data restriction creator
          </button>
        </div>

        {/* Content Area */}
        <div className="flex-1 overflow-hidden min-h-0 bg-white">
          {/* Class Hierarchy Tab */}
          {activeTab === 'hierarchy' && (
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
              
              {/* Inline Delete Confirmation */}
              {showInlineDelete && selectedClass && (
                <div className="px-3 py-2 bg-red-50 border-b border-red-200">
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-red-800 font-medium">
                      Delete "{selectedClass.label}"?
                    </span>
                    <span className="flex-1" />
                    <button
                      onClick={handleInlineDeleteConfirm}
                      disabled={isDeletingClass}
                      className="px-3 py-1 text-xs bg-red-600 text-white rounded hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {isDeletingClass ? 'Deleting...' : 'Delete'}
                    </button>
                    <button
                      onClick={handleInlineDeleteCancel}
                      disabled={isDeletingClass}
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
                  filteredData={classHierarchy}
                  selectedItem={selectedClass}
                  expandedNodes={effectiveExpandedNodes}
                  searchQuery={classSearchQuery}
                  onSearchQueryChange={setClassSearchQuery}
                  onSelectItem={(item) => setSelectedClass(item as TreeNode)}
                  onToggleNode={handleHierarchyToggle}
                  onAddItem={projectId ? (type) => handleInlineAddClass(type as 'subclass' | 'sibling') : () => {}}
                  onDeleteItem={projectId ? handleInlineDeleteStart : () => {}}
                  hideToolbarActions={!projectId}
                />
              </div>
            </div>
          )}

          {/* Object Restriction Creator Tab */}
          {activeTab === 'objectRestriction' && (
            <div className="h-full flex">
              {/* LEFT: Restricted property - Uses EntityHierarchy */}
              <div className="w-1/2 border-r border-gray-300 flex flex-col">
                <div className="bg-gray-100 px-3 py-2 border-b border-gray-300">
                  <h4 className="text-sm font-semibold text-gray-700">Restricted property</h4>
                </div>
                <div className="flex-1 overflow-hidden">
                  <EntityHierarchy
                    entitiesTab="ObjectProperties"
                    filteredData={objectPropertiesTree}
                    selectedItem={selectedProperty as any}
                    expandedNodes={propertyExpandedNodes}
                    searchQuery=""
                    onSearchQueryChange={() => {}}
                    onSelectItem={(item) => setSelectedProperty(item as any as Property)}
                    onToggleNode={handleObjectPropertyToggle}
                    onAddItem={() => {}}
                    onDeleteItem={() => {}}
                    hideToolbarActions={true}
                  />
                </div>
              </div>

              {/* RIGHT: Restriction filler - Uses EntityHierarchy */}
              <div className="w-1/2 flex flex-col">
                <div className="bg-gray-100 px-3 py-2 border-b border-gray-300">
                  <h4 className="text-sm font-semibold text-gray-700">Restriction filler</h4>
                </div>
                <div className="flex-1 overflow-hidden">
                  <EntityHierarchy
                    entitiesTab="Classes"
                    filteredData={classHierarchy}
                    selectedItem={restrictionFiller}
                    expandedNodes={effectiveFillerExpandedNodes}
                    searchQuery={fillerSearchQuery}
                    onSearchQueryChange={setFillerSearchQuery}
                    onSelectItem={(item) => setRestrictionFiller(item as TreeNode)}
                    onToggleNode={handleFillerToggle}
                    onAddItem={() => {}}
                    onDeleteItem={() => {}}
                    hideToolbarActions={true}
                  />
                </div>
              </div>
            </div>
          )}

          {/* Class Expression Editor Tab */}
          {activeTab === 'classExpression' && (
            <div className="h-full p-6 flex flex-col">
              <div className="flex-1 flex flex-col min-h-0">
                <label className="text-sm font-semibold text-gray-700 mb-2">Manchester OWL Syntax</label>
                <textarea
                  value={manchesterExpression}
                  onChange={(e) => setManchesterExpression(e.target.value)}
                  placeholder="e.g., Cell and hasPart some Nucleus&#10;      Person and hasAge some xsd:integer&#10;      hasSibling min 2 Person"
                  className="flex-1 p-4 border border-gray-300 rounded font-mono text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
                />
              </div>
              <div className="mt-4 p-4 bg-blue-50 rounded border border-blue-200">
                <p className="text-xs font-semibold text-blue-900 mb-2">KEYWORDS</p>
                <div className="flex flex-wrap gap-2 mb-3">
                  {manchesterKeywords.map(kw => (
                    <button
                      key={kw}
                      onClick={() => setManchesterExpression(prev => prev + (prev ? ' ' : '') + kw + ' ')}
                      className="px-3 py-1 bg-white hover:bg-blue-100 text-blue-900 text-xs font-mono rounded border border-blue-300 transition-colors"
                    >
                      {kw}
                    </button>
                  ))}
                </div>
                <p className="text-xs font-semibold text-blue-900 mb-1.5">EXAMPLES</p>
                <ul className="text-xs text-blue-800 space-y-1">
                  <li className="font-mono">• Cell and hasPart some Nucleus</li>
                  <li className="font-mono">• Person and hasAge some xsd:integer</li>
                  <li className="font-mono">• hasSibling min 2 Person</li>
                  <li className="font-mono">• (Cat or Dog) and hasFur value true</li>
                </ul>
              </div>
            </div>
          )}

          {/* Data Restriction Creator Tab */}
          {activeTab === 'dataRestriction' && (
            <div className="h-full flex">
              {/* LEFT: Restricted property - Uses EntityHierarchy */}
              <div className="w-1/2 border-r border-gray-300 flex flex-col">
                <div className="bg-gray-100 px-3 py-2 border-b border-gray-300">
                  <h4 className="text-sm font-semibold text-gray-700">Restricted property</h4>
                </div>
                <div className="flex-1 overflow-hidden">
                  <EntityHierarchy
                    entitiesTab="DataProperties"
                    filteredData={dataPropertiesTree}
                    selectedItem={selectedDataProperty as any}
                    expandedNodes={dataPropertyExpandedNodes}
                    searchQuery=""
                    onSearchQueryChange={() => {}}
                    onSelectItem={(item) => setSelectedDataProperty(item as any as Property)}
                    onToggleNode={handleDataPropertyToggle}
                    onAddItem={() => {}}
                    onDeleteItem={() => {}}
                    hideToolbarActions={true}
                  />
                </div>
              </div>

              {/* RIGHT: Restriction filler (Datatypes) */}
              <div className="w-1/2 flex flex-col">
                <div className="bg-gray-100 px-3 py-2 border-b border-gray-300">
                  <h4 className="text-sm font-semibold text-gray-700">Restriction filler</h4>
                </div>
                <div className="flex-1 overflow-y-auto">
                  {datatypes.map(dt => (
                    <div
                      key={dt}
                      onClick={() => setDatatype(dt)}
                      className={`flex items-center gap-2 px-3 py-1.5 cursor-pointer transition-colors text-sm border-b border-gray-100 ${
                        datatype === dt ? 'bg-red-50 font-semibold' : 'hover:bg-gray-50'
                      }`}
                    >
                      <span className={`w-3.5 h-3.5 rounded-sm border flex-shrink-0 ${
                        datatype === dt ? 'bg-red-600 border-red-700' : 'bg-red-400 border-red-600'
                      }`} />
                      <span className="font-mono text-xs">{dt}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Restriction Type Controls - Bottom panel for restriction tabs */}
        {(activeTab === 'objectRestriction' || activeTab === 'dataRestriction') && (
          <div className="px-4 py-3 bg-gray-50 border-t border-gray-300">
            <div className="flex items-center gap-4">
              <label className="text-sm font-semibold text-gray-700">Restriction type</label>
              <select
                value={activeTab === 'objectRestriction' ? restrictionType : dataRestrictionType}
                onChange={(e) => {
                  const val = e.target.value as any;
                  if (activeTab === 'objectRestriction') {
                    setRestrictionType(val);
                  } else {
                    setDataRestrictionType(val);
                  }
                }}
                className="flex-1 max-w-xs px-3 py-2 text-sm border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                {restrictionTypes
                  .filter(t => activeTab === 'dataRestriction' ? t.value !== 'value' : true)
                  .map(type => (
                    <option key={type.value} value={type.value}>{type.label}</option>
                  ))}
              </select>

              {((activeTab === 'objectRestriction' && (restrictionType === 'min' || restrictionType === 'max' || restrictionType === 'exactly')) ||
                (activeTab === 'dataRestriction' && (dataRestrictionType === 'min' || dataRestrictionType === 'max' || dataRestrictionType === 'exactly'))) && (
                <>
                  <label className="text-sm font-semibold text-gray-700">Cardinality</label>
                  <input
                    type="number"
                    min="0"
                    value={activeTab === 'objectRestriction' ? cardinality : dataCardinality}
                    onChange={(e) => {
                      const val = parseInt(e.target.value) || 0;
                      if (activeTab === 'objectRestriction') {
                        setCardinality(val);
                      } else {
                        setDataCardinality(val);
                      }
                    }}
                    className="w-24 px-3 py-2 text-sm border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </>
              )}
            </div>
          </div>
        )}

        {/* Footer */}
        <div className="px-6 py-3 border-t border-gray-300 flex justify-end gap-3 bg-gray-50">
          <button
            onClick={handleClose}
            className="px-5 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-100 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleConfirm}
            disabled={!isOkEnabled}
            className="px-5 py-2 text-sm font-medium text-white bg-blue-600 rounded hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            OK
          </button>
        </div>
      </div>
    </div>
  );
};

export default ClassExpressionDialog;

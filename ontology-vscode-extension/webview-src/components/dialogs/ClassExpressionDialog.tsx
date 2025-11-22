import React, { useState, useEffect } from 'react';
import { X } from 'lucide-react';
import EntityHierarchy from '../EntityHierarchy';
import type { TreeNode, Property } from '../../types';

interface ClassExpressionDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (expression: string) => void;
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
  onToggleDataProperty
}) => {
  const [activeTab, setActiveTab] = useState<TabType>('hierarchy');

  // Class hierarchy state
  const [selectedClass, setSelectedClass] = useState<TreeNode | null>(null);
  const [classSearchQuery, setClassSearchQuery] = useState('');
  const [localExpandedNodes, setLocalExpandedNodes] = useState<string[]>([]);

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

    // Debug logging
    console.log(`[propertiesToTree] ${isDataProperty ? 'Data' : 'Object'} Properties:`, {
      inputCount: properties.length,
      topPropertyChildren: topPropertyChildren.length,
      hasChildren: topPropertyChildren.length > 0,
      firstFewProps: properties.slice(0, 3).map(p => ({ label: p.label, superProperties: p.superProperties })),
      childrenMapKeys: Array.from(childrenMap.keys()),
      result: result[0]
    });

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

    switch (activeTab) {
      case 'hierarchy':
        if (selectedClass) expression = selectedClass.id;
        break;
      case 'objectRestriction':
        expression = buildObjectRestriction();
        break;
      case 'classExpression':
        expression = manchesterExpression.trim();
        break;
      case 'dataRestriction':
        expression = buildDataRestriction();
        break;
    }

    if (expression) {
      onConfirm(expression);
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

  // Debug logging
  console.log('[ClassExpressionDialog] Object Properties Tree Debug:', {
    externalProvided: !!externalObjectPropertiesTree,
    treeLength: objectPropertiesTree.length,
    firstNode: objectPropertiesTree[0],
    hasChildren: objectPropertiesTree[0]?.hasChildren,
    childrenCount: objectPropertiesTree[0]?.children?.length
  });

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
            <div className="h-full">
              <EntityHierarchy
                entitiesTab="Classes"
                filteredData={classHierarchy}
                selectedItem={selectedClass}
                expandedNodes={effectiveExpandedNodes}
                searchQuery={classSearchQuery}
                onSearchQueryChange={setClassSearchQuery}
                onSelectItem={(item) => setSelectedClass(item as TreeNode)}
                onToggleNode={handleHierarchyToggle}
                onAddItem={onAddClass || (() => {})}
                onDeleteItem={onDeleteClass || (() => {})}
              />
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

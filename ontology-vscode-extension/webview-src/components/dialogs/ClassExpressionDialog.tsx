import React, { useState } from 'react';
import { X, Search, ChevronDown, ChevronRight } from 'lucide-react';
import type { TreeNode, Property } from '../../types';

interface ClassExpressionDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (expression: string) => void;
  classHierarchy: TreeNode[];
  objectProperties: Property[];
  dataProperties: Property[];
  title?: string;
  expandedNodes?: string[];
  onToggleNode?: (nodeId: string) => void;
  onCreateProperty?: () => void;
}

type TabType = 'hierarchy' | 'objectRestriction' | 'classExpression' | 'dataRestriction';

const ClassExpressionDialog: React.FC<ClassExpressionDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  classHierarchy,
  objectProperties,
  dataProperties,
  title = "Class Expression Editor",
  expandedNodes: parentExpandedNodes = [],
  onToggleNode: parentToggleNode,
  onCreateProperty
}) => {
  const [activeTab, setActiveTab] = useState<TabType>('hierarchy');
  const [selectedClass, setSelectedClass] = useState<TreeNode | null>(null);
  const [localExpandedNodes, setLocalExpandedNodes] = useState<string[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [loadingNodes, setLoadingNodes] = useState<string[]>([]);
  
  // Use parent's expanded nodes if provided, otherwise use local state
  const expandedNodes = parentToggleNode ? parentExpandedNodes : localExpandedNodes;
  const setExpandedNodes = parentToggleNode ? (() => {}) : setLocalExpandedNodes;
  
  // Object Restriction state
  const [selectedProperty, setSelectedProperty] = useState<Property | null>(null);
  const [restrictionType, setRestrictionType] = useState<'some' | 'only' | 'min' | 'max' | 'exactly' | 'value'>('some');
  const [cardinality, setCardinality] = useState(1);
  const [restrictionFiller, setRestrictionFiller] = useState<TreeNode | null>(null);
  const [propertySearchQuery, setPropertySearchQuery] = useState('');
  const [fillerSearchQuery, setFillerSearchQuery] = useState('');
  
  // Data Restriction state
  const [selectedDataProperty, setSelectedDataProperty] = useState<Property | null>(null);
  const [dataRestrictionType, setDataRestrictionType] = useState<'some' | 'only' | 'min' | 'max' | 'exactly' | 'value'>('some');
  const [dataCardinality, setDataCardinality] = useState(1);
  const [datatype, setDatatype] = useState('xsd:string');
  const [dataPropertySearchQuery, setDataPropertySearchQuery] = useState('');
  
  // Class Expression state
  const [manchesterExpression, setManchesterExpression] = useState('');

  if (!isOpen) return null;

  const toggleNode = async (nodeId: string) => {
    // Use parent's toggle function if provided, otherwise use local logic
    if (parentToggleNode) {
      await parentToggleNode(nodeId);
    } else {
      const isExpanded = localExpandedNodes.includes(nodeId);
      
      if (isExpanded) {
        setLocalExpandedNodes(prev => prev.filter(id => id !== nodeId));
      } else {
        setLocalExpandedNodes(prev => [...prev, nodeId]);
      }
    }
  };

  const renderClassTree = (
    nodes: TreeNode[], 
    level: number, 
    onSelect: (node: TreeNode) => void, 
    selected: TreeNode | null,
    searchQuery: string
  ): React.ReactNode => {
    return nodes
      .filter(node => !searchQuery || node.label.toLowerCase().includes(searchQuery.toLowerCase()))
      .map(node => (
        <div key={node.id}>
          <div
            className={`flex items-center gap-2 px-2 py-2 hover:bg-gray-50 cursor-pointer transition-colors ${
              selected?.id === node.id ? 'bg-gray-100 border-l-3 border-l-gray-700' : ''
            }`}
            style={{ paddingLeft: `${level * 16 + 8}px` }}
          >
            {node.hasChildren ? (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  toggleNode(node.id);
                }}
                className="p-0.5 hover:bg-gray-200 rounded"
              >
                {expandedNodes.includes(node.id) ? (
                  <ChevronDown size={14} className="text-gray-600" />
                ) : (
                  <ChevronRight size={14} className="text-gray-400" />
                )}
              </button>
            ) : (
              <span className="w-5" />
            )}
            <div
              onClick={() => onSelect(node)}
              className="flex items-center gap-2 flex-1"
            >
              <span className={`w-2.5 h-2.5 rounded-full ${
                selected?.id === node.id ? 'bg-gray-700' : 'bg-amber-400'
              }`} />
              <span className={`text-sm ${selected?.id === node.id ? 'font-semibold text-gray-900' : 'text-gray-700'}`}>
                {node.label}
              </span>
            </div>
          </div>
          {expandedNodes.includes(node.id) && node.children && node.children.length > 0 && (
            renderClassTree(node.children, level + 1, onSelect, selected, searchQuery)
          )}
        </div>
      ));
  };

  const renderPropertyTree = (
    properties: Property[],
    onSelect: (prop: Property) => void,
    selected: Property | null,
    searchQuery: string,
    isDataProperty: boolean = false
  ) => {
    return properties
      .filter(prop => !searchQuery || prop.label.toLowerCase().includes(searchQuery.toLowerCase()))
      .map(prop => (
        <div
          key={prop.id}
          onClick={() => onSelect(prop)}
          className={`flex items-center gap-2 px-3 py-2.5 cursor-pointer transition-colors ${
            selected?.id === prop.id 
              ? 'bg-gray-100 border-l-3 border-l-gray-700' 
              : 'hover:bg-gray-50'
          }`}
        >
          <span className={`w-2.5 h-2.5 rounded-sm ${
            selected?.id === prop.id 
              ? 'bg-gray-700'
              : isDataProperty ? 'bg-green-500' : 'bg-blue-500'
          }`} />
          <span className={`text-sm font-mono ${
            selected?.id === prop.id ? 'font-semibold text-gray-900' : 'text-gray-700'
          }`}>
            {prop.label}
          </span>
        </div>
      ));
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
    setSelectedClass(null);
    setSelectedProperty(null);
    setRestrictionFiller(null);
    setSelectedDataProperty(null);
    setManchesterExpression('');
    onClose();
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

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-6xl mx-4 flex flex-col h-[90vh]">
        {/* Header */}
        <div className="px-6 py-4 border-b bg-gray-800 flex justify-between items-center">
          <h3 className="text-lg font-semibold text-white">{title}</h3>
          <button 
            onClick={handleClose} 
            className="text-white hover:bg-gray-700 rounded-lg p-1.5 transition-colors"
          >
            <X size={20} />
          </button>
        </div>

        {/* Tabs */}
        <div className="flex border-b bg-gray-50">
          <button
            onClick={() => setActiveTab('hierarchy')}
            className={`px-5 py-2.5 text-sm font-medium border-b-2 transition-colors ${
              activeTab === 'hierarchy'
                ? 'border-indigo-500 text-indigo-700 bg-white'
                : 'border-transparent text-gray-600 hover:text-gray-800 hover:bg-gray-100'
            }`}
          >
            Class hierarchy
          </button>
          <button
            onClick={() => setActiveTab('objectRestriction')}
            className={`px-5 py-2.5 text-sm font-medium border-b-2 transition-colors ${
              activeTab === 'objectRestriction'
                ? 'border-indigo-500 text-indigo-700 bg-white'
                : 'border-transparent text-gray-600 hover:text-gray-800 hover:bg-gray-100'
            }`}
          >
            Object restriction creator
          </button>
          <button
            onClick={() => setActiveTab('classExpression')}
            className={`px-5 py-2.5 text-sm font-medium border-b-2 transition-colors ${
              activeTab === 'classExpression'
                ? 'border-indigo-500 text-indigo-700 bg-white'
                : 'border-transparent text-gray-600 hover:text-gray-800 hover:bg-gray-100'
            }`}
          >
            Class expression editor
          </button>
          <button
            onClick={() => setActiveTab('dataRestriction')}
            className={`px-5 py-2.5 text-sm font-medium border-b-2 transition-colors ${
              activeTab === 'dataRestriction'
                ? 'border-indigo-500 text-indigo-700 bg-white'
                : 'border-transparent text-gray-600 hover:text-gray-800 hover:bg-gray-100'
            }`}
          >
            Data restriction creator
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-hidden">
          {/* Class Hierarchy Tab */}
          {activeTab === 'hierarchy' && (
            <div className="h-full flex flex-col">
              <div className="p-3 border-b bg-gray-50">
                <div className="flex items-center gap-2 mb-2">
                  <h4 className="text-sm font-semibold text-gray-700">Class hierarchy</h4>
                  <select className="ml-auto px-2 py-1 text-xs border rounded bg-white">
                    <option>Asserted</option>
                    <option>Inferred</option>
                  </select>
                </div>
                <div className="relative">
                  <Search size={16} className="absolute left-3 top-2.5 text-gray-400" />
                  <input
                    type="text"
                    placeholder="Search classes..."
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    className="w-full pl-9 pr-3 py-2 text-sm border border-purple-200 rounded-md focus:outline-none focus:ring-2 focus:ring-purple-500 bg-white"
                  />
                </div>
              </div>
              <div className="flex-1 overflow-y-auto bg-white">
                {classHierarchy.length > 0 ? (
                  renderClassTree(classHierarchy, 0, setSelectedClass, selectedClass, searchQuery)
                ) : (
                  <div className="flex items-center justify-center h-full text-sm text-gray-400 italic">
                    No classes available
                  </div>
                )}
              </div>
            </div>
          )}

          {/* Object Restriction Creator Tab */}
          {activeTab === 'objectRestriction' && (
            <div className="h-full flex flex-col">
              {/* Restriction type - Top panel */}
              <div className="p-4 bg-gray-50 border-b">
                <h4 className="text-xs font-semibold text-gray-600 uppercase tracking-wide mb-2">Restriction type</h4>
                <div className="flex items-center gap-3">
                  <select
                    value={restrictionType}
                    onChange={(e) => setRestrictionType(e.target.value as any)}
                    className="flex-1 px-3 py-2 bg-white border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                  >
                    <option value="some">Some (existential)</option>
                    <option value="only">Only (universal)</option>
                    <option value="min">Min (minimum cardinality)</option>
                    <option value="max">Max (maximum cardinality)</option>
                    <option value="exactly">Exactly (exact cardinality)</option>
                    <option value="value">Value (has value)</option>
                  </select>
                  {(restrictionType === 'min' || restrictionType === 'max' || restrictionType === 'exactly') && (
                    <div className="flex items-center gap-2">
                      <label className="text-sm font-medium text-gray-700">Cardinality</label>
                      <input
                        type="number"
                        min="0"
                        step="1"
                        value={cardinality}
                        onChange={(e) => setCardinality(parseInt(e.target.value) || 0)}
                        className="w-24 px-2 py-1.5 bg-white border border-gray-300 rounded text-sm focus:outline-none focus:ring-2 focus:ring-purple-500"
                      />
                    </div>
                  )}
                </div>
              </div>

              {/* Two-column layout */}
              <div className="flex-1 flex overflow-hidden">
                {/* Restricted property */}
                <div className="w-1/2 border-r flex flex-col bg-white">
                  <div className="p-3 border-b bg-gray-50">
                    <h4 className="text-sm font-semibold text-gray-700 mb-2">Restricted property</h4>
                    <div className="relative">
                      <Search size={16} className="absolute left-3 top-2.5 text-gray-400" />
                      <input
                        type="text"
                        placeholder="Search properties..."
                        value={propertySearchQuery}
                        onChange={(e) => setPropertySearchQuery(e.target.value)}
                        className="w-full pl-9 pr-3 py-2 text-sm border rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                      />
                    </div>
                  </div>
                  <div className="flex-1 overflow-y-auto">
                    {objectProperties.length > 0 ? (
                      renderPropertyTree(objectProperties, setSelectedProperty, selectedProperty, propertySearchQuery, false)
                    ) : (
                      <div className="flex flex-col">
                        <div
                          onClick={() => setSelectedProperty({ id: 'http://www.w3.org/2002/07/owl#topObjectProperty', label: 'owl:topObjectProperty', type: 'ObjectProperty' } as Property)}
                          className={`flex items-center gap-2 px-3 py-2.5 cursor-pointer transition-colors ${
                            selectedProperty?.id === 'http://www.w3.org/2002/07/owl#topObjectProperty'
                              ? 'bg-gray-100 border-l-3 border-l-gray-700'
                              : 'hover:bg-gray-50'
                          }`}
                        >
                          <span className="w-2.5 h-2.5 rounded-sm bg-blue-500" />
                          <span className="text-sm font-mono text-gray-700">owl:topObjectProperty</span>
                        </div>
                        {onCreateProperty && (
                          <div className="p-4 text-center">
                            <button
                              onClick={onCreateProperty}
                              className="px-4 py-2 text-sm text-blue-600 hover:text-blue-700 border border-blue-300 hover:border-blue-400 rounded-md hover:bg-blue-50 transition-colors"
                            >
                              Create new object property
                            </button>
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                </div>

                {/* Restriction filler */}
                <div className="w-1/2 flex flex-col bg-white">
                  <div className="p-3 border-b bg-gray-50">
                    <h4 className="text-sm font-semibold text-gray-700 mb-2">Restriction filler</h4>
                    <div className="relative">
                      <Search size={16} className="absolute left-3 top-2.5 text-gray-400" />
                      <input
                        type="text"
                        placeholder="Search classes..."
                        value={fillerSearchQuery}
                        onChange={(e) => setFillerSearchQuery(e.target.value)}
                        className="w-full pl-9 pr-3 py-2 text-sm border border-amber-200 rounded-md focus:outline-none focus:ring-2 focus:ring-amber-500"
                      />
                    </div>
                  </div>
                  <div className="flex-1 overflow-y-auto">
                    {classHierarchy.length > 0 ? (
                      renderClassTree(classHierarchy, 0, setRestrictionFiller, restrictionFiller, fillerSearchQuery)
                    ) : (
                      <div className="flex items-center justify-center h-full text-sm text-gray-400 italic">
                        No classes available
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Class Expression Editor Tab */}
          {activeTab === 'classExpression' && (
            <div className="h-full p-6 flex flex-col bg-white">
              <div className="flex-1 flex flex-col">
                <label className="text-sm font-bold text-gray-700 mb-2">Manchester OWL Syntax</label>
                <textarea
                  value={manchesterExpression}
                  onChange={(e) => setManchesterExpression(e.target.value)}
                  placeholder="Enter Manchester OWL Syntax expression"
                  className="flex-1 p-4 border-2 border-purple-200 rounded-lg font-mono text-sm focus:outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent resize-none bg-white shadow-sm"
                />
              </div>
              <div className="mt-4 p-4 bg-white rounded-lg border border-purple-200 shadow-sm">
                <p className="text-xs font-bold text-gray-700 mb-2">KEYWORDS</p>
                <div className="flex flex-wrap gap-2 mb-3">
                  {['and', 'or', 'not', 'some', 'only', 'min', 'max', 'exactly', 'value'].map(kw => (
                    <button
                      key={kw}
                      onClick={() => setManchesterExpression(prev => prev + (prev ? ' ' : '') + kw + ' ')}
                      className="px-3 py-1 bg-gray-100 hover:bg-gray-200 text-gray-800 text-xs font-mono rounded border transition-colors"
                    >
                      {kw}
                    </button>
                  ))}
                </div>
                <p className="text-xs font-bold text-gray-700 mb-1">EXAMPLES</p>
                <ul className="text-xs text-gray-600 space-y-1">
                  <li className="font-mono">• Cell and hasPart some Nucleus</li>
                  <li className="font-mono">• locatedIn only PlantCell</li>
                  <li className="font-mono">• hasPart max 1 Nucleus</li>
                  <li className="font-mono">• (Cat or Dog) and hasFur value true</li>
                </ul>
              </div>
            </div>
          )}

          {/* Data Restriction Creator Tab */}
          {activeTab === 'dataRestriction' && (
            <div className="h-full flex flex-col">
              {/* Restriction type - Top panel */}
              <div className="p-4 bg-gradient-to-r from-green-50 to-emerald-50 border-b">
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-2">Restriction type</h4>
                <div className="flex items-center gap-3">
                  <select
                    value={dataRestrictionType}
                    onChange={(e) => setDataRestrictionType(e.target.value as any)}
                    className="flex-1 px-3 py-2 bg-white border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-green-500 focus:border-transparent"
                  >
                    <option value="some">Some (existential)</option>
                    <option value="only">Only (universal)</option>
                    <option value="min">Min (minimum cardinality)</option>
                    <option value="max">Max (maximum cardinality)</option>
                    <option value="exactly">Exactly (exact cardinality)</option>
                  </select>
                  {(dataRestrictionType === 'min' || dataRestrictionType === 'max' || dataRestrictionType === 'exactly') && (
                    <div className="flex items-center gap-2">
                      <label className="text-sm font-medium text-gray-700">Cardinality</label>
                      <input
                        type="number"
                        min="0"
                        step="1"
                        value={dataCardinality}
                        onChange={(e) => setDataCardinality(parseInt(e.target.value) || 0)}
                        className="w-24 px-2 py-1.5 bg-white border border-gray-300 rounded text-sm focus:outline-none focus:ring-2 focus:ring-green-500"
                      />
                    </div>
                  )}
                </div>
              </div>

              {/* Two-column layout */}
              <div className="flex-1 flex overflow-hidden">
                {/* Restricted property */}
                <div className="w-1/2 border-r flex flex-col bg-white">
                  <div className="p-3 border-b bg-gradient-to-r from-green-50 to-green-100">
                    <div className="flex items-center justify-between mb-2">
                      <h4 className="text-sm font-bold text-green-900">Restricted property</h4>
                      <select className="px-2 py-1 text-xs border border-green-200 rounded bg-white text-green-900">
                        <option>Asserted</option>
                        <option>Inferred</option>
                      </select>
                    </div>
                    <div className="relative">
                      <Search size={16} className="absolute left-3 top-2.5 text-gray-400" />
                      <input
                        type="text"
                        placeholder="Search data properties..."
                        value={dataPropertySearchQuery}
                        onChange={(e) => setDataPropertySearchQuery(e.target.value)}
                        className="w-full pl-9 pr-3 py-2 text-sm border border-green-200 rounded-md focus:outline-none focus:ring-2 focus:ring-green-500"
                      />
                    </div>
                  </div>
                  <div className="flex-1 overflow-y-auto">
                    {dataProperties.length > 0 ? (
                      renderPropertyTree(dataProperties, setSelectedDataProperty, selectedDataProperty, dataPropertySearchQuery, true)
                    ) : (
                      <div className="flex items-center justify-center h-full text-sm text-gray-400 italic">
                        No data properties available
                      </div>
                    )}
                  </div>
                </div>

                {/* Restriction filler (Datatypes) */}
                <div className="w-1/2 flex flex-col bg-white">
                  <div className="p-3 border-b bg-gradient-to-r from-red-50 to-red-100">
                    <h4 className="text-sm font-bold text-red-900">Restriction filler (Datatypes)</h4>
                  </div>
                  <div className="flex-1 overflow-y-auto">
                    {datatypes.map(dt => (
                      <div
                        key={dt}
                        onClick={() => setDatatype(dt)}
                        className={`px-3 py-2.5 hover:bg-red-50 cursor-pointer transition-colors border-b border-gray-100 ${
                          datatype === dt ? 'bg-red-100 border-l-4 border-l-red-500' : ''
                        }`}
                      >
                        <div className="flex items-center gap-2">
                          <span className={`w-3 h-3 rounded-full ${datatype === dt ? 'bg-red-500' : 'bg-red-300'} border border-red-600`} />
                          <span className={`text-sm font-mono ${datatype === dt ? 'font-bold text-red-900' : 'text-gray-700'}`}>{dt}</span>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-6 py-4 border-t bg-gradient-to-r from-gray-50 to-gray-100 flex justify-end gap-3">
          <button
            onClick={handleClose}
            className="px-6 py-2.5 text-sm font-semibold text-gray-700 bg-white border-2 border-gray-300 rounded-lg hover:bg-gray-50 hover:border-gray-400 transition-all shadow-sm"
          >
            Cancel
          </button>
          <button
            onClick={handleConfirm}
            disabled={
              (activeTab === 'hierarchy' && !selectedClass) ||
              (activeTab === 'objectRestriction' && (!selectedProperty || !restrictionFiller)) ||
              (activeTab === 'classExpression' && !manchesterExpression.trim()) ||
              (activeTab === 'dataRestriction' && !selectedDataProperty)
            }
            className="px-6 py-2.5 text-sm font-semibold text-white bg-gradient-to-r from-purple-600 to-indigo-600 rounded-lg hover:from-purple-700 hover:to-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-all shadow-md hover:shadow-lg"
          >
            OK
          </button>
        </div>
      </div>
    </div>
  );
};

export default ClassExpressionDialog;

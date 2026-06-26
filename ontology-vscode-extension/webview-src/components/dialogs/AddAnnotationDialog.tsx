import React, { useState, useEffect } from 'react';
import { Search, Plus, Edit2, Trash2, RefreshCw, Check, X } from 'lucide-react';
import type { AnnotationProperty } from '../../types';

interface AddAnnotationDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onAdd: (propertyIri: string, value: string, datatype?: string, lang?: string) => void;
  availableProperties: AnnotationProperty[];
  entities?: {
    classes: any[];
    objectProperties: any[];
    dataProperties: any[];
    individuals: any[];
  };
  editMode?: boolean;
  initialProperty?: string;
  initialValue?: string;
  initialLang?: string;
  initialDatatype?: string;
  /** Create a new annotation property in the ontology */
  onCreateProperty?: (iri: string, label: string) => Promise<void>;
  /** Refresh the available properties list from the server */
  onRefreshProperties?: () => void;
  /** Ontology namespace for auto-generating new property IRIs */
  ontologyNamespace?: string;
}

const AddAnnotationDialog: React.FC<AddAnnotationDialogProps> = ({
  isOpen,
  onClose,
  onAdd,
  availableProperties,
  entities = { classes: [], objectProperties: [], dataProperties: [], individuals: [] },
  editMode = false,
  initialProperty = '',
  initialValue = '',
  initialLang = '',
  initialDatatype = 'xsd:string',
  onCreateProperty,
  onRefreshProperties,
  ontologyNamespace = 'http://ontocode.org/ontology/annotation#',
}) => {
  const [selectedProperty, setSelectedProperty] = useState(initialProperty);
  const [value, setValue] = useState(initialValue);
  const [lang, setLang] = useState(initialLang);
  const [datatype, setDatatype] = useState(initialDatatype);
  const [activeTab, setActiveTab] = useState('Literal');
  const [searchQuery, setSearchQuery] = useState('');

  // Inline "create new annotation property" form state
  const [showCreate, setShowCreate] = useState(false);
  const [newPropLabel, setNewPropLabel] = useState('');
  const [newPropIri, setNewPropIri] = useState('');
  const [isCreating, setIsCreating] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);

  // Reset form when opening
  useEffect(() => {
    if (isOpen) {
      setSelectedProperty(initialProperty || 'http://www.w3.org/2000/01/rdf-schema#label');
      setValue(initialValue);
      setLang(initialLang);
      setDatatype(initialDatatype || 'xsd:string');
      setShowCreate(false);
      setNewPropLabel('');
      setNewPropIri('');
    } else {
      setSelectedProperty('');
      setValue('');
      setDatatype('xsd:string');
      setSearchQuery('');
      setLang('');
      setActiveTab('Literal');
      setShowCreate(false);
    }
  }, [isOpen, initialProperty, initialValue, initialLang, initialDatatype]);

  if (!isOpen) return null;

  // Auto-generate a safe IRI fragment from a label
  const labelToIriFrag = (lbl: string) =>
    lbl.trim().replace(/\s+/g, '').replace(/[^a-zA-Z0-9_-]/g, '');

  const handleNewLabelChange = (lbl: string) => {
    setNewPropLabel(lbl);
    const frag = labelToIriFrag(lbl);
    if (frag) setNewPropIri(`${ontologyNamespace}${frag}`);
    else setNewPropIri('');
  };

  const handleCreateProperty = async () => {
    if (!newPropLabel.trim() || !newPropIri.trim() || !onCreateProperty) return;
    setIsCreating(true);
    try {
      await onCreateProperty(newPropIri.trim(), newPropLabel.trim());
      setShowCreate(false);
      setNewPropLabel('');
      setNewPropIri('');
      // Refresh so the new property appears in the list
      onRefreshProperties?.();
    } finally {
      setIsCreating(false);
    }
  };

  const handleRefresh = async () => {
    if (!onRefreshProperties) return;
    setIsRefreshing(true);
    try {
      onRefreshProperties();
    } finally {
      // Small visual delay so the spin animation is visible
      setTimeout(() => setIsRefreshing(false), 600);
    }
  };

  // Standard OWL/RDFS annotation properties
  const standardProperties = [
    { iri: 'http://www.w3.org/2002/07/owl#backwardCompatibleWith', label: 'owl:backwardCompatibleWith' },
    { iri: 'http://www.w3.org/2002/07/owl#deprecated', label: 'owl:deprecated' },
    { iri: 'http://www.w3.org/2002/07/owl#incompatibleWith', label: 'owl:incompatibleWith' },
    { iri: 'http://www.w3.org/2002/07/owl#priorVersion', label: 'owl:priorVersion' },
    { iri: 'http://www.w3.org/2002/07/owl#versionInfo', label: 'owl:versionInfo' },
    { iri: 'http://www.w3.org/2000/01/rdf-schema#comment', label: 'rdfs:comment' },
    { iri: 'http://www.w3.org/2000/01/rdf-schema#isDefinedBy', label: 'rdfs:isDefinedBy' },
    { iri: 'http://www.w3.org/2000/01/rdf-schema#label', label: 'rdfs:label' },
    { iri: 'http://www.w3.org/2000/01/rdf-schema#seeAlso', label: 'rdfs:seeAlso' },
  ];

  // Merge with available properties from ontology, avoiding duplicates
  const allProperties = [...standardProperties];
  availableProperties.forEach(p => {
    if (!allProperties.find(ap => ap.iri === p.id)) {
      allProperties.push({ iri: p.id, label: p.label || p.id.split('#').pop() || p.id });
    }
  });

  // Filter properties based on search
  const filteredProperties = allProperties.filter(p =>
    p.label.toLowerCase().includes(searchQuery.toLowerCase()) ||
    p.iri.toLowerCase().includes(searchQuery.toLowerCase())
  ).sort((a, b) => a.label.localeCompare(b.label));

  const handleAdd = () => {
    if (!selectedProperty || !value.trim()) {
      return;
    }
    onAdd(selectedProperty, value, datatype, lang);
    onClose();
  };

  const tabs = ['Literal', 'Entity IRI', 'IRI Editor', 'Property values'];

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-[100]" onClick={onClose}>
      <div className="bg-[#F0F0F0] rounded-lg shadow-2xl w-[800px] h-[600px] flex flex-col overflow-hidden border border-gray-400" onClick={e => e.stopPropagation()}>
        {/* Header */}
        <div className="bg-white px-4 py-2 flex justify-between items-center border-b border-gray-300">
          <div className="flex items-center gap-2">
            <div className="w-5 h-5 bg-purple-600 rounded flex items-center justify-center">
              <Plus size={12} className="text-white" />
            </div>
            <span className="text-sm font-medium text-gray-800">{editMode ? 'Edit Annotation' : 'Create Annotation'}</span>
          </div>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-700">
            <span className="text-xl">×</span>
          </button>
        </div>

        <div className="flex-1 flex overflow-hidden">
          {/* Left Column: Property Selection */}
          <div className="w-1/3 flex flex-col border-r border-gray-300 bg-white">
            {/* Toolbar */}
            <div className="p-2 flex gap-2 border-b border-gray-200 bg-gray-50">
              <button
                onClick={() => { setShowCreate((prev: boolean) => !prev); setNewPropLabel(''); setNewPropIri(''); }}
                className={`p-1 rounded ${onCreateProperty ? 'hover:bg-gray-200 text-gray-600' : 'text-gray-300 cursor-not-allowed'}`}
                title={onCreateProperty ? 'Add annotation property' : 'Not available'}
                disabled={!onCreateProperty}
              >
                <Plus size={16} />
              </button>
              <button
                className="p-1 text-gray-300 cursor-not-allowed"
                title="Edit annotation property (select one first)"
                disabled
              >
                <Edit2 size={16} />
              </button>
              <button
                className="p-1 text-gray-300 cursor-not-allowed"
                title="Delete annotation property (use Entities panel)"
                disabled
              >
                <Trash2 size={16} />
              </button>
              <button
                onClick={handleRefresh}
                className={`p-1 rounded ${onRefreshProperties ? 'hover:bg-gray-200 text-gray-600' : 'text-gray-300 cursor-not-allowed'}`}
                title={onRefreshProperties ? 'Refresh properties list' : 'Not available'}
                disabled={!onRefreshProperties}
              >
                <RefreshCw size={16} className={isRefreshing ? 'animate-spin' : ''} />
              </button>
            </div>

            {/* Inline create new annotation property form */}
            {showCreate && onCreateProperty && (
              <div className="p-2 border-b border-blue-200 bg-blue-50 space-y-1">
                <p className="text-xs font-medium text-blue-700">New Annotation Property</p>
                <input
                  type="text"
                  value={newPropLabel}
                  onChange={e => handleNewLabelChange(e.target.value)}
                  placeholder="Label (e.g. Review Status)"
                  className="w-full px-2 py-1 text-xs border border-blue-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500"
                  autoFocus
                />
                <input
                  type="text"
                  value={newPropIri}
                  onChange={e => setNewPropIri(e.target.value)}
                  placeholder="IRI"
                  className="w-full px-2 py-1 text-xs border border-blue-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500 font-mono"
                />
                <div className="flex gap-1 justify-end">
                  <button
                    onClick={() => setShowCreate(false)}
                    className="p-1 rounded hover:bg-blue-100 text-blue-400"
                    title="Cancel"
                  >
                    <X size={14} />
                  </button>
                  <button
                    onClick={handleCreateProperty}
                    disabled={!newPropLabel.trim() || !newPropIri.trim() || isCreating}
                    className="p-1 rounded hover:bg-blue-100 text-blue-600 disabled:text-blue-200 disabled:cursor-not-allowed"
                    title="Create property"
                  >
                    <Check size={14} />
                  </button>
                </div>
              </div>
            )}

            {/* Search */}
            <div className="p-2 border-b border-gray-200">
              <div className="relative">
                <Search size={14} className="absolute left-2 top-1/2 -translate-y-1/2 text-gray-400" />
                <input
                  type="text"
                  value={searchQuery}
                  onChange={e => setSearchQuery(e.target.value)}
                  placeholder="Search properties..."
                  className="w-full pl-8 pr-2 py-1 text-xs border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
            </div>

            {/* Property List */}
            <div className="flex-1 overflow-y-auto">
              {filteredProperties.map(prop => (
                <div
                  key={prop.iri}
                  onClick={() => setSelectedProperty(prop.iri)}
                  className={`flex items-center gap-2 px-3 py-1.5 cursor-pointer hover:bg-blue-50 ${selectedProperty === prop.iri ? 'bg-blue-100' : ''}`}
                >
                  <div className="w-3 h-3 bg-[#B87333] rounded-sm flex-shrink-0" />
                  <span className={`text-xs truncate ${selectedProperty === prop.iri ? 'text-blue-700 font-medium' : 'text-gray-700'}`}>
                    {prop.label}
                  </span>
                </div>
              ))}
            </div>
          </div>

          {/* Right Column: Tabs and Content */}
          <div className="flex-1 flex flex-col bg-white">
            {/* Tabs */}
            <div className="flex bg-gray-200 border-b border-gray-300">
              {tabs.map(tab => (
                <button
                  key={tab}
                  onClick={() => setActiveTab(tab)}
                  className={`px-4 py-2 text-xs font-medium border-r border-gray-300 ${activeTab === tab ? 'bg-white text-black' : 'text-gray-600 hover:bg-gray-100'}`}
                >
                  {tab}
                </button>
              ))}
            </div>

            {/* Tab Content */}
            <div className="flex-1 p-4 flex flex-col gap-4 overflow-y-auto">
              {activeTab === 'Literal' && (
                <>
                  <div className="flex flex-col flex-1">
                    <label className="text-sm font-bold text-black mb-1">Value</label>
                    <textarea
                      value={value}
                      onChange={e => setValue(e.target.value)}
                      className="flex-1 w-full p-2 border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500 text-sm font-mono min-h-[200px]"
                    />
                  </div>

                  <div>
                    <label className="text-sm font-bold text-black mb-1 block">Language Tag</label>
                    <input
                      type="text"
                      value={lang}
                      onChange={e => setLang(e.target.value)}
                      placeholder="Language Tag"
                      className="w-full p-2 border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500 text-sm"
                    />
                  </div>

                  <div>
                    <label className="text-sm font-bold text-black mb-1 block">Datatype</label>
                    <div className="relative">
                      <div className="absolute left-2 top-1/2 -translate-y-1/2 w-3 h-3 bg-red-700 rounded-full" />
                      <select
                        value={datatype}
                        onChange={e => setDatatype(e.target.value)}
                        className="w-full pl-8 pr-2 py-2 border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500 text-sm appearance-none bg-white"
                      >
                        <option value="xsd:string">xsd:string</option>
                        <option value="xsd:boolean">xsd:boolean</option>
                        <option value="xsd:integer">xsd:integer</option>
                        <option value="xsd:decimal">xsd:decimal</option>
                        <option value="xsd:dateTime">xsd:dateTime</option>
                        <option value="xsd:anyURI">xsd:anyURI</option>
                      </select>
                      <div className="absolute right-2 top-1/2 -translate-y-1/2 pointer-events-none">
                        <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M19 9l-7 7-7-7" />
                        </svg>
                      </div>
                    </div>
                  </div>
                </>
              )}

              {activeTab === 'Entity IRI' && (
                <div className="flex-1 flex flex-col gap-4 overflow-hidden">
                  <div className="flex flex-col flex-1 overflow-hidden">
                    <label className="text-sm font-bold text-black mb-1">Select Entity</label>
                    <div className="border border-gray-300 rounded flex flex-col overflow-hidden flex-1">
                      <div className="p-2 bg-gray-50 border-b border-gray-200">
                        <div className="relative">
                          <Search size={14} className="absolute left-2 top-1/2 -translate-y-1/2 text-gray-400" />
                          <input
                            type="text"
                            placeholder="Search entities..."
                            className="w-full pl-8 pr-2 py-1 text-xs border border-gray-300 rounded focus:outline-none"
                          />
                        </div>
                      </div>
                      <div className="flex-1 overflow-y-auto p-2 space-y-1">
                        {[...entities.classes, ...entities.objectProperties, ...entities.dataProperties, ...entities.individuals].map(entity => (
                          <div
                            key={entity.id || entity.iri}
                            onClick={() => {
                              setValue(entity.id || entity.iri);
                              setDatatype('xsd:anyURI');
                            }}
                            className={`px-2 py-1 text-xs cursor-pointer hover:bg-blue-50 rounded ${value === (entity.id || entity.iri) ? 'bg-blue-100 text-blue-700 font-medium' : 'text-gray-700'}`}
                          >
                            {entity.label || (entity.id || entity.iri).split('#').pop()}
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>
                  <div>
                    <label className="text-sm font-bold text-black mb-1 block">Selected IRI</label>
                    <input
                      type="text"
                      value={value}
                      readOnly
                      className="w-full p-2 border border-gray-200 bg-gray-50 rounded text-xs font-mono"
                    />
                  </div>
                </div>
              )}

              {activeTab === 'IRI Editor' && (
                <div className="flex-1 flex flex-col gap-4">
                  <div className="flex flex-col flex-1">
                    <label className="text-sm font-bold text-black mb-1">IRI</label>
                    <input
                      type="text"
                      value={value}
                      onChange={e => {
                        setValue(e.target.value);
                        setDatatype('xsd:anyURI');
                      }}
                      placeholder="http://example.com/resource"
                      className="w-full p-2 border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500 text-sm font-mono"
                    />
                  </div>
                  <div className="bg-blue-50 p-3 rounded border border-blue-100">
                    <p className="text-xs text-blue-700">
                      Enter a full IRI for the annotation value. This will be treated as an object property value (IRI) rather than a literal.
                    </p>
                  </div>
                </div>
              )}

              {activeTab === 'Property values' && (
                <div className="flex-1 flex items-center justify-center text-gray-400 italic text-sm">
                  Property values tab content will be implemented soon
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="bg-[#F0F0F0] p-4 flex justify-end gap-2 border-t border-gray-300">
          <button
            onClick={handleAdd}
            disabled={!selectedProperty || !value.trim()}
            className="px-6 py-1.5 bg-white border border-gray-400 rounded text-sm hover:bg-gray-50 text-black min-w-[80px] disabled:opacity-50 disabled:cursor-not-allowed"
          >
            OK
          </button>
          <button
            onClick={onClose}
            className="px-6 py-1.5 bg-white border border-gray-400 rounded text-sm hover:bg-gray-50 text-black min-w-[80px]"
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
};

export default AddAnnotationDialog;

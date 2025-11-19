import React, { useState } from 'react';
import { Search } from 'lucide-react';
import type { AnnotationProperty } from '../../types';

interface AddAnnotationDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onAdd: (propertyIri: string, value: string, datatype?: string) => void;
  availableProperties: AnnotationProperty[];
}

const AddAnnotationDialog: React.FC<AddAnnotationDialogProps> = ({ 
  isOpen, 
  onClose, 
  onAdd, 
  availableProperties 
}) => {
  const [selectedProperty, setSelectedProperty] = useState('');
  const [customProperty, setCustomProperty] = useState('');
  const [value, setValue] = useState('');
  const [datatype, setDatatype] = useState('xsd:string');
  const [searchQuery, setSearchQuery] = useState('');
  const [useCustom, setUseCustom] = useState(false);

  if (!isOpen) return null;

  // Common annotation properties
  const commonProperties = [
    { iri: 'http://www.w3.org/2000/01/rdf-schema#label', label: 'rdfs:label' },
    { iri: 'http://www.w3.org/2000/01/rdf-schema#comment', label: 'rdfs:comment' },
    { iri: 'http://www.w3.org/2000/01/rdf-schema#seeAlso', label: 'rdfs:seeAlso' },
    { iri: 'http://www.w3.org/2000/01/rdf-schema#isDefinedBy', label: 'rdfs:isDefinedBy' },
  ];

  // Merge with available properties from ontology
  const allProperties = [
    ...commonProperties,
    ...availableProperties.map(p => ({ iri: p.id, label: p.label || p.id.split('#').pop() || p.id }))
  ];

  // Filter properties based on search
  const filteredProperties = allProperties.filter(p => 
    p.label.toLowerCase().includes(searchQuery.toLowerCase()) ||
    p.iri.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const handleAdd = () => {
    const propertyIri = useCustom ? customProperty : selectedProperty;
    if (!propertyIri.trim() || !value.trim()) {
      return;
    }
    onAdd(propertyIri, value, datatype);
    // Reset form
    setSelectedProperty('');
    setCustomProperty('');
    setValue('');
    setDatatype('xsd:string');
    setSearchQuery('');
    setUseCustom(false);
    onClose();
  };

  const handleClose = () => {
    setSelectedProperty('');
    setCustomProperty('');
    setValue('');
    setDatatype('xsd:string');
    setSearchQuery('');
    setUseCustom(false);
    onClose();
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={handleClose}>
      <div className="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4 flex flex-col max-h-[80vh]" onClick={e => e.stopPropagation()}>
        <div className="p-6 border-b">
          <h3 className="text-lg font-semibold text-black">Add Annotation</h3>
        </div>
        
        <div className="flex-1 overflow-y-auto p-6 space-y-4 text-sm min-h-0">
          {/* Property Selection */}
          <div>
            <div className="flex items-center gap-2 mb-2">
              <label className="font-medium text-black">Annotation Property</label>
              <label className="flex items-center gap-1 text-xs text-black">
                <input 
                  type="checkbox" 
                  checked={useCustom} 
                  onChange={(e) => setUseCustom(e.target.checked)}
                  className="w-3 h-3"
                />
                Custom IRI
              </label>
            </div>
            
            {useCustom ? (
              <input
                type="text"
                value={customProperty}
                onChange={e => setCustomProperty(e.target.value)}
                placeholder="http://example.com/ontology#customProperty"
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 text-black"
              />
            ) : (
              <>
                <div className="relative mb-2">
                  <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                  <input
                    type="text"
                    value={searchQuery}
                    onChange={e => setSearchQuery(e.target.value)}
                    placeholder="Search properties..."
                    className="w-full pl-9 pr-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 text-black"
                  />
                </div>
                <div className="border border-gray-300 rounded-md max-h-48 overflow-y-auto">
                  {filteredProperties.map(prop => (
                    <label 
                      key={prop.iri} 
                      className="flex items-center gap-2 p-2 hover:bg-blue-50 cursor-pointer border-b last:border-b-0"
                    >
                      <input
                        type="radio"
                        name="property"
                        value={prop.iri}
                        checked={selectedProperty === prop.iri}
                        onChange={(e) => setSelectedProperty(e.target.value)}
                        className="w-4 h-4"
                      />
                      <div className="flex-1">
                        <div className="font-medium text-black">{prop.label}</div>
                        <div className="text-xs text-gray-500 truncate">{prop.iri}</div>
                      </div>
                    </label>
                  ))}
                  {filteredProperties.length === 0 && (
                    <div className="p-4 text-center text-gray-500 text-xs">
                      No properties found
                    </div>
                  )}
                </div>
              </>
            )}
          </div>

          {/* Value */}
          <div>
            <label className="font-medium text-black mb-2 block">Value</label>
            <textarea
              value={value}
              onChange={e => setValue(e.target.value)}
              placeholder="Enter annotation value..."
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 text-black min-h-[100px]"
            />
          </div>

          {/* Datatype */}
          <div>
            <label className="font-medium text-black mb-2 block">Datatype</label>
            <select
              value={datatype}
              onChange={e => setDatatype(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 text-black"
            >
              <option value="xsd:string">xsd:string</option>
              <option value="xsd:boolean">xsd:boolean</option>
              <option value="xsd:integer">xsd:integer</option>
              <option value="xsd:decimal">xsd:decimal</option>
              <option value="xsd:dateTime">xsd:dateTime</option>
              <option value="xsd:anyURI">xsd:anyURI</option>
            </select>
          </div>
        </div>

        <div className="p-6 flex justify-end gap-3 border-t">
          <button 
            onClick={handleClose} 
            className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
          >
            Cancel
          </button>
          <button 
            onClick={handleAdd} 
            className="px-4 py-2 text-sm bg-blue-600 text-white rounded-md hover:bg-blue-700"
          >
            Add
          </button>
        </div>
      </div>
    </div>
  );
};

export default AddAnnotationDialog;

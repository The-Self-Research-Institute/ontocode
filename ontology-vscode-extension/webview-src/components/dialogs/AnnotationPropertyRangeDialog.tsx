import React, { useState, useEffect } from 'react';
import { X } from 'lucide-react';
import type { TreeNode, Datatype } from '../../types';

/**
 * AnnotationPropertyRangeDialog - Protégé-style dialog for selecting annotation property ranges
 * 
 * Based on Protégé's OWLAnnotationPropertyRangeEditor.java:
 * - Two tabs: "Select Datatype" (for literal values) and "Edit raw IRI" (direct IRI input)
 * - Annotation property ranges can be:
 *   - Datatypes (xsd:string, xsd:integer, etc.) for literal values
 *   - IRIs for restricting to certain entity types
 */

interface AnnotationPropertyRangeDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (iri: string) => void;
  datatypes?: Datatype[];
  title?: string;
  selectedRanges?: string[]; // Already selected ranges to exclude/show
}

// Common OWL/XSD datatypes
const COMMON_DATATYPES = [
  { id: 'http://www.w3.org/2001/XMLSchema#string', label: 'xsd:string' },
  { id: 'http://www.w3.org/2001/XMLSchema#integer', label: 'xsd:integer' },
  { id: 'http://www.w3.org/2001/XMLSchema#int', label: 'xsd:int' },
  { id: 'http://www.w3.org/2001/XMLSchema#decimal', label: 'xsd:decimal' },
  { id: 'http://www.w3.org/2001/XMLSchema#float', label: 'xsd:float' },
  { id: 'http://www.w3.org/2001/XMLSchema#double', label: 'xsd:double' },
  { id: 'http://www.w3.org/2001/XMLSchema#boolean', label: 'xsd:boolean' },
  { id: 'http://www.w3.org/2001/XMLSchema#date', label: 'xsd:date' },
  { id: 'http://www.w3.org/2001/XMLSchema#dateTime', label: 'xsd:dateTime' },
  { id: 'http://www.w3.org/2001/XMLSchema#time', label: 'xsd:time' },
  { id: 'http://www.w3.org/2001/XMLSchema#anyURI', label: 'xsd:anyURI' },
  { id: 'http://www.w3.org/2001/XMLSchema#nonNegativeInteger', label: 'xsd:nonNegativeInteger' },
  { id: 'http://www.w3.org/2001/XMLSchema#positiveInteger', label: 'xsd:positiveInteger' },
  { id: 'http://www.w3.org/2001/XMLSchema#negativeInteger', label: 'xsd:negativeInteger' },
  { id: 'http://www.w3.org/2001/XMLSchema#long', label: 'xsd:long' },
  { id: 'http://www.w3.org/2001/XMLSchema#short', label: 'xsd:short' },
  { id: 'http://www.w3.org/2001/XMLSchema#byte', label: 'xsd:byte' },
  { id: 'http://www.w3.org/2001/XMLSchema#language', label: 'xsd:language' },
  { id: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#langString', label: 'rdf:langString' },
  { id: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#PlainLiteral', label: 'rdf:PlainLiteral' },
  { id: 'http://www.w3.org/2000/01/rdf-schema#Literal', label: 'rdfs:Literal' },
];

const AnnotationPropertyRangeDialog: React.FC<AnnotationPropertyRangeDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  datatypes = [],
  title = "Range (intersection)",
  selectedRanges = []
}) => {
  const [activeTab, setActiveTab] = useState<'select-datatype' | 'edit-iri'>('select-datatype');
  const [selectedDatatype, setSelectedDatatype] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [rawIri, setRawIri] = useState('');

  // Merge provided datatypes with common ones
  const allDatatypes = [
    ...COMMON_DATATYPES,
    ...datatypes.filter(dt => !COMMON_DATATYPES.some(c => c.id === dt.id))
  ];

  useEffect(() => {
    if (isOpen) {
      setSelectedDatatype(null);
      setRawIri('');
      setSearchQuery('');
      setActiveTab('select-datatype');
    }
  }, [isOpen]);

  const handleConfirm = () => {
    if (activeTab === 'select-datatype' && selectedDatatype) {
      onConfirm(selectedDatatype);
    } else if (activeTab === 'edit-iri' && rawIri.trim()) {
      onConfirm(rawIri.trim());
    }
    handleClose();
  };

  const handleClose = () => {
    setSelectedDatatype(null);
    setRawIri('');
    setSearchQuery('');
    onClose();
  };

  const isValidSelection = activeTab === 'select-datatype' 
    ? selectedDatatype !== null 
    : rawIri.trim().length > 0;

  // Filter datatypes by search query
  const filteredDatatypes = allDatatypes.filter(dt => {
    if (!searchQuery) return true;
    const lowerQuery = searchQuery.toLowerCase();
    return dt.label.toLowerCase().includes(lowerQuery) || dt.id.toLowerCase().includes(lowerQuery);
  });

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

        {/* Tabs - Protégé style */}
        <div className="flex border-b border-gray-200 bg-gray-50">
          <button
            onClick={() => setActiveTab('select-datatype')}
            className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
              activeTab === 'select-datatype'
                ? 'border-orange-600 text-orange-700 bg-white'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
            }`}
          >
            Select Datatype
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
          {activeTab === 'select-datatype' && (
            <>
              {/* Search */}
              <div className="px-3 py-2 border-b bg-gray-50">
                <input
                  type="text"
                  placeholder="Search datatypes..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full px-3 py-1.5 text-sm border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-orange-500 focus:border-orange-500"
                />
              </div>

              {/* Datatype List */}
              <div className="flex-1 overflow-y-auto p-2 bg-white">
                {filteredDatatypes.length > 0 ? (
                  <div className="space-y-0.5">
                    {filteredDatatypes.map(dt => {
                      const isSelected = selectedDatatype === dt.id;
                      const isAlreadySelected = selectedRanges.includes(dt.id);
                      
                      return (
                        <div
                          key={dt.id}
                          className={`flex items-center gap-2 px-2 py-1.5 cursor-pointer rounded transition-colors ${
                            isSelected 
                              ? 'bg-blue-600 text-white' 
                              : isAlreadySelected
                                ? 'bg-gray-100 text-gray-400'
                                : 'hover:bg-gray-100 text-gray-900'
                          }`}
                          onClick={() => !isAlreadySelected && setSelectedDatatype(dt.id)}
                          onDoubleClick={() => {
                            if (!isAlreadySelected) {
                              setSelectedDatatype(dt.id);
                              handleConfirm();
                            }
                          }}
                        >
                          {/* Datatype Icon (purple rectangle like Protégé) */}
                          <span className={`w-3 h-3 rounded-sm flex-shrink-0 ${
                            isSelected ? 'bg-purple-300' : 'bg-purple-500'
                          }`} />
                          
                          <span className="text-sm font-mono truncate">
                            {dt.label}
                            {isAlreadySelected && <span className="ml-1 text-xs">(already selected)</span>}
                          </span>
                        </div>
                      );
                    })}
                  </div>
                ) : (
                  <div className="text-sm text-gray-500 text-center py-4">
                    {searchQuery ? 'No datatypes match your search' : 'No datatypes available'}
                  </div>
                )}
              </div>

              {/* Selected Datatype Display */}
              {selectedDatatype && (
                <div className="px-3 py-2 border-t bg-gray-50 text-xs">
                  <span className="text-gray-500">Selected: </span>
                  <span className="font-mono text-gray-700">
                    {allDatatypes.find(dt => dt.id === selectedDatatype)?.label || selectedDatatype}
                  </span>
                </div>
              )}
            </>
          )}

          {activeTab === 'edit-iri' && (
            <div className="flex-1 p-4 flex flex-col">
              <label className="text-sm font-medium text-gray-700 mb-2">
                Enter IRI for range:
              </label>
              <input
                type="text"
                placeholder="http://www.w3.org/2001/XMLSchema#string"
                value={rawIri}
                onChange={(e) => setRawIri(e.target.value)}
                className="w-full px-3 py-2 text-sm font-mono border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-orange-500 focus:border-orange-500"
              />
              <p className="mt-2 text-xs text-gray-500">
                Enter a valid IRI to specify the range for this annotation property.
                This can be a datatype IRI (e.g., xsd:string) or any other valid IRI.
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

export default AnnotationPropertyRangeDialog;

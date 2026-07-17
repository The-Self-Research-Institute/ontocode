import React, { useState, useEffect, useMemo } from 'react';
import { X, Plus, Trash2 } from 'lucide-react';
import type { Datatype } from '../../types';

/**
 * DataPropertyRangeDialog - dialog for selecting data property ranges
 */

interface DataPropertyRangeDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (iri: string) => void;
  datatypes?: Datatype[];
  title?: string;
  selectedRanges?: string[];
}

type FacetOperator = '>=' | '>' | '<=' | '<' | '=';

interface FacetRow {
  id: string;
  operator: FacetOperator;
  value: string;
}

const BUILT_IN_DATATYPES = [
  { id: 'http://www.w3.org/2002/07/owl#rational', label: 'owl:rational' },
  { id: 'http://www.w3.org/2002/07/owl#real', label: 'owl:real' },
  { id: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#langString', label: 'rdf:langString' },
  { id: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#PlainLiteral', label: 'rdf:PlainLiteral' },
  { id: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#XMLLiteral', label: 'rdf:XMLLiteral' },
  { id: 'http://www.w3.org/2000/01/rdf-schema#Literal', label: 'rdfs:Literal' },
  { id: 'http://www.w3.org/2001/XMLSchema#anyURI', label: 'xsd:anyURI' },
  { id: 'http://www.w3.org/2001/XMLSchema#base64Binary', label: 'xsd:base64Binary' },
  { id: 'http://www.w3.org/2001/XMLSchema#boolean', label: 'xsd:boolean' },
  { id: 'http://www.w3.org/2001/XMLSchema#byte', label: 'xsd:byte' },
  { id: 'http://www.w3.org/2001/XMLSchema#dateTime', label: 'xsd:dateTime' },
  { id: 'http://www.w3.org/2001/XMLSchema#dateTimeStamp', label: 'xsd:dateTimeStamp' },
  { id: 'http://www.w3.org/2001/XMLSchema#decimal', label: 'xsd:decimal' },
  { id: 'http://www.w3.org/2001/XMLSchema#double', label: 'xsd:double' },
  { id: 'http://www.w3.org/2001/XMLSchema#float', label: 'xsd:float' },
  { id: 'http://www.w3.org/2001/XMLSchema#hexBinary', label: 'xsd:hexBinary' },
  { id: 'http://www.w3.org/2001/XMLSchema#int', label: 'xsd:int' },
  { id: 'http://www.w3.org/2001/XMLSchema#integer', label: 'xsd:integer' },
  { id: 'http://www.w3.org/2001/XMLSchema#language', label: 'xsd:language' },
  { id: 'http://www.w3.org/2001/XMLSchema#long', label: 'xsd:long' },
  { id: 'http://www.w3.org/2001/XMLSchema#Name', label: 'xsd:Name' },
  { id: 'http://www.w3.org/2001/XMLSchema#NCName', label: 'xsd:NCName' },
  { id: 'http://www.w3.org/2001/XMLSchema#negativeInteger', label: 'xsd:negativeInteger' },
  { id: 'http://www.w3.org/2001/XMLSchema#NMTOKEN', label: 'xsd:NMTOKEN' },
  { id: 'http://www.w3.org/2001/XMLSchema#nonNegativeInteger', label: 'xsd:nonNegativeInteger' },
  { id: 'http://www.w3.org/2001/XMLSchema#nonPositiveInteger', label: 'xsd:nonPositiveInteger' },
  { id: 'http://www.w3.org/2001/XMLSchema#normalizedString', label: 'xsd:normalizedString' },
  { id: 'http://www.w3.org/2001/XMLSchema#positiveInteger', label: 'xsd:positiveInteger' },
  { id: 'http://www.w3.org/2001/XMLSchema#short', label: 'xsd:short' },
  { id: 'http://www.w3.org/2001/XMLSchema#string', label: 'xsd:string' },
  { id: 'http://www.w3.org/2001/XMLSchema#token', label: 'xsd:token' },
  { id: 'http://www.w3.org/2001/XMLSchema#unsignedByte', label: 'xsd:unsignedByte' },
  { id: 'http://www.w3.org/2001/XMLSchema#unsignedInt', label: 'xsd:unsignedInt' },
  { id: 'http://www.w3.org/2001/XMLSchema#unsignedLong', label: 'xsd:unsignedLong' },
  { id: 'http://www.w3.org/2001/XMLSchema#unsignedShort', label: 'xsd:unsignedShort' },
];

function iriToShortLabel(iri: string): string {
  const builtIn = BUILT_IN_DATATYPES.find((dt) => dt.id === iri);
  if (builtIn) return builtIn.label;
  if (iri.includes('#')) return iri.split('#').pop() || iri;
  if (iri.includes('/')) return iri.split('/').pop() || iri;
  return iri;
}

function buildFacetExpression(baseDatatype: string, facets: FacetRow[]): string {
  const base = iriToShortLabel(baseDatatype);
  const validFacets = facets.filter((f) => f.value.trim().length > 0);
  if (validFacets.length === 0) return base;
  const facetText = validFacets.map((f) => `${f.operator} ${f.value.trim()}`).join(', ');
  return `${base}[${facetText}]`;
}

const DataPropertyRangeDialog: React.FC<DataPropertyRangeDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  datatypes = [],
  title = "'has measurement value'",
}) => {
  const [activeTab, setActiveTab] = useState<'built-in' | 'facets' | 'expression'>('built-in');
  const [selectedDatatype, setSelectedDatatype] = useState<string | null>(null);
  const [facetBaseDatatype, setFacetBaseDatatype] = useState('http://www.w3.org/2001/XMLSchema#integer');
  const [facets, setFacets] = useState<FacetRow[]>([{ id: '1', operator: '>=', value: '0' }]);
  const [expression, setExpression] = useState('');

  const allDatatypes = useMemo(
    () => [
      ...BUILT_IN_DATATYPES,
      ...datatypes.filter((dt) => !BUILT_IN_DATATYPES.some((c) => c.id === dt.id)),
    ],
    [datatypes],
  );

  const facetPreview = useMemo(
    () => buildFacetExpression(facetBaseDatatype, facets),
    [facetBaseDatatype, facets],
  );

  useEffect(() => {
    if (isOpen) {
      setSelectedDatatype(null);
      setExpression('');
      setFacetBaseDatatype('http://www.w3.org/2001/XMLSchema#integer');
      setFacets([{ id: '1', operator: '>=', value: '0' }]);
      setActiveTab('built-in');
    }
  }, [isOpen]);

  const handleConfirm = () => {
    if (activeTab === 'built-in' && selectedDatatype) {
      onConfirm(selectedDatatype);
    } else if (activeTab === 'facets') {
      onConfirm(facetPreview);
    } else if (activeTab === 'expression' && expression.trim()) {
      onConfirm(expression.trim());
    }
    handleClose();
  };

  const handleClose = () => {
    setSelectedDatatype(null);
    setExpression('');
    onClose();
  };

  const isValidSelection =
    activeTab === 'built-in'
      ? selectedDatatype !== null
      : activeTab === 'facets'
        ? facetBaseDatatype.length > 0
        : expression.trim().length > 0;

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={handleClose}>
      <div
        className="bg-white rounded-lg shadow-xl w-full max-w-md mx-4 flex flex-col max-h-[80vh]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="px-4 py-3 border-b bg-gray-100 flex justify-between items-center">
          <div className="flex items-center gap-2">
            <span className="w-4 h-4 bg-green-500 rounded-sm" />
            <h3 className="text-sm font-medium text-gray-900">{title}</h3>
          </div>
          <button onClick={handleClose} className="text-gray-500 hover:text-gray-700 p-1 hover:bg-gray-200 rounded transition-colors">
            <X size={18} />
          </button>
        </div>

        <div className="border-b bg-gray-50 flex">
          {[
            { id: 'built-in' as const, label: 'Built in datatypes' },
            { id: 'facets' as const, label: 'Faceted restriction' },
            { id: 'expression' as const, label: 'Data range expression' },
          ].map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`px-3 py-2.5 text-xs font-medium transition-colors ${
                activeTab === tab.id
                  ? 'bg-white border-b-2 border-green-500 text-green-700'
                  : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        <div className="flex-1 overflow-y-auto min-h-[280px]">
          {activeTab === 'built-in' && (
            <div className="p-2">
              {allDatatypes.map((dt) => (
                <div
                  key={dt.id}
                  onClick={() => setSelectedDatatype(dt.id)}
                  className={`flex items-center gap-2 px-3 py-2 cursor-pointer rounded transition-colors ${
                    selectedDatatype === dt.id ? 'bg-green-100 text-green-900' : 'hover:bg-gray-100 text-gray-800'
                  }`}
                >
                  <span className={`w-3 h-3 rounded-full flex-shrink-0 ${selectedDatatype === dt.id ? 'bg-red-600' : 'bg-red-500'}`} />
                  <span className="text-sm">{dt.label}</span>
                </div>
              ))}
            </div>
          )}

          {activeTab === 'facets' && (
            <div className="p-4 space-y-3">
              <div>
                <label className="block text-xs font-medium text-gray-700 mb-1">Base datatype</label>
                <select
                  value={facetBaseDatatype}
                  onChange={(e) => setFacetBaseDatatype(e.target.value)}
                  className="w-full px-3 py-2 text-sm border border-gray-300 rounded bg-white"
                >
                  {allDatatypes.map((dt) => (
                    <option key={dt.id} value={dt.id}>{dt.label}</option>
                  ))}
                </select>
              </div>
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-medium text-gray-700">Facets</span>
                  <button
                    type="button"
                    onClick={() => setFacets((prev) => [...prev, { id: String(Date.now()), operator: '>=', value: '' }])}
                    className="text-xs text-green-700 hover:text-green-900 flex items-center gap-1"
                  >
                    <Plus size={12} /> Add facet
                  </button>
                </div>
                {facets.map((facet) => (
                  <div key={facet.id} className="flex items-center gap-2">
                    <select
                      value={facet.operator}
                      onChange={(e) =>
                        setFacets((prev) =>
                          prev.map((row) => (row.id === facet.id ? { ...row, operator: e.target.value as FacetOperator } : row)),
                        )
                      }
                      className="px-2 py-1.5 text-sm border border-gray-300 rounded bg-white"
                    >
                      <option value=">=">&gt;=</option>
                      <option value=">">&gt;</option>
                      <option value="<=">&lt;=</option>
                      <option value="<">&lt;</option>
                      <option value="=">=</option>
                    </select>
                    <input
                      value={facet.value}
                      onChange={(e) =>
                        setFacets((prev) =>
                          prev.map((row) => (row.id === facet.id ? { ...row, value: e.target.value } : row)),
                        )
                      }
                      placeholder="Value"
                      className="flex-1 px-2 py-1.5 text-sm border border-gray-300 rounded"
                    />
                    <button
                      type="button"
                      onClick={() => setFacets((prev) => prev.filter((row) => row.id !== facet.id))}
                      className="p-1 text-gray-400 hover:text-red-500"
                      disabled={facets.length <= 1}
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                ))}
              </div>
              <div className="text-xs text-gray-600 bg-gray-50 border border-gray-200 rounded px-3 py-2 font-mono">
                Preview: {facetPreview}
              </div>
            </div>
          )}

          {activeTab === 'expression' && (
            <div className="p-4">
              <p className="text-sm text-gray-600 mb-3">
                Enter a data range expression (e.g., xsd:integer[&gt;= 0, &lt;= 100])
              </p>
              <textarea
                value={expression}
                onChange={(e) => setExpression(e.target.value)}
                placeholder="Enter data range expression..."
                className="w-full h-32 px-3 py-2 border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-green-500 focus:border-green-500 text-sm font-mono bg-white text-black"
              />
            </div>
          )}
        </div>

        <div className="px-4 py-3 border-t bg-gray-50 flex justify-end gap-2">
          <button
            onClick={handleConfirm}
            disabled={!isValidSelection}
            className="px-4 py-2 text-sm font-medium text-white bg-green-600 rounded hover:bg-green-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            OK
          </button>
          <button
            onClick={handleClose}
            className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-50 transition-colors"
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
};

export default DataPropertyRangeDialog;

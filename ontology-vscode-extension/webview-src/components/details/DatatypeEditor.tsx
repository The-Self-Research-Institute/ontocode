import React from 'react';

interface DatatypeEditorProps {
  datatype: {
    id: string;
    iri: string;
    label: string;
    type: string;
    annotations?: any;
  };
  activeTheme?: string;
}

export const DatatypeEditor: React.FC<DatatypeEditorProps> = ({ datatype }) => {
  return (
    <div className="flex-1 p-2 bg-gray-50">
      <div className="bg-white border border-gray-300 rounded">
        <div className="px-3 py-2 border-b border-gray-300 bg-gradient-to-b from-red-50 to-white">
          <h3 className="text-sm font-semibold text-gray-800">Datatype Information</h3>
        </div>
        <div className="p-3 space-y-3">
          <div>
            <label className="block text-xs font-semibold text-gray-700 mb-1.5">IRI</label>
            <div className="px-2 py-1.5 bg-gray-50 border border-gray-200 rounded font-mono text-xs text-gray-700 break-all">
              {datatype.iri}
            </div>
          </div>
          
          <div>
            <label className="block text-xs font-semibold text-gray-700 mb-1.5">Label</label>
            <div className="px-2 py-1.5 bg-gray-50 border border-gray-200 rounded text-xs text-gray-700">
              {datatype.label || <span className="text-gray-400 italic">No label</span>}
            </div>
          </div>

          <div>
            <label className="block text-xs font-semibold text-gray-700 mb-1.5">Type</label>
            <div className="px-2 py-1.5 bg-gray-50 border border-gray-200 rounded text-xs text-gray-700">
              Datatype
            </div>
          </div>

          <div>
            <label className="block text-xs font-semibold text-gray-700 mb-1.5">Description</label>
            <div className="px-2 py-1.5 bg-gray-50 border border-gray-200 rounded text-xs text-gray-600">
              <p className="mb-1">
                This is a built-in datatype used in OWL ontologies for representing literal values.
              </p>
              {datatype.iri.includes('XMLSchema') && (
                <p className="text-[11px] text-gray-500 mt-2">
                  Part of the XML Schema Datatypes specification.
                </p>
              )}
              {datatype.iri.includes('rdf-syntax-ns') && (
                <p className="text-[11px] text-gray-500 mt-2">
                  Part of the RDF specification.
                </p>
              )}
              {datatype.iri.includes('owl') && (
                <p className="text-[11px] text-gray-500 mt-2">
                  Part of the OWL 2 specification.
                </p>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default DatatypeEditor;

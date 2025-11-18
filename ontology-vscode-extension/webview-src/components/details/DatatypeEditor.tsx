import React from 'react';
import { Panel } from './common';

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

export const DatatypeEditor: React.FC<DatatypeEditorProps> = ({ datatype, activeTheme }) => {
  return (
    <div className="space-y-4">
      <Panel title="Datatype Information" theme={activeTheme}>
        <div className="space-y-3 text-sm">
          <div>
            <label className="block text-xs font-semibold text-gray-600 mb-1">IRI</label>
            <div className="p-2 bg-gray-50 rounded border border-gray-200 font-mono text-xs break-all">
              {datatype.iri}
            </div>
          </div>
          
          <div>
            <label className="block text-xs font-semibold text-gray-600 mb-1">Label</label>
            <div className="p-2 bg-gray-50 rounded border border-gray-200">
              {datatype.label || <span className="text-gray-400 italic">No label</span>}
            </div>
          </div>

          <div>
            <label className="block text-xs font-semibold text-gray-600 mb-1">Type</label>
            <div className="p-2 bg-gray-50 rounded border border-gray-200">
              Datatype
            </div>
          </div>
        </div>
      </Panel>

      <Panel title="Description" theme={activeTheme}>
        <div className="text-sm text-gray-600">
          <p className="mb-2">
            This is a built-in datatype used in OWL ontologies for representing literal values.
          </p>
          {datatype.iri.includes('XMLSchema') && (
            <p className="text-xs text-gray-500 mt-2">
              Part of the XML Schema Datatypes specification.
            </p>
          )}
          {datatype.iri.includes('rdf-syntax-ns') && (
            <p className="text-xs text-gray-500 mt-2">
              Part of the RDF specification.
            </p>
          )}
          {datatype.iri.includes('owl') && (
            <p className="text-xs text-gray-500 mt-2">
              Part of the OWL 2 specification.
            </p>
          )}
        </div>
      </Panel>
    </div>
  );
};

export default DatatypeEditor;

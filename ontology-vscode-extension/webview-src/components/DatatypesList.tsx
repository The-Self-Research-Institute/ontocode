import React from 'react';
import { Type } from 'lucide-react';

interface Datatype {
  id: string;
  iri: string;
  label: string;
  type: string;
}

interface DatatypesListProps {
  datatypes: Datatype[];
  onSelectDatatype: (datatype: Datatype) => void;
  selectedDatatypeId?: string;
}

export const DatatypesList: React.FC<DatatypesListProps> = ({
  datatypes,
  onSelectDatatype,
  selectedDatatypeId
}) => {
  const selectedDatatype = datatypes.find(dt => dt.id === selectedDatatypeId);
  const displayName = selectedDatatype?.label || selectedDatatype?.iri.split(/[/#]/).pop() || 'Datatypes';
  
  return (
    <aside className="w-80 bg-white border-r border-gray-300 flex flex-col h-full">
      {/* Header */}
      <div className="text-xs font-normal text-gray-700 px-2 py-2 border-b border-gray-300 bg-gradient-to-b from-gray-50 to-white">
        <span>Datatypes hierarchy</span>
      </div>
      
      {/* List View */}
      <div className="flex-1 overflow-y-auto bg-white">
        {datatypes.length === 0 ? (
          <div className="p-4 text-center text-gray-400 text-xs">No datatypes found.</div>
        ) : (
          datatypes.map(datatype => {
            const isSelected = selectedDatatypeId === datatype.id;
            return (
              <div
                key={datatype.id}
                className={`flex items-center gap-2 py-1 px-2 cursor-pointer border-b border-gray-100 ${
                  isSelected 
                    ? 'bg-blue-100' 
                    : 'hover:bg-gray-50'
                }`}
                onClick={() => onSelectDatatype(datatype)}
              >
                <div 
                  title="Datatype"
                  className="w-4 h-4 rounded border bg-red-500 border-red-600 flex-shrink-0 flex items-center justify-center"
                >
                  <Type size={10} className="text-white" strokeWidth={2} />
                </div>
                <span className={`text-xs truncate text-gray-900 ${
                  isSelected ? 'font-semibold' : 'font-normal'
                }`}>
                  {datatype.label || datatype.iri.split(/[/#]/).pop()}
                </span>
              </div>
            );
          })
        )}
      </div>
    </aside>
  );
};

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
    <aside className="w-80 bg-white border-r border-gray-200 flex flex-col h-full">
      {/* Header */}
      <div className="text-xs font-semibold p-1.5 flex items-center justify-between border-b">
        <span>Datatypes hierarchy</span>
      </div>
      
      {/* List View */}
      <div className="flex-1 overflow-y-auto p-1">
        {datatypes.length === 0 ? (
          <div className="p-4 text-center text-gray-400">No datatypes found.</div>
        ) : (
          datatypes.map(datatype => {
            const isSelected = selectedDatatypeId === datatype.id;
            return (
              <div
                key={datatype.id}
                className={`flex items-center px-2 py-0.5 rounded cursor-pointer ${
                  isSelected ? 'bg-blue-200' : 'hover:bg-slate-100'
                }`}
                onClick={() => onSelectDatatype(datatype)}
              >
                <span className="w-5 mr-1" />
                <div 
                  title="Datatype"
                  className="w-3.5 h-3.5 rounded-sm border bg-red-400 border-red-600 mr-2 flex-shrink-0 flex items-center justify-center"
                >
                  <Type size={10} className="text-white" />
                </div>
                <span className={`text-xs select-none text-black ${isSelected ? 'font-semibold' : ''}`}>
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

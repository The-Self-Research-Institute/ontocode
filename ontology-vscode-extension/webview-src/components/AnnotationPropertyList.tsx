import React from 'react';
import { Tag } from 'lucide-react';

interface AnnotationProperty {
  id: string;
  iri: string;
  label: string;
  type: string;
}

interface AnnotationPropertyListProps {
  properties: AnnotationProperty[];
  onSelectProperty: (property: AnnotationProperty) => void;
  selectedPropertyId?: string;
}

export const AnnotationPropertyList: React.FC<AnnotationPropertyListProps> = ({
  properties,
  onSelectProperty,
  selectedPropertyId
}) => {
  const selectedProperty = properties.find(p => p.id === selectedPropertyId);
  const displayName = selectedProperty?.label || selectedProperty?.iri.split(/[/#]/).pop() || 'Annotation Properties';
  
  return (
    <aside className="w-80 bg-white border-r border-gray-200 flex flex-col h-full">
      {/* Header */}
      <div className="text-xs font-semibold p-1.5 flex items-center justify-between border-b">
        <span>Annotation properties hierarchy</span>
      </div>
      
      {/* List View */}
      <div className="flex-1 overflow-y-auto p-1">
        {properties.length === 0 ? (
          <div className="p-4 text-center text-gray-400">No annotation properties found.</div>
        ) : (
          properties.map(prop => {
            const isSelected = selectedPropertyId === prop.id;
            return (
              <div
                key={prop.id}
                className={`flex items-center px-2 py-0.5 rounded cursor-pointer ${
                  isSelected ? 'bg-blue-200' : 'hover:bg-slate-100'
                }`}
                onClick={() => onSelectProperty(prop)}
              >
                <span className="w-5 mr-1" />
                <div 
                  title="Annotation property"
                  className="w-3.5 h-3.5 rounded-sm border bg-orange-400 border-orange-600 mr-2 flex-shrink-0 flex items-center justify-center"
                >
                  <Tag size={10} className="text-white" />
                </div>
                <span className={`text-xs select-none text-black ${isSelected ? 'font-semibold' : ''}`}>
                  {prop.label || prop.iri.split(/[/#]/).pop()}
                </span>
              </div>
            );
          })
        )}
      </div>
    </aside>
  );
};

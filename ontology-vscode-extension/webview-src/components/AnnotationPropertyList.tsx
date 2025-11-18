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
    <aside className="w-80 bg-white border-r border-gray-300 flex flex-col h-full">
      {/* Header */}
      <div className="text-xs font-normal text-gray-700 px-2 py-2 border-b border-gray-300 bg-gradient-to-b from-gray-50 to-white">
        <span>Annotation properties hierarchy</span>
      </div>
      
      {/* List View */}
      <div className="flex-1 overflow-y-auto bg-white">
        {properties.length === 0 ? (
          <div className="p-4 text-center text-gray-400 text-xs">No annotation properties found.</div>
        ) : (
          properties.map(prop => {
            const isSelected = selectedPropertyId === prop.id;
            return (
              <div
                key={prop.id}
                className={`flex items-center gap-2 py-1 px-2 cursor-pointer border-b border-gray-100 ${
                  isSelected 
                    ? 'bg-blue-100' 
                    : 'hover:bg-gray-50'
                }`}
                onClick={() => onSelectProperty(prop)}
              >
                <div 
                  title="Annotation property"
                  className="w-4 h-4 rounded border bg-orange-500 border-orange-600 flex-shrink-0 flex items-center justify-center"
                >
                  <Tag size={10} className="text-white" strokeWidth={2} />
                </div>
                <span className={`text-xs truncate text-gray-900 ${
                  isSelected ? 'font-semibold' : 'font-normal'
                }`}>
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

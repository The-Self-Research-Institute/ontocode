import React, { useState } from "react";
import { ChevronRight, ChevronDown, Plus, Trash2, Edit2, MessageCircle, HelpCircle } from "lucide-react";
import ManchesterSyntaxEditor from './ManchesterSyntaxEditor';
import type { Axiom } from '../../types';

export const AxiomRow: React.FC<{
  axiom: Axiom;
  onDelete: (id: string) => void;
  onEdit?: (id: string, newDefinition: string) => void;
  isInferred?: boolean;
  isInActiveOntology?: boolean;
  ontologyIri?: string;
  hasAxiomAnnotations?: boolean;
}> = ({ axiom, onDelete, onEdit, isInferred = false, isInActiveOntology = false, ontologyIri, hasAxiomAnnotations = false }) => {
  const [isEditing, setIsEditing] = useState(false);
  const [showAxiomAnnotations, setShowAxiomAnnotations] = useState(false);

  const handleEdit = (newDefinition: string) => {
    if (onEdit) {
      onEdit(axiom.id, newDefinition);
    }
    setIsEditing(false);
  };

  return (
    <div 
      className={`group flex justify-between items-start p-1.5 border-b border-gray-100 last:border-0 hover:bg-blue-50 transition-colors ${
        isInferred ? 'bg-yellow-50' : 'bg-white'
      }`}
      title={ontologyIri ? `Defined in: ${ontologyIri}` : undefined}
    >
      {isEditing ? (
        <div className="flex-1">
          <ManchesterSyntaxEditor 
            initialValue={axiom.definition}
            onSave={handleEdit} 
            onCancel={() => setIsEditing(false)} 
          />
        </div>
      ) : (
        <>
          <div className={`text-sm font-mono text-gray-800 break-all leading-relaxed ${isInActiveOntology ? 'font-bold' : ''}`}>
            {axiom.definition}
            {isInferred && (
              <span className="ml-2 text-[10px] bg-yellow-200 text-yellow-800 px-1.5 py-0.5 rounded">Inferred</span>
            )}
          </div>
          <div className="flex items-center gap-1 ml-2">
            {/* Explain Inference button */}
            <button 
              className="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-purple-100 text-gray-400 hover:text-purple-600 transition-all" 
              title="Explain why this axiom holds"
              aria-label="Explain inference"
            >
              <HelpCircle size={14} />
            </button>

            {/* Axiom Annotations button */}
            <button 
              onClick={() => setShowAxiomAnnotations(!showAxiomAnnotations)}
              className={`p-1 rounded hover:bg-blue-100 transition-all ${
                hasAxiomAnnotations ? 'bg-yellow-100 text-blue-600' : 'opacity-0 group-hover:opacity-100 text-gray-400 hover:text-blue-600'
              }`}
              title="View/edit axiom annotations"
              aria-label="Axiom annotations"
            >
              <MessageCircle size={14} />
            </button>

            {/* Edit button - only for asserted axioms */}
            {!isInferred && onEdit && (
              <button 
                onClick={() => setIsEditing(true)}
                className="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-blue-100 text-gray-400 hover:text-blue-600 transition-all" 
                title="Edit axiom"
                aria-label="Edit"
              >
                <Edit2 size={14} />
              </button>
            )}

            {/* Delete button - only for asserted axioms */}
            {!isInferred && (
              <button 
                onClick={() => onDelete(axiom.id)} 
                className="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-red-100 text-gray-400 hover:text-red-600 transition-all" 
                title="Delete axiom"
                aria-label="Delete"
              >
                <Trash2 size={14} />
              </button>
            )}
          </div>
        </>
      )}
      
      {/* Axiom Annotations Panel */}
      {showAxiomAnnotations && (
        <div className="mt-2 p-2 bg-blue-50 border border-blue-200 rounded text-xs">
          <div className="font-semibold text-blue-900 mb-1">Axiom Annotations</div>
          <div className="text-gray-600 italic">
            {hasAxiomAnnotations ? 'Axiom annotations would be displayed here' : 'No annotations on this axiom'}
          </div>
        </div>
      )}
    </div>
  );
};

export const AxiomSubsection: React.FC<{
  title: string;
  axioms: Axiom[] | undefined;
  inferredAxioms?: Axiom[];
  onAdd: (definition: string) => void;
  onEdit?: (id: string, newDefinition: string) => void;
  onDelete: (id: string) => void;
  emptyMessage?: string;
  onAddClick?: () => void;
  activeOntologyIri?: string;
}> = ({ title, axioms, inferredAxioms, onAdd, onEdit, onDelete, emptyMessage, onAddClick, activeOntologyIri }) => {
  const [isAdding, setIsAdding] = useState(false);
  const [isFocused, setIsFocused] = useState(false);

  const handleSave = (definition: string) => {
    onAdd(definition);
    setIsAdding(false);
  };

  const handleAddButtonClick = () => {
    if (onAddClick) {
      onAddClick();
    } else {
      setIsAdding(true);
    }
  };

  // Keyboard shortcut: Enter to add new axiom when section header is focused
  const handleHeaderKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && isFocused) {
      e.preventDefault();
      handleAddButtonClick();
    }
  };

  const allAxioms = [...(axioms || []), ...(inferredAxioms || [])];
  const hasContent = allAxioms.length > 0;

  return (
    <div className="mb-4 last:mb-0">
      <div 
        className={`flex justify-between items-center mb-1 px-2 py-1 rounded ${isFocused ? 'ring-2 ring-purple-300' : ''}`}
        tabIndex={0}
        onFocus={() => setIsFocused(true)}
        onBlur={() => setIsFocused(false)}
        onKeyDown={handleHeaderKeyDown}
      >
        <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">
          {title}
          {isFocused && <span className="ml-2 text-[10px] text-purple-600">(Press Enter to add)</span>}
        </h4>
        <button 
          onClick={handleAddButtonClick} 
          className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-purple-600 transition-colors" 
          title={`Add ${title} (Enter when focused)`}
        >
          <Plus size={14} />
        </button>
      </div>
      <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm">
        {hasContent ? (
          <>
            {/* Asserted axioms */}
            {axioms?.map(axiom => (
              <AxiomRow 
                key={axiom.id} 
                axiom={axiom} 
                onDelete={onDelete}
                onEdit={onEdit}
                isInferred={false}
                isInActiveOntology={axiom.ontologyIri === activeOntologyIri}
                ontologyIri={axiom.ontologyIri}
                hasAxiomAnnotations={false}
              />
            ))}
            {/* Inferred axioms */}
            {inferredAxioms?.map(axiom => (
              <AxiomRow 
                key={`inferred-${axiom.id}`} 
                axiom={axiom} 
                onDelete={onDelete}
                isInferred={true}
                ontologyIri="Inferred by reasoner"
              />
            ))}
          </>
        ) : (
          !isAdding && (
            <div className="p-2 text-xs text-gray-400 italic bg-gray-50">
              {emptyMessage || "No axioms defined"}
            </div>
          )
        )}
        {isAdding && (
          <div className="p-2 bg-gray-50 border-t border-gray-200">
            <ManchesterSyntaxEditor onSave={handleSave} onCancel={() => setIsAdding(false)} />
          </div>
        )}
      </div>
    </div>
  );
};

/**
 * A component to safely render an annotation value, stripping
 * common RDF literal suffixes like "^^xsd:string".
 *
 */
export const AnnotationValue = ({ value }: { value: string }) => {
  let cleanedValue = value.toString();
  if (cleanedValue.startsWith('"')) cleanedValue = cleanedValue.substring(1);
  if (cleanedValue.endsWith('"^^xsd:string') || cleanedValue.endsWith('"')) {
    cleanedValue = cleanedValue.replace(/"\^\^xsd:string$/, "").replace(/"$/, "");
  }
  return <p className="text-sm text-gray-700 leading-relaxed whitespace-pre-wrap">{cleanedValue}</p>;
};

/**
 * Extract readable property name from full URI
 */
const getPropertyLabel = (uri: string): string => {
  if (uri.includes('#')) {
    return uri.split('#').pop() || uri;
  }
  if (uri.includes('/')) {
    return uri.split('/').pop() || uri;
  }
  return uri;
};

/**
 * A component that displays a list of annotations (key-value pairs)
 * and provides a delete button for each.
 *
 */
export const AnnotationsDisplay = ({ annotations, onDelete }: { annotations?: Record<string, string>, onDelete: (key: string) => void }) => {
  if (!annotations || Object.keys(annotations).length === 0) {
    return (
        <div className="p-2 text-xs text-gray-400 italic">No annotations</div>
    );
  }
  
  const sortedAnnotations = Object.entries(annotations).sort(([keyA], [keyB]) => {
    const labelA = getPropertyLabel(keyA);
    const labelB = getPropertyLabel(keyB);
    return labelA.localeCompare(labelB);
  });
  
  return (
    <div className="space-y-2">
      {sortedAnnotations.map(([key, value]) => {
        const propertyLabel = getPropertyLabel(key);
        return (
          <div key={key} className="group border border-gray-200 rounded-md hover:border-blue-300 transition-colors">
            <div className="bg-gradient-to-r from-blue-50 to-gray-50 px-3 py-2 border-b border-gray-200 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="text-xs font-semibold text-blue-900">{propertyLabel}</span>
                <span className="text-[10px] text-gray-400 font-mono truncate max-w-[200px]" title={key}>
                  {key}
                </span>
              </div>
              <button 
                onClick={() => onDelete(key)} 
                className="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-red-100 transition-all"
                title={`Delete annotation ${propertyLabel}`}
                aria-label={`Delete annotation ${propertyLabel}`}
              >
                <Trash2 size={14} className="text-red-600" />
              </button>
            </div>
            <div className="px-3 py-2 bg-white">
              <AnnotationValue value={value} />
            </div>
          </div>
        );
      })}
    </div>
  );
};

/**
 * The main collapsible panel component used in all editors.
 *
 */
export const Panel = ({ 
  title, 
  children, 
  actions, 
  defaultOpen = true, 
  themeColor 
}: { 
  title: string, 
  children?: React.ReactNode, 
  actions?: React.ReactNode, 
  defaultOpen?: boolean, 
  themeColor?: string 
}) => {
    const [isOpen, setIsOpen] = useState(defaultOpen);
    const themeClasses = themeColor || 'bg-gradient-to-b from-[#F5F0E6] to-[#E1C688] text-black border-[#D6C9AD]';
    
    return (
        <div className={`border bg-white rounded-sm flex flex-col ${themeColor?.split(' ')[2] || 'border-[#D6C9AD]'}`}>
            <div className={`text-xs font-semibold p-1.5 flex items-center justify-between border-b ${themeClasses}`}>
                <div className="flex items-center">
                    <button 
                      onClick={() => setIsOpen(!isOpen)} 
                      className="mr-1 p-0.5 rounded hover:bg-black/10"
                      aria-expanded={isOpen}
                      aria-controls={`panel-content-${title}`}
                    >
                        {isOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                    </button>
                    <span>{title}</span>
                </div>
                <div className="flex items-center gap-1">{actions}</div>
            </div>
            <div 
              id={`panel-content-${title}`}
              className={`transition-all duration-300 ease-in-out overflow-hidden ${isOpen ? 'max-h-[1000px]' : 'max-h-0'}`}
            >
                {isOpen && <div className="bg-white overflow-y-auto">{children}</div>}
            </div>
        </div>
    );
};

export const MultiSelectItem: React.FC<{
  item: string;
  onDelete: (item: string) => void;
}> = ({ item, onDelete }) => (
    <div className="group flex justify-between items-center bg-white p-1.5 border-b border-gray-100 last:border-0 hover:bg-blue-50 transition-colors">
        <span className="text-sm text-gray-800">{item.split('#').pop() || item}</span>
        <button 
          onClick={() => onDelete(item)} 
          className="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-red-100 text-gray-400 hover:text-red-600 transition-all"
          title={`Remove ${item.split('#').pop()}`}
          aria-label={`Remove ${item.split('#').pop()}`}
        >
            <Trash2 size={14} />
        </button>
    </div>
);

export const MultiSelectSection: React.FC<{
    title: string;
    items: string[] | undefined;
    onAddClick?: () => void;
    onDelete: (item: string) => void;
}> = ({ title, items, onAddClick, onDelete }) => {
    return (
         <div className="mb-4 last:mb-0">
             <div className="flex justify-between items-center mb-1">
                 <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">{title}</h4>
                 {onAddClick && (
                    <button 
                    onClick={onAddClick} 
                    className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-purple-600 transition-colors"
                    title={`Add ${title.slice(0, -1)}`}
                    aria-label={`Add ${title.slice(0, -1)}`}
                    >
                        <Plus size={14}/>
                    </button>
                 )}
             </div>
             <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm">
                 {items && items.length > 0 ? (
                    items.map(item => <MultiSelectItem key={item} item={item} onDelete={onDelete} />)
                 ) : (
                    <div className="p-2 text-xs text-gray-400 italic bg-gray-50">
                        No {title.toLowerCase()} defined
                    </div>
                 )}
             </div>
         </div>
    );
};
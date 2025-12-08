import React, { useState, useRef, useEffect } from "react";
import { ChevronRight, ChevronDown, Plus, Trash2, Edit2, MessageCircle, HelpCircle } from "lucide-react";
import ManchesterSyntaxEditor from './ManchesterSyntaxEditor';
import type { Axiom } from '../../types';

export const AxiomRow: React.FC<{
  axiom: Axiom;
  onDelete: (id: string) => void;
  onEdit?: (id: string, newDefinition: string) => void;
  onEditClick?: (axiom: Axiom, initialTab?: 'hierarchy' | 'objectRestriction' | 'dataRestriction' | 'classExpression', restrictionData?: any) => void;
  isInferred?: boolean;
  isInActiveOntology?: boolean;
  ontologyIri?: string;
  hasAxiomAnnotations?: boolean;
  properties?: any[];
  dataProperties?: any[];
}> = ({ axiom, onDelete, onEdit, onEditClick, isInferred: isInferredProp = false, isInActiveOntology = false, ontologyIri, hasAxiomAnnotations = false, properties = [], dataProperties = [] }) => {
  // Handle isInferred from prop or axiom object (can be boolean or string 'true')
  const isInferred = isInferredProp || axiom.isInferred === true || axiom.isInferred === 'true';
  const [isEditing, setIsEditing] = useState(false);
  const [showAxiomAnnotations, setShowAxiomAnnotations] = useState(false);

  const handleEdit = (newDefinition: string) => {
    if (onEdit) {
      onEdit(axiom.id, newDefinition);
    }
    setIsEditing(false);
  };

  // Determine which tab to open based on axiom properties
  const determineInitialTab = (): 'hierarchy' | 'objectRestriction' | 'dataRestriction' | 'classExpression' | undefined => {
    const isRestriction = axiom.isRestriction === true || axiom.isRestriction === 'true';
    
    if (isRestriction && axiom.propertyIri) {
      // Check if it's a data property restriction (including owl:topDataProperty)
      const isDataProperty = axiom.propertyIri === 'http://www.w3.org/2002/07/owl#topDataProperty' 
        || dataProperties.some(p => p.id === axiom.propertyIri);
      if (isDataProperty) {
        return 'dataRestriction';
      }
      // Otherwise it's an object property restriction
      return 'objectRestriction';
    }
    
    // Check if it's a complex expression (intersection, union, complement, oneOf)
    const isComplex = axiom.isComplex === true || axiom.isComplex === 'true';
    if (isComplex && axiom.expressionType) {
      return 'classExpression';
    }
    
    // Default to hierarchy for simple class references
    return 'hierarchy';
  };

  // Build restriction data from axiom properties
  const buildRestrictionData = (): any => {
    const isRestriction = axiom.isRestriction === true || axiom.isRestriction === 'true';
    
    if (isRestriction && axiom.propertyIri && axiom.restrictionType && axiom.fillerIri) {
      // Check if it's a data property (including owl:topDataProperty)
      const isDataProperty = axiom.propertyIri === 'http://www.w3.org/2002/07/owl#topDataProperty' 
        || dataProperties.some(p => p.id === axiom.propertyIri);
      
      return {
        type: isDataProperty ? 'dataRestriction' : 'objectRestriction',
        axiomType: axiom.type, // Include the axiom type (EquivalentTo, SubClassOf, etc.)
        propertyIri: axiom.propertyIri,
        restrictionType: axiom.restrictionType,
        fillerIri: axiom.fillerIri,
        cardinality: axiom.cardinality ? (typeof axiom.cardinality === 'string' ? parseInt(axiom.cardinality) : axiom.cardinality) : undefined,
        isDataProperty
      };
    }
    
    return undefined;
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
            {!isInferred && (onEdit || onEditClick) && (
              <button 
                onClick={() => {
                  if (onEditClick) {
                    const initialTab = determineInitialTab();
                    const restrictionData = buildRestrictionData();
                    onEditClick(axiom, initialTab, restrictionData);
                  } else if (onEdit) {
                    setIsEditing(true);
                  }
                }}
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
  onEditClick?: (axiom: Axiom, initialTab?: 'hierarchy' | 'objectRestriction' | 'dataRestriction' | 'classExpression', restrictionData?: any) => void;
  onDelete: (id: string) => void;
  emptyMessage?: string;
  onAddClick?: () => void;
  activeOntologyIri?: string;
  properties?: any[];
  dataProperties?: any[];
}> = ({ title, axioms, inferredAxioms, onAdd, onEdit, onEditClick, onDelete, emptyMessage, onAddClick, activeOntologyIri, properties = [], dataProperties = [] }) => {
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
                onEditClick={onEditClick}
                isInferred={false}
                isInActiveOntology={axiom.ontologyIri === activeOntologyIri}
                ontologyIri={axiom.ontologyIri}
                hasAxiomAnnotations={false}
                properties={properties}
                dataProperties={dataProperties}
              />
            ))}
            {/* Inferred axioms */}
            {inferredAxioms?.map(axiom => (
              <AxiomRow 
                key={`inferred-${axiom.id}`} 
                axiom={axiom} 
                onDelete={onDelete}
                onEdit={onEdit}
                onEditClick={onEditClick}
                isInferred={true}
                ontologyIri="Inferred by reasoner"
                properties={properties}
                dataProperties={dataProperties}
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
 * Sorts annotations to show rdfs:comment first, then rdfs:label, then others alphabetically.
 */
export const AnnotationsDisplay = ({ annotations, onDelete, onEdit }: { 
  annotations?: Record<string, string>, 
  onDelete: (key: string) => void,
  onEdit?: (key: string, currentValue: string) => void 
}) => {
  if (!annotations || Object.keys(annotations).length === 0) {
    return (
        <div className="p-3 text-xs text-gray-400 italic text-center">No annotations</div>
    );
  }
  
  // Priority annotation properties (shown first)
  const priorityProps = [
    'http://www.w3.org/2000/01/rdf-schema#comment',
    'http://www.w3.org/2000/01/rdf-schema#label',
    'http://www.w3.org/2000/01/rdf-schema#isDefinedBy',
    'http://www.w3.org/2000/01/rdf-schema#seeAlso'
  ];
  
  const sortedAnnotations = Object.entries(annotations).sort(([keyA], [keyB]) => {
    const priorityA = priorityProps.indexOf(keyA);
    const priorityB = priorityProps.indexOf(keyB);
    
    // If both have priority, sort by priority order
    if (priorityA !== -1 && priorityB !== -1) {
      return priorityA - priorityB;
    }
    // If only A has priority, A comes first
    if (priorityA !== -1) return -1;
    // If only B has priority, B comes first
    if (priorityB !== -1) return 1;
    // Otherwise sort alphabetically by label
    const labelA = getPropertyLabel(keyA);
    const labelB = getPropertyLabel(keyB);
    return labelA.localeCompare(labelB);
  });
  
  // Check if this is a description annotation (rdfs:comment)
  const isDescription = (key: string) => key === 'http://www.w3.org/2000/01/rdf-schema#comment';
  
  return (
    <div className="space-y-1">
      {sortedAnnotations.map(([key, value]) => {
        const propertyLabel = getPropertyLabel(key);
        const isDesc = isDescription(key);
        
        return (
          <div 
            key={key} 
            className={`group bg-white border rounded-lg overflow-hidden hover:shadow-sm transition-all ${
              isDesc 
                ? 'border-blue-300 hover:border-blue-400 ring-1 ring-blue-100' 
                : 'border-gray-200 hover:border-purple-300'
            }`}
          >
            <div className={`flex items-center justify-between px-3 py-2 border-b ${
              isDesc 
                ? 'bg-gradient-to-r from-blue-100 via-blue-50 to-white border-blue-200' 
                : 'bg-gradient-to-r from-purple-50 via-blue-50 to-gray-50 border-gray-200'
            }`}>
              <div className="flex items-center gap-2 flex-1 min-w-0">
                <span className={`text-xs font-bold ${isDesc ? 'text-blue-800' : 'text-purple-900'}`}>
                  {isDesc ? '📝 ' : ''}{propertyLabel}
                </span>
                {key !== propertyLabel && (
                  <span className="text-[10px] text-gray-500 font-mono truncate" title={key}>
                    ({key})
                  </span>
                )}
              </div>
              <div className="flex items-center gap-1">
                {onEdit && (
                  <button 
                    onClick={() => onEdit(key, value)} 
                    className="opacity-0 group-hover:opacity-100 p-1.5 rounded-md hover:bg-blue-50 transition-all flex-shrink-0"
                    title={`Edit ${propertyLabel} annotation`}
                    aria-label={`Edit ${propertyLabel} annotation`}
                  >
                    <Edit2 size={13} className="text-blue-600" />
                  </button>
                )}
                <button 
                  onClick={() => onDelete(key)} 
                  className="opacity-0 group-hover:opacity-100 p-1.5 rounded-md hover:bg-red-50 transition-all flex-shrink-0"
                  title={`Delete ${propertyLabel} annotation`}
                  aria-label={`Delete ${propertyLabel} annotation`}
                >
                  <Trash2 size={13} className="text-red-600" />
                </button>
              </div>
            </div>
            <div className={`px-3 py-2.5 ${isDesc ? 'bg-blue-50/30' : ''}`}>
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
    const contentRef = useRef<HTMLDivElement>(null);
    const themeClasses = themeColor || 'bg-gradient-to-b from-[#F5F0E6] to-[#E1C688] text-black border-[#D6C9AD]';
    
    useEffect(() => {
      const handleWheel = (e: WheelEvent) => {
        const element = contentRef.current;
        if (!element) return;

        const isAtTop = element.scrollTop === 0;
        const isAtBottom = Math.abs(element.scrollHeight - element.clientHeight - element.scrollTop) < 1;
        
        if ((isAtTop && e.deltaY < 0) || (isAtBottom && e.deltaY > 0)) {
          return;
        }
        
        e.stopPropagation();
      };

      const element = contentRef.current;
      if (element) {
        element.addEventListener('wheel', handleWheel, { passive: false });
        return () => element.removeEventListener('wheel', handleWheel);
      }
    }, [isOpen]);
    
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
              className={`transition-all duration-300 ease-in-out ${isOpen ? 'block' : 'hidden'}`}
            >
                {isOpen && <div ref={contentRef} className="bg-white overflow-y-auto max-h-[600px]">{children}</div>}
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
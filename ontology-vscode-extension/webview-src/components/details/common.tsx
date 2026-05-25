import React, { useState, useRef, useEffect } from "react";
import { ChevronRight, ChevronDown, Plus, Trash2, Edit2, MessageCircle, Tag, MousePointer2 } from "lucide-react";
import { useCollaboration } from "../../contexts/CollaborationContext";
import ManchesterSyntaxEditor from './ManchesterSyntaxEditor';
import type { Axiom } from '../../types';

// Protégé-style: Text is BLACK, only keywords are colored (magenta/pink)
// Links are shown as underlined text for clickable entities
const MANCHESTER_KEYWORDS = ['some', 'only', 'value', 'Self', 'min', 'max', 'exactly', 'and', 'or', 'not', 'that', 'inverse'];

// Entity type icons - Protégé style bullets/shapes
// Classes: yellow/orange filled circle, Object Properties: blue filled square, Data Properties: green filled square
export const EntityIcon: React.FC<{ 
  type: 'class' | 'objectProperty' | 'dataProperty' | 'individual' | 'datatype' | 'annotationProperty' | 'mixed';
  size?: 'sm' | 'md';
}> = ({ type, size = 'sm' }) => {
  const sizeClass = size === 'sm' ? 'w-2.5 h-2.5' : 'w-3 h-3';
  
  switch (type) {
    case 'class':
      // Yellow/Orange filled circle for classes
      return <span className={`inline-block ${sizeClass} rounded-full bg-amber-400 mr-1.5 flex-shrink-0`} />;
    case 'objectProperty':
      // Blue filled square for object properties
      return <span className={`inline-block ${sizeClass} bg-blue-500 mr-1.5 flex-shrink-0`} />;
    case 'dataProperty':
      // Green filled square for data properties
      return <span className={`inline-block ${sizeClass} bg-green-500 mr-1.5 flex-shrink-0`} />;
    case 'individual':
      // Purple diamond for individuals
      return <span className={`inline-block ${sizeClass} bg-purple-500 mr-1.5 flex-shrink-0 rotate-45`} />;
    case 'datatype':
      // Red filled circle for datatypes
      return <span className={`inline-block ${sizeClass} rounded-full bg-red-500 mr-1.5 flex-shrink-0`} />;
    case 'annotationProperty':
      // Light blue square for annotation properties
      return <span className={`inline-block ${sizeClass} bg-sky-400 mr-1.5 flex-shrink-0`} />;
    case 'mixed':
      // Mixed: half blue, half green (for mixed property lists)
      return (
        <span className={`inline-flex mr-1.5 flex-shrink-0`}>
          <span className={`inline-block w-1.5 h-2.5 bg-blue-500`} />
          <span className={`inline-block w-1.5 h-2.5 bg-green-500`} />
        </span>
      );
    default:
      return <span className={`inline-block ${sizeClass} rounded-full bg-gray-400 mr-1.5 flex-shrink-0`} />;
  }
};

// Determine entity type from axiom properties
const getEntityTypeFromAxiom = (axiom: Axiom, dataProperties: any[] = [], properties: any[] = []): 'class' | 'objectProperty' | 'dataProperty' | 'individual' | 'datatype' | 'mixed' => {
  // Check if this is an instance/individual
  if ((axiom as any).type === 'Instance') {
    return 'individual';
  }
  
  const isRestriction = axiom.isRestriction === true || axiom.isRestriction === 'true';
  
  // Check if this is a HasKey axiom with properties array
  if (axiom.type === 'HasKey' && (axiom as any).properties && Array.isArray((axiom as any).properties)) {
    const propsArray = (axiom as any).properties as string[];
    // Check if all properties are data properties or object properties
    const hasDataProp = propsArray.some(p => dataProperties.some(dp => dp.id === p));
    const hasObjProp = propsArray.some(p => properties.some(op => op.id === p) || !dataProperties.some(dp => dp.id === p));
    
    if (hasDataProp && hasObjProp) return 'mixed';
    if (hasDataProp) return 'dataProperty';
    return 'objectProperty';
  }
  
  if (isRestriction && axiom.propertyIri) {
    // Check if it's a data property restriction
    const isDataProp = axiom.propertyIri === 'http://www.w3.org/2002/07/owl#topDataProperty' 
      || dataProperties.some(p => p.id === axiom.propertyIri);
    return isDataProp ? 'dataProperty' : 'objectProperty';
  }
  
  // Check if the axiom references a datatype (xsd: prefix or similar)
  if (axiom.definition && (axiom.definition.includes('xsd:') || axiom.definition.includes('XMLSchema'))) {
    return 'datatype';
  }
  
  // Default to class
  return 'class';
};

// Parse and colorize axiom definition - Protégé style
// Text is BLACK, only keywords (some, and, or, etc.) are MAGENTA/PINK
// Property names in restrictions can be shown as links (underlined)
const ColorizedAxiomDefinition: React.FC<{
  definition: string;
  axiom: Axiom;
  properties?: any[];
  dataProperties?: any[];
  onNavigate?: (iri: string, type: string) => void; // For clickable links
}> = ({ definition, axiom, properties = [], dataProperties = [], onNavigate }) => {
  // Tokenize and colorize: only keywords get color, everything else is black
  // This matches Protégé's style exactly
  
  const isRestriction = axiom.isRestriction === true || axiom.isRestriction === 'true';
  
  // Helper function to extract label from IRI
  const getLabelFromIri = (iri: string): string => {
    if (iri.includes('#')) {
      return iri.split('#').pop() || iri;
    }
    if (iri.includes('/')) {
      return iri.split('/').pop() || iri;
    }
    return iri;
  };
  
  // Helper function to find entity IRI by label
  const findEntityIri = (label: string): { iri: string; type: string } | null => {
    // Remove quotes if present
    const cleanLabel = label.replace(/^['"]|['"]$/g, '');
    
    // Check in data properties
    const dataProp = dataProperties.find(p => getLabelFromIri(p.id) === cleanLabel || p.label === cleanLabel);
    if (dataProp) return { iri: dataProp.id, type: 'dataProperty' };
    
    // Check in object properties
    const objProp = properties.find(p => getLabelFromIri(p.id) === cleanLabel || p.label === cleanLabel);
    if (objProp) return { iri: objProp.id, type: 'objectProperty' };
    
    // If it's in the axiom fillerIri, use that
    if (axiom.fillerIri && (getLabelFromIri(axiom.fillerIri) === cleanLabel)) {
      // Determine if it's a class or individual based on context
      return { iri: axiom.fillerIri, type: 'class' };
    }
    
    return null;
  };
  
  // For restrictions, we can make both property name and filler class clickable
  if (isRestriction && axiom.propertyIri) {
    const parts = definition.split(' ');
    
    if (parts.length >= 3) {
      const keywordIndex = parts.findIndex(p => MANCHESTER_KEYWORDS.includes(p.toLowerCase()));
      
      if (keywordIndex > 0) {
        const propertyParts = parts.slice(0, keywordIndex);
        const keyword = parts[keywordIndex];
        const fillerParts = parts.slice(keywordIndex + 1);
        const fillerText = fillerParts.join(' ');
        
        // Find filler entity info
        const fillerEntity = findEntityIri(fillerText);
        
        return (
          <span className="font-bold text-gray-900">
            {/* Property name - can be a link */}
            <span 
              className={onNavigate ? "text-blue-600 underline cursor-pointer hover:text-blue-800" : "text-gray-900"}
              onClick={onNavigate ? () => onNavigate(axiom.propertyIri!, 'property') : undefined}
              title={axiom.propertyIri}
            >
              '{propertyParts.join(' ')}'
            </span>
            {' '}
            {/* Keyword in magenta/pink - bold */}
            <span className="text-fuchsia-600 font-bold">{keyword}</span>
            {' '}
            {/* Filler class/datatype - clickable if we have the IRI */}
            {fillerEntity && onNavigate ? (
              <span 
                className="text-blue-600 underline cursor-pointer hover:text-blue-800"
                onClick={(e) => { e.stopPropagation(); onNavigate(fillerEntity.iri, fillerEntity.type); }}
                title={fillerEntity.iri}
              >
                '{fillerText}'
              </span>
            ) : (
              <span className="text-gray-900">'{fillerText}'</span>
            )}
          </span>
        );
      }
    }
  }
  
  // For all other definitions (simple class names), make them clickable if we can find the entity
  // First check if the definition itself is an IRI
  const isIri = definition.startsWith('http://') || definition.startsWith('https://') || definition.startsWith('urn:');
  
  if (isIri && onNavigate) {
    // It's a full IRI - extract label and make it clickable as a class
    const label = getLabelFromIri(definition);
    return (
      <span 
        className="text-blue-600 underline cursor-pointer hover:text-blue-800 font-bold"
        onClick={(e) => { e.stopPropagation(); onNavigate(definition, 'class'); }}
        title={definition}
      >
        {label}
      </span>
    );
  }
  
  // Try to find entity by label
  const entityInfo = findEntityIri(definition);
  
  if (entityInfo && onNavigate) {
    // Simple class/entity reference - make it clickable
    return (
      <span 
        className="text-blue-600 underline cursor-pointer hover:text-blue-800 font-bold"
        onClick={(e) => { e.stopPropagation(); onNavigate(entityInfo.iri, entityInfo.type); }}
        title={entityInfo.iri}
      >
        {definition}
      </span>
    );
  }
  
  // For complex definitions with keywords, parse and colorize
  const result: React.ReactNode[] = [];
  let keyIndex = 0;
  
  // Use regex to find both keywords and quoted entity names
  const keywordRegex = new RegExp(`\\b(${MANCHESTER_KEYWORDS.join('|')})\\b|'([^']+)'`, 'gi');
  let lastIndex = 0;
  let match;
  
  while ((match = keywordRegex.exec(definition)) !== null) {
    // Add text before the match (black)
    if (match.index > lastIndex) {
      const beforeText = definition.slice(lastIndex, match.index);
      if (beforeText.trim()) {
        result.push(
          <span key={`text-${keyIndex++}`} className="text-gray-900 font-bold">{beforeText}</span>
        );
      }
    }
    
    if (match[1]) {
      // It's a keyword (magenta/pink, bold)
      result.push(
        <span key={`kw-${keyIndex++}`} className="text-fuchsia-600 font-bold">{match[0]}</span>
      );
    } else if (match[2]) {
      // It's a quoted entity name - make it clickable if we can find it
      const entityName = match[2];
      const entity = findEntityIri(entityName);
      
      if (entity && onNavigate) {
        result.push(
          <span 
            key={`entity-${keyIndex++}`}
            className="text-blue-600 underline cursor-pointer hover:text-blue-800 font-bold"
            onClick={(e) => { e.stopPropagation(); onNavigate(entity.iri, entity.type); }}
            title={entity.iri}
          >
            '{entityName}'
          </span>
        );
      } else {
        result.push(
          <span key={`entity-${keyIndex++}`} className="text-gray-900 font-bold">'{entityName}'</span>
        );
      }
    }
    
    lastIndex = keywordRegex.lastIndex;
  }
  
  // Add remaining text after last match (black)
  if (lastIndex < definition.length) {
    const remainingText = definition.slice(lastIndex);
    if (remainingText.trim()) {
      result.push(
        <span key={`text-${keyIndex++}`} className="text-gray-900 font-bold">{remainingText}</span>
      );
    }
  }
  
  // If no matches found, just return the definition in black
  if (result.length === 0) {
    return <span className="text-gray-900 font-bold">{definition}</span>;
  }
  
  return <span>{result}</span>;
};

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
  onNavigate?: (iri: string, type: string) => void;
  isViewOnly?: boolean;
  onViewOnlyAction?: () => void;
}> = ({ axiom, onDelete, onEdit, onEditClick, isInferred: isInferredProp = false, isInActiveOntology = false, ontologyIri, hasAxiomAnnotations = false, properties = [], dataProperties = [], onNavigate, isViewOnly = false, onViewOnlyAction }) => {
  // Handle isInferred from prop or axiom object (can be boolean or string 'true')
  const isInferred = isInferredProp || axiom.isInferred === true || axiom.isInferred === 'true';
  const [isEditing, setIsEditing] = useState(false);
  const [showAxiomAnnotations, setShowAxiomAnnotations] = useState(false);
  const [isFocused, setIsFocused] = useState(false);

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

  // Handle double-click to edit (Protégé-style)
  const handleDoubleClick = () => {
    if (isViewOnly) { onViewOnlyAction?.(); return; }
    if (!isInferred && (onEdit || onEditClick)) {
      if (onEditClick) {
        const initialTab = determineInitialTab();
        const restrictionData = buildRestrictionData();
        onEditClick(axiom, initialTab, restrictionData);
      } else if (onEdit) {
        setIsEditing(true);
      }
    }
  };

  // Handle keyboard shortcuts (Protégé-style)
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (!isFocused) return;

    if (e.key === 'Enter' && !isInferred && (onEdit || onEditClick)) {
      e.preventDefault();
      if (isViewOnly) { onViewOnlyAction?.(); return; }
      handleDoubleClick();
    } else if (e.key === 'Delete' && !isInferred) {
      e.preventDefault();
      if (isViewOnly) { onViewOnlyAction?.(); return; }
      onDelete(axiom.id);
    } else if (e.key === 'Escape' && isEditing) {
      e.preventDefault();
      setIsEditing(false);
    }
  };

  return (
    <div 
      className={`group flex justify-between items-start px-3 py-2 border-b border-gray-100 last:border-0 hover:bg-blue-50 transition-colors ${
        isInferred ? 'bg-yellow-50' : 'bg-white'
      } ${isFocused ? 'ring-2' : ''} ${!isInferred ? 'cursor-pointer' : ''}`}
      title={ontologyIri ? `Defined in: ${ontologyIri}${!isInferred ? ' (Double-click to edit, Del to delete)' : ''}` : (!isInferred ? 'Double-click to edit, Del to delete' : undefined)}
      data-axiom-id={axiom.id}
      data-axiom-type={axiom.type}
      tabIndex={0}
      onFocus={() => setIsFocused(true)}
      onBlur={() => setIsFocused(false)}
      onDoubleClick={handleDoubleClick}
      onKeyDown={handleKeyDown}
      style={{
        borderLeft: isInActiveOntology ? '3px solid #D97706' : 'none',
        paddingLeft: isInActiveOntology ? '9px' : undefined
      }}
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
          <div className={`flex items-start text-sm break-all leading-relaxed ${isInActiveOntology ? 'font-bold' : ''}`}>
            <EntityIcon type={getEntityTypeFromAxiom(axiom, dataProperties, properties)} />
            <div className="flex-1">
              <ColorizedAxiomDefinition 
                definition={axiom.definition}
                axiom={axiom}
                properties={properties}
                dataProperties={dataProperties}
                onNavigate={onNavigate}
              />
              {isInferred && (
                <span className="ml-2 text-[10px] bg-yellow-200 text-yellow-800 px-1.5 py-0.5 rounded">Inferred</span>
              )}
            </div>
          </div>
          <div className="flex items-center gap-1 ml-2">
            {/* Axiom Annotations button */}
            <button 
              onClick={(e) => {
                e.stopPropagation();
                setShowAxiomAnnotations(!showAxiomAnnotations);
              }}
              className={`p-1 rounded hover:bg-blue-100 transition-all ${
                hasAxiomAnnotations ? 'bg-yellow-100 text-blue-600' : `${isFocused ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'} text-gray-400 hover:text-blue-600`
              }`}
              title="View/edit axiom annotations"
              aria-label="Axiom annotations"
            >
              <MessageCircle size={14} />
            </button>

            {/* Edit button - only for asserted axioms */}
            {!isInferred && (onEdit || onEditClick) && (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  if (isViewOnly) { onViewOnlyAction?.(); return; }
                  if (onEditClick) {
                    const initialTab = determineInitialTab();
                    const restrictionData = buildRestrictionData();
                    onEditClick(axiom, initialTab, restrictionData);
                  } else if (onEdit) {
                    setIsEditing(true);
                  }
                }}
                className={`p-1 rounded hover:bg-blue-100 text-gray-400 hover:text-blue-600 transition-all ${isFocused ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}
                title={isViewOnly ? "View-only: upgrade to edit" : "Edit axiom (or press Enter)"}
                aria-label="Edit"
              >
                <Edit2 size={14} />
              </button>
            )}

            {/* Delete button - only for asserted axioms */}
            {!isInferred && (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  if (isViewOnly) { onViewOnlyAction?.(); return; }
                  onDelete(axiom.id);
                }}
                className={`p-1 rounded hover:bg-red-100 text-gray-400 hover:text-red-600 transition-all ${isFocused ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}
                title="Delete axiom (or press Delete)"
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
  themeColor?: 'yellow' | 'blue' | 'green' | 'orange' | 'purple';
  onNavigate?: (iri: string, type: string) => void;
  isViewOnly?: boolean;
  onViewOnlyAction?: () => void;
}> = ({ title, axioms, inferredAxioms, onAdd, onEdit, onEditClick, onDelete, emptyMessage, onAddClick, activeOntologyIri, properties = [], dataProperties = [], themeColor, onNavigate, isViewOnly = false, onViewOnlyAction }) => {
  const [isAdding, setIsAdding] = useState(false);
  const [isFocused, setIsFocused] = useState(false);

  const handleSave = (definition: string) => {
    onAdd(definition);
    setIsAdding(false);
  };

  const handleAddButtonClick = () => {
    if (isViewOnly) { onViewOnlyAction?.(); return; }
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
    } else if (e.key === 'ArrowDown' && isFocused) {
      // Move focus to first axiom
      e.preventDefault();
      const firstAxiom = e.currentTarget.parentElement?.querySelector('[data-axiom-id]') as HTMLElement;
      firstAxiom?.focus();
    }
  };

  const allAxioms = [...(axioms || []), ...(inferredAxioms || [])];
  const hasContent = allAxioms.length > 0;

  // Clean minimal theme colors - subtle and professional
  const themes = {
    yellow: {
      headerBg: 'bg-amber-50 border-l-2 border-l-amber-400',
      headerText: 'text-stone-700',
      hoverBg: 'hover:bg-amber-100',
      focusRing: 'ring-amber-300',
      plusHover: 'hover:text-amber-600'
    },
    blue: {
      headerBg: 'bg-blue-50 border-l-2 border-l-blue-400',
      headerText: 'text-stone-700',
      hoverBg: 'hover:bg-blue-100',
      focusRing: 'ring-blue-300',
      plusHover: 'hover:text-blue-600'
    },
    green: {
      headerBg: 'bg-emerald-50 border-l-2 border-l-emerald-400',
      headerText: 'text-stone-700',
      hoverBg: 'hover:bg-emerald-100',
      focusRing: 'ring-emerald-300',
      plusHover: 'hover:text-emerald-600'
    },
    orange: {
      headerBg: 'bg-orange-50 border-l-2 border-l-orange-400',
      headerText: 'text-stone-700',
      hoverBg: 'hover:bg-orange-100',
      focusRing: 'ring-orange-300',
      plusHover: 'hover:text-orange-600'
    },
    purple: {
      headerBg: 'bg-purple-50 border-l-2 border-l-purple-400',
      headerText: 'text-stone-700',
      hoverBg: 'hover:bg-purple-100',
      focusRing: 'ring-purple-300',
      plusHover: 'hover:text-purple-600'
    }
  };

  const theme = themeColor ? themes[themeColor] : null;
    const themeBorder = {
        blue: 'var(--info)',
        green: 'var(--success)',
        orange: 'var(--warning)',
        yellow: 'var(--warning)',
        purple: 'var(--accent)'
    }[themeColor || 'blue'];

  return (
    <div className="mb-4 last:mb-0">
      {theme ? (
        // Protégé-style header with golden/yellow accent for classes
        <button 
          onClick={handleAddButtonClick}
          onKeyDown={handleHeaderKeyDown}
          className={`w-full flex justify-between items-center px-3 py-2 transition-colors ${isFocused ? 'ring-2 ring-amber-300' : ''} font-medium shadow-sm text-gray-900`}
          style={{
            backgroundColor: isFocused ? 'var(--selected-bg)' : '#FEF3C7',
            borderLeft: `3px solid ${themeBorder}`,
            color: '#111827',
          }}
          onFocus={() => setIsFocused(true)}
          onBlur={() => setIsFocused(false)}
        >
          <span className="text-xs font-medium">{title}</span>
          {onAddClick && (
            <span className="transition-colors hover-text-accent" style={{ color: 'var(--text-tertiary)' }}>
              <Plus size={16} strokeWidth={2.5} />
            </span>
          )}
        </button>
      ) : (
        // Default header style
        <div
          className={`flex justify-between items-center mb-1 px-2 py-1 rounded ${isFocused ? 'ring-2 ring-purple-300' : ''}`}
          tabIndex={0}
          onFocus={() => setIsFocused(true)}
          onBlur={() => setIsFocused(false)}
          onKeyDown={handleHeaderKeyDown}
        >
          <h4 className="text-xs font-bold text-tertiary uppercase tracking-wider">
            {title}
            {isFocused && !isViewOnly && <span className="ml-2 text-[10px] text-accent">(Press Enter to add)</span>}
          </h4>
          <button
            onClick={handleAddButtonClick}
            className="p-1 hover-overlay rounded text-tertiary hover-text-accent transition-colors"
            title={isViewOnly ? "View-only: upgrade to edit" : `Add ${title} (Enter when focused)`}
          >
            <Plus size={14} />
          </button>
        </div>
      )}
      <div
        className={`border overflow-hidden shadow-sm ${theme ? 'border-t-0 rounded-b-sm' : 'rounded-md'}`}
        style={{ backgroundColor: 'var(--surface-1)', borderColor: 'var(--border)' }}
      >
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
                onNavigate={onNavigate}
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
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
                onNavigate={onNavigate}
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
              />
            ))}
          </>
        ) : (
          !isAdding && (
            <div className="p-2 text-xs text-gray-400 italic bg-gray-50">
              {emptyMessage !== undefined ? emptyMessage : "No axioms defined"}
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
 * in Protégé style - grouped by property with full URI displayed.
 * Sorts annotations to show rdfs:comment first, then rdfs:label, then others alphabetically.
 */
export const AnnotationsDisplay = ({ annotations, onDelete, onEdit, isViewOnly = false, onViewOnlyAction }: {
  annotations?: Record<string, string>,
  onDelete: (key: string) => void,
  onEdit?: (key: string, currentValue: string) => void,
  isViewOnly?: boolean,
  onViewOnlyAction?: () => void,
}) => {
  if (!annotations || Object.keys(annotations).length === 0) {
    return (
        <div className="p-3 text-xs text-gray-400 italic text-center">No annotations</div>
    );
  }
  
  // Priority annotation properties (shown first)
  const priorityProps = [
    'http://www.w3.org/2000/01/rdf-schema#label',
    'http://www.w3.org/2000/01/rdf-schema#comment',
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
  
  return (
    <div className="rounded-sm overflow-hidden" style={{ border: '1px solid var(--border)' }}>
      {sortedAnnotations.map(([key, value]) => {
        const propertyLabel = getPropertyLabel(key);
        
        return (
          <div
            key={key}
            className="group transition-colors border-b last:border-b-0"
            style={{ backgroundColor: 'var(--surface-1)', borderColor: 'var(--border)' }}
          >
            {/* Property header row - Clean minimal style */}
            <div
              className="flex items-center justify-between px-3 py-1.5 border-b"
              style={{ backgroundColor: 'var(--surface-2)', borderColor: 'var(--border)' }}
            >
              <div className="flex items-center gap-2 flex-1 min-w-0">
                <span className="text-xs font-medium text-secondary">
                  {propertyLabel}
                </span>
                <span className="text-[10px] text-tertiary font-mono truncate" title={key}>
                  ({key})
                </span>
              </div>
              <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                {onEdit && (
                  <button
                    onClick={() => isViewOnly ? onViewOnlyAction?.() : onEdit(key, value)}
                    className="p-1 rounded hover-overlay transition-all"
                    title={isViewOnly ? "View-only: upgrade to edit" : `Edit ${propertyLabel}`}
                  >
                    <Edit2 size={12} className="text-tertiary hover-text-accent" />
                  </button>
                )}
                <button
                  onClick={() => isViewOnly ? onViewOnlyAction?.() : onDelete(key)}
                  className="p-1 rounded hover-overlay transition-all"
                  title={isViewOnly ? "View-only: upgrade to edit" : `Delete ${propertyLabel}`}
                >
                  <Trash2 size={12} className="text-tertiary hover-text-error" />
                </button>
              </div>
            </div>
            {/* Value row */}
            <div className="px-3 py-2">
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
        <div className="border rounded-sm flex flex-col" style={{ backgroundColor: 'var(--color-surface)', borderColor: 'var(--color-border)' }}>
            <div className="text-xs font-semibold p-1.5 flex items-center justify-between border-b" style={{ borderColor: 'var(--color-border)', backgroundColor: 'var(--color-background)', color: 'var(--color-text)' }}>
                <div className="flex items-center">
                    <button 
                      onClick={() => setIsOpen(!isOpen)} 
                      className="mr-1 p-0.5 rounded hover:opacity-70"
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
                {isOpen && <div ref={contentRef} className="overflow-y-auto max-h-[600px]" style={{ backgroundColor: 'var(--color-surface)' }}>{children}</div>}
            </div>
        </div>
    );
};

export const MultiSelectItem: React.FC<{
  item: string;
  onDelete: (item: string) => void;
  entityType?: 'class' | 'objectProperty' | 'dataProperty' | 'datatype' | 'annotationProperty' | 'individual';
  themeColor?: 'blue' | 'green' | 'orange' | 'yellow' | 'purple';
  isInferred?: boolean;
}> = ({ item, onDelete, entityType, themeColor = 'blue', isInferred = false }) => {
    const [showInfo, setShowInfo] = useState(false);
    const [copied, setCopied] = useState(false);

    const handleCopyIri = () => {
        navigator.clipboard.writeText(item).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
        }).catch(() => {
            // fallback: select text
        });
    };
    // Check if this is an inverse property expression
    const inverseMatch = item.match(/^inverse\((.+)\)$/i);
    const isInverse = !!inverseMatch;
    const propertyIri = isInverse ? inverseMatch[1] : item;
    
    // Check if this is a restriction expression (contains 'some', 'only', 'min', 'max', 'exactly', 'value')
    const restrictionKeywords = ['some', 'only', 'min', 'max', 'exactly', 'value', 'and', 'or', 'not'];
    const isRestrictionExpression = restrictionKeywords.some(kw => 
        item.includes(` ${kw} `) || item.startsWith(`${kw} `) || item.endsWith(` ${kw}`)
    );
    
    // Format display name - keep prefix for datatypes
    const getDisplayName = (iri: string): string => {
        // Check if it's a datatype with known prefixes
        if (iri.includes('XMLSchema#')) {
            return 'xsd:' + iri.split('#').pop();
        } else if (iri.includes('rdf-syntax-ns#')) {
            return 'rdf:' + iri.split('#').pop();
        } else if (iri.includes('rdf-schema#') || iri.includes('2000/01/rdf-schema#')) {
            return 'rdfs:' + iri.split('#').pop();
        } else if (iri.includes('owl#')) {
            return 'owl:' + iri.split('#').pop();
        }
        // Default: just get the local name
        return iri.split('#').pop() || iri;
    };
    
    const displayName = getDisplayName(propertyIri);
    
    // Detect entity type from themeColor if not provided
    // blue = objectProperty, green = dataProperty, yellow/orange = class, purple = individual
    let detectedType = entityType;
    if (!detectedType) {
        if (item.includes('XMLSchema#') || item.includes('rdf-syntax-ns#') || item.includes('rdf-schema#') || 
            item.startsWith('xsd:') || item.startsWith('rdf:') || item.startsWith('rdfs:') ||
            item.includes('Literal') || item.includes('PlainLiteral') || item.includes('XMLLiteral')) {
            detectedType = 'datatype';
        } else if (themeColor === 'blue') {
            detectedType = 'objectProperty';
        } else if (themeColor === 'green') {
            detectedType = 'dataProperty';
        } else if (themeColor === 'purple') {
            detectedType = 'individual';
        } else {
            detectedType = 'class';
        }
    }
    
    // Icon based on entity type - matches Protégé style
    // Classes: yellow/orange circle, ObjectProperties: blue square, DataProperties: green square
    // Individuals: purple diamond, Datatypes: red circle
    const getIcon = () => {
        switch (detectedType) {
            case 'objectProperty':
                return <span className="w-2.5 h-2.5 bg-blue-500 mr-1 flex-shrink-0" title="Object Property" />;
            case 'dataProperty':
                return <span className="w-2.5 h-2.5 bg-green-500 mr-1 flex-shrink-0" title="Data Property" />;
            case 'datatype':
                return <span className="w-2.5 h-2.5 bg-red-500 rounded-full mr-1 flex-shrink-0" title="Datatype" />;
            case 'individual':
                return <span className="w-2.5 h-2.5 bg-purple-500 rotate-45 mr-1 flex-shrink-0" title="Individual" />;
            case 'annotationProperty':
                return <span className="w-2.5 h-2.5 bg-sky-400 mr-1 flex-shrink-0" title="Annotation Property" />;
            case 'class':
            default:
                return <span className="w-2.5 h-2.5 bg-amber-400 rounded-full mr-1 flex-shrink-0" title="Class" />;
        }
    };
    
    // Format restriction expression with colored keywords (text is black, keywords are magenta)
    const formatRestrictionExpression = (expr: string): React.ReactNode => {
        // Split by restriction keywords and color them
        const parts: React.ReactNode[] = [];
        let keyIndex = 0;
        
        // Find and replace keywords with colored versions
        const regex = /\b(some|only|min|max|exactly|value|and|or|not|inverse)\b/gi;
        let lastIndex = 0;
        let match;
        
        while ((match = regex.exec(expr)) !== null) {
            // Add text before the keyword (BLACK)
            if (match.index > lastIndex) {
                parts.push(<span key={`text-${keyIndex++}`} className="text-gray-900">{expr.slice(lastIndex, match.index)}</span>);
            }
            // Add the colored keyword (MAGENTA/PINK)
            parts.push(<span key={`kw-${keyIndex++}`} className="text-fuchsia-600 font-bold">{match[0]}</span>);
            lastIndex = regex.lastIndex;
        }
        // Add remaining text (BLACK)
        if (lastIndex < expr.length) {
            parts.push(<span key={`text-${keyIndex++}`} className="text-gray-900">{expr.slice(lastIndex)}</span>);
        }
        
        return parts.length > 0 ? parts : expr;
    };
    
    // Hover background color based on theme
    const hoverBgColor = themeColor === 'green' ? 'hover:bg-green-50' : themeColor === 'orange' ? 'hover:bg-orange-50' : themeColor === 'purple' ? 'hover:bg-purple-50' : 'hover:bg-blue-50';
    
    return (
        <div className={`group border-b border-gray-100 last:border-0 ${isInferred ? 'bg-yellow-50' : 'bg-white'} ${hoverBgColor} transition-colors`}>
            <div className="flex justify-between items-center p-1.5">
                <div className="flex items-center">
                    {/* Entity type icon */}
                    {getIcon()}
                    {isInverse ? (
                        <span className="text-sm font-bold text-gray-900">
                            <span className="text-fuchsia-600 font-bold">inverse</span>
                            <span className="text-gray-900">(</span>
                            <span className="text-gray-900">'{displayName}'</span>
                            <span className="text-gray-900">)</span>
                        </span>
                    ) : isRestrictionExpression ? (
                        <span className="text-sm font-bold">{formatRestrictionExpression(item)}</span>
                    ) : (
                        <span className="text-sm font-bold text-gray-900">'{displayName}'</span>
                    )}
                    {isInferred && (
                        <span className="ml-2 text-[10px] bg-yellow-200 text-yellow-800 px-1.5 py-0.5 rounded">Inferred</span>
                    )}
                </div>
                {!isInferred && (
                    <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-all">
                        <button
                            onClick={() => setShowInfo(v => !v)}
                            className={`p-1 rounded transition-all ${showInfo ? 'text-blue-600 bg-blue-50 opacity-100' : 'text-gray-400 hover:bg-blue-100 hover:text-blue-600'}`}
                            title="Show IRI info"
                            aria-label="Axiom info"
                        >
                            <MessageCircle size={14} />
                        </button>
                        <button
                            onClick={() => onDelete(item)}
                            className="p-1 rounded hover:bg-red-100 text-gray-400 hover:text-red-600"
                            title={`Remove '${displayName}'`}
                            aria-label={`Remove ${displayName}`}
                        >
                            <Trash2 size={14} />
                        </button>
                    </div>
                )}
            </div>
            {showInfo && !isInferred && (
                <div className="mx-1.5 mb-1.5 p-2 bg-blue-50 border border-blue-200 rounded text-xs">
                    <div className="text-[10px] font-semibold text-blue-700 uppercase tracking-wide mb-1">IRI</div>
                    <div className="flex items-start gap-2">
                        <span className="font-mono text-gray-700 break-all flex-1 leading-relaxed select-all">{item}</span>
                        <button
                            onClick={handleCopyIri}
                            className={`shrink-0 px-1.5 py-0.5 rounded text-[10px] font-medium border transition-colors ${
                                copied
                                    ? 'bg-green-100 border-green-300 text-green-700'
                                    : 'bg-white border-blue-300 text-blue-600 hover:bg-blue-100'
                            }`}
                            title="Copy IRI to clipboard"
                        >
                            {copied ? '✓ Copied' : 'Copy'}
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
};

export const MultiSelectSection: React.FC<{
    title: string;
    items: string[] | undefined;
    inferredItems?: string[] | undefined;
    onAddClick?: () => void;
    onDelete: (item: string) => void;
    themeColor?: 'blue' | 'green' | 'orange' | 'yellow' | 'purple'; // For header styling
    itemEntityType?: 'class' | 'objectProperty' | 'dataProperty' | 'datatype' | 'annotationProperty' | 'individual'; // For item icons
    isViewOnly?: boolean;
    onViewOnlyAction?: () => void;
}> = ({ title, items, inferredItems, onAddClick, onDelete, themeColor = 'blue', itemEntityType, isViewOnly = false, onViewOnlyAction }) => {
    const [isSelected, setIsSelected] = useState(false);
    
    // Clean minimal theme colors - Protégé-style
    const themes = {
        blue: {
            headerBg: 'bg-blue-50 border-l-2 border-l-blue-500',
            headerText: 'text-stone-700',
            hoverBg: 'hover:bg-blue-100',
            selectedBg: 'bg-blue-100',
            plusColor: 'text-stone-400 hover:text-blue-600'
        },
        green: {
            headerBg: 'bg-emerald-50 border-l-2 border-l-emerald-500',
            headerText: 'text-stone-700',
            hoverBg: 'hover:bg-emerald-100',
            selectedBg: 'bg-emerald-100',
            plusColor: 'text-stone-400 hover:text-emerald-600'
        },
        orange: {
            headerBg: 'bg-orange-50 border-l-2 border-l-orange-400',
            headerText: 'text-stone-700',
            hoverBg: 'hover:bg-orange-100',
            selectedBg: 'bg-orange-100',
            plusColor: 'text-stone-400 hover:text-orange-600'
        },
        yellow: {
            headerBg: 'bg-amber-50 border-l-2 border-l-amber-400',
            headerText: 'text-stone-700',
            hoverBg: 'hover:bg-amber-100',
            selectedBg: 'bg-amber-100',
            plusColor: 'text-stone-400 hover:text-amber-600'
        },
        purple: {
            headerBg: 'bg-purple-50 border-l-2 border-l-purple-400',
            headerText: 'text-stone-700',
            hoverBg: 'hover:bg-purple-100',
            selectedBg: 'bg-purple-100',
            plusColor: 'text-stone-400 hover:text-purple-600'
        }
    };
    
    const theme = themes[themeColor];
    
    const handleHeaderClick = () => {
        setIsSelected(true);
        if (isViewOnly) { onViewOnlyAction?.(); return; }
        if (onAddClick) {
            onAddClick();
        }
    };
    
    return (
         <div className="mb-3 last:mb-0">
             {/* Clean minimal clickable header */}
             <button 
                onClick={handleHeaderClick}
                onBlur={() => setIsSelected(false)}
                className={`w-full flex justify-between items-center px-2 py-1.5 transition-colors ${
                    isSelected 
                        ? `${theme.selectedBg} ${theme.headerText}` 
                        : `${theme.headerBg} ${theme.headerText} ${theme.hoverBg}`
                }`}
             >
                 <span className="text-xs font-medium">{title}</span>
                 {onAddClick && (
                    <span className={`${theme.plusColor} transition-colors`}>
                        <Plus size={14}/>
                    </span>
                 )}
             </button>
             {/* Content area */}
             <div className="bg-white border border-t-0 border-gray-200 rounded-b-sm overflow-hidden">
                 {items && items.length > 0 ? (
                    items.map(item => <MultiSelectItem key={item} item={item} onDelete={(i: string) => { if (isViewOnly) { onViewOnlyAction?.(); return; } onDelete(i); }} themeColor={themeColor} entityType={itemEntityType} />)
                 ) : null}
                 {inferredItems && inferredItems.length > 0 ? (
                    inferredItems.map(item => <MultiSelectItem key={item} item={item} onDelete={() => {}} themeColor={themeColor} entityType={itemEntityType} isInferred={true} />)
                 ) : null}
                 {(!items || items.length === 0) && (!inferredItems || inferredItems.length === 0) && (
                    <div className="p-2 text-xs text-gray-400 italic">
                        {/* Empty - matches Protégé's minimal empty state */}
                    </div>
                 )}
             </div>
         </div>
    );
};

export const CollaboratorPresenceBar: React.FC<{ entityId: string }> = ({ entityId }) => {
  const { state } = useCollaboration();
  const viewers = Array.from(state.activeUsers.values()).filter(
    u => u.cursorPosition === entityId
  );
  if (viewers.length === 0) return null;
  return (
    <div className="flex items-center gap-1.5 px-3 py-1 bg-indigo-50 border-b border-indigo-100">
      <MousePointer2 size={11} className="text-indigo-400 flex-shrink-0" />
      <span className="text-[10px] text-indigo-500 mr-0.5">Also viewing:</span>
      {viewers.map(u => (
        <span
          key={u.userId}
          className="inline-flex items-center gap-1 text-[10px] font-medium px-1.5 py-0.5 rounded-full"
          style={{ backgroundColor: `${u.color}20`, color: u.color, border: `1px solid ${u.color}60` }}
          title={u.username}
        >
          <span
            className="w-2 h-2 rounded-full flex-shrink-0"
            style={{ backgroundColor: u.color }}
          />
          {u.username}
        </span>
      ))}
    </div>
  );
};
import React, { useState, useRef, useEffect, useCallback } from "react";
import { ChevronRight, ChevronDown, Plus, Trash2, Edit2, MessageCircle, Tag, MousePointer2, HelpCircle, AtSign, Circle, Loader } from "lucide-react";
import { useCollaboration } from "../../contexts/CollaborationContext";
import apiClient from "../../services/apiClient";
import axiomAnnotationService from "../../services/axiomAnnotationService";
import ManchesterSyntaxEditor from './ManchesterSyntaxEditor';
import type { Axiom } from '../../types';

type JustificationAxiom = { type: string; manchester: string; entities: { iri: string; label: string; type: string }[]; isAsserted: boolean };
type Justification = { index: number; axioms: JustificationAxiom[]; isAsserted: boolean };

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
  definition: string | undefined | null;
  axiom: Axiom;
  properties?: any[];
  dataProperties?: any[];
  onNavigate?: (iri: string, type: string) => void; // For clickable links
}> = ({ definition, axiom, properties = [], dataProperties = [], onNavigate }) => {
  // Tokenize and colorize: only keywords get color, everything else is black
  // This matches Protégé's style exactly
  if (!definition) return <span className="text-gray-400 italic text-xs">(no definition)</span>;

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
  projectId?: string;
  parentEntityIri?: string;
  sectionName?: string;
}> = ({ axiom, onDelete, onEdit, onEditClick, isInferred: isInferredProp = false, isInActiveOntology = false, ontologyIri, hasAxiomAnnotations: hasAxiomAnnotationsProp = false, properties = [], dataProperties = [], onNavigate, isViewOnly = false, onViewOnlyAction, projectId, parentEntityIri, sectionName }) => {
  // Handle isInferred from prop or axiom object (can be boolean or string 'true')
  const isInferred = isInferredProp || axiom.isInferred === true || axiom.isInferred === 'true';
  const [isEditing, setIsEditing] = useState(false);
  const [showAxiomAnnotations, setShowAxiomAnnotations] = useState(false);
  const [isFocused, setIsFocused] = useState(false);
  const [axiomAnnotations, setAxiomAnnotations] = useState<Array<{ property: string; value: string; language?: string }>>([]);
  const [newAnnotProp, setNewAnnotProp] = useState('http://www.w3.org/2000/01/rdf-schema#comment');
  const [newAnnotValue, setNewAnnotValue] = useState('');
  const [showAddAxiomAnnotForm, setShowAddAxiomAnnotForm] = useState(false);
  const [annotationsLoading, setAnnotationsLoading] = useState(false);
  const [deletingAnnotIdx, setDeletingAnnotIdx] = useState<number | null>(null);
  const [isSavingAnnot, setIsSavingAnnot] = useState(false);

  const relatedIri = axiom.id?.includes('|||') ? axiom.id.split('|||')[0] : axiom.id;
  const hasAxiomAnnotations = hasAxiomAnnotationsProp || axiomAnnotations.length > 0;

  const loadAxiomAnnotations = useCallback(async () => {
    if (!projectId || !parentEntityIri || !relatedIri) return;
    setAnnotationsLoading(true);
    try {
      const data = await axiomAnnotationService.getAnnotations(
        projectId, parentEntityIri, relatedIri, sectionName,
      );
      setAxiomAnnotations(data);
    } catch (error) {
      console.warn('Failed to load axiom annotations', error);
      setAxiomAnnotations([]);
    } finally {
      setAnnotationsLoading(false);
    }
  }, [projectId, parentEntityIri, relatedIri, sectionName]);

  useEffect(() => {
    if (showAxiomAnnotations) {
      void loadAxiomAnnotations();
    }
  }, [showAxiomAnnotations, loadAxiomAnnotations]);

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
      className={`group border-b last:border-0 transition-colors ${isFocused ? 'ring-2' : ''} ${!isInferred ? 'cursor-pointer' : ''}`}
      data-axiom-id={axiom.id}
      data-axiom-type={axiom.type}
      style={{
        borderColor: 'var(--border)',
        backgroundColor: isInferred ? 'var(--warning-tint)' : 'var(--surface-1)',
        borderLeft: isInActiveOntology ? '3px solid #D97706' : 'none',
      }}
    >
    <div
      className="flex justify-between items-start px-3 py-2"
      title={ontologyIri ? `Defined in: ${ontologyIri}${!isInferred ? ' (Double-click to edit, Del to delete)' : ''}` : (!isInferred ? 'Double-click to edit, Del to delete' : undefined)}
      tabIndex={0}
      onFocus={() => setIsFocused(true)}
      onBlur={() => setIsFocused(false)}
      onDoubleClick={handleDoubleClick}
      onKeyDown={handleKeyDown}
      style={{ paddingLeft: isInActiveOntology ? '9px' : undefined }}
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
                <span
                  className="ml-2 text-[10px] px-1.5 py-0.5 rounded font-medium"
                  style={{ backgroundColor: 'var(--warning-tint)', color: 'var(--warning)' }}
                >
                  Inferred
                </span>
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
    </div>

      {/* Axiom Annotations Panel */}
      {showAxiomAnnotations && (
        <div className="mx-3 mb-2 p-2 bg-amber-50 border border-amber-200 rounded text-xs">
          <div className="font-semibold text-amber-800 mb-1">Axiom Annotations</div>
          {annotationsLoading ? (
            <div className="text-gray-500 italic">Loading…</div>
          ) : (
            <>
              {axiomAnnotations.map((ann, idx) => (
                <div key={idx} className={`flex items-start justify-between bg-white border border-amber-100 rounded px-2 py-1 mb-1 transition-opacity duration-300 ${deletingAnnotIdx === idx ? 'opacity-40' : ''}`}>
                  <div>
                    <div className="text-[10px] font-medium text-amber-700">{ann.property.split('#').pop() || ann.property.split('/').pop()}</div>
                    <div className="text-[10px] text-gray-700">{ann.value}</div>
                  </div>
                  {!isInferred && !isViewOnly && projectId && parentEntityIri && (
                    <button
                      onClick={async (e) => {
                        e.stopPropagation();
                        setDeletingAnnotIdx(idx);
                        try {
                          await axiomAnnotationService.deleteAnnotation(projectId, {
                            entityIri: parentEntityIri,
                            relatedIri,
                            sectionName,
                            annotationProperty: ann.property,
                            value: ann.value,
                          });
                          await loadAxiomAnnotations();
                        } finally {
                          setDeletingAnnotIdx(null);
                        }
                      }}
                      disabled={deletingAnnotIdx === idx}
                      className="ml-1 p-0.5 text-gray-400 hover:text-red-500 disabled:opacity-50"
                      title="Remove annotation"
                    >
                      {deletingAnnotIdx === idx ? <Loader size={10} className="animate-spin" /> : <Trash2 size={10} />}
                    </button>
                  )}
                </div>
              ))}
              {!isInferred && !isViewOnly && projectId && parentEntityIri && (
                showAddAxiomAnnotForm ? (
                  <div className="mt-1 p-2 bg-white border border-amber-300 rounded" onClick={(e) => e.stopPropagation()}>
                    <select
                      value={newAnnotProp}
                      onChange={(e) => setNewAnnotProp(e.target.value)}
                      className="w-full mb-1 px-2 py-1 text-[10px] border border-amber-200 rounded"
                    >
                      <option value="http://www.w3.org/2000/01/rdf-schema#comment">rdfs:comment</option>
                      <option value="http://www.w3.org/2000/01/rdf-schema#label">rdfs:label</option>
                      <option value="http://www.w3.org/2000/01/rdf-schema#seeAlso">rdfs:seeAlso</option>
                      <option value="http://www.w3.org/2000/01/rdf-schema#isDefinedBy">rdfs:isDefinedBy</option>
                    </select>
                    <textarea
                      autoFocus
                      value={newAnnotValue}
                      onChange={(e) => setNewAnnotValue(e.target.value)}
                      rows={2}
                      className="w-full mb-1 px-2 py-1 text-[10px] border border-amber-200 rounded resize-none"
                      placeholder="Annotation value…"
                    />
                    <div className="flex justify-end gap-1">
                      <button className="px-2 py-0.5 text-[10px] bg-gray-100 rounded" onClick={() => { setShowAddAxiomAnnotForm(false); setNewAnnotValue(''); }}>Cancel</button>
                      <button
                        disabled={!newAnnotValue.trim() || isSavingAnnot}
                        className="px-2 py-0.5 text-[10px] bg-amber-500 text-white rounded disabled:opacity-40 flex items-center gap-1"
                        onClick={async () => {
                          setIsSavingAnnot(true);
                          try {
                            await axiomAnnotationService.addAnnotation(projectId, {
                              entityIri: parentEntityIri,
                              relatedIri,
                              sectionName,
                              annotationProperty: newAnnotProp,
                              value: newAnnotValue.trim(),
                            });
                            setNewAnnotValue('');
                            setShowAddAxiomAnnotForm(false);
                            await loadAxiomAnnotations();
                          } finally {
                            setIsSavingAnnot(false);
                          }
                        }}
                      >{isSavingAnnot ? <><Loader size={10} className="animate-spin" /> Saving…</> : 'Save'}</button>
                    </div>
                  </div>
                ) : (
                  <button
                    className="mt-1 text-[10px] text-amber-700 hover:text-amber-900 flex items-center gap-0.5"
                    onClick={(e) => { e.stopPropagation(); setShowAddAxiomAnnotForm(true); }}
                  >
                    <Plus size={11} /> Add annotation
                  </button>
                )
              )}
              {axiomAnnotations.length === 0 && !showAddAxiomAnnotForm && (
                <div className="text-gray-500 italic">No annotations on this axiom.</div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
};

export const AxiomSubsection: React.FC<{
  title: string;
  axioms: Axiom[] | undefined;
  inferredAxioms?: Axiom[];
  /** Protégé-style: inferred rows only when hierarchy/view is in inferred mode */
  viewMode?: 'asserted' | 'inferred';
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
  projectId?: string;
  parentEntityIri?: string;
}> = ({ title, axioms, inferredAxioms, viewMode = 'asserted', onAdd, onEdit, onEditClick, onDelete, emptyMessage, onAddClick, activeOntologyIri, properties = [], dataProperties = [], themeColor, onNavigate, isViewOnly = false, onViewOnlyAction, projectId, parentEntityIri }) => {
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

  const visibleInferred = viewMode === 'inferred' ? (inferredAxioms || []) : [];
  const allAxioms = [...(axioms || []), ...visibleInferred];
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
        className={`border shadow-sm ${theme ? 'border-t-0 rounded-b-sm' : 'rounded-md'} ${allAxioms.length > 5 ? 'overflow-y-auto max-h-48' : 'overflow-hidden'}`}
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
                properties={properties}
                dataProperties={dataProperties}
                onNavigate={onNavigate}
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
                projectId={projectId}
                parentEntityIri={parentEntityIri}
                sectionName={title}
              />
            ))}
            {/* Inferred axioms (Protégé: only in inferred view) */}
            {visibleInferred.map(axiom => (
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
type AnnotationMap = Record<string, string | string[]>;

const flattenAnnotations = (annotations?: AnnotationMap) => {
  const rows: Array<{ property: string; value: string; rowKey: string }> = [];
  if (!annotations) return rows;
  const seen = new Set<string>();
  Object.entries(annotations).forEach(([property, rawValue]) => {
    const values = Array.isArray(rawValue) ? rawValue : [rawValue];
    values.forEach((value) => {
      const key = `${property}::${value}`;
      if (seen.has(key)) return;
      seen.add(key);
      rows.push({ property, value, rowKey: key });
    });
  });
  return rows;
};

export const AnnotationsDisplay = ({ annotations, onDelete, onEdit, isViewOnly = false, onViewOnlyAction }: {
  annotations?: AnnotationMap,
  onDelete: (property: string, value?: string) => void,
  onEdit?: (property: string, currentValue: string) => void,
  isViewOnly?: boolean,
  onViewOnlyAction?: () => void,
}) => {
  const rows = flattenAnnotations(annotations);
  if (rows.length === 0) {
    return (
        <div className="p-3 text-xs text-gray-400 italic text-center">No annotations</div>
    );
  }
  
  const priorityProps = [
    'http://www.w3.org/2000/01/rdf-schema#label',
    'http://www.w3.org/2000/01/rdf-schema#comment',
    'http://www.w3.org/2000/01/rdf-schema#isDefinedBy',
    'http://www.w3.org/2000/01/rdf-schema#seeAlso'
  ];
  
  const sortedRows = [...rows].sort((a, b) => {
    const priorityA = priorityProps.indexOf(a.property);
    const priorityB = priorityProps.indexOf(b.property);
    if (priorityA !== -1 && priorityB !== -1) return priorityA - priorityB;
    if (priorityA !== -1) return -1;
    if (priorityB !== -1) return 1;
    return getPropertyLabel(a.property).localeCompare(getPropertyLabel(b.property));
  });
  
  return (
    <div className="rounded-sm overflow-hidden" style={{ border: '1px solid var(--border)' }}>
      {sortedRows.map((row) => {
        const propertyLabel = getPropertyLabel(row.property);
        
        return (
          <div
            key={row.rowKey}
            className="group transition-colors border-b last:border-b-0"
            style={{ backgroundColor: 'var(--surface-1)', borderColor: 'var(--border)' }}
          >
            <div
              className="flex items-center justify-between px-3 py-1.5 border-b"
              style={{ backgroundColor: 'var(--surface-2)', borderColor: 'var(--border)' }}
            >
              <div className="flex items-center gap-2 flex-1 min-w-0">
                <span className="text-xs font-medium text-secondary">
                  {propertyLabel}
                </span>
                <span className="text-[10px] text-tertiary font-mono truncate" title={row.property}>
                  ({row.property})
                </span>
              </div>
              <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                {onEdit && (
                  <button
                    onClick={() => isViewOnly ? onViewOnlyAction?.() : onEdit(row.property, row.value)}
                    className="p-1 rounded hover-overlay transition-all"
                    title={isViewOnly ? "View-only: upgrade to edit" : `Edit ${propertyLabel}`}
                  >
                    <Edit2 size={12} className="text-tertiary hover-text-accent" />
                  </button>
                )}
                <button
                  onClick={() => isViewOnly ? onViewOnlyAction?.() : onDelete(row.property, row.value)}
                  className="p-1 rounded hover-overlay transition-all"
                  title={isViewOnly ? "View-only: upgrade to edit" : `Delete ${propertyLabel}`}
                >
                  <Trash2 size={12} className="text-tertiary hover-text-error" />
                </button>
              </div>
            </div>
            <div className="px-3 py-2">
              <AnnotationValue value={row.value} />
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
  onDelete: (item: string) => Promise<void> | void;
  onEdit?: (item: string) => void;
  entityType?: 'class' | 'objectProperty' | 'dataProperty' | 'datatype' | 'annotationProperty' | 'individual';
  themeColor?: 'blue' | 'green' | 'orange' | 'yellow' | 'purple';
  isInferred?: boolean;
  sectionName?: string;
  onNavigate?: (iri: string, type: string) => void;
  projectId?: string;
  parentEntityIri?: string;
}> = ({ item, onDelete, onEdit, entityType, themeColor = 'blue', isInferred = false, sectionName, onNavigate, projectId, parentEntityIri }) => {
    const [showExplanation, setShowExplanation] = useState(false);
    const [isDeleting, setIsDeleting] = useState(false);
    const [showAnnotations, setShowAnnotations] = useState(false);
    const [copied, setCopied] = useState(false);
    const [isHovered, setIsHovered] = useState(false);
    const [justType, setJustType] = useState<'regular' | 'laconic'>('laconic');
    const [limitJustifications, setLimitJustifications] = useState(true);
    const [justLimit, setJustLimit] = useState(3);
    const [showLaconicExpl, setShowLaconicExpl] = useState(false);
    const [explanationData, setExplanationData] = useState<Justification[] | null>(null);
    const [explanationLoading, setExplanationLoading] = useState(false);
    const [explanationError, setExplanationError] = useState<string | null>(null);
    const [showAddAnnotationForm, setShowAddAnnotationForm] = useState(false);
    const [newAnnotProp, setNewAnnotProp] = useState('http://www.w3.org/2000/01/rdf-schema#comment');
    const [newAnnotValue, setNewAnnotValue] = useState('');
    const [localAnnotations, setLocalAnnotations] = useState<{ property: string; value: string }[]>([]);
    const [annotationsLoading, setAnnotationsLoading] = useState(false);

    const displayItem = item.includes('|||') ? item.split('|||')[0] : item;

    const fetchAxiomAnnotations = useCallback(async () => {
        if (!projectId || !parentEntityIri) return;
        setAnnotationsLoading(true);
        try {
            const data = await axiomAnnotationService.getAnnotations(
                projectId, parentEntityIri, displayItem, sectionName,
            );
            setLocalAnnotations(data);
        } catch (error) {
            console.warn('Failed to load axiom annotations', error);
            setLocalAnnotations([]);
        } finally {
            setAnnotationsLoading(false);
        }
    }, [projectId, parentEntityIri, displayItem, sectionName]);

    const fetchExplanation = useCallback(async (type: 'regular' | 'laconic', limit: number) => {
        if (!projectId || !parentEntityIri) return;
        setExplanationLoading(true);
        setExplanationError(null);
        try {
            const res = await apiClient.post<any>(`/api/ontology/${projectId}/explain-axiom`, {
                entityIri: parentEntityIri,
                relatedIri: displayItem,
                sectionName,
                justificationType: type,
                maxJustifications: limit,
            });
            const data = res?.data ?? res;
            setExplanationData(data?.justifications ?? []);
        } catch (e: any) {
            setExplanationError(e?.message ?? 'Failed to load explanation');
        } finally {
            setExplanationLoading(false);
        }
    }, [projectId, parentEntityIri, displayItem, sectionName]);

    useEffect(() => {
        if (showExplanation) {
            setExplanationData(null);
            fetchExplanation(justType, limitJustifications ? justLimit : 99);
        }
    }, [showExplanation]); // eslint-disable-line react-hooks/exhaustive-deps

    useEffect(() => {
        if (showAnnotations) {
            void fetchAxiomAnnotations();
        }
    }, [showAnnotations, fetchAxiomAnnotations]);

    const handleCopyIri = () => {
        navigator.clipboard.writeText(displayItem).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
        }).catch(() => {});
    };
    // Check if this is an inverse property expression
    const inverseMatch = displayItem.match(/^inverse\((.+)\)$/i);
    const isInverse = !!inverseMatch;
    const propertyIri = isInverse ? inverseMatch[1] : displayItem;

    // Check if this is a restriction expression (contains 'some', 'only', 'min', 'max', 'exactly', 'value')
    const restrictionKeywords = ['some', 'only', 'min', 'max', 'exactly', 'value', 'and', 'or', 'not'];
    const isRestrictionExpression = restrictionKeywords.some(kw =>
        displayItem.includes(` ${kw} `) || displayItem.startsWith(`${kw} `) || displayItem.endsWith(` ${kw}`)
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
                parts.push(<span key={`text-${keyIndex++}`}>{expr.slice(lastIndex, match.index)}</span>);
            }
            // Add the colored keyword (MAGENTA/PINK)
            parts.push(<span key={`kw-${keyIndex++}`} className="text-fuchsia-600 font-bold">{match[0]}</span>);
            lastIndex = regex.lastIndex;
        }
        // Add remaining text
        if (lastIndex < expr.length) {
            parts.push(<span key={`text-${keyIndex++}`}>{expr.slice(lastIndex)}</span>);
        }
        
        return parts.length > 0 ? parts : expr;
    };
    
    return (
        <div
            className={`group border-b last:border-0 transition-colors transition-opacity duration-300 ${isDeleting ? 'opacity-40' : ''}`}
            style={{
                borderColor: 'var(--border, #f3f4f6)',
                backgroundColor: isHovered
                    ? 'var(--surface-2)'
                    : isInferred ? 'rgba(254, 252, 232, 0.5)' : 'var(--surface-1)',
                color: 'var(--color-text)',
            }}
            onMouseEnter={() => setIsHovered(true)}
            onMouseLeave={() => setIsHovered(false)}
        >
            <div className="flex justify-between items-center p-1.5">
                <div className="flex items-center">
                    {/* Entity type icon */}
                    {getIcon()}
                    {isInverse ? (
                        <span className="text-sm font-bold">
                            <span className="text-fuchsia-600 font-bold">inverse</span>
                            <span>(</span>
                            <span>'{displayName}'</span>
                            <span>)</span>
                        </span>
                    ) : isRestrictionExpression ? (
                        <span className="text-sm font-bold">{formatRestrictionExpression(displayItem)}</span>
                    ) : (
                        <span className="text-sm font-bold">'{displayName}'</span>
                    )}
                    {isInferred && (
                        <span className="ml-2 text-[10px] bg-yellow-200 text-yellow-800 px-1.5 py-0.5 rounded">Inferred</span>
                    )}
                </div>
                {!isInferred && (
                    <div className={`flex items-center gap-1 transition-all ${isDeleting ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}>
                        {/* ? — Explanation (Protégé-style justification) */}
                        <button
                            onClick={(e) => { e.stopPropagation(); setShowExplanation(v => !v); if (showAnnotations) setShowAnnotations(false); }}
                            className={`p-1 rounded transition-all ${showExplanation ? 'text-blue-600 bg-blue-50 opacity-100' : 'text-gray-400 hover:bg-blue-100 hover:text-blue-600'}`}
                            title={`Explanation for '${displayName}'`}
                            aria-label="Explanation"
                        >
                            <HelpCircle size={14} />
                        </button>
                        {/* @ — Axiom annotations */}
                        <button
                            onClick={(e) => { e.stopPropagation(); setShowAnnotations(v => !v); if (showExplanation) setShowExplanation(false); }}
                            className={`p-1 rounded transition-all ${showAnnotations ? 'text-amber-600 bg-amber-50 opacity-100' : 'text-gray-400 hover:bg-amber-100 hover:text-amber-600'}`}
                            title={`Annotations for ${sectionName || 'axiom'}`}
                            aria-label="Axiom annotations"
                        >
                            <AtSign size={14} />
                        </button>
                        {/* ✎ — Edit item */}
                        {onEdit && (
                            <button
                                onClick={(e) => { e.stopPropagation(); onEdit(item); }}
                                className="p-1 rounded text-gray-400 hover:bg-blue-100 hover:text-blue-600 transition-all"
                                title={`Edit '${displayName}'`}
                                aria-label={`Edit ${displayName}`}
                            >
                                <Edit2 size={14} />
                            </button>
                        )}
                        {/* ○ — Navigate to entity in hierarchy */}
                        {onNavigate && (
                            <button
                                onClick={(e) => { e.stopPropagation(); onNavigate(propertyIri, detectedType || 'class'); }}
                                className="p-1 rounded text-gray-400 hover:bg-gray-100 hover:text-gray-700 transition-all"
                                title={`Navigate to '${displayName}'`}
                                aria-label={`Navigate to ${displayName}`}
                            >
                                <Circle size={14} />
                            </button>
                        )}
                        <button
                            onClick={async () => {
                                setIsDeleting(true);
                                try { await onDelete(item); } finally { setIsDeleting(false); }
                            }}
                            disabled={isDeleting}
                            className="p-1 rounded hover:bg-red-100 text-gray-400 hover:text-red-600 disabled:opacity-50 disabled:cursor-not-allowed"
                            title={`Remove '${displayName}'`}
                            aria-label={`Remove ${displayName}`}
                        >
                            {isDeleting ? <Loader size={14} className="animate-spin" /> : <Trash2 size={14} />}
                        </button>
                    </div>
                )}
            </div>

            {/* Explanation panel — Protégé-style justification dialog */}
            {showExplanation && !isInferred && (
                <div className="mx-1.5 mb-1.5 bg-blue-50 border border-blue-200 rounded text-xs" onClick={(e) => e.stopPropagation()}>
                    {/* Title bar */}
                    <div className="flex items-center gap-1.5 px-2 py-1.5 border-b border-blue-200 bg-blue-100">
                        <HelpCircle size={11} className="text-blue-600 shrink-0" />
                        <span className="text-[10px] font-semibold text-blue-800">
                            Explanation for '{displayName}'{sectionName ? ` ${sectionName}` : ''}
                        </span>
                    </div>
                    {/* Filter row — matches Protégé's radio layout */}
                    <div className="px-2 pt-2 pb-1 grid grid-cols-2 gap-x-4 gap-y-0.5">
                        <label className="flex items-center gap-1 cursor-pointer">
                            <input type="radio" name={`just-${item}`} checked={justType === 'regular'}
                                onChange={() => { setJustType('regular'); fetchExplanation('regular', limitJustifications ? justLimit : 99); }}
                                className="w-2.5 h-2.5 accent-blue-600" />
                            <span className="text-[10px] text-gray-700">Show regular justifications</span>
                        </label>
                        <label className="flex items-center gap-1 cursor-pointer">
                            <input type="radio" name={`just-limit-${item}`} checked={!limitJustifications}
                                onChange={() => { setLimitJustifications(false); fetchExplanation(justType, 99); }}
                                className="w-2.5 h-2.5 accent-blue-600" />
                            <span className="text-[10px] text-gray-700">All justifications</span>
                        </label>
                        <label className="flex items-center gap-1 cursor-pointer">
                            <input type="radio" name={`just-${item}`} checked={justType === 'laconic'}
                                onChange={() => { setJustType('laconic'); fetchExplanation('laconic', limitJustifications ? justLimit : 99); }}
                                className="w-2.5 h-2.5 accent-blue-600" />
                            <span className="text-[10px] text-gray-700">Show laconic justifications</span>
                        </label>
                        <label className="flex items-center gap-1 cursor-pointer">
                            <input type="radio" name={`just-limit-${item}`} checked={limitJustifications}
                                onChange={() => { setLimitJustifications(true); fetchExplanation(justType, justLimit); }}
                                className="w-2.5 h-2.5 accent-blue-600" />
                            <span className="text-[10px] text-gray-700">Limit justifications to</span>
                            <input
                                type="number" min={1} max={99} value={justLimit}
                                onChange={(e) => { const v = Math.max(1, parseInt(e.target.value) || 1); setJustLimit(v); setLimitJustifications(true); fetchExplanation(justType, v); }}
                                onClick={() => setLimitJustifications(true)}
                                className="w-8 px-1 py-0 text-[10px] border border-gray-300 rounded bg-white text-gray-700"
                            />
                        </label>
                    </div>
                    {/* Justification blocks */}
                    <div className="px-2 pb-2">
                        {explanationLoading && (
                            <div className="flex items-center gap-1.5 py-2 text-[10px] text-blue-500">
                                <Loader size={11} className="animate-spin" /> Loading justifications…
                            </div>
                        )}
                        {explanationError && (
                            <div className="text-[10px] text-red-500 py-1">{explanationError}</div>
                        )}
                        {!explanationLoading && !explanationError && explanationData !== null && (
                            explanationData.length === 0 ? (
                                <div className="text-[10px] text-gray-400 italic py-1">No justifications found (axiom may be inferred by the reasoner).</div>
                            ) : (
                                explanationData.map((just) => (
                                    <div key={just.index} className="mb-2">
                                        <div className="flex items-center justify-between mb-0.5">
                                            <span className="text-[10px] font-semibold text-gray-700">Explanation {just.index}</span>
                                            <label className="flex items-center gap-1 cursor-pointer">
                                                <input type="checkbox" checked={showLaconicExpl} onChange={(e) => setShowLaconicExpl(e.target.checked)} className="w-2.5 h-2.5 accent-blue-600" />
                                                <span className="text-[10px] text-gray-500">Display laconic explanation</span>
                                            </label>
                                        </div>
                                        <div className="bg-white border border-blue-100 rounded overflow-hidden">
                                            <div className="px-2 py-1 text-[10px] text-gray-500 border-b border-blue-50">
                                                Explanation for: '{displayName}'{sectionName ? ` ${sectionName}` : ''}
                                            </div>
                                            {just.axioms.map((ax, ai) => (
                                                <div key={ai} className="flex items-center justify-between px-2 py-1.5 bg-green-50">
                                                    <span className="text-xs text-gray-900 font-semibold">{ax.manchester}</span>
                                                    <HelpCircle size={12} className="shrink-0 text-gray-400 ml-2" />
                                                </div>
                                            ))}
                                        </div>
                                    </div>
                                ))
                            )
                        )}
                        {!explanationLoading && explanationData === null && !projectId && (
                            <div className="text-[10px] text-gray-400 italic py-1">No project context available.</div>
                        )}
                        {/* IRI row */}
                        <div className="text-[10px] font-semibold text-blue-700 uppercase tracking-wide mt-2 mb-1">IRI</div>
                        <div className="flex items-start gap-2">
                            <span className="font-mono text-gray-700 break-all flex-1 leading-relaxed select-all text-[10px]">{item}</span>
                            <button onClick={handleCopyIri}
                                className={`shrink-0 px-1.5 py-0.5 rounded text-[10px] font-medium border transition-colors ${
                                    copied ? 'bg-green-100 border-green-300 text-green-700' : 'bg-white border-blue-300 text-blue-600 hover:bg-blue-100'
                                }`}
                            >{copied ? '✓ Copied' : 'Copy IRI'}</button>
                        </div>
                    </div>
                </div>
            )}

            {/* Annotations panel — like Protégé's "Annotations for DataPropertyRange" dialog */}
            {showAnnotations && !isInferred && (
                <div className="mx-1.5 mb-1.5 p-2 bg-amber-50 border border-amber-200 rounded text-xs">
                    <div className="text-[10px] font-semibold text-amber-700 uppercase tracking-wide mb-1.5">
                        Annotations for {sectionName || 'Axiom'}
                    </div>
                    {/* The axiom expression this annotation applies to */}
                    <div className="bg-white border border-amber-100 rounded px-2 py-1.5 mb-2 text-gray-700 text-xs">
                        <span className="font-semibold">'{displayName}'</span>
                        {sectionName && <span className="text-blue-600 mx-1">{sectionName}</span>}
                        <span className="font-mono">{displayName}</span>
                    </div>
                    <div className="flex items-center justify-between mb-1">
                        <span className="text-[10px] font-semibold text-amber-700 uppercase tracking-wide">Annotations</span>
                        {!showAddAnnotationForm && (
                            <button
                                className="flex items-center gap-0.5 text-amber-600 hover:text-amber-800 text-[10px]"
                                title="Add axiom annotation"
                                onClick={(e) => { e.stopPropagation(); setShowAddAnnotationForm(true); setNewAnnotValue(''); }}
                            >
                                <Plus size={11} /> Add
                            </button>
                        )}
                    </div>
                    {/* Inline add form */}
                    {showAddAnnotationForm && (
                        <div className="mb-2 p-2 bg-white border border-amber-300 rounded" onClick={(e) => e.stopPropagation()}>
                            <select
                                value={newAnnotProp}
                                onChange={(e) => setNewAnnotProp(e.target.value)}
                                className="w-full mb-1 px-2 py-1 text-[10px] border border-amber-200 rounded bg-white text-gray-700"
                            >
                                <option value="http://www.w3.org/2000/01/rdf-schema#comment">rdfs:comment</option>
                                <option value="http://www.w3.org/2000/01/rdf-schema#label">rdfs:label</option>
                                <option value="http://www.w3.org/2000/01/rdf-schema#seeAlso">rdfs:seeAlso</option>
                                <option value="http://www.w3.org/2000/01/rdf-schema#isDefinedBy">rdfs:isDefinedBy</option>
                                <option value="http://www.w3.org/2002/07/owl#deprecated">owl:deprecated</option>
                            </select>
                            <textarea
                                autoFocus
                                value={newAnnotValue}
                                onChange={(e) => setNewAnnotValue(e.target.value)}
                                placeholder="Annotation value..."
                                rows={2}
                                className="w-full mb-1 px-2 py-1 text-[10px] border border-amber-200 rounded bg-white text-gray-700 resize-none"
                            />
                            <div className="flex justify-end gap-1">
                                <button
                                    className="px-2 py-0.5 text-[10px] bg-gray-100 text-gray-600 rounded hover:bg-gray-200"
                                    onClick={(e) => { e.stopPropagation(); setShowAddAnnotationForm(false); setNewAnnotValue(''); }}
                                >Cancel</button>
                                <button
                                    disabled={!newAnnotValue.trim() || !projectId || !parentEntityIri}
                                    className="px-2 py-0.5 text-[10px] bg-amber-500 text-white rounded hover:bg-amber-600 disabled:opacity-40"
                                    onClick={async (e) => {
                                        e.stopPropagation();
                                        if (!newAnnotValue.trim() || !projectId || !parentEntityIri) return;
                                        await axiomAnnotationService.addAnnotation(projectId, {
                                            entityIri: parentEntityIri,
                                            relatedIri: displayItem,
                                            sectionName,
                                            annotationProperty: newAnnotProp,
                                            value: newAnnotValue.trim(),
                                        });
                                        setShowAddAnnotationForm(false);
                                        setNewAnnotValue('');
                                        await fetchAxiomAnnotations();
                                    }}
                                >Save</button>
                            </div>
                        </div>
                    )}
                    {/* Stored axiom annotations */}
                    {localAnnotations.length > 0 ? (
                        <div className="space-y-1 mb-1">
                            {localAnnotations.map((ann, idx) => (
                                <div key={idx} className="flex items-start justify-between bg-white border border-amber-100 rounded px-2 py-1">
                                    <div>
                                        <div className="text-[10px] font-medium text-amber-700">{ann.property.split('#').pop()}</div>
                                        <div className="text-[10px] text-gray-700">{ann.value}</div>
                                    </div>
                                    <button
                                        onClick={async (e) => {
                                            e.stopPropagation();
                                            if (!projectId || !parentEntityIri) return;
                                            await axiomAnnotationService.deleteAnnotation(projectId, {
                                                entityIri: parentEntityIri,
                                                relatedIri: displayItem,
                                                sectionName,
                                                annotationProperty: ann.property,
                                                value: ann.value,
                                            });
                                            await fetchAxiomAnnotations();
                                        }}
                                        className="ml-1 p-0.5 text-gray-400 hover:text-red-500"
                                        title="Remove annotation"
                                    >
                                        <Trash2 size={10} />
                                    </button>
                                </div>
                            ))}
                        </div>
                    ) : (
                        !showAddAnnotationForm && <div className="text-[10px] text-gray-400 italic">No annotations on this axiom.</div>
                    )}
                </div>
            )}
        </div>
    );
};

export const MultiSelectSection: React.FC<{
    title: string;
    items: string[] | undefined;
    inferredItems?: string[] | undefined;
    onAddClick?: (editingItem?: string) => void;
    onDelete: (item: string) => Promise<void> | void;
    themeColor?: 'blue' | 'green' | 'orange' | 'yellow' | 'purple'; // For header styling
    itemEntityType?: 'class' | 'objectProperty' | 'dataProperty' | 'datatype' | 'annotationProperty' | 'individual'; // For item icons
    isViewOnly?: boolean;
    onViewOnlyAction?: () => void;
    onNavigate?: (iri: string, type: string) => void;
    onEdit?: (item: string) => void;
    projectId?: string;
    parentEntityIri?: string;
}> = ({ title, items, inferredItems, onAddClick, onDelete, themeColor = 'blue', itemEntityType, isViewOnly = false, onViewOnlyAction, onNavigate, onEdit, projectId, parentEntityIri }) => {
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
    // Pass the editingItem to onAddClick — the picker's confirm handler will do
    // delete + add in a single API call instead of delete-now / add-later.
    const itemEditHandler = onEdit || (onAddClick ? (editItem: string) => { onAddClick(editItem); } : undefined);

    const handleHeaderClick = () => {
        setIsSelected(true);
        if (isViewOnly) { onViewOnlyAction?.(); return; }
        if (onAddClick) {
            onAddClick(undefined);
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
             <div className="border border-t-0 rounded-b-sm overflow-hidden" style={{ backgroundColor: 'var(--surface-1)', borderColor: 'var(--border, #e5e7eb)' }}>
                 {items && items.length > 0 ? (
                    items.map(item => <MultiSelectItem key={item} item={item} onDelete={(i: string) => { if (isViewOnly) { onViewOnlyAction?.(); return; } return onDelete(i); }} onEdit={isViewOnly ? undefined : itemEditHandler} themeColor={themeColor} entityType={itemEntityType} sectionName={title} onNavigate={onNavigate} projectId={projectId} parentEntityIri={parentEntityIri} />)
                 ) : null}
                 {inferredItems && inferredItems.length > 0 ? (
                    inferredItems.map(item => <MultiSelectItem key={item} item={item} onDelete={() => {}} themeColor={themeColor} entityType={itemEntityType} isInferred={true} sectionName={title} onNavigate={onNavigate} projectId={projectId} parentEntityIri={parentEntityIri} />)
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
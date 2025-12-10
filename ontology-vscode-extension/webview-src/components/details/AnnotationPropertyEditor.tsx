import React, { useState } from 'react';
import { Plus, Tag, Edit3, Search } from 'lucide-react';
import { AnnotationsDisplay, MultiSelectSection } from './common';
import ontologyMutationService from '../../services/ontologyMutationService';
import type { AnnotationProperty } from '../../types';

/**
 * AnnotationPropertyEditor - Protégé-style editor for OWL Annotation Properties
 * 
 * Based on Protégé's OWLAnnotationPropertyDescriptionFrame.java:
 * - 3 sections in Description tab:
 *   1. OWLAnnotationPropertyDomainFrameSection - "Domains (intersection)"
 *   2. OWLAnnotationPropertyRangeFrameSection - "Range (intersection)"
 *   3. OWLSubAnnotationPropertyFrameSection - "Superproperties"
 * 
 * Annotation properties in OWL:
 * - CAN have: Annotations, Domains, Ranges, Superproperties
 * - CANNOT have: Equivalent properties, Characteristics, Inverse properties, Property chains
 * - Do NOT participate in reasoning (unlike Object/Data properties)
 * 
 * Tabs match Protégé:
 * - Annotations (OWLAnnotationPropertyAnnotationsViewComponent)
 * - Description (OWLAnnotationPropertyDescriptionViewComponent)
 * - Usage (OWLAnnotationPropertyUsageViewComponent)
 */

interface AnnotationPropertyEditorProps {
  item: AnnotationProperty;
  onUpdate: (updatedItem: AnnotationProperty) => void;
  onAddAnnotation: () => void;
  onEditAnnotation: (propertyIri: string, currentValue: string) => void;
  onDeleteAnnotation: (key: string) => void;
  activeTheme?: string;
  projectId: string;
  onAddSubPropertyClick?: () => void;
  onAddDomainClick?: () => void;
  onAddRangeClick?: () => void;
}

const AnnotationPropertyEditor: React.FC<AnnotationPropertyEditorProps> = ({
  item,
  onUpdate,
  onAddAnnotation,
  onEditAnnotation,
  onDeleteAnnotation,
  activeTheme,
  projectId,
  onAddSubPropertyClick,
  onAddDomainClick,
  onAddRangeClick
}) => {
  const [activeTab, setActiveTab] = useState<'annotations' | 'description' | 'usage'>('annotations');
  
  // Extended annotation property type with optional fields
  const extendedItem = item as AnnotationProperty & {
    superProperties?: string[];
    domains?: string[];
    ranges?: string[];
    usages?: { entityId: string; entityLabel: string; axiomType: string }[];
  };

  const annotationCount = Object.keys(item.annotations || {}).length;
  const domainCount = extendedItem.domains?.length || 0;
  const rangeCount = extendedItem.ranges?.length || 0;
  const superPropertyCount = extendedItem.superProperties?.length || 0;
  const descriptionCount = domainCount + rangeCount + superPropertyCount;

  const handleDeleteRelation = async (relation: 'subProperty' | 'domain' | 'range', target: string) => {
    try {
      switch (relation) {
        case 'subProperty':
          await ontologyMutationService.deleteSubPropertyOf(projectId, item.id, target);
          onUpdate({ 
            ...item, 
            superProperties: extendedItem.superProperties?.filter(p => p !== target) 
          } as AnnotationProperty);
          break;
        case 'domain':
          await ontologyMutationService.deletePropertyDomain(projectId, item.id, target);
          onUpdate({ 
            ...item, 
            domains: extendedItem.domains?.filter(d => d !== target) 
          } as AnnotationProperty);
          break;
        case 'range':
          await ontologyMutationService.deletePropertyRange(projectId, item.id, target);
          onUpdate({ 
            ...item, 
            ranges: extendedItem.ranges?.filter(r => r !== target) 
          } as AnnotationProperty);
          break;
      }
    } catch (error) {
      console.error(`Failed to delete ${relation}`, error);
    }
  };

  return (
    <div className="flex flex-col h-full bg-white">
      {/* Header with IRI */}
      <div className="bg-gray-100 border-b border-gray-200 p-3 flex items-center justify-between">
        <div className="flex items-center gap-2 overflow-hidden">
          <div className="p-1.5 rounded bg-orange-500 text-white">
            <Tag size={14} />
          </div>
          <div className="flex flex-col min-w-0">
            <span className="font-bold text-sm truncate">{item.label}</span>
            <span className="text-xs text-gray-500 truncate font-mono">{item.id}</span>
          </div>
        </div>
        <button
          className="p-1.5 hover:bg-gray-200 rounded text-gray-600 hover:text-purple-600 flex-shrink-0"
          title="Edit IRI and Label"
        >
          <Edit3 size={16} />
        </button>
      </div>

      {/* Tabs - Protégé style with Annotations, Description, Usage */}
      <div className="flex border-b border-gray-200 bg-gray-50">
        <button 
          onClick={() => setActiveTab('annotations')}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
            activeTab === 'annotations' 
              ? 'border-orange-600 text-orange-700 bg-white' 
              : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
          }`}
        >
          Annotations {annotationCount > 0 && `(${annotationCount})`}
        </button>
        <button 
          onClick={() => setActiveTab('description')}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
            activeTab === 'description' 
              ? 'border-orange-600 text-orange-700 bg-white' 
              : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
          }`}
        >
          Description {descriptionCount > 0 && `(${descriptionCount})`}
        </button>
        <button 
          onClick={() => setActiveTab('usage')}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
            activeTab === 'usage' 
              ? 'border-orange-600 text-orange-700 bg-white' 
              : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
          }`}
        >
          Usage
        </button>
      </div>

      {/* Main Content */}
      <div className="flex-1 overflow-y-auto bg-gray-50 p-3 min-h-0">
        {activeTab === 'annotations' && (
          <div className="space-y-0">
            {/* Annotations Panel Header - Clean minimal style */}
            <div className="bg-stone-100 border-b border-stone-300 px-3 py-1.5 flex items-center justify-between">
              <span className="text-xs font-medium text-stone-700">Annotations: {item.label}</span>
              <button onClick={onAddAnnotation} className="p-1 hover:bg-stone-200 rounded text-stone-500 hover:text-stone-700" title="Add annotation">
                <Plus size={14} />
              </button>
            </div>
            {/* Annotations Content */}
            <div className="bg-white border border-t-0 border-gray-200 rounded-b-sm">
              <AnnotationsDisplay 
                annotations={item.annotations} 
                onDelete={onDeleteAnnotation} 
                onEdit={onEditAnnotation} 
              />
            </div>
          </div>
        )}

        {activeTab === 'description' && (
          <div className="space-y-0">
            {/* Description Panel Header - Clean minimal style */}
            <div className="bg-stone-100 border-b border-stone-300 px-3 py-1.5">
              <span className="text-xs font-medium text-stone-700">Description: {item.label}</span>
            </div>
            {/* Description Content */}
            <div className="bg-white border border-t-0 border-gray-200 rounded-b-sm p-3 space-y-3">
              {/* Domains - Annotation properties can optionally have domains */}
              <MultiSelectSection
                title="Domains (intersection)"
                items={extendedItem.domains}
                onAddClick={onAddDomainClick}
                onDelete={domain => handleDeleteRelation('domain', domain)}
                themeColor="orange"
              />

              {/* Ranges - Can be datatype (literal) or IRI */}
              <MultiSelectSection
                title="Range (intersection)"
                items={extendedItem.ranges}
                onAddClick={onAddRangeClick}
                onDelete={range => handleDeleteRelation('range', range)}
                themeColor="orange"
              />

              {/* Superproperties */}
              <MultiSelectSection
                title="Superproperties"
                items={extendedItem.superProperties}
                onAddClick={onAddSubPropertyClick}
                onDelete={prop => handleDeleteRelation('subProperty', prop)}
                themeColor="orange"
              />
            </div>
          </div>
        )}

        {activeTab === 'usage' && (
          <div className="space-y-0">
            {/* Usage Panel Header */}
            <div className="bg-stone-100 border-b border-stone-300 px-3 py-1.5 flex items-center justify-between">
              <span className="text-xs font-medium text-stone-700">Usage: {item.label}</span>
              <div className="flex items-center gap-1">
                <Search size={14} className="text-stone-400" />
              </div>
            </div>
            {/* Usage Content */}
            <div className="bg-white border border-t-0 border-gray-200 rounded-b-sm p-3">
              {extendedItem.usages && extendedItem.usages.length > 0 ? (
                <div className="space-y-1">
                  {extendedItem.usages.map((usage, idx) => (
                    <div key={idx} className="flex items-center gap-2 p-1.5 hover:bg-gray-50 rounded text-sm">
                      <span className="w-3 h-3 rounded-full bg-amber-400 flex-shrink-0" />
                      <span className="text-gray-800">{usage.entityLabel}</span>
                      <span className="text-gray-400 text-xs">({usage.axiomType})</span>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="text-xs text-gray-400 italic py-2 text-center">
                  No usages found for this annotation property
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default AnnotationPropertyEditor;

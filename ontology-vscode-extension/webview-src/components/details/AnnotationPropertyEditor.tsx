import React, { useState, useEffect } from 'react';
import { Plus, Tag, Edit3, Search } from 'lucide-react';
import { AnnotationsDisplay, MultiSelectSection, CollaboratorPresenceBar } from './common';
import { IRIEditorDialog } from '../dialogs';
import ontologyMutationService from '../../services/ontologyMutationService';
import { notificationService } from '../../services/notificationService';
import apiClient from '../../services/apiClient';
import type { AnnotationProperty } from '../../types';

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
  isViewOnly?: boolean;
  onViewOnlyAction?: () => void;
  onNavigate?: (iri: string, type: string) => void;
}

interface UsageItem {
  type: string;
  subject: string;
  subjectLabel?: string;
  predicate?: string;
  object?: string;
  context?: string;
}

const PropertyUsageTab: React.FC<{ 
  propertyIri: string; 
  projectId: string; 
  label: string;
  propertyType: string;
}> = ({ propertyIri, projectId, label, propertyType }) => {
  const [loading, setLoading] = useState(true);
  const [usages, setUsages] = useState<UsageItem[]>([]);
  const [filter, setFilter] = useState('');
  const [showTypes, setShowTypes] = useState({
    domain: true,
    range: true,
    subproperty: true,
    superproperty: true,
    assertion: true,
    restriction: true,
    annotation: true
  });

  useEffect(() => {
    loadUsages();
  }, [propertyIri, projectId]);

  const loadUsages = async () => {
    setLoading(true);
    try {
      let endpoint = `/api/ontology/properties/usage/${projectId}?propertyIri=${encodeURIComponent(propertyIri)}`;
      if (propertyType === 'AnnotationProperty') {
        endpoint = `/api/ontology/annotation-properties/${projectId}/usage?propertyIri=${encodeURIComponent(propertyIri)}`;
      }
      
      const response = await apiClient.get<any>(endpoint);
      const usageData = response?.data?.data || response?.data || response || [];
      setUsages(Array.isArray(usageData) ? usageData : []);
    } catch (error) {
      console.error('Failed to load usage data:', error);
      setUsages([]);
    } finally {
      setLoading(false);
    }
  };

  const filteredUsages = usages.filter(u => 
    (u.subjectLabel || u.subject || '').toLowerCase().includes(filter.toLowerCase()) &&
    showTypes[u.type as keyof typeof showTypes] !== false
  );

  const usagesByType = {
    domain: filteredUsages.filter(u => u.type === 'domain'),
    range: filteredUsages.filter(u => u.type === 'range'),
    subproperty: filteredUsages.filter(u => u.type === 'subproperty'),
    superproperty: filteredUsages.filter(u => u.type === 'superproperty'),
    assertion: filteredUsages.filter(u => u.type === 'assertion'),
    restriction: filteredUsages.filter(u => u.type === 'restriction'),
    annotation: filteredUsages.filter(u => u.type === 'annotation')
  };

  if (loading) {
    return <div className="p-4 text-sm text-gray-500">Loading usage information...</div>;
  }

  return (
    <div className="flex flex-col h-full">
      <div className="p-2 border-b border-gray-200 space-y-2">
        <div className="text-xs text-gray-600">
          Found <span className="font-bold text-purple-600">{usages.length}</span> uses of <span className="font-semibold">{label}</span>
        </div>
        
        <div className="flex items-center gap-2">
          <div className="flex-1 relative">
            <Search size={14} className="absolute left-2 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              type="text"
              placeholder="Filter usages..."
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              className="w-full pl-7 pr-2 py-1 text-xs rounded focus:outline-none theme-input"
            />
          </div>
        </div>

        <div className="flex flex-wrap gap-2 text-xs">
          {propertyType !== 'AnnotationProperty' && (
            <>
              <label className="flex items-center gap-1 cursor-pointer">
                <input type="checkbox" checked={showTypes.domain} onChange={(e) => setShowTypes({...showTypes, domain: e.target.checked})} className="w-3 h-3" />
                <span>domains ({usagesByType.domain.length})</span>
              </label>
              <label className="flex items-center gap-1 cursor-pointer">
                <input type="checkbox" checked={showTypes.range} onChange={(e) => setShowTypes({...showTypes, range: e.target.checked})} className="w-3 h-3" />
                <span>ranges ({usagesByType.range.length})</span>
              </label>
              <label className="flex items-center gap-1 cursor-pointer">
                <input type="checkbox" checked={showTypes.subproperty} onChange={(e) => setShowTypes({...showTypes, subproperty: e.target.checked})} className="w-3 h-3" />
                <span>subproperties ({usagesByType.subproperty.length})</span>
              </label>
              <label className="flex items-center gap-1 cursor-pointer">
                <input type="checkbox" checked={showTypes.superproperty} onChange={(e) => setShowTypes({...showTypes, superproperty: e.target.checked})} className="w-3 h-3" />
                <span>superproperties ({usagesByType.superproperty.length})</span>
              </label>
              <label className="flex items-center gap-1 cursor-pointer">
                <input type="checkbox" checked={showTypes.assertion} onChange={(e) => setShowTypes({...showTypes, assertion: e.target.checked})} className="w-3 h-3" />
                <span>assertions ({usagesByType.assertion.length})</span>
              </label>
              <label className="flex items-center gap-1 cursor-pointer">
                <input type="checkbox" checked={showTypes.restriction} onChange={(e) => setShowTypes({...showTypes, restriction: e.target.checked})} className="w-3 h-3" />
                <span>restrictions ({usagesByType.restriction.length})</span>
              </label>
            </>
          )}
          {propertyType === 'AnnotationProperty' && (
            <label className="flex items-center gap-1 cursor-pointer">
              <input type="checkbox" checked={showTypes.annotation} onChange={(e) => setShowTypes({...showTypes, annotation: e.target.checked})} className="w-3 h-3" />
              <span>annotations ({usagesByType.annotation.length})</span>
            </label>
          )}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-2">
        {filteredUsages.length === 0 ? (
          <div className="text-xs text-gray-400 italic text-center py-4">No usages found</div>
        ) : (
          <div className="space-y-1">
            {filteredUsages.map((u, idx) => (
              <div key={idx} className="p-2 bg-gray-50 rounded border border-gray-200 hover:bg-gray-100">
                <div className="flex items-start gap-2">
                  <span className="text-[10px] font-semibold text-orange-600 uppercase min-w-[80px] mt-0.5">{u.type || 'annotation'}</span>
                  <div className="flex-1 text-xs">
                    <div className="font-mono text-purple-700 break-all">{u.subjectLabel || u.subject}</div>
                    {(u.context || u.value) && <div className="text-gray-500 mt-1 italic">{u.context || u.value}</div>}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

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
  onAddRangeClick,
  isViewOnly = false,
  onViewOnlyAction,
  onNavigate,
}) => {
  const [activeTab, setActiveTab] = useState<'annotations' | 'description' | 'usage'>('annotations');
  const [isIRIEditorOpen, setIsIRIEditorOpen] = useState(false);
  
  // Extended annotation property type with optional fields
  const extendedItem = item as AnnotationProperty & {
    superProperties?: string[];
    domains?: string[];
    ranges?: string[];
  };
  
  const annotationCount = Object.keys(item.annotations || {}).length;
  const domainCount = extendedItem.domains?.length || 0;
  const rangeCount = extendedItem.ranges?.length || 0;
  const superPropertyCount = extendedItem.superProperties?.length || 0;
  const descriptionCount = domainCount + rangeCount + superPropertyCount;

  const handleSaveIRI = async (newIRI: string, newLabel: string) => {
    try {
      if (newLabel !== item.label) {
        await ontologyMutationService.updateClassLabel(projectId, item.id, newLabel);
        onUpdate({ ...item, label: newLabel } as AnnotationProperty);
      }
      if (newIRI !== item.id) {
        console.warn("IRI renaming requires backend support - not yet implemented");
        notificationService.warning("Not Supported", "IRI renaming is not yet supported. Only label changes are saved.");
      }
    } catch (error) {
      console.error("Failed to update annotation property:", error);
      notificationService.error("Update Failed", "Failed to update annotation property. See console for details.");
    }
  };

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
          onClick={isViewOnly ? () => onViewOnlyAction?.() : () => setIsIRIEditorOpen(true)}
          className="p-1.5 hover:bg-gray-200 rounded text-gray-600 hover:text-purple-600 flex-shrink-0"
          title={isViewOnly ? "View-only: upgrade to edit" : "Edit IRI and Label"}
        >
          <Edit3 size={16} />
        </button>
      </div>
      <CollaboratorPresenceBar entityId={item.id} />

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
              <button onClick={isViewOnly ? () => onViewOnlyAction?.() : onAddAnnotation} className="p-1 hover:bg-stone-200 rounded text-stone-500 hover:text-stone-700" title={isViewOnly ? "View-only: upgrade to edit" : "Add annotation"}>
                <Plus size={14} />
              </button>
            </div>
            {/* Annotations Content */}
            <div className="bg-white border border-t-0 border-gray-200 rounded-b-sm">
              <AnnotationsDisplay
                annotations={item.annotations}
                onDelete={onDeleteAnnotation}
                onEdit={onEditAnnotation}
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
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
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
                onNavigate={onNavigate}
              />

              {/* Ranges - Can be datatype (literal) or IRI */}
              <MultiSelectSection
                title="Range (intersection)"
                items={extendedItem.ranges}
                onAddClick={onAddRangeClick}
                onDelete={range => handleDeleteRelation('range', range)}
                themeColor="orange"
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
                onNavigate={onNavigate}
              />

              {/* Superproperties */}
              <MultiSelectSection
                title="Superproperties"
                items={extendedItem.superProperties}
                onAddClick={onAddSubPropertyClick}
                onDelete={prop => handleDeleteRelation('subProperty', prop)}
                themeColor="orange"
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
                onNavigate={onNavigate}
              />
            </div>
          </div>
        )}

        {activeTab === 'usage' && (
          <PropertyUsageTab 
            propertyIri={item.id} 
            projectId={projectId} 
            label={item.label || item.id.split(/[#/]/).pop() || ''}
            propertyType="AnnotationProperty"
          />
        )}
      </div>

      <IRIEditorDialog
        isOpen={isIRIEditorOpen}
        onClose={() => setIsIRIEditorOpen(false)}
        currentIRI={item.id}
        currentLabel={item.label}
        entityType="AnnotationProperty"
        onSave={handleSaveIRI}
      />
    </div>
  );
};

export default AnnotationPropertyEditor;

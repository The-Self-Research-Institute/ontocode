import React, { useState, useEffect } from 'react';
import { Plus, Trash2, CheckSquare, Square, Edit3, Search } from 'lucide-react';
import { Panel, AnnotationsDisplay, MultiSelectSection } from './common';
import { ManchesterSyntaxEditor, PropertyChainDialog } from '../dialogs';
import apiClient from '../../services/apiClient';
import ontologyMutationService from '../../services/ontologyMutationService';
import type { Property } from '../../types';

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
                  <span className="text-[10px] font-semibold text-orange-600 uppercase min-w-[80px] mt-0.5">{u.type}</span>
                  <div className="flex-1 text-xs">
                    <div className="font-mono text-purple-700 break-all">{u.subjectLabel || u.subject}</div>
                    {u.context && <div className="text-gray-500 mt-1 italic">{u.context}</div>}
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


const PropertyEditor: React.FC<{
  item: Property;
  onUpdate: (updatedItem: Property) => void;
  onAddAnnotation: () => void;
  onEditAnnotation: (propertyIri: string, currentValue: string) => void;
  onDeleteAnnotation: (key: string) => void;
  activeTheme?: string;
  projectId: string;
  onAddDomainClick?: () => void;
  onAddRangeClick?: () => void;
  onAddSubPropertyClick?: () => void;
  onAddInverseClick?: () => void;
  onAddDisjointClick?: () => void;
  onAddEquivalentClick?: () => void;
  objectProperties?: Property[];
}> = ({ 
    item, 
    onUpdate, 
    onAddAnnotation,
    onEditAnnotation, 
    onDeleteAnnotation, 
    activeTheme, 
    projectId,
    onAddDomainClick,
    onAddRangeClick,
    onAddSubPropertyClick,
    onAddInverseClick,
    onAddDisjointClick,
    onAddEquivalentClick,
    objectProperties = []
}) => {
    const [activeTab, setActiveTab] = useState<'annotations' | 'usage' | 'description'>('annotations');
    const [isEditorOpen, setIsEditorOpen] = useState(false);
    const [editorTitle, setEditorTitle] = useState("");
    const [editorAction, setEditorAction] = useState<((val: string) => void) | null>(null);
    const [isChainDialogOpen, setIsChainDialogOpen] = useState(false);

    const isObjectProperty = item.type === 'ObjectProperty';
    const isDataProperty = item.type === 'DatatypeProperty';
    const isAnnotationProperty = item.type === 'AnnotationProperty';
    
    // Theme colors based on property type
    const themeColor = isObjectProperty ? 'blue' : isDataProperty ? 'green' : 'orange';
    const headerGradient = isObjectProperty 
        ? 'bg-gradient-to-r from-blue-500 to-blue-600' 
        : isDataProperty 
        ? 'bg-gradient-to-r from-green-500 to-green-600' 
        : 'bg-gradient-to-r from-orange-500 to-amber-500';
    
    const characteristics = isObjectProperty 
        ? [
            { key: 'Functional', label: 'Functional' },
            { key: 'InverseFunctional', label: 'Inverse functional' },
            { key: 'Transitive', label: 'Transitive' },
            { key: 'Symmetric', label: 'Symmetric' },
            { key: 'Asymmetric', label: 'Asymmetric' },
            { key: 'Reflexive', label: 'Reflexive' },
            { key: 'Irreflexive', label: 'Irreflexive' }
          ] 
        : isDataProperty 
        ? [{ key: 'Functional', label: 'Functional' }]
        : []; // Annotation properties don't have characteristics
    
    const handleCharacteristicChange = async (char: string, checked: boolean) => {
        const currentChars = item.characteristics || [];
        const newChars = checked ? [...currentChars, char] : currentChars.filter(c => c !== char);
        
        // Optimistic update
        onUpdate({ ...item, characteristics: newChars });

        try {
            if (checked) {
                await ontologyMutationService.addCharacteristic(projectId, item.id, `http://www.w3.org/2002/07/owl#${char}Property`);
            } else {
                await ontologyMutationService.deleteCharacteristic(projectId, item.id, `http://www.w3.org/2002/07/owl#${char}Property`);
            }
        } catch (error) {
            console.error("Failed to update characteristic", error);
            // Revert on error
            onUpdate({ ...item, characteristics: currentChars });
        }
    };

    const handleAddRelation = async (relation: 'domain' | 'range' | 'subProperty' | 'inverse' | 'disjoint' | 'equivalent', target: string) => {
        try {
            switch (relation) {
                case 'domain':
                    await ontologyMutationService.addPropertyDomain(projectId, item.id, target);
                    onUpdate({ ...item, domains: [...(item.domains || []), target] });
                    break;
                case 'range':
                    await ontologyMutationService.addPropertyRange(projectId, item.id, target);
                    onUpdate({ ...item, ranges: [...(item.ranges || []), target] });
                    break;
                case 'subProperty':
                    await ontologyMutationService.addSubPropertyOf(projectId, item.id, target);
                    onUpdate({ ...item, superProperties: [...(item.superProperties || []), target] });
                    break;
                case 'inverse':
                    await ontologyMutationService.addInverseProperty(projectId, item.id, target);
                    onUpdate({ ...item, inverseProperties: [...(item.inverseProperties || []), target] });
                    break;
                case 'disjoint':
                    await ontologyMutationService.addDisjointProperty(projectId, item.id, target);
                    onUpdate({ ...item, disjointProperties: [...(item.disjointProperties || []), target] });
                    break;
                case 'equivalent': {
                    const currentEq = item.equivalentProperties || [];
                    const updatedEq = [...currentEq, target];
                    onUpdate({ ...item, equivalentProperties: updatedEq });
                    await ontologyMutationService.addEquivalentProperty(projectId, item.id, target);
                    break;
                }
            }
        } catch (error) {
            console.error(`Failed to add ${relation}`, error);
        }
    };

    const handleDeleteRelation = async (relation: 'domain' | 'range' | 'subProperty' | 'inverse' | 'disjoint' | 'equivalent', target: string) => {
        try {
            switch (relation) {
                case 'domain':
                    await ontologyMutationService.deletePropertyDomain(projectId, item.id, target);
                    onUpdate({ ...item, domains: item.domains?.filter(d => d !== target) });
                    break;
                case 'range':
                    await ontologyMutationService.deletePropertyRange(projectId, item.id, target);
                    onUpdate({ ...item, ranges: item.ranges?.filter(r => r !== target) });
                    break;
                case 'subProperty':
                    await ontologyMutationService.deleteSubPropertyOf(projectId, item.id, target);
                    onUpdate({ ...item, superProperties: item.superProperties?.filter(p => p !== target) });
                    break;
                case 'inverse':
                    await ontologyMutationService.deleteInverseProperty(projectId, item.id, target);
                    onUpdate({ ...item, inverseProperties: item.inverseProperties?.filter(p => p !== target) });
                    break;
                case 'disjoint':
                    await ontologyMutationService.deleteDisjointProperty(projectId, item.id, target);
                    onUpdate({ ...item, disjointProperties: item.disjointProperties?.filter(p => p !== target) });
                    break;
                case 'equivalent': {
                    const remaining = item.equivalentProperties?.filter(p => p !== target) || [];
                    onUpdate({ ...item, equivalentProperties: remaining });
                    await ontologyMutationService.deleteEquivalentProperty(projectId, item.id, target);
                    break;
                }
            }
        } catch (error) {
            console.error(`Failed to delete ${relation}`, error);
        }
    };

    const handleDeletePropertyChain = async (chain: string) => {
        const updatedChains = item.propertyChains?.filter(c => c !== chain) || [];
        onUpdate({ ...item, propertyChains: updatedChains });

        try {
            await ontologyMutationService.deletePropertyChain(projectId, item.id, chain);
        } catch (error) {
            console.error("Failed to delete property chain:", error);
            // Revert if the API call fails
            onUpdate({ ...item, propertyChains: item.propertyChains });
        }
    };

    const handlePropertyChainConfirm = async (chain: string[]) => {
        const expression = chain.join(' o ');
        const updatedChains = [...(item.propertyChains || []), expression];
        onUpdate({ ...item, propertyChains: updatedChains });

        try {
            await ontologyMutationService.addPropertyChain(projectId, item.id, expression);
            console.log("Property chain added:", expression);
        } catch (error) {
            console.error("Failed to add property chain:", error);
            // Revert on failure
            onUpdate({ ...item, propertyChains: item.propertyChains });
        }
    };
    
    const openChainEditor = () => {
        setIsChainDialogOpen(true);
    };

    const annotationCount = Object.keys(item.annotations || {}).length;

    return (
        <div className="flex flex-col h-full bg-white">
            {/* Header with IRI */}
            <div className="bg-gray-100 border-b border-gray-200 p-3 flex items-center justify-between">
                <div className="flex items-center gap-2 overflow-hidden">
                    <div className={`p-1 rounded text-xs font-bold ${isObjectProperty ? 'bg-blue-200 text-blue-800' : isDataProperty ? 'bg-green-200 text-green-800' : 'bg-orange-200 text-orange-800'}`}>
                        {isObjectProperty ? 'OP' : isDataProperty ? 'DP' : 'AP'}
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

            {/* Tabs - Protégé style */}
            <div className="flex border-b border-gray-200 bg-gray-50">
                <button 
                    onClick={() => setActiveTab('annotations')}
                    className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
                        activeTab === 'annotations' 
                            ? `border-${themeColor}-600 text-${themeColor}-700 bg-white` 
                            : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
                    }`}
                    style={activeTab === 'annotations' ? { borderColor: isObjectProperty ? '#2563eb' : isDataProperty ? '#16a34a' : '#ea580c' } : {}}
                >
                    Annotations ({annotationCount})
                </button>
                <button 
                    onClick={() => setActiveTab('usage')}
                    className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
                        activeTab === 'usage' 
                            ? `border-${themeColor}-600 text-${themeColor}-700 bg-white` 
                            : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
                    }`}
                    style={activeTab === 'usage' ? { borderColor: isObjectProperty ? '#2563eb' : isDataProperty ? '#16a34a' : '#ea580c' } : {}}
                >
                    Usage
                </button>
                <button 
                    onClick={() => setActiveTab('description')}
                    className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
                        activeTab === 'description' 
                            ? `border-${themeColor}-600 text-${themeColor}-700 bg-white` 
                            : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
                    }`}
                    style={activeTab === 'description' ? { borderColor: isObjectProperty ? '#2563eb' : isDataProperty ? '#16a34a' : '#ea580c' } : {}}
                >
                    Description
                </button>
            </div>

            {/* Main Content */}
            <div className="flex-1 overflow-y-auto bg-gray-50 p-3 min-h-0">
                {activeTab === 'annotations' && (
                    <div className="space-y-0">
                        {/* Annotations Panel Header - Protégé style */}
                        <div className={`${headerGradient} text-white px-3 py-2 flex items-center justify-between rounded-t-sm`}>
                            <span className="text-sm font-semibold">Annotations: {item.label}</span>
                            <div className="flex items-center gap-1">
                                <button onClick={onAddAnnotation} className="p-1 hover:bg-white/20 rounded transition-colors" title="Add annotation">
                                    <Plus size={16} />
                                </button>
                            </div>
                        </div>
                        {/* Annotations Content */}
                        <div className="bg-white border border-t-0 border-gray-200 rounded-b-sm">
                            <AnnotationsDisplay annotations={item.annotations} onDelete={onDeleteAnnotation} onEdit={onEditAnnotation} />
                        </div>
                    </div>
                )}

                {activeTab === 'usage' && (
                    <div className="flex flex-col h-full min-h-0">
                        {/* Usage Panel Header - Protégé style */}
                        <div className={`${headerGradient} text-white px-3 py-2 flex items-center justify-between rounded-t-sm flex-shrink-0`}>
                            <span className="text-sm font-semibold">Usage: {item.label}</span>
                        </div>
                        {/* Usage Content */}
                        <div className="bg-white border border-t-0 border-gray-200 rounded-b-sm flex-1 min-h-0">
                            <PropertyUsageTab 
                                propertyIri={item.id} 
                                projectId={projectId} 
                                label={item.label} 
                                propertyType={item.type}
                            />
                        </div>
                    </div>
                )}

                {activeTab === 'description' && (
                    <div className="space-y-0">
                        {/* Description Panel Header - Protégé style */}
                        <div className={`${headerGradient} text-white px-3 py-2 flex items-center justify-between rounded-t-sm`}>
                            <span className="text-sm font-semibold">Description: {item.label}</span>
                        </div>
                        {/* Description Content */}
                        <div className="bg-white border border-t-0 border-gray-200 rounded-b-sm p-3 space-y-3">
                            {/* Characteristics - only for Object/Data properties */}
                            {!isAnnotationProperty && characteristics.length > 0 && (
                                <div className="mb-3">
                                    <div className={`${isObjectProperty ? 'bg-blue-600' : 'bg-green-600'} text-white px-2 py-1.5 rounded-t-sm text-xs font-medium`}>
                                        Characteristics
                                    </div>
                                    <div className="bg-white border border-t-0 border-gray-200 rounded-b-sm p-2">
                                        <div className="grid grid-cols-2 gap-2">
                                            {characteristics.map(({ key, label }) => (
                                                <label key={key} className="flex items-center gap-2 text-sm cursor-pointer hover:bg-gray-50 p-1 rounded">
                                                    <input 
                                                        type="checkbox" 
                                                        checked={item.characteristics?.includes(key)} 
                                                        onChange={e => handleCharacteristicChange(key, e.target.checked)}
                                                        className="hidden"
                                                    />
                                                    {item.characteristics?.includes(key) ? (
                                                        <CheckSquare size={16} className={isObjectProperty ? 'text-blue-600' : 'text-green-600'} />
                                                    ) : (
                                                        <Square size={16} className="text-gray-300" />
                                                    )}
                                                    <span className={item.characteristics?.includes(key) ? 'text-gray-900 font-medium' : 'text-gray-500'}>{label}</span>
                                                </label>
                                            ))}
                                        </div>
                                    </div>
                                </div>
                            )}

                            <MultiSelectSection
                                title="Equivalent To"
                                items={item.equivalentProperties}
                                onAddClick={onAddEquivalentClick}
                                onDelete={prop => handleDeleteRelation('equivalent', prop)}
                                themeColor={isObjectProperty ? 'blue' : 'green'}
                                itemEntityType={isObjectProperty ? 'objectProperty' : 'dataProperty'}
                            />

                        <MultiSelectSection
                            title="SubProperty Of"
                            items={item.superProperties}
                            onAddClick={onAddSubPropertyClick}
                            onDelete={prop => handleDeleteRelation('subProperty', prop)}
                            themeColor={isObjectProperty ? 'blue' : 'green'}
                            itemEntityType={isObjectProperty ? 'objectProperty' : 'dataProperty'}
                        />

                        {isObjectProperty && (
                            <MultiSelectSection
                                title="Inverse Of"
                                items={item.inverseProperties}
                                onAddClick={onAddInverseClick}
                                onDelete={prop => handleDeleteRelation('inverse', prop)}
                                themeColor="blue"
                                itemEntityType="objectProperty"
                            />
                        )}

                        {!isAnnotationProperty && (
                        <MultiSelectSection
                            title="Domains (Intersection)"
                            items={item.domains}
                            onAddClick={onAddDomainClick}
                            onDelete={domain => handleDeleteRelation('domain', domain)}
                            themeColor={isObjectProperty ? 'blue' : 'green'}
                            itemEntityType="class"
                        />
                        )}

                        {!isAnnotationProperty && (
                        <MultiSelectSection
                            title="Ranges (Intersection)"
                            items={item.ranges}
                            onAddClick={onAddRangeClick}
                            onDelete={range => handleDeleteRelation('range', range)}
                            themeColor={isObjectProperty ? 'blue' : 'green'}
                            itemEntityType={isObjectProperty ? 'class' : 'datatype'}
                        />
                        )}

                        <MultiSelectSection
                            title="Disjoint With"
                            items={item.disjointProperties}
                            onAddClick={onAddDisjointClick}
                            onDelete={prop => handleDeleteRelation('disjoint', prop)}
                            themeColor={isObjectProperty ? 'blue' : 'green'}
                            itemEntityType={isObjectProperty ? 'objectProperty' : 'dataProperty'}
                        />

                        {isObjectProperty && (
                            <MultiSelectSection
                                title="SuperProperty Of (Chain)"
                                items={item.propertyChains}
                                onAddClick={openChainEditor}
                                onDelete={handleDeletePropertyChain}
                                themeColor="blue"
                            />
                        )}
                        </div>
                    </div>
                )}
            </div>

            <ManchesterSyntaxEditor
                isOpen={isEditorOpen}
                onClose={() => setIsEditorOpen(false)}
                onConfirm={(val) => {
                    if (editorAction) editorAction(val);
                    setIsEditorOpen(false);
                }}
                title={editorTitle}
                projectId={projectId}
                initialValue=""
            />

            <PropertyChainDialog
                isOpen={isChainDialogOpen}
                onClose={() => setIsChainDialogOpen(false)}
                onConfirm={handlePropertyChainConfirm}
                properties={objectProperties}
                title="Create Property Chain"
            />
        </div>
    );
};

export default PropertyEditor;
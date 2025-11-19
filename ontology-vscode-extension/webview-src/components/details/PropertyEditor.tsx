import React, { useState } from 'react';
import { Plus, Trash2, CheckSquare, Square } from 'lucide-react';
import { Panel, AnnotationsDisplay, MultiSelectSection } from './common';
import { ManchesterSyntaxEditor } from '../dialogs';
import ontologyMutationService from '../../services/ontologyMutationService';
import type { Property } from '../../types';


const PropertyEditor: React.FC<{
  item: Property;
  onUpdate: (updatedItem: Property) => void;
  onAddAnnotation: () => void;
  onDeleteAnnotation: (key: string) => void;
  activeTheme?: string;
  projectId: string;
  onAddDomainClick?: () => void;
  onAddRangeClick?: () => void;
  onAddSubPropertyClick?: () => void;
  onAddInverseClick?: () => void;
  onAddDisjointClick?: () => void;
  onAddEquivalentClick?: () => void;
}> = ({ 
    item, 
    onUpdate, 
    onAddAnnotation, 
    onDeleteAnnotation, 
    activeTheme, 
    projectId,
    onAddDomainClick,
    onAddRangeClick,
    onAddSubPropertyClick,
    onAddInverseClick,
    onAddDisjointClick,
    onAddEquivalentClick
}) => {
    const [isEditorOpen, setIsEditorOpen] = useState(false);
    const [editorTitle, setEditorTitle] = useState("");
    const [editorAction, setEditorAction] = useState<((val: string) => void) | null>(null);

    const isObjectProperty = item.type === 'ObjectProperty';
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
        : [{ key: 'Functional', label: 'Functional' }];
    
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
                case 'equivalent':
                    await ontologyMutationService.addEquivalentProperty(projectId, item.id, target);
                    // Assuming we add an equivalentProperties field to Property type or reuse one
                    break;
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
                 case 'equivalent':
                    await ontologyMutationService.deleteEquivalentProperty(projectId, item.id, target);
                    break;
            }
        } catch (error) {
            console.error(`Failed to delete ${relation}`, error);
        }
    };

    const handleAddPropertyChain = async (expression: string) => {
        try {
            await ontologyMutationService.addAxiom(projectId, item.id, 'PropertyChain' as any, expression);
            // We should reload property details here, but we don't have loadPropertyDetails function exposed or local.
            // PropertyEditor receives 'item' from parent.
            // We can call onUpdate with optimistic update, but we don't know the ID.
            // Ideally, we should trigger a reload in parent.
            // For now, let's just log.
            console.log("Property chain added:", expression);
        } catch (error) {
            console.error("Failed to add property chain:", error);
        }
    };

    const handleDeletePropertyChain = async (chain: string) => {
        // Implement delete logic
        console.log("Delete property chain:", chain);
    };

    const openChainEditor = () => {
        setEditorTitle("Add Property Chain");
        setEditorAction(() => handleAddPropertyChain);
        setIsEditorOpen(true);
    };

    return (
        <div className="flex flex-col h-full bg-white">
            {/* Header with IRI */}
            <div className="bg-gray-100 border-b border-gray-200 p-3 flex items-center justify-between">
                <div className="flex items-center gap-2 overflow-hidden">
                <div className={`p-1 rounded text-xs font-bold ${isObjectProperty ? 'bg-blue-200 text-blue-800' : 'bg-green-200 text-green-800'}`}>
                    {isObjectProperty ? 'OP' : 'DP'}
                </div>
                <div className="flex flex-col min-w-0">
                    <span className="font-bold text-sm truncate">{item.label}</span>
                    <span className="text-xs text-gray-500 truncate font-mono">{item.id}</span>
                </div>
                </div>
            </div>

            <div className="flex-1 overflow-y-auto bg-gray-50 p-3 space-y-4">
                {/* Annotations Section */}
                <Panel title="Annotations" defaultOpen={true} themeColor="bg-gradient-to-b from-gray-50 to-gray-100 text-gray-800 border-gray-200"
                    actions={
                        <button onClick={onAddAnnotation} className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-purple-600" title="Add annotation">
                        <Plus size={14} />
                        </button>
                    }
                >
                    <div className="p-2">
                        <AnnotationsDisplay annotations={item.annotations} onDelete={onDeleteAnnotation} />
                    </div>
                </Panel>

                {/* Description Section */}
                <Panel title="Description" defaultOpen={true} themeColor={isObjectProperty ? 'bg-gradient-to-b from-blue-50 to-blue-100 text-blue-900 border-blue-200' : 'bg-gradient-to-b from-green-50 to-green-100 text-green-900 border-green-200'}>
                    <div className="p-3 space-y-4">
                        {/* Characteristics */}
                        <div className="mb-4">
                            <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-2">Characteristics</h4>
                            <div className="grid grid-cols-2 gap-2 bg-white p-2 border border-gray-200 rounded-md">
                                {characteristics.map(({ key, label }) => (
                                    <label key={key} className="flex items-center gap-2 text-sm cursor-pointer hover:bg-gray-50 p-1 rounded">
                                        <input 
                                            type="checkbox" 
                                            checked={item.characteristics?.includes(key)} 
                                            onChange={e => handleCharacteristicChange(key, e.target.checked)}
                                            className="hidden"
                                        />
                                        {item.characteristics?.includes(key) ? (
                                            <CheckSquare size={16} className="text-purple-600" />
                                        ) : (
                                            <Square size={16} className="text-gray-300" />
                                        )}
                                        <span className={item.characteristics?.includes(key) ? 'text-gray-900 font-medium' : 'text-gray-500'}>{label}</span>
                                    </label>
                                ))}
                            </div>
                        </div>

                        <MultiSelectSection
                            title="Equivalent To"
                            items={item.equivalentProperties}
                            onAddClick={onAddEquivalentClick}
                            onDelete={prop => handleDeleteRelation('equivalent', prop)}
                        />

                        <MultiSelectSection
                            title="SubProperty Of"
                            items={item.superProperties}
                            onAddClick={onAddSubPropertyClick}
                            onDelete={prop => handleDeleteRelation('subProperty', prop)}
                        />

                        {isObjectProperty && (
                            <MultiSelectSection
                                title="Inverse Of"
                                items={item.inverseProperties}
                                onAddClick={onAddInverseClick}
                                onDelete={prop => handleDeleteRelation('inverse', prop)}
                            />
                        )}

                        <MultiSelectSection
                            title="Domains (Intersection)"
                            items={item.domains}
                            onAddClick={onAddDomainClick}
                            onDelete={domain => handleDeleteRelation('domain', domain)}
                        />

                        <MultiSelectSection
                            title="Ranges (Intersection)"
                            items={item.ranges}
                            onAddClick={onAddRangeClick}
                            onDelete={range => handleDeleteRelation('range', range)}
                        />

                        <MultiSelectSection
                            title="Disjoint With"
                            items={item.disjointProperties}
                            onAddClick={onAddDisjointClick}
                            onDelete={prop => handleDeleteRelation('disjoint', prop)}
                        />

                        {isObjectProperty && (
                            <MultiSelectSection
                                title="Property Chains"
                                items={item.propertyChains}
                                onAddClick={openChainEditor}
                                onDelete={handleDeletePropertyChain}
                            />
                        )}
                    </div>
                </Panel>
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
        </div>
    );
};

export default PropertyEditor;
import React, { useState, useEffect } from 'react';
import { Plus, Trash2, Loader2 } from 'lucide-react';
import { Panel, AnnotationsDisplay, MultiSelectSection } from './common';
import type { Individual, PropertyAssertion } from '../../types';
import { ManchesterSyntaxEditor, IndividualSelectorDialog, PropertyAssertionDialog } from '../dialogs';
import ontologyMutationService from '../../services/ontologyMutationService';
import apiClient from '../../services/apiClient';

const IndividualEditor: React.FC<{
  item: Individual;
  onUpdate: (updatedItem: Individual) => void;
  onAddAnnotation: () => void;
  onEditAnnotation: (propertyIri: string, currentValue: string) => void;
  onDeleteAnnotation: (key: string) => void;
  activeTheme?: string;
  projectId: string;
  userId?: string;
  username?: string;
}> = ({ item, onUpdate, onAddAnnotation, onEditAnnotation, onDeleteAnnotation, activeTheme, projectId, userId, username }) => {
  const [isAddingAssertion, setIsAddingAssertion] = useState(false);
  const [newAssertion, setNewAssertion] = useState({ propertyLabel: '', targetLabel: '', isObjectProperty: true });
  const [isLoading, setIsLoading] = useState(false);
  const [detailsFetched, setDetailsFetched] = useState<string | null>(null);
  const [propertySuggestions, setPropertySuggestions] = useState<{ label: string; value: string }[]>([]);
  const [individualSuggestions, setIndividualSuggestions] = useState<{ label: string; value: string }[]>([]);
  const [sameDiffDialog, setSameDiffDialog] = useState<null | { mode: 'same' | 'different' }>(null);
  const [allIndividuals, setAllIndividuals] = useState<Individual[]>([]);

  // Fetch individual details when component mounts or item changes
  useEffect(() => {
    const fetchIndividualDetails = async () => {
      // Only fetch if we haven't fetched for this item yet
      if (detailsFetched === item.id || !projectId || !item.id) return;
      
      setIsLoading(true);
      try {
        // Use query parameter endpoint to avoid URL encoding issues with IRI containing #
        const response = await apiClient.get<any>(`/api/ontology/individual-details/${projectId}?individualIri=${encodeURIComponent(item.id)}`);
        
        console.log('[IndividualEditor] Fetched individual details:', response);
        
        // Extract data from response
        const details = response?.data || response;
        
        // Update the item with property assertions from backend
        if (details) {
          const updatedItem: Individual = {
            ...item,
            types: details.types || item.types,
            annotations: details.annotations || item.annotations,
            propertyAssertions: details.propertyAssertions || [],
            sameIndividualAs: details.sameAs || item.sameIndividualAs,
            differentIndividualFrom: details.differentFrom || item.differentIndividualFrom,
          };
          
          onUpdate(updatedItem);
          setDetailsFetched(item.id);
        }
      } catch (error) {
        console.error('[IndividualEditor] Failed to fetch individual details:', error);
      } finally {
        setIsLoading(false);
      }
    };

    fetchIndividualDetails();
  }, [item.id, projectId]);

  // Separate object and data property assertions
  const objectPropertyAssertions = item.propertyAssertions?.filter(a => a.isObjectProperty) || [];
  const dataPropertyAssertions = item.propertyAssertions?.filter(a => !a.isObjectProperty) || [];
  
  const [isEditorOpen, setIsEditorOpen] = useState(false);
  const [editorTitle, setEditorTitle] = useState("");
  const [editorAction, setEditorAction] = useState<((val: string) => void) | null>(null);

  const handleAddAssertion = async () => {
    if (!newAssertion.propertyLabel || !newAssertion.targetLabel) {
        alert("Property and value cannot be empty.");
        return;
    }
    
    // Build proper IRIs from the input
    const baseIri = item.id.substring(0, item.id.lastIndexOf('#') + 1) || 'http://example.com/onto#';
    const propertyIri = newAssertion.propertyLabel.startsWith('http') 
      ? newAssertion.propertyLabel 
      : `${baseIri}${newAssertion.propertyLabel.replace(/\s+/g, '_')}`;
    const targetIri = newAssertion.isObjectProperty 
      ? (newAssertion.targetLabel.startsWith('http') 
        ? newAssertion.targetLabel 
        : `${baseIri}${newAssertion.targetLabel.replace(/\s+/g, '_')}`)
      : newAssertion.targetLabel;
    
    try {
      // Call mutation service to persist
      if (newAssertion.isObjectProperty) {
        await ontologyMutationService.addObjectPropertyAssertion(
          projectId, item.id, propertyIri, targetIri, userId, username
        );
      } else {
        await ontologyMutationService.addDataPropertyAssertion(
          projectId, item.id, propertyIri, newAssertion.targetLabel, userId, username
        );
      }
      
      // Update local state
      const newAssertionObject: PropertyAssertion = {
          id: `assertion-${Date.now()}`,
          propertyIri: propertyIri,
          propertyLabel: newAssertion.propertyLabel,
          [newAssertion.isObjectProperty ? 'targetIri' : 'targetLiteral']: newAssertion.isObjectProperty ? targetIri : `"${newAssertion.targetLabel}"`,
          [newAssertion.isObjectProperty ? 'targetLabel' : '']: newAssertion.targetLabel,
          isObjectProperty: newAssertion.isObjectProperty,
      };
      onUpdate({ ...item, propertyAssertions: [...(item.propertyAssertions || []), newAssertionObject] });
      setNewAssertion({ propertyLabel: '', targetLabel: '', isObjectProperty: true });
      setIsAddingAssertion(false);
    } catch (error) {
      console.error('Failed to add property assertion:', error);
      alert('Failed to add property assertion. See console for details.');
    }
  };

  const openPropertyAssertionDialog = async (isObjectProperty: boolean) => {
    setNewAssertion(p => ({ ...p, isObjectProperty }));
    setIsAddingAssertion(true);

    try {
      if (!projectId) return;
      const [propsRes, indsRes] = await Promise.all([
        apiClient.get<any>(`/api/ontology/${isObjectProperty ? 'object-properties' : 'data-properties'}/${projectId}`),
        isObjectProperty ? apiClient.get<any>(`/api/ontology/individuals/${projectId}`) : Promise.resolve({ data: [] })
      ]);

      const props = Array.isArray(propsRes?.data) ? propsRes.data : propsRes?.data?.properties || [];
      const propSuggestions = (props || []).map((p: any) => ({
        label: p.label || p.name || (typeof p === 'string' ? p.split('#').pop() : String(p)),
        value: p.id || p.iri || p.value || p.label || String(p)
      }));
      setPropertySuggestions(propSuggestions);

      if (isObjectProperty) {
        const inds = Array.isArray(indsRes?.data) ? indsRes.data : indsRes?.data?.individuals || [];
        const indSuggestions = (inds || []).map((i: any) => ({
          label: i.label || (typeof i === 'string' ? i.split('#').pop() : String(i)),
          value: i.id || i.iri || i.value || i.label || String(i)
        }));
        setIndividualSuggestions(indSuggestions);
      } else {
        setIndividualSuggestions([]);
      }
    } catch (e) {
      console.error('[IndividualEditor] Failed to load suggestions for assertion dialog:', e);
      setPropertySuggestions([]);
      setIndividualSuggestions([]);
    }
  };

  const openSameDifferentDialog = async (mode: 'same' | 'different') => {
    setSameDiffDialog({ mode });
    try {
      const res = await apiClient.get<any>(`/api/ontology/individuals/${projectId}`);
      const inds = Array.isArray(res?.data) ? res.data : res?.data?.individuals || [];
      setAllIndividuals(inds);
    } catch (e) {
      console.error('[IndividualEditor] Failed to load individuals:', e);
      setAllIndividuals([]);
    }
  };

  const handleDeleteAssertion = async (assertion: PropertyAssertion) => {
    try {
      // Call mutation service to persist deletion
      if (assertion.isObjectProperty && assertion.targetIri) {
        await ontologyMutationService.deleteObjectPropertyAssertion(
          projectId, item.id, assertion.propertyIri, assertion.targetIri, userId, username
        );
      } else if (assertion.targetLiteral) {
        // Remove quotes from literal value if present
        const literalValue = assertion.targetLiteral.replace(/^"|"$/g, '');
        await ontologyMutationService.deleteDataPropertyAssertion(
          projectId, item.id, assertion.propertyIri, literalValue, userId, username
        );
      }
      
      // Update local state
      onUpdate({ ...item, propertyAssertions: item.propertyAssertions?.filter(a => a.id !== assertion.id) });
    } catch (error) {
      console.error('Failed to delete property assertion:', error);
      alert('Failed to delete property assertion. See console for details.');
    }
  };

  const handleAddSameAs = async (iri: string) => {
      try {
          await ontologyMutationService.addSameIndividual(projectId, item.id, iri, userId, username);
          // Optimistic update
          onUpdate({ ...item, sameIndividualAs: [...(item.sameIndividualAs || []), iri] });
      } catch (e) { console.error(e); }
  };

  const handleAddDifferentFrom = async (iri: string) => {
      try {
          await ontologyMutationService.addDifferentIndividual(projectId, item.id, iri, userId, username);
          // Optimistic update
          onUpdate({ ...item, differentIndividualFrom: [...(item.differentIndividualFrom || []), iri] });
      } catch (e) { console.error(e); }
  };

  const handleDeleteSameAs = async (iri: string) => {
      try {
        await ontologyMutationService.deleteSameIndividual(projectId, item.id, iri, userId, username);
        onUpdate({ ...item, sameIndividualAs: item.sameIndividualAs?.filter(i => i !== iri) });
      } catch (e) { console.error(e); }
  };

  const handleDeleteDifferentFrom = async (iri: string) => {
      try {
        await ontologyMutationService.deleteDifferentIndividual(projectId, item.id, iri, userId, username);
        onUpdate({ ...item, differentIndividualFrom: item.differentIndividualFrom?.filter(i => i !== iri) });
      } catch (e) { console.error(e); }
  };

  const handleAddType = async (iri: string) => {
      try {
          await ontologyMutationService.addClassAssertion(projectId, item.id, iri);
          // Optimistic update
          onUpdate({ ...item, types: [...(item.types || []), iri] });
      } catch (e) { console.error(e); }
  };

  const openEditor = (title: string, action: (val: string) => void) => {
      setEditorTitle(title);
      setEditorAction(() => action);
      setIsEditorOpen(true);
  };
  
  return (
    <div className="flex flex-col h-full bg-white">
      {/* Header with IRI */}
      <div className="bg-gray-100 border-b border-gray-200 p-3 flex items-center justify-between">
        <div className="flex items-center gap-2 overflow-hidden">
          <div className="bg-purple-200 text-purple-800 p-1 rounded text-xs font-bold">I</div>
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
            <AnnotationsDisplay annotations={item.annotations} onDelete={onDeleteAnnotation} onEdit={onEditAnnotation} />
          </div>
        </Panel>

        {/* Description Section */}
        <Panel title="Description" defaultOpen={true} themeColor="bg-gradient-to-b from-purple-50 to-purple-100 text-purple-900 border-purple-200">
          <div className="p-3 space-y-4">
            {/* Types */}
            <div className="mb-4 last:mb-0">
              <div className="flex justify-between items-center mb-1">
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Types</h4>
                <button onClick={() => openEditor('Add Type (Class IRI)', handleAddType)} className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-purple-600 transition-colors" title="Add type">
                  <Plus size={14} />
                </button>
              </div>
              <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm p-1.5 space-y-1">
                {item.types?.map(type => (
                    <div key={type} className="text-xs p-1 bg-gray-50 rounded border border-gray-100">{type.split('#').pop()}</div>
                ))}
                {(!item.types || item.types.length === 0) && (
                    <div className="text-xs text-gray-400 italic p-1">No types defined</div>
                )}
              </div>
            </div>

            {/* Object Property Assertions */}
            <div className="mb-4 last:mb-0">
              <div className="flex justify-between items-center mb-1">
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Object property assertions</h4>
                <button onClick={() => openPropertyAssertionDialog(true)} className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-purple-600 transition-colors" title="Add object property assertion">
                  <Plus size={14} />
                </button>
              </div>
              <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm">
                <div className="p-1.5 space-y-1">
                    {isLoading ? (
                      <div className="flex items-center justify-center p-3 text-gray-500">
                        <Loader2 size={16} className="animate-spin mr-2" />
                        <span className="text-xs">Loading...</span>
                      </div>
                    ) : (
                      <>
                        {objectPropertyAssertions.map(assertion => (
                          <div key={assertion.id} className="group flex items-center justify-between text-xs bg-blue-50 p-1.5 rounded-sm border border-blue-100">
                              <div>
                                  <span className="font-semibold text-blue-700">{assertion.propertyLabel}</span>
                                  <span className="mx-1.5 text-gray-400">→</span>
                                  <span className="text-blue-600">{assertion.targetLabel || assertion.targetIri?.split('#').pop()}</span>
                              </div>
                              <button onClick={() => handleDeleteAssertion(assertion)} className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-red-200">
                                  <Trash2 size={12} className="text-red-600"/>
                              </button>
                          </div>
                        ))}
                        {objectPropertyAssertions.length === 0 && (
                          <div className="text-xs text-gray-400 italic p-1">No object property assertions</div>
                        )}
                      </>
                    )}
                </div>
              </div>
            </div>

            {/* Data Property Assertions */}
            <div className="mb-4 last:mb-0">
              <div className="flex justify-between items-center mb-1">
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Data property assertions</h4>
                <button onClick={() => openPropertyAssertionDialog(false)} className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-green-600 transition-colors" title="Add data property assertion">
                  <Plus size={14} />
                </button>
              </div>
              <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm">
                <div className="p-1.5 space-y-1">
                    {isLoading ? (
                      <div className="flex items-center justify-center p-3 text-gray-500">
                        <Loader2 size={16} className="animate-spin mr-2" />
                        <span className="text-xs">Loading...</span>
                      </div>
                    ) : (
                      <>
                        {dataPropertyAssertions.map(assertion => (
                          <div key={assertion.id} className="group flex items-center justify-between text-xs bg-green-50 p-1.5 rounded-sm border border-green-100">
                              <div>
                                  <span className="font-semibold text-green-700">{assertion.propertyLabel}</span>
                                  <span className="mx-1.5 text-gray-400">=</span>
                                  <span className="text-green-600">{assertion.targetLiteral}</span>
                              </div>
                              <button onClick={() => handleDeleteAssertion(assertion)} className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-red-200">
                                  <Trash2 size={12} className="text-red-600"/>
                              </button>
                          </div>
                        ))}
                        {dataPropertyAssertions.length === 0 && (
                          <div className="text-xs text-gray-400 italic p-1">No data property assertions</div>
                        )}
                      </>
                    )}
                </div>
              </div>
            </div>

            {/* Add Assertion Dialog (Protégé-style) */}
            <PropertyAssertionDialog
              isOpen={isAddingAssertion}
              title={`Property assertions: ${item.label}`}
              propertyLabel={newAssertion.propertyLabel}
              targetLabel={newAssertion.targetLabel}
              isObjectProperty={newAssertion.isObjectProperty}
              propertySuggestions={propertySuggestions}
              targetSuggestions={individualSuggestions}
              onChange={(next) => setNewAssertion(next)}
              onCancel={() => {
                setIsAddingAssertion(false);
                setNewAssertion(p => ({ ...p, propertyLabel: '', targetLabel: '' }));
              }}
              onConfirm={async () => {
                await handleAddAssertion();
                setIsAddingAssertion(false);
                setNewAssertion(p => ({ ...p, propertyLabel: '', targetLabel: '' }));
              }}
            />

            {/* Same Individual As / Different Individual From Section */}
            <div className="mb-4 last:mb-0">
              <div className="flex justify-between items-center mb-1">
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Same Individual As / Different Individual From</h4>
              </div>
              <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm p-1.5">
                {/* Same Individual As */}
                <MultiSelectSection
                    title="Same Individual As"
                    items={item.sameIndividualAs}
                    onAddClick={() => openSameDifferentDialog('same')}
                    onDelete={handleDeleteSameAs}
                    themeColor="purple"
                    itemEntityType="individual"
                />

                {/* Different Individual From */}
                <MultiSelectSection
                    title="Different Individual From"
                    items={item.differentIndividualFrom}
                    onAddClick={() => openSameDifferentDialog('different')}
                    onDelete={handleDeleteDifferentFrom}
                    themeColor="purple"
                    itemEntityType="individual"
                />
              </div>
            </div>
          </div>
        </Panel>
      </div>

      {/* Manchester Syntax Editor Dialog */}
      {isEditorOpen && (
        <ManchesterSyntaxEditor
          isOpen={isEditorOpen}
          onClose={() => setIsEditorOpen(false)}
          title={editorTitle}
          onConfirm={val => {
              if (editorAction) editorAction(val);
              setIsEditorOpen(false);
          }}
          projectId={projectId}
        />
      )}

      {/* Protégé-style selector for same/different individuals */}
      {sameDiffDialog && (
        <IndividualSelectorDialog
          isOpen={true}
          onClose={() => setSameDiffDialog(null)}
          title={sameDiffDialog.mode === 'same' ? `Same Individual As: ${item.label}` : `Different Individuals: ${item.label}`}
          individuals={allIndividuals}
          projectId={projectId}
          excludeIndividualIds={[
            item.id,
            ...(sameDiffDialog.mode === 'same' ? (item.sameIndividualAs || []) : (item.differentIndividualFrom || []))
          ]}
          minSelection={1}
          onConfirm={async (inds) => {
            if (sameDiffDialog.mode === 'same') {
              for (const ind of inds) await handleAddSameAs(ind.id);
            } else {
              for (const ind of inds) await handleAddDifferentFrom(ind.id);
            }
            setSameDiffDialog(null);
          }}
        />
      )}
    </div>
  );
};

export default IndividualEditor;

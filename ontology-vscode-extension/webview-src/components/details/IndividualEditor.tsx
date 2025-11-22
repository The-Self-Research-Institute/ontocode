import React, { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Panel, AnnotationsDisplay, MultiSelectSection } from './common';
import type { Individual, PropertyAssertion } from '../../types';
import { ManchesterSyntaxEditor } from '../dialogs';
import ontologyMutationService from '../../services/ontologyMutationService';

const IndividualEditor: React.FC<{
  item: Individual;
  onUpdate: (updatedItem: Individual) => void;
  onAddAnnotation: () => void;
  onEditAnnotation: (propertyIri: string, currentValue: string) => void;
  onDeleteAnnotation: (key: string) => void;
  activeTheme?: string;
  projectId: string;
}> = ({ item, onUpdate, onAddAnnotation, onEditAnnotation, onDeleteAnnotation, activeTheme, projectId }) => {
  const [isAddingAssertion, setIsAddingAssertion] = useState(false);
  const [newAssertion, setNewAssertion] = useState({ propertyLabel: '', targetLabel: '', isObjectProperty: true });
  
  const [isEditorOpen, setIsEditorOpen] = useState(false);
  const [editorTitle, setEditorTitle] = useState("");
  const [editorAction, setEditorAction] = useState<((val: string) => void) | null>(null);

  const handleAddAssertion = () => {
    if (!newAssertion.propertyLabel || !newAssertion.targetLabel) {
        alert("Property and value cannot be empty.");
        return;
    }
    const newAssertionObject: PropertyAssertion = {
        id: `assertion-${Date.now()}`,
        propertyIri: `:${newAssertion.propertyLabel}`,
        propertyLabel: newAssertion.propertyLabel,
        [newAssertion.isObjectProperty ? 'targetIri' : 'targetLiteral']: newAssertion.isObjectProperty ? `:${newAssertion.targetLabel}` : `"${newAssertion.targetLabel}"`,
        [newAssertion.isObjectProperty ? 'targetLabel' : '']: newAssertion.targetLabel,
        isObjectProperty: newAssertion.isObjectProperty,
    };
    onUpdate({ ...item, propertyAssertions: [...(item.propertyAssertions || []), newAssertionObject] });
    setNewAssertion({ propertyLabel: '', targetLabel: '', isObjectProperty: true });
    setIsAddingAssertion(false);
  };

  const handleDeleteAssertion = (id: string) => {
    onUpdate({ ...item, propertyAssertions: item.propertyAssertions?.filter(a => a.id !== id) });
  };

  const handleAddSameAs = async (iri: string) => {
      try {
          await ontologyMutationService.addAxiom(projectId, item.id, 'SameIndividual' as any, iri);
          // Optimistic update
          onUpdate({ ...item, sameIndividualAs: [...(item.sameIndividualAs || []), iri] });
      } catch (e) { console.error(e); }
  };

  const handleAddDifferentFrom = async (iri: string) => {
      try {
          await ontologyMutationService.addAxiom(projectId, item.id, 'DifferentIndividuals' as any, iri);
          // Optimistic update
          onUpdate({ ...item, differentIndividualFrom: [...(item.differentIndividualFrom || []), iri] });
      } catch (e) { console.error(e); }
  };

  const handleDeleteSameAs = async (iri: string) => {
      // Implement delete
      onUpdate({ ...item, sameIndividualAs: item.sameIndividualAs?.filter(i => i !== iri) });
  };

  const handleDeleteDifferentFrom = async (iri: string) => {
      // Implement delete
      onUpdate({ ...item, differentIndividualFrom: item.differentIndividualFrom?.filter(i => i !== iri) });
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
                <button className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-purple-600 transition-colors" title="Add type">
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

            {/* Property Assertions */}
            <div className="mb-4 last:mb-0">
              <div className="flex justify-between items-center mb-1">
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Property assertions</h4>
                <button onClick={() => setIsAddingAssertion(true)} className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-purple-600 transition-colors" title="Add assertion">
                  <Plus size={14} />
                </button>
              </div>
              <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm">
                <div className="p-1.5 space-y-1">
                    {item.propertyAssertions?.map(assertion => (
                        <div key={assertion.id} className="group flex items-center justify-between text-xs bg-gray-50 p-1.5 rounded-sm border border-gray-100">
                            <div>
                                <span className="font-semibold text-purple-700">{assertion.propertyLabel}</span>
                                <span className="mx-1.5 text-gray-400">{assertion.isObjectProperty ? '→' : '='}</span>
                                <span>{assertion.isObjectProperty ? assertion.targetLabel : assertion.targetLiteral}</span>
                            </div>
                            <button onClick={() => handleDeleteAssertion(assertion.id)} className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-red-200">
                                <Trash2 size={12} className="text-red-600"/>
                            </button>
                        </div>
                    ))}
                    {isAddingAssertion && (
                        <div className="p-2 border border-purple-200 rounded-md bg-purple-50 space-y-2 text-xs mt-2">
                            <div className="flex gap-2 items-center">
                               <label className="flex items-center gap-1"><input type="radio" name="propType" checked={newAssertion.isObjectProperty} onChange={() => setNewAssertion(p => ({...p, isObjectProperty: true}))} /> Object</label>
                               <label className="flex items-center gap-1"><input type="radio" name="propType" checked={!newAssertion.isObjectProperty} onChange={() => setNewAssertion(p => ({...p, isObjectProperty: false}))}/> Data</label>
                            </div>
                            <input value={newAssertion.propertyLabel} onChange={e => setNewAssertion(p => ({...p, propertyLabel: e.target.value}))} placeholder="Property" className="w-full p-1.5 border rounded"/>
                            <input value={newAssertion.targetLabel} onChange={e => setNewAssertion(p => ({...p, targetLabel: e.target.value}))} placeholder={newAssertion.isObjectProperty ? "Target Individual" : "Literal Value"} className="w-full p-1.5 border rounded"/>
                            <div className="flex justify-end gap-2">
                                 <button onClick={() => setIsAddingAssertion(false)} className="px-2 py-1 bg-gray-200 rounded hover:bg-gray-300">Cancel</button>
                                 <button onClick={handleAddAssertion} className="px-2 py-1 bg-purple-600 text-white rounded hover:bg-purple-700">Save</button>
                            </div>
                        </div>
                    )}
                    {!isAddingAssertion && (!item.propertyAssertions || item.propertyAssertions.length === 0) && (
                        <div className="text-xs text-gray-400 italic p-1">No assertions defined</div>
                    )}
                </div>
              </div>
            </div>

            {/* Same Individual As / Different Individual From Section */}
            <div className="mb-4 last:mb-0">
              <div className="flex justify-between items-center mb-1">
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Same Individual As / Different Individual From</h4>
                <button onClick={() => openEditor('Add Same Individual As (IRI)', handleAddSameAs)} className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-purple-600 transition-colors" title="Add Same Individual As">
                  <Plus size={14} />
                </button>
                <button onClick={() => openEditor('Add Different Individual From (IRI)', handleAddDifferentFrom)} className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-purple-600 transition-colors" title="Add Different Individual From">
                  <Plus size={14} />
                </button>
              </div>
              <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm p-1.5">
                {/* Same Individual As */}
                <MultiSelectSection
                    title="Same Individual As"
                    items={item.sameIndividualAs}
                    onAddClick={() => openEditor("Add Same Individual As (IRI)", handleAddSameAs)}
                    onDelete={handleDeleteSameAs}
                />

                {/* Different Individual From */}
                <MultiSelectSection
                    title="Different Individual From"
                    items={item.differentIndividualFrom}
                    onAddClick={() => openEditor("Add Different Individual From (IRI)", handleAddDifferentFrom)}
                    onDelete={handleDeleteDifferentFrom}
                />
              </div>
            </div>
          </div>
        </Panel>
      </div>

      {/* Manchester Syntax Editor Dialog */}
      {isEditorOpen && (
        <ManchesterSyntaxEditor
          open={isEditorOpen}
          onClose={() => setIsEditorOpen(false)}
          title={editorTitle}
          onSave={val => {
              if (editorAction) editorAction(val);
              setIsEditorOpen(false);
          }}
        />
      )}
    </div>
  );
};

export default IndividualEditor;

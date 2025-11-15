import React, { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Panel, AnnotationsDisplay } from './common';
import type { Individual, PropertyAssertion } from '../../types';

const IndividualEditor: React.FC<{
  item: Individual;
  onUpdate: (updatedItem: Individual) => void;
  onAddAnnotation: () => void;
  onDeleteAnnotation: (key: string) => void;
  activeTheme?: string;
  onEditAnnotation: (key: string, value: string) => void;
}> = ({ item, onUpdate, onAddAnnotation, onDeleteAnnotation, activeTheme,onEditAnnotation }) => {
  const [isAddingAssertion, setIsAddingAssertion] = useState(false);
  const [newAssertion, setNewAssertion] = useState({ propertyLabel: '', targetLabel: '', isObjectProperty: true });

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
  
  return (
    <div className="flex flex-col gap-2 h-full">
      <Panel title={`Annotations: ${item.label}`} actions={<button onClick={onAddAnnotation} className="p-0.5 hover:bg-black/20 rounded-full"><Plus size={14} /></button>} themeColor={activeTheme}>
        <AnnotationsDisplay annotations={item.annotations} onDelete={onDeleteAnnotation} onEdit={onEditAnnotation}/>
      </Panel>
      <Panel title="Types" defaultOpen={true} themeColor={activeTheme}>
        <div className="p-1.5 space-y-1">
            {item.types?.map(type => (
                <div key={type} className="text-xs">{type.split('#').pop()}</div>
            ))}
             <button className="text-xs text-gray-400 italic hover:text-purple-600 hover:underline">
                Add type...
             </button>
        </div>
      </Panel>
      <Panel title="Property assertions" defaultOpen={true} themeColor={activeTheme}>
        <div className="p-1.5 space-y-1">
            {item.propertyAssertions?.map(assertion => (
                <div key={assertion.id} className="group flex items-center justify-between text-xs bg-gray-50 p-1.5 rounded-sm">
                    <div>
                        <span className="font-semibold text-purple-700">{assertion.propertyLabel}</span>
                        <span className="mx-1.5">{assertion.isObjectProperty ? '->' : '='}</span>
                        <span>{assertion.isObjectProperty ? assertion.targetLabel : assertion.targetLiteral}</span>
                    </div>
                    <button onClick={() => handleDeleteAssertion(assertion.id)} className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-red-200">
                        <Trash2 size={12} className="text-red-600"/>
                    </button>
                </div>
            ))}
            {isAddingAssertion && (
                <div className="p-2 border border-purple-200 rounded-md bg-purple-50 space-y-2 text-xs">
                    <div className="flex gap-2 items-center">
                       <label><input type="radio" name="propType" checked={newAssertion.isObjectProperty} onChange={() => setNewAssertion(p => ({...p, isObjectProperty: true}))} /> Object</label>
                       <label><input type="radio" name="propType" checked={!newAssertion.isObjectProperty} onChange={() => setNewAssertion(p => ({...p, isObjectProperty: false}))}/> Data</label>
                    </div>
                    <input value={newAssertion.propertyLabel} onChange={e => setNewAssertion(p => ({...p, propertyLabel: e.target.value}))} placeholder="Property" className="w-full p-1 border rounded"/>
                    <input value={newAssertion.targetLabel} onChange={e => setNewAssertion(p => ({...p, targetLabel: e.target.value}))} placeholder={newAssertion.isObjectProperty ? "Target Individual" : "Literal Value"} className="w-full p-1 border rounded"/>
                    <div className="flex justify-end gap-2">
                         <button onClick={() => setIsAddingAssertion(false)} className="px-2 py-1 bg-gray-200 rounded">Cancel</button>
                         <button onClick={handleAddAssertion} className="px-2 py-1 bg-purple-600 text-white rounded">Save</button>
                    </div>
                </div>
            )}
             <button onClick={() => setIsAddingAssertion(true)} className="text-xs text-gray-400 italic hover:text-purple-600 hover:underline">
                Add assertion...
             </button>
        </div>
      </Panel>
    </div>
  );
};

export default IndividualEditor;

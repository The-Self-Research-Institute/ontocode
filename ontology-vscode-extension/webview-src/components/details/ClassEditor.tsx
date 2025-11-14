import React, { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Panel, AnnotationsDisplay } from './common';
import ManchesterSyntaxEditor from './ManchesterSyntaxEditor';
import type { TreeNode, Axiom, ClassUsage, AxiomUsage } from '../../types';

type AxiomType = 'EquivalentTo' | 'SubClassOf' | 'DisjointWith';

const AxiomSection: React.FC<{
  title: string;
  axioms: Axiom[] | undefined;
  onAdd: (definition: string) => void;
  onDelete: (id: string) => void;
}> = ({ title, axioms, onAdd, onDelete }) => {
  const [isAdding, setIsAdding] = useState(false);

  const handleSave = (definition: string) => {
    onAdd(definition);
    setIsAdding(false);
  };

  return (
    <div className="border border-gray-200 rounded-sm">
      <div className="p-1 text-xs bg-gray-100 border-b flex justify-between items-center">
        <span>{title}</span>
        <button onClick={() => setIsAdding(true)} className="p-0.5 hover:bg-gray-300 rounded">
          <Plus size={14} />
        </button>
      </div>
      <div className="p-1.5 space-y-1">
        {axioms?.map(axiom => (
          <div key={axiom.id} className="group flex justify-between items-center bg-gray-50 p-1.5 rounded-sm text-xs font-mono">
            <span>{axiom.definition}</span>
            <button onClick={() => onDelete(axiom.id)} className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-red-200">
                <Trash2 size={12} className="text-red-600"/>
            </button>
          </div>
        ))}
        {isAdding && <ManchesterSyntaxEditor onSave={handleSave} onCancel={() => setIsAdding(false)} />}
        {!isAdding && (!axioms || axioms.length === 0) && (
             <button onClick={() => setIsAdding(true)} className="text-xs text-gray-400 italic hover:text-purple-600 hover:underline">
                Add...
             </button>
        )}
      </div>
    </div>
  );
};


const ClassEditor: React.FC<{
  item: TreeNode;
  onUpdate: (updatedItem: TreeNode) => void;
  onAddAnnotation: () => void;
  onDeleteAnnotation: (key: string) => void;
  activeTheme?: string; // This prop was in your file
}> = ({ item, onUpdate, onAddAnnotation, onDeleteAnnotation, activeTheme }) => {

  const handleAddAxiom = (type: AxiomType, definition: string) => {
    const newAxiom: Axiom = { id: `axiom-${Date.now()}`, type, definition };
    let updatedAxioms;
    
    switch (type) {
        case 'EquivalentTo':
            updatedAxioms = [...(item.equivalentClassesAxioms || []), newAxiom];
            onUpdate({ ...item, equivalentClassesAxioms: updatedAxioms });
            break;
        case 'SubClassOf':
            updatedAxioms = [...(item.subClassOfAxioms || []), newAxiom];
            onUpdate({ ...item, subClassOfAxioms: updatedAxioms });
            break;
        case 'DisjointWith':
            updatedAxioms = [...(item.disjointClassesAxioms || []), newAxiom];
            onUpdate({ ...item, disjointClassesAxioms: updatedAxioms });
            break;
    }
  };

  const handleDeleteAxiom = (type: AxiomType, id: string) => {
    switch (type) {
        case 'EquivalentTo':
            onUpdate({ ...item, equivalentClassesAxioms: item.equivalentClassesAxioms?.filter(a => a.id !== id) });
            break;
        case 'SubClassOf':
            onUpdate({ ...item, subClassOfAxioms: item.subClassOfAxioms?.filter(a => a.id !== id) });
            break;
        case 'DisjointWith':
            onUpdate({ ...item, disjointClassesAxioms: item.disjointClassesAxioms?.filter(a => a.id !== id) });
            break;
    }
  };


  return (
    <div className="flex flex-col gap-2 h-full">
      <Panel
        title={`Annotations: ${item.label}`}
        actions={<button onClick={onAddAnnotation} className="p-0.5 hover:bg-black/20 rounded-full"><Plus size={14} /></button>}
        themeColor={activeTheme}
      >
        <AnnotationsDisplay annotations={item.annotations} onDelete={onDeleteAnnotation} />
      </Panel>

      <Panel title={`Description: ${item.label}`} defaultOpen={true} themeColor={activeTheme}>
        <div className="space-y-1 p-1">
          <AxiomSection
            title="Equivalent To"
            axioms={item.equivalentClassesAxioms}
            onAdd={(def) => handleAddAxiom('EquivalentTo', def)}
            onDelete={(id) => handleDeleteAxiom('EquivalentTo', id)}
          />
          <AxiomSection
            title="SubClass Of"
            axioms={item.subClassOfAxioms}
            onAdd={(def) => handleAddAxiom('SubClassOf', def)}
            onDelete={(id) => handleDeleteAxiom('SubClassOf', id)}
          />
          <AxiomSection
            title="Disjoint With"
            axioms={item.disjointClassesAxioms}
            onAdd={(def) => handleAddAxiom('DisjointWith', def)}
            onDelete={(id) => handleDeleteAxiom('DisjointWith', id)}
          />
        </div>
      </Panel>
    </div>
  );
};

export default ClassEditor;
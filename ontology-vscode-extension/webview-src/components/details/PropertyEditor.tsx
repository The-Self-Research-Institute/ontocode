import React, { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Panel, AnnotationsDisplay } from './common';
import type { Property } from '../../types';

const MultiSelectItem: React.FC<{
  item: string;
  onDelete: (item: string) => void;
}> = ({ item, onDelete }) => (
    <div className="group flex justify-between items-center bg-gray-50 p-1.5 rounded-sm text-xs">
        <span>{item.split('#').pop() || item}</span>
        <button onClick={() => onDelete(item)} className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-red-200">
            <Trash2 size={12} className="text-red-600"/>
        </button>
    </div>
);


const MultiSelectSection: React.FC<{
    title: string;
    items: string[] | undefined;
    onAdd: (item: string) => void;
    onDelete: (item: string) => void;
}> = ({ title, items, onAdd, onDelete }) => {
    const [isAdding, setIsAdding] = useState(false);
    const [value, setValue] = useState('');

    const handleAdd = () => {
        if (value.trim()) {
            onAdd(value.trim());
            setValue('');
            setIsAdding(false);
        }
    };

    return (
         <div className="border border-gray-200 rounded-sm">
             <div className="p-1 text-xs bg-gray-100 border-b flex justify-between items-center">
                 <span>{title}</span>
                 <button onClick={() => setIsAdding(true)} className="p-0.5 hover:bg-gray-300 rounded"><Plus size={14}/></button>
             </div>
             <div className="p-1.5 space-y-1">
                 {items?.map(item => <MultiSelectItem key={item} item={item} onDelete={onDelete} />)}
                 {isAdding && (
                     <div className="flex gap-1">
                         <input
                           type="text"
                           value={value}
                           onChange={e => setValue(e.target.value)}
                           className="flex-grow w-full px-2 py-1 text-xs border border-gray-300 rounded-md"
                           placeholder={`Enter ${title.slice(0, -1)} IRI...`}
                           autoFocus
                           onKeyDown={e => e.key === 'Enter' && handleAdd()}
                         />
                         <button onClick={handleAdd} className="px-2 py-1 bg-purple-600 text-white rounded-md text-xs">Add</button>
                     </div>
                 )}
                 {!isAdding && (!items || items.length === 0) && (
                     <button onClick={() => setIsAdding(true)} className="text-xs text-gray-400 italic hover:text-purple-600 hover:underline">
                       Add...
                     </button>
                 )}
             </div>
         </div>
    );
};


const PropertyEditor: React.FC<{
  item: Property;
  onUpdate: (updatedItem: Property) => void;
  onAddAnnotation: () => void;
  onDeleteAnnotation: (key: string) => void;
  activeTheme?: string;
  onEditAnnotation: (key: string, value: string) => void;
}> = ({ item, onUpdate, onAddAnnotation, onDeleteAnnotation, activeTheme,onEditAnnotation }) => {
    const isObjectProperty = item.type === 'ObjectProperty';
    const characteristics = isObjectProperty ? ['Functional', 'Inverse functional', 'Transitive', 'Symmetric', 'Asymmetric', 'Reflexive', 'Irreflexive'] : ['Functional'];
    
    const handleCharacteristicChange = (char: string, checked: boolean) => {
        const currentChars = item.characteristics || [];
        const newChars = checked ? [...currentChars, char] : currentChars.filter(c => c !== char);
        onUpdate({ ...item, characteristics: newChars });
    };

    return (
        <div className="flex gap-2 h-full">
            <div className="w-1/3 flex flex-col gap-2">
                <Panel title={`Annotations: ${item.label}`} actions={<button onClick={onAddAnnotation} className="p-0.5 hover:bg-black/20 rounded-full"><Plus size={14}/></button>} themeColor={activeTheme}>
                    <AnnotationsDisplay annotations={item.annotations} onDelete={onDeleteAnnotation} onEdit={onEditAnnotation} />
                </Panel>
                <Panel title="Characteristics" themeColor={activeTheme}>
                   <div className="p-2 space-y-1.5 text-xs">
                     {characteristics.map(char => (
                        <label key={char} className="flex items-center gap-2 cursor-pointer">
                            <input
                                type="checkbox"
                                checked={item.characteristics?.includes(char) || false}
                                onChange={(e) => handleCharacteristicChange(char, e.target.checked)}
                            />
                            {char}
                         </label>
                     ))}
                   </div>
                </Panel>
            </div>
            <div className="flex-1 flex flex-col gap-2">
                 <Panel title={`Description: ${item.label}`} defaultOpen={true} themeColor={activeTheme}>
                     <div className="space-y-1 p-1">
                        <MultiSelectSection
                            title="SuperProperties"
                            items={item.superProperties}
                            onAdd={( newItem ) => onUpdate({ ...item, superProperties: [...(item.superProperties || []), newItem]})}
                            onDelete={(itemToDelete) => onUpdate({ ...item, superProperties: item.superProperties?.filter(i => i !== itemToDelete)})}
                        />
                         <MultiSelectSection
                            title="Domains"
                            items={item.domains}
                            onAdd={( newItem ) => onUpdate({ ...item, domains: [...(item.domains || []), newItem]})}
                            onDelete={(itemToDelete) => onUpdate({ ...item, domains: item.domains?.filter(i => i !== itemToDelete)})}
                        />
                         <MultiSelectSection
                            title="Ranges"
                            items={item.ranges}
                            onAdd={( newItem ) => onUpdate({ ...item, ranges: [...(item.ranges || []), newItem]})}
                            onDelete={(itemToDelete) => onUpdate({ ...item, ranges: item.ranges?.filter(i => i !== itemToDelete)})}
                        />
                     </div>
                 </Panel>
            </div>
        </div>
    );
};

export default PropertyEditor;

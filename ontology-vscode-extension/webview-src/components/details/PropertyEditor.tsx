import React, { useState } from 'react';
import { Plus, Trash2, CheckSquare, Square } from 'lucide-react';
import { Panel, AnnotationsDisplay } from './common';
import type { Property } from '../../types';

const MultiSelectItem: React.FC<{
  item: string;
  onDelete: (item: string) => void;
}> = ({ item, onDelete }) => (
    <div className="group flex justify-between items-center bg-white p-1.5 border-b border-gray-100 last:border-0 hover:bg-blue-50 transition-colors">
        <span className="text-sm text-gray-800">{item.split('#').pop() || item}</span>
        <button 
          onClick={() => onDelete(item)} 
          className="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-red-100 text-gray-400 hover:text-red-600 transition-all"
          title={`Remove ${item.split('#').pop()}`}
          aria-label={`Remove ${item.split('#').pop()}`}
        >
            <Trash2 size={14} />
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
         <div className="mb-4 last:mb-0">
             <div className="flex justify-between items-center mb-1">
                 <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">{title}</h4>
                 <button 
                   onClick={() => setIsAdding(true)} 
                   className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-purple-600 transition-colors"
                   title={`Add ${title.slice(0, -1)}`}
                   aria-label={`Add ${title.slice(0, -1)}`}
                 >
                    <Plus size={14}/>
                 </button>
             </div>
             <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm">
                 {items && items.length > 0 ? (
                    items.map(item => <MultiSelectItem key={item} item={item} onDelete={onDelete} />)
                 ) : (
                    !isAdding && (
                        <div className="p-2 text-xs text-gray-400 italic bg-gray-50">
                          No {title.toLowerCase()} defined
                        </div>
                    )
                 )}
                 {isAdding && (
                     <div className="p-2 bg-gray-50 border-t border-gray-200 flex gap-1">
                         <input
                           type="text"
                           value={value}
                           onChange={e => setValue(e.target.value)}
                           className="flex-grow w-full px-2 py-1 text-xs border border-gray-300 rounded-md focus:ring-1 focus:ring-purple-500 focus:border-purple-500 outline-none"
                           placeholder={`Enter ${title.slice(0, -1)} IRI...`}
                           autoFocus
                           onKeyDown={e => e.key === 'Enter' && handleAdd()}
                         />
                         <button onClick={handleAdd} className="px-3 py-1 bg-purple-600 text-white rounded-md text-xs hover:bg-purple-700">Add</button>
                         <button onClick={() => setIsAdding(false)} className="px-3 py-1 bg-gray-200 text-gray-700 rounded-md text-xs hover:bg-gray-300">Cancel</button>
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
  onDeleteAnnotation: (key: string) => void;
  activeTheme?: string;
}> = ({ item, onUpdate, onAddAnnotation, onDeleteAnnotation, activeTheme }) => {
    const isObjectProperty = item.type === 'ObjectProperty';
    const characteristics = isObjectProperty 
        ? ['Functional', 'Inverse functional', 'Transitive', 'Symmetric', 'Asymmetric', 'Reflexive', 'Irreflexive'] 
        : ['Functional'];
    
    const handleCharacteristicChange = (char: string, checked: boolean) => {
        const currentChars = item.characteristics || [];
        const newChars = checked ? [...currentChars, char] : currentChars.filter(c => c !== char);
        onUpdate({ ...item, characteristics: newChars });
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
                                {characteristics.map(char => (
                                    <label key={char} className="flex items-center gap-2 text-sm cursor-pointer hover:bg-gray-50 p-1 rounded">
                                        <input 
                                            type="checkbox" 
                                            checked={item.characteristics?.includes(char)} 
                                            onChange={e => handleCharacteristicChange(char, e.target.checked)}
                                            className="hidden"
                                        />
                                        {item.characteristics?.includes(char) ? (
                                            <CheckSquare size={16} className="text-purple-600" />
                                        ) : (
                                            <Square size={16} className="text-gray-300" />
                                        )}
                                        <span className={item.characteristics?.includes(char) ? 'text-gray-900 font-medium' : 'text-gray-500'}>{char}</span>
                                    </label>
                                ))}
                            </div>
                        </div>

                        <MultiSelectSection
                            title="Domains"
                            items={item.domains}
                            onAdd={domain => onUpdate({ ...item, domains: [...(item.domains || []), domain] })}
                            onDelete={domain => onUpdate({ ...item, domains: item.domains?.filter(d => d !== domain) })}
                        />

                        <MultiSelectSection
                            title="Ranges"
                            items={item.ranges}
                            onAdd={range => onUpdate({ ...item, ranges: [...(item.ranges || []), range] })}
                            onDelete={range => onUpdate({ ...item, ranges: item.ranges?.filter(r => r !== range) })}
                        />
                        
                        <MultiSelectSection
                            title="SubProperty Of"
                            items={item.superProperties}
                            onAdd={prop => onUpdate({ ...item, superProperties: [...(item.superProperties || []), prop] })}
                            onDelete={prop => onUpdate({ ...item, superProperties: item.superProperties?.filter(p => p !== prop) })}
                        />

                        <MultiSelectSection
                            title="Disjoint With"
                            items={item.disjointProperties}
                            onAdd={prop => onUpdate({ ...item, disjointProperties: [...(item.disjointProperties || []), prop] })}
                            onDelete={prop => onUpdate({ ...item, disjointProperties: item.disjointProperties?.filter(p => p !== prop) })}
                        />
                        
                        {isObjectProperty && (
                            <MultiSelectSection
                                title="Inverse Of"
                                items={item.inverseProperties}
                                onAdd={prop => onUpdate({ ...item, inverseProperties: [...(item.inverseProperties || []), prop] })}
                                onDelete={prop => onUpdate({ ...item, inverseProperties: item.inverseProperties?.filter(p => p !== prop) })}
                            />
                        )}
                    </div>
                </Panel>
            </div>
        </div>
    );
};

export default PropertyEditor;
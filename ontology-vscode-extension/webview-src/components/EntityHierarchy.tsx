import React from 'react';
import { ChevronRight, ChevronDown, PlusCircle, Trash2, Search, Package, GitBranch, Database, Tag, User, Type, Binary } from "lucide-react";
import type { SelectableItem, TreeNode } from '../types';

interface EntityHierarchyProps {
  entitiesTab: string;
  filteredData: SelectableItem[];
  selectedItem: SelectableItem | null;
  expandedNodes: string[];
  searchQuery: string;
  onSearchQueryChange: (query: string) => void;
  onSelectItem: (item: SelectableItem) => void;
  onToggleNode: (nodeId: string) => void;
  onAddItem: (type: 'subclass' | 'sibling' | 'individual') => void;
  onDeleteItem: () => void;
}

const EntityHierarchy: React.FC<EntityHierarchyProps> = ({
  entitiesTab,
  filteredData,
  selectedItem,
  expandedNodes,
  searchQuery,
  onSearchQueryChange,
  onSelectItem,
  onToggleNode,
  onAddItem,
  onDeleteItem,
}) => {

  const renderItem = (item: SelectableItem, level = 0): React.JSX.Element => {
    const isSelected = selectedItem?.id === item.id;
    const isTreeNode = entitiesTab === 'Classes';
    const hasChildren = 'hasChildren' in item && item.hasChildren;
    const isExpanded = isTreeNode && hasChildren && expandedNodes.includes(item.id);

    let Icon, iconClasses;
    let itemType = entitiesTab;
    
    switch (itemType) {
        case 'Classes': Icon = Package; iconClasses = 'bg-amber-500 border-amber-600'; break;
        case 'ObjectProperties': Icon = GitBranch; iconClasses = 'bg-blue-500 border-blue-600'; break;
        case 'DataProperties': Icon = Database; iconClasses = 'bg-green-500 border-green-600'; break;
        case 'AnnotationProperties': Icon = Tag; iconClasses = 'bg-orange-500 border-orange-600'; break;
        case 'Individuals': Icon = User; iconClasses = 'bg-purple-500 border-purple-600'; break;
        case 'Datatypes': Icon = Type; iconClasses = 'bg-red-500 border-red-600'; break;
        default: Icon = Package; iconClasses = 'bg-gray-500 border-gray-600';
    }

    return (
      <div key={item.id}>
        <div 
          data-class-id={item.id}
          className={`flex items-center gap-2 py-1 px-2 cursor-pointer border-b border-gray-100 ${
            isSelected ? "bg-blue-100" : "hover:bg-gray-50"
          }`}
          style={{ paddingLeft: `${level * 16 + 8}px` }}
          onClick={() => onSelectItem(item)}
        >
          {/* Expander Arrow */}
          {isTreeNode ? (
            <button 
              className="w-4 h-4 flex items-center justify-center flex-shrink-0 hover:bg-gray-200 rounded" 
              onClick={(e) => { 
                e.stopPropagation(); 
                if (hasChildren) onToggleNode(item.id);
              }}
              disabled={!hasChildren} 
            >
              {hasChildren && (
                isExpanded ? <ChevronDown size={14} className="text-gray-600" /> : <ChevronRight size={14} className="text-gray-600" />
              )}
            </button>
          ) : (
            <span className="w-4" />
          )}
          
          {/* Entity Icon */}
          <div 
            title={itemType.slice(0, -1)} 
            className={`w-4 h-4 rounded border ${iconClasses} flex-shrink-0 flex items-center justify-center`}
          >
            <Icon size={10} className="text-white" strokeWidth={2} />
          </div>
          
          {/* Label */}
          <span className={`text-xs truncate text-gray-900 ${
            isSelected ? "font-semibold" : "font-normal"
          }`}>
            {item.label}
          </span>
        </div>
        
        {/* Render Children Recursively */}
        {isTreeNode && isExpanded && 'children' in item && (item as TreeNode).children?.map((child: TreeNode) => renderItem(child, level + 1))}
      </div>
    );
  };
  
  const entitiesTabsConfig = {
      Classes: { label: "Classes", icon: Package },
      ObjectProperties: { label: "Object properties", icon: GitBranch },
      DataProperties: { label: "Data properties", icon: Database },
      AnnotationProperties: { label: "Annotation properties", icon: Tag },
      Datatypes: { label: "Datatypes", icon: Type },
      Individuals: { label: "Individuals", icon: User },
  };
  
  const currentTabConfig = entitiesTabsConfig[entitiesTab as keyof typeof entitiesTabsConfig];

  return (
    <aside className="w-80 bg-white border-r border-gray-300 flex flex-col h-full">
      {/* Header with CUD buttons */}
      <div className="text-xs font-normal text-gray-700 px-2 py-2 flex items-center justify-between border-b border-gray-300 bg-gradient-to-b from-gray-50 to-white">
        <span>{currentTabConfig?.label} hierarchy</span>
         <div className="flex items-center gap-1">
              {entitiesTab === 'Classes' && (
                 <>
                 <button 
                    title="Add subclass" 
                    aria-label="Add subclass"
                    disabled={!selectedItem} 
                    onClick={() => onAddItem('subclass')}
                    className="p-0.5 hover:bg-gray-200 rounded disabled:opacity-30"
                 >
                      <PlusCircle size={14} className="text-gray-600" />
                 </button>
                 <button 
                    title="Add sibling class" 
                    aria-label="Add sibling class"
                    disabled={!selectedItem} 
                    onClick={() => onAddItem('sibling')}
                    className="p-0.5 hover:bg-gray-200 rounded disabled:opacity-30"
                 >
                      <Binary size={14} className="text-gray-600" />
                 </button>
                 </>
              )}
              {entitiesTab === 'Individuals' && (
                   <button 
                      title="Add individual" 
                      aria-label="Add individual"
                      onClick={() => onAddItem('individual')}
                      className="p-0.5 hover:bg-gray-200 rounded"
                   >
                      <PlusCircle size={14} className="text-gray-600" />
                 </button>
              )}
             <button 
                title="Delete selected entity" 
                aria-label="Delete selected entity"
                disabled={!selectedItem} 
                onClick={onDeleteItem}
                className="p-0.5 hover:bg-red-100 rounded disabled:opacity-30"
             >
                <Trash2 size={14} className="text-gray-600" />
             </button>
        </div>
      </div>
      
      {/* Search Bar */}
      <div className="px-2 py-2 border-b border-gray-200 flex-shrink-0">
          <div className="relative">
              <Search size={13} className="absolute left-2 top-1/2 -translate-y-1/2 text-gray-400" />
              <input 
                type="text" 
                placeholder={`Search ${entitiesTab.toLowerCase()}...`} 
                value={searchQuery} 
                onChange={e => onSearchQueryChange(e.target.value)}
                className="w-full pl-7 pr-2 py-1 text-xs border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500 bg-white placeholder:text-gray-400"
              />
          </div>
      </div>
      
      {/* Tree/List View */}
      <div className="flex-1 overflow-y-auto bg-white">
        {filteredData.length > 0 ? filteredData.map(node => renderItem(node)) : 
          (searchQuery ? (
             <div className="p-4 text-center text-gray-400 text-xs">No items found for "{searchQuery}".</div>
          ) : (entitiesTab === 'Individuals' && !searchQuery) ? (
             <div className="p-4 text-center text-gray-400">
               <p className="mb-2 text-xs">No individuals created yet.</p>
               <button onClick={() => onAddItem('individual')} className="text-xs text-blue-600 hover:underline">Create a new Individual</button>
             </div>
          ) : <div className="p-4 text-center text-gray-400 text-xs">No items found.</div>)
        }
      </div>
    </aside>
  );
};

export default EntityHierarchy;
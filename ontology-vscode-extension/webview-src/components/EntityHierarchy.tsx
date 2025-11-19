import React, { useState, useEffect, useRef } from 'react';
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
  onMakeSiblingsDisjoint?: () => void;
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
  onMakeSiblingsDisjoint,
}) => {
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; item: SelectableItem } | null>(null);
  const contextMenuRef = useRef<HTMLDivElement>(null);

  // Close context menu when clicking outside
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (contextMenuRef.current && !contextMenuRef.current.contains(event.target as Node)) {
        setContextMenu(null);
      }
    };

    if (contextMenu) {
      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }
  }, [contextMenu]);

  const handleContextMenu = (e: React.MouseEvent, item: SelectableItem) => {
    e.preventDefault();
    e.stopPropagation();
    setContextMenu({ x: e.clientX, y: e.clientY, item });
  };

  const renderItem = (item: SelectableItem, level = 0): React.JSX.Element => {
    const isSelected = selectedItem?.id === item.id;
    // An item is a "TreeNode" if it's in the Classes or ObjectProperties tab.
    // We check 'hasChildren' to know if it's expandable.
    const isTreeNode = entitiesTab === 'Classes' || entitiesTab === 'ObjectProperties';
    const hasChildren = 'hasChildren' in item && item.hasChildren;
    const isExpanded = isTreeNode && hasChildren && expandedNodes.includes(item.id);

    let Icon, iconClasses;
    let itemType = entitiesTab;
    
    // Determine icon based on the tab
    switch (itemType) {
        case 'Classes': Icon = Package; iconClasses = 'bg-amber-400 border-amber-600'; break; //
        case 'ObjectProperties': Icon = GitBranch; iconClasses = 'bg-blue-400 border-blue-600'; break; //
        case 'DataProperties': Icon = Database; iconClasses = 'bg-green-400 border-green-600'; break; //
        case 'AnnotationProperties': Icon = Tag; iconClasses = 'bg-orange-400 border-orange-600'; break; //
        case 'Individuals': Icon = User; iconClasses = 'bg-purple-400 border-purple-600'; break; //
        case 'Datatypes': Icon = Type; iconClasses = 'bg-red-400 border-red-600'; break; //
        default: Icon = Package; iconClasses = 'bg-gray-400 border-gray-600';
    }

    return (
      <div key={item.id}>
        <div 
          data-class-id={item.id}
          className={`flex items-center px-2 py-0.5 rounded cursor-pointer ${isSelected ? "bg-blue-200" : "hover:bg-slate-100"}`}
          style={{ paddingLeft: `${level * 16 + 4}px` }}
          onClick={() => onSelectItem(item)}
          onContextMenu={(e) => handleContextMenu(e, item)}
        >
          {/* Expander Arrow */}
          {isTreeNode ? (
            <button 
              className="p-0.5 mr-1" 
              onClick={(e) => { 
                e.stopPropagation(); 
                if (hasChildren) onToggleNode(item.id); //
              }}
              // Disable button if it has no children
              disabled={!hasChildren} 
            >
              {!hasChildren ? 
                <span className="w-5" /> : 
                (isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />)}
            </button>
          ) : (
            // Non-tree items get a spacer to align text
            <span className="w-5 mr-1" /> 
          )}
          
          {/* Entity Icon */}
           <div title={itemType.slice(0, -1)} className={`w-3.5 h-3.5 rounded-sm border ${iconClasses} mr-2 flex-shrink-0 flex items-center justify-center`}>
              <Icon size={10} className="text-white"/>
           </div>
          
          {/* Label */}
          <span className={`text-xs select-none text-black ${isSelected ? "font-semibold" : ""}`}>{item.label}</span>
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
    <aside className="w-80 bg-white border-r border-gray-200 flex flex-col h-full">
      {/* Header with CUD buttons */}
      <div className={`text-xs font-semibold p-1.5 flex items-center justify-between border-b`}>
        <span>{currentTabConfig?.label} hierarchy</span>
         <div className="flex items-center gap-1">
              {entitiesTab === 'Classes' && (
                 <>
                 <button 
                    title="Add subclass" 
                    aria-label="Add subclass"
                    disabled={!selectedItem} 
                    onClick={() => onAddItem('subclass')} //
                    className="p-0.5 hover:bg-black/20 rounded disabled:opacity-30"
                 >
                      <PlusCircle size={14} />
                 </button>
                 <button 
                    title="Add sibling class" 
                    aria-label="Add sibling class"
                    disabled={!selectedItem} 
                    onClick={() => onAddItem('sibling')} //
                    className="p-0.5 hover:bg-black/20 rounded disabled:opacity-30"
                 >
                      <Binary size={14} />
                 </button>
                 </>
              )}
              {entitiesTab === 'ObjectProperties' && (
                 <>
                 <button 
                    title="Add sub property" 
                    aria-label="Add sub property"
                    disabled={!selectedItem} 
                    onClick={() => onAddItem('subclass')} // Reusing 'subclass' type for sub-property
                    className="p-0.5 hover:bg-black/20 rounded disabled:opacity-30"
                 >
                      <PlusCircle size={14} />
                 </button>
                 <button 
                    title="Add sibling property" 
                    aria-label="Add sibling property"
                    disabled={!selectedItem} 
                    onClick={() => onAddItem('sibling')} // Reusing 'sibling' type for sibling property
                    className="p-0.5 hover:bg-black/20 rounded disabled:opacity-30"
                 >
                      <Binary size={14} />
                 </button>
                 </>
              )}
              {entitiesTab === 'Individuals' && (
                   <button 
                      title="Add individual" 
                      aria-label="Add individual"
                      onClick={() => onAddItem('individual')} //
                      className="p-0.5 hover:bg-black/20 rounded"
                   >
                      <PlusCircle size={14} />
                 </button>
              )}
             <button 
                title="Delete selected entity" 
                aria-label="Delete selected entity"
                disabled={!selectedItem} 
                onClick={onDeleteItem} //
                className="p-0.5 hover:bg-black/20 rounded disabled:opacity-30"
             >
                <Trash2 size={14} />
             </button>
        </div>
      </div>
      
      {/* Search Bar */}
      <div className="p-2 border-b border-gray-200 flex-shrink-0">
          <div className="relative">
              <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
              <input type="text" placeholder={`Search ${entitiesTab.toLowerCase()}...`} value={searchQuery} onChange={e => onSearchQueryChange(e.target.value)} //
                  className="w-full pl-8 pr-3 py-1.5 border border-gray-300 rounded-md focus:ring-1 focus:ring-purple-500 text-sm bg-white" />
          </div>
      </div>
      
      {/* Tree/List View */}
      <div className="flex-1 overflow-y-auto p-1">
        {filteredData.length > 0 ? filteredData.map(node => renderItem(node)) : 
          (searchQuery ? (
             <div className="p-4 text-center text-gray-400">No items found for "{searchQuery}".</div>
          ) : (entitiesTab === 'Individuals' && !searchQuery) ? (
             <div className="p-4 text-center text-gray-400">
               <p className="mb-2">No individuals created yet.</p>
               <button onClick={() => onAddItem('individual')} className="text-sm text-purple-600 hover:underline">Create a new Individual</button>
             </div>
          ) : <div className="p-4 text-center text-gray-400">No items found.</div>)
        }
      </div>

      {/* Context Menu */}
      {contextMenu && entitiesTab === 'Classes' && (
        <div
          ref={contextMenuRef}
          className="fixed bg-white border border-gray-300 rounded shadow-lg py-1 z-50"
          style={{ left: contextMenu.x, top: contextMenu.y }}
        >
          <button
            className="w-full px-4 py-2 text-left text-sm hover:bg-gray-100 flex items-center gap-2"
            onClick={() => {
              onSelectItem(contextMenu.item);
              if (onMakeSiblingsDisjoint) {
                onMakeSiblingsDisjoint();
              }
              setContextMenu(null);
            }}
          >
            <Binary size={14} />
            Make Siblings Disjoint
          </button>
        </div>
      )}
    </aside>
  );
};

export default EntityHierarchy;
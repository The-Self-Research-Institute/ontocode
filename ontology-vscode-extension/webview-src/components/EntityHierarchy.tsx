import React, { useState, useEffect, useRef } from 'react';
import { ChevronRight, ChevronDown, PlusCircle, Trash2, Search, Package, GitBranch, Database, Tag, User, Type, Binary, MousePointer2, Eye, Settings, Edit3, Check } from "lucide-react";
import type { SelectableItem, TreeNode } from '../types';
import { useCollaboration } from '../contexts/CollaborationContext';
import InlineRenameInput from './InlineRenameInput';

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
  onMoveClass?: (classId: string, newParentId: string) => void;
  onOpenPreferences?: () => void;
  onRenameItem?: (itemId: string, newLabel: string) => void;
  hideToolbarActions?: boolean; // Hide add/delete buttons
  selectedProperties?: string[]; // For multi-select mode (HasKey dialog)
  multiSelectMode?: boolean; // Enable checkbox multi-select
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
  onMoveClass,
  onOpenPreferences,
  onRenameItem,
  hideToolbarActions = false,
  selectedProperties = [],
  multiSelectMode = false,
}) => {
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; item: SelectableItem } | null>(null);
  const [viewMode, setViewMode] = useState<'asserted' | 'inferred'>('asserted');
  const [draggedItem, setDraggedItem] = useState<SelectableItem | null>(null);
  const [renamingItemId, setRenamingItemId] = useState<string | null>(null);
  const contextMenuRef = useRef<HTMLDivElement>(null);
  const { state: collaborationState } = useCollaboration();
  
  // Get active users as array and filter by current project
  const allUsers = Array.from(collaborationState.activeUsers.values());
  const activeUsers = allUsers.filter(user => 
    !collaborationState.currentProjectId || user.projectId === collaborationState.currentProjectId
  );

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

  // Keyboard shortcuts: 'a' for asserted, 'i' for inferred
  useEffect(() => {
    const handleKeyPress = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;

      if (e.key === 'a' || e.key === 'A') {
        setViewMode('asserted');
      } else if (e.key === 'i' || e.key === 'I') {
        setViewMode('inferred');
      }
    };

    document.addEventListener('keydown', handleKeyPress);
    return () => document.removeEventListener('keydown', handleKeyPress);
  }, []);

  // Listen for F2 rename trigger from Dashboard
  useEffect(() => {
    const handleRenameEvent = (e: Event) => {
      const customEvent = e as CustomEvent;
      if (customEvent.detail?.itemId) {
        setRenamingItemId(customEvent.detail.itemId);
      }
    };

    window.addEventListener('triggerRename', handleRenameEvent);
    return () => window.removeEventListener('triggerRename', handleRenameEvent);
  }, []);

  const handleContextMenu = (e: React.MouseEvent, item: SelectableItem) => {
    e.preventDefault();
    e.stopPropagation();
    setContextMenu({ x: e.clientX, y: e.clientY, item });
  };

  // Drag and Drop handlers for class hierarchy reorganization
  const handleDragStart = (e: React.DragEvent, item: SelectableItem) => {
    // Only allow drag in Classes tab and asserted mode
    if (entitiesTab !== 'Classes' || viewMode !== 'asserted') {
      e.preventDefault();
      return;
    }
    setDraggedItem(item);
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', item.id);
  };

  const handleDragOver = (e: React.DragEvent) => {
    if (draggedItem && viewMode === 'asserted') {
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
    }
  };

  const handleDrop = (e: React.DragEvent, targetItem: SelectableItem) => {
    e.preventDefault();
    e.stopPropagation();
    
    if (draggedItem && draggedItem.id !== targetItem.id && onMoveClass) {
      // Call the move handler to update backend
      onMoveClass(draggedItem.id, targetItem.id);
    }
    setDraggedItem(null);
  };

  const handleDragEnd = () => {
    setDraggedItem(null);
  };

  const handleRenameConfirm = (newLabel: string) => {
    if (renamingItemId && onRenameItem) {
      onRenameItem(renamingItemId, newLabel);
    }
    setRenamingItemId(null);
  };

  const handleRenameCancel = () => {
    setRenamingItemId(null);
  };

  const handleDoubleClick = (e: React.MouseEvent, item: SelectableItem) => {
    e.stopPropagation();
    setRenamingItemId(item.id);
  };

  const renderItem = (item: SelectableItem, level = 0): React.JSX.Element => {
    const isSelected = selectedItem?.id === item.id;
    // An item is a "TreeNode" if it's in the Classes, ObjectProperties, or DataProperties tab.
    // We check 'hasChildren' to know if it's expandable.
    const isTreeNode = entitiesTab === 'Classes' || entitiesTab === 'ObjectProperties' || entitiesTab === 'DataProperties';
    const hasChildren = 'hasChildren' in item && item.hasChildren;
    const isExpanded = isTreeNode && hasChildren && expandedNodes.includes(item.id);
    const isDragging = draggedItem?.id === item.id;
    const canDrag = entitiesTab === 'Classes' && viewMode === 'asserted';
    
    // Check if class is defined (has equivalent classes) vs primitive
    const isDefined = entitiesTab === 'Classes' && 'equivalentClassesAxioms' in item && 
                      (item as TreeNode).equivalentClassesAxioms && 
                      (item as TreeNode).equivalentClassesAxioms!.length > 0;
   
    // Find users viewing this node
    const usersViewingNode = activeUsers.filter(user => 
      user.cursorPosition === item.id || user.selectedNodes?.includes(item.id)
    );
    let Icon, iconClasses;
    let itemType = entitiesTab;
    
    // Determine icon based on the tab - add equivalence lines for defined classes
    switch (itemType) {
        case 'Classes': 
          Icon = Package; 
          // Defined classes get yellow icon with equivalence symbol (≡)
          iconClasses = isDefined 
            ? 'bg-amber-300 border-amber-600 relative' 
            : 'bg-amber-400 border-amber-600'; 
          break;
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
          draggable={canDrag}
          onDragStart={(e) => handleDragStart(e, item)}
          onDragOver={handleDragOver}
          onDrop={(e) => handleDrop(e, item)}
          onDragEnd={handleDragEnd}
          className={`flex items-center px-2 py-0.5 rounded cursor-pointer transition-all ${
            isSelected ? "selected" : "hover-overlay"
          } ${isDragging ? "opacity-50" : ""}`}
          style={{ paddingLeft: `${level * 16 + 4}px` }}
          onClick={() => {
            onSelectItem(item);
            // Broadcast cursor position to other users
            if (window.vscode) {
              window.vscode.postMessage({
                type: 'cursorMoved',
                nodeId: item.id,
                nodeName: item.label
              });
            }
          }}
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
          
          {/* Checkbox for multi-select mode (HasKey dialog) */}
          {multiSelectMode && (
            <div 
              className={`w-4 h-4 mr-2 rounded border flex items-center justify-center flex-shrink-0 ${
                selectedProperties.includes(item.id)
                  ? 'bg-purple-600 border-purple-600'
                  : 'border-gray-300 bg-white'
              }`}
              onClick={(e) => {
                e.stopPropagation();
                onSelectItem(item);
              }}
            >
              {selectedProperties.includes(item.id) && (
                <Check size={12} className="text-white" />
              )}
            </div>
          )}
          
          {/* Entity Icon with defined class indicator */}
           <div 
             title={isDefined ? 'Defined class (has equivalent classes)' : itemType.slice(0, -1)} 
             className={`w-3.5 h-3.5 rounded-sm border ${iconClasses} mr-2 flex-shrink-0 flex items-center justify-center`}
           >
              {isDefined ? (
                // Show ≡ symbol for defined classes (three horizontal lines)
                <div className="text-white text-[8px] font-bold leading-none">≡</div>
              ) : (
                <Icon size={10} className="text-white"/>
              )}
           </div>
          
          {/* Label - show input if renaming */}
          {renamingItemId === item.id ? (
            <InlineRenameInput
              initialValue={item.label}
              onConfirm={handleRenameConfirm}
              onCancel={handleRenameCancel}
            />
          ) : (
            <span
              className={`text-xs select-none ${isSelected ? "font-semibold" : ""}`}
              style={{ color: 'var(--text-primary)' }}
              onDoubleClick={(e) => handleDoubleClick(e, item)}
            >
              {item.label}
            </span>
          )}
          
          {/* Active User Cursors */}
          {usersViewingNode.length > 0 && (
            <div className="flex items-center gap-1 ml-2">
              {usersViewingNode.map(user => (
                <div 
                  key={user.userId}
                  className="flex items-center gap-1 px-1.5 py-0.5 rounded-full text-[10px] font-medium"
                  style={{ 
                    backgroundColor: `${user.color}20`,
                    border: `1px solid ${user.color}`,
                    color: user.color
                  }}
                  title={`${user.username} is viewing this item`}
                >
                  <MousePointer2 size={10} />
                  <span>{user.username}</span>
                </div>
              ))}
            </div>
          )}
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

  const isPropertyTab = entitiesTab === 'ObjectProperties' || entitiesTab === 'DataProperties' || entitiesTab === 'AnnotationProperties';
  const sidebarWidthClass = isPropertyTab ? 'w-[26rem] min-w-[24rem]' : 'w-80';
  const currentLabel = currentTabConfig?.label || entitiesTab;

  return (
    <aside className={`${hideToolbarActions ? 'w-full' : sidebarWidthClass} ${hideToolbarActions ? '' : 'border-r'} flex flex-col h-full`} style={{ backgroundColor: 'var(--color-surface)', borderColor: 'var(--color-border)' }}>
      {/* Header with CUD buttons and Asserted/Inferred toggle */}
      {!hideToolbarActions && (
      <div className="text-xs font-semibold p-1 flex items-center justify-center gap-1 flex-wrap border-b text-center" style={{ borderColor: 'var(--color-border)' }}>
        <span style={{ color: 'var(--color-text-secondary)' }}>{currentLabel} hierarchy</span>
        <div className="flex items-center gap-0.5">
          {/* Asserted/Inferred mode toggle for Classes, Properties, and Datatypes */}
          {(entitiesTab === 'Classes' || entitiesTab === 'ObjectProperties' || entitiesTab === 'DataProperties' || entitiesTab === 'Datatypes') && (
            <div className="flex items-center gap-0.5 bg-gray-100 rounded px-1 py-0.5">
              <button
                onClick={() => setViewMode('asserted')}
                className={`px-2 py-0.5 text-[10px] rounded transition-colors whitespace-nowrap ${
                  viewMode === 'asserted' 
                    ? 'bg-purple-600 text-white font-semibold' 
                    : 'text-gray-600 hover:bg-gray-200'
                }`}
                title="Asserted hierarchy (a)"
              >
                Asserted
              </button>
              <button
                onClick={() => setViewMode('inferred')}
                className={`px-2 py-0.5 text-[10px] rounded transition-colors whitespace-nowrap ${
                  viewMode === 'inferred' 
                    ? 'bg-yellow-500 text-white font-semibold' 
                    : 'text-gray-600 hover:bg-gray-200'
                }`}
                title="Inferred hierarchy (i)"
              >
                <Eye size={10} className="inline mr-1" />
                Inferred
              </button>
            </div>
          )}
          
          {!hideToolbarActions && (
          <div className="flex items-center gap-0.5">
              {entitiesTab === 'Classes' && viewMode === 'asserted' && (
                 <>
                 <button 
                    title="Add subclass (Ctrl+E)" 
                    aria-label="Add subclass"
                    disabled={!selectedItem} 
                    onClick={() => onAddItem('subclass')} //
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600 disabled:text-gray-400 disabled:opacity-80"
                 >
                      <PlusCircle size={14} />
                 </button>
                 <button 
                    title="Add sibling class (Ctrl+Shift+E)" 
                    aria-label="Add sibling class"
                    disabled={!selectedItem} 
                    onClick={() => onAddItem('sibling')} //
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600 disabled:text-gray-400 disabled:opacity-80"
                 >
                      <Binary size={14} />
                 </button>
                 </>
              )}
              {entitiesTab === 'ObjectProperties' && viewMode === 'asserted' && (
                 <>
                 <button 
                    title="Add sub property" 
                    aria-label="Add sub property"
                    disabled={!selectedItem} 
                    onClick={() => onAddItem('subclass')} // Reusing 'subclass' type for sub-property
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600 disabled:text-gray-400 disabled:opacity-80"
                 >
                      <PlusCircle size={14} />
                 </button>
                 <button 
                    title="Add sibling property" 
                    aria-label="Add sibling property"
                    disabled={!selectedItem} 
                    onClick={() => onAddItem('sibling')} // Reusing 'sibling' type for sibling property
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600 disabled:text-gray-400 disabled:opacity-80"
                 >
                      <Binary size={14} />
                 </button>
                 </>
              )}
              {entitiesTab === 'DataProperties' && viewMode === 'asserted' && (
                 <>
                 <button 
                    title="Add sub property" 
                    aria-label="Add sub property"
                    disabled={!selectedItem} 
                    onClick={() => onAddItem('subclass')} //
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600 disabled:text-gray-400 disabled:opacity-80"
                 >
                      <PlusCircle size={14} />
                 </button>
                 <button 
                    title="Add sibling property" 
                    aria-label="Add sibling property"
                    disabled={!selectedItem} 
                    onClick={() => onAddItem('sibling')} //
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600 disabled:text-gray-400 disabled:opacity-80"
                 >
                      <Binary size={14} />
                 </button>
                 </>
              )}
              {entitiesTab === 'Datatypes' && viewMode === 'asserted' && (
                 <>
                 <button
                    title="Add datatype"
                    aria-label="Add datatype"
                    onClick={() => onAddItem('subclass')} // Reusing 'subclass' type for datatype creation
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600"
                 >
                      <PlusCircle size={14} />
                 </button>
                 </>
              )}
              {entitiesTab === 'Individuals' && (
                   <button
                      title="Add individual"
                      aria-label="Add individual"
                      onClick={() => onAddItem('individual')} //
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600"
                 >
                      <PlusCircle size={14} />
                 </button>
              )}
             <button
                title="Delete selected entity"
                aria-label="Delete selected entity"
                disabled={!selectedItem || viewMode === 'inferred'}
                onClick={() => onDeleteItem()}
                className="p-0.5 rounded text-gray-600 hover:text-red-600 disabled:text-gray-400 disabled:opacity-80"
             >
                <Trash2 size={14} />
             </button>
             <button
                title="Entity creation preferences"
                aria-label="Entity creation preferences"
                onClick={() => onOpenPreferences?.()}
                className="p-0.5 rounded text-gray-600 hover:text-gray-700 hover:bg-gray-200"
             >
                <Settings size={14} />
             </button>
        </div>
          )}
        </div>
      </div>
      )}
      
      {/* Search Bar */}
      <div className="p-2 border-b flex-shrink-0" style={{ borderColor: 'var(--color-border)' }}>
          <div className="relative">
              <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: 'var(--color-text-secondary)' }} />
              <input type="text" placeholder={`Search ${currentLabel.toLowerCase()}...`} value={searchQuery} onChange={e => onSearchQueryChange(e.target.value)} //
                  className="w-full pl-8 pr-3 py-1.5 border rounded-md focus:ring-1 text-sm"
                  style={{
                    borderColor: 'var(--color-border)',
                    backgroundColor: 'var(--color-surface)',
                    color: 'var(--color-text)',
                    '--tw-ring-color': 'var(--color-primary)'
                  } as React.CSSProperties} />
          </div>
          
          {/* Tips banner */}
          {!hideToolbarActions && entitiesTab === 'Classes' && viewMode === 'asserted' && (
            <div className="mt-2 text-[10px] text-gray-500 bg-blue-50 border border-blue-200 rounded p-2">
              💡 <strong>Tip:</strong> Drag &amp; drop to reorganize | <kbd className="px-1 py-0.5 bg-gray-200 rounded">A</kbd> Asserted | <kbd className="px-1 py-0.5 bg-gray-200 rounded">I</kbd> Inferred | <kbd className="px-1 py-0.5 bg-gray-200 rounded">Ctrl+E</kbd> Add subclass
            </div>
          )}
      </div>
      
      {/* Tree/List View */}
      <div className="flex-1 overflow-y-auto p-1">
        {filteredData && filteredData.length > 0 ? filteredData.map(node => renderItem(node)) : 
          (searchQuery ? (
             <div className="p-4 text-center text-gray-600">No items found for "{searchQuery}".</div>
          ) : (entitiesTab === 'Individuals' && !searchQuery) ? (
             <div className="p-4 text-center text-gray-600">
               <p className="mb-2">No individuals created yet.</p>
               <button onClick={() => onAddItem('individual')} className="text-sm text-purple-600 hover:underline">Create a new Individual</button>
             </div>
          ) : (entitiesTab === 'AnnotationProperties' && !searchQuery) ? (
             <div className="p-4 text-center text-gray-600">
               <p className="mb-2">No annotation properties created yet.</p>
               <button onClick={() => onAddItem('subclass')} className="text-sm text-purple-600 hover:underline">Create a new Annotation Property</button>
             </div>
          ) : <div className="p-4 text-center text-gray-600">No items found.</div>)
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
              setRenamingItemId(contextMenu.item.id);
              setContextMenu(null);
            }}
          >
            <Edit3 size={14} />
            Rename
          </button>
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

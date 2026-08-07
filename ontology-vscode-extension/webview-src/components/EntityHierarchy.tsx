import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import {
  ChevronRight,
  ChevronDown,
  PlusCircle,
  Trash2,
  Search,
  Package,
  GitBranch,
  Database,
  Tag,
  User,
  Type,
  CornerDownRight,
  Rows3,
  MousePointer2,
  Eye,
  Settings,
  Edit3,
  Check,
  Loader2,
  Hash,
} from "lucide-react";
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
  searchOptions?: {
    useRegex: boolean;
    searchAnnotations: boolean;
    hideDeprecated: boolean;
    hideBuiltins: boolean;
  };
  onSearchOptionsChange?: (next: {
    useRegex: boolean;
    searchAnnotations: boolean;
    hideDeprecated: boolean;
    hideBuiltins: boolean;
  }) => void;
  onSelectItem: (item: SelectableItem) => void;
  onToggleNode: (nodeId: string) => void;
  onAddItem: (type: 'subclass' | 'sibling' | 'individual') => void;
  onDeleteItem: () => void;
  onMakeSiblingsDisjoint?: () => void;
  onMoveClass?: (classId: string, newParentId: string) => void;
  onOpenPreferences?: () => void;
  onRenameItem?: (itemId: string, newLabel: string) => void;
  onChangeEntityIri?: (item: SelectableItem) => void;
  onQuickSetParent?: (item: SelectableItem) => void;
  onQuickAddNote?: (item: SelectableItem) => void;
  viewMode?: 'asserted' | 'inferred';
  onViewModeChange?: (mode: 'asserted' | 'inferred') => void;
  displayMode?: 'label' | 'id' | 'annotation' | 'custom';
  onDisplayModeChange?: (mode: 'label' | 'id' | 'annotation' | 'custom') => void;
  displayAnnotationPropIri?: string;
  onDisplayAnnotationPropChange?: (iri: string) => void;
  customTemplate?: string;
  onCustomTemplateChange?: (tpl: string) => void;
  annotationProperties?: Array<{ id: string; label: string }>;
  annotationValues?: Map<string, string>;
  importsScope?: 'active' | 'closure';
  onImportsScopeChange?: (scope: 'active' | 'closure') => void;
  isReasonerRunning?: boolean;
  hideToolbarActions?: boolean;
  selectedProperties?: string[];
  multiSelectMode?: boolean;
  loadingNodes?: Set<string>; // Nodes currently fetching children
  isViewOnly?: boolean;
  onViewOnlyAction?: () => void;
  isLoading?: boolean;
  onLoadMoreTopLevel?: () => void;
  isLoadingMoreTopLevel?: boolean;
  topLevelTotal?: number;
  excludeIds?: string[];
}

function getLocalName(iri: string): string {
  if (!iri) return '';
  const hashIdx = iri.lastIndexOf('#');
  if (hashIdx !== -1) return iri.substring(hashIdx + 1);
  const slashIdx = iri.lastIndexOf('/');
  if (slashIdx !== -1) return iri.substring(slashIdx + 1);
  return iri;
}

function applyTemplate(tpl: string, label: string, id: string): string {
  const safeLabel = label ?? '';
  const safeId = id ?? '';
  return (tpl || '{label}')
    .replace(/\{label\}/g, safeLabel)
    .replace(/\{id\}/g, getLocalName(safeId))
    .replace(/\{iri\}/g, safeId);
}

const EntityHierarchy = ({
  entitiesTab,
  filteredData,
  selectedItem,
  expandedNodes,
  searchQuery,
  onSearchQueryChange,
  searchOptions,
  onSearchOptionsChange,
  onSelectItem,
  onToggleNode,
  onAddItem,
  onDeleteItem,
  onMakeSiblingsDisjoint,
  onMoveClass,
  onOpenPreferences,
  onRenameItem,
  onChangeEntityIri,
  onQuickSetParent,
  onQuickAddNote,
  viewMode = 'asserted',
  onViewModeChange,
  displayMode = 'label' as EntityHierarchyProps['displayMode'],
  onDisplayModeChange,
  displayAnnotationPropIri,
  onDisplayAnnotationPropChange,
  customTemplate = '{label} ({id})',
  onCustomTemplateChange,
  annotationProperties = [] as Array<{ id: string; label: string }>,
  annotationValues,
  importsScope = 'active' as EntityHierarchyProps['importsScope'],
  onImportsScopeChange,
  isReasonerRunning = false,
  hideToolbarActions = false,
  selectedProperties = [],
  multiSelectMode = false,
  loadingNodes = new Set(),
  isViewOnly = false,
  onViewOnlyAction,
  isLoading = false,
  onLoadMoreTopLevel,
  isLoadingMoreTopLevel = false,
  topLevelTotal = 0,
}: EntityHierarchyProps) => {
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; item: SelectableItem } | null>(null);
  const [draggedItem, setDraggedItem] = useState<SelectableItem | null>(null);
  const [renamingItemId, setRenamingItemId] = useState<string | null>(null);
  const contextMenuRef = useRef<HTMLDivElement>(null);
  const { state: collaborationState, publishCursor } = useCollaboration();

  // Search input is decoupled from searchQuery so keystrokes stay instant even
  // when the underlying tree filter (Dashboard's filterRecursively, O(materialized
  // node count) which can be far larger than the entity count on ontologies with
  // heavy multi-parent classes) takes a while — the filter only runs after typing pauses.
  const [searchInputValue, setSearchInputValue] = useState(searchQuery);
  useEffect(() => {
    setSearchInputValue(searchQuery);
  }, [searchQuery]);
  useEffect(() => {
    if (searchInputValue === searchQuery) return;
    const timeoutId = setTimeout(() => onSearchQueryChange(searchInputValue), 200);
    return () => clearTimeout(timeoutId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchInputValue]);

  // ── Virtualized rendering ─────────────────────────────────────────────────
  // Keep render cost O(visible rows) instead of O(total nodes) so the tree stays
  // smooth on large ontologies with tens of thousands of expanded entities —
  // the same windowing technique Monaco/VS Code use to keep huge files fast.
  const ROW_HEIGHT = 24;            // px; virtual rows are clipped to this height
  const OVERSCAN = 12;              // extra rows rendered above/below the viewport
  const VIRTUALIZE_THRESHOLD = 200; // below this, render normally (no windowing)
  const scrollRef = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [viewportH, setViewportH] = useState(600);

  const handleTreeScroll = useCallback((e: React.UIEvent<HTMLDivElement>) => {
    setScrollTop(e.currentTarget.scrollTop);
    if (e.currentTarget.clientHeight !== viewportH) setViewportH(e.currentTarget.clientHeight);
  }, [viewportH]);

  useEffect(() => {
    if (scrollRef.current) setViewportH(scrollRef.current.clientHeight);
  }, [filteredData, entitiesTab]);
  
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
        onViewModeChange?.('asserted');
      } else if (e.key === 'i' || e.key === 'I') {
        onViewModeChange?.('inferred');
      }
    };

    document.addEventListener('keydown', handleKeyPress);
    return () => document.removeEventListener('keydown', handleKeyPress);
  }, [onViewModeChange]);

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
    if (isViewOnly) { e.preventDefault(); return; }
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
    if (isViewOnly) { setDraggedItem(null); return; }
    if (draggedItem && draggedItem.id !== targetItem.id && onMoveClass) {
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
    if (isViewOnly) { onViewOnlyAction?.(); return; }
    setRenamingItemId(item.id);
  };

  const renderRow = (item: SelectableItem, level = 0): React.JSX.Element => {
    // Sentinel node injected into owl:Thing's children when top-level is truncated.
    // Always intercept — never fall through to normal class rendering.
    // Dialogs/secondary trees that don't pass onLoadMoreTopLevel get an empty fragment.
    if (item.id === "__load_more_top_level__") {
      if (!onLoadMoreTopLevel) return <React.Fragment key="__load_more_top_level__" />;
      const loaded = (filteredData[0] as any)?.children?.filter(
        (c: any) => c.id !== "__load_more_top_level__"
      ).length ?? 0;
      const remaining = topLevelTotal > 0 ? topLevelTotal - loaded : 0;
      return (
        <div
          key="__load_more_top_level__"
          style={{ paddingLeft: `${level * 16 + 4}px` }}
          className="py-1"
        >
          <button
            onClick={onLoadMoreTopLevel}
            disabled={isLoadingMoreTopLevel}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium
              bg-gradient-to-r from-indigo-50 to-purple-50
              border border-dashed border-indigo-200
              text-indigo-600 hover:from-indigo-100 hover:to-purple-100 hover:border-indigo-400
              disabled:opacity-60 disabled:cursor-not-allowed
              transition-all w-full"
          >
            {isLoadingMoreTopLevel ? (
              <>
                <Loader2 size={12} className="animate-spin flex-shrink-0" />
                <span>Loading more classes…</span>
              </>
            ) : (
              <>
                <ChevronDown size={12} className="flex-shrink-0" />
                <span>
                  {remaining > 0
                    ? `Load ${remaining.toLocaleString()} more classes`
                    : "Load more classes"}
                </span>
              </>
            )}
          </button>
        </div>
      );
    }
    const isSelected = selectedItem?.id === item.id;
    // An item is a "TreeNode" if it's in the Classes, ObjectProperties, DataProperties, or AnnotationProperties tab.
    // We check 'hasChildren' to know if it's expandable.
    const isTreeNode = entitiesTab === 'Classes' || entitiesTab === 'ObjectProperties' || entitiesTab === 'DataProperties' || entitiesTab === 'AnnotationProperties';
    const hasChildren = 'hasChildren' in item && item.hasChildren;
    const isExpanded = isTreeNode && hasChildren && expandedNodes.includes(item.id);
    const isDragging = draggedItem?.id === item.id;
    const canDrag = entitiesTab === 'Classes' && viewMode === 'asserted';
    
    // Check if class is defined (has equivalent classes) vs primitive
    const isDefined = entitiesTab === 'Classes' && (
      ('equivalentClassesAxioms' in item && (item as TreeNode).equivalentClassesAxioms && (item as TreeNode).equivalentClassesAxioms!.length > 0) ||
      ('equivalentClasses' in item && (item as TreeNode).equivalentClasses && (item as TreeNode).equivalentClasses!.length > 0)
    );
   
    // Find users viewing this node
    const usersViewingNode = activeUsers.filter(user => 
      user.cursorPosition === item.id || user.selectedNodes?.includes(item.id)
    );
    let Icon, iconClasses;
    let itemType = entitiesTab;
    
    // Multi-parent: class appears under more than one parent in the visible tree
    const isMultiParent = entitiesTab === 'Classes' && multiParentIds.has(item.id);

    // Determine icon based on the tab - add equivalence lines for defined classes
    switch (itemType) {
        case 'Classes':
          Icon = Package;
          // Multi-parent: double-border — class has >1 parent
          // Defined: amber-300 with equivalence indicator
          // Normal: solid amber-400
          iconClasses = isMultiParent
            ? 'bg-amber-400 border-amber-600 ring-1 ring-amber-300 ring-offset-[1px]'
            : isDefined
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
        <div
          key={item.id}
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
            publishCursor(item.id, item.label);
          }}
          onContextMenu={(e) => handleContextMenu(e, item)}
        >
          {/* Expander Arrow */}
          {isTreeNode ? (
            <button
              className="p-0.5 mr-1"
              onClick={(e) => {
                e.stopPropagation();
                if (hasChildren && !loadingNodes.has(item.id)) onToggleNode(item.id);
              }}
              disabled={!hasChildren || loadingNodes.has(item.id)}
            >
              {loadingNodes.has(item.id) ? (
                <Loader2 size={14} className="animate-spin text-blue-500" />
              ) : !hasChildren ? (
                <span className="w-5" />
              ) : (
                isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />
              )}
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
          
          {/* Entity Icon */}
           <div
             title={
               isMultiParent ? 'This class has multiple parent classes — it appears once under each parent (correct OWL behavior)'
               : isDefined ? 'Defined class (has equivalent classes)'
               : itemType.slice(0, -1)
             }
             className={`w-3.5 h-3.5 rounded-sm border ${iconClasses} mr-2 flex-shrink-0 flex items-center justify-center`}
           >
              {isDefined ? (
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
            <div className="flex items-center gap-1 overflow-hidden">
              <span
                className={`text-xs select-none truncate min-w-0 shrink ${isSelected ? "font-semibold" : ""} ${
                  (item as any).isUnsatisfiable ? "text-red-600 font-bold" : ""
                }`}
                style={{ color: (item as any).isUnsatisfiable ? '#dc2626' : 'var(--text-primary)' }}
                onDoubleClick={(e) => handleDoubleClick(e, item)}
                title={(() => {
                  if (displayMode === 'id') return item.label;
                  if (displayMode === 'annotation') return `${annotationValues?.get(item.id) ?? item.label} — ${getLocalName(item.id)}`;
                  if (displayMode === 'custom') return `${getLocalName(item.id)}`;
                  return getLocalName(item.id);
                })()}
              >
                {displayMode === 'id'
                  ? getLocalName(item.id)
                  : displayMode === 'annotation'
                    ? (annotationValues?.get(item.id) ?? item.label)
                    : displayMode === 'custom'
                      ? applyTemplate(customTemplate, item.label, item.id)
                      : item.label}
              </span>

              {/* Equivalent classes/properties display — shown in both asserted and inferred modes */}
              {(item as any).equivalentClasses && (item as any).equivalentClasses.length > 0 && (
                <span className="text-[10px] text-amber-700 italic whitespace-nowrap ml-1 shrink-0 max-w-[50%] overflow-hidden text-ellipsis">
                  ≡ {(item as any).equivalentClasses.map((c: any) => c.label).join(', ')}
                </span>
              )}
              {viewMode === 'inferred' && (item as any).equivalentProperties && (item as any).equivalentProperties.length > 0 && (
                <span className="text-[10px] text-gray-500 italic whitespace-nowrap shrink-0 max-w-[50%] overflow-hidden text-ellipsis">
                  ≡ {(item as any).equivalentProperties.map((p: any) => typeof p === 'string' ? p : p.label).join(', ')}
                </span>
              )}
              
              {/* Inferred types for individuals */}
              {entitiesTab === 'Individuals' && viewMode === 'inferred' && (item as any).inferredTypes && (item as any).inferredTypes.length > 0 && (
                <div className="flex flex-wrap gap-1 ml-1">
                  {(item as any).inferredTypes.map((type: any) => (
                    <span 
                      key={type.iri} 
                      className="text-[9px] px-1 py-0.2 rounded bg-purple-50 text-purple-700 border border-purple-100 whitespace-nowrap"
                      title={type.iri}
                    >
                      {type.label}
                    </span>
                  ))}
                </div>
              )}
            </div>
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
        
    );
  };

  // Recursive wrapper used by the non-virtualized path: a row plus its expanded
  // descendants. The virtualized path renders flattened rows directly instead.
  const renderItem = (item: SelectableItem, level = 0): React.JSX.Element => {
    const isTreeNodeTab = entitiesTab === 'Classes' || entitiesTab === 'ObjectProperties' || entitiesTab === 'DataProperties' || entitiesTab === 'AnnotationProperties';
    const nodeHasChildren = 'hasChildren' in item && (item as TreeNode).hasChildren;
    const nodeExpanded = isTreeNodeTab && !!nodeHasChildren && expandedNodes.includes(item.id);
    return (
      <div key={item.id}>
        {renderRow(item, level)}
        {isTreeNodeTab && nodeExpanded && 'children' in item && Array.isArray((item as TreeNode).children) && (item as TreeNode).children!.map((child: TreeNode) => renderItem(child, level + 1))}
      </div>
    );
  };

  // Flatten the currently-visible tree (respecting expanded state) into a
  // positional list. This is the data the virtualizer slices into a window.
  const flatNodes = useMemo(() => {
    const isTreeNodeTab = entitiesTab === 'Classes' || entitiesTab === 'ObjectProperties' || entitiesTab === 'DataProperties' || entitiesTab === 'AnnotationProperties';
    const out: { item: SelectableItem; level: number }[] = [];
    const walk = (items: SelectableItem[], level: number) => {
      for (const item of items) {
        out.push({ item, level });
        const hasKids = 'hasChildren' in item && (item as TreeNode).hasChildren;
        const expanded = isTreeNodeTab && !!hasKids && expandedNodes.includes(item.id);
        if (expanded && 'children' in item && Array.isArray((item as TreeNode).children)) {
          walk((item as TreeNode).children as SelectableItem[], level + 1);
        }
      }
    };
    walk(filteredData || [], 0);
    return out;
  }, [filteredData, expandedNodes, entitiesTab]);

  // Classes that appear more than once in the visible tree are multi-parent.
  // Shown with a double-border icon so the user understands
  // they're not duplicates — the class genuinely has multiple parent classes.
  const multiParentIds = useMemo(() => {
    if (entitiesTab !== 'Classes') return new Set<string>();
    const seen = new Set<string>();
    const multi = new Set<string>();
    for (const { item } of flatNodes) {
      if (item.id === '__load_more_top_level__') continue;
      if (seen.has(item.id)) multi.add(item.id);
      else seen.add(item.id);
    }
    return multi;
  }, [flatNodes, entitiesTab]);

  // Render the hierarchy body. Large asserted trees are windowed; small lists and
  // inferred mode (which can have variable-height rows) render normally.
  const renderHierarchyBody = (): React.ReactNode => {
    if (viewMode !== 'asserted' || flatNodes.length <= VIRTUALIZE_THRESHOLD) {
      return (filteredData || []).map(node => renderItem(node));
    }
    const total = flatNodes.length;
    const start = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - OVERSCAN);
    const visibleCount = Math.ceil(viewportH / ROW_HEIGHT) + OVERSCAN * 2;
    const end = Math.min(total, start + visibleCount);
    const slice = flatNodes.slice(start, end);
    return (
      <div style={{ height: total * ROW_HEIGHT, position: 'relative' }}>
        <div style={{ position: 'absolute', top: start * ROW_HEIGHT, left: 0, right: 0 }}>
          {slice.map(({ item, level }) => (
            <div key={item.id} style={{ height: ROW_HEIGHT, overflow: 'hidden' }}>
              {renderRow(item, level)}
            </div>
          ))}
        </div>
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
  // Mobile: use full width so the hierarchy doesn't crush the details panel.
  // Desktop: keep the existing sidebar widths.
  const sidebarWidthClass = isPropertyTab ? 'w-full md:w-[26rem] md:min-w-[24rem]' : 'w-full md:w-80';
  const currentLabel = currentTabConfig?.label || entitiesTab;

  return (
    <aside className={`${hideToolbarActions ? 'w-full' : sidebarWidthClass} ${hideToolbarActions ? '' : 'border-r'} flex flex-col h-full`} style={{ backgroundColor: 'var(--color-surface)', borderColor: 'var(--color-border)' }}>
      {/* Header with CUD buttons and Asserted/Inferred toggle */}
      {!hideToolbarActions && (
      <div className="text-xs font-semibold p-1 flex items-center justify-center gap-1 flex-wrap border-b text-center" style={{ borderColor: 'var(--color-border)' }}>
        <span style={{ color: 'var(--color-text-secondary)' }}>{currentLabel} hierarchy</span>
        {/* {viewMode === 'inferred' && isReasonerRunning && (
          <span className="flex items-center gap-1 px-1.5 py-0.5 bg-green-50 text-green-700 rounded text-[10px] font-medium border border-green-300">
            <span className="w-1.5 h-1.5 bg-green-500 rounded-full animate-pulse"></span>
            Reasoner Active
          </span>
        )} */}
        <div className="flex items-center gap-0.5">
          {/* Asserted/Inferred mode toggle for all entity types */}
          {(entitiesTab === 'Classes' || entitiesTab === 'ObjectProperties' || entitiesTab === 'DataProperties' || entitiesTab === 'Datatypes' || entitiesTab === 'AnnotationProperties' || entitiesTab === 'Individuals') && (
            <div className="flex items-center gap-0.5 bg-gray-100 rounded px-1 py-0.5">
              <button
                onClick={() => onViewModeChange?.('asserted')}
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
                onClick={() => onViewModeChange?.('inferred')}
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
              {/*
                Toolbar layout, Bug #46 (matches):
                  • Primary "Add" button is CONTEXTUAL — no selection creates
                    a top-level entity (under owl:Thing / topObjectProperty /
                    topDataProperty), with selection creates a sibling. This
                    is what we pass to onAddItem('sibling') — Dashboard's
                    handleAddItem now treats sibling-without-selection as
                    "create at the top".
                  • Secondary "Add Subclass / Sub-property" button always
                    creates a child of the selection. It stays enabled even
                    without a selection so the click can surface a clear
                    "Select X first" notification (the previous disabled
                    state was silent — the actual user complaint).
                  • Icons:
                      Rows3            – multiple rows at the same level
                                         (Add Class / Add at root / Sibling)
                      CornerDownRight  – arrow that visually indents (child)
                                         (Add Subclass / Sub-property)
              */}
              {entitiesTab === 'Classes' && viewMode === 'asserted' && (
                 <>
                 <button
                    title={isViewOnly
                      ? "View-only: upgrade to edit"
                      : selectedItem
                        ? `Add sibling class to "${selectedItem.label}" (Ctrl+Shift+E)`
                        : "Add class at top level (under owl:Thing)"}
                    aria-label={selectedItem ? "Add sibling class" : "Add top-level class"}
                    onClick={() => isViewOnly ? onViewOnlyAction?.() : onAddItem('sibling')}
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600"
                 >
                      <Rows3 size={14} />
                 </button>
                 <button
                    title={isViewOnly ? "View-only: upgrade to edit" : "Add subclass (Ctrl+E)"}
                    aria-label="Add subclass"
                    onClick={() => isViewOnly ? onViewOnlyAction?.() : onAddItem('subclass')}
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600"
                 >
                      <CornerDownRight size={14} />
                 </button>
                 </>
              )}
              {entitiesTab === 'ObjectProperties' && viewMode === 'asserted' && (
                 <>
                 <button
                    title={isViewOnly
                      ? "View-only: upgrade to edit"
                      : selectedItem
                        ? `Add sibling property to "${selectedItem.label}"`
                        : "Add property at top level (under owl:topObjectProperty)"}
                    aria-label={selectedItem ? "Add sibling property" : "Add top-level object property"}
                    onClick={() => isViewOnly ? onViewOnlyAction?.() : onAddItem('sibling')}
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600"
                 >
                      <Rows3 size={14} />
                 </button>
                 <button
                    title={isViewOnly ? "View-only: upgrade to edit" : "Add sub property"}
                    aria-label="Add sub property"
                    onClick={() => isViewOnly ? onViewOnlyAction?.() : onAddItem('subclass')}
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600"
                 >
                      <CornerDownRight size={14} />
                 </button>
                 </>
              )}
              {entitiesTab === 'DataProperties' && viewMode === 'asserted' && (
                 <>
                 <button
                    title={isViewOnly
                      ? "View-only: upgrade to edit"
                      : selectedItem
                        ? `Add sibling property to "${selectedItem.label}"`
                        : "Add property at top level (under owl:topDataProperty)"}
                    aria-label={selectedItem ? "Add sibling property" : "Add top-level data property"}
                    onClick={() => isViewOnly ? onViewOnlyAction?.() : onAddItem('sibling')}
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600"
                 >
                      <Rows3 size={14} />
                 </button>
                 <button
                    title={isViewOnly ? "View-only: upgrade to edit" : "Add sub property"}
                    aria-label="Add sub property"
                    onClick={() => isViewOnly ? onViewOnlyAction?.() : onAddItem('subclass')}
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600"
                 >
                      <CornerDownRight size={14} />
                 </button>
                 </>
              )}
              {/* Bug #45: Annotation Properties tab now matches the other
                  property tabs — Add (top / sibling) plus Add sub-property. */}
              {entitiesTab === 'AnnotationProperties' && viewMode === 'asserted' && (
                 <>
                 <button
                    title={isViewOnly
                      ? "View-only: upgrade to edit"
                      : selectedItem
                        ? `Add sibling annotation property to "${selectedItem.label}"`
                        : "Add annotation property at top level"}
                    aria-label={selectedItem ? "Add sibling annotation property" : "Add top-level annotation property"}
                    onClick={() => isViewOnly ? onViewOnlyAction?.() : onAddItem('sibling')}
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600"
                 >
                      <Rows3 size={14} />
                 </button>
                 <button
                    title={isViewOnly ? "View-only: upgrade to edit" : "Add sub annotation property"}
                    aria-label="Add sub annotation property"
                    onClick={() => isViewOnly ? onViewOnlyAction?.() : onAddItem('subclass')}
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600"
                 >
                      <CornerDownRight size={14} />
                 </button>
                 </>
              )}
              {entitiesTab === 'Datatypes' && viewMode === 'asserted' && (
                 <>
                 <button
                    title={isViewOnly ? "View-only: upgrade to edit" : "Add datatype"}
                    aria-label="Add datatype"
                    onClick={() => isViewOnly ? onViewOnlyAction?.() : onAddItem('subclass')}
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600"
                 >
                      <PlusCircle size={14} />
                 </button>
                 </>
              )}
              {entitiesTab === 'Individuals' && (
                   <button
                      title={isViewOnly ? "View-only: upgrade to edit" : "Add individual"}
                      aria-label="Add individual"
                      onClick={() => isViewOnly ? onViewOnlyAction?.() : onAddItem('individual')}
                    className="p-0.5 rounded text-gray-600 hover:text-purple-600"
                 >
                      <PlusCircle size={14} />
                 </button>
              )}
              <button
                title={isViewOnly ? "View-only: upgrade to edit" : "Delete selected entity"}
                aria-label="Delete selected entity"
                disabled={!isViewOnly && (!selectedItem || ((entitiesTab === 'Classes' || entitiesTab === 'ObjectProperties' || entitiesTab === 'DataProperties' || entitiesTab === 'Datatypes') && viewMode === 'inferred'))}
                onClick={() => isViewOnly ? onViewOnlyAction?.() : onDeleteItem()}
                className="p-0.5 rounded text-gray-600 hover:text-red-600 disabled:text-gray-400 disabled:opacity-80"
             >
                <Trash2 size={14} />
             </button>
             <button
                title={displayMode === 'id' ? "Showing IDs — click to show labels" : "Showing labels — click to show IDs"}
                aria-label="Toggle label / ID display"
                onClick={() => onDisplayModeChange?.(displayMode === 'id' ? 'label' : 'id')}
                className={`p-0.5 rounded ${displayMode === 'id' ? 'text-purple-600 bg-purple-100 hover:bg-purple-200' : 'text-gray-600 hover:text-gray-700 hover:bg-gray-200'}`}
             >
                <Hash size={14} />
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
              <input type="text" placeholder={`Search ${currentLabel.toLowerCase()}...`} value={searchInputValue} onChange={e => setSearchInputValue(e.target.value)} //
                  className="w-full pl-8 pr-3 py-1.5 border rounded-md focus:ring-1 text-sm"
                  style={{
                    borderColor: 'var(--color-border)',
                    backgroundColor: 'var(--color-surface)',
                    color: 'var(--color-text)',
                    '--tw-ring-color': 'var(--color-primary)'
                  } as React.CSSProperties} />
          </div>

          {onSearchOptionsChange && searchOptions && (
            <div className="mt-2 flex flex-wrap gap-2 text-[10px] text-gray-600">
              <label className="flex items-center gap-1">
                <input
                  type="checkbox"
                  checked={searchOptions.useRegex}
                  onChange={(e) => onSearchOptionsChange({ ...searchOptions, useRegex: e.target.checked })}
                  className="w-3 h-3"
                />
                Regex
              </label>
              <label className="flex items-center gap-1">
                <input
                  type="checkbox"
                  checked={searchOptions.searchAnnotations}
                  onChange={(e) => onSearchOptionsChange({ ...searchOptions, searchAnnotations: e.target.checked })}
                  className="w-3 h-3"
                />
                Annotations
              </label>
              <label className="flex items-center gap-1">
                <input
                  type="checkbox"
                  checked={searchOptions.hideDeprecated}
                  onChange={(e) => onSearchOptionsChange({ ...searchOptions, hideDeprecated: e.target.checked })}
                  className="w-3 h-3"
                />
                Hide deprecated
              </label>
              <label className="flex items-center gap-1">
                <input
                  type="checkbox"
                  checked={searchOptions.hideBuiltins}
                  onChange={(e) => onSearchOptionsChange({ ...searchOptions, hideBuiltins: e.target.checked })}
                  className="w-3 h-3"
                />
                Hide built-ins
              </label>
            </div>
          )}
          
          {/* Tips banner */}
          {!hideToolbarActions && entitiesTab === 'Classes' && viewMode === 'asserted' && (
            <div className="mt-2 text-[10px] text-gray-500 bg-blue-50 border border-blue-200 rounded p-2">
              💡 <strong>Tip:</strong> Drag &amp; drop to reorganize | <kbd className="px-1 py-0.5 bg-gray-200 rounded">A</kbd> Asserted | <kbd className="px-1 py-0.5 bg-gray-200 rounded">I</kbd> Inferred | <kbd className="px-1 py-0.5 bg-gray-200 rounded">Ctrl+E</kbd> Add subclass
            </div>
          )}
      </div>
      
      {/* Tree/List View */}
      <div ref={scrollRef} onScroll={handleTreeScroll} className="flex-1 overflow-y-auto p-1">
        {/* Skeleton + footer status (preferred over dimmed tree / "Refreshing…" overlay) */}
        {isLoading ? (
          <div className="p-2 space-y-1" role="status" aria-live="polite">
            {[...Array(12)].map((_, i) => (
              <div
                key={i}
                className="flex items-center gap-2 rounded px-2 py-1 animate-pulse"
                style={{ paddingLeft: `${(i % 3) * 16 + 8}px` }}
              >
                <div className="h-3 w-3 flex-shrink-0 rounded bg-gray-200" />
                <div
                  className="h-3 flex-shrink-0 rounded bg-gray-200"
                  style={{ width: `${60 + ((i * 23) % 80)}px` }}
                />
              </div>
            ))}
            <div className="flex items-center gap-1 px-2 pt-2 text-xs text-purple-500">
              <div className="h-3 w-3 flex-shrink-0 animate-spin rounded-full border-2 border-purple-300 border-t-purple-600" />
              <span>Loading {currentLabel.toLowerCase()}…</span>
            </div>
          </div>
        ) : (viewMode === 'inferred' && !isReasonerRunning) ? (
          <div className="p-4 text-center text-gray-600">
            <p className="mb-2 flex items-center justify-center gap-2">
              <span className="text-2xl">🔍</span>
              <span>No inferred {currentLabel.toLowerCase()} hierarchy available</span>
            </p>
            <p className="text-xs text-gray-500 mb-3">Run the reasoner to generate the inferred hierarchy</p>
            <p className="text-xs text-gray-400">Go to the <strong>Reasoner</strong> tab and click <strong>Start</strong></p>
          </div>
          ) : filteredData && filteredData.length > 0 ? (
            <div className="ontocode-fade-in">{renderHierarchyBody()}</div>
          ) :
          (searchQuery ? (
             <div className="p-4 text-center text-gray-600">No items found for "{searchQuery}".</div>
          ) : viewMode === 'inferred' ? (
             <div className="p-4 text-center text-gray-600">
               <p className="mb-2 flex items-center justify-center gap-2">
                 <span className="text-2xl">🔍</span>
                 <span>No inferred {currentLabel.toLowerCase()} hierarchy available</span>
               </p>
               <p className="text-xs text-gray-500 mb-3">Run the reasoner to generate the inferred hierarchy</p>
               <p className="text-xs text-gray-400">Go to the <strong>Reasoner</strong> tab and click <strong>Start</strong></p>
             </div>
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
      {contextMenu && (entitiesTab === 'Classes' || entitiesTab === 'ObjectProperties' || entitiesTab === 'DataProperties') && (
        <div
          ref={contextMenuRef}
          className="fixed bg-white border border-gray-300 rounded shadow-lg py-1 z-50"
          style={{ left: contextMenu.x, top: contextMenu.y }}
        >
          <button
            className="w-full px-4 py-2 text-left text-sm hover:bg-gray-100 flex items-center gap-2"
            onClick={() => {
              setContextMenu(null);
              if (isViewOnly) { onViewOnlyAction?.(); return; }
              onSelectItem(contextMenu.item);
              setRenamingItemId(contextMenu.item.id);
            }}
          >
            <Edit3 size={14} />
            Rename label
          </button>
          {onChangeEntityIri && (
            <button
              className="w-full px-4 py-2 text-left text-sm hover:bg-gray-100 flex items-center gap-2"
              onClick={() => {
                setContextMenu(null);
                if (isViewOnly) { onViewOnlyAction?.(); return; }
                onChangeEntityIri(contextMenu.item);
              }}
            >
              <Edit3 size={14} />
              Change IRI…
            </button>
          )}
          {onQuickSetParent && (
            <button
              className="w-full px-4 py-2 text-left text-sm hover:bg-gray-100 flex items-center gap-2"
              onClick={() => {
                setContextMenu(null);
                if (isViewOnly) { onViewOnlyAction?.(); return; }
                onQuickSetParent(contextMenu.item);
              }}
            >
              <GitBranch size={14} />
              Set parent
            </button>
          )}
          {onQuickAddNote && (
            <button
              className="w-full px-4 py-2 text-left text-sm hover:bg-gray-100 flex items-center gap-2"
              onClick={() => {
                setContextMenu(null);
                if (isViewOnly) { onViewOnlyAction?.(); return; }
                onQuickAddNote(contextMenu.item);
              }}
            >
              <Edit3 size={14} />
              Quick note
            </button>
          )}
          {entitiesTab === 'Classes' && onMakeSiblingsDisjoint && (
            <button
              className="w-full px-4 py-2 text-left text-sm hover:bg-gray-100 flex items-center gap-2"
              onClick={() => {
                setContextMenu(null);
                if (isViewOnly) { onViewOnlyAction?.(); return; }
                onSelectItem(contextMenu.item);
                onMakeSiblingsDisjoint();
              }}
            >
              <Rows3 size={14} />
              Make Siblings Disjoint
            </button>
          )}
        </div>
      )}
    </aside>
  );
};

export default EntityHierarchy;

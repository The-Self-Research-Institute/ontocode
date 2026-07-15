/**
 * ============================================================================
 * CLASS HIERARCHY PANEL
 * ============================================================================
 *
 * Production-grade Protege-style class hierarchy navigator.
 *
 * Features (Protege parity + improvements):
 *   - Asserted / Inferred / All view modes with visual differentiation
 *     (inferred-only relationships highlighted, matching Protege's yellow accent)
 *   - Sub-class and super-class hierarchy tabs
 *   - Multi-parent classes appear under every asserted parent
 *   - Cycle-safe traversal (defensive visited sets)
 *   - Search with auto-expand-to-match
 *   - Full keyboard navigation (Arrow keys / Home / End / Enter / Space /
 *     F2 rename / Delete / "+" expand / "-" collapse / "*" expand all)
 *   - Working right-click context menu (Show in Graph, Focus, Show
 *     Subclasses, Show Superclasses, Show Individuals, Add Subclass,
 *     Rename, Delete, Copy IRI)
 *   - Instance counts beside every class
 *   - Virtualized rendering for ontologies with 10 000+ classes
 *   - ARIA-compliant tree semantics (role="tree", aria-expanded, aria-level)
 *   - Two-way selection sync via controlled `selectedNodeId`
 *
 * The panel is a pure presentational component — all mutation actions are
 * delegated to callbacks supplied by the parent so the panel stays decoupled
 * from any specific backend/service.
 */

import React, {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState
} from 'react';
import {
  ChevronDown,
  ChevronRight,
  Circle,
  Search,
  X,
  Plus,
  Minus,
  GitBranch,
  Layers,
  ArrowUpFromLine,
  ArrowDownFromLine
} from 'lucide-react';
import type { OntologyNode, OntologyEdge } from '../types';
import {
  buildHierarchyIndex,
  getRootNodes,
  searchNodesWithPaths
} from '../HierarchicalLazyLoading';
import { nodeAccent } from '../utils/nodePalette';

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

export type AssertionViewMode = 'asserted' | 'inferred' | 'all';
export type HierarchyDirection = 'sub' | 'super';

export interface ClassHierarchyContextAction {
  id: string;
  label: string;
  /** Returns false to skip rendering for the supplied node. */
  isApplicable?: (node: OntologyNode) => boolean;
  separatorBefore?: boolean;
  destructive?: boolean;
}

export interface ClassHierarchyPanelProps {
  /** Nodes to render. The panel filters to `class` by default unless `nodeTypes` is set. */
  nodes: OntologyNode[];
  /** Edges driving the hierarchy. */
  edges: OntologyEdge[];
  /** Asserted edges (used for highlighting inferred-only ones). When omitted, every edge is treated as asserted. */
  assertedEdges?: OntologyEdge[];
  /** Title shown above the tree. Falls back to "Class hierarchy" + first root label. */
  title?: string;
  /** Currently selected node id (controlled). */
  selectedNodeId?: string | null;
  /** Fired when the user selects a node (click, keyboard, double-click). */
  onSelect?: (node: OntologyNode | null) => void;
  /** Fired on double-click — defaults to onSelect. Useful for "open in graph". */
  onActivate?: (node: OntologyNode) => void;
  /** Fired when the user requests focus mode for this node (Crosshair button). */
  onFocusInGraph?: (node: OntologyNode) => void;
  /** Fired when the user wants this class displayed in the main graph panel. */
  onShowInGraph?: (node: OntologyNode) => void;
  /** Fired when the user requests showing subclasses / superclasses / individuals in the graph. */
  onShowSubclasses?: (node: OntologyNode) => void;
  onShowSuperclasses?: (node: OntologyNode) => void;
  onShowIndividuals?: (node: OntologyNode) => void;
  /** Mutation callbacks. Set to undefined to hide the action. */
  onAddSubclass?: (parent: OntologyNode) => void;
  onRename?: (node: OntologyNode) => void;
  onDelete?: (node: OntologyNode) => void;
  /** Optional: provide custom context-menu actions, appended after the built-ins. */
  extraContextActions?: ClassHierarchyContextAction[];
  /** Restrict the visible nodes to one or more entity types. Default: ['class']. */
  nodeTypes?: ReadonlyArray<OntologyNode['type']>;
  /** Initial assertion mode. Default 'asserted'. */
  initialAssertionMode?: AssertionViewMode;
  /** Initial direction. Default 'sub' (sub-class hierarchy). */
  initialDirection?: HierarchyDirection;
  /** Show the assertion-mode dropdown. Default true. */
  showAssertionToggle?: boolean;
  /** Show the sub/super direction toggle. Default true. */
  showDirectionToggle?: boolean;
  /** Show the search input. Default true. */
  showSearch?: boolean;
  /** Number of pixels per row — used by the virtualizer. Default 26. */
  rowHeight?: number;
  /** Whether to read-only the panel (hides mutation actions). Default false. */
  readonly?: boolean;
}

// ---------------------------------------------------------------------------
// Internal types
// ---------------------------------------------------------------------------

interface FlatRow {
  /** Stable per-occurrence key (a single class can appear multiple times). */
  key: string;
  node: OntologyNode;
  depth: number;
  hasChildren: boolean;
  childCount: number;
  /** Path to the row (sequence of ancestor ids). */
  path: string[];
  /** True when at least one ancestor link in the path is inferred-only. */
  inferredInPath: boolean;
  /** True when the immediate edge to this row's parent is inferred-only. */
  immediateInferred: boolean;
  /** Number of asserted parents — surfaces multi-inheritance in the UI. */
  parentCount: number;
}

interface ContextMenuState {
  x: number;
  y: number;
  row: FlatRow;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

// Tree-row dot color comes from the canonical NODE_ACCENTS palette (utils/nodePalette) via
// nodeAccent() — this used to be its own dated set of named-CSS-color-era hex values
// (gold/lightgreen/skyblue/plum/khaki), unrelated to what the main graph view uses for the
// same entity types, so the Navigator panel looked visually disconnected from the graph.

const DEFAULT_NODE_TYPES: ReadonlyArray<OntologyNode['type']> = ['class'];

const labelFor = (node: OntologyNode): string =>
  node.label && node.label.length > 0
    ? node.label
    : (node.id.split('#').pop() || node.id.split('/').pop() || node.id);

export const ClassHierarchyPanel: React.FC<ClassHierarchyPanelProps> = ({
  nodes,
  edges,
  assertedEdges,
  title,
  selectedNodeId = null,
  onSelect,
  onActivate,
  onFocusInGraph,
  onShowInGraph,
  onShowSubclasses,
  onShowSuperclasses,
  onShowIndividuals,
  onAddSubclass,
  onRename,
  onDelete,
  extraContextActions,
  nodeTypes = DEFAULT_NODE_TYPES,
  initialAssertionMode = 'asserted',
  initialDirection = 'sub',
  showAssertionToggle = true,
  showDirectionToggle = true,
  showSearch = true,
  rowHeight = 26,
  readonly = false
}) => {
  // ----- State ------------------------------------------------------------
  const [assertionMode, setAssertionMode] = useState<AssertionViewMode>(initialAssertionMode);
  const [direction, setDirection] = useState<HierarchyDirection>(initialDirection);
  const [searchTerm, setSearchTerm] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [viewportHeight, setViewportHeight] = useState(0);

  // Debounce search input — keeps typing snappy on huge ontologies.
  useEffect(() => {
    const handle = window.setTimeout(() => setDebouncedSearch(searchTerm.trim()), 120);
    return () => window.clearTimeout(handle);
  }, [searchTerm]);

  // ----- Derive filtered node set ----------------------------------------
  const filteredNodes = useMemo(() => {
    const allowed = new Set(nodeTypes);
    return nodes.filter(n => allowed.has(n.type));
  }, [nodes, nodeTypes]);

  // ----- Determine which edges are "inferred-only" -----------------------
  const inferredOnlyEdgeIds = useMemo(() => {
    if (!assertedEdges) return new Set<string>();
    const assertedKeys = new Set<string>();
    for (const edge of assertedEdges) {
      assertedKeys.add(`${edge.from} ${edge.to} ${edge.type}`);
    }
    const out = new Set<string>();
    for (const edge of edges) {
      const key = `${edge.from} ${edge.to} ${edge.type}`;
      if (!assertedKeys.has(key)) out.add(edge.id);
    }
    return out;
  }, [edges, assertedEdges]);

  // ----- Build the active edge set for the requested view mode -----------
  const activeEdges = useMemo(() => {
    if (assertionMode === 'all') return edges;
    if (assertionMode === 'asserted' && assertedEdges) return assertedEdges;
    if (assertionMode === 'inferred' && assertedEdges) {
      return edges.filter(e => inferredOnlyEdgeIds.has(e.id));
    }
    return edges;
  }, [edges, assertedEdges, inferredOnlyEdgeIds, assertionMode]);

  // ----- Hierarchy index for fast lookups --------------------------------
  const index = useMemo(
    () => buildHierarchyIndex(filteredNodes, activeEdges),
    [filteredNodes, activeEdges]
  );

  // For super-class direction we use the same index but invert child/parent traversal.
  const adjacencyChildren = direction === 'sub' ? index.childrenOf : index.parentsOf;
  const adjacencyParents = direction === 'sub' ? index.parentsOf : index.childrenOf;

  // ----- Determine roots --------------------------------------------------
  const roots = useMemo(() => {
    const ids =
      direction === 'sub'
        ? getRootNodes(filteredNodes, activeEdges)
        : filteredNodes.filter(n => !(adjacencyParents.get(n.id)?.length)).map(n => n.id);
    // Keep roots that have a node, sorted alphabetically.
    const lookup = new Map(filteredNodes.map(n => [n.id, n]));
    return ids
      .map(id => lookup.get(id))
      .filter((n): n is OntologyNode => Boolean(n))
      .sort((a, b) => labelFor(a).localeCompare(labelFor(b)));
  }, [filteredNodes, activeEdges, adjacencyParents, direction]);

  // ----- Auto-expand on search to reveal matches -------------------------
  useEffect(() => {
    if (!debouncedSearch) return;
    const { nodesToExpand } = searchNodesWithPaths(debouncedSearch, filteredNodes, activeEdges);
    if (nodesToExpand.size === 0) return;
    setExpanded(prev => {
      let changed = false;
      const next = new Set(prev);
      nodesToExpand.forEach(id => {
        if (!next.has(id)) {
          next.add(id);
          changed = true;
        }
      });
      return changed ? next : prev;
    });
  }, [debouncedSearch, filteredNodes, activeEdges]);

  // ----- Auto-expand path to selected node --------------------------------
  useEffect(() => {
    if (!selectedNodeId) return;
    const ancestors: string[] = [];
    const visited = new Set<string>();
    const queue = [selectedNodeId];
    while (queue.length > 0) {
      const cur = queue.shift() as string;
      const parents = adjacencyParents.get(cur);
      if (!parents) continue;
      for (const p of parents) {
        if (visited.has(p)) continue;
        visited.add(p);
        ancestors.push(p);
        queue.push(p);
      }
    }
    if (ancestors.length === 0) return;
    setExpanded(prev => {
      let changed = false;
      const next = new Set(prev);
      ancestors.forEach(id => {
        if (!next.has(id)) {
          next.add(id);
          changed = true;
        }
      });
      return changed ? next : prev;
    });
  }, [selectedNodeId, adjacencyParents]);

  // ----- Flatten the tree into virtualizable rows ------------------------
  const flatRows: FlatRow[] = useMemo(() => {
    const rows: FlatRow[] = [];
    const lookup = new Map(filteredNodes.map(n => [n.id, n]));
    const search = debouncedSearch.toLowerCase();

    const matchesSearch = (id: string, memo: Map<string, boolean>): boolean => {
      if (!search) return true;
      const cached = memo.get(id);
      if (cached !== undefined) return cached;
      memo.set(id, false); // break cycles
      const node = lookup.get(id);
      const selfMatch = !!node && (
        labelFor(node).toLowerCase().includes(search) ||
        node.id.toLowerCase().includes(search)
      );
      const children = adjacencyChildren.get(id) ?? [];
      const childMatch = children.some(c => matchesSearch(c, memo));
      const result = selfMatch || childMatch;
      memo.set(id, result);
      return result;
    };

    const matchMemo = new Map<string, boolean>();

    // Map asserted-only edge keys for inferred highlighting.
    const inferredEdgeBetween = (childId: string, parentId: string): boolean => {
      if (!assertedEdges) return false;
      // An inferred-only relationship is one that exists in `edges` but not in `assertedEdges`.
      const targetTo = direction === 'sub' ? parentId : childId;
      const targetFrom = direction === 'sub' ? childId : parentId;
      const inAsserted = assertedEdges.some(
        e => e.from === targetFrom && e.to === targetTo &&
             (e.type === 'subClassOf' || e.type === 'subPropertyOf' || e.type === 'instanceOf')
      );
      const inActive = edges.some(
        e => e.from === targetFrom && e.to === targetTo &&
             (e.type === 'subClassOf' || e.type === 'subPropertyOf' || e.type === 'instanceOf')
      );
      return inActive && !inAsserted;
    };

    const visit = (
      id: string,
      depth: number,
      path: string[],
      pathSet: Set<string>,
      inferredInPath: boolean,
      parentId: string | null
    ): void => {
      if (pathSet.has(id)) return; // cycle guard
      const node = lookup.get(id);
      if (!node) return;
      if (!matchesSearch(id, matchMemo)) return;

      const childIds = (adjacencyChildren.get(id) ?? [])
        .filter(cid => lookup.has(cid))
        .sort((a, b) => labelFor(lookup.get(a) as OntologyNode).localeCompare(
          labelFor(lookup.get(b) as OntologyNode)
        ));

      const parents = adjacencyParents.get(id) ?? [];
      const immediateInferred = parentId !== null ? inferredEdgeBetween(id, parentId) : false;

      rows.push({
        key: path.length === 0 ? id : `${path.join('>')}>${id}`,
        node,
        depth,
        hasChildren: childIds.length > 0,
        childCount: childIds.length,
        path,
        inferredInPath: inferredInPath || immediateInferred,
        immediateInferred,
        parentCount: parents.length
      });

      if (!expanded.has(id)) return;

      const nextPathSet = new Set(pathSet);
      nextPathSet.add(id);
      const nextPath = [...path, id];
      for (const childId of childIds) {
        visit(childId, depth + 1, nextPath, nextPathSet, inferredInPath || immediateInferred, id);
      }
    };

    for (const root of roots) {
      visit(root.id, 0, [], new Set<string>(), false, null);
    }

    return rows;
  }, [
    roots,
    expanded,
    debouncedSearch,
    adjacencyChildren,
    adjacencyParents,
    filteredNodes,
    edges,
    assertedEdges,
    direction
  ]);

  // ----- Instance counts (lazy, only computed once per nodes/edges) ------
  const instanceCounts = useMemo(() => {
    const out = new Map<string, number>();
    for (const edge of edges) {
      if (edge.type === 'instanceOf') {
        out.set(edge.to, (out.get(edge.to) ?? 0) + 1);
      }
    }
    return out;
  }, [edges]);

  // ----- Virtualization ---------------------------------------------------
  const scrollerRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const el = scrollerRef.current;
    if (!el) return;
    const update = (): void => {
      setViewportHeight(el.clientHeight);
    };
    update();
    const obs = new ResizeObserver(update);
    obs.observe(el);
    return () => obs.disconnect();
  }, []);

  const overscan = 8;
  const totalRows = flatRows.length;
  const startIndex = Math.max(0, Math.floor(scrollTop / rowHeight) - overscan);
  const endIndex = Math.min(
    totalRows,
    Math.ceil((scrollTop + viewportHeight) / rowHeight) + overscan
  );
  const visibleRows = flatRows.slice(startIndex, endIndex);
  const offsetY = startIndex * rowHeight;

  const handleScroll = useCallback((e: React.UIEvent<HTMLDivElement>) => {
    setScrollTop((e.target as HTMLDivElement).scrollTop);
  }, []);

  // ----- Keyboard navigation ---------------------------------------------
  const selectedRowIndex = useMemo(() => {
    if (!selectedNodeId) return -1;
    return flatRows.findIndex(r => r.node.id === selectedNodeId);
  }, [flatRows, selectedNodeId]);

  const moveSelection = useCallback(
    (delta: number) => {
      if (flatRows.length === 0) return;
      let idx = selectedRowIndex < 0 ? 0 : selectedRowIndex + delta;
      idx = Math.max(0, Math.min(flatRows.length - 1, idx));
      const row = flatRows[idx];
      onSelect?.(row.node);
      // Auto-scroll the row into view.
      const el = scrollerRef.current;
      if (el) {
        const rowTop = idx * rowHeight;
        const rowBottom = rowTop + rowHeight;
        if (rowTop < el.scrollTop) el.scrollTop = rowTop;
        else if (rowBottom > el.scrollTop + el.clientHeight) {
          el.scrollTop = rowBottom - el.clientHeight;
        }
      }
    },
    [flatRows, selectedRowIndex, onSelect, rowHeight]
  );

  const setExpansion = useCallback((nodeId: string, value: boolean) => {
    setExpanded(prev => {
      if (value && prev.has(nodeId)) return prev;
      if (!value && !prev.has(nodeId)) return prev;
      const next = new Set(prev);
      if (value) next.add(nodeId);
      else next.delete(nodeId);
      return next;
    });
  }, []);

  const expandAllVisible = useCallback(() => {
    setExpanded(prev => {
      const next = new Set(prev);
      const visit = (id: string, pathSet: Set<string>): void => {
        if (pathSet.has(id)) return;
        const children = adjacencyChildren.get(id) ?? [];
        if (children.length === 0) return;
        next.add(id);
        const nextSet = new Set(pathSet);
        nextSet.add(id);
        for (const child of children) visit(child, nextSet);
      };
      for (const root of roots) visit(root.id, new Set<string>());
      return next;
    });
  }, [adjacencyChildren, roots]);

  const collapseAllVisible = useCallback(() => {
    setExpanded(new Set());
  }, []);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLDivElement>) => {
      if (flatRows.length === 0) return;
      const currentRow = selectedRowIndex >= 0 ? flatRows[selectedRowIndex] : null;

      switch (e.key) {
        case 'ArrowDown':
          e.preventDefault();
          moveSelection(1);
          return;
        case 'ArrowUp':
          e.preventDefault();
          moveSelection(-1);
          return;
        case 'PageDown':
          e.preventDefault();
          moveSelection(Math.max(1, Math.floor(viewportHeight / rowHeight) - 1));
          return;
        case 'PageUp':
          e.preventDefault();
          moveSelection(-Math.max(1, Math.floor(viewportHeight / rowHeight) - 1));
          return;
        case 'Home':
          e.preventDefault();
          if (flatRows[0]) onSelect?.(flatRows[0].node);
          return;
        case 'End':
          e.preventDefault();
          if (flatRows[flatRows.length - 1]) onSelect?.(flatRows[flatRows.length - 1].node);
          return;
        case 'ArrowRight':
          e.preventDefault();
          if (currentRow && currentRow.hasChildren) {
            if (!expanded.has(currentRow.node.id)) setExpansion(currentRow.node.id, true);
            else moveSelection(1);
          }
          return;
        case 'ArrowLeft':
          e.preventDefault();
          if (currentRow) {
            if (expanded.has(currentRow.node.id)) {
              setExpansion(currentRow.node.id, false);
            } else if (currentRow.path.length > 0) {
              const parentId = currentRow.path[currentRow.path.length - 1];
              const parentNode = filteredNodes.find(n => n.id === parentId);
              if (parentNode) onSelect?.(parentNode);
            }
          }
          return;
        case 'Enter':
          e.preventDefault();
          if (currentRow) {
            if (onActivate) onActivate(currentRow.node);
            else onSelect?.(currentRow.node);
          }
          return;
        case ' ':
          e.preventDefault();
          if (currentRow && currentRow.hasChildren) {
            setExpansion(currentRow.node.id, !expanded.has(currentRow.node.id));
          }
          return;
        case '+':
        case '=':
          e.preventDefault();
          if (currentRow && currentRow.hasChildren) setExpansion(currentRow.node.id, true);
          return;
        case '-':
          e.preventDefault();
          if (currentRow) setExpansion(currentRow.node.id, false);
          return;
        case '*':
          e.preventDefault();
          expandAllVisible();
          return;
        case 'F2':
          e.preventDefault();
          if (currentRow && !readonly) onRename?.(currentRow.node);
          return;
        case 'Delete':
          e.preventDefault();
          if (currentRow && !readonly) onDelete?.(currentRow.node);
          return;
        default:
          break;
      }

      // Ctrl/Cmd combinations.
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'c' && currentRow) {
        e.preventDefault();
        copyToClipboard(currentRow.node.id);
      }
    },
    [
      flatRows,
      selectedRowIndex,
      moveSelection,
      viewportHeight,
      rowHeight,
      onSelect,
      onActivate,
      expanded,
      setExpansion,
      filteredNodes,
      readonly,
      onRename,
      onDelete,
      expandAllVisible
    ]
  );

  // ----- Context menu actions --------------------------------------------
  const closeContextMenu = useCallback(() => setContextMenu(null), []);

  useEffect(() => {
    if (!contextMenu) return;
    const dismiss = (): void => closeContextMenu();
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') closeContextMenu();
    };
    window.addEventListener('click', dismiss);
    window.addEventListener('contextmenu', dismiss);
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('click', dismiss);
      window.removeEventListener('contextmenu', dismiss);
      window.removeEventListener('keydown', onKey);
    };
  }, [contextMenu, closeContextMenu]);

  const builtInActions = useMemo<ClassHierarchyContextAction[]>(() => {
    const list: ClassHierarchyContextAction[] = [];
    if (onShowInGraph) list.push({ id: 'show', label: 'Show in graph' });
    if (onFocusInGraph) list.push({ id: 'focus', label: 'Focus (local view)' });
    list.push({ id: 'copy-iri', label: 'Copy IRI', separatorBefore: true });
    if (onShowSubclasses) list.push({ id: 'show-sub', label: 'Show subclasses', separatorBefore: true });
    if (onShowSuperclasses) list.push({ id: 'show-super', label: 'Show superclasses' });
    if (onShowIndividuals) list.push({ id: 'show-individuals', label: 'Show individuals' });
    if (!readonly && onAddSubclass) {
      list.push({ id: 'add-subclass', label: 'Add subclass…', separatorBefore: true });
    }
    if (!readonly && onRename) {
      list.push({ id: 'rename', label: 'Rename… (F2)' });
    }
    if (!readonly && onDelete) {
      list.push({ id: 'delete', label: 'Delete (Del)', destructive: true, separatorBefore: true });
    }
    return list;
  }, [
    readonly,
    onShowInGraph,
    onFocusInGraph,
    onShowSubclasses,
    onShowSuperclasses,
    onShowIndividuals,
    onAddSubclass,
    onRename,
    onDelete
  ]);

  const allContextActions = useMemo<ClassHierarchyContextAction[]>(() => {
    return extraContextActions ? [...builtInActions, ...extraContextActions] : builtInActions;
  }, [builtInActions, extraContextActions]);

  const dispatchContextAction = useCallback(
    (id: string, node: OntologyNode) => {
      switch (id) {
        case 'show':
          onShowInGraph?.(node);
          break;
        case 'focus':
          onFocusInGraph?.(node);
          break;
        case 'copy-iri':
          copyToClipboard(node.id);
          break;
        case 'show-sub':
          onShowSubclasses?.(node);
          break;
        case 'show-super':
          onShowSuperclasses?.(node);
          break;
        case 'show-individuals':
          onShowIndividuals?.(node);
          break;
        case 'add-subclass':
          onAddSubclass?.(node);
          break;
        case 'rename':
          onRename?.(node);
          break;
        case 'delete':
          onDelete?.(node);
          break;
        default:
          break;
      }
      closeContextMenu();
    },
    [
      onShowInGraph,
      onFocusInGraph,
      onShowSubclasses,
      onShowSuperclasses,
      onShowIndividuals,
      onAddSubclass,
      onRename,
      onDelete,
      closeContextMenu
    ]
  );

  // ----- Header title -----------------------------------------------------
  const computedTitle = useMemo(() => {
    if (title) return title;
    const directionLabel = direction === 'sub' ? 'Class hierarchy' : 'Class hierarchy (superclasses)';
    if (roots.length === 0) return directionLabel;
    if (roots.length === 1) return `${directionLabel}: ${labelFor(roots[0])}`;
    return `${directionLabel} (${roots.length} roots)`;
  }, [title, roots, direction]);

  // ----- Render -----------------------------------------------------------
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        backgroundColor: 'var(--surface-1)',
        borderRight: '1px solid var(--border)',
        fontFamily: 'system-ui, -apple-system, "Segoe UI", Arial, sans-serif'
      }}
    >
      {/* Header */}
      <div
        style={{
          padding: '8px 12px',
          borderBottom: '1px solid var(--border)',
          backgroundColor: 'var(--surface-2)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 8
        }}
      >
        <div
          style={{
            fontWeight: 600,
            fontSize: 13,
            color: 'var(--text-primary)',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap'
          }}
          title={computedTitle}
        >
          {computedTitle}
        </div>
        <div style={{ display: 'flex', gap: 4 }}>
          <IconButton title="Expand all (*)" onClick={expandAllVisible}>
            <Plus size={14} />
          </IconButton>
          <IconButton title="Collapse all" onClick={collapseAllVisible}>
            <Minus size={14} />
          </IconButton>
        </div>
      </div>

      {/* Toolbar */}
      <div
        style={{
          padding: '6px 8px',
          borderBottom: '1px solid var(--border)',
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          flexWrap: 'wrap'
        }}
      >
        {showAssertionToggle && (
          <select
            value={assertionMode}
            onChange={(e) => setAssertionMode(e.target.value as AssertionViewMode)}
            style={selectStyle}
            title="Switch between asserted edges, inferred-only edges, and both"
          >
            <option value="asserted">Asserted</option>
            <option value="inferred" disabled={!assertedEdges}>Inferred</option>
            <option value="all">All</option>
          </select>
        )}
        {showDirectionToggle && (
          <div style={{ display: 'flex', border: '1px solid var(--border)', borderRadius: 4, overflow: 'hidden' }}>
            <button
              type="button"
              onClick={() => setDirection('sub')}
              title="Sub-class hierarchy (top-down)"
              style={{
                ...directionButtonStyle,
                backgroundColor: direction === 'sub' ? 'var(--accent)' : 'var(--surface-1)',
                color: direction === 'sub' ? 'var(--on-accent)' : 'var(--text-primary)'
              }}
            >
              <ArrowDownFromLine size={12} />
              <span>Sub</span>
            </button>
            <button
              type="button"
              onClick={() => setDirection('super')}
              title="Super-class hierarchy (bottom-up)"
              style={{
                ...directionButtonStyle,
                backgroundColor: direction === 'super' ? 'var(--accent)' : 'var(--surface-1)',
                color: direction === 'super' ? 'var(--on-accent)' : 'var(--text-primary)',
                borderLeft: '1px solid var(--border)'
              }}
            >
              <ArrowUpFromLine size={12} />
              <span>Super</span>
            </button>
          </div>
        )}
        {showSearch && (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              flex: '1 1 140px',
              minWidth: 120,
              border: '1px solid var(--border)',
              borderRadius: 4,
              padding: '2px 6px',
              backgroundColor: 'var(--surface-1)'
            }}
          >
            <Search size={13} color="var(--text-secondary)" />
            <input
              type="text"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              placeholder="Filter…"
              aria-label="Filter classes"
              style={{
                border: 'none',
                outline: 'none',
                flex: 1,
                padding: '2px 4px',
                fontSize: 12,
                background: 'transparent'
              }}
            />
            {searchTerm && (
              <button
                type="button"
                onClick={() => setSearchTerm('')}
                title="Clear filter"
                style={{
                  border: 'none',
                  background: 'transparent',
                  cursor: 'pointer',
                  padding: 2,
                  display: 'flex'
                }}
              >
                <X size={12} />
              </button>
            )}
          </div>
        )}
      </div>

      {/* Tree */}
      <div
        ref={scrollerRef}
        role="tree"
        aria-label="Class hierarchy"
        tabIndex={0}
        onScroll={handleScroll}
        onKeyDown={handleKeyDown}
        style={{
          flex: 1,
          overflowY: 'auto',
          overflowX: 'hidden',
          outline: 'none',
          position: 'relative'
        }}
      >
        {flatRows.length === 0 ? (
          <div
            style={{
              padding: 24,
              fontSize: 12,
              color: 'var(--text-secondary)',
              textAlign: 'center'
            }}
          >
            {filteredNodes.length === 0
              ? 'No classes in this ontology yet.'
              : 'No classes match the current filter.'}
          </div>
        ) : (
          <div style={{ height: totalRows * rowHeight, position: 'relative' }}>
            <div
              style={{
                position: 'absolute',
                top: offsetY,
                left: 0,
                right: 0
              }}
            >
              {visibleRows.map(row => (
                <HierarchyRow
                  key={row.key}
                  row={row}
                  rowHeight={rowHeight}
                  isSelected={selectedNodeId === row.node.id}
                  isExpanded={expanded.has(row.node.id)}
                  instanceCount={instanceCounts.get(row.node.id) ?? 0}
                  onToggleExpand={() => setExpansion(row.node.id, !expanded.has(row.node.id))}
                  onSelect={() => onSelect?.(row.node)}
                  onActivate={() => (onActivate ?? onSelect)?.(row.node)}
                  onContextMenu={(x, y) => setContextMenu({ x, y, row })}
                />
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Footer status bar */}
      <div
        style={{
          padding: '4px 10px',
          borderTop: '1px solid var(--border)',
          fontSize: 11,
          color: 'var(--text-secondary)',
          backgroundColor: 'var(--surface-2)',
          display: 'flex',
          alignItems: 'center',
          gap: 8
        }}
      >
        <Layers size={11} />
        <span>{flatRows.length} visible · {filteredNodes.length} total</span>
        {assertionMode === 'inferred' && (
          <span style={{ color: '#b45309', fontWeight: 600 }}>· inferred only</span>
        )}
        {selectedRowIndex >= 0 && (
          <span style={{ marginLeft: 'auto' }}>
            ↑↓ navigate · → expand · Enter open
          </span>
        )}
      </div>

      {/* Context menu */}
      {contextMenu && (
        <ContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          actions={allContextActions.filter(
            a => !a.isApplicable || a.isApplicable(contextMenu.row.node)
          )}
          onSelect={(actionId) => dispatchContextAction(actionId, contextMenu.row.node)}
        />
      )}
    </div>
  );
};

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

interface HierarchyRowProps {
  row: FlatRow;
  rowHeight: number;
  isSelected: boolean;
  isExpanded: boolean;
  instanceCount: number;
  onToggleExpand: () => void;
  onSelect: () => void;
  onActivate: () => void;
  onContextMenu: (x: number, y: number) => void;
}

const HierarchyRow: React.FC<HierarchyRowProps> = React.memo(
  ({
    row,
    rowHeight,
    isSelected,
    isExpanded,
    instanceCount,
    onToggleExpand,
    onSelect,
    onActivate,
    onContextMenu
  }) => {
    const indent = row.depth * 14;
    const iconColor = nodeAccent(row.node.type);

    return (
      <div
        role="treeitem"
        aria-level={row.depth + 1}
        aria-selected={isSelected}
        aria-expanded={row.hasChildren ? isExpanded : undefined}
        onClick={onSelect}
        onDoubleClick={onActivate}
        onContextMenu={(e) => {
          e.preventDefault();
          onSelect();
          onContextMenu(e.clientX, e.clientY);
        }}
        style={{
          height: rowHeight,
          display: 'flex',
          alignItems: 'center',
          paddingLeft: 6 + indent,
          paddingRight: 8,
          cursor: 'default',
          backgroundColor: isSelected ? 'var(--surface-3)' : 'transparent',
          borderLeft: isSelected ? '3px solid var(--accent)' : '3px solid transparent',
          fontSize: 13,
          color: 'var(--text-primary)',
          userSelect: 'none',
          gap: 4
        }}
        onMouseEnter={(e) => {
          if (!isSelected) (e.currentTarget as HTMLDivElement).style.backgroundColor = 'var(--surface-2)';
        }}
        onMouseLeave={(e) => {
          if (!isSelected) (e.currentTarget as HTMLDivElement).style.backgroundColor = 'transparent';
        }}
      >
        {row.hasChildren ? (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              onToggleExpand();
            }}
            aria-label={isExpanded ? 'Collapse' : 'Expand'}
            style={{
              border: 'none',
              background: 'transparent',
              cursor: 'pointer',
              padding: 0,
              width: 16,
              height: 16,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center'
            }}
          >
            {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          </button>
        ) : (
          <span style={{ display: 'inline-block', width: 16, height: 16 }} />
        )}

        <Circle
          size={10}
          fill={iconColor}
          stroke="var(--text-secondary)"
          strokeWidth={1}
          style={{ flexShrink: 0 }}
        />

        <span
          style={{
            flex: 1,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            fontStyle: row.immediateInferred ? 'italic' : 'normal',
            color: row.immediateInferred ? '#b45309' : 'var(--text-primary)',
            fontWeight: row.immediateInferred ? 500 : 400
          }}
          title={`${labelFor(row.node)}\n${row.node.id}${
            row.parentCount > 1 ? `\nMulti-parent (${row.parentCount})` : ''
          }${row.immediateInferred ? '\nInferred relation' : ''}`}
        >
          {labelFor(row.node)}
        </span>

        {row.parentCount > 1 && (
          <span
            title={`This class has ${row.parentCount} asserted parents`}
            style={badgeStyle('#7c3aed', '#ede9fe')}
          >
            <GitBranch size={9} />
            {row.parentCount}
          </span>
        )}
        {row.childCount > 0 && (
          <span
            title={`${row.childCount} direct subclass${row.childCount === 1 ? '' : 'es'}`}
            style={badgeStyle('#2563eb', '#dbeafe')}
          >
            {row.childCount}
          </span>
        )}
        {instanceCount > 0 && (
          <span
            title={`${instanceCount} individual${instanceCount === 1 ? '' : 's'}`}
            style={badgeStyle('#059669', '#d1fae5')}
          >
            i {instanceCount}
          </span>
        )}
      </div>
    );
  }
);

HierarchyRow.displayName = 'HierarchyRow';

interface ContextMenuProps {
  x: number;
  y: number;
  actions: ClassHierarchyContextAction[];
  onSelect: (actionId: string) => void;
}

const ContextMenu: React.FC<ContextMenuProps> = ({ x, y, actions, onSelect }) => {
  // Avoid the menu running off the right or bottom edge.
  const ref = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState({ left: x, top: y });

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    let left = x;
    let top = y;
    if (left + rect.width > window.innerWidth - 8) {
      left = Math.max(8, window.innerWidth - rect.width - 8);
    }
    if (top + rect.height > window.innerHeight - 8) {
      top = Math.max(8, window.innerHeight - rect.height - 8);
    }
    setPosition({ left, top });
  }, [x, y]);

  if (actions.length === 0) return null;

  return (
    <div
      ref={ref}
      role="menu"
      onClick={(e) => e.stopPropagation()}
      onContextMenu={(e) => e.preventDefault()}
      style={{
        position: 'fixed',
        left: position.left,
        top: position.top,
        background: 'var(--surface-1)',
        border: '1px solid var(--border)',
        borderRadius: 6,
        boxShadow: '0 8px 24px rgba(15, 23, 42, 0.12)',
        zIndex: 9999,
        minWidth: 200,
        padding: '4px 0',
        fontSize: 13
      }}
    >
      {actions.map((action) => (
        <React.Fragment key={action.id}>
          {action.separatorBefore && (
            <div
              role="separator"
              style={{
                height: 1,
                background: 'var(--border)',
                margin: '4px 0'
              }}
            />
          )}
          <button
            type="button"
            role="menuitem"
            onClick={() => onSelect(action.id)}
            style={{
              display: 'block',
              width: '100%',
              padding: '6px 14px',
              border: 'none',
              background: 'transparent',
              textAlign: 'left',
              cursor: 'pointer',
              color: action.destructive ? '#b91c1c' : 'var(--text-primary)',
              fontSize: 13
            }}
            onMouseEnter={(e) => {
              (e.currentTarget as HTMLButtonElement).style.backgroundColor = action.destructive
                ? '#fee2e2'
                : 'var(--surface-2)';
            }}
            onMouseLeave={(e) => {
              (e.currentTarget as HTMLButtonElement).style.backgroundColor = 'transparent';
            }}
          >
            {action.label}
          </button>
        </React.Fragment>
      ))}
    </div>
  );
};

interface IconButtonProps {
  title: string;
  onClick: () => void;
  children: React.ReactNode;
}

const IconButton: React.FC<IconButtonProps> = ({ title, onClick, children }) => (
  <button
    type="button"
    onClick={onClick}
    title={title}
    aria-label={title}
    style={{
      border: '1px solid var(--border)',
      background: 'var(--surface-1)',
      borderRadius: 4,
      padding: '2px 4px',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      cursor: 'pointer'
    }}
  >
    {children}
  </button>
);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const selectStyle: React.CSSProperties = {
  padding: '2px 6px',
  border: '1px solid var(--border)',
  borderRadius: 4,
  fontSize: 12,
  backgroundColor: 'var(--surface-1)'
};

const directionButtonStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 4,
  border: 'none',
  padding: '3px 8px',
  fontSize: 12,
  cursor: 'pointer'
};

const badgeStyle = (fg: string, bg: string): React.CSSProperties => ({
  display: 'inline-flex',
  alignItems: 'center',
  gap: 2,
  padding: '0 6px',
  height: 16,
  borderRadius: 8,
  backgroundColor: bg,
  color: fg,
  fontSize: 10,
  fontWeight: 600,
  flexShrink: 0
});

function copyToClipboard(text: string): void {
  if (typeof navigator !== 'undefined' && navigator.clipboard) {
    void navigator.clipboard.writeText(text).catch(() => {
      fallbackCopy(text);
    });
    return;
  }
  fallbackCopy(text);
}

function fallbackCopy(text: string): void {
  if (typeof document === 'undefined') return;
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  try {
    document.execCommand('copy');
  } catch {
    /* swallow */
  }
  document.body.removeChild(ta);
}

export default ClassHierarchyPanel;

/**
 * Graph View Sidebar - Similar to VOWL plugin
 * Provides entity selector, filters, statistics, and detailed information
 */

import React, { useState, useMemo, useEffect, useRef } from 'react';
import {
  Search, X, ChevronDown, ChevronRight,
  Square, Circle, Diamond, Hexagon,
  Layers, GitBranch, ExternalLink
} from 'lucide-react';
import { OntologyNode as BaseOntologyNode, OntologyEdge } from '../types';
import { ClassHierarchyPanel } from './ClassHierarchyPanel';

interface OntologyNode extends BaseOntologyNode {
  iri?: string;
  description?: string;
}

interface GraphViewSidebarProps {
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  selectedNode: OntologyNode | null;
  onNodeSelect: (node: OntologyNode | null) => void;
  onNodeHighlight: (nodeId: string | null) => void;
  filters: {
    nodeTypes: Set<string>;
    edgeTypes: Set<string>;
  };
  onFilterChange: (filters: any) => void;
  projectId: string;
  viewMode?: 'force' | 'vowl' | 'ontograph' | 'spatial3d';
  vowlLegend?: Array<{ 
    name: string; 
    type: string; 
    nodeType?: string; 
    stroke?: string; 
    strokeDasharray?: string;
    color?: string;
  }>;
  vowlFilters?: {
    showExternalClasses: boolean;
    showInternalClasses: boolean;
    showDatatypes: boolean;
    showObjectProperties: boolean;
    showDataProperties: boolean;
    showSubClassOf: boolean;
    showFunctionalProperties: boolean;
  };
  onVowlFilterChange?: (filters: any) => void;
  hierarchySelectedClass?: OntologyNode | null;
  onHierarchyClassSelect?: (node: OntologyNode | null) => void;
  showClassHierarchy?: boolean;
  onSearchChange?: (searchTerm: string) => void;
  classDistance?: number;
  datatypeDistance?: number;
  onClassDistanceChange?: (distance: number) => void;
  onDatatypeDistanceChange?: (distance: number) => void;
  onPauseLayout?: () => void;
  onResetLayout?: () => void;
  isLayoutPaused?: boolean;
  showFilterSidebar?: boolean;
  showSettings?: boolean;
  // Hierarchy tree callbacks — expand/collapse nodes in the graph from the sidebar tree
  onGraphNodeExpand?: (nodeId: string) => void;
  onGraphNodeCollapse?: (nodeId: string) => void;
  graphExpandedNodeIds?: Set<string>;
  graphVisibleNodeIds?: Set<string>;
  // Focus mode (OntoCode hierarchy-style neighborhood isolation)
  focusedNodeId?: string | null;
  onFocusNode?: (nodeId: string) => void;
  onClearFocus?: () => void;
  // Ontology header metadata (IRI, version, annotations) — optional, read-only display
  ontologyMetadata?: any;
  // Inline hierarchy navigator — pre-rendered content shown above the Class Tree
  // panel when a class is focused (see AdvancedGraphView's hierarchyNavigatorBody).
  // Replaces always-popping-open the floating navigator dialog; that dialog is
  // now opt-in via onPopOutHierarchyNavigator.
  hierarchyNavigatorContent?: React.ReactNode;
  hierarchyNavigatorLabel?: string;
  onCloseHierarchyNavigator?: () => void;
  onPopOutHierarchyNavigator?: () => void;
}

export const GraphViewSidebar: React.FC<GraphViewSidebarProps> = ({
  nodes,
  edges,
  selectedNode,
  onNodeSelect,
  onNodeHighlight,
  filters,
  onFilterChange,
  projectId,
  viewMode = 'force',
  vowlLegend = [],
  onSearchChange,
  classDistance = 50,
  datatypeDistance = 20,
  onClassDistanceChange,
  onDatatypeDistanceChange,
  onPauseLayout,
  onResetLayout,
  isLayoutPaused = false,
  showFilterSidebar = true,
  showSettings = false,
  vowlFilters,
  onVowlFilterChange,
  hierarchySelectedClass,
  onHierarchyClassSelect,
  showClassHierarchy,
  onGraphNodeExpand,
  onGraphNodeCollapse,
  graphExpandedNodeIds,
  graphVisibleNodeIds,
  focusedNodeId,
  onFocusNode,
  onClearFocus,
  ontologyMetadata,
  hierarchyNavigatorContent,
  hierarchyNavigatorLabel,
  onCloseHierarchyNavigator,
  onPopOutHierarchyNavigator
}) => {
  const [sidebarMode, setSidebarMode] = useState<'entities' | 'hierarchy'>('hierarchy');

  // Auto-switch to the Hierarchy tab when a class is newly focused for the
  // navigator — onNodeSelect (which drives hierarchyNavigatorContent) is also
  // wired from the Entities-mode detail panel's "related class" links, so a
  // user browsing Entities who clicks one would otherwise populate content
  // they can't see without manually switching tabs.
  const hadHierarchyContentRef = useRef(false);
  useEffect(() => {
    const hasContent = !!hierarchyNavigatorContent;
    if (hasContent && !hadHierarchyContentRef.current) {
      setSidebarMode('hierarchy');
    }
    hadHierarchyContentRef.current = hasContent;
  }, [hierarchyNavigatorContent]);
  const [entityTab, setEntityTab] = useState<'classes' | 'objectProperties' | 'datatypeProperties' | 'individuals' | 'annotations' | 'datatypes'>('classes');
  const [searchTerm, setSearchTerm] = useState('');
  const [sidebarWidth, setSidebarWidth] = useState(340);
  const [isResizing, setIsResizing] = useState(false);
  
  // Local state for sliders to prevent frequent graph movement
  const [localClassDistance, setLocalClassDistance] = useState(classDistance);
  const [localDatatypeDistance, setLocalDatatypeDistance] = useState(datatypeDistance);

  // Sync local state with props when props change
  React.useEffect(() => {
    setLocalClassDistance(classDistance);
  }, [classDistance]);

  React.useEffect(() => {
    setLocalDatatypeDistance(datatypeDistance);
  }, [datatypeDistance]);

  const [expandedSections, setExpandedSections] = useState({
    filters: true,
    search: true,
    entitySelector: true,
    filterEntities: true,
    entityDetails: true,
    statistics: true,
    vowlLegend: true,
    vowlControls: true
  });

  // Toggle sections
  const toggleSection = (section: keyof typeof expandedSections) => {
    setExpandedSections(prev => ({ ...prev, [section]: !prev[section] }));
  };

  // Handle resize
  const handleMouseDown = (e: React.MouseEvent) => {
    setIsResizing(true);
    e.preventDefault();
  };

  React.useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (!isResizing) return;
      const newWidth = window.innerWidth - e.clientX;
      if (newWidth >= 280 && newWidth <= 600) {
        setSidebarWidth(newWidth);
      }
    };

    const handleMouseUp = () => {
      setIsResizing(false);
    };

    if (isResizing) {
      document.addEventListener('mousemove', handleMouseMove);
      document.addEventListener('mouseup', handleMouseUp);
    }

    return () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };
  }, [isResizing]);

  // Calculate statistics
  const statistics = useMemo(() => {
    const stats = {
      classes: nodes.filter(n => n.type === 'class').length,
      objectProperties: nodes.filter(n => n.type === 'objectProperty').length,
      datatypeProperties: nodes.filter(n => n.type === 'dataProperty').length,
      datatypes: nodes.filter(n => n.type === 'datatype').length,
      individuals: nodes.filter(n => n.type === 'individual').length,
      annotations: nodes.filter(n => n.type === 'annotation').length,
      totalNodes: nodes.length,
      totalEdges: edges.length,
      edgesByType: {} as Record<string, number>
    };

    // Count edges by type
    edges.forEach(edge => {
      const type = edge.type || 'unknown';
      stats.edgesByType[type] = (stats.edgesByType[type] || 0) + 1;
    });

    return stats;
  }, [nodes, edges]);

  // Filter nodes by search term and entity tab
  const filteredNodes = useMemo(() => {
    const typeFilter = (node: OntologyNode) => {
      // First check if this node type is enabled in the filters
      if (!filters.nodeTypes.has(node.type)) return false;
      
      // Then filter by entity tab
      if (entityTab === 'classes') return node.type === 'class';
      if (entityTab === 'objectProperties') return node.type === 'objectProperty';
      if (entityTab === 'datatypeProperties') return node.type === 'dataProperty';
      if (entityTab === 'datatypes') return node.type === 'datatype';
      if (entityTab === 'individuals') return node.type === 'individual';
      if (entityTab === 'annotations') return node.type === 'annotation';
      return true;
    };

    return nodes
      .filter(typeFilter)
      .filter(node => 
        !searchTerm || 
        node.label?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        node.id.toLowerCase().includes(searchTerm.toLowerCase())
      )
      .sort((a, b) => (a.label || a.id).localeCompare(b.label || b.id));
  }, [nodes, entityTab, searchTerm, filters]);

  // Get node icon
  const getNodeIcon = (type: string) => {
    switch (type) {
      case 'class': return <Square size={14} className="node-icon" style={{ color: '#4A90E2' }} />;
      case 'objectProperty': return <Circle size={14} className="node-icon" style={{ color: '#50C878' }} />;
      case 'dataProperty': 
      case 'datatypeProperty': return <Hexagon size={14} className="node-icon" style={{ color: '#F39C12' }} />;
      case 'individual': return <Diamond size={14} className="node-icon" style={{ color: '#E74C3C' }} />;
      default: return <Circle size={14} className="node-icon" style={{ color: '#95A5A6' }} />;
    }
  };

  // Get related entities
  const getRelatedEntities = (node: OntologyNode) => {
    if (!node) return { parents: [], children: [], properties: [], instances: [] };

    const parents = edges
      .filter(e => e.from === node.id && e.type === 'subClassOf')
      .map(e => nodes.find(n => n.id === e.to))
      .filter(Boolean) as OntologyNode[];

    const children = edges
      .filter(e => e.to === node.id && e.type === 'subClassOf')
      .map(e => nodes.find(n => n.id === e.from))
      .filter(Boolean) as OntologyNode[];

    const properties = edges
      .filter(e => (e.from === node.id || e.to === node.id) && (e.type === 'domain' || e.type === 'range' || e.type === 'propertyRelation'))
      .map(e => nodes.find(n => n.id === (e.from === node.id ? e.to : e.from)))
      .filter(Boolean) as OntologyNode[];
    
    // Remove duplicates
    const uniqueProperties = Array.from(new Set(properties.map(p => p.id)))
      .map(id => properties.find(p => p.id === id)!)
      .sort((a, b) => (a.label || a.id).localeCompare(b.label || b.id));

    const instances = edges
      .filter(e => e.to === node.id && e.type === 'instanceOf')
      .map(e => nodes.find(n => n.id === e.from))
      .filter(Boolean) as OntologyNode[];

    return { parents, children, properties: uniqueProperties, instances };
  };

  // NOTE: The hierarchy tab is now rendered by <ClassHierarchyPanel /> below.
  // The previous in-component class-hierarchy index, expansion state and
  // recursive renderers have been removed — the panel handles all of that
  // (multi-parent, cycle-safe, virtualized, keyboard-navigable).


  // Get available node types from current graph (excluding properties)
  const availableNodeTypes = useMemo(() => {
    const types = new Set<string>();
    nodes.forEach(node => {
      // Exclude objectProperty and dataProperty from Node Types
      if (node.type && node.type !== 'objectProperty' && node.type !== 'dataProperty') {
        types.add(node.type);
      }
    });
    return Array.from(types).sort();
  }, [nodes]);

  // Get available edge types from current graph
  const availableEdgeTypes = useMemo(() => {
    const types = new Set<string>();
    edges.forEach(edge => {
      if (edge.type) types.add(edge.type);
    });
    return Array.from(types).sort();
  }, [edges]);

  // Node type display names
  const nodeTypeLabels: Record<string, string> = {
    'class': 'Classes',
    'individual': 'Individuals',
    'objectProperty': 'Object Properties',
    'dataProperty': 'Datatype Properties',
    'datatypeProperty': 'Datatype Properties',
    'datatype': 'Datatypes',
    'annotation': 'Annotations'
  };

  // Edge type display names
  const edgeTypeLabels: Record<string, string> = {
    'subClassOf': 'SubClass Of',
    'instanceOf': 'Instance Of',
    'domain': 'Domain',
    'range': 'Range',
    'propertyRelation': 'Property Relation',
    'equivalentClass': 'Equivalent Class',
    'disjointWith': 'Disjoint With',
    'inverseOf': 'Inverse Of',
    'custom': 'Custom'
  };

//   const toggleSection = (section: string) => {
//     setExpandedSections(prev => {
//       const newSet = new Set(prev);
//       if (newSet.has(section)) {
//         newSet.delete(section);
//       } else {
//         newSet.add(section);
//       }
//       return newSet;
//     });
//   };

  const toggleNodeType = (type: string) => {
    const newTypes = new Set(filters.nodeTypes);
    if (newTypes.has(type)) {
      newTypes.delete(type);
    } else {
      newTypes.add(type);
    }
    onFilterChange({ ...filters, nodeTypes: newTypes });
  };

  const toggleEdgeType = (type: string) => {
    const newTypes = new Set(filters.edgeTypes);
    if (newTypes.has(type)) {
      newTypes.delete(type);
    } else {
      newTypes.add(type);
    }
    onFilterChange({ ...filters, edgeTypes: newTypes });
  };


  return (
    <div style={{...styles.sidebar, width: `${sidebarWidth}px`}}>
      {/* Resize Handle */}
      <div
        style={styles.resizeHandle}
        onMouseDown={handleMouseDown}
        title="Drag to resize"
      />
      
      {/* Scrollable Content */}
      <div style={styles.scrollableContent}>

      {/* Mode Toggle: Hierarchy vs Entities */}
      <div style={{ display: 'flex', borderBottom: '1px solid var(--border)', backgroundColor: 'var(--surface-2)' }}>
        <button
          onClick={() => setSidebarMode('hierarchy')}
          style={{
            flex: 1,
            padding: '8px 4px',
            fontSize: '12px',
            fontWeight: sidebarMode === 'hierarchy' ? 600 : 400,
            color: sidebarMode === 'hierarchy' ? 'var(--accent)' : 'var(--text-secondary)',
            backgroundColor: sidebarMode === 'hierarchy' ? 'var(--surface-1)' : 'transparent',
            border: 'none',
            borderBottom: sidebarMode === 'hierarchy' ? '2px solid var(--accent)' : '2px solid transparent',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '4px'
          }}
        >
          <GitBranch size={13} /> Class Tree
        </button>
        <button
          onClick={() => setSidebarMode('entities')}
          style={{
            flex: 1,
            padding: '8px 4px',
            fontSize: '12px',
            fontWeight: sidebarMode === 'entities' ? 600 : 400,
            color: sidebarMode === 'entities' ? 'var(--accent)' : 'var(--text-secondary)',
            backgroundColor: sidebarMode === 'entities' ? 'var(--surface-1)' : 'transparent',
            border: 'none',
            borderBottom: sidebarMode === 'entities' ? '2px solid var(--accent)' : '2px solid transparent',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '4px'
          }}
        >
          <Layers size={13} /> Entities
        </button>
      </div>

      {/* === HIERARCHY MODE === */}
      {/*
        * The hierarchy tab is rendered by the production-grade
        * <ClassHierarchyPanel /> component, which provides:
        *   - virtualized rendering (handles 100k+ classes)
        *   - multi-parent + cycle-safe traversal
        *   - asserted/inferred/all toggle
        *   - sub/super-class direction toggle
        *   - full keyboard navigation (arrow keys, F2, Del, etc.)
        *   - working right-click context menu
        *   - badges for child count, instance count and multi-parent classes
        */}
      {sidebarMode === 'hierarchy' && (
        <div style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
          {focusedNodeId && (() => {
            const focusNode = nodes.find(n => n.id === focusedNodeId);
            return (
              <div style={{
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                padding: '6px 10px',
                background: '#ede9fe',
                borderBottom: '1px solid #c4b5fd',
                fontSize: 11,
                color: '#4c1d95',
                flexShrink: 0
              }}>
                <span title="Focus mode" aria-hidden>🎯</span>
                <span
                  style={{
                    fontWeight: 600,
                    flex: 1,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap'
                  }}
                >
                  {focusNode?.label || focusedNodeId.split(/[#/]/).pop()}
                </span>
                <button
                  type="button"
                  onClick={() => onClearFocus?.()}
                  style={{
                    padding: '2px 8px',
                    background: '#7c3aed',
                    color: '#fff',
                    border: 'none',
                    borderRadius: 4,
                    cursor: 'pointer',
                    fontSize: 10,
                    fontWeight: 600
                  }}
                  title="Exit focus mode"
                >
                  Clear
                </button>
              </div>
            );
          })()}
          {hierarchyNavigatorContent && (
            <div
              style={{
                flexShrink: 0,
                maxHeight: '45%',
                overflow: 'auto',
                display: 'flex',
                flexDirection: 'column',
                borderBottom: '2px solid var(--border, #e5e7eb)'
              }}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  padding: '6px 10px',
                  background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
                  color: '#fff',
                  fontSize: 12,
                  fontWeight: 600,
                  flexShrink: 0
                }}
              >
                <span
                  style={{
                    flex: 1,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap'
                  }}
                  title={hierarchyNavigatorLabel ? `Editing: ${hierarchyNavigatorLabel}` : 'Editing'}
                >
                  Editing: {hierarchyNavigatorLabel || '…'}
                </span>
                <button
                  type="button"
                  onClick={() => onPopOutHierarchyNavigator?.()}
                  style={{
                    background: 'transparent',
                    border: 'none',
                    color: '#fff',
                    cursor: 'pointer',
                    padding: '2px 4px',
                    display: 'flex',
                    alignItems: 'center',
                    borderRadius: 4
                  }}
                  title="Pop out as floating window"
                >
                  <ExternalLink size={14} />
                </button>
                <button
                  type="button"
                  onClick={() => onCloseHierarchyNavigator?.()}
                  style={{
                    background: 'transparent',
                    border: 'none',
                    color: '#fff',
                    cursor: 'pointer',
                    padding: '2px 4px',
                    display: 'flex',
                    alignItems: 'center',
                    borderRadius: 4
                  }}
                  title="Close"
                >
                  <X size={14} />
                </button>
              </div>
              {hierarchyNavigatorContent}
            </div>
          )}
          <div style={{ flex: 1, minHeight: 0 }}>
            <ClassHierarchyPanel
              nodes={nodes}
              edges={edges}
              selectedNodeId={selectedNode?.id ?? null}
              onSelect={(node) => onNodeSelect(node)}
              onActivate={(node) => {
                onNodeSelect(node);
                onGraphNodeExpand?.(node.id);
              }}
              onShowInGraph={(node) => {
                onNodeSelect(node);
                if (graphExpandedNodeIds && !graphExpandedNodeIds.has(node.id)) {
                  onGraphNodeExpand?.(node.id);
                }
              }}
              onFocusInGraph={(node) => onFocusNode?.(node.id)}
              onShowSubclasses={(node) => onGraphNodeExpand?.(node.id)}
              onShowSuperclasses={(node) => onNodeSelect(node)}
              onShowIndividuals={(node) => onGraphNodeExpand?.(node.id)}
              readonly
            />
          </div>
        </div>
      )}

      {/* === ENTITIES MODE === */}
      {sidebarMode === 'entities' && (
      <>
      {/* Top Filters (like VOWL) - Only show when filter button is clicked */}
      {showFilterSidebar && (
      <div style={styles.accordionSection}>
        <div 
          className="accordion-header"
          style={styles.accordionHeader}
          onClick={() => toggleSection('filters')}
        >
          <span style={styles.accordionTitle}>GRAPH FILTERS</span>
          {expandedSections.filters ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        </div>
        {expandedSections.filters && (
          <div style={styles.topFilters}>
            {/* Node Type Filters - Dynamic based on current graph */}
            <div style={styles.filterCategory}>
              <div style={styles.filterCategoryTitle}>
                Node Types ({availableNodeTypes.length})
              </div>
              {availableNodeTypes.length === 0 ? (
                <div style={{ padding: '8px', color: '#9ca3af', fontSize: '12px' }}>
                  No nodes in graph
                </div>
              ) : (
                availableNodeTypes.map(type => (
                  <label key={type} style={styles.topFilterLabel}>
                    <input
                      type="checkbox"
                      checked={filters.nodeTypes.has(type)}
                      onChange={() => toggleNodeType(type)}
                      style={styles.topFilterCheckbox}
                    />
                    <span>{nodeTypeLabels[type] || type}</span>
                    <span style={{ marginLeft: 'auto', color: '#9ca3af', fontSize: '11px' }}>
                      ({nodes.filter(n => n.type === type).length})
                    </span>
                  </label>
                ))
              )}
            </div>

            {/* Edge Type Filters - Dynamic based on current graph */}
            <div style={styles.filterCategory}>
              <div style={styles.filterCategoryTitle}>
                Relationship Types ({availableEdgeTypes.length})
              </div>
              {availableEdgeTypes.length === 0 ? (
                <div style={{ padding: '8px', color: '#9ca3af', fontSize: '12px' }}>
                  No relationships in graph
                </div>
              ) : (
                availableEdgeTypes.map(type => (
                  <label key={type} style={styles.topFilterLabel}>
                    <input
                      type="checkbox"
                      checked={filters.edgeTypes.has(type)}
                      onChange={() => toggleEdgeType(type)}
                      style={styles.topFilterCheckbox}
                    />
                    <span>{edgeTypeLabels[type] || type}</span>
                    <span style={{ marginLeft: 'auto', color: '#9ca3af', fontSize: '11px' }}>
                      ({edges.filter(e => e.type === type).length})
                    </span>
                  </label>
                ))
              )}
            </div>

            {/* Property Visibility Filters (VOWL) */}
            {(viewMode === 'vowl' || viewMode === 'ontograph') && (
              <div style={styles.filterCategory}>
                <div style={styles.filterCategoryTitle}>
                  Property Visibility
                </div>
                <label style={styles.topFilterLabel}>
                  <input
                    type="checkbox"
                    checked={vowlFilters?.showObjectProperties ?? true}
                    onChange={(e) => vowlFilters && onVowlFilterChange?.({ ...vowlFilters, showObjectProperties: e.target.checked })}
                    style={styles.topFilterCheckbox}
                  />
                  <span>Object Properties</span>
                  <span style={{ marginLeft: 'auto', color: '#9ca3af', fontSize: '11px' }}>
                    ({nodes.filter(n => n.type === 'objectProperty').length})
                  </span>
                </label>
                <label style={styles.topFilterLabel}>
                  <input
                    type="checkbox"
                    checked={vowlFilters?.showDataProperties ?? true}
                    onChange={(e) => vowlFilters && onVowlFilterChange?.({ ...vowlFilters, showDataProperties: e.target.checked })}
                    style={styles.topFilterCheckbox}
                  />
                  <span>Data Properties</span>
                  <span style={{ marginLeft: 'auto', color: '#9ca3af', fontSize: '11px' }}>
                    ({nodes.filter(n => n.type === 'dataProperty').length})
                  </span>
                </label>
                <label style={styles.topFilterLabel}>
                  <input
                    type="checkbox"
                    checked={vowlFilters?.showSubClassOf ?? true}
                    onChange={(e) => vowlFilters && onVowlFilterChange?.({ ...vowlFilters, showSubClassOf: e.target.checked })}
                    style={styles.topFilterCheckbox}
                  />
                  <span>SubClass Relationships</span>
                  <span style={{ marginLeft: 'auto', color: '#9ca3af', fontSize: '11px' }}>
                    ({edges.filter(e => e.type === 'subClassOf').length})
                  </span>
                </label>
                <label style={styles.topFilterLabel}>
                  <input
                    type="checkbox"
                    checked={vowlFilters?.showFunctionalProperties ?? true}
                    onChange={(e) => vowlFilters && onVowlFilterChange?.({ ...vowlFilters, showFunctionalProperties: e.target.checked })}
                    style={styles.topFilterCheckbox}
                  />
                  <span>Functional Properties</span>
                </label>
                <div style={{ ...styles.filterCategoryTitle, marginTop: '12px' }}>
                  Class Visibility
                </div>
                <label style={styles.topFilterLabel}>
                  <input
                    type="checkbox"
                    checked={vowlFilters?.showInternalClasses ?? true}
                    onChange={(e) => vowlFilters && onVowlFilterChange?.({ ...vowlFilters, showInternalClasses: e.target.checked })}
                    style={styles.topFilterCheckbox}
                  />
                  <span>Internal Classes</span>
                </label>
                <label style={styles.topFilterLabel}>
                  <input
                    type="checkbox"
                    checked={vowlFilters?.showExternalClasses ?? true}
                    onChange={(e) => vowlFilters && onVowlFilterChange?.({ ...vowlFilters, showExternalClasses: e.target.checked })}
                    style={styles.topFilterCheckbox}
                  />
                  <span>External Classes</span>
                </label>
                <label style={styles.topFilterLabel}>
                  <input
                    type="checkbox"
                    checked={vowlFilters?.showDatatypes ?? true}
                    onChange={(e) => vowlFilters && onVowlFilterChange?.({ ...vowlFilters, showDatatypes: e.target.checked })}
                    style={styles.topFilterCheckbox}
                  />
                  <span>Datatypes</span>
                </label>
              </div>
            )}
          </div>
        )}
      </div>
      )}

      {/* Combined Search, Selector, and Entities Section */}
      <div style={styles.accordionSection}>
        <div 
          className="accordion-header"
          style={styles.accordionHeader}
          onClick={() => toggleSection('filterEntities')}
        >
          <span style={styles.accordionTitle}>ENTITIES</span>
          {expandedSections.filterEntities ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        </div>
        {expandedSections.filterEntities && (
          <div style={styles.combinedSection}>
            {/* Search Bar */}
            <div style={styles.searchInputContainer}>
              <Search size={16} style={styles.searchIcon} />
              <input
                type="text"
                placeholder="Search entities..."
                value={searchTerm}
                onChange={(e) => {
                  setSearchTerm(e.target.value);
                  onSearchChange?.(e.target.value);
                }}
                style={styles.searchInput}
              />
              {searchTerm && (
                <X 
                  size={16} 
                  style={styles.searchClear}
                  onClick={() => {
                    setSearchTerm('');
                    onSearchChange?.('');
                  }}
                />
              )}
            </div>
            
            {/* Entity Selector Tabs */}
            <div style={styles.entityTabs}>
              <button
                style={{...styles.entityTab, ...(entityTab === 'classes' ? styles.activeEntityTab : {})}}
                onClick={() => setEntityTab('classes')}
              >
                Classes ({statistics.classes})
              </button>
              <button
                style={{...styles.entityTab, ...(entityTab === 'individuals' ? styles.activeEntityTab : {})}}
                onClick={() => setEntityTab('individuals')}
              >
                Indiv. ({statistics.individuals})
              </button>
              <button
                style={{...styles.entityTab, ...(entityTab === 'objectProperties' ? styles.activeEntityTab : {})}}
                onClick={() => setEntityTab('objectProperties')}
              >
                Obj. ({statistics.objectProperties})
              </button>
              <button
                style={{...styles.entityTab, ...(entityTab === 'datatypeProperties' ? styles.activeEntityTab : {})}}
                onClick={() => setEntityTab('datatypeProperties')}
              >
                Data ({statistics.datatypeProperties})
              </button>
              <button
                style={{...styles.entityTab, ...(entityTab === 'datatypes' ? styles.activeEntityTab : {})}}
                onClick={() => setEntityTab('datatypes')}
              >
                Type ({statistics.datatypes})
              </button>
              <button
                style={{...styles.entityTab, ...(entityTab === 'annotations' ? styles.activeEntityTab : {})}}
                onClick={() => setEntityTab('annotations')}
              >
                Anno. ({statistics.annotations})
              </button>
            </div>

            {/* Entity List */}
            <div style={styles.entityList}>
              {filteredNodes.map(node => {
                // Determine if class is Thing or external
                const isThing = node.label === 'Thing' || node.id.includes('owl#Thing');
                const isExternal = node.label?.includes('external') || ['Item', 'UserAccount', 'Concept'].includes(node.label || '');
                
                // Get color based on node type (matching graph visualization)
                let nodeColor = '#667eea'; // default class color
                if (node.type === 'class') {
                  if (viewMode === 'vowl') {
                    if (isThing) nodeColor = '#ffffff';
                    else if (isExternal) nodeColor = '#4682b4';
                    else nodeColor = '#acd5f2';
                  } else if (viewMode === 'force') {
                    nodeColor = '#FFE4B5'; // Light peach for force mode classes
                  } else if (viewMode === 'ontograph') {
                    nodeColor = '#E8EAF6'; // Light purple-grey for OntoGraph
                  } else {
                    nodeColor = '#667eea';
                  }
                } else if (node.type === 'objectProperty') {
                  nodeColor = '#06b6d4';
                } else if (node.type === 'dataProperty') {
                  nodeColor = '#ec4899';
                } else if (node.type === 'individual') {
                  nodeColor = viewMode === 'force' ? '#a78bfa' : '#10b981';
                } else if (node.type === 'datatype') {
                  nodeColor = viewMode === 'vowl' ? '#FFD9B3' : (viewMode === 'force' ? '#FFFFFF' : '#FFA500');
                } else if (node.type === 'annotation') {
                  nodeColor = viewMode === 'vowl' ? '#e8d5f2' : '#8b5cf6';
                }
                
                return (
                  <div
                    key={node.id}
                    className="entity-item"
                    style={{
                      ...styles.entityItem,
                      ...(selectedNode?.id === node.id ? styles.selectedEntity : {})
                    }}
                    onClick={() => onNodeSelect(node)}
                    onMouseEnter={() => onNodeHighlight(node.id)}
                    onMouseLeave={() => onNodeHighlight(null)}
                  >
                    {/* Type-specific shape matching graph visualization */}
                    {node.type === 'class' ? (
                      // Classes: Circle for VOWL/OntoGraph, Ellipse for Force mode, Rectangle for OntoGraph
                      viewMode === 'force' ? (
                        <div style={{
                          width: '24px',
                          height: '14px',
                          borderRadius: '50%',
                          backgroundColor: nodeColor,
                          border: '2px solid #000000',
                          flexShrink: 0,
                          marginRight: '8px'
                        }} />
                      ) : viewMode === 'ontograph' ? (
                        <div style={{
                          width: '22px',
                          height: '12px',
                          borderRadius: '3px',
                          backgroundColor: nodeColor,
                          border: '2px solid #5E35B1',
                          flexShrink: 0,
                          marginRight: '8px'
                        }} />
                      ) : (
                        // VOWL: Circle (solid border for normal, dashed for Thing)
                        <div style={{
                          width: '16px',
                          height: '16px',
                          borderRadius: '50%',
                          backgroundColor: nodeColor,
                          border: isThing ? '2px dashed #1f2937' : '2px solid #1f2937',
                          flexShrink: 0,
                          marginRight: '8px'
                        }} />
                      )
                    ) : node.type === 'objectProperty' ? (
                      // Object Properties: Circle (cyan)
                      <div style={{
                        width: '16px',
                        height: '16px',
                        borderRadius: '50%',
                        backgroundColor: nodeColor,
                        border: '2px solid #1f2937',
                        flexShrink: 0,
                        marginRight: '8px'
                      }} />
                    ) : node.type === 'dataProperty' ? (
                      // Data Properties: Square (pink)
                      <div style={{
                        width: '14px',
                        height: '14px',
                        backgroundColor: nodeColor,
                        border: '2px solid #1f2937',
                        flexShrink: 0,
                        borderRadius: '3px',
                        marginRight: '8px'
                      }} />
                    ) : node.type === 'datatype' ? (
                      // Datatypes: Rounded Rectangle - white rectangle for force, dashed for VOWL
                      <div style={{
                        width: '24px',
                        height: '12px',
                        backgroundColor: nodeColor,
                        border: viewMode === 'vowl' ? '2px dashed #1f2937' : (viewMode === 'force' ? '1px solid #999999' : '2px solid #1f2937'),
                        flexShrink: 0,
                        borderRadius: viewMode === 'force' ? '3px' : '6px',
                        marginRight: '8px'
                      }} />
                    ) : node.type === 'individual' ? (
                      // Individuals: Rectangle - purple for force mode, green otherwise
                      <div style={{
                        width: viewMode === 'force' ? '28px' : '20px',
                        height: '12px',
                        backgroundColor: nodeColor,
                        border: viewMode === 'force' ? '2px solid #000000' : '2px solid #1f2937',
                        flexShrink: 0,
                        borderRadius: '3px',
                        marginRight: '8px'
                      }} />
                    ) : node.type === 'annotation' ? (
                      // Annotation Properties: Hexagon (light purple)
                      <svg width="18" height="18" viewBox="-9 -9 18 18" style={{ flexShrink: 0, marginRight: '8px' }}>
                        <polygon
                          points="0,-7 6,-3.5 6,3.5 0,7 -6,3.5 -6,-3.5"
                          fill={nodeColor}
                          stroke="#1f2937"
                          strokeWidth="2"
                        />
                      </svg>
                    ) : (
                      // Default: Circle
                      <div style={{
                        width: '16px',
                        height: '16px',
                        borderRadius: '50%',
                        backgroundColor: nodeColor,
                        border: '2px solid #1f2937',
                        flexShrink: 0,
                        marginRight: '8px'
                      }} />
                    )}
                    <span style={styles.entityLabel}>{node.label || node.id}</span>
                  </div>
                );
              })}
              {filteredNodes.length === 0 && (
                <div style={styles.emptyState}>
                  {searchTerm ? 'No entities found' : 'Select a class or property to view details'}
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Ontology Info Section — read-only header metadata */}
      {ontologyMetadata && (() => {
        const m = ontologyMetadata as any;
        const ontoIRI = m.ontologyIRI || m.ontologyIri || m.iri || m.baseIRI || m.baseIri || m.defaultNamespace;
        const versionIRI = m.versionIRI || m.versionIri || m.version;
        const importsRaw = m.imports || m.importedOntologies || [];
        const imports: string[] = Array.isArray(importsRaw) ? importsRaw : [];
        const annotations = m.annotations || m.ontologyAnnotations || {};
        const annotationKeys = annotations && typeof annotations === 'object' ? Object.keys(annotations) : [];
        const hasAny = ontoIRI || versionIRI || imports.length > 0 || annotationKeys.length > 0;
        if (!hasAny) return null;
        return (
          <div style={styles.accordionSection}>
            <div style={styles.entityTitleHeader}>
              <h3 style={styles.entityTitle}>Ontology Info</h3>
            </div>
            <div style={styles.entityDetailsTable}>
              {ontoIRI && (
                <div style={styles.entityDetailRow}>
                  <div style={styles.entityDetailLabel}>Ontology IRI</div>
                  <div style={styles.entityDetailValue}>
                    <a href={ontoIRI} target="_blank" rel="noopener noreferrer" style={styles.iriLink}>
                      {String(ontoIRI).length > 50 ? `...${String(ontoIRI).slice(-47)}` : String(ontoIRI)}
                    </a>
                  </div>
                </div>
              )}
              {versionIRI && (
                <div style={styles.entityDetailRow}>
                  <div style={styles.entityDetailLabel}>Version IRI</div>
                  <div style={styles.entityDetailValue}>{String(versionIRI)}</div>
                </div>
              )}
              {imports.length > 0 && (
                <div style={styles.entityDetailRow}>
                  <div style={styles.entityDetailLabel}>Imports ({imports.length})</div>
                  <div style={styles.entityDetailValue}>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                      {imports.slice(0, 10).map((imp, idx) => (
                        <a key={idx} href={imp} target="_blank" rel="noopener noreferrer" style={{ ...styles.iriLink, fontSize: 11 }}>
                          {String(imp).length > 60 ? `...${String(imp).slice(-57)}` : String(imp)}
                        </a>
                      ))}
                      {imports.length > 10 && (
                        <span style={{ fontSize: 11, color: 'var(--text-secondary)', fontStyle: 'italic' }}>… and {imports.length - 10} more</span>
                      )}
                    </div>
                  </div>
                </div>
              )}
              {annotationKeys.length > 0 && (
                <div style={styles.entityDetailRow}>
                  <div style={styles.entityDetailLabel}>Annotations</div>
                  <div style={styles.entityDetailValue}>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                      {annotationKeys.slice(0, 8).map((k) => (
                        <div key={k} style={{ fontSize: 12 }}>
                          <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{k}:</span>{' '}
                          <span style={{ color: 'var(--text-secondary)' }}>{String((annotations as any)[k]).slice(0, 120)}</span>
                        </div>
                      ))}
                      {annotationKeys.length > 8 && (
                        <span style={{ fontSize: 11, color: 'var(--text-secondary)', fontStyle: 'italic' }}>… and {annotationKeys.length - 8} more</span>
                      )}
                    </div>
                  </div>
                </div>
              )}
            </div>
          </div>
        );
      })()}

      {/* Entity Details Section (like VOWL) */}
      {selectedNode && (
        <div style={styles.accordionSection}>
          {/* Entity Title Header */}
          <div style={styles.entityTitleHeader}>
            <h3 style={styles.entityTitle}>{selectedNode.label || selectedNode.id}</h3>
          </div>
          
          {/* Entity Details Table */}
          <div style={styles.entityDetailsTable}>
            {/* Type */}
            <div style={styles.entityDetailRow}>
              <div style={styles.entityDetailLabel}>Type</div>
              <div style={styles.entityDetailValue}>
                <span style={styles.typeBadge}>
                  {selectedNode.type}
                </span>
              </div>
            </div>

            {/* Name/Label */}
            <div style={styles.entityDetailRow}>
              <div style={styles.entityDetailLabel}>Name</div>
              <div style={styles.entityDetailValue}>
                {selectedNode.label || selectedNode.id}
              </div>
            </div>

            {/* IRI */}
            {selectedNode.iri && (
              <div style={styles.entityDetailRow}>
                <div style={styles.entityDetailLabel}>IRI</div>
                <div style={styles.entityDetailValue}>
                  <a 
                    href={selectedNode.iri} 
                    target="_blank" 
                    rel="noopener noreferrer"
                    style={styles.iriLink}
                  >
                    {selectedNode.iri.length > 50 
                      ? `...${selectedNode.iri.slice(-47)}` 
                      : selectedNode.iri}
                  </a>
                </div>
              </div>
            )}

            {/* Description */}
            {selectedNode.description && (
              <div style={styles.entityDetailRow}>
                <div style={styles.entityDetailLabel}>Description</div>
                <div style={styles.entityDetailValue}>
                  {selectedNode.description}
                </div>
              </div>
            )}

            {/* Property Characteristics (OWL: Functional, Symmetric, Transitive, etc.) */}
            {(selectedNode.type === 'objectProperty' || selectedNode.type === 'dataProperty') && (() => {
              const m: any = (selectedNode as any).metadata || {};
              const chars: string[] = [];
              if (m.functional) chars.push('Functional');
              if (m.inverseFunctional) chars.push('Inverse Functional');
              if (m.symmetric) chars.push('Symmetric');
              if (m.asymmetric) chars.push('Asymmetric');
              if (m.transitive) chars.push('Transitive');
              if (m.reflexive) chars.push('Reflexive');
              if (m.irreflexive) chars.push('Irreflexive');
              if (chars.length === 0) return null;
              return (
                <div style={styles.entityDetailRow}>
                  <div style={styles.entityDetailLabel}>Characteristics</div>
                  <div style={styles.entityDetailValue}>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                      {chars.map(c => (
                        <span key={c} style={{
                          background: '#eef2ff',
                          color: '#3730a3',
                          padding: '2px 8px',
                          borderRadius: 12,
                          fontSize: 11,
                          fontWeight: 600,
                          border: '1px solid #c7d2fe'
                        }}>{c}</span>
                      ))}
                    </div>
                  </div>
                </div>
              );
            })()}

            {/* Connections */}
            <div style={styles.entityDetailRow}>
              <div style={styles.entityDetailLabel}>Connections</div>
              <div style={styles.entityDetailValue}>
                {edges.filter(e => e.from === selectedNode.id || e.to === selectedNode.id).length} edges
              </div>
            </div>

            {/* Related Entities */}
            {(() => {
              const related = getRelatedEntities(selectedNode);
              const hasRelated = related.parents.length > 0 || related.children.length > 0 || 
                                related.properties.length > 0 || related.instances.length > 0;
              
              if (!hasRelated) return null;

              return (
                <>
                  {/* Superclasses */}
                  {related.parents.length > 0 && (
                    <div style={styles.entityDetailRow}>
                      <div style={styles.entityDetailLabel}>Superclasses</div>
                      <div style={styles.entityDetailValue}>
                        <div style={styles.relatedEntityList}>
                          {related.parents.map(parent => (
                            <div 
                              key={parent.id}
                              className="related-entity-badge"
                              style={styles.relatedEntityBadge}
                              onClick={() => onNodeSelect(parent)}
                              title="Click to select"
                            >
                              {parent.label || parent.id}
                            </div>
                          ))}
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Subclasses */}
                  {related.children.length > 0 && (
                    <div style={styles.entityDetailRow}>
                      <div style={styles.entityDetailLabel}>Subclasses</div>
                      <div style={styles.entityDetailValue}>
                        <div style={styles.relatedEntityList}>
                          {related.children.map(child => (
                            <div 
                              key={child.id}
                              className="related-entity-badge"
                              style={styles.relatedEntityBadge}
                              onClick={() => onNodeSelect(child)}
                              title="Click to select"
                            >
                              {child.label || child.id}
                            </div>
                          ))}
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Properties */}
                  {related.properties.length > 0 && (
                    <div style={styles.entityDetailRow}>
                      <div style={styles.entityDetailLabel}>Properties</div>
                      <div style={styles.entityDetailValue}>
                        <div style={styles.relatedEntityList}>
                          {related.properties.map(prop => (
                            <div 
                              key={prop.id}
                              className="related-entity-badge"
                              style={styles.relatedEntityBadge}
                              onClick={() => onNodeSelect(prop)}
                              title="Click to select"
                            >
                              {prop.label || prop.id}
                            </div>
                          ))}
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Instances */}
                  {related.instances.length > 0 && (
                    <div style={styles.entityDetailRow}>
                      <div style={styles.entityDetailLabel}>Instances</div>
                      <div style={styles.entityDetailValue}>
                        <div style={styles.relatedEntityList}>
                          {related.instances.map(instance => (
                            <div 
                              key={instance.id}
                              className="related-entity-badge"
                              style={styles.relatedEntityBadge}
                              onClick={() => onNodeSelect(instance)}
                              title="Click to select"
                            >
                              {instance.label || instance.id}
                            </div>
                          ))}
                        </div>
                      </div>
                    </div>
                  )}
                </>
              );
            })()}

            {/* Notes - if available */}
            {(selectedNode as any).notes && (
              <div style={styles.entityDetailRow}>
                <div style={styles.entityDetailLabel}>Notes</div>
                <div style={{...styles.entityDetailValue, fontSize: '12px', color: '#5f6368', fontStyle: 'italic'}}>
                  {(selectedNode as any).notes}
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Legend Section - Always visible with comprehensive node and edge types */}
      {vowlLegend.length > 0 && (
        <div style={styles.accordionSection}>
          <div 
            className="accordion-header"
            style={styles.accordionHeader}
            onClick={() => toggleSection('vowlLegend')}
          >
            <span style={styles.accordionTitle}>{viewMode === 'vowl' ? 'VOWL NOTATION' : 'LEGEND'}</span>
            {expandedSections.vowlLegend ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
          </div>
          {expandedSections.vowlLegend && (
            <div style={styles.vowlLegendSection}>
              {/* Node Types */}
              <div style={styles.legendCategory}>Node Types</div>
              {(() => {
                const nodeItems = vowlLegend.filter(item => item.type === 'node');
                console.log('[Sidebar Legend] Rendering node items:', nodeItems.length, nodeItems.map(i => ({name: i.name, nodeType: i.nodeType, color: i.color})));
                return nodeItems.map((item) => (
                  <div key={`node-${item.nodeType}-${item.name}`} style={styles.vowlLegendItem}>
                  {/* Render shape based on node type and name - matching graph visualization */}
                  {item.nodeType === 'class' ? (
                    // Classes: Circle for VOWL/OntoGraph, Ellipse indicator for Force mode
                    viewMode === 'force' && item.name.includes('Ellipse') ? (
                      // Force mode: Ellipse shape indicator
                      <div style={{
                        width: '28px',
                        height: '16px',
                        borderRadius: '50%',
                        backgroundColor: item.color || '#FFE4B5',
                        border: '2px solid #000000',
                        flexShrink: 0
                      }} />
                    ) : viewMode === 'ontograph' ? (
                      // OntoGraph mode: Rectangle with rounded corners and 'C' icon
                      <div style={{
                        width: '32px',
                        height: '18px',
                        borderRadius: '3px',
                        backgroundColor: item.color || '#FFF9C4',
                        border: '1px solid #9E9E9E',
                        flexShrink: 0,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        position: 'relative',
                        overflow: 'hidden'
                      }}>
                        <div style={{
                          position: 'absolute',
                          left: 0,
                          top: 0,
                          bottom: 0,
                          width: '10px',
                          backgroundColor: '#5E35B1',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          fontSize: '8px',
                          fontWeight: 'bold',
                          color: '#fff'
                        }}>C</div>
                      </div>
                    ) : (
                      // VOWL mode: Circle (solid border for normal classes, dashed for Thing)
                      <div style={{
                        width: '20px',
                        height: '20px',
                        borderRadius: '50%',
                        backgroundColor: item.color || '#acd5f2',
                        border: item.name.includes('Thing') ? '2px dashed #1f2937' : '2px solid #1f2937',
                        flexShrink: 0
                      }} />
                    )
                  ) : item.nodeType === 'objectProperty' ? (
                    // Object Properties: Circle (green)
                    <div style={{
                      width: '20px',
                      height: '20px',
                      borderRadius: '50%',
                      backgroundColor: item.color || '#50C878',
                      border: '2px solid #1f2937',
                      flexShrink: 0
                    }} />
                  ) : item.nodeType === 'dataProperty' || item.nodeType === 'datatypeProperty' ? (
                    // Data Properties: Square (pink)
                    <div style={{
                      width: '18px',
                      height: '18px',
                      backgroundColor: item.color || '#F39C12',
                      border: '2px solid #1f2937',
                      flexShrink: 0,
                      borderRadius: '3px'
                    }} />
                  ) : item.nodeType === 'individual' ? (
                    // Individuals: Rectangle (all modes)
                    viewMode === 'ontograph' ? (
                      <div style={{
                        width: '32px',
                        height: '18px',
                        borderRadius: '3px',
                        backgroundColor: item.color || '#E1F5FE',
                        border: '1px solid #9E9E9E',
                        flexShrink: 0,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        position: 'relative',
                        overflow: 'hidden'
                      }}>
                        <div style={{
                          position: 'absolute',
                          left: 0,
                          top: 0,
                          bottom: 0,
                          width: '10px',
                          backgroundColor: '#0288D1',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          fontSize: '8px',
                          fontWeight: 'bold',
                          color: '#fff'
                        }}>I</div>
                      </div>
                    ) : (
                      <div style={{
                        width: viewMode === 'force' ? '32px' : '28px',
                        height: '16px',
                        backgroundColor: item.color || (viewMode === 'force' ? '#a78bfa' : '#E74C3C'),
                        border: viewMode === 'force' ? '2px solid #000000' : '2px solid #1f2937',
                        flexShrink: 0,
                        borderRadius: '4px'
                      }} />
                    )
                  ) : item.nodeType === 'datatype' ? (
                    // Datatypes: Rounded Rectangle - white for force mode, dashed for VOWL
                    <div style={{
                      width: '32px',
                      height: '16px',
                      backgroundColor: item.color || (viewMode === 'force' ? '#FFFFFF' : '#FFD9B3'),
                      border: viewMode === 'vowl' ? '2px dashed #1f2937' : (viewMode === 'force' ? '1px solid #999999' : '2px solid #1f2937'),
                      flexShrink: 0,
                      borderRadius: viewMode === 'force' ? '3px' : '8px'
                    }} />
                  ) : item.nodeType === 'annotation' ? (
                    // Annotation Properties: Hexagon (light purple)
                    <svg width="24" height="24" viewBox="-12 -12 24 24" style={{ flexShrink: 0 }}>
                      <polygon
                        points="0,-10 8.66,-5 8.66,5 0,10 -8.66,5 -8.66,-5"
                        fill={item.color || '#e8d5f2'}
                        stroke="#1f2937"
                        strokeWidth="2"
                      />
                    </svg>
                  ) : (
                    // Default: Circle
                    <div style={{
                      width: '20px',
                      height: '20px',
                      borderRadius: '50%',
                      backgroundColor: item.color || '#4A90E2',
                      border: '2px solid #1f2937',
                      flexShrink: 0
                    }} />
                  )}
                  <span style={styles.vowlLegendLabel}>{item.name}</span>
                </div>
              ));
              })()}
              
              {/* Edge Types */}
              {vowlLegend.filter(item => item.type === 'edge').length > 0 && (
                <>
                  <div style={styles.legendCategory}>Relationship Types</div>
                  {vowlLegend.filter(item => item.type === 'edge').map((item) => (
                    <div key={`edge-${item.name}`} style={styles.vowlLegendItem}>
                      <svg width="28" height="4" style={{ flexShrink: 0 }}>
                        <line 
                          x1="0" 
                          y1="2" 
                          x2="28" 
                          y2="2" 
                          stroke={item.stroke || item.color || '#50C878'}
                          strokeWidth="2"
                          strokeDasharray={item.strokeDasharray || '0'}
                        />
                      </svg>
                      <span style={styles.vowlLegendLabel}>{item.name}</span>
                    </div>
                  ))}
                </>
              )}
              
              {/* Property Label Colors (VOWL Mode) */}
              {vowlLegend.filter(item => item.type === 'label').length > 0 && (
                <>
                  <div style={styles.legendCategory}>Property Label Colors</div>
                  {vowlLegend.filter(item => item.type === 'label').map((item) => (
                    <div key={`label-${item.name}`} style={styles.vowlLegendItem}>
                      <div style={{
                        width: '20px',
                        height: '12px',
                        backgroundColor: item.color || '#BBDEFB',
                        border: '1px solid ' + (item.color === '#C8E6C9' ? '#4CAF50' : item.color === '#BBDEFB' ? '#2196F3' : '#9C27B0'),
                        borderRadius: '2px',
                        flexShrink: 0
                      }} />
                      <span style={styles.vowlLegendLabel}>{item.name}</span>
                    </div>
                  ))}
                </>
              )}
            </div>
          )}
        </div>
      )}

      {/* Ontology Statistics (like VOWL) */}
      <div style={styles.accordionSection}>
        <div 
          className="accordion-header"
          style={styles.accordionHeader}
          onClick={() => toggleSection('statistics')}
        >
          <span style={styles.accordionTitle}>STATISTICS</span>
          {expandedSections.statistics ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        </div>
        {expandedSections.statistics && (
          <div style={styles.statsSection}>
            <div style={styles.statsGrid}>
              <div style={styles.statRow}>
                <span style={styles.statLabel}>Total Entities:</span>
                <span style={styles.statValue}>{statistics.totalNodes}</span>
              </div>
              <div style={styles.statRow}>
                <span style={styles.statLabel}>Classes:</span>
                <span style={styles.statValue}>{statistics.classes}</span>
              </div>
              <div style={styles.statRow}>
                <span style={styles.statLabel}>Individuals:</span>
                <span style={styles.statValue}>{statistics.individuals}</span>
              </div>
              <div style={styles.statRow}>
                <span style={styles.statLabel}>Object Properties:</span>
                <span style={styles.statValue}>{statistics.objectProperties}</span>
              </div>
              <div style={styles.statRow}>
                <span style={styles.statLabel}>Datatype Properties:</span>
                <span style={styles.statValue}>{statistics.datatypeProperties}</span>
              </div>
              <div style={styles.statRow}>
                <span style={styles.statLabel}>Datatypes:</span>
                <span style={styles.statValue}>{statistics.datatypes}</span>
              </div>
              <div style={styles.statRow}>
                <span style={styles.statLabel}>Annotation Properties:</span>
                <span style={styles.statValue}>{statistics.annotations}</span>
              </div>
              <div style={styles.statRow}>
                <span style={styles.statLabel}>Total Edges:</span>
                <span style={styles.statValue}>{statistics.totalEdges}</span>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* VOWL Controls Section - Show only when Settings button clicked */}
      {showSettings && (
        <div style={styles.accordionSection}>
          <div 
            className="accordion-header"
            style={styles.accordionHeader}
            onClick={() => toggleSection('vowlControls')}
          >
            <span style={styles.accordionTitle}>VOWL CONTROLS</span>
            {expandedSections.vowlControls ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
          </div>
          {expandedSections.vowlControls && (
            <div style={styles.vowlControlsSection}>
              {/* VOWL Specific Filters */}
              {(viewMode === 'vowl' || viewMode === 'ontograph') && (
                <div style={{ marginBottom: '16px', borderBottom: '1px solid #eee', paddingBottom: '12px' }}>
                  <div style={{ ...styles.controlLabel, fontWeight: 'bold', marginBottom: '8px' }}>VOWL Filters</div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', cursor: 'pointer' }}>
                      <input
                        type="checkbox"
                        checked={vowlFilters?.showInternalClasses ?? true}
                        onChange={(e) => vowlFilters && onVowlFilterChange?.({ ...vowlFilters, showInternalClasses: e.target.checked })}
                      />
                      Internal Classes
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', cursor: 'pointer' }}>
                      <input
                        type="checkbox"
                        checked={vowlFilters?.showExternalClasses ?? true}
                        onChange={(e) => vowlFilters && onVowlFilterChange?.({ ...vowlFilters, showExternalClasses: e.target.checked })}
                      />
                      External Classes
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', cursor: 'pointer' }}>
                      <input
                        type="checkbox"
                        checked={vowlFilters?.showDatatypes ?? true}
                        onChange={(e) => vowlFilters && onVowlFilterChange?.({ ...vowlFilters, showDatatypes: e.target.checked })}
                      />
                      Datatypes
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', cursor: 'pointer' }}>
                      <input
                        type="checkbox"
                        checked={vowlFilters?.showObjectProperties ?? true}
                        onChange={(e) => vowlFilters && onVowlFilterChange?.({ ...vowlFilters, showObjectProperties: e.target.checked })}
                      />
                      Object Properties
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', cursor: 'pointer' }}>
                      <input
                        type="checkbox"
                        checked={vowlFilters?.showDataProperties ?? true}
                        onChange={(e) => vowlFilters && onVowlFilterChange?.({ ...vowlFilters, showDataProperties: e.target.checked })}
                      />
                      Data Properties
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', cursor: 'pointer' }}>
                      <input
                        type="checkbox"
                        checked={vowlFilters?.showSubClassOf ?? true}
                        onChange={(e) => vowlFilters && onVowlFilterChange?.({ ...vowlFilters, showSubClassOf: e.target.checked })}
                      />
                      SubClass Relationships
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', cursor: 'pointer' }}>
                      <input
                        type="checkbox"
                        checked={vowlFilters?.showFunctionalProperties ?? true}
                        onChange={(e) => vowlFilters && onVowlFilterChange?.({ ...vowlFilters, showFunctionalProperties: e.target.checked })}
                      />
                      Functional Properties
                    </label>
                  </div>
                </div>
              )}

              {/* Class Distance Slider */}
              <div style={styles.controlGroup}>
                <label style={styles.controlLabel}>Class Distance:</label>
                <input
                  type="range"
                  min="10"
                  max="200"
                  value={localClassDistance}
                  onChange={(e) => setLocalClassDistance(parseInt(e.target.value))}
                  onMouseUp={() => onClassDistanceChange?.(localClassDistance)}
                  onKeyUp={(e) => { if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') onClassDistanceChange?.(localClassDistance); }}
                  style={styles.slider}
                />
                <span style={styles.sliderValue}>{localClassDistance}</span>
              </div>

              {/* Datatype Distance Slider */}
              <div style={styles.controlGroup}>
                <label style={styles.controlLabel}>Datatype Distance:</label>
                <input
                  type="range"
                  min="5"
                  max="150"
                  value={localDatatypeDistance}
                  onChange={(e) => setLocalDatatypeDistance(parseInt(e.target.value))}
                  onMouseUp={() => onDatatypeDistanceChange?.(localDatatypeDistance)}
                  onKeyUp={(e) => { if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') onDatatypeDistanceChange?.(localDatatypeDistance); }}
                  style={styles.slider}
                />
                <span style={styles.sliderValue}>{localDatatypeDistance}</span>
              </div>

              {/* Layout Controls */}
              <div style={styles.layoutControls}>
                <button
                  onClick={onPauseLayout}
                  style={{
                    ...styles.controlButton,
                    ...(isLayoutPaused ? styles.controlButtonActive : {})
                  }}
                >
                  {isLayoutPaused ? 'Resume Layouting' : 'Pause Layouting'}
                </button>
                <button
                  onClick={onResetLayout}
                  style={styles.controlButton}
                >
                  Reset
                </button>
              </div>
            </div>
          )}
        </div>
      )}
      </>
      )}
      </div>

      {/* CSS for hover effects */}
      <style>{`
        .accordion-header:hover {
          background: #f3f4f6 !important;
        }
        .entity-item:hover {
          background-color: #f3f4f6 !important;
          transform: translateX(2px);
        }
        input[type="text"]:focus {
          background-color: #ffffff !important;
          border-color: #667eea !important;
          box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.1) !important;
        }
        .entity-tab:hover:not(.active-tab) {
          background-color: #f9fafb !important;
          border-color: #d1d5db !important;
        }
        a:hover {
          color: #5568d3 !important;
          text-decoration: underline !important;
        }
        .related-entity-badge:hover {
          background-color: #667eea !important;
          color: #ffffff !important;
          transform: translateY(-1px);
          box-shadow: 0 2px 8px rgba(102, 126, 234, 0.3) !important;
        }
      `}</style>
    </div>
  );
};

const styles: Record<string, React.CSSProperties> = {
  sidebar: {
    position: 'relative',
    width: '340px',
    minWidth: '280px',
    maxWidth: '600px',
    background: 'var(--surface-1)',
    borderLeft: '1px solid var(--border)',
    boxShadow: '-2px 0 8px rgba(0, 0, 0, 0.04)',
    display: 'flex',
    flexDirection: 'column',
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
    overflow: 'hidden',
    flexShrink: 0,
    height: '100%'
  },
  scrollableContent: {
    flex: 1,
    overflowY: 'auto',
    overflowX: 'hidden',
    backgroundColor: 'var(--bg)'
  },
  resizeHandle: {
    position: 'absolute',
    left: 0,
    top: 0,
    width: '4px',
    height: '100%',
    cursor: 'col-resize',
    background: 'transparent',
    zIndex: 10,
    transition: 'background 0.2s ease'
  },
  accordionSection: {
    marginBottom: '1px',
    flexShrink: 0,
    backgroundColor: 'var(--surface-1)',
    borderRadius: '0'
  },
  accordionHeader: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: '12px 16px',
    background: 'var(--surface-2)',
    cursor: 'pointer',
    userSelect: 'none',
    transition: 'all 0.2s ease',
    borderBottom: '1px solid var(--border)'
  },
  accordionTitle: {
    fontSize: '11px',
    fontWeight: '600',
    color: 'var(--text-primary)',
    letterSpacing: '0.5px',
    textTransform: 'uppercase'
  },
  topFilters: {
    display: 'flex',
    flexDirection: 'column',
    gap: '8px',
    padding: '14px 16px',
    backgroundColor: 'var(--surface-1)',
    fontSize: '13px',
    flexShrink: 0
  },
  filterCategory: {
    marginBottom: '12px'
  },
  filterCategoryTitle: {
    fontSize: '11px',
    fontWeight: '700',
    color: 'var(--text-secondary)',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
    marginBottom: '8px',
    paddingBottom: '4px',
    borderBottom: '1px solid var(--divider)'
  },
  topFilterLabel: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    cursor: 'pointer',
    color: 'var(--text-secondary)',
    userSelect: 'none',
    fontSize: '13px',
    fontWeight: '500',
    transition: 'color 0.2s ease',
    padding: '4px 0'
  },
  topFilterCheckbox: {
    cursor: 'pointer',
    width: '16px',
    height: '16px',
    accentColor: 'var(--accent)',
    borderRadius: '3px'
  },
  combinedSection: {
    display: 'flex',
    flexDirection: 'column',
    backgroundColor: 'var(--surface-1)'
  },
  searchSection: {
    padding: '14px 16px',
    backgroundColor: 'var(--surface-1)',
    flexShrink: 0
  },
  searchInputContainer: {
    padding: '14px 16px',
    flexShrink: 0,
    position: 'relative',
    width: '100%'
  },
  searchIcon: {
    position: 'absolute',
    left: '12px',
    top: '50%',
    transform: 'translateY(-50%)',
    color: 'var(--text-tertiary)',
    pointerEvents: 'none'
  },
  searchInput: {
    width: '100%',
    padding: '12px 14px 12px 42px',
    border: '2px solid var(--border)',
    borderRadius: '24px',
    fontSize: '13px',
    outline: 'none',
    boxSizing: 'border-box',
    backgroundColor: 'var(--surface-1)',
    color: 'var(--text-primary)',
    transition: 'all 0.3s ease',
    fontFamily: 'inherit',
    boxShadow: '0 2px 8px rgba(0,0,0,0.05)'
  },
  entitySelector: {
    backgroundColor: 'var(--surface-1)',
    padding: '12px 16px',
    flexShrink: 0
  },
  entityTabs: {
    padding: '0 16px 12px 16px',
    flexShrink: 0,
    display: 'grid',
    gridTemplateColumns: 'repeat(3, 1fr)',
    gap: '6px'
  },
  entityTab: {
    padding: '8px 6px',
    border: '1px solid var(--border)',
    backgroundColor: 'var(--surface-1)',
    color: 'var(--text-secondary)',
    fontSize: '11px',
    fontWeight: '500',
    cursor: 'pointer',
    borderRadius: '6px',
    textAlign: 'center',
    transition: 'all 0.2s cubic-bezier(0.4, 0, 0.2, 1)',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    boxShadow: '0 1px 2px rgba(0,0,0,0.05)'
  },
  activeEntityTab: {
    background: 'var(--accent)',
    color: 'var(--on-accent)',
    fontWeight: '600',
    borderColor: 'var(--accent)',
    boxShadow: '0 4px 12px var(--accent-tint)',
    transform: 'translateY(-2px) scale(1.05)'
  },
  filterSection: {
    display: 'flex',
    flexDirection: 'column',
    backgroundColor: 'var(--surface-1)'
  },
  filterTitle: {
    padding: '12px 16px',
    fontSize: '12px',
    color: 'var(--text-tertiary)',
    borderBottom: '1px solid var(--divider)',
    fontStyle: 'italic',
    flexShrink: 0,
    fontWeight: '400'
  },
  entityList: {
    padding: '0',
    backgroundColor: 'var(--surface-1)',
    borderTop: '1px solid var(--divider)'
  },
  entityItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
    padding: '10px 16px',
    cursor: 'pointer',
    fontSize: '13px',
    color: 'var(--text-primary)',
    transition: 'all 0.2s cubic-bezier(0.4, 0, 0.2, 1)',
    borderLeft: '3px solid transparent'
  },
  selectedEntity: {
    backgroundColor: 'var(--accent-tint)',
    borderLeftColor: 'var(--accent)',
    fontWeight: '600',
    boxShadow: 'inset 0 0 12px var(--accent-tint)'
  },
  entityBullet: {
    fontSize: '12px',
    color: 'var(--accent)',
    flexShrink: 0,
    lineHeight: 1,
    textShadow: '0 1px 2px var(--accent-tint)'
  },
  entityLabel: {
    flex: 1,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap'
  },
  emptyState: {
    padding: '48px 24px',
    textAlign: 'center',
    color: 'var(--text-tertiary)',
    fontSize: '14px',
    fontStyle: 'italic',
    lineHeight: '1.5'
  },
  vowlLegendSection: {
    backgroundColor: 'var(--surface-1)',
    padding: '14px 16px',
    flexShrink: 0
  },
  legendCategory: {
    fontSize: '11px',
    fontWeight: '700',
    color: 'var(--accent)',
    textTransform: 'uppercase',
    letterSpacing: '0.8px',
    marginTop: '8px',
    marginBottom: '8px',
    paddingBottom: '6px',
    borderBottom: '2px solid var(--divider)'
  },
  vowlLegendItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    padding: '10px 12px',
    marginBottom: '6px',
    backgroundColor: 'var(--surface-2)',
    borderRadius: '6px',
    border: '1px solid var(--border)',
    transition: 'all 0.2s ease',
    cursor: 'default'
  },
  vowlLegendLabel: {
    fontSize: '12px',
    color: 'var(--text-primary)',
    fontWeight: '500',
    flex: 1
  },
  statsSection: {
    backgroundColor: 'var(--surface-1)',
    padding: '14px 16px',
    flexShrink: 0
  },
  statsGrid: {
    display: 'flex',
    flexDirection: 'column',
    gap: '8px'
  },
  statRow: {
    display: 'flex',
    justifyContent: 'space-between',
    fontSize: '13px',
    padding: '4px 0',
    transition: 'all 0.2s ease'
  },
  statLabel: {
    color: 'var(--text-secondary)',
    fontWeight: '500'
  },
  statValue: {
    color: 'var(--accent)',
    fontWeight: '700',
    fontSize: '15px',
    textShadow: '0 1px 2px var(--accent-tint)'
  },
  detailsSection: {
    backgroundColor: 'var(--surface-1)',
    padding: '16px',
    maxHeight: '400px',
    overflowY: 'auto',
    flexShrink: 0
  },
  detailRow: {
    marginBottom: '16px',
    paddingBottom: '12px',
    borderBottom: '1px solid var(--divider)'
  },
  detailLabel: {
    fontSize: '11px',
    fontWeight: '600',
    color: 'var(--text-secondary)',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
    marginBottom: '6px',
    display: 'block'
  },
  detailValue: {
    fontSize: '13px',
    color: 'var(--text-primary)',
    lineHeight: '1.5'
  },
  detailLink: {
    color: 'var(--accent)',
    textDecoration: 'none',
    fontWeight: '500',
    transition: 'color 0.2s ease'
  },
  // New Entity Details Card Styles (Blood Pressure style)
  entityTitleHeader: {
    background: 'var(--accent)',
    padding: '20px 20px',
    borderTopLeftRadius: '0',
    borderTopRightRadius: '0'
  },
  entityTitle: {
    fontSize: '18px',
    fontWeight: '700',
    color: 'var(--on-accent)',
    margin: 0,
    textShadow: '0 1px 3px rgba(0,0,0,0.2)',
    letterSpacing: '0.3px'
  },
  entityDetailsTable: {
    backgroundColor: 'var(--surface-1)',
    padding: '0'
  },
  entityDetailRow: {
    display: 'flex',
    padding: '14px 20px',
    borderBottom: '1px solid var(--divider)',
    transition: 'background-color 0.2s ease',
    alignItems: 'flex-start',
    minHeight: '50px'
  },
  entityDetailLabel: {
    fontSize: '14px',
    fontWeight: '600',
    color: 'var(--text-primary)',
    minWidth: '130px',
    flexShrink: 0,
    paddingRight: '16px',
    lineHeight: '1.6'
  },
  entityDetailValue: {
    fontSize: '14px',
    color: 'var(--text-secondary)',
    flex: 1,
    lineHeight: '1.6',
    wordBreak: 'break-word'
  },
  typeBadge: {
    display: 'inline-block',
    padding: '4px 12px',
    backgroundColor: 'var(--accent)',
    color: 'var(--on-accent)',
    borderRadius: '4px',
    fontSize: '12px',
    fontWeight: '600',
    textTransform: 'capitalize'
  },
  iriLink: {
    color: 'var(--accent)',
    textDecoration: 'none',
    fontSize: '12px',
    wordBreak: 'break-all',
    transition: 'color 0.2s ease'
  },
  relatedEntityList: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '6px',
    marginTop: '4px'
  },
  relatedEntityBadge: {
    display: 'inline-block',
    padding: '5px 10px',
    backgroundColor: 'var(--surface-2)',
    color: 'var(--text-secondary)',
    borderRadius: '4px',
    fontSize: '12px',
    fontWeight: '500',
    cursor: 'pointer',
    transition: 'all 0.2s ease',
    border: '1px solid var(--border)'
  },
  relatedList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '6px',
    marginTop: '8px'
  },
  relatedItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    padding: '6px 10px',
    backgroundColor: 'var(--surface-2)',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '12px',
    color: 'var(--text-secondary)',
    transition: 'all 0.2s ease',
    border: '1px solid transparent'
  },
  searchClear: {
    position: 'absolute',
    right: '26px',
    top: '50%',
    transform: 'translateY(-50%)',
    color: 'var(--text-tertiary)',
    cursor: 'pointer',
    transition: 'color 0.2s ease'
  },
  vowlControlsSection: {
    backgroundColor: 'var(--surface-1)',
    padding: '16px',
    flexShrink: 0
  },
  controlGroup: {
    marginBottom: '20px'
  },
  controlLabel: {
    fontSize: '12px',
    fontWeight: '600',
    color: 'var(--text-secondary)',
    marginBottom: '8px',
    display: 'block'
  },
  slider: {
    width: '100%',
    height: '6px',
    borderRadius: '3px',
    outline: 'none',
    background: 'linear-gradient(to right, var(--border) 0%, var(--accent) 100%)',
    WebkitAppearance: 'none',
    appearance: 'none',
    cursor: 'pointer'
  },
  sliderValue: {
    display: 'inline-block',
    marginLeft: '12px',
    fontSize: '13px',
    fontWeight: '600',
    color: 'var(--accent)',
    minWidth: '35px',
    textAlign: 'right'
  },
  layoutControls: {
    display: 'flex',
    gap: '10px',
    marginTop: '16px'
  },
  controlButton: {
    flex: 1,
    padding: '10px 16px',
    background: 'var(--surface-1)',
    border: '2px solid var(--accent)',
    borderRadius: '8px',
    color: 'var(--accent)',
    fontSize: '13px',
    fontWeight: '600',
    cursor: 'pointer',
    transition: 'all 0.2s ease',
    textAlign: 'center'
  },
  controlButtonActive: {
    background: 'var(--accent)',
    color: 'var(--on-accent)',
    borderColor: 'var(--accent)'
  },
  // VOWL Sidebar Header Styles
  vowlSidebarHeader: {
    background: 'var(--surface-2)',
    padding: '16px',
    boxShadow: '0 1px 3px rgba(0, 0, 0, 0.05)',
    borderBottom: '1px solid var(--border)'
  },
  vowlSidebarTitle: {
    fontSize: '14px',
    fontWeight: '600',
    color: 'var(--text-primary)',
    marginBottom: '4px'
  },
  vowlSidebarSubtitle: {
    fontSize: '12px',
    color: 'var(--text-secondary)',
    fontWeight: '400'
  },
  // VOWL Controls Card Styles
  vowlControlsCard: {
    marginBottom: '1px',
    backgroundColor: 'var(--surface-1)',
    borderRadius: '0'
  },
  vowlControlsHeader: {
    padding: '12px 16px',
    background: 'var(--surface-2)',
    color: 'var(--text-primary)',
    fontWeight: '600',
    fontSize: '11px',
    letterSpacing: '0.5px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    cursor: 'pointer',
    userSelect: 'none',
    transition: 'all 0.2s ease',
    borderBottom: '1px solid var(--border)'
  },
  vowlControlsTitle: {
    fontSize: '11px',
    fontWeight: '600',
    letterSpacing: '0.5px',
    textTransform: 'uppercase'
  },
  vowlControlsContent: {
    padding: '16px',
    backgroundColor: 'var(--surface-1)'
  },
  vowlControlGroup: {
    marginBottom: '20px',
    padding: '12px',
    backgroundColor: 'var(--surface-2)',
    borderRadius: '6px',
    border: '1px solid var(--border)'
  },
  vowlControlHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: '12px'
  },
  vowlControlLabel: {
    fontSize: '12px',
    fontWeight: '600',
    color: 'var(--text-primary)'
  },
  vowlControlValue: {
    fontSize: '13px',
    fontWeight: '600',
    color: 'var(--accent)',
    backgroundColor: 'var(--accent-tint)',
    padding: '4px 10px',
    borderRadius: '4px',
    minWidth: '45px',
    textAlign: 'center'
  },
  sliderContainer: {
    position: 'relative',
    marginBottom: '8px'
  },
  vowlSlider: {
    width: '100%',
    height: '6px',
    borderRadius: '3px',
    outline: 'none',
    background: 'transparent',
    WebkitAppearance: 'none',
    appearance: 'none',
    cursor: 'pointer',
    position: 'relative',
    zIndex: 2
  },
  sliderTrack: {
    position: 'absolute',
    top: '50%',
    left: 0,
    right: 0,
    height: '6px',
    backgroundColor: 'var(--border)',
    borderRadius: '3px',
    transform: 'translateY(-50%)',
    overflow: 'hidden',
    pointerEvents: 'none'
  },
  sliderFill: {
    height: '100%',
    backgroundColor: 'var(--accent)',
    transition: 'width 0.1s ease',
    borderRadius: '3px'
  },
  sliderLabels: {
    display: 'flex',
    justifyContent: 'space-between',
    marginTop: '4px'
  },
  sliderLabelMin: {
    fontSize: '10px',
    color: 'var(--text-tertiary)',
    fontWeight: '500'
  },
  sliderLabelMax: {
    fontSize: '10px',
    color: 'var(--text-tertiary)',
    fontWeight: '500'
  },
  vowlLayoutControls: {
    display: 'flex',
    gap: '10px',
    marginTop: '16px'
  },
  vowlControlButton: {
    flex: 1,
    padding: '10px 14px',
    background: 'var(--surface-1)',
    border: '1px solid var(--border)',
    borderRadius: '6px',
    color: 'var(--text-primary)',
    fontSize: '12px',
    fontWeight: '600',
    cursor: 'pointer',
    transition: 'all 0.2s ease',
    textAlign: 'center'
  },
  vowlControlButtonActive: {
    background: 'var(--accent)',
    color: 'var(--on-accent)',
    borderColor: 'var(--accent)'
  },
  vowlResetButton: {
    flex: 1,
    padding: '10px 14px',
    background: 'var(--surface-1)',
    border: '1px solid var(--border)',
    borderRadius: '6px',
    color: 'var(--text-primary)',
    fontSize: '12px',
    fontWeight: '600',
    cursor: 'pointer',
    transition: 'all 0.2s ease',
    textAlign: 'center'
  },
  // VOWL Entity Card Styles
  vowlEntityCard: {
    marginBottom: '1px',
    backgroundColor: 'var(--surface-1)',
    borderRadius: '0'
  },
  vowlEntityHeader: {
    padding: '12px 16px',
    background: 'var(--surface-2)',
    color: 'var(--text-primary)',
    fontWeight: '600',
    fontSize: '11px',
    letterSpacing: '0.5px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    cursor: 'pointer',
    userSelect: 'none',
    transition: 'all 0.2s ease',
    borderBottom: '1px solid var(--border)'
  },
  vowlEntityTitle: {
    fontSize: '11px',
    fontWeight: '600',
    letterSpacing: '0.5px',
    textTransform: 'uppercase'
  },
  vowlEntityInfo: {
    padding: '16px',
    backgroundColor: 'var(--surface-1)'
  },
  vowlEntityRow: {
    display: 'flex',
    alignItems: 'center',
    marginBottom: '12px',
    padding: '10px',
    backgroundColor: 'var(--surface-2)',
    borderRadius: '6px',
    border: '1px solid var(--border)'
  },
  vowlEntityLabel: {
    fontSize: '12px',
    fontWeight: '600',
    color: 'var(--text-secondary)',
    minWidth: '70px'
  },
  vowlEntityBadge: {
    fontSize: '12px',
    fontWeight: '600',
    color: 'var(--on-accent)',
    padding: '4px 12px',
    borderRadius: '4px',
    textTransform: 'capitalize'
  },
  vowlEntityLink: {
    fontSize: '12px',
    fontWeight: '500',
    color: 'var(--accent)',
    textDecoration: 'none',
    transition: 'color 0.2s ease',
    wordBreak: 'break-all'
  }
};

export default GraphViewSidebar;

/**
 * Graph View Sidebar - Similar to webVOWL plugin
 * Provides entity selector, filters, statistics, and detailed information
 */

import React, { useState, useMemo } from 'react';
import { 
  Search, X, ChevronDown, ChevronRight, ChevronUp, Info, Tag,
  Square, Circle, Diamond, Triangle, Hexagon,
  Link2, Filter, BarChart3, Eye, EyeOff, Layers, GitBranch
} from 'lucide-react';
import { OntologyNode as BaseOntologyNode, OntologyEdge } from '../types';

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
  viewMode?: 'graph' | 'vowl' | 'force' | 'ontograph';
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
  viewMode = 'graph',
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
  showClassHierarchy
}) => {
  const [entityTab, setEntityTab] = useState<'classes' | 'objectProperties' | 'datatypeProperties' | 'individuals' | 'annotations' | 'datatypes'>('classes');
  const [searchTerm, setSearchTerm] = useState('');
  const [sidebarWidth, setSidebarWidth] = useState(340);
  const [isResizing, setIsResizing] = useState(false);
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

  // Build class hierarchy tree with relationships
  const classHierarchyTree = useMemo(() => {
    const classNodes = nodes.filter(n => n.type === 'class');
    
    // Build relationship map
    const childrenMap = new Map<string, OntologyNode[]>();
    const parentMap = new Map<string, OntologyNode[]>();
    
    edges.forEach(edge => {
      if (edge.type === 'subClassOf') {
        // edge.from is child, edge.to is parent
        if (!childrenMap.has(edge.to)) {
          childrenMap.set(edge.to, []);
        }
        const childNode = classNodes.find(n => n.id === edge.from);
        if (childNode && !childrenMap.get(edge.to)!.some(c => c.id === childNode.id)) {
          childrenMap.get(edge.to)!.push(childNode);
        }
        
        if (!parentMap.has(edge.from)) {
          parentMap.set(edge.from, []);
        }
        const parentNode = classNodes.find(n => n.id === edge.to);
        if (parentNode && !parentMap.get(edge.from)!.some(p => p.id === parentNode.id)) {
          parentMap.get(edge.from)!.push(parentNode);
        }
      }
    });
    
    // Find root classes (no parents) - exclude owl:Thing-like nodes if they have children
    const rootClasses = classNodes.filter(node => !parentMap.has(node.id) || parentMap.get(node.id)!.length === 0)
      .sort((a, b) => (a.label || a.id).localeCompare(b.label || b.id));
    
    return { rootClasses, childrenMap, parentMap };
  }, [nodes, edges]);

  // Track expanded nodes in the class hierarchy tree
  const [expandedNodes, setExpandedNodes] = useState<Set<string>>(new Set());

  // Render hierarchy tree with navigator style
  const renderHierarchyNode = (node: OntologyNode, level: number = 0): JSX.Element => {
    const children = classHierarchyTree.childrenMap.get(node.id) || [];
    const parents = classHierarchyTree.parentMap.get(node.id) || [];
    const hasChildren = children.length > 0;
    const hasParents = parents.length > 0;
    const isExpanded = expandedNodes.has(node.id);

    return (
      <div key={node.id}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            padding: '6px 8px',
            marginLeft: `${level * 20}px`,
            cursor: 'pointer',
            borderRadius: '4px',
            backgroundColor: selectedNode?.id === node.id ? '#e0e7ff' : 'transparent',
            transition: 'background-color 0.15s'
          }}
          onMouseEnter={(e) => {
            e.currentTarget.style.backgroundColor = selectedNode?.id === node.id ? '#e0e7ff' : '#f3f4f6';
            onNodeHighlight(node.id);
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.backgroundColor = selectedNode?.id === node.id ? '#e0e7ff' : 'transparent';
            onNodeHighlight(null);
          }}
        >
          {/* Expand Up (Parents) Icon */}
          {hasParents && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                // Could trigger expand parents action
              }}
              style={{
                marginRight: '4px',
                cursor: 'pointer',
                fontSize: '12px',
                fontWeight: 'bold',
                color: '#fff',
                backgroundColor: '#667eea',
                border: 'none',
                borderRadius: '50%',
                width: '18px',
                height: '18px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                padding: 0,
                flexShrink: 0
              }}
              title={`Has ${parents.length} parent(s)`}
            >
              <ChevronUp size={12} />
            </button>
          )}
          
          {/* Expand Down (Children) Icon */}
          {hasChildren && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                setExpandedNodes(prev => {
                  const newSet = new Set(prev);
                  if (newSet.has(node.id)) {
                    newSet.delete(node.id);
                  } else {
                    newSet.add(node.id);
                  }
                  return newSet;
                });
              }}
              style={{
                marginRight: '6px',
                cursor: 'pointer',
                fontSize: '12px',
                fontWeight: 'bold',
                color: '#fff',
                backgroundColor: '#10b981',
                border: 'none',
                borderRadius: '50%',
                width: '18px',
                height: '18px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                padding: 0,
                flexShrink: 0
              }}
              title={`${isExpanded ? 'Collapse' : 'Expand'} ${children.length} child(ren)`}
            >
              {isExpanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
            </button>
          )}
          
          {/* Spacer if no icons */}
          {!hasChildren && !hasParents && <span style={{ width: '22px', display: 'inline-block', flexShrink: 0 }} />}
          
          <span
            style={{
              fontSize: '13px',
              color: '#374151',
              flex: 1,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              cursor: 'pointer'
            }}
            onClick={(e) => {
              e.stopPropagation();
              // Clicking the class label opens the hierarchy navigator
              onNodeSelect(node);
            }}
            title="Click to open class hierarchy navigator"
          >
            {node.label || node.id}
          </span>
          <span
            style={{
              fontSize: '10px',
              color: '#9ca3af',
              marginLeft: '8px',
              padding: '2px 6px',
              backgroundColor: '#4A90E220',
              borderRadius: '4px',
              flexShrink: 0
            }}
          >
            class
          </span>
        </div>
        {isExpanded && hasChildren && (
          <div>
            {children.map(child => renderHierarchyNode(child, level + 1))}
          </div>
        )}
      </div>
    );
  };

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
      {/* Top Filters (like webVOWL) - Only show when filter button is clicked */}
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

            {/* Property Visibility Filters (WebVOWL) */}
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
                  } else {
                    nodeColor = '#667eea';
                  }
                } else if (node.type === 'objectProperty') {
                  nodeColor = '#06b6d4';
                } else if (node.type === 'dataProperty') {
                  nodeColor = '#ec4899';
                } else if (node.type === 'individual') {
                  nodeColor = '#10b981';
                } else if (node.type === 'datatype') {
                  nodeColor = viewMode === 'vowl' ? '#FFD9B3' : '#FFA500';
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
                      // Classes: Circle (solid border for normal, dashed for Thing)
                      <div style={{
                        width: '16px',
                        height: '16px',
                        borderRadius: '50%',
                        backgroundColor: nodeColor,
                        border: isThing ? '2px dashed #1f2937' : '2px solid #1f2937',
                        flexShrink: 0,
                        marginRight: '8px'
                      }} />
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
                      // Datatypes: Rounded Rectangle with dashed border (yellow/orange)
                      <div style={{
                        width: '24px',
                        height: '12px',
                        backgroundColor: nodeColor,
                        border: '2px dashed #1f2937',
                        flexShrink: 0,
                        borderRadius: '6px',
                        marginRight: '8px'
                      }} />
                    ) : node.type === 'individual' ? (
                      // Individuals: Rectangle (green)
                      <div style={{
                        width: '20px',
                        height: '12px',
                        backgroundColor: nodeColor,
                        border: '2px solid #1f2937',
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

      {/* Entity Details Section (like webVOWL) */}
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
                    // Classes: Circle (solid border for normal classes, dashed for Thing)
                    <div style={{
                      width: '20px',
                      height: '20px',
                      borderRadius: '50%',
                      backgroundColor: item.color || '#4A90E2',
                      border: item.name.includes('Thing') ? '2px dashed #1f2937' : '2px solid #1f2937',
                      flexShrink: 0
                    }} />
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
                    // Individuals: Rectangle (purple/pink)
                    <div style={{
                      width: '28px',
                      height: '16px',
                      backgroundColor: item.color || '#E74C3C',
                      border: '2px solid #1f2937',
                      flexShrink: 0,
                      borderRadius: '4px'
                    }} />
                  ) : item.nodeType === 'datatype' ? (
                    // Datatypes: Rounded Rectangle with dashed border (yellow/orange)
                    <div style={{
                      width: '32px',
                      height: '16px',
                      backgroundColor: item.color || '#FFD9B3',
                      border: '2px dashed #1f2937',
                      flexShrink: 0,
                      borderRadius: '8px'
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
              
              {/* Property Label Colors (WebVOWL Mode) */}
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

      {/* Ontology Statistics (like webVOWL) */}
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
              {/* Class Distance Slider */}
              <div style={styles.controlGroup}>
                <label style={styles.controlLabel}>Class Distance:</label>
                <input
                  type="range"
                  min="10"
                  max="200"
                  value={classDistance}
                  onChange={(e) => onClassDistanceChange?.(parseInt(e.target.value))}
                  style={styles.slider}
                />
                <span style={styles.sliderValue}>{classDistance}</span>
              </div>

              {/* Datatype Distance Slider */}
              <div style={styles.controlGroup}>
                <label style={styles.controlLabel}>Datatype Distance:</label>
                <input
                  type="range"
                  min="5"
                  max="150"
                  value={datatypeDistance}
                  onChange={(e) => onDatatypeDistanceChange?.(parseInt(e.target.value))}
                  style={styles.slider}
                />
                <span style={styles.sliderValue}>{datatypeDistance}</span>
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
    background: '#ffffff',
    borderLeft: '1px solid #e5e7eb',
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
    backgroundColor: '#fafbfc'
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
    backgroundColor: '#ffffff',
    borderRadius: '0'
  },
  accordionHeader: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: '12px 16px',
    background: '#f8f9fa',
    cursor: 'pointer',
    userSelect: 'none',
    transition: 'all 0.2s ease',
    borderBottom: '1px solid #e5e7eb'
  },
  accordionTitle: {
    fontSize: '11px',
    fontWeight: '600',
    color: '#374151',
    letterSpacing: '0.5px',
    textTransform: 'uppercase'
  },
  topFilters: {
    display: 'flex',
    flexDirection: 'column',
    gap: '8px',
    padding: '14px 16px',
    backgroundColor: '#ffffff',
    fontSize: '13px',
    flexShrink: 0
  },
  filterCategory: {
    marginBottom: '12px'
  },
  filterCategoryTitle: {
    fontSize: '11px',
    fontWeight: '700',
    color: '#5f6368',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
    marginBottom: '8px',
    paddingBottom: '4px',
    borderBottom: '1px solid #e8eaed'
  },
  topFilterLabel: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    cursor: 'pointer',
    color: '#5f6368',
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
    accentColor: '#1a73e8',
    borderRadius: '3px'
  },
  combinedSection: {
    display: 'flex',
    flexDirection: 'column',
    backgroundColor: '#ffffff'
  },
  searchSection: {
    padding: '14px 16px',
    backgroundColor: '#ffffff',
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
    color: '#9aa0a6',
    pointerEvents: 'none'
  },
  searchInput: {
    width: '100%',
    padding: '12px 14px 12px 42px',
    border: '2px solid #e8eaed',
    borderRadius: '24px',
    fontSize: '13px',
    outline: 'none',
    boxSizing: 'border-box',
    backgroundColor: '#ffffff',
    transition: 'all 0.3s ease',
    fontFamily: 'inherit',
    boxShadow: '0 2px 8px rgba(0,0,0,0.05)'
  },
  entitySelector: {
    backgroundColor: '#ffffff',
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
    border: '1px solid #dadce0',
    backgroundColor: '#ffffff',
    color: '#5f6368',
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
    background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
    color: '#ffffff',
    fontWeight: '600',
    borderColor: '#667eea',
    boxShadow: '0 4px 12px rgba(102, 126, 234, 0.4)',
    transform: 'translateY(-2px) scale(1.05)'
  },
  filterSection: {
    display: 'flex',
    flexDirection: 'column',
    backgroundColor: '#ffffff'
  },
  filterTitle: {
    padding: '12px 16px',
    fontSize: '12px',
    color: '#9aa0a6',
    borderBottom: '1px solid #e8eaed',
    fontStyle: 'italic',
    flexShrink: 0,
    fontWeight: '400'
  },
  entityList: {
    padding: '0',
    backgroundColor: '#ffffff',
    borderTop: '1px solid #e8eaed'
  },
  entityItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
    padding: '10px 16px',
    cursor: 'pointer',
    fontSize: '13px',
    color: '#202124',
    transition: 'all 0.2s cubic-bezier(0.4, 0, 0.2, 1)',
    borderLeft: '3px solid transparent'
  },
  selectedEntity: {
    backgroundColor: '#f0f2ff',
    borderLeftColor: '#667eea',
    fontWeight: '600',
    boxShadow: 'inset 0 0 12px rgba(102, 126, 234, 0.1)'
  },
  entityBullet: {
    fontSize: '12px',
    color: '#667eea',
    flexShrink: 0,
    lineHeight: 1,
    textShadow: '0 1px 2px rgba(102, 126, 234, 0.3)'
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
    color: '#9aa0a6',
    fontSize: '14px',
    fontStyle: 'italic',
    lineHeight: '1.5'
  },
  vowlLegendSection: {
    backgroundColor: '#ffffff',
    padding: '14px 16px',
    flexShrink: 0
  },
  legendCategory: {
    fontSize: '11px',
    fontWeight: '700',
    color: '#667eea',
    textTransform: 'uppercase',
    letterSpacing: '0.8px',
    marginTop: '8px',
    marginBottom: '8px',
    paddingBottom: '6px',
    borderBottom: '2px solid #e8eaed'
  },
  vowlLegendItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    padding: '10px 12px',
    marginBottom: '6px',
    backgroundColor: '#f8f9fa',
    borderRadius: '6px',
    border: '1px solid #e8eaed',
    transition: 'all 0.2s ease',
    cursor: 'default'
  },
  vowlLegendLabel: {
    fontSize: '12px',
    color: '#374151',
    fontWeight: '500',
    flex: 1
  },
  statsSection: {
    backgroundColor: '#ffffff',
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
    color: '#5f6368',
    fontWeight: '500'
  },
  statValue: {
    color: '#667eea',
    fontWeight: '700',
    fontSize: '15px',
    textShadow: '0 1px 2px rgba(102, 126, 234, 0.2)'
  },
  detailsSection: {
    backgroundColor: '#ffffff',
    padding: '16px',
    maxHeight: '400px',
    overflowY: 'auto',
    flexShrink: 0
  },
  detailRow: {
    marginBottom: '16px',
    paddingBottom: '12px',
    borderBottom: '1px solid #f1f3f4'
  },
  detailLabel: {
    fontSize: '11px',
    fontWeight: '600',
    color: '#5f6368',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
    marginBottom: '6px',
    display: 'block'
  },
  detailValue: {
    fontSize: '13px',
    color: '#202124',
    lineHeight: '1.5'
  },
  detailLink: {
    color: '#1a73e8',
    textDecoration: 'none',
    fontWeight: '500',
    transition: 'color 0.2s ease'
  },
  // New Entity Details Card Styles (Blood Pressure style)
  entityTitleHeader: {
    background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
    padding: '20px 20px',
    borderTopLeftRadius: '0',
    borderTopRightRadius: '0'
  },
  entityTitle: {
    fontSize: '18px',
    fontWeight: '700',
    color: '#ffffff',
    margin: 0,
    textShadow: '0 1px 3px rgba(0,0,0,0.2)',
    letterSpacing: '0.3px'
  },
  entityDetailsTable: {
    backgroundColor: '#ffffff',
    padding: '0'
  },
  entityDetailRow: {
    display: 'flex',
    padding: '14px 20px',
    borderBottom: '1px solid #e8eaed',
    transition: 'background-color 0.2s ease',
    alignItems: 'flex-start',
    minHeight: '50px'
  },
  entityDetailLabel: {
    fontSize: '14px',
    fontWeight: '600',
    color: '#202124',
    minWidth: '130px',
    flexShrink: 0,
    paddingRight: '16px',
    lineHeight: '1.6'
  },
  entityDetailValue: {
    fontSize: '14px',
    color: '#5f6368',
    flex: 1,
    lineHeight: '1.6',
    wordBreak: 'break-word'
  },
  typeBadge: {
    display: 'inline-block',
    padding: '4px 12px',
    backgroundColor: '#667eea',
    color: '#ffffff',
    borderRadius: '4px',
    fontSize: '12px',
    fontWeight: '600',
    textTransform: 'capitalize'
  },
  iriLink: {
    color: '#1a73e8',
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
    backgroundColor: '#f1f3f4',
    color: '#5f6368',
    borderRadius: '4px',
    fontSize: '12px',
    fontWeight: '500',
    cursor: 'pointer',
    transition: 'all 0.2s ease',
    border: '1px solid #dadce0'
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
    backgroundColor: '#f8f9fa',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '12px',
    color: '#5f6368',
    transition: 'all 0.2s ease',
    border: '1px solid transparent'
  },
  searchClear: {
    position: 'absolute',
    right: '26px',
    top: '50%',
    transform: 'translateY(-50%)',
    color: '#9aa0a6',
    cursor: 'pointer',
    transition: 'color 0.2s ease'
  },
  vowlControlsSection: {
    backgroundColor: '#ffffff',
    padding: '16px',
    flexShrink: 0
  },
  controlGroup: {
    marginBottom: '20px'
  },
  controlLabel: {
    fontSize: '12px',
    fontWeight: '600',
    color: '#5f6368',
    marginBottom: '8px',
    display: 'block'
  },
  slider: {
    width: '100%',
    height: '6px',
    borderRadius: '3px',
    outline: 'none',
    background: 'linear-gradient(to right, #e8eaed 0%, #667eea 100%)',
    WebkitAppearance: 'none',
    appearance: 'none',
    cursor: 'pointer'
  },
  sliderValue: {
    display: 'inline-block',
    marginLeft: '12px',
    fontSize: '13px',
    fontWeight: '600',
    color: '#667eea',
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
    background: '#ffffff',
    border: '2px solid #667eea',
    borderRadius: '8px',
    color: '#667eea',
    fontSize: '13px',
    fontWeight: '600',
    cursor: 'pointer',
    transition: 'all 0.2s ease',
    textAlign: 'center'
  },
  controlButtonActive: {
    background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
    color: '#ffffff',
    borderColor: '#667eea'
  },
  // VOWL Sidebar Header Styles
  vowlSidebarHeader: {
    background: '#f8f9fa',
    padding: '16px',
    boxShadow: '0 1px 3px rgba(0, 0, 0, 0.05)',
    borderBottom: '1px solid #e5e7eb'
  },
  vowlSidebarTitle: {
    fontSize: '14px',
    fontWeight: '600',
    color: '#1f2937',
    marginBottom: '4px'
  },
  vowlSidebarSubtitle: {
    fontSize: '12px',
    color: '#6b7280',
    fontWeight: '400'
  },
  // VOWL Controls Card Styles
  vowlControlsCard: {
    marginBottom: '1px',
    backgroundColor: '#ffffff',
    borderRadius: '0'
  },
  vowlControlsHeader: {
    padding: '12px 16px',
    background: '#f8f9fa',
    color: '#374151',
    fontWeight: '600',
    fontSize: '11px',
    letterSpacing: '0.5px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    cursor: 'pointer',
    userSelect: 'none',
    transition: 'all 0.2s ease',
    borderBottom: '1px solid #e5e7eb'
  },
  vowlControlsTitle: {
    fontSize: '11px',
    fontWeight: '600',
    letterSpacing: '0.5px',
    textTransform: 'uppercase'
  },
  vowlControlsContent: {
    padding: '16px',
    backgroundColor: '#ffffff'
  },
  vowlControlGroup: {
    marginBottom: '20px',
    padding: '12px',
    backgroundColor: '#f9fafb',
    borderRadius: '6px',
    border: '1px solid #e5e7eb'
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
    color: '#374151'
  },
  vowlControlValue: {
    fontSize: '13px',
    fontWeight: '600',
    color: '#667eea',
    backgroundColor: '#eef2ff',
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
    backgroundColor: '#e5e7eb',
    borderRadius: '3px',
    transform: 'translateY(-50%)',
    overflow: 'hidden',
    pointerEvents: 'none'
  },
  sliderFill: {
    height: '100%',
    backgroundColor: '#667eea',
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
    color: '#9ca3af',
    fontWeight: '500'
  },
  sliderLabelMax: {
    fontSize: '10px',
    color: '#9ca3af',
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
    background: '#ffffff',
    border: '1px solid #d1d5db',
    borderRadius: '6px',
    color: '#374151',
    fontSize: '12px',
    fontWeight: '600',
    cursor: 'pointer',
    transition: 'all 0.2s ease',
    textAlign: 'center'
  },
  vowlControlButtonActive: {
    background: '#667eea',
    color: '#ffffff',
    borderColor: '#667eea'
  },
  vowlResetButton: {
    flex: 1,
    padding: '10px 14px',
    background: '#ffffff',
    border: '1px solid #d1d5db',
    borderRadius: '6px',
    color: '#374151',
    fontSize: '12px',
    fontWeight: '600',
    cursor: 'pointer',
    transition: 'all 0.2s ease',
    textAlign: 'center'
  },
  // VOWL Entity Card Styles
  vowlEntityCard: {
    marginBottom: '1px',
    backgroundColor: '#ffffff',
    borderRadius: '0'
  },
  vowlEntityHeader: {
    padding: '12px 16px',
    background: '#f8f9fa',
    color: '#374151',
    fontWeight: '600',
    fontSize: '11px',
    letterSpacing: '0.5px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    cursor: 'pointer',
    userSelect: 'none',
    transition: 'all 0.2s ease',
    borderBottom: '1px solid #e5e7eb'
  },
  vowlEntityTitle: {
    fontSize: '11px',
    fontWeight: '600',
    letterSpacing: '0.5px',
    textTransform: 'uppercase'
  },
  vowlEntityInfo: {
    padding: '16px',
    backgroundColor: '#ffffff'
  },
  vowlEntityRow: {
    display: 'flex',
    alignItems: 'center',
    marginBottom: '12px',
    padding: '10px',
    backgroundColor: '#f9fafb',
    borderRadius: '6px',
    border: '1px solid #e5e7eb'
  },
  vowlEntityLabel: {
    fontSize: '12px',
    fontWeight: '600',
    color: '#6b7280',
    minWidth: '70px'
  },
  vowlEntityBadge: {
    fontSize: '12px',
    fontWeight: '600',
    color: '#ffffff',
    padding: '4px 12px',
    borderRadius: '4px',
    textTransform: 'capitalize'
  },
  vowlEntityLink: {
    fontSize: '12px',
    fontWeight: '500',
    color: '#667eea',
    textDecoration: 'none',
    transition: 'color 0.2s ease',
    wordBreak: 'break-all'
  }
};

export default GraphViewSidebar;

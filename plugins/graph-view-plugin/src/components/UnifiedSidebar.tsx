import React, { useState, useMemo } from 'react';
import { FileText, X } from 'lucide-react';
import { OntologyNode, OntologyEdge } from '../types';

interface UnifiedSidebarProps {
  selectedNodeInfo: OntologyNode | null;
  allNodes: OntologyNode[];
  allEdges: OntologyEdge[];
  projectId: string;
  onNodeSelect: (node: OntologyNode) => void;
  onClose: () => void;
  viewMode: 'graph' | 'vowl';
  vowlLegend?: Array<{ name: string; type: string; nodeType?: string; stroke?: string; strokeDasharray?: string }>;
  vowlNotationService?: any;
}

export const UnifiedSidebar: React.FC<UnifiedSidebarProps> = ({
  selectedNodeInfo,
  allNodes,
  allEdges,
  projectId,
  onNodeSelect,
  onClose,
  viewMode,
  vowlLegend,
  vowlNotationService
}) => {
  const [activeTab, setActiveTab] = useState<'classes' | 'properties' | 'individuals' | 'details' | 'statistics'>('details');

  // Statistics calculation
  const statistics = useMemo(() => {
    const stats = {
      classCount: 0,
      individualCount: 0,
      objectPropertyCount: 0,
      datatypePropertyCount: 0,
      annotationPropertyCount: 0,
      datatypeCount: 0,
      axiomCount: allEdges.length
    };

    allNodes.forEach(node => {
      if (node.type === 'class') stats.classCount++;
      else if (node.type === 'individual') stats.individualCount++;
      else if (node.type === 'objectProperty') stats.objectPropertyCount++;
      else if (node.type === 'datatypeProperty') stats.datatypePropertyCount++;
      else if (node.type === 'annotationProperty') stats.annotationPropertyCount++;
      else if (node.type === 'datatype') stats.datatypeCount++;
    });

    return stats;
  }, [allNodes, allEdges]);

  // Categorize nodes
  const categorizedNodes = useMemo(() => {
    return {
      classes: allNodes.filter(n => n.type === 'class'),
      objectProperties: allNodes.filter(n => n.type === 'objectProperty'),
      datatypeProperties: allNodes.filter(n => n.type === 'datatypeProperty'),
      annotationProperties: allNodes.filter(n => n.type === 'annotationProperty'),
      individuals: allNodes.filter(n => n.type === 'individual')
    };
  }, [allNodes]);

  const propertyPanelStyles = {
    panel: {
      position: 'fixed' as const,
      right: '0',
      top: '60px',
      width: '380px',
      height: 'calc(100vh - 60px)',
      backgroundColor: '#fff',
      borderLeft: '1px solid #d1d5db',
      boxShadow: '-2px 0 8px rgba(0,0,0,0.1)',
      zIndex: 800,
      display: 'flex',
      flexDirection: 'column' as const,
      fontFamily: 'system-ui, -apple-system, sans-serif',
      overflow: 'hidden'
    },
    header: {
      padding: '12px 16px',
      backgroundColor: '#667eea',
      color: '#fff',
      fontWeight: '700',
      fontSize: '14px',
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'center',
      borderBottom: '1px solid #4f46e5',
      flexShrink: 0
    },
    tabBar: {
      display: 'flex',
      borderBottom: '1px solid #e5e7eb',
      backgroundColor: '#f9fafb',
      flexShrink: 0,
      overflowX: 'auto' as const
    },
    tab: {
      padding: '10px 14px',
      fontSize: '12px',
      fontWeight: '500',
      cursor: 'pointer',
      borderBottom: '2px solid transparent',
      backgroundColor: 'transparent',
      border: 'none',
      color: '#6b7280',
      whiteSpace: 'nowrap' as const,
      transition: 'all 0.15s'
    },
    tabActive: {
      color: '#667eea',
      borderBottomColor: '#667eea',
      backgroundColor: '#fff'
    },
    content: {
      flex: 1,
      overflowY: 'auto' as const,
      padding: '12px',
      fontSize: '13px',
      color: '#374151'
    },
    itemList: {
      display: 'flex',
      flexDirection: 'column' as const,
      gap: '6px'
    },
    listItem: {
      padding: '8px 12px',
      backgroundColor: '#f3f4f6',
      borderRadius: '6px',
      cursor: 'pointer',
      transition: 'all 0.15s',
      wordBreak: 'break-word' as const,
      fontSize: '12px'
    },
    propertyItem: {
      marginBottom: '12px',
      paddingBottom: '12px',
      borderBottom: '1px solid #e5e7eb'
    },
    propertyLabel: {
      fontWeight: '600',
      color: '#4b5563',
      fontSize: '11px',
      marginBottom: '4px',
      textTransform: 'uppercase' as const,
      letterSpacing: '0.5px'
    },
    propertyValue: {
      color: '#374151',
      fontSize: '12px',
      lineHeight: '1.5'
    },
    statItem: {
      display: 'flex',
      justifyContent: 'space-between',
      padding: '8px 0',
      borderBottom: '1px solid #f3f4f6'
    },
    statLabel: {
      color: '#6b7280',
      fontSize: '12px'
    },
    statValue: {
      fontWeight: '600',
      color: '#667eea',
      fontSize: '12px'
    },
    legendSection: {
      marginBottom: '12px'
    },
    legendTitle: {
      fontWeight: '600',
      color: '#4b5563',
      fontSize: '12px',
      marginBottom: '6px'
    },
    legendItem: {
      display: 'flex',
      alignItems: 'center',
      gap: '8px',
      padding: '6px 0',
      fontSize: '11px'
    },
    legendIcon: {
      flexShrink: 0 as const
    },
    emptyState: {
      textAlign: 'center' as const,
      padding: '24px 16px',
      color: '#9ca3af'
    }
  };

  const closeBtn = {
    background: 'rgba(255,255,255,0.2)',
    border: 'none',
    color: '#fff',
    cursor: 'pointer',
    fontSize: '18px',
    padding: '0 4px',
    borderRadius: '4px'
  };

  return (
    <div style={propertyPanelStyles.panel}>
      {/* Header */}
      <div style={propertyPanelStyles.header}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <FileText size={18} />
          <span>{selectedNodeInfo?.label || 'Ontology Info'}</span>
        </div>
        <button onClick={onClose} style={closeBtn}>×</button>
      </div>

      {/* Tab Bar */}
      <div style={propertyPanelStyles.tabBar}>
        {selectedNodeInfo && (
          <button
            style={{
              ...propertyPanelStyles.tab,
              ...(activeTab === 'details' ? propertyPanelStyles.tabActive : {})
            }}
            onClick={() => setActiveTab('details')}
          >
            Details
          </button>
        )}
        <button
          style={{
            ...propertyPanelStyles.tab,
            ...(activeTab === 'classes' ? propertyPanelStyles.tabActive : {})
          }}
          onClick={() => setActiveTab('classes')}
        >
          Classes ({categorizedNodes.classes.length})
        </button>
        <button
          style={{
            ...propertyPanelStyles.tab,
            ...(activeTab === 'properties' ? propertyPanelStyles.tabActive : {})
          }}
          onClick={() => setActiveTab('properties')}
        >
          Properties ({categorizedNodes.objectProperties.length + categorizedNodes.datatypeProperties.length})
        </button>
        <button
          style={{
            ...propertyPanelStyles.tab,
            ...(activeTab === 'individuals' ? propertyPanelStyles.tabActive : {})
          }}
          onClick={() => setActiveTab('individuals')}
        >
          Individuals ({categorizedNodes.individuals.length})
        </button>
        <button
          style={{
            ...propertyPanelStyles.tab,
            ...(activeTab === 'statistics' ? propertyPanelStyles.tabActive : {})
          }}
          onClick={() => setActiveTab('statistics')}
        >
          Statistics
        </button>
      </div>

      {/* Content */}
      <div style={propertyPanelStyles.content}>
        {/* Details Tab */}
        {activeTab === 'details' && selectedNodeInfo && (
          <div>
            <div style={propertyPanelStyles.propertyItem}>
              <div style={propertyPanelStyles.propertyLabel}>Type</div>
              <div style={propertyPanelStyles.propertyValue}>{selectedNodeInfo.type}</div>
            </div>

            <div style={propertyPanelStyles.propertyItem}>
              <div style={propertyPanelStyles.propertyLabel}>IRI</div>
              <div style={{ ...propertyPanelStyles.propertyValue, fontFamily: 'monospace', fontSize: '10px', wordBreak: 'break-all' }}>
                {selectedNodeInfo.id}
              </div>
            </div>

            {selectedNodeInfo.uri && selectedNodeInfo.uri !== selectedNodeInfo.id && (
              <div style={propertyPanelStyles.propertyItem}>
                <div style={propertyPanelStyles.propertyLabel}>URI</div>
                <div style={{ ...propertyPanelStyles.propertyValue, fontFamily: 'monospace', fontSize: '10px', wordBreak: 'break-all' }}>
                  {selectedNodeInfo.uri}
                </div>
              </div>
            )}

            {selectedNodeInfo.description && (
              <div style={propertyPanelStyles.propertyItem}>
                <div style={propertyPanelStyles.propertyLabel}>Definition</div>
                <div style={propertyPanelStyles.propertyValue}>{selectedNodeInfo.description}</div>
              </div>
            )}

            {selectedNodeInfo.superClasses && selectedNodeInfo.superClasses.length > 0 && (
              <div style={propertyPanelStyles.propertyItem}>
                <div style={propertyPanelStyles.propertyLabel}>Superclasses ({selectedNodeInfo.superClasses.length})</div>
                <div style={propertyPanelStyles.propertyValue}>
                  {selectedNodeInfo.superClasses.slice(0, 5).map((sc, idx) => (
                    <div key={idx} style={{ marginBottom: '4px', fontSize: '11px', padding: '4px', background: '#f3f4f6', borderRadius: '4px' }}>
                      {sc.split('#').pop() || sc.split('/').pop() || sc}
                    </div>
                  ))}
                  {selectedNodeInfo.superClasses.length > 5 && (
                    <div style={{ fontSize: '11px', color: '#666', fontStyle: 'italic' }}>+ {selectedNodeInfo.superClasses.length - 5} more</div>
                  )}
                </div>
              </div>
            )}

            {selectedNodeInfo.equivalentClasses && selectedNodeInfo.equivalentClasses.length > 0 && (
              <div style={propertyPanelStyles.propertyItem}>
                <div style={propertyPanelStyles.propertyLabel}>Equivalent Classes</div>
                <div style={propertyPanelStyles.propertyValue}>
                  {selectedNodeInfo.equivalentClasses.slice(0, 3).map((ec, idx) => (
                    <div key={idx} style={{ marginBottom: '4px', fontSize: '11px', padding: '4px', background: '#f3f4f6', borderRadius: '4px' }}>
                      {ec.split('#').pop() || ec.split('/').pop() || ec}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {selectedNodeInfo.annotations && Object.keys(selectedNodeInfo.annotations).length > 0 && (
              <div style={propertyPanelStyles.propertyItem}>
                <div style={propertyPanelStyles.propertyLabel}>Annotations ({Object.keys(selectedNodeInfo.annotations).length})</div>
                <div style={propertyPanelStyles.propertyValue}>
                  {Object.entries(selectedNodeInfo.annotations).slice(0, 3).map(([key, value], idx) => (
                    <div key={idx} style={{ marginBottom: '6px', fontSize: '10px' }}>
                      <div style={{ fontWeight: '600', color: '#4b5563' }}>{key}:</div>
                      <div style={{ padding: '2px 4px', background: '#f9fafb', borderRadius: '2px', wordBreak: 'break-word' }}>
                        {typeof value === 'object' ? JSON.stringify(value) : String(value).substring(0, 50)}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {selectedNodeInfo.namespace && (
              <div style={propertyPanelStyles.propertyItem}>
                <div style={propertyPanelStyles.propertyLabel}>Namespace</div>
                <div style={{ ...propertyPanelStyles.propertyValue, fontSize: '10px', fontFamily: 'monospace', wordBreak: 'break-all' }}>
                  {selectedNodeInfo.namespace}
                </div>
              </div>
            )}
          </div>
        )}

        {/* Classes Tab */}
        {activeTab === 'classes' && (
          <div>
            {categorizedNodes.classes.length > 0 ? (
              <div style={propertyPanelStyles.itemList}>
                {categorizedNodes.classes.map(node => (
                  <div
                    key={node.id}
                    style={{
                      ...propertyPanelStyles.listItem,
                      backgroundColor: selectedNodeInfo?.id === node.id ? '#dbeafe' : '#f3f4f6'
                    }}
                    onClick={() => onNodeSelect(node)}
                  >
                    {node.label}
                  </div>
                ))}
              </div>
            ) : (
              <div style={propertyPanelStyles.emptyState}>No classes found</div>
            )}
          </div>
        )}

        {/* Properties Tab */}
        {activeTab === 'properties' && (
          <div>
            {categorizedNodes.objectProperties.length > 0 && (
              <div style={{ marginBottom: '16px' }}>
                <div style={{ fontWeight: '600', marginBottom: '6px', color: '#4b5563', fontSize: '11px' }}>
                  Object Properties
                </div>
                <div style={propertyPanelStyles.itemList}>
                  {categorizedNodes.objectProperties.map(node => (
                    <div
                      key={node.id}
                      style={{
                        ...propertyPanelStyles.listItem,
                        backgroundColor: selectedNodeInfo?.id === node.id ? '#dbeafe' : '#f3f4f6'
                      }}
                      onClick={() => onNodeSelect(node)}
                    >
                      {node.label}
                    </div>
                  ))}
                </div>
              </div>
            )}
            {categorizedNodes.datatypeProperties.length > 0 && (
              <div>
                <div style={{ fontWeight: '600', marginBottom: '6px', color: '#4b5563', fontSize: '11px' }}>
                  Datatype Properties
                </div>
                <div style={propertyPanelStyles.itemList}>
                  {categorizedNodes.datatypeProperties.map(node => (
                    <div
                      key={node.id}
                      style={{
                        ...propertyPanelStyles.listItem,
                        backgroundColor: selectedNodeInfo?.id === node.id ? '#dbeafe' : '#f3f4f6'
                      }}
                      onClick={() => onNodeSelect(node)}
                    >
                      {node.label}
                    </div>
                  ))}
                </div>
              </div>
            )}
            {categorizedNodes.objectProperties.length === 0 && categorizedNodes.datatypeProperties.length === 0 && (
              <div style={propertyPanelStyles.emptyState}>No properties found</div>
            )}
          </div>
        )}

        {/* Individuals Tab */}
        {activeTab === 'individuals' && (
          <div>
            {categorizedNodes.individuals.length > 0 ? (
              <div style={propertyPanelStyles.itemList}>
                {categorizedNodes.individuals.map(node => (
                  <div
                    key={node.id}
                    style={{
                      ...propertyPanelStyles.listItem,
                      backgroundColor: selectedNodeInfo?.id === node.id ? '#dbeafe' : '#f3f4f6'
                    }}
                    onClick={() => onNodeSelect(node)}
                  >
                    {node.label}
                  </div>
                ))}
              </div>
            ) : (
              <div style={propertyPanelStyles.emptyState}>No individuals found</div>
            )}
          </div>
        )}

        {/* Statistics Tab */}
        {activeTab === 'statistics' && (
          <div>
            <div style={{ marginBottom: '16px' }}>
              <div style={{ fontWeight: '600', marginBottom: '8px', color: '#374151' }}>Ontology Statistics</div>
              <div>
                <div style={propertyPanelStyles.statItem}>
                  <span style={propertyPanelStyles.statLabel}>Classes</span>
                  <span style={propertyPanelStyles.statValue}>{statistics.classCount}</span>
                </div>
                <div style={propertyPanelStyles.statItem}>
                  <span style={propertyPanelStyles.statLabel}>Object Properties</span>
                  <span style={propertyPanelStyles.statValue}>{statistics.objectPropertyCount}</span>
                </div>
                <div style={propertyPanelStyles.statItem}>
                  <span style={propertyPanelStyles.statLabel}>Datatype Properties</span>
                  <span style={propertyPanelStyles.statValue}>{statistics.datatypePropertyCount}</span>
                </div>
                <div style={propertyPanelStyles.statItem}>
                  <span style={propertyPanelStyles.statLabel}>Annotation Properties</span>
                  <span style={propertyPanelStyles.statValue}>{statistics.annotationPropertyCount}</span>
                </div>
                <div style={propertyPanelStyles.statItem}>
                  <span style={propertyPanelStyles.statLabel}>Individuals</span>
                  <span style={propertyPanelStyles.statValue}>{statistics.individualCount}</span>
                </div>
                <div style={propertyPanelStyles.statItem}>
                  <span style={propertyPanelStyles.statLabel}>Datatypes</span>
                  <span style={propertyPanelStyles.statValue}>{statistics.datatypeCount}</span>
                </div>
                <div style={propertyPanelStyles.statItem}>
                  <span style={propertyPanelStyles.statLabel}>Total Axioms</span>
                  <span style={propertyPanelStyles.statValue}>{statistics.axiomCount}</span>
                </div>
              </div>
            </div>

            {/* VOWL Legend in Statistics Tab */}
            {viewMode === 'vowl' && vowlLegend && (
              <div>
                <div style={{ fontWeight: '600', marginBottom: '8px', color: '#374151' }}>VOWL Notation Legend</div>
                {vowlLegend.map((item, idx) => (
                  <div key={idx} style={propertyPanelStyles.legendItem}>
                    {item.type === 'node' ? (
                      <div style={{
                        ...propertyPanelStyles.legendIcon,
                        width: '14px',
                        height: '14px',
                        borderRadius: '50%',
                        backgroundColor: vowlNotationService?.getVOWLNodeColor(item.nodeType || '') || '#3b82f6',
                        border: '1px solid #1f2937'
                      }} />
                    ) : (
                      <svg width="14" height="2" style={propertyPanelStyles.legendIcon}>
                        <line 
                          x1="0" y1="1" x2="14" y2="1" 
                          stroke={item.stroke || '#000'}
                          strokeWidth="1.5"
                          strokeDasharray={item.strokeDasharray}
                        />
                      </svg>
                    )}
                    <span>{item.name}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

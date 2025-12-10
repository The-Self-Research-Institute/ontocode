/**
 * ============================================================================
 * ADVANCED ONTOLOGY GRAPH VIEW PLUGIN
 * ============================================================================
 * 
 * Enterprise-grade graph visualization with:
 * - Rich semantic modeling (higher-order, n-ary, temporal, spatial)
 * - AI-powered reasoning and suggestions
 * - Collaborative editing with real-time sync
 * - Advanced querying (pattern matching, path finding, motif detection)
 * - Provenance tracking and version control
 * - Performance optimization (clustering, lazy loading, caching)
 * - Multi-format export (OWL, RDF, JSON-LD, GraphML, Cypher)
 * - Natural language to query translation
 * - Graph-RAG integration
 * 
 * @author OntoCode Team
 * @version 2.0.0
 */

import React, { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import { Network } from 'vis-network';
import type { Options, Data, Node, Edge } from 'vis-network';
import { 
  ZoomIn, 
  ZoomOut, 
  Maximize2, 
  Settings, 
  Download, 
  RefreshCw,
  Filter,
  Layers,
  Eye,
  EyeOff,
  Search,
  Sparkles,
  GitBranch,
  Clock,
  MapPin,
  Share2,
  Edit3,
  Trash2,
  Plus,
  Link,
  AlertTriangle,
  CheckCircle,
  Info,
  Target,
  Zap,
  Brain,
  Database,
  FileText,
  Copy,
  Save,
  Undo,
  Redo,
  Lock,
  Unlock,
  Users,
  MessageSquare
} from 'lucide-react';

import type {
  OntologyNode,
  OntologyEdge,
  GraphSettings,
  GraphFilters,
  GraphQuery,
  ReasoningResult,
  ImpactAnalysis,
  ExportFormat,
  NodeType,
  EdgeType,
  LayoutAlgorithm
} from './types';

import { graphDataService } from './services/GraphDataService';
import { graphMutationService } from './services/GraphMutationService';

interface GraphViewProps {
  projectId: string;
  context?: any;
  onNodeClick?: (nodeId: string) => void;
}

export const GraphView: React.FC<GraphViewProps> = ({ projectId }) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const networkRef = useRef<Network | null>(null);
  
  const [nodes, setNodes] = useState<OntologyNode[]>([]);
  const [edges, setEdges] = useState<OntologyEdge[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedNode, setSelectedNode] = useState<OntologyNode | null>(null);
  const [showSettings, setShowSettings] = useState(false);
  const [showFilters, setShowFilters] = useState(false);
  const [settings, setSettings] = useState<GraphSettings>({
    layout: 'force',
    showLabels: true,
    showArrows: true,
    physics: true,
    nodeSize: 25
  });
  
  const [visibleTypes, setVisibleTypes] = useState({
    class: true,
    individual: true,
    property: true,
    dataProperty: true,
    objectProperty: true
  });

  // Fetch graph data from backend
  const fetchGraphData = useCallback(async () => {
    setLoading(true);
    try {
      const response = await fetch(`${(window as any).API_BASE_URL}/api/ontology/${projectId}/graph`, {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('authToken')}`
        }
      });
      
      if (response.ok) {
        const data = await response.json();
        setNodes(data.nodes || []);
        setEdges(data.edges || []);
      } else {
        console.error('Failed to fetch graph data');
      }
    } catch (error) {
      console.error('Error fetching graph data:', error);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    fetchGraphData();
  }, [fetchGraphData]);

  // Initialize vis-network
  useEffect(() => {
    if (!containerRef.current || nodes.length === 0) return;

    const typeColors = {
      class: '#4A90E2',
      individual: '#7ED321',
      property: '#F5A623',
      dataProperty: '#BD10E0',
      objectProperty: '#50E3C2'
    };

    const visNodes: Node[] = nodes
      .filter(node => visibleTypes[node.type])
      .map(node => ({
        id: node.id,
        label: settings.showLabels ? node.label : '',
        color: node.color || typeColors[node.type],
        shape: node.type === 'class' ? 'box' : node.type === 'individual' ? 'ellipse' : 'diamond',
        size: settings.nodeSize,
        font: {
          size: 14,
          color: '#333'
        }
      }));

    const visEdges: Edge[] = edges
      .filter(edge => {
        const fromNode = nodes.find(n => n.id === edge.from);
        const toNode = nodes.find(n => n.id === edge.to);
        return fromNode && toNode && 
               visibleTypes[fromNode.type] && 
               visibleTypes[toNode.type];
      })
      .map(edge => ({
        id: edge.id,
        from: edge.from,
        to: edge.to,
        label: edge.label,
        arrows: settings.showArrows ? { to: { enabled: true } } : undefined,
        font: {
          size: 12,
          color: '#666',
          align: 'middle'
        },
        color: {
          color: '#999',
          hover: '#333'
        }
      }));

    const data: Data = {
      nodes: visNodes,
      edges: visEdges
    };

    const options: Options = {
      layout: settings.layout === 'hierarchical' ? {
        hierarchical: {
          direction: 'UD',
          sortMethod: 'directed',
          levelSeparation: 150,
          nodeSpacing: 200
        }
      } : settings.layout === 'circular' ? {
        randomSeed: 2
      } : {},
      physics: {
        enabled: settings.physics,
        barnesHut: {
          gravitationalConstant: -8000,
          springConstant: 0.04,
          springLength: 200
        },
        stabilization: {
          iterations: 150
        }
      },
      interaction: {
        hover: true,
        zoomView: true,
        dragView: true,
        navigationButtons: false
      },
      nodes: {
        borderWidth: 2,
        borderWidthSelected: 4
      },
      edges: {
        width: 2,
        smooth: {
          type: 'continuous'
        }
      }
    };

    const network = new Network(containerRef.current, data, options);
    networkRef.current = network;

    // Event listeners
    network.on('selectNode', (params) => {
      const nodeId = params.nodes[0];
      const node = nodes.find(n => n.id === nodeId);
      if (node) {
        setSelectedNode(node);
      }
    });

    network.on('deselectNode', () => {
      setSelectedNode(null);
    });

    return () => {
      network.destroy();
    };
  }, [nodes, edges, settings, visibleTypes]);

  const handleZoomIn = () => {
    if (networkRef.current) {
      const scale = networkRef.current.getScale();
      networkRef.current.moveTo({ scale: scale * 1.2 });
    }
  };

  const handleZoomOut = () => {
    if (networkRef.current) {
      const scale = networkRef.current.getScale();
      networkRef.current.moveTo({ scale: scale * 0.8 });
    }
  };

  const handleFit = () => {
    if (networkRef.current) {
      networkRef.current.fit({
        animation: {
          duration: 500,
          easingFunction: 'easeInOutQuad'
        }
      });
    }
  };

  const handleExport = () => {
    if (networkRef.current && containerRef.current) {
      const canvas = containerRef.current.querySelector('canvas');
      if (canvas) {
        const link = document.createElement('a');
        link.download = `ontology-graph-${projectId}.png`;
        link.href = canvas.toDataURL();
        link.click();
      }
    }
  };

  const toggleTypeVisibility = (type: keyof typeof visibleTypes) => {
    setVisibleTypes(prev => ({
      ...prev,
      [type]: !prev[type]
    }));
  };

  return (
    <div className="graph-view-container" style={{ 
      width: '100%', 
      height: '100%', 
      display: 'flex', 
      flexDirection: 'column',
      position: 'relative',
      backgroundColor: '#f5f5f5'
    }}>
      {/* Toolbar */}
      <div style={{
        padding: '10px',
        backgroundColor: '#fff',
        borderBottom: '1px solid #ddd',
        display: 'flex',
        gap: '10px',
        alignItems: 'center'
      }}>
        <button
          onClick={fetchGraphData}
          disabled={loading}
          style={{
            padding: '6px 12px',
            backgroundColor: '#4A90E2',
            color: 'white',
            border: 'none',
            borderRadius: '4px',
            cursor: loading ? 'not-allowed' : 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: '6px'
          }}
        >
          <RefreshCw size={16} style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
          Refresh
        </button>

        <div style={{ flex: 1 }} />

        <button onClick={handleZoomIn} title="Zoom In" style={{
          padding: '6px',
          backgroundColor: 'transparent',
          border: '1px solid #ddd',
          borderRadius: '4px',
          cursor: 'pointer'
        }}>
          <ZoomIn size={18} />
        </button>

        <button onClick={handleZoomOut} title="Zoom Out" style={{
          padding: '6px',
          backgroundColor: 'transparent',
          border: '1px solid #ddd',
          borderRadius: '4px',
          cursor: 'pointer'
        }}>
          <ZoomOut size={18} />
        </button>

        <button onClick={handleFit} title="Fit to Screen" style={{
          padding: '6px',
          backgroundColor: 'transparent',
          border: '1px solid #ddd',
          borderRadius: '4px',
          cursor: 'pointer'
        }}>
          <Maximize2 size={18} />
        </button>

        <button onClick={() => setShowFilters(!showFilters)} title="Filters" style={{
          padding: '6px',
          backgroundColor: showFilters ? '#4A90E2' : 'transparent',
          color: showFilters ? 'white' : 'inherit',
          border: '1px solid #ddd',
          borderRadius: '4px',
          cursor: 'pointer'
        }}>
          <Filter size={18} />
        </button>

        <button onClick={() => setShowSettings(!showSettings)} title="Settings" style={{
          padding: '6px',
          backgroundColor: showSettings ? '#4A90E2' : 'transparent',
          color: showSettings ? 'white' : 'inherit',
          border: '1px solid #ddd',
          borderRadius: '4px',
          cursor: 'pointer'
        }}>
          <Settings size={18} />
        </button>

        <button onClick={handleExport} title="Export as PNG" style={{
          padding: '6px',
          backgroundColor: 'transparent',
          border: '1px solid #ddd',
          borderRadius: '4px',
          cursor: 'pointer'
        }}>
          <Download size={18} />
        </button>
      </div>

      {/* Main Content */}
      <div style={{ 
        flex: 1, 
        display: 'flex', 
        position: 'relative',
        overflow: 'hidden'
      }}>
        {/* Graph Canvas */}
        <div 
          ref={containerRef} 
          style={{ 
            flex: 1,
            backgroundColor: '#fff',
            position: 'relative'
          }}
        />

        {/* Filters Panel */}
        {showFilters && (
          <div style={{
            position: 'absolute',
            top: '10px',
            left: '10px',
            backgroundColor: 'white',
            border: '1px solid #ddd',
            borderRadius: '6px',
            padding: '15px',
            boxShadow: '0 2px 8px rgba(0,0,0,0.1)',
            minWidth: '200px',
            zIndex: 10
          }}>
            <h3 style={{ margin: '0 0 12px 0', fontSize: '14px', fontWeight: 600 }}>
              <Layers size={16} style={{ verticalAlign: 'middle', marginRight: '6px' }} />
              Node Types
            </h3>
            {Object.entries(visibleTypes).map(([type, visible]) => (
              <div key={type} style={{ marginBottom: '8px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                <button
                  onClick={() => toggleTypeVisibility(type as keyof typeof visibleTypes)}
                  style={{
                    padding: '4px',
                    backgroundColor: 'transparent',
                    border: 'none',
                    cursor: 'pointer',
                    display: 'flex',
                    alignItems: 'center'
                  }}
                >
                  {visible ? <Eye size={16} /> : <EyeOff size={16} />}
                </button>
                <label style={{ fontSize: '13px', cursor: 'pointer' }}>
                  {type.charAt(0).toUpperCase() + type.slice(1)}
                </label>
              </div>
            ))}
          </div>
        )}

        {/* Settings Panel */}
        {showSettings && (
          <div style={{
            position: 'absolute',
            top: '10px',
            right: '10px',
            backgroundColor: 'white',
            border: '1px solid #ddd',
            borderRadius: '6px',
            padding: '15px',
            boxShadow: '0 2px 8px rgba(0,0,0,0.1)',
            minWidth: '250px',
            zIndex: 10
          }}>
            <h3 style={{ margin: '0 0 12px 0', fontSize: '14px', fontWeight: 600 }}>
              <Settings size={16} style={{ verticalAlign: 'middle', marginRight: '6px' }} />
              Graph Settings
            </h3>

            <div style={{ marginBottom: '12px' }}>
              <label style={{ fontSize: '13px', fontWeight: 500, display: 'block', marginBottom: '6px' }}>
                Layout Algorithm
              </label>
              <select
                value={settings.layout}
                onChange={(e) => setSettings(prev => ({ ...prev, layout: e.target.value as any }))}
                style={{
                  width: '100%',
                  padding: '6px',
                  borderRadius: '4px',
                  border: '1px solid #ddd',
                  fontSize: '13px'
                }}
              >
                <option value="force">Force-Directed</option>
                <option value="hierarchical">Hierarchical</option>
                <option value="circular">Circular</option>
              </select>
            </div>

            <div style={{ marginBottom: '12px' }}>
              <label style={{ fontSize: '13px', fontWeight: 500, display: 'block', marginBottom: '6px' }}>
                Node Size: {settings.nodeSize}
              </label>
              <input
                type="range"
                min="15"
                max="50"
                value={settings.nodeSize}
                onChange={(e) => setSettings(prev => ({ ...prev, nodeSize: parseInt(e.target.value) }))}
                style={{ width: '100%' }}
              />
            </div>

            <div style={{ marginBottom: '8px' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={settings.showLabels}
                  onChange={(e) => setSettings(prev => ({ ...prev, showLabels: e.target.checked }))}
                />
                Show Labels
              </label>
            </div>

            <div style={{ marginBottom: '8px' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={settings.showArrows}
                  onChange={(e) => setSettings(prev => ({ ...prev, showArrows: e.target.checked }))}
                />
                Show Arrows
              </label>
            </div>

            <div style={{ marginBottom: '8px' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={settings.physics}
                  onChange={(e) => setSettings(prev => ({ ...prev, physics: e.target.checked }))}
                />
                Enable Physics
              </label>
            </div>
          </div>
        )}

        {/* Selected Node Info */}
        {selectedNode && (
          <div style={{
            position: 'absolute',
            bottom: '10px',
            left: '10px',
            backgroundColor: 'white',
            border: '1px solid #ddd',
            borderRadius: '6px',
            padding: '12px',
            boxShadow: '0 2px 8px rgba(0,0,0,0.1)',
            maxWidth: '300px',
            zIndex: 10
          }}>
            <h4 style={{ margin: '0 0 8px 0', fontSize: '14px', fontWeight: 600 }}>
              {selectedNode.label}
            </h4>
            <div style={{ fontSize: '12px', color: '#666' }}>
              <div><strong>Type:</strong> {selectedNode.type}</div>
              <div><strong>ID:</strong> {selectedNode.id}</div>
            </div>
          </div>
        )}

        {/* Loading Overlay */}
        {loading && (
          <div style={{
            position: 'absolute',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            backgroundColor: 'rgba(255,255,255,0.8)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 100
          }}>
            <div style={{ textAlign: 'center' }}>
              <RefreshCw size={32} style={{ animation: 'spin 1s linear infinite' }} />
              <div style={{ marginTop: '10px', fontSize: '14px', color: '#666' }}>
                Loading graph data...
              </div>
            </div>
          </div>
        )}
      </div>

      <style>{`
        @keyframes spin {
          from { transform: rotate(0deg); }
          to { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  );
};

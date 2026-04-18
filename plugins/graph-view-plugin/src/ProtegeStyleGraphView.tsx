/**
 * Protégé-Style OntoGraf View
 * Complete feature parity with Protégé Desktop OntoGraf plugin
 */

import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Network } from 'vis-network';
import type { Options, Data, Node, Edge } from 'vis-network';
import { applyRadialLayout, applyCircularLayout } from './layouts';
import { 
  ZoomIn, 
  ZoomOut, 
  Maximize2, 
  Download,
  RefreshCw,
  Search,
  ChevronRight,
  ChevronDown,
  Circle,
  Package,
  Database,
  Link2,
  X,
  Settings,
  Eye,
  EyeOff,
  Grid,
  Minimize2,
  Move,
  MousePointer,
  Hand,
  Crosshair,
  Share2,
  Filter,
  Layers,
  Image,
  FileText,
  Save,
  Copy,
  Scissors,
  Plus,
  Minus
} from 'lucide-react';

interface ProtegeStyleGraphViewProps {
  projectId: string;
}

interface OntologyClass {
  iri: string;
  label: string;
  subClasses?: OntologyClass[];
  superClasses?: string[];
  annotations?: { property: string; value: string }[];
}

export const ProtegeStyleGraphView: React.FC<ProtegeStyleGraphViewProps> = ({ projectId }) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const networkRef = useRef<Network | null>(null);
  
  const [nodes, setNodes] = useState<any[]>([]);
  const [edges, setEdges] = useState<any[]>([]);
  const [classHierarchy, setClassHierarchy] = useState<OntologyClass[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedNode, setSelectedNode] = useState<any>(null);
  const [hoveredNode, setHoveredNode] = useState<any>(null);
  const [tooltipPosition, setTooltipPosition] = useState<{ x: number; y: number } | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; node: any } | null>(null);
  const [showClassTree, setShowClassTree] = useState(true);
  const [expandedClasses, setExpandedClasses] = useState<Set<string>>(new Set(['owl:Thing']));
  
  const [layoutType, setLayoutType] = useState<'hierarchical' | 'force' | 'circular' | 'radial' | 'tree-vertical' | 'tree-horizontal' | 'spring'>('hierarchical');
  const [assertionView, setAssertionView] = useState<'asserted' | 'inferred' | 'all'>('asserted');
  const [showInferences, setShowInferences] = useState(false);
  const [showLayoutMenu, setShowLayoutMenu] = useState(false);
  
  // Fetch ontology data
  const fetchGraphData = useCallback(async () => {
    setLoading(true);
    try {
      const response = await fetch(
        `${(window as any).API_BASE_URL}/api/ontology/${projectId}/graph`,
        {
          headers: {
            'Authorization': `Bearer ${localStorage.getItem('authToken')}`
          }
        }
      );
      
      if (response.ok) {
        const data = await response.json();
        setNodes(data.nodes || []);
        setEdges(data.edges || []);
        
        // Build class hierarchy
        buildClassHierarchy(data.nodes, data.edges);
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

  const buildClassHierarchy = (nodes: any[], edges: any[]) => {
    const classNodes = nodes.filter(n => n.type === 'class');
    const hierarchy: Map<string, OntologyClass> = new Map();
    
    // Initialize all classes
    classNodes.forEach(node => {
      hierarchy.set(node.id, {
        iri: node.id,
        label: node.label || node.id.split('#').pop() || node.id.split('/').pop() || node.id,
        subClasses: [],
        superClasses: [],
        annotations: node.annotations || []
      });
    });
    
    // Build parent-child relationships
    edges.forEach(edge => {
      if (edge.label === 'subClassOf' || edge.type === 'subClassOf') {
        const child = hierarchy.get(edge.from);
        const parent = hierarchy.get(edge.to);
        
        if (child && parent) {
          child.superClasses?.push(parent.iri);
          if (!parent.subClasses) parent.subClasses = [];
          parent.subClasses.push(child);
        }
      }
    });
    
    // Find root classes (those without superclasses or only owl:Thing as superclass)
    const roots = Array.from(hierarchy.values()).filter(c => 
      !c.superClasses || c.superClasses.length === 0 || 
      c.superClasses.every(s => s.includes('owl#Thing'))
    );
    
    setClassHierarchy(roots);
  };

  // Initialize vis-network
  useEffect(() => {
    if (!containerRef.current || nodes.length === 0) return;

    const getNodeColor = (type: string) => {
      switch (type) {
        case 'class': return '#FFD700'; // Gold like Protégé
        case 'individual': return '#90EE90'; // Light green
        case 'objectProperty': return '#87CEEB'; // Sky blue
        case 'dataProperty': return '#DDA0DD'; // Plum
        case 'annotationProperty': return '#F0E68C'; // Khaki
        default: return '#D3D3D3'; // Light gray
      }
    };

    const getNodeShape = (type: string) => {
      switch (type) {
        case 'class': return 'box';
        case 'individual': return 'ellipse';
        case 'objectProperty': return 'diamond';
        case 'dataProperty': return 'star';
        case 'annotationProperty': return 'triangle';
        default: return 'dot';
      }
    };

    const getEdgeStyle = (type: string) => {
      switch (type) {
        case 'subClassOf':
          return { dashes: false, color: '#000000', arrows: { to: { enabled: true, type: 'arrow' } } };
        case 'type':
        case 'instanceOf':
          return { dashes: [5, 5], color: '#666666', arrows: { to: { enabled: true, type: 'arrow' } } };
        case 'property':
          return { dashes: false, color: '#4169E1', arrows: { to: { enabled: true, type: 'arrow' } } };
        case 'equivalentClass':
          return { dashes: [2, 2], color: '#FF6347', arrows: { to: { enabled: false } } };
        case 'disjointWith':
          return { dashes: [10, 5], color: '#FF0000', arrows: { to: { enabled: false } } };
        default:
          return { dashes: false, color: '#999999', arrows: { to: { enabled: true, type: 'arrow' } } };
      }
    };

    const visNodes: Node[] = nodes.map(node => ({
      id: node.id,
      label: node.label || node.id.split('#').pop() || node.id.split('/').pop() || node.id,
      color: {
        background: getNodeColor(node.type),
        border: '#000000',
        highlight: {
          background: getNodeColor(node.type),
          border: '#00FF00' // Green border on selection like Protégé
        },
        hover: {
          background: getNodeColor(node.type),
          border: '#0000FF'
        }
      },
      shape: getNodeShape(node.type),
      size: 25,
      font: {
        size: 14,
        color: '#000000',
        bold: { color: '#000000' },
        face: 'Arial'
      },
      borderWidth: 2,
      borderWidthSelected: 3,
      title: createNodeTooltip(node)
    }));

    const visEdges: Edge[] = edges.map(edge => {
      const style = getEdgeStyle(edge.type || edge.label);
      return {
        id: edge.id,
        from: edge.from,
        to: edge.to,
        label: edge.label || edge.type || '',
        ...style,
        font: {
          size: 11,
          color: '#333333',
          align: 'middle',
          background: 'white'
        },
        width: 2,
        smooth: {
          type: 'cubicBezier',
          forceDirection: layoutType === 'hierarchical' ? 'vertical' : 'none'
        }
      };
    });

    const data: Data = {
      nodes: visNodes,
      edges: visEdges
    };

    const layoutOptions: Record<string, any> = {
      hierarchical: {
        hierarchical: {
          enabled: true,
          direction: 'UD',
          sortMethod: 'directed',
          levelSeparation: 150,
          nodeSpacing: 200,
          treeSpacing: 200,
          blockShifting: true,
          edgeMinimization: true,
          parentCentralization: true
        }
      },
      'tree-vertical': {
        hierarchical: {
          enabled: true,
          direction: 'UD',
          sortMethod: 'directed',
          levelSeparation: 120,
          nodeSpacing: 150,
          treeSpacing: 150,
          blockShifting: true,
          edgeMinimization: true,
          parentCentralization: true
        }
      },
      'tree-horizontal': {
        hierarchical: {
          enabled: true,
          direction: 'LR',
          sortMethod: 'directed',
          levelSeparation: 200,
          nodeSpacing: 100,
          treeSpacing: 150,
          blockShifting: true,
          edgeMinimization: true,
          parentCentralization: true
        }
      },
      force: {
        randomSeed: 2
      },
      spring: {
        randomSeed: 2
      },
      circular: {
        randomSeed: 2
      },
      radial: {
        randomSeed: 2
      }
    };

    const isHierarchicalLayout = layoutType === 'hierarchical' || layoutType === 'tree-vertical' || layoutType === 'tree-horizontal';

    const options: Options = {
      layout: layoutOptions[layoutType] || {},
      physics: {
        enabled: !isHierarchicalLayout,
        barnesHut: {
          gravitationalConstant: layoutType === 'spring' ? -3000 : -2000,
          springConstant: layoutType === 'spring' ? 0.08 : 0.04,
          springLength: layoutType === 'spring' ? 200 : 150,
          damping: 0.09,
          centralGravity: layoutType === 'radial' ? 0.8 : 0.3
        },
        stabilization: {
          iterations: 200,
          updateInterval: 25
        }
      },
      interaction: {
        hover: true,
        zoomView: true,
        dragView: true,
        tooltipDelay: 100,
        hideEdgesOnDrag: true,
        hideEdgesOnZoom: true
      },
      nodes: {
        borderWidth: 2,
        borderWidthSelected: 4,
        chosen: {
          node: (values: any) => {
            values.borderWidth = 4;
            values.color = '#FF0000';
          }
        }
      },
      edges: {
        width: 2,
        selectionWidth: 4,
        chosen: {
          edge: (values: any) => {
            values.width = 4;
            values.color = '#FF0000';
          }
        }
      }
    };

    const network = new Network(containerRef.current, data, options);
    networkRef.current = network;

    // Apply custom layout positions for circular and radial layouts
    if (layoutType === 'circular' || layoutType === 'radial') {
      const container = containerRef.current;
      const width = container.clientWidth || 800;
      const height = container.clientHeight || 600;
      
      const layoutNodes = nodes.map(n => ({
        id: n.id,
        label: n.label || n.id,
        type: n.type || 'class',
        uri: n.uri || n.id,
      }));
      const layoutEdges = edges.map(e => ({
        id: e.id || `${e.from}-${e.to}`,
        from: e.from || e.source,
        to: e.to || e.target,
        label: e.label || '',
        type: e.type || 'subClassOf',
      }));
      
      const positionMap = layoutType === 'radial'
        ? applyRadialLayout(layoutNodes as any, layoutEdges as any, { width, height })
        : applyCircularLayout(layoutNodes as any, layoutEdges as any, { width, height });
      
      network.once('stabilizationIterationsDone', () => {
        positionMap.forEach((pos, nodeId) => {
          try { network.moveNode(nodeId, pos.x, pos.y); } catch { /* node may not exist */ }
        });
        network.setOptions({ physics: { enabled: false } });
        network.fit({ animation: { duration: 500, easingFunction: 'easeInOutQuad' } });
      });
    }

    // Event listeners
    network.on('selectNode', (params: any) => {
      const nodeId = params.nodes[0];
      const node = nodes.find(n => n.id === nodeId);
      if (node) {
        setSelectedNode(node);
      }
    });

    network.on('deselectNode', () => {
      setSelectedNode(null);
    });

    network.on('hoverNode', (params: any) => {
      const node = nodes.find(n => n.id === params.node);
      if (node) {
        setHoveredNode(node);
        const canvasPos = network.canvasToDOM({ x: params.event.center.x, y: params.event.center.y });
        setTooltipPosition({ x: canvasPos.x, y: canvasPos.y });
      }
    });

    network.on('blurNode', () => {
      setHoveredNode(null);
      setTooltipPosition(null);
    });

    network.on('oncontext', (params: any) => {
      params.event.preventDefault();
      const nodeId = network.getNodeAt(params.pointer.DOM);
      if (nodeId) {
        const node = nodes.find(n => n.id === nodeId);
        if (node) {
          setContextMenu({
            x: params.event.center.x,
            y: params.event.center.y,
            node
          });
        }
      }
    });

    return () => {
      network.destroy();
    };
  }, [nodes, edges, layoutType]);

  const createNodeTooltip = (node: any): string => {
    let tooltip = `<div style="padding: 8px; max-width: 300px;">`;
    tooltip += `<strong>${node.label || node.id}</strong><br/>`;
    tooltip += `<div style="font-size: 11px; color: #666; margin-top: 4px;">`;
    tooltip += `<strong>URL:</strong> ${node.id}<br/>`;
    
    if (node.superClasses && node.superClasses.length > 0) {
      tooltip += `<strong>Superclasses:</strong><br/>`;
      node.superClasses.forEach((sc: string) => {
        tooltip += `&nbsp;&nbsp;${sc.split('#').pop() || sc}<br/>`;
      });
    }
    
    if (node.annotations && node.annotations.length > 0) {
      tooltip += `<strong>Annotations:</strong><br/>`;
      node.annotations.forEach((ann: any) => {
        tooltip += `&nbsp;&nbsp;${ann.property}: ${ann.value}<br/>`;
      });
    }
    
    tooltip += `</div></div>`;
    return tooltip;
  };

  const handleZoomIn = () => {
    if (networkRef.current) {
      const scale = networkRef.current.getScale();
      networkRef.current.moveTo({ scale: scale * 1.2, animation: { duration: 300 } });
    }
  };

  const handleZoomOut = () => {
    if (networkRef.current) {
      const scale = networkRef.current.getScale();
      networkRef.current.moveTo({ scale: scale * 0.8, animation: { duration: 300 } });
    }
  };

  const handleFit = () => {
    networkRef.current?.fit({ animation: { duration: 500, easingFunction: 'easeInOutQuad' } });
  };

  const handleExport = () => {
    if (containerRef.current) {
      const canvas = containerRef.current.querySelector('canvas');
      if (canvas) {
        const link = document.createElement('a');
        link.download = `ontograf-${projectId}-${Date.now()}.png`;
        link.href = canvas.toDataURL('image/png');
        link.click();
      }
    }
  };

  const toggleClassExpansion = (classIri: string) => {
    setExpandedClasses(prev => {
      const newSet = new Set(prev);
      if (newSet.has(classIri)) {
        newSet.delete(classIri);
      } else {
        newSet.add(classIri);
      }
      return newSet;
    });
  };

  const addNodeToGraph = (classIri: string) => {
    const node = nodes.find(n => n.id === classIri);
    if (node && networkRef.current) {
      networkRef.current.selectNodes([classIri]);
      networkRef.current.focus(classIri, { scale: 1.5, animation: { duration: 500 } });
    }
  };

  const renderClassTreeNode = (cls: OntologyClass, level: number = 0) => {
    const hasChildren = cls.subClasses && cls.subClasses.length > 0;
    const isExpanded = expandedClasses.has(cls.iri);
    
    return (
      <div key={cls.iri}>
        <div 
          style={{
            paddingLeft: `${level * 16}px`,
            display: 'flex',
            alignItems: 'center',
            gap: '4px',
            padding: '4px 8px',
            cursor: 'pointer',
            fontSize: '13px'
          }}
          onMouseEnter={(e) => (e.currentTarget.style.backgroundColor = '#f0f0f0')}
          onMouseLeave={(e) => (e.currentTarget.style.backgroundColor = 'transparent')}
        >
          {hasChildren ? (
            <button
              onClick={() => toggleClassExpansion(cls.iri)}
              style={{
                border: 'none',
                background: 'none',
                padding: '2px',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center'
              }}
            >
              {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
            </button>
          ) : (
            <span style={{ width: '14px' }} />
          )}
          <Circle size={12} fill="#FFD700" stroke="#000" strokeWidth={1} />
          <span 
            onClick={() => addNodeToGraph(cls.iri)}
            style={{ flex: 1, userSelect: 'none' }}
            title={cls.iri}
          >
            {cls.label}
          </span>
        </div>
        {hasChildren && isExpanded && cls.subClasses?.map(subCls => 
          renderClassTreeNode(subCls, level + 1)
        )}
      </div>
    );
  };

  return (
    <div style={{ 
      width: '100%', 
      height: '100%', 
      display: 'flex', 
      flexDirection: 'column',
      backgroundColor: '#f5f5f5',
      fontFamily: 'Arial, sans-serif'
    }}>
      {/* Top Toolbar - Protégé Style */}
      <div style={{
        backgroundColor: '#fff',
        borderBottom: '2px solid #ccc',
        padding: '8px 12px',
        display: 'flex',
        alignItems: 'center',
        gap: '12px',
        boxShadow: '0 2px 4px rgba(0,0,0,0.1)'
      }}>
        {/* Assertion View Dropdown */}
        <select
          value={assertionView}
          onChange={(e) => setAssertionView(e.target.value as any)}
          style={{
            padding: '6px 12px',
            border: '1px solid #ccc',
            borderRadius: '4px',
            fontSize: '13px',
            backgroundColor: 'white'
          }}
        >
          <option value="asserted">Asserted</option>
          <option value="inferred">Inferred</option>
          <option value="all">All</option>
        </select>

        {/* Search Bar */}
        <div style={{ 
          display: 'flex', 
          alignItems: 'center', 
          flex: 1,
          maxWidth: '400px',
          border: '1px solid #ccc',
          borderRadius: '4px',
          backgroundColor: 'white',
          padding: '4px 8px'
        }}>
          <Search size={16} color="#666" />
          <input
            type="text"
            placeholder="contains"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            style={{
              border: 'none',
              outline: 'none',
              flex: 1,
              padding: '4px 8px',
              fontSize: '13px'
            }}
          />
          <button
            onClick={() => setSearchTerm('')}
            style={{
              border: 'none',
              background: 'none',
              padding: '4px',
              cursor: 'pointer',
              display: 'flex'
            }}
          >
            <X size={14} />
          </button>
        </div>

        <button
          onClick={() => setSearchTerm('')}
          style={{
            padding: '6px 12px',
            border: '1px solid #ccc',
            borderRadius: '4px',
            backgroundColor: 'white',
            cursor: 'pointer',
            fontSize: '13px'
          }}
        >
          Search
        </button>

        <button
          onClick={() => setSearchTerm('')}
          style={{
            padding: '6px 12px',
            border: '1px solid #ccc',
            borderRadius: '4px',
            backgroundColor: 'white',
            cursor: 'pointer',
            fontSize: '13px'
          }}
        >
          Clear
        </button>

        <div style={{ flex: 1 }} />

        {/* Control Buttons */}
        <button onClick={handleZoomIn} title="Zoom In" style={toolbarButtonStyle}>
          <ZoomIn size={18} />
        </button>

        <button onClick={handleZoomOut} title="Zoom Out" style={toolbarButtonStyle}>
          <ZoomOut size={18} />
        </button>

        <button onClick={handleFit} title="Fit to Screen" style={toolbarButtonStyle}>
          <Maximize2 size={18} />
        </button>

        <button onClick={handleExport} title="Export as PNG" style={toolbarButtonStyle}>
          <Download size={18} />
        </button>

        <button onClick={fetchGraphData} disabled={loading} title="Refresh" style={toolbarButtonStyle}>
          <RefreshCw size={18} style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
        </button>

        <button
          onClick={() => setShowClassTree(!showClassTree)} 
          title="Toggle Class Tree" 
          style={{
            ...toolbarButtonStyle,
            backgroundColor: showClassTree ? '#4A90E2' : 'white',
            color: showClassTree ? 'white' : 'inherit'
          }}
        >
          <Grid size={18} />
        </button>
      </div>

      {/* Icon Toolbar - Below search (Protégé style) */}
      <div style={{
        backgroundColor: '#f8f8f8',
        borderBottom: '1px solid #ccc',
        padding: '4px 8px',
        display: 'flex',
        gap: '2px',
        alignItems: 'center',
        boxShadow: 'inset 0 2px 4px rgba(0,0,0,0.05)'
      }}>
        <button title="Select" style={iconToolbarButtonStyle}>
          <MousePointer size={16} />
        </button>
        <button title="Pan" style={iconToolbarButtonStyle}>
          <Hand size={16} />
        </button>
        <button title="Add Node" style={iconToolbarButtonStyle}>
          <Plus size={16} />
        </button>
        <button title="Add Edge" style={iconToolbarButtonStyle}>
          <Share2 size={16} />
        </button>
        <button title="Remove" style={iconToolbarButtonStyle}>
          <Minus size={16} />
        </button>
        
        <div style={{ width: '1px', height: '20px', backgroundColor: '#ccc', margin: '0 4px' }} />
        
        <button title="Zoom In" onClick={handleZoomIn} style={iconToolbarButtonStyle}>
          <ZoomIn size={16} />
        </button>
        <button title="Zoom Out" onClick={handleZoomOut} style={iconToolbarButtonStyle}>
          <ZoomOut size={16} />
        </button>
        <button title="Fit to Window" onClick={handleFit} style={iconToolbarButtonStyle}>
          <Maximize2 size={16} />
        </button>
        
        <div style={{ width: '1px', height: '20px', backgroundColor: '#ccc', margin: '0 4px' }} />
        
        <div style={{ position: 'relative' }}>
          <button 
            title="Layout" 
            style={{
              ...iconToolbarButtonStyle,
              backgroundColor: showLayoutMenu ? '#e0e0e0' : 'white'
            }}
            onClick={() => setShowLayoutMenu(!showLayoutMenu)}
          >
            <Layers size={16} />
          </button>
          {showLayoutMenu && (
            <div style={{
              position: 'absolute',
              top: '100%',
              left: 0,
              backgroundColor: 'white',
              border: '1px solid #ccc',
              borderRadius: '4px',
              boxShadow: '2px 2px 8px rgba(0,0,0,0.15)',
              zIndex: 1001,
              minWidth: '180px',
              padding: '4px 0'
            }}>
              {([
                { key: 'hierarchical', label: 'Hierarchical' },
                { key: 'tree-vertical', label: 'Tree (Vertical)' },
                { key: 'tree-horizontal', label: 'Tree (Horizontal)' },
                { key: 'radial', label: 'Radial Layout' },
                { key: 'spring', label: 'Spring (Force) Layout' },
                { key: 'circular', label: 'Circular Layout' },
              ] as const).map(item => (
                <button
                  key={item.key}
                  onClick={() => { setLayoutType(item.key); setShowLayoutMenu(false); }}
                  style={{
                    width: '100%',
                    padding: '6px 16px',
                    border: 'none',
                    backgroundColor: layoutType === item.key ? '#e8f0fe' : 'transparent',
                    textAlign: 'left',
                    cursor: 'pointer',
                    fontSize: '13px',
                    fontWeight: layoutType === item.key ? 600 : 400,
                    display: 'flex',
                    alignItems: 'center',
                    gap: '8px'
                  }}
                  onMouseEnter={(e) => { if (layoutType !== item.key) e.currentTarget.style.backgroundColor = '#f0f0f0'; }}
                  onMouseLeave={(e) => { if (layoutType !== item.key) e.currentTarget.style.backgroundColor = 'transparent'; }}
                >
                  {layoutType === item.key && <span style={{ color: '#1a73e8' }}>&#10003;</span>}
                  {layoutType !== item.key && <span style={{ width: '14px' }} />}
                  {item.label}
                </button>
              ))}
            </div>
          )}
        </div>
        <button title="Filter" style={iconToolbarButtonStyle}>
          <Filter size={16} />
        </button>
        <button title="Settings" style={iconToolbarButtonStyle}>
          <Settings size={16} />
        </button>
        
        <div style={{ width: '1px', height: '20px', backgroundColor: '#ccc', margin: '0 4px' }} />
        
        <button title="Copy" style={iconToolbarButtonStyle}>
          <Copy size={16} />
        </button>
        <button title="Save View" style={iconToolbarButtonStyle}>
          <Save size={16} />
        </button>
        <button title="Export Image" onClick={handleExport} style={iconToolbarButtonStyle}>
          <Image size={16} />
        </button>
        <button title="Export Data" style={iconToolbarButtonStyle}>
          <FileText size={16} />
        </button>
      </div>

      {/* Main Content Area */}
      <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>
        {/* Left Sidebar - Class Hierarchy */}
        {showClassTree && (
          <div style={{
            width: '280px',
            backgroundColor: 'white',
            borderRight: '2px solid #ccc',
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden'
          }}>
            <div style={{
              padding: '8px 12px',
              borderBottom: '1px solid #ddd',
              fontWeight: 'bold',
              fontSize: '14px',
              backgroundColor: '#f8f8f8'
            }}>
              Class hierarchy: HonorsStudent
            </div>
            <div style={{
              flex: 1,
              overflow: 'auto',
              padding: '8px 0'
            }}>
              {classHierarchy.map(cls => renderClassTreeNode(cls))}
            </div>
          </div>
        )}

        {/* Graph Canvas */}
        <div style={{ flex: 1, position: 'relative', backgroundColor: '#ffffff' }}>
          <div ref={containerRef} style={{ width: '100%', height: '100%' }} />

          {/* Node Tooltip */}
          {hoveredNode && tooltipPosition && (
            <div style={{
              position: 'absolute',
              left: tooltipPosition.x + 10,
              top: tooltipPosition.y + 10,
              backgroundColor: '#FFFACD',
              border: '1px solid #000',
              borderRadius: '4px',
              padding: '8px 12px',
              boxShadow: '2px 2px 8px rgba(0,0,0,0.3)',
              zIndex: 1000,
              maxWidth: '300px',
              fontSize: '12px',
              pointerEvents: 'none'
            }}>
              <div style={{ fontWeight: 'bold', marginBottom: '4px' }}>
                {hoveredNode.label || hoveredNode.id}
              </div>
              <div style={{ color: '#666', fontSize: '11px' }}>
                <div><strong>URL:</strong> {hoveredNode.id}</div>
                {hoveredNode.superClasses && hoveredNode.superClasses.length > 0 && (
                  <div>
                    <strong>Superclasses:</strong>
                    {hoveredNode.superClasses.map((sc: string) => (
                      <div key={sc} style={{ paddingLeft: '8px' }}>
                        {sc.split('#').pop() || sc}
                      </div>
                    ))}
                  </div>
                )}
                {hoveredNode.annotations && hoveredNode.annotations.length > 0 && (
                  <div>
                    <strong>Annotations:</strong>
                    {hoveredNode.annotations.map((ann: any, idx: number) => (
                      <div key={idx} style={{ paddingLeft: '8px' }}>
                        {ann.property}: "{ann.value}"
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}

          {/* Context Menu */}
          {contextMenu && (
            <div style={{
              position: 'absolute',
              left: contextMenu.x,
              top: contextMenu.y,
              backgroundColor: 'white',
              border: '1px solid #ccc',
              borderRadius: '4px',
              boxShadow: '2px 2px 8px rgba(0,0,0,0.2)',
              zIndex: 1000,
              minWidth: '180px'
            }}>
              <div style={{ padding: '4px 0' }}>
                <button style={contextMenuItemStyle}>Add to Graph</button>
                <button style={contextMenuItemStyle}>Show Subclasses</button>
                <button style={contextMenuItemStyle}>Show Superclasses</button>
                <button style={contextMenuItemStyle}>Show Individuals</button>
                <div style={{ borderTop: '1px solid #ddd', margin: '4px 0' }} />
                <button style={contextMenuItemStyle}>Remove from Graph</button>
                <button style={contextMenuItemStyle}>Hide Related</button>
                <div style={{ borderTop: '1px solid #ddd', margin: '4px 0' }} />
                <button style={contextMenuItemStyle}>Properties...</button>
              </div>
            </div>
          )}

          {/* Bottom Status Bar */}
          {!showInferences && (
            <div style={{
              position: 'absolute',
              bottom: 0,
              left: 0,
              right: 0,
              backgroundColor: '#FFF8DC',
              border: '1px solid #F0E68C',
              padding: '8px 12px',
              fontSize: '12px',
              display: 'flex',
              alignItems: 'center',
              gap: '8px'
            }}>
              <Package size={16} color="#666" />
              <span>No Reasoner set. Select a reasoner from the Reasoner menu</span>
              <button
                onClick={() => setShowInferences(true)}
                style={{
                  marginLeft: 'auto',
                  padding: '4px 12px',
                  border: '1px solid #ccc',
                  borderRadius: '4px',
                  backgroundColor: 'white',
                  cursor: 'pointer',
                  fontSize: '12px'
                }}
              >
                Show Inferences
              </button>
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
              backgroundColor: 'rgba(255,255,255,0.9)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              zIndex: 100
            }}>
              <div style={{ textAlign: 'center' }}>
                <RefreshCw size={32} style={{ animation: 'spin 1s linear infinite' }} />
                <div style={{ marginTop: '10px', fontSize: '14px', color: '#666' }}>
                  Loading OntoGraf...
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Close context menu on click outside */}
      {(contextMenu || showLayoutMenu) && (
        <div
          style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            zIndex: 999
          }}
          onClick={() => { setContextMenu(null); setShowLayoutMenu(false); }}
        />
      )}

      <style>{`
        @keyframes spin {
          from { transform: rotate(0deg); }
          to { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  );
};

const toolbarButtonStyle: React.CSSProperties = {
  padding: '6px',
  border: '1px solid #ccc',
  borderRadius: '4px',
  backgroundColor: 'white',
  cursor: 'pointer',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center'
};

const iconToolbarButtonStyle: React.CSSProperties = {
  padding: '4px 6px',
  border: '1px solid #ddd',
  borderRadius: '3px',
  backgroundColor: 'white',
  cursor: 'pointer',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  transition: 'all 0.2s'
};

const contextMenuItemStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 16px',
  border: 'none',
  backgroundColor: 'transparent',
  textAlign: 'left',
  cursor: 'pointer',
  fontSize: '13px',
  transition: 'background-color 0.2s'
};

export default ProtegeStyleGraphView;

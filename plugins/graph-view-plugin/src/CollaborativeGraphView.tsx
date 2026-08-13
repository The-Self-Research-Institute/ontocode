import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Network } from 'vis-network';
import { DataSet } from 'vis-data';
import type { Options, Node, Edge, IdType } from 'vis-network';
import SockJS from 'sockjs-client';
import { Client, Stomp, IMessage } from '@stomp/stompjs';
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
  Users,
  Expand
} from 'lucide-react';

interface GraphViewProps {
  projectId: string;
  userId?: string;
  username?: string;
}

interface GraphNode {
  id: string;
  label: string;
  type: 'class' | 'individual' | 'objectProperty' | 'datatypeProperty' | 'property';
  color?: string;
  hasChildren?: boolean;
  expanded?: boolean;
}

interface GraphEdge {
  id: string;
  from: string;
  to: string;
  label: string;
  type: string;
}

interface GraphSettings {
  layout: 'hierarchical' | 'force' | 'circular';
  showLabels: boolean;
  showArrows: boolean;
  physics: boolean;
  nodeSize: number;
  lazyLoading: boolean;
}

interface CollaborativeUser {
  userId: string;
  username: string;
  color: string;
  selectedNodeId?: string;
  cursorPosition?: { x: number; y: number };
}

export const CollaborativeGraphView: React.FC<GraphViewProps> = ({
  projectId,
  userId = `user_${Date.now()}`,
  username = 'Anonymous'
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const networkRef = useRef<Network | null>(null);
  const stompClientRef = useRef<Client | null>(null);

  const nodesDataSetRef = useRef<DataSet<Node>>(new DataSet());
  const edgesDataSetRef = useRef<DataSet<Edge>>(new DataSet());

  const [loading, setLoading] = useState(false);
  const [connected, setConnected] = useState(false);
  const [selectedNode, setSelectedNode] = useState<GraphNode | null>(null);
  const [collaborativeUsers, setCollaborativeUsers] = useState<Map<string, CollaborativeUser>>(new Map());
  const [showSettings, setShowSettings] = useState(false);
  const [showFilters, setShowFilters] = useState(false);
  const [showUsers, setShowUsers] = useState(false);
  const [userColor] = useState(`#${Math.floor(Math.random()*16777215).toString(16)}`);

  const [settings, setSettings] = useState<GraphSettings>({
    layout: 'force',
    showLabels: true,
    showArrows: true,
    physics: true,
    nodeSize: 25,
    lazyLoading: true
  });

  const [visibleTypes, setVisibleTypes] = useState({
    class: true,
    individual: true,
    property: true,
    objectProperty: true,
    datatypeProperty: true
  });

  const typeColors = {
    class: '#4A90E2',
    individual: '#7ED321',
    property: '#F5A623',
    datatypeProperty: '#BD10E0',
    objectProperty: '#50E3C2'
  };

  const connectWebSocket = useCallback(() => {
    const socket = new SockJS('http://localhost:8080/ws');
    const stompClient = Stomp.over(socket);

    stompClient.connect({}, () => {
      console.log('Connected to WebSocket');
      setConnected(true);

      stompClient.subscribe(`/topic/graph/${projectId}`, (message: IMessage) => {
        handleGraphUpdate(JSON.parse(message.body));
      });

      stompClient.subscribe(`/topic/ontology/${projectId}`, (message: IMessage) => {
        handleEditOperation(JSON.parse(message.body));
      });

      console.log(`Subscribed to graph updates for project: ${projectId}`);
    }, (error: any) => {
      console.error('WebSocket connection error:', error);
      setConnected(false);

      setTimeout(connectWebSocket, 5000);
    });

    stompClientRef.current = stompClient;
  }, [projectId]);

  useEffect(() => {
    connectWebSocket();

    return () => {
      if (stompClientRef.current) {
        stompClientRef.current.disconnect();
      }
    };
  }, [connectWebSocket]);

  const fetchInitialGraph = useCallback(async (forceReload: boolean = false) => {
    setLoading(true);
    try {
      const endpoint = settings.lazyLoading
        ? `/api/collab-graph/${projectId}/initial?maxNodes=100${forceReload ? '&forceReload=true' : ''}`
        : `/api/ontology/${projectId}/graph${forceReload ? '?forceReload=true' : ''}`;

      console.log(`[CollaborativeGraphView] Fetching graph from: ${endpoint} (forceReload=${forceReload})`);

      const response = await fetch(endpoint, {
        headers: {
          'Authorization': `Bearer ${localStorage.getItem('authToken')}`
        }
      });

      if (response.ok) {
        const data = await response.json();
        const nodes = data.nodes || [];
        const edges = data.edges || [];

        nodesDataSetRef.current.clear();
        edgesDataSetRef.current.clear();
        nodesDataSetRef.current.add(nodes.map(convertToVisNode));
        edgesDataSetRef.current.add(edges.map(convertToVisEdge));

        console.log(`[CollaborativeGraphView] Loaded ${nodes.length} nodes and ${edges.length} edges`);
      }
    } catch (error) {
      console.error('Error fetching initial graph:', error);
    } finally {
      setLoading(false);
    }
  }, [projectId, settings.lazyLoading]);

  useEffect(() => {
    fetchInitialGraph();
  }, [fetchInitialGraph]);

  const convertToVisNode = (node: GraphNode): Node => ({
    id: node.id,
    label: settings.showLabels ? node.label : '',
    color: node.color || typeColors[node.type] || '#999',
    shape: node.type === 'class' ? 'box' : node.type === 'individual' ? 'ellipse' : 'diamond',
    size: settings.nodeSize,
    font: { size: 14, color: '#333' },
    title: `${node.label}\n(${node.type})${node.hasChildren ? '\n[Click to expand]' : ''}`,
    borderWidth: node.hasChildren ? 3 : 2,
    borderWidthSelected: 4,

    ...node
  });

  const convertToVisEdge = (edge: GraphEdge): Edge => ({
    id: edge.id,
    from: edge.from,
    to: edge.to,
    label: edge.label,
    arrows: settings.showArrows ? { to: { enabled: true } } : undefined,
    font: { size: 12, color: '#666', align: 'middle' },
    color: { color: '#999', hover: '#333' },
    dashes: edge.type === 'subClassOf',
    ...edge
  });

  const handleGraphUpdate = useCallback((update: any) => {
    console.log('Graph update received:', update.type);

    switch (update.type) {
      case 'NODE_ADDED':
        if (update.addedNodes) {
          update.addedNodes.forEach((node: GraphNode) => {
            if (!nodesDataSetRef.current.get(node.id)) {
              nodesDataSetRef.current.add(convertToVisNode(node));
            }
          });
        }
        break;

      case 'NODE_UPDATED':
        if (update.updatedNodes) {
          update.updatedNodes.forEach((node: GraphNode) => {
            nodesDataSetRef.current.update(convertToVisNode(node));
          });
        }
        break;

      case 'NODE_DELETED':
        if (update.deletedNodeIds) {
          update.deletedNodeIds.forEach((nodeId: string) => {
            nodesDataSetRef.current.remove(nodeId);
          });
        }
        break;

      case 'EDGE_ADDED':
        if (update.addedEdges) {
          update.addedEdges.forEach((edge: GraphEdge) => {
            if (!edgesDataSetRef.current.get(edge.id)) {
              edgesDataSetRef.current.add(convertToVisEdge(edge));
            }
          });
        }
        break;

      case 'EDGE_DELETED':
        if (update.deletedEdges) {
          update.deletedEdges.forEach((edge: GraphEdge) => {
            edgesDataSetRef.current.remove(edge.id);
          });
        }
        break;

      case 'DELTA_UPDATE':

        if (update.addedNodes?.length > 0) {
          nodesDataSetRef.current.add(update.addedNodes.map(convertToVisNode));
        }
        if (update.updatedNodes?.length > 0) {
          nodesDataSetRef.current.update(update.updatedNodes.map(convertToVisNode));
        }
        if (update.deletedNodeIds?.length > 0) {
          nodesDataSetRef.current.remove(update.deletedNodeIds);
        }
        if (update.addedEdges?.length > 0) {
          edgesDataSetRef.current.add(update.addedEdges.map(convertToVisEdge));
        }
        if (update.deletedEdges?.length > 0) {
          edgesDataSetRef.current.remove(update.deletedEdges.map((e: GraphEdge) => e.id));
        }
        break;

      case 'NODE_SELECTED':
        if (update.userId !== userId) {
          updateCollaborativeUser(update.userId, {
            userId: update.userId,
            username: update.username,
            color: update.userColor,
            selectedNodeId: update.selectedNodeId
          });
        }
        break;

      case 'CURSOR_MOVED':
        if (update.userId !== userId) {
          updateCollaborativeUser(update.userId, {
            userId: update.userId,
            username: update.username,
            color: update.userColor,
            cursorPosition: update.cursor
          });
        }
        break;

      case 'NODE_EXPANDED':

        if (update.addedNodes?.length > 0) {
          nodesDataSetRef.current.add(update.addedNodes.map(convertToVisNode));
        }
        if (update.addedEdges?.length > 0) {
          edgesDataSetRef.current.add(update.addedEdges.map(convertToVisEdge));
        }
        break;
    }
  }, [userId, settings]);

  const handleEditOperation = useCallback((operation: any) => {

    console.log('Edit operation received:', operation.type);
  }, []);

  const updateCollaborativeUser = (userId: string, user: CollaborativeUser) => {
    setCollaborativeUsers(prev => {
      const updated = new Map(prev);
      updated.set(userId, user);
      return updated;
    });
  };

  useEffect(() => {
    if (!containerRef.current) return;

    const data = {
      nodes: nodesDataSetRef.current,
      edges: edgesDataSetRef.current
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
        stabilization: { iterations: 150 }
      },
      interaction: {
        hover: true,
        zoomView: true,
        dragView: true,
        navigationButtons: false,
        multiselect: false
      },
      nodes: {
        borderWidth: 2,
        borderWidthSelected: 4
      },
      edges: {
        width: 2,
        smooth: { type: 'continuous' }
      }
    };

    const network = new Network(containerRef.current, data, options);
    networkRef.current = network;

    network.on('selectNode', handleNodeSelect);
    network.on('deselectNode', handleNodeDeselect);
    network.on('doubleClick', handleNodeDoubleClick);
    network.on('hoverNode', handleNodeHover);

    return () => {
      network.destroy();
    };
  }, [settings.layout, settings.physics, settings.showLabels, settings.showArrows, settings.nodeSize]);

  const handleNodeSelect = useCallback((params: any) => {
    const nodeId = params.nodes[0] as string;
    const node = nodesDataSetRef.current.get(nodeId) as any;

    if (node) {
      setSelectedNode(node as GraphNode);

      if (stompClientRef.current?.connected) {
        stompClientRef.current.send(
          `/app/graph/${projectId}/select`,
          {},
          JSON.stringify({ userId, username, nodeId, userColor })
        );
      }
    }
  }, [projectId, userId, username, userColor]);

  const handleNodeDeselect = useCallback(() => {
    setSelectedNode(null);
  }, []);

  const handleNodeDoubleClick = useCallback(async (params: any) => {
    if (!settings.lazyLoading) return;

    const nodeId = params.nodes[0] as string;
    const node = nodesDataSetRef.current.get(nodeId) as any;

    if (node?.hasChildren && !node.expanded) {
      await expandNode(nodeId);
    }
  }, [settings.lazyLoading, projectId]);

  const handleNodeHover = useCallback((params: any) => {
    // Could send cursor position here (debounced)
  }, []);

  const expandNode = useCallback(async (nodeId: string) => {
    try {
      console.log(`Expanding node: ${nodeId}`);
      setLoading(true);

      const response = await fetch(`${(window as any).API_BASE_URL}/api/graph/${projectId}/expand/${nodeId}`, {
        headers: { 'Authorization': `Bearer ${localStorage.getItem('authToken')}` }
      });

      if (response.ok) {
        const data = await response.json();
        const newNodes = data.nodes || [];
        const newEdges = data.edges || [];

        nodesDataSetRef.current.add(newNodes.map(convertToVisNode));
        edgesDataSetRef.current.add(newEdges.map(convertToVisEdge));

        nodesDataSetRef.current.update({ id: nodeId, expanded: true });

        if (stompClientRef.current?.connected) {
          stompClientRef.current.send(
            `/app/graph/${projectId}/expand`,
            {},
            JSON.stringify({ userId, username, nodeId })
          );
        }

        console.log(`Expanded ${nodeId}: added ${newNodes.length} nodes, ${newEdges.length} edges`);
      }
    } catch (error) {
      console.error('Error expanding node:', error);
    } finally {
      setLoading(false);
    }
  }, [projectId, userId, username]);

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
      networkRef.current.fit({ animation: { duration: 500, easingFunction: 'easeInOutQuad' } });
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
    setVisibleTypes(prev => ({ ...prev, [type]: !prev[type] }));

    const allNodes = nodesDataSetRef.current.get();
    allNodes.forEach((node: any) => {
      const visible = visibleTypes[node.type as keyof typeof visibleTypes];
      if (visible !== undefined) {
        nodesDataSetRef.current.update({ id: node.id, hidden: !visible });
      }
    });
  };

  return (
    <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column', position: 'relative', backgroundColor: '#f5f5f5' }}>
      {}
      <div style={{ padding: '10px', backgroundColor: '#fff', borderBottom: '1px solid #ddd', display: 'flex', gap: '10px', alignItems: 'center' }}>
        <button onClick={() => fetchInitialGraph(true)} disabled={loading} style={{ padding: '6px 12px', backgroundColor: '#4A90E2', color: 'white', border: 'none', borderRadius: '4px', cursor: loading ? 'not-allowed' : 'pointer', display: 'flex', alignItems: 'center', gap: '6px' }}>
          <RefreshCw size={16} style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
          Refresh
        </button>

        {}
        <div style={{ padding: '4px 8px', borderRadius: '4px', fontSize: '12px', backgroundColor: connected ? '#d4edda' : '#f8d7da', color: connected ? '#155724' : '#721c24' }}>
          {connected ? '● Connected' : '○ Disconnected'}
        </div>

        <div style={{ flex: 1 }} />

        <button onClick={handleZoomIn} title="Zoom In" style={{ padding: '6px', backgroundColor: 'transparent', border: '1px solid #ddd', borderRadius: '4px', cursor: 'pointer' }}>
          <ZoomIn size={18} />
        </button>

        <button onClick={handleZoomOut} title="Zoom Out" style={{ padding: '6px', backgroundColor: 'transparent', border: '1px solid #ddd', borderRadius: '4px', cursor: 'pointer' }}>
          <ZoomOut size={18} />
        </button>

        <button onClick={handleFit} title="Fit to Screen" style={{ padding: '6px', backgroundColor: 'transparent', border: '1px solid #ddd', borderRadius: '4px', cursor: 'pointer' }}>
          <Maximize2 size={18} />
        </button>

        <button onClick={() => setShowFilters(!showFilters)} title="Filters" style={{ padding: '6px', backgroundColor: showFilters ? '#4A90E2' : 'transparent', color: showFilters ? 'white' : 'inherit', border: '1px solid #ddd', borderRadius: '4px', cursor: 'pointer' }}>
          <Filter size={18} />
        </button>

        <button onClick={() => setShowSettings(!showSettings)} title="Settings" style={{ padding: '6px', backgroundColor: showSettings ? '#4A90E2' : 'transparent', color: showSettings ? 'white' : 'inherit', border: '1px solid #ddd', borderRadius: '4px', cursor: 'pointer' }}>
          <Settings size={18} />
        </button>

        <button onClick={() => setShowUsers(!showUsers)} title="Active Users" style={{ padding: '6px', backgroundColor: showUsers ? '#4A90E2' : 'transparent', color: showUsers ? 'white' : 'inherit', border: '1px solid #ddd', borderRadius: '4px', cursor: 'pointer', position: 'relative' }}>
          <Users size={18} />
          {collaborativeUsers.size > 0 && (
            <span style={{ position: 'absolute', top: '-4px', right: '-4px', backgroundColor: '#e74c3c', color: 'white', borderRadius: '50%', width: '16px', height: '16px', fontSize: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              {collaborativeUsers.size}
            </span>
          )}
        </button>

        <button onClick={handleExport} title="Export as PNG" style={{ padding: '6px', backgroundColor: 'transparent', border: '1px solid #ddd', borderRadius: '4px', cursor: 'pointer' }}>
          <Download size={18} />
        </button>
      </div>

      {}
      <div style={{ flex: 1, display: 'flex', position: 'relative', overflow: 'hidden' }}>
        <div ref={containerRef} style={{ flex: 1, backgroundColor: '#fff' }} />

        {}
        {}

        {}
        {selectedNode && (
          <div style={{ position: 'absolute', bottom: '10px', left: '10px', backgroundColor: 'white', border: '1px solid #ddd', borderRadius: '6px', padding: '12px', boxShadow: '0 2px 8px rgba(0,0,0,0.1)', maxWidth: '300px', zIndex: 10 }}>
            <h4 style={{ margin: '0 0 8px 0', fontSize: '14px', fontWeight: 600 }}>{selectedNode.label}</h4>
            <div style={{ fontSize: '12px', color: '#666' }}>
              <div><strong>Type:</strong> {selectedNode.type}</div>
              <div><strong>ID:</strong> {selectedNode.id}</div>
              {selectedNode.hasChildren && !selectedNode.expanded && (
                <div style={{ marginTop: '8px', color: '#4A90E2', cursor: 'pointer' }} onClick={() => expandNode(selectedNode.id)}>
                  <Expand size={14} style={{ verticalAlign: 'middle' }} /> Double-click to expand
                </div>
              )}
            </div>
          </div>
        )}

        {loading && (
          <div style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(255,255,255,0.8)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 }}>
            <RefreshCw size={32} style={{ animation: 'spin 1s linear infinite' }} />
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

export default CollaborativeGraphView;

import React, { useMemo } from 'react';
import { BarChart3, PieChart, TrendingUp, GitBranch, Users, Box, Database } from 'lucide-react';
import type { OntologyNode, OntologyEdge } from '../types';

interface StatsDashboardProps {
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  width: number;
  height: number;
}

export const StatsDashboard: React.FC<StatsDashboardProps> = ({
  nodes,
  edges,
  width,
  height
}) => {
  const stats = useMemo(() => {

    const nodeTypeCount = nodes.reduce((acc, node) => {
      acc[node.type] = (acc[node.type] || 0) + 1;
      return acc;
    }, {} as Record<string, number>);

    const edgeTypeCount = edges.reduce((acc, edge) => {
      acc[edge.type] = (acc[edge.type] || 0) + 1;
      return acc;
    }, {} as Record<string, number>);

    const calculateDepth = () => {
      const visited = new Set<string>();
      const depths = new Map<string, number>();

      const dfs = (nodeId: string, depth: number) => {
        if (visited.has(nodeId)) return;
        visited.add(nodeId);
        depths.set(nodeId, Math.max(depths.get(nodeId) || 0, depth));

        edges
          .filter(e => e.to === nodeId && e.type === 'subClassOf')
          .forEach(e => dfs(e.from, depth + 1));
      };

      const hasParent = new Set(edges.filter(e => e.type === 'subClassOf').map(e => e.from));
      const roots = nodes.filter(n => !hasParent.has(n.id));

      roots.forEach(root => dfs(root.id, 0));

      return depths.size > 0 ? Math.max(...Array.from(depths.values())) : 0;
    };

    const connectivity = nodes.length > 0 ? (edges.length / nodes.length).toFixed(2) : '0';

    const connectionCount = new Map<string, number>();
    edges.forEach(edge => {
      connectionCount.set(edge.from, (connectionCount.get(edge.from) || 0) + 1);
      connectionCount.set(edge.to, (connectionCount.get(edge.to) || 0) + 1);
    });

    const mostConnected = Array.from(connectionCount.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5)
      .map(([nodeId, count]) => ({
        node: nodes.find(n => n.id === nodeId),
        count
      }))
      .filter(item => item.node);

    return {
      totalNodes: nodes.length,
      totalEdges: edges.length,
      nodeTypeCount,
      edgeTypeCount,
      hierarchyDepth: calculateDepth(),
      connectivity,
      mostConnected
    };
  }, [nodes, edges]);

  const nodeTypeColors: Record<string, string> = {
    class: '#667eea',
    individual: '#10b981',
    property: '#f59e0b',
    dataProperty: '#ec4899',
    objectProperty: '#06b6d4',
    annotation: '#8b5cf6',
    datatype: '#FFA500'
  };

  const edgeTypeColors: Record<string, string> = {
    subClassOf: '#667eea',
    instanceOf: '#10b981',
    propertyRelation: '#f59e0b',
    domain: '#06b6d4',
    range: '#8b5cf6',
    custom: '#6b7280'
  };

  return (
    <div className="p-6 overflow-auto" style={{ width, height, backgroundColor: 'var(--bg)' }}>
      <h2 className="text-2xl font-bold mb-6" style={{ color: 'var(--text-primary)' }}>Ontology Statistics</h2>

      {}
      <div className="grid grid-cols-4 gap-4 mb-6">
        <div className="p-4 rounded-lg shadow-sm" style={{ backgroundColor: 'var(--surface-1)', border: '1px solid var(--border)' }}>
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>Total Nodes</p>
              <p className="text-2xl font-bold" style={{ color: 'var(--text-primary)' }}>{stats.totalNodes}</p>
            </div>
            <Box style={{ color: 'var(--accent)' }} size={32} />
          </div>
        </div>

        <div className="p-4 rounded-lg shadow-sm" style={{ backgroundColor: 'var(--surface-1)', border: '1px solid var(--border)' }}>
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>Total Edges</p>
              <p className="text-2xl font-bold" style={{ color: 'var(--text-primary)' }}>{stats.totalEdges}</p>
            </div>
            <GitBranch style={{ color: 'var(--success)' }} size={32} />
          </div>
        </div>

        <div className="p-4 rounded-lg shadow-sm" style={{ backgroundColor: 'var(--surface-1)', border: '1px solid var(--border)' }}>
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>Hierarchy Depth</p>
              <p className="text-2xl font-bold" style={{ color: 'var(--text-primary)' }}>{stats.hierarchyDepth}</p>
            </div>
            <TrendingUp style={{ color: 'var(--accent)' }} size={32} />
          </div>
        </div>

        <div className="p-4 rounded-lg shadow-sm" style={{ backgroundColor: 'var(--surface-1)', border: '1px solid var(--border)' }}>
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>Avg Connectivity</p>
              <p className="text-2xl font-bold" style={{ color: 'var(--text-primary)' }}>{stats.connectivity}</p>
            </div>
            <Database style={{ color: 'var(--warning)' }} size={32} />
          </div>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-6">
        {}
        <div className="p-6 rounded-lg shadow-sm" style={{ backgroundColor: 'var(--surface-1)', border: '1px solid var(--border)' }}>
          <h3 className="text-lg font-semibold mb-4 flex items-center gap-2" style={{ color: 'var(--text-primary)' }}>
            <PieChart size={20} />
            Node Type Distribution
          </h3>
          <div className="space-y-3">
            {Object.entries(stats.nodeTypeCount).map(([type, count]) => {
              const percentage = ((count / stats.totalNodes) * 100).toFixed(1);
              return (
                <div key={type}>
                  <div className="flex justify-between text-sm mb-1">
                    <span className="capitalize" style={{ color: 'var(--text-primary)' }}>{type}</span>
                    <span className="font-semibold" style={{ color: 'var(--text-primary)' }}>{count} ({percentage}%)</span>
                  </div>
                  <div className="w-full rounded-full h-2" style={{ backgroundColor: 'var(--surface-3)' }}>
                    <div
                      className="h-2 rounded-full transition-all duration-300"
                      style={{
                        width: `${percentage}%`,
                        backgroundColor: nodeTypeColors[type] || '#6b7280'
                      }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {}
        <div className="p-6 rounded-lg shadow-sm" style={{ backgroundColor: 'var(--surface-1)', border: '1px solid var(--border)' }}>
          <h3 className="text-lg font-semibold mb-4 flex items-center gap-2" style={{ color: 'var(--text-primary)' }}>
            <BarChart3 size={20} />
            Relationship Type Distribution
          </h3>
          <div className="space-y-3">
            {Object.entries(stats.edgeTypeCount).map(([type, count]) => {
              const percentage = ((count / stats.totalEdges) * 100).toFixed(1);
              return (
                <div key={type}>
                  <div className="flex justify-between text-sm mb-1">
                    <span className="capitalize" style={{ color: 'var(--text-primary)' }}>{type.replace(/([A-Z])/g, ' $1').trim()}</span>
                    <span className="font-semibold" style={{ color: 'var(--text-primary)' }}>{count} ({percentage}%)</span>
                  </div>
                  <div className="w-full rounded-full h-2" style={{ backgroundColor: 'var(--surface-3)' }}>
                    <div
                      className="h-2 rounded-full transition-all duration-300"
                      style={{
                        width: `${percentage}%`,
                        backgroundColor: edgeTypeColors[type] || '#6b7280'
                      }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {}
        <div className="p-6 rounded-lg shadow-sm col-span-2" style={{ backgroundColor: 'var(--surface-1)', border: '1px solid var(--border)' }}>
          <h3 className="text-lg font-semibold mb-4 flex items-center gap-2" style={{ color: 'var(--text-primary)' }}>
            <Users size={20} />
            Most Connected Nodes
          </h3>
          <div className="space-y-2">
            {stats.mostConnected.map(({ node, count }, index) => (
              <div key={node?.id} className="flex items-center justify-between p-3 rounded-lg" style={{ backgroundColor: 'var(--surface-2)' }}>
                <div className="flex items-center gap-3">
                  <div className="text-lg font-bold" style={{ color: 'var(--text-tertiary)' }}>#{index + 1}</div>
                  <div>
                    <div className="font-semibold" style={{ color: 'var(--text-primary)' }}>{node?.label}</div>
                    <div className="text-sm capitalize" style={{ color: 'var(--text-secondary)' }}>{node?.type}</div>
                  </div>
                </div>
                <div className="text-right">
                  <div className="text-lg font-bold" style={{ color: nodeTypeColors[node?.type || ''] || '#6b7280' }}>
                    {count}
                  </div>
                  <div className="text-xs" style={{ color: 'var(--text-secondary)' }}>connections</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};

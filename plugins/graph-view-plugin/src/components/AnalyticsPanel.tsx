/**
 * InfraNodus-inspired insights panel — clusters, top concepts, structural gaps.
 */

import React, { useMemo } from 'react';
import { BarChart3, GitBranch, Lightbulb, Network, TrendingUp, X } from 'lucide-react';
import type { OntologyNode } from '../types';
import type { GraphAnalytics, StructuralGap } from '../services/GraphAnalyticsService';

interface AnalyticsPanelProps {
  analytics: GraphAnalytics;
  nodes: OntologyNode[];
  onSelectNode?: (node: OntologyNode) => void;
  onHighlightGap?: (gap: StructuralGap) => void;
  onClose?: () => void;
  colorByCluster: boolean;
  onToggleColorByCluster?: (value: boolean) => void;
}

export const AnalyticsPanel: React.FC<AnalyticsPanelProps> = ({
  analytics,
  nodes,
  onSelectNode,
  onHighlightGap,
  onClose,
  colorByCluster,
  onToggleColorByCluster
}) => {
  const clusters = useMemo(() => {
    const byCluster = new Map<number, OntologyNode[]>();
    for (const node of nodes) {
      const c = analytics.communities.get(node.id);
      if (c === undefined) continue;
      const list = byCluster.get(c) ?? [];
      list.push(node);
      byCluster.set(c, list);
    }
    return [...byCluster.entries()]
      .map(([id, members]) => ({
        id,
        members,
        size: members.length,
        color: analytics.clusterColors.get(id) ?? '#94a3b8',
        label: members.slice(0, 3).map(n => n.label).join(', ')
      }))
      .sort((a, b) => b.size - a.size)
      .slice(0, 6);
  }, [analytics, nodes]);

  const maxBetweenness = Math.max(0.001, ...analytics.topConcepts.map(t => t.score));

  return (
    <div
      data-testid="graph-analytics-panel"
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        background: 'var(--surface-1)',
        borderTop: '1px solid var(--border)',
        overflow: 'hidden'
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '8px 12px',
          borderBottom: '1px solid var(--border)',
          background: 'var(--surface-2)',
          flexShrink: 0
        }}
      >
        <TrendingUp size={16} />
        <span style={{ fontWeight: 600, fontSize: 13, color: 'var(--text-primary)' }}>Graph Insights</span>
        <span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>InfraNodus-style</span>
        <div style={{ flex: 1 }} />
        <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, cursor: 'pointer' }}>
          <input
            type="checkbox"
            checked={colorByCluster}
            onChange={(e) => onToggleColorByCluster?.(e.target.checked)}
            data-testid="graph-color-by-cluster"
          />
          Color clusters
        </label>
        {onClose && (
          <button type="button" onClick={onClose} style={iconBtn} title="Close insights">
            <X size={14} />
          </button>
        )}
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: 12, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        {/* Top concepts */}
        <section style={cardStyle}>
          <header style={sectionHeader}>
            <Network size={14} />
            Top concepts
            <span style={hint}>betweenness</span>
          </header>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {analytics.topConcepts.map(({ node, score, degree }, i) => (
              <button
                key={node.id}
                type="button"
                data-testid="graph-top-concept"
                onClick={() => onSelectNode?.(node)}
                style={rowBtn}
              >
                <span style={{ width: 18, color: 'var(--text-tertiary)', fontSize: 11 }}>#{i + 1}</span>
                <span style={{ flex: 1, textAlign: 'left', fontWeight: 500 }}>{node.label}</span>
                <span style={{ fontSize: 10, color: 'var(--text-secondary)' }}>deg {degree}</span>
                <div style={{ width: 48, height: 4, background: 'var(--surface-3)', borderRadius: 2 }}>
                  <div
                    style={{
                      width: `${Math.round((score / maxBetweenness) * 100)}%`,
                      height: '100%',
                      background: 'var(--accent)',
                      borderRadius: 2
                    }}
                  />
                </div>
              </button>
            ))}
          </div>
        </section>

        {/* Topic clusters */}
        <section style={cardStyle}>
          <header style={sectionHeader}>
            <BarChart3 size={14} />
            Topic clusters
            <span style={hint}>{clusters.length} groups</span>
          </header>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {clusters.map(c => (
              <div key={c.id} style={{ ...rowBtn, cursor: 'default' }}>
                <span
                  style={{
                    width: 10,
                    height: 10,
                    borderRadius: '50%',
                    background: c.color,
                    flexShrink: 0
                  }}
                />
                <span style={{ flex: 1, fontSize: 12 }}>
                  {c.label}{c.size > 3 ? ` +${c.size - 3}` : ''}
                </span>
                <span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{c.size}</span>
              </div>
            ))}
          </div>
        </section>

        {/* Structural gaps */}
        <section style={{ ...cardStyle, gridColumn: '1 / -1' }}>
          <header style={sectionHeader}>
            <Lightbulb size={14} />
            Structural gaps
            <span style={hint}>disconnected clusters</span>
          </header>
          {analytics.gaps.length === 0 ? (
            <p style={{ fontSize: 12, color: 'var(--text-secondary)', margin: 0 }}>
              No major gaps — clusters are linked in the visible graph.
            </p>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              {analytics.gaps.map((gap, i) => (
                <button
                  key={`${gap.clusterA}-${gap.clusterB}-${i}`}
                  type="button"
                  data-testid="graph-structural-gap"
                  onClick={() => onHighlightGap?.(gap)}
                  style={{ ...rowBtn, textAlign: 'left' }}
                >
                  <GitBranch size={12} style={{ flexShrink: 0, color: '#f59e0b' }} />
                  <span style={{ fontSize: 12, lineHeight: 1.35 }}>{gap.suggestion}</span>
                </button>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
};

const cardStyle: React.CSSProperties = {
  background: 'var(--surface-2)',
  border: '1px solid var(--border)',
  borderRadius: 8,
  padding: 10
};

const sectionHeader: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  fontSize: 12,
  fontWeight: 600,
  marginBottom: 8,
  color: 'var(--text-primary)'
};

const hint: React.CSSProperties = {
  marginLeft: 'auto',
  fontSize: 10,
  fontWeight: 400,
  color: 'var(--text-secondary)'
};

const rowBtn: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '6px 8px',
  border: '1px solid transparent',
  borderRadius: 6,
  background: 'var(--surface-1)',
  cursor: 'pointer',
  fontSize: 12,
  color: 'var(--text-primary)',
  width: '100%'
};

const iconBtn: React.CSSProperties = {
  border: '1px solid var(--border)',
  background: 'var(--surface-1)',
  borderRadius: 4,
  padding: 4,
  display: 'flex',
  cursor: 'pointer'
};

export default AnalyticsPanel;

/**
 * Task-first insight chips overlaid on the WebGL canvas. Clicking a chip
 * emphasizes the matching nodes and flies the camera to them — the graph is
 * opened *for* something, never as wallpaper.
 */

import React from 'react';
import type { GraphInsights } from './graphAnalysis';

export type InsightKind = 'orphans' | 'islands' | 'hubs' | 'communities';

interface InsightChipsProps {
  insights: GraphInsights;
  active: InsightKind | null;
  /** Communities is a color overlay toggle, tracked separately from emphasis. */
  communitiesOn: boolean;
  dark: boolean;
  onSelect: (kind: InsightKind, nodeIds: string[]) => void;
  onClear: () => void;
}

export const InsightChips: React.FC<InsightChipsProps> = ({ insights, active, communitiesOn, dark, onSelect, onClear }) => {
  const islandNodes = insights.islands.flat();
  const chips: Array<{ kind: InsightKind; label: string; count: number; ids: string[]; title: string }> = [
    { kind: 'orphans', label: 'Orphans', count: insights.orphans.length, ids: insights.orphans, title: 'Nodes with no connections — likely missing axioms' },
    { kind: 'islands', label: 'Islands', count: insights.islands.length, ids: islandNodes, title: 'Disconnected clusters — should these link to the main graph?' },
    { kind: 'hubs', label: 'Hubs', count: insights.hubs.length, ids: insights.hubs, title: 'Degree outliers that dominate the layout' },
    { kind: 'communities', label: 'Modules', count: insights.communityCount, ids: [], title: 'Color nodes by their natural module (Louvain communities)' }
  ];

  const visible = chips.filter(c => c.count > (c.kind === 'communities' ? 1 : 0));
  if (visible.length === 0) return null;

  const chipStyle = (isActive: boolean): React.CSSProperties => ({
    display: 'inline-flex',
    alignItems: 'center',
    gap: 5,
    padding: '3px 10px',
    borderRadius: 12,
    fontSize: 11.5,
    cursor: 'pointer',
    userSelect: 'none',
    border: `1px solid ${isActive ? '#6366f1' : dark ? '#374151' : '#d1d5db'}`,
    backgroundColor: isActive ? (dark ? '#312e81' : '#e0e7ff') : dark ? '#1f2937cc' : '#ffffffcc',
    color: dark ? '#e5e7eb' : '#1f2937',
    backdropFilter: 'blur(4px)'
  });

  return (
    <div
      data-testid="graph-insight-chips"
      style={{ position: 'absolute', top: 10, left: 10, zIndex: 20, display: 'flex', gap: 6, flexWrap: 'wrap' }}
    >
      {visible.map(chip => {
        const isActive = chip.kind === 'communities' ? communitiesOn : active === chip.kind;
        return (
          <span
            key={chip.kind}
            data-testid={`graph-insight-${chip.kind}`}
            title={chip.title}
            style={chipStyle(isActive)}
            onClick={() => (isActive && chip.kind !== 'communities' ? onClear() : onSelect(chip.kind, chip.ids))}
          >
            {chip.label}
            <strong>{chip.count}</strong>
          </span>
        );
      })}
      {active && (
        <span data-testid="graph-insight-clear" style={chipStyle(false)} onClick={onClear}>
          ✕ Clear
        </span>
      )}
    </div>
  );
};

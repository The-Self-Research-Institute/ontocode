import React, { useEffect, useState } from 'react';
import { Filter, X } from 'lucide-react';

interface VowlDegreeControlProps {
  degree: number | null;
  maxDegree: number;
  shownClasses: number;
  totalClasses: number;
  openToken: number;
  onChange: (degree: number) => void;
}

export const VowlDegreeControl: React.FC<VowlDegreeControlProps> = ({
  degree,
  maxDegree,
  shownClasses,
  totalClasses,
  openToken,
  onChange
}) => {
  const [open, setOpen] = useState(false);
  const [showNotice, setShowNotice] = useState(false);

  useEffect(() => {
    if (openToken > 0) {
      setOpen(true);
      setShowNotice(true);
    }
  }, [openToken]);

  const active = degree !== null;

  return (
    <div
      data-testid="vowl-degree-control"
      style={{
        position: 'absolute',
        left: 16,
        bottom: 16,
        zIndex: 25,
        display: 'flex',
        alignItems: 'flex-end',
        gap: 8,
        maxWidth: 'calc(100% - 32px)'
      }}
    >
      <button
        data-testid="vowl-degree-toggle"
        onClick={() => setOpen(v => !v)}
        title="Degree of collapsing"
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          padding: '6px 10px',
          borderRadius: 8,
          border: '1px solid var(--border, #d1d5db)',
          backgroundColor: active ? 'var(--accent, #3b82f6)' : 'var(--surface-1, #ffffff)',
          color: active ? '#fff' : 'var(--text-primary, #111827)',
          fontSize: 12,
          fontWeight: 600,
          cursor: 'pointer',
          boxShadow: '0 1px 3px rgba(0,0,0,0.12)'
        }}
      >
        <Filter size={14} />
        {active ? degree : 'off'}
      </button>
      {open && (
        <div
          data-testid="vowl-degree-panel"
          style={{
            padding: '8px 12px',
            borderRadius: 8,
            border: '1px solid var(--border, #d1d5db)',
            backgroundColor: 'var(--surface-1, #ffffff)',
            color: 'var(--text-primary, #111827)',
            fontSize: 12,
            boxShadow: '0 2px 8px rgba(0,0,0,0.15)',
            minWidth: 240,
            maxWidth: 360,
            display: 'flex',
            flexDirection: 'column',
            gap: 6
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 600, whiteSpace: 'nowrap' }}>Degree of collapsing</span>
            <span style={{ marginLeft: 'auto', fontWeight: 600 }}>{active ? degree : 'off'}</span>
            <button
              onClick={() => setOpen(false)}
              title="Close"
              style={{ border: 'none', background: 'none', cursor: 'pointer', color: 'inherit', padding: 0, display: 'flex' }}
            >
              <X size={14} />
            </button>
          </div>
          <input
            type="range"
            min={0}
            max={Math.max(0, maxDegree)}
            step={1}
            value={active ? degree! : 0}
            onChange={(e) => {
              setShowNotice(false);
              onChange(Number(e.target.value));
            }}
            data-testid="vowl-degree-slider"
            style={{ width: '100%' }}
          />
          <div style={{ color: 'var(--text-secondary, #6b7280)' }}>
            {active ? `${shownClasses} of ${totalClasses} classes shown` : 'Move the slider or use Collapse All'}
          </div>
          {showNotice && active && (
            <div style={{ color: 'var(--text-secondary, #6b7280)', lineHeight: 1.4 }}>
              Collapsing filter activated: only classes with at least {degree} connections are shown. Lower the degree to see more.
            </div>
          )}
        </div>
      )}
    </div>
  );
};

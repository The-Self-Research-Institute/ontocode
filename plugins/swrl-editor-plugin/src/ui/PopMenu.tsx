import React, { useEffect, useRef, useState } from 'react';
import { ChevronDown } from 'lucide-react';
import { rt } from '../resultsTheme';

export interface MenuItem {
  id: string;
  label: string;
  description?: string;
  badge?: string;
  onClick: () => void;
  disabled?: boolean;
  danger?: boolean;
}

interface PopMenuProps {
  label: React.ReactNode;
  items: MenuItem[];
  testId: string;
  title?: string;
  align?: 'left' | 'right';
  openUp?: boolean;
  disabled?: boolean;
  className?: string;
  style?: React.CSSProperties;
  width?: string;
  onOpen?: () => void;
}

export const PopMenu: React.FC<PopMenuProps> = ({ label, items, testId, title, align = 'left', openUp = false, disabled = false, className = '', style, width = '16rem', onOpen }) => {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div ref={rootRef} className="relative inline-block">
      <button
        type="button"
        onClick={() => {
          if (!open) onOpen?.();
          setOpen(o => !o);
        }}
        disabled={disabled}
        aria-haspopup="menu"
        aria-expanded={open}
        title={title}
        data-testid={testId}
        className={`swrl-focus inline-flex items-center gap-1 ${className}`}
        style={style}
      >
        {label}
        <ChevronDown size={14} aria-hidden="true" />
      </button>
      {open && (
        <div
          role="menu"
          data-testid={`${testId}-list`}
          className="absolute z-40 p-1.5 rounded-lg shadow-xl"
          style={{ ...rt.card, width, maxWidth: '90vw', [align === 'right' ? 'right' : 'left']: 0, [openUp ? 'bottom' : 'top']: '100%', margin: '4px 0' }}
        >
          {items.map(item => (
            <button
              key={item.id}
              type="button"
              role="menuitem"
              disabled={item.disabled}
              onClick={() => {
                setOpen(false);
                item.onClick();
              }}
              className="swrl-focus w-full text-left px-3 py-2 rounded-md text-sm block"
              style={{ color: item.danger ? 'var(--error)' : 'var(--text-primary)', opacity: item.disabled ? 0.5 : 1, cursor: item.disabled ? 'not-allowed' : 'pointer' }}
              data-testid={`${testId}-${item.id}`}
            >
              <span className="flex items-center justify-between gap-2">
                <span className="font-medium">{item.label}</span>
                {item.badge && <span className="text-xs" style={rt.muted}>{item.badge}</span>}
              </span>
              {item.description && <span className="block text-xs" style={rt.muted}>{item.description}</span>}
            </button>
          ))}
        </div>
      )}
    </div>
  );
};

export const FOCUS_CSS = '.swrl-focus:focus-visible{outline:2px solid var(--text-primary,#111);outline-offset:2px;border-radius:4px}';

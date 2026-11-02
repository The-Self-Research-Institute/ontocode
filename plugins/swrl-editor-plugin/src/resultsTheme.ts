import type { CSSProperties } from 'react';

const v = (name: string) => `var(--${name})`;

export const rt = {
  panel: { backgroundColor: v('surface-1'), border: `1px solid ${v('border')}`, color: v('text-primary') } as CSSProperties,
  panelHeader: { backgroundColor: v('surface-2'), borderBottom: `1px solid ${v('border')}`, color: v('text-primary') } as CSSProperties,
  rowBorder: { borderBottom: `1px solid ${v('divider')}` } as CSSProperties,
  divider: { borderTop: `1px solid ${v('border')}` } as CSSProperties,
  card: { backgroundColor: v('surface-2'), border: `1px solid ${v('border')}`, color: v('text-primary') } as CSSProperties,
  label: { color: v('text-secondary') } as CSSProperties,
  value: { color: v('text-primary') } as CSSProperties,
  muted: { color: v('text-tertiary') } as CSSProperties,
  heading: { color: v('text-primary') } as CSSProperties,
  chip: { backgroundColor: v('surface-3'), border: `1px solid ${v('border')}`, color: v('text-primary') } as CSSProperties,
  chipOn: { backgroundColor: v('text-primary'), border: `1px solid ${v('text-primary')}`, color: v('bg') } as CSSProperties,
  link: { color: v('text-primary'), textDecoration: 'underline' } as CSSProperties,
  button: { backgroundColor: v('surface-1'), border: `1px solid ${v('border')}`, color: v('text-primary') } as CSSProperties,
  buttonDisabled: { backgroundColor: v('surface-2'), border: `1px dashed ${v('border')}`, color: v('text-tertiary'), cursor: 'not-allowed' } as CSSProperties,
  buttonPrimary: { backgroundColor: v('text-primary'), border: `1px solid ${v('text-primary')}`, color: v('bg') } as CSSProperties,
  buttonPrimaryDisabled: { backgroundColor: v('surface-3'), border: `1px dashed ${v('border')}`, color: v('text-tertiary'), cursor: 'not-allowed' } as CSSProperties,
  input: { backgroundColor: v('surface-1'), border: `1px solid ${v('border')}`, color: v('text-primary') } as CSSProperties,
  bannerWarning: { backgroundColor: v('warning-tint'), border: `1px solid ${v('warning')}`, color: v('text-primary') } as CSSProperties,
  bannerNote: { backgroundColor: v('surface-2'), border: `1px solid ${v('border')}`, color: v('text-primary') } as CSSProperties,
  bannerSuccess: { backgroundColor: v('success-tint'), border: `1px solid ${v('success')}`, color: v('text-primary') } as CSSProperties,
  bannerError: { backgroundColor: v('error-tint'), border: `1px solid ${v('error')}`, color: v('text-primary') } as CSSProperties,
  tooltip: { backgroundColor: v('text-primary'), color: v('bg') } as CSSProperties,
  code: { backgroundColor: v('surface-3'), color: v('text-primary') } as CSSProperties,
};

export const btn = (disabled: boolean, primary = false): CSSProperties =>
  disabled ? (primary ? rt.buttonPrimaryDisabled : rt.buttonDisabled) : (primary ? rt.buttonPrimary : rt.button);

export interface TokenPair { name: string; fg: string; bg: string[] }

export const TOKEN_PAIRS: TokenPair[] = [
  { name: 'card label', fg: 'text-secondary', bg: ['surface-2'] },
  { name: 'card value', fg: 'text-primary', bg: ['surface-2'] },
  { name: 'muted on panel', fg: 'text-tertiary', bg: ['surface-1'] },
  { name: 'muted on card', fg: 'text-tertiary', bg: ['surface-2'] },
  { name: 'panel text', fg: 'text-primary', bg: ['surface-1'] },
  { name: 'header text', fg: 'text-primary', bg: ['surface-2'] },
  { name: 'chip', fg: 'text-primary', bg: ['surface-3'] },
  { name: 'chip active', fg: 'bg', bg: ['text-primary'] },
  { name: 'link', fg: 'text-primary', bg: ['surface-1'] },
  { name: 'button', fg: 'text-primary', bg: ['surface-1'] },
  { name: 'button disabled', fg: 'text-tertiary', bg: ['surface-2'] },
  { name: 'primary button', fg: 'bg', bg: ['text-primary'] },
  { name: 'primary button disabled', fg: 'text-tertiary', bg: ['surface-3'] },
  { name: 'input', fg: 'text-primary', bg: ['surface-1'] },
  { name: 'warning banner', fg: 'text-primary', bg: ['surface-1', 'warning-tint'] },
  { name: 'success banner', fg: 'text-primary', bg: ['surface-1', 'success-tint'] },
  { name: 'error banner', fg: 'text-primary', bg: ['surface-1', 'error-tint'] },
  { name: 'note banner', fg: 'text-primary', bg: ['surface-2'] },
  { name: 'tooltip', fg: 'bg', bg: ['text-primary'] },
  { name: 'code', fg: 'text-primary', bg: ['surface-3'] },
];

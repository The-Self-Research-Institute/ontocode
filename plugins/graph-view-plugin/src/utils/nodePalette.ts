

export const NODE_ACCENTS: Record<string, string> = {
  class: '#3B82F6',          // blue
  individual: '#7C3AED',     // violet
  objectProperty: '#0D9488', // teal
  property: '#0D9488',       // generic property = objectProperty teal
  dataProperty: '#DB2777',   // magenta
  datatype: '#D97706',       // amber
  annotation: '#65A30D',     // lime
};

const FALLBACK_ACCENT = '#3B82F6';

export function nodeAccent(type: string | undefined): string {
  return NODE_ACCENTS[type ?? ''] ?? FALLBACK_ACCENT;
}

function mix(hex: string, target: string, t: number): string {
  const h = hex.replace('#', '');
  const g = target.replace('#', '');
  const c = (s: string, i: number) => parseInt(s.slice(i, i + 2), 16);
  const ch = (i: number) => Math.round(c(h, i) + (c(g, i) - c(h, i)) * t);
  return '#' + [0, 2, 4].map((i) => ch(i).toString(16).padStart(2, '0')).join('');
}

export function nodeFill(type: string | undefined, dark: boolean): string {
  const accent = nodeAccent(type);
  return dark ? mix(accent, '#1b1e2b', 0.72) : mix(accent, '#ffffff', 0.88);
}

export function nodeStroke(type: string | undefined, dark: boolean): string {
  const accent = nodeAccent(type);
  return dark ? mix(accent, '#ffffff', 0.18) : accent;
}

export function nodeLabelColor(dark: boolean): string {
  return dark ? '#e7e9f0' : '#1f2430';
}

/**
 * Canonical node palette — single source of truth for entity-type color.
 *
 * Validated with the dataviz six-checks validator against BOTH light
 * (#fcfcfb) and dark surfaces: lightness band, chroma floor, adjacent-pair
 * CVD separation (worst ΔE 14.5 deutan), and ≥3:1 surface contrast.
 * Node SHAPE (ellipse/rect/diamond) is the secondary encoding, so identity
 * is never color-alone.
 *
 * Rules: hues are assigned to entity types in FIXED order and never cycled;
 * derive fills/strokes/glows from these accents — never hardcode a node hex
 * anywhere else (legend, tooltip, selection ring included).
 */

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

/** Mix a hex color toward a target by t (0..1) in sRGB — cheap, dependency-free. */
function mix(hex: string, target: string, t: number): string {
  const h = hex.replace('#', '');
  const g = target.replace('#', '');
  const c = (s: string, i: number) => parseInt(s.slice(i, i + 2), 16);
  const ch = (i: number) => Math.round(c(h, i) + (c(g, i) - c(h, i)) * t);
  return '#' + [0, 2, 4].map((i) => ch(i).toString(16).padStart(2, '0')).join('');
}

/**
 * Node body fill: a soft tint of the accent so the crisp accent stroke and
 * the label carry the identity — light: 88% toward white, dark: 72% toward
 * the dark surface. Replaces the old uniform moccasin (#FFE4B5) fill.
 */
export function nodeFill(type: string | undefined, dark: boolean): string {
  const accent = nodeAccent(type);
  return dark ? mix(accent, '#1b1e2b', 0.72) : mix(accent, '#ffffff', 0.88);
}

/** Node border: the full-strength accent (light) or a lifted accent (dark). */
export function nodeStroke(type: string | undefined, dark: boolean): string {
  const accent = nodeAccent(type);
  return dark ? mix(accent, '#ffffff', 0.18) : accent;
}

/** Text color that reads on the tinted fill. */
export function nodeLabelColor(dark: boolean): string {
  return dark ? '#e7e9f0' : '#1f2430';
}

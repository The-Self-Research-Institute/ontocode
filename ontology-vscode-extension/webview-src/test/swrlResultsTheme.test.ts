import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { TOKEN_PAIRS, rt, btn } from '../../../plugins/swrl-editor-plugin/src/resultsTheme';

type Rgba = [number, number, number, number];

const css = readFileSync(path.resolve(__dirname, '../index.css'), 'utf-8');

const block = (selector: RegExp): string => {
  const m = selector.exec(css);
  if (!m) throw new Error('block not found');
  let depth = 0;
  for (let i = css.indexOf('{', m.index); i < css.length; i++) {
    if (css[i] === '{') depth++;
    if (css[i] === '}' && --depth === 0) return css.slice(css.indexOf('{', m.index) + 1, i);
  }
  throw new Error('unterminated block');
};

const tokens = (body: string): Record<string, string> => {
  const out: Record<string, string> = {};
  for (const m of body.matchAll(/--([a-z0-9-]+)\s*:\s*([^;]+);/g)) out[m[1]] = m[2].trim();
  return out;
};

const light = tokens(block(/:root\s*\{/));
const dark = { ...light, ...tokens(block(/^\.dark\s*\{/m)) };

const parse = (value: string, set: Record<string, string>): Rgba => {
  const ref = /^var\(--([a-z0-9-]+)\)$/.exec(value);
  if (ref) return parse(set[ref[1]], set);
  const hex = /^#([0-9a-f]{6})$/i.exec(value);
  if (hex) {
    const n = parseInt(hex[1], 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 1];
  }
  const rgba = /^rgba?\(([^)]+)\)$/.exec(value);
  if (rgba) {
    const p = rgba[1].split(',').map(x => parseFloat(x));
    return [p[0], p[1], p[2], p[3] ?? 1];
  }
  throw new Error('cannot parse ' + value);
};

const over = (top: Rgba, base: Rgba): Rgba => {
  const a = top[3];
  return [0, 1, 2].map(i => top[i] * a + base[i] * (1 - a)).concat(1) as Rgba;
};

const lum = ([r, g, b]: Rgba) => {
  const f = (c: number) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
};

const ratio = (fg: Rgba, bg: Rgba) => {
  const [x, y] = [lum(fg), lum(bg)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
};

const contrast = (set: Record<string, string>, fg: string, bgLayers: string[]) => {
  let bg = parse(set[bgLayers[0]], set);
  for (const layer of bgLayers.slice(1)) bg = over(parse(set[layer], set), bg);
  return ratio(parse(set[fg], set), bg);
};

describe('SWRL results theme', () => {
  for (const [label, set] of [['light', light], ['dark', dark]] as const) {
    for (const pair of TOKEN_PAIRS) {
      it(`${pair.name} meets 4.5:1 in ${label}`, () => {
        expect(contrast(set, pair.fg, pair.bg)).toBeGreaterThanOrEqual(4.5);
      });
    }
  }

  it('summary value and its card never share a colour token', () => {
    expect(rt.value.color).not.toBe(rt.card.backgroundColor);
    expect(rt.label.color).not.toBe(rt.card.backgroundColor);
  });

  it('only uses theme variables, never fixed colours', () => {
    const all = JSON.stringify({ rt, d: btn(true), e: btn(false, true) });
    expect(all).not.toMatch(/#[0-9a-fA-F]{3,6}/);
    expect(all).not.toMatch(/rgb/);
  });

  it('disabled buttons keep readable text instead of fading with opacity', () => {
    expect(btn(true).opacity).toBeUndefined();
    expect(btn(true, true).opacity).toBeUndefined();
  });
});

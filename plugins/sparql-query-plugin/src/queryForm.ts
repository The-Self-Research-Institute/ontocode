export type QueryForm = 'select' | 'ask' | 'construct' | 'describe' | 'update' | 'unknown';

const UPDATE_WORDS = new Set(['INSERT', 'DELETE', 'LOAD', 'CLEAR', 'CREATE', 'DROP', 'COPY', 'MOVE', 'ADD', 'WITH']);

export const UPDATE_HINT = 'This is an update; use the update option';

function blankOutNoise(text: string): string {
  let out = '';
  let i = 0;
  const n = text.length;
  while (i < n) {
    const c = text[i];
    if (c === '#') {
      while (i < n && text[i] !== '\n') i++;
      continue;
    }
    if (c === '"' || c === "'") {
      const triple = text.startsWith(c.repeat(3), i);
      const quote = triple ? c.repeat(3) : c;
      i += quote.length;
      while (i < n && !text.startsWith(quote, i)) {
        if (text[i] === '\\') i++;
        else if (!triple && text[i] === '\n') break;
        i++;
      }
      i += quote.length;
      out += ' "" ';
      continue;
    }
    if (c === '<') {
      const end = text.indexOf('>', i + 1);
      const inner = end === -1 ? '' : text.slice(i + 1, end);
      if (end !== -1 && !/[\s<"{}|^`\\]/.test(inner)) {
        out += ' <i> ';
        i = end + 1;
        continue;
      }
    }
    out += c;
    i++;
  }
  return out;
}

export function detectQueryForm(text: string): QueryForm {
  if (!text) return 'unknown';
  let rest = blankOutNoise(text);
  for (;;) {
    const m = /^\s*(?:PREFIX\s+[^\s:]*:\s*<i>|BASE\s+<i>)/i.exec(rest);
    if (!m) break;
    rest = rest.slice(m[0].length);
  }
  const word = /^\s*([A-Za-z]+)/.exec(rest);
  if (!word) return 'unknown';
  const kw = word[1].toUpperCase();
  if (kw === 'SELECT') return 'select';
  if (kw === 'ASK') return 'ask';
  if (kw === 'CONSTRUCT') return 'construct';
  if (kw === 'DESCRIBE') return 'describe';
  if (UPDATE_WORDS.has(kw)) return 'update';
  return 'unknown';
}

export function isGraphForm(form: QueryForm): boolean {
  return form === 'construct' || form === 'describe';
}

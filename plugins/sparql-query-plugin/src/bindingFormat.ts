export interface RdfTerm {
  type: string;
  value: string;
  datatype?: string;
  'xml:lang'?: string;
}

export type TermRow = Record<string, RdfTerm>;

export interface PrefixEntry {
  prefix: string;
  namespace: string;
}

export interface FormattedTerm {
  text: string;
  title?: string;
  kind: 'uri' | 'literal' | 'bnode' | 'empty';
}

export const XSD_NS = 'http://www.w3.org/2001/XMLSchema#';

function lastSegment(iri: string): string {
  const hash = iri.lastIndexOf('#');
  const cut = hash >= 0 ? hash : iri.lastIndexOf('/');
  const tail = cut >= 0 ? iri.slice(cut + 1) : iri;
  return tail || iri;
}

export function shortenIri(iri: string, prefixes: PrefixEntry[] = []): string {
  let best: PrefixEntry | undefined;
  for (const p of prefixes) {
    if (p.namespace && iri.startsWith(p.namespace) && iri.length > p.namespace.length) {
      if (!best || p.namespace.length > best.namespace.length) best = p;
    }
  }
  if (best) return `${best.prefix}:${iri.slice(best.namespace.length)}`;
  return lastSegment(iri);
}

export function shortDatatype(datatype: string): string {
  if (datatype.startsWith(XSD_NS)) return `xsd:${datatype.slice(XSD_NS.length)}`;
  return lastSegment(datatype);
}

export function formatTerm(term: RdfTerm | undefined, prefixes: PrefixEntry[] = []): FormattedTerm {
  if (!term) return { text: '-', kind: 'empty' };
  if (term.type === 'uri') {
    return { text: shortenIri(term.value, prefixes), title: term.value, kind: 'uri' };
  }
  if (term.type === 'bnode') {
    return { text: `_:${term.value}`, title: `_:${term.value}`, kind: 'bnode' };
  }
  const lang = term['xml:lang'];
  const dt = term.datatype;
  let text = `"${term.value}"`;
  let title: string | undefined;
  if (lang) {
    text += `@${lang}`;
  } else if (dt && dt !== `${XSD_NS}string`) {
    text += `^^${shortDatatype(dt)}`;
    title = dt;
  }
  return { text, title, kind: 'literal' };
}

function looksLikeIri(value: string): boolean {
  return /^https?:\/\/\S+$/i.test(value) || /^urn:\S+$/i.test(value);
}

function toTerm(raw: any): RdfTerm {
  if (raw !== null && typeof raw === 'object') {
    const term: RdfTerm = {
      type: typeof raw.type === 'string' ? raw.type : 'literal',
      value: raw.value == null ? '' : String(raw.value)
    };
    if (raw.datatype) term.datatype = String(raw.datatype);
    if (raw['xml:lang']) term['xml:lang'] = String(raw['xml:lang']);
    return term;
  }
  const value = raw == null ? '' : String(raw);
  return { type: looksLikeIri(value) ? 'uri' : 'literal', value };
}

export function normalizeRows(response: any): TermRow[] {
  let source: any = Array.isArray(response?.bindings) ? response.bindings : response?.results;
  if (typeof source === 'string') {
    try { source = JSON.parse(source); } catch { source = []; }
  }
  const rows: any[] = Array.isArray(source)
    ? source
    : Array.isArray(source?.bindings)
      ? source.bindings
      : [];
  return rows.map((row) => {
    const out: TermRow = {};
    for (const [key, val] of Object.entries(row || {})) out[key] = toTerm(val);
    return out;
  });
}

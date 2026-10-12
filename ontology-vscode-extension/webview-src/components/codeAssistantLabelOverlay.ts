export interface PrefixMapping {
  prefix: string;
  namespace: string;
}

export interface LabelOverlaySegment {
  text: string;
  label?: string;
}

const CORE_VOCAB_NAMESPACES = [
  'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
  'http://www.w3.org/2000/01/rdf-schema#',
  'http://www.w3.org/2002/07/owl#',
  'http://www.w3.org/2001/XMLSchema#',
];

const TOKEN_PATTERN = /<([^<>\s]+)>|(?<![\w:])([A-Za-z_][\w.-]*)?:([A-Za-z_][\w.-]*)\b/g;

function isCoreVocabIri(iri: string): boolean {
  return CORE_VOCAB_NAMESPACES.some((ns) => iri.startsWith(ns));
}

function resolveFullIri(
  bracketedIri: string | undefined,
  prefix: string | undefined,
  localName: string | undefined,
  prefixMappings: PrefixMapping[],
): string | null {
  if (bracketedIri) {
    return bracketedIri;
  }
  if (prefix === 'http' || prefix === 'https') {
    return null;
  }
  if (localName === undefined) {
    return null;
  }
  const mapping = prefixMappings.find((m) => m.prefix === `${prefix ?? ''}:`);
  if (!mapping) {
    return null;
  }
  return mapping.namespace + localName;
}

export function resolveLabelOverlaySegments(
  text: string,
  labelMap: Map<string, string>,
  prefixMappings: PrefixMapping[],
): LabelOverlaySegment[] {
  const segments: LabelOverlaySegment[] = [];
  let lastIndex = 0;
  TOKEN_PATTERN.lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = TOKEN_PATTERN.exec(text)) !== null) {
    if (match.index > lastIndex) {
      segments.push({ text: text.slice(lastIndex, match.index) });
    }

    const [raw, bracketedIri, prefix, localName] = match;
    const fullIri = resolveFullIri(bracketedIri, prefix, localName, prefixMappings);
    const label = fullIri && !isCoreVocabIri(fullIri) ? labelMap.get(fullIri) : undefined;

    segments.push(label ? { text: raw, label } : { text: raw });
    lastIndex = match.index + raw.length;
  }

  if (lastIndex < text.length) {
    segments.push({ text: text.slice(lastIndex) });
  }

  return segments;
}

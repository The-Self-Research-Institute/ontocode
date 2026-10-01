const NAMESPACES: Record<string, string> = {
  'http://www.w3.org/2000/01/rdf-schema#': 'rdfs',
  'http://www.w3.org/1999/02/22-rdf-syntax-ns#': 'rdf',
  'http://www.w3.org/2002/07/owl#': 'owl',
  'http://www.w3.org/2004/02/skos/core#': 'skos',
  'http://www.w3.org/2001/XMLSchema#': 'xsd'
};

const LABELS: Record<string, string> = {
  'rdfs:label': 'Label',
  'rdfs:comment': 'Comment',
  'rdfs:subClassOf': 'Parent class',
  'rdfs:subPropertyOf': 'Parent property',
  'rdf:type': 'Type',
  'owl:disjointWith': 'Disjoint with',
  'rdfs:domain': 'Domain',
  'rdfs:range': 'Range',
  'owl:equivalentClass': 'Equivalent to',
  'skos:prefLabel': 'Preferred label',
  'skos:definition': 'Definition'
};

export function localName(iri: string): string {
  const trimmed = iri.replace(/^<|>$/g, '');
  const parts = trimmed.split(/[#/:]/).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : trimmed;
}

export function toCurie(predicate: string): string {
  const trimmed = predicate.trim().replace(/^<|>$/g, '');
  for (const ns of Object.keys(NAMESPACES)) {
    if (trimmed.startsWith(ns)) return `${NAMESPACES[ns]}:${trimmed.slice(ns.length)}`;
  }
  return trimmed;
}

export function predicateLabel(predicate?: string | null): string {
  if (!predicate) return 'Value';
  const curie = toCurie(predicate);
  if (LABELS[curie]) return LABELS[curie];
  const name = localName(curie);
  return name ? name.charAt(0).toUpperCase() + name.slice(1) : 'Value';
}

function isIri(value: string): boolean {
  return /^<?(https?:|urn:)[^\s]+>?$/.test(value);
}

export function formatValue(value?: string | null): string {
  if (!value) return '';
  const trimmed = value.trim();
  if (isIri(trimmed)) return localName(trimmed);
  const literal = trimmed.match(/^"([\s\S]*)"(?:@[\w-]+|\^\^\S+)?$/);
  if (literal) return literal[1];
  return trimmed;
}

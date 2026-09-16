export const DEFAULT_ONTOLOGY_BASE_IRI = 'http://example.com/onto';

const FULL_IRI_PATTERN = /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//;

export function isFullIriString(value: string): boolean {
  return FULL_IRI_PATTERN.test(value.trim());
}

export function sanitizeIriFragment(name: string): string {
  return name.trim().replace(/\s+/g, '_');
}

export function buildEntityIri(ontologyBaseIri: string | undefined, rawName: string): string {
  const trimmed = rawName.trim();
  if (!trimmed) return '';
  if (isFullIriString(trimmed)) return trimmed;
  const base = ontologyBaseIri || DEFAULT_ONTOLOGY_BASE_IRI;
  return `${base}#${sanitizeIriFragment(trimmed)}`;
}

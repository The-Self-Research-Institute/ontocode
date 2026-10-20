export const DEFAULT_ONTOLOGY_BASE_IRI = 'http://example.com/onto';

const FULL_IRI_PATTERN = /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//;

export function isFullIriString(value: string): boolean {
  return FULL_IRI_PATTERN.test(value.trim());
}

export function sanitizeIriFragment(name: string): string {
  return name.trim().replace(/\s+/g, '_');
}

export function labelForNewEntity(rawName: string): string {
  const trimmed = rawName.trim();
  if (!trimmed || isFullIriString(trimmed)) return '';
  return sanitizeIriFragment(trimmed) === trimmed ? '' : trimmed;
}

export function buildEntityIri(ontologyBaseIri: string | undefined, rawName: string): string {
  const trimmed = rawName.trim();
  if (!trimmed) return '';
  if (isFullIriString(trimmed)) return trimmed;
  const base = (ontologyBaseIri || DEFAULT_ONTOLOGY_BASE_IRI).trim();
  const separator = base.endsWith('#') || base.endsWith('/') ? '' : '#';
  return `${base}${separator}${sanitizeIriFragment(trimmed)}`;
}

export interface EntityIriPreview {
  trimmedName: string;
  computedIri: string;
  isDuplicate: boolean;
  canCreate: boolean;
}

export function computeEntityIriPreview(
  ontologyBaseIri: string | undefined,
  rawName: string,
  existingIris: string[],
): EntityIriPreview {
  const trimmedName = rawName.trim();
  const computedIri = buildEntityIri(ontologyBaseIri, trimmedName);
  const isDuplicate = !!computedIri && existingIris.includes(computedIri);
  const canCreate = !!trimmedName && !isDuplicate;
  return { trimmedName, computedIri, isDuplicate, canCreate };
}

const BULK_CHANGE_PREFIXES = ["urn:ontocode:spreadsheet-import:", "urn:ontocode:merge:"];

export function isBulkChangeIri(iri: unknown): boolean {
  const text = String(iri ?? "");
  return BULK_CHANGE_PREFIXES.some((prefix) => text.startsWith(prefix));
}

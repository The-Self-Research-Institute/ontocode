import type { EntityPreferences } from '../contexts/EntityPreferencesContext';

export type EntityIriPreferences = Pick<EntityPreferences, 'autoGenerateIRI' | 'iriGenerationPattern' | 'customPattern'>;

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

export function buildEntityIri(ontologyBaseIri: string | undefined, rawName: string, preferences?: EntityIriPreferences): string {
  const trimmed = rawName.trim();
  if (!trimmed) return '';
  if (isFullIriString(trimmed)) return trimmed;
  const base = (ontologyBaseIri || DEFAULT_ONTOLOGY_BASE_IRI).trim();
  const fragment = sanitizeIriFragment(trimmed);
  if (preferences?.autoGenerateIRI) {
    const namespace = base.replace(/[#/]$/, '');
    if (preferences.iriGenerationPattern === 'namespace_slash_entity') return `${namespace}/${fragment}`;
    if (preferences.iriGenerationPattern === 'namespace_entity') return `${namespace}${fragment}`;
    if (preferences.iriGenerationPattern === 'custom' && preferences.customPattern?.trim()) {
      return preferences.customPattern.trim().replace(/\{(namespace|entity)\}/g,
        (_, key) => key === 'namespace' ? namespace : fragment);
    }
  }
  const separator = base.endsWith('#') || base.endsWith('/') ? '' : '#';
  return `${base}${separator}${fragment}`;
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
  preferences?: EntityIriPreferences,
  iriOverride?: string | null,
): EntityIriPreview {
  const trimmedName = rawName.trim();
  const computedIri = iriOverride?.trim() ?? buildEntityIri(ontologyBaseIri, trimmedName, preferences);
  const isDuplicate = !!computedIri && existingIris.includes(computedIri);
  const canCreate = !!trimmedName && !isDuplicate && (iriOverride == null || isFullIriString(computedIri) && !/\s/.test(computedIri));
  return { trimmedName, computedIri, isDuplicate, canCreate };
}

const BULK_CHANGE_PREFIXES = ["urn:ontocode:spreadsheet-import:", "urn:ontocode:merge:"];

export function isBulkChangeIri(iri: unknown): boolean {
  const text = String(iri ?? "");
  return BULK_CHANGE_PREFIXES.some((prefix) => text.startsWith(prefix));
}

export interface AncestorEntry {
  id: string;
  ancestorIri?: string;
  navigable?: boolean | string;
  fromDefinition?: boolean | string;
  isRestriction?: boolean | string;
  fillerIri?: string;
  definition?: string;
}

export interface InheritanceInfo {
  kind: 'asserted' | 'inherited';
  fromIri?: string;
  fromLabel?: string;
  fromDefinition: boolean;
  note?: string;
}

const isTrue = (value: unknown) => value === true || value === 'true';

export const lastSegment = (iri: string): string => {
  const cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
  return cut >= 0 ? iri.slice(cut + 1) : iri;
};

export function describeInheritance(
  entry: AncestorEntry,
  selfIri: string | undefined,
  selfLabel: string | undefined,
  labelFor: (iri: string) => string | undefined,
): InheritanceInfo | null {
  const from = entry.ancestorIri;
  if (!from || !selfIri) return null;
  if (from === selfIri) return { kind: 'asserted', fromDefinition: false };
  const fromLabel = labelFor(from) || lastSegment(from);
  const fromDefinition = isTrue(entry.fromDefinition);
  let note: string | undefined;
  if (!isTrue(entry.navigable)) {
    const filler = entry.fillerIri && /^(https?:|urn:)/.test(entry.fillerIri) ? entry.fillerIri : undefined;
    note = filler
      ? `Inherited condition. ${selfLabel || lastSegment(selfIri)} is not a subclass of ${labelFor(filler) || lastSegment(filler)}.`
      : 'Inherited condition. It describes this class but does not add a parent.';
  }
  return { kind: 'inherited', fromIri: from, fromLabel, fromDefinition, note };
}

export function buildLabelLookup(...lists: Array<Array<{ id?: string; definition?: string; navigable?: boolean | string }> | undefined>) {
  const labels = new Map<string, string>();
  for (const list of lists) {
    for (const item of list || []) {
      if (item.id && /^(https?:|urn:)/.test(item.id) && item.definition && isTrue(item.navigable ?? true)) {
        labels.set(item.id, item.definition);
      }
    }
  }
  return (iri: string) => labels.get(iri);
}

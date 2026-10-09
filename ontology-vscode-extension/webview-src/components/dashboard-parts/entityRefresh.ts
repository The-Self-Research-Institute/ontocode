export const RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";

export type EntityKind = "class" | "individual" | "property";

const PROPERTY_TYPES = new Set(["ObjectProperty", "DatatypeProperty", "AnnotationProperty"]);
const PROPERTY_TABS = new Set(["ObjectProperties", "DataProperties", "AnnotationProperties"]);

export function entityKindOf(item: { type?: unknown } | null | undefined, entitiesTab: string): EntityKind {
  const type = typeof item?.type === "string" ? item.type : "";
  if (PROPERTY_TYPES.has(type) || PROPERTY_TABS.has(entitiesTab)) {
    return "property";
  }
  if (/individual/i.test(type) || entitiesTab === "Individuals") {
    return "individual";
  }
  return "class";
}

export function entityDetailsUrl(kind: EntityKind, projectId: string, iri: string): string {
  const encodedIri = encodeURIComponent(iri);
  if (kind === "property") {
    return `/api/ontology/properties/detail/${encodeURIComponent(projectId)}?iri=${encodedIri}`;
  }
  if (kind === "individual") {
    return `/api/ontology/individual-details/${projectId}?individualIri=${encodedIri}`;
  }
  return `/api/ontology/classes/details/${projectId}?classIri=${encodedIri}`;
}

export function annotationText(value: unknown): string | undefined {
  if (Array.isArray(value)) {
    return annotationText(value[0]);
  }
  if (typeof value === "string") {
    return value.trim() ? value : undefined;
  }
  if (value && typeof value === "object") {
    const record = value as Record<string, unknown>;
    return annotationText(record.value ?? record.literal ?? record.label);
  }
  return undefined;
}

export function unwrapDetails(response: unknown): Record<string, any> | null {
  let payload: any = response;
  for (let i = 0; i < 2 && payload && typeof payload === "object" && "data" in payload && payload.data; i++) {
    payload = payload.data;
  }
  return payload && typeof payload === "object" ? payload : null;
}

export function mergeFreshDetails<T extends { id: string; label?: string; annotations?: any }>(
  kind: EntityKind,
  current: T,
  details: Record<string, any>,
): T {
  const annotations = details.annotations ?? current.annotations;
  const freshLabel =
    (typeof details.label === "string" && details.label.trim() ? details.label : undefined) ??
    annotationText(annotations?.[RDFS_LABEL]) ??
    current.label;
  if (kind === "property") {
    return { ...current, ...details, id: current.id, annotations, label: freshLabel, _propertyDetailsLoaded: true };
  }
  if (kind === "individual") {
    const fields = ["types", "propertyAssertions", "sameIndividualAs", "differentIndividualFrom", "fuzzyMemberships"];
    const picked = Object.fromEntries(fields.filter((f) => details[f] !== undefined).map((f) => [f, details[f]]));
    return { ...current, ...picked, annotations, label: freshLabel };
  }
  return { ...current, annotations, label: freshLabel };
}

export function isEditingFormField(active: Element | null | undefined): boolean {
  if (!active) {
    return false;
  }
  const tag = active.tagName;
  if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") {
    return true;
  }
  return (active as HTMLElement).isContentEditable === true;
}

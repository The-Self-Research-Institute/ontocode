export interface DraftChange {
  id: string;
  operationType: string;
  operationData: Record<string, any>;
}

const HEADER_OPS = new Set([
  "updateOntologyIri",
  "addOntologyAnnotation",
  "updateOntologyAnnotation",
  "deleteOntologyAnnotation",
  "addOntologyImport",
  "deleteOntologyImport",
  "addPrefix",
  "updatePrefix",
  "deletePrefix",
]);

const GCI_OPS = new Set(["addGCI", "updateGCI", "deleteGCI"]);

export const getCategory = (opType: string): string => {
  if (HEADER_OPS.has(opType)) {
    return "Ontology header";
  }
  if (GCI_OPS.has(opType)) {
    return "GCI axioms";
  }
  if (opType === "ontologyMerge") {
    return "Ontology merge";
  }
  if (opType === "addClassExpression") {
    return "Class Axioms";
  }
  if (opType === "addAxiomAnnotation" || opType === "deleteAxiomAnnotation") {
    return "Annotations";
  }
  if (/^(create|delete|update)Class/i.test(opType)) return "Classes";
  if (
    /^(create|delete)ObjectProperty/i.test(opType) ||
    /^(add|delete)(PropertyDomain|PropertyRange|SubPropertyOf|InverseProperty|DisjointProperty|EquivalentProperty)/i.test(opType)
  )
    return "Object Properties";
  if (/^(create|delete)DataProperty/i.test(opType)) return "Data Properties";
  if (/^(create|delete)AnnotationProperty/i.test(opType)) return "Annotation Properties";
  if (
    /^(create|delete)Individual/i.test(opType) ||
    /^(add|remove)ClassAssertion/i.test(opType)
  )
    return "Individuals";
  if (/^(create|delete)Datatype/i.test(opType)) return "Datatypes";
  if (/^(add|delete|update)(SubClassOf|EquivalentClass|DisjointWith)/i.test(opType))
    return "Class Axioms";
  if (/^(add|delete|update)Annotation/i.test(opType)) return "Annotations";
  return "Other";
};

export const CATEGORY_ORDER = [
  "Ontology merge",
  "Ontology header",
  "Classes",
  "Object Properties",
  "Data Properties",
  "Annotation Properties",
  "Individuals",
  "Datatypes",
  "Class Axioms",
  "GCI axioms",
  "Annotations",
  "Other",
];

export const getEntityName = (opType: string, data: Record<string, any>): string => {
  if (!data) return "";
  const label = data.label as string;
  if (label) return label;
  const iri = (data.iri || data.target || "") as string;
  if (iri) {
    const last = Math.max(iri.lastIndexOf("#"), iri.lastIndexOf("/"));
    const name = iri.substring(last + 1);
    return name || iri;
  }
  return opType;
};

export const getEntityIri = (data: Record<string, any>): string =>
  ((data?.iri || data?.target || data?.classIri || "") as string);

export const extractLocalName = (iri: string): string => {
  if (!iri) return "";
  const last = Math.max(iri.lastIndexOf("#"), iri.lastIndexOf("/"));
  return iri.substring(last + 1) || iri;
};

export const getActionMeta = (opType: string): { symbol: string; cls: string } => {
  const lower = opType.toLowerCase();
  if (lower.startsWith("create") || lower.startsWith("add"))
    return { symbol: "+", cls: "text-green-500" };
  if (lower.startsWith("delete") || lower.startsWith("remove"))
    return { symbol: "−", cls: "text-red-500" };
  return { symbol: "~", cls: "text-amber-500" };
};

export const getOpLabel = (opType: string): string =>
  opType
    .replace(/([A-Z])/g, " $1")
    .replace(/^./, (s) => s.toUpperCase())
    .trim();

export const groupByCategory = (
  changes: DraftChange[]
): Record<string, DraftChange[]> => {
  const grouped: Record<string, DraftChange[]> = {};
  for (const c of changes) {
    const cat = getCategory(c.operationType);
    (grouped[cat] = grouped[cat] || []).push(c);
  }
  return grouped;
};

export interface ChangeDescription {
  label: string;
  lines: string[];
}

const KNOWN_NAMESPACES: Record<string, string> = {
  "http://purl.org/dc/elements/1.1/": "dc",
  "http://purl.org/dc/terms/": "dcterms",
  "http://www.w3.org/2000/01/rdf-schema#": "rdfs",
  "http://www.w3.org/2002/07/owl#": "owl",
  "http://www.w3.org/2004/02/skos/core#": "skos",
  "http://www.w3.org/1999/02/22-rdf-syntax-ns#": "rdf",
};

const isBlank = (value: unknown): boolean => value === undefined || value === null || value === "";

export const displayValue = (value: unknown): string => {
  if (Array.isArray(value)) {
    return value.length ? value.map(String).join(", ") : "(none)";
  }
  return isBlank(value) ? "(none)" : String(value);
};

export const shortPropertyName = (iri: string): string => {
  if (!iri) {
    return "";
  }
  for (const [ns, prefix] of Object.entries(KNOWN_NAMESPACES)) {
    if (iri.startsWith(ns) && iri.length > ns.length) {
      return `${prefix}:${iri.substring(ns.length)}`;
    }
  }
  return extractLocalName(iri);
};

const literal = (value: unknown, language?: unknown): string =>
  `"${displayValue(value)}"${isBlank(language) ? "" : `@${language}`}`;

const angle = (value: unknown): string => (isBlank(value) ? "(none)" : `<${value}>`);

export const describeChange = (opType: string, data: Record<string, any>): ChangeDescription | null => {
  if (!data) {
    return null;
  }
  switch (opType) {
    case "updateOntologyIri": {
      const lines: string[] = [];
      if (displayValue(data.oldValue) !== displayValue(data.value)) {
        lines.push(`Ontology IRI: ${displayValue(data.oldValue)} → ${displayValue(data.value)}`);
      }
      if (displayValue(data.oldVersionIri) !== displayValue(data.versionIri)) {
        lines.push(`Version IRI: ${displayValue(data.oldVersionIri)} → ${displayValue(data.versionIri)}`);
      }
      if (lines.length === 0) {
        lines.push(`Ontology IRI: ${displayValue(data.value)}`);
      }
      return { label: "Ontology IRI", lines };
    }
    case "addOntologyAnnotation":
      return { label: `Annotation ${shortPropertyName(data.property)}`, lines: [`Added ${literal(data.value, data.language)}`] };
    case "updateOntologyAnnotation": {
      const before = isBlank(data.oldProperty)
        ? literal(data.oldValue)
        : `${shortPropertyName(data.oldProperty)} ${literal(data.oldValue)}`;
      const after = isBlank(data.oldProperty)
        ? literal(data.value, data.language)
        : `${shortPropertyName(data.property)} ${literal(data.value, data.language)}`;
      return { label: `Annotation ${shortPropertyName(data.property)}`, lines: [`${before} → ${after}`] };
    }
    case "deleteOntologyAnnotation":
      return { label: `Annotation ${shortPropertyName(data.property)}`, lines: [`Removed ${literal(data.value, data.language)}`] };
    case "addOntologyImport":
      return { label: "Import added", lines: [displayValue(data.value)] };
    case "deleteOntologyImport":
      return { label: "Import removed", lines: [displayValue(data.value)] };
    case "addPrefix":
      return { label: `Prefix ${data.prefix ?? ""}:`, lines: [`Added ${angle(data.value)}`] };
    case "updatePrefix": {
      const lines: string[] = [];
      if (!isBlank(data.oldPrefix)) {
        lines.push(`Renamed ${data.oldPrefix}: → ${data.prefix}:`);
      }
      if (displayValue(data.oldValue) !== displayValue(data.value)) {
        lines.push(`${angle(data.oldValue)} → ${angle(data.value)}`);
      }
      if (lines.length === 0) {
        lines.push(angle(data.value));
      }
      return { label: `Prefix ${data.prefix ?? ""}:`, lines };
    }
    case "deletePrefix":
      return { label: `Prefix ${data.prefix ?? ""}:`, lines: [`Removed ${angle(data.oldValue ?? data.value)}`] };
    case "addGCI":
      return { label: "GCI", lines: [`Added: ${displayValue(data.value)}`] };
    case "updateGCI":
      return { label: "GCI", lines: [`${displayValue(data.oldValue)} → ${displayValue(data.value)}`] };
    case "deleteGCI":
      return { label: "GCI", lines: [`Removed: ${displayValue(data.value ?? data.oldValue)}`] };
    case "addPropertyDomain":
    case "addPropertyRange":
    case "deletePropertyDomain":
    case "deletePropertyRange": {
      const relation = opType.endsWith("Domain") ? "Domain" : "Range";
      const verb = opType.startsWith("add") ? "added" : "removed";
      return { label: extractLocalName(data.iri), lines: [`${relation} ${verb}: ${displayValue(data.value)}`] };
    }
    case "addClassExpression":
      return {
        label: extractLocalName(data.iri),
        lines: [`Class expression ${displayValue(data.axiomType)}: ${displayValue(data.value)}`],
      };
    case "addAxiomAnnotation":
    case "deleteAxiomAnnotation": {
      const verb = opType.startsWith("add") ? "Added" : "Removed";
      const on = [data.axiomType, data.target ? extractLocalName(data.target) : null].filter((v) => !isBlank(v)).join(" ");
      return {
        label: extractLocalName(data.iri),
        lines: [`${verb} axiom annotation ${shortPropertyName(data.property)} ${literal(data.value, data.language)}${on ? ` on ${on}` : ""}`],
      };
    }
    case "deleteCitation":
      return { label: "Citation", lines: [`Removed ${displayValue(data.value ?? data.iri)}`] };
    case "bulkGraphEdit":
    case "structuralGraphEdit":
      return {
        label: displayValue(data.label),
        lines: isBlank(data.value) ? [] : [String(data.value)],
      };
    case "ontologyMerge":
      return {
        label: "Ontology merge",
        lines: [displayValue(data.mergeSummary?.description ?? data.value ?? "Merged another ontology into this draft")],
      };
    default:
      return null;
  }
};

export type MergeChange = "added" | "changed" | "removed" | "copies";

export interface MergeKindSummary {
  kind: string;
  added?: string[];
  addedCount?: number;
  changed?: string[];
  changedCount?: number;
  removed?: string[];
  removedCount?: number;
  copies?: string[];
  copiesCount?: number;
}

export interface MergeSummary {
  description?: string;
  totals?: Record<string, number>;
  kinds?: MergeKindSummary[];
}

export const MERGE_CHANGES: MergeChange[] = ["added", "changed", "removed", "copies"];

const KIND_NAMES: Record<string, [string, string]> = {
  class: ["class", "classes"],
  objectProperty: ["object property", "object properties"],
  dataProperty: ["data property", "data properties"],
  annotationProperty: ["annotation property", "annotation properties"],
  individual: ["individual", "individuals"],
  datatype: ["datatype", "datatypes"],
  other: ["other item", "other items"],
};

export const mergeKindTitle = (kind: string): string => {
  const plural = (KIND_NAMES[kind] || KIND_NAMES.other)[1];
  return plural.charAt(0).toUpperCase() + plural.slice(1);
};

export const mergeNames = (kind: MergeKindSummary, change: MergeChange): string[] =>
  Array.isArray(kind[change]) ? (kind[change] as string[]) : [];

export const mergeCount = (kind: MergeKindSummary, change: MergeChange): number => {
  const count = kind[`${change}Count` as keyof MergeKindSummary];
  return typeof count === "number" ? count : mergeNames(kind, change).length;
};

const preview = (names: string[], count: number, max = 3): string => {
  if (names.length === 0) {
    return "";
  }
  const shown = names.slice(0, max).join(", ");
  return ` (${shown}${count > Math.min(max, names.length) ? ", …" : ""})`;
};

export const mergeKindSentence = (kind: MergeKindSummary): string => {
  const [singular, plural] = KIND_NAMES[kind.kind] || KIND_NAMES.other;
  const parts: string[] = [];
  for (const change of MERGE_CHANGES) {
    const count = mergeCount(kind, change);
    if (!count) {
      continue;
    }
    const first = parts.length === 0;
    const names = mergeNames(kind, change);
    const withNames = change === "changed" ? "" : preview(names, count);
    if (change === "copies") {
      const copies = count === 1 ? "copy added" : "copies added";
      parts.push(`${count} ${first ? `${singular} ` : ""}${copies}${withNames}`);
    } else {
      parts.push(`${count}${first ? ` ${count === 1 ? singular : plural}` : ""} ${change}${withNames}`);
    }
  }
  return parts.join(", ");
};

export const mergeHeadline = (summary: MergeSummary | undefined, fallback?: string): string => {
  const description = summary?.description || fallback;
  const sentences = (summary?.kinds || []).map(mergeKindSentence).filter(Boolean);
  const head = description ? `Ontology merge — ${description}` : "Ontology merge";
  return sentences.length ? `${head}: ${sentences.join("; ")}` : head;
};

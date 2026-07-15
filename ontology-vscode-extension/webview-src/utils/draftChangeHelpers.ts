export interface DraftChange {
  id: string;
  operationType: string;
  operationData: Record<string, any>;
}

export const getCategory = (opType: string): string => {
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
  "Classes",
  "Object Properties",
  "Data Properties",
  "Annotation Properties",
  "Individuals",
  "Datatypes",
  "Class Axioms",
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

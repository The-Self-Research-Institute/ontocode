/* eslint-disable @typescript-eslint/no-explicit-any */
import { notificationService } from "../../services/notificationService";
import type { AnnotationProperty } from "../../types";

export type TopLevelClass = { id: string; label?: string; hasChildren: boolean; [key: string]: any };

export type FileInfo = {
  id: string;
  filename: string;
  contentType?: string | null;
  length: number;
  uploadDate: string;
  projectId?: string | null;
  size?: number;
  permission?: "view" | "edit";
  sharedBy?: string;
  ownerEmail?: string;
};

export const findParentNode = (nodes: any[], targetId: string, parent: any | null = null): any | null => {
  for (const node of nodes) {
    if (node.id === targetId) return parent;
    if (node.children && node.children.length) {
      const found = findParentNode(node.children, targetId, node);
      if (found) return found;
    }
  }
  return null;
};

export const DATATYPE_IRI_MAP: Record<string, string> = {
  "xsd:string": "http://www.w3.org/2001/XMLSchema#string",
  "xsd:boolean": "http://www.w3.org/2001/XMLSchema#boolean",
  "xsd:integer": "http://www.w3.org/2001/XMLSchema#integer",
  "xsd:decimal": "http://www.w3.org/2001/XMLSchema#decimal",
  "xsd:dateTime": "http://www.w3.org/2001/XMLSchema#dateTime",
  "xsd:anyURI": "http://www.w3.org/2001/XMLSchema#anyURI",
};

export const REASONER_ID_MAP: Record<string, string> = {
  HermiT: "HERMIT",
  ELK: "ELK",
  Pellet: "PELLET",
  Openllet: "OPENLLET",
  Structural: "STRUCTURAL",
};

export const REASONER_OPTIONS = Object.keys(REASONER_ID_MAP);

export const normalizeReasonerType = (label: string): string => REASONER_ID_MAP[label] || "HERMIT";

/** Parse entity declaration counts from warm, cache-status, top-level, or metadata API payloads. */
export function extractDeclarationCountsPatch(countsRes: any): Record<string, number> | null {
  const data = countsRes?.data ?? countsRes;
  if (!data || typeof data !== "object") return null;
  const num = (v: unknown) => {
    if (typeof v === "number" && Number.isFinite(v)) return v;
    if (typeof v === "string" && v.trim() !== "") {
      const n = Number(v);
      if (Number.isFinite(n)) return n;
    }
    return undefined;
  };
  const nested = data.counts && typeof data.counts === "object" ? data.counts : {};
  const patch: Record<string, number> = {};
  const classCount = num(data.classCount) ?? num(nested.classes);
  const objectPropertyCount = num(data.objectPropertyCount) ?? num(nested.objectProperties);
  const dataPropertyCount = num(data.dataPropertyCount) ?? num(nested.dataProperties);
  const individualCount = num(data.individualCount) ?? num(nested.individuals);
  const annotationPropertyCount =
    num(data.annotationPropertyCount) ?? num(nested.annotationProperties);
  if (classCount !== undefined) patch.classCount = classCount;
  if (objectPropertyCount !== undefined) patch.objectPropertyCount = objectPropertyCount;
  if (dataPropertyCount !== undefined) patch.dataPropertyCount = dataPropertyCount;
  if (individualCount !== undefined) patch.individualCount = individualCount;
  if (annotationPropertyCount !== undefined) patch.annotationPropertyCount = annotationPropertyCount;
  return Object.keys(patch).length > 0 ? patch : null;
}

export const buildHierarchyTree = (nodes: any[]): any[] => {
  if (!Array.isArray(nodes)) return [];

  const stack: any[] = [];
  const roots: any[] = [];

  nodes.forEach((node) => {
    const depth = Number((node && (node as any).depth) ?? 0);
    const copy = { ...node, children: [] as any[] };

    while (stack.length > 0 && (stack[stack.length - 1]?.depth ?? 0) >= depth) {
      stack.pop();
    }

    if (stack.length === 0) {
      roots.push(copy);
    } else {
      stack[stack.length - 1].children.push(copy);
    }

    stack.push(copy);
  });

  return roots;
};

export const extractResponseData = (payload: any) => {
  if (payload && typeof payload === "object" && "data" in payload) {
    return (payload as any).data ?? {};
  }
  return payload ?? {};
};

export const normalizePrefixMappings = (prefixesData: unknown): Array<{ prefix: string; namespace: string }> => {
  if (Array.isArray(prefixesData)) {
    return prefixesData.map((entry: any) => {
      const prefixValue = entry?.prefix ?? entry?.p ?? "";
      const namespaceValue = entry?.namespace ?? entry?.iri ?? entry?.uri ?? "";
      return {
        prefix: prefixValue ? (String(prefixValue).endsWith(":") ? String(prefixValue) : `${prefixValue}:`) : ":",
        namespace: typeof namespaceValue === "string" ? namespaceValue : String(namespaceValue ?? ""),
      };
    });
  }

  if (prefixesData && typeof prefixesData === "object") {
    return Object.entries(prefixesData as Record<string, unknown>).map(([prefix, namespace]) => ({
      prefix: prefix ? (prefix.endsWith(":") ? prefix : `${prefix}:`) : ":",
      namespace: typeof namespace === "string" ? namespace : String(namespace ?? ""),
    }));
  }

  return [];
};

export const normalizeOntologyAnnotation = (annotation: any) => {
  if (!annotation || annotation.value === undefined) {
    return null;
  }
  const propertyIri = annotation.propertyIri || annotation.property;
  if (!propertyIri) {
    return null;
  }
  return { ...annotation, propertyIri, property: propertyIri };
};

export const normalizeOntologyAnnotations = (annotations: unknown) =>
  (Array.isArray(annotations) ? annotations : [])
    .map(normalizeOntologyAnnotation)
    .filter((annotation): annotation is NonNullable<ReturnType<typeof normalizeOntologyAnnotation>> => annotation !== null);

export const mapAnnotationProperty = (prop: any): AnnotationProperty => {
  const id = prop?.id || prop?.iri;
  if (!id) {
    return prop;
  }
  const annotations = { ...(prop?.annotations || {}) };
  if (prop?.label && !annotations["http://www.w3.org/2000/01/rdf-schema#label"]) {
    annotations["http://www.w3.org/2000/01/rdf-schema#label"] = String(prop.label);
  }
  if (prop?.description && !annotations["http://www.w3.org/2000/01/rdf-schema#comment"]) {
    annotations["http://www.w3.org/2000/01/rdf-schema#comment"] = String(prop.description);
  }
  return {
    ...prop,
    id,
    label: prop?.label || id.split("#").pop() || id.split("/").pop() || id,
    annotations,
  };
};

export const STANDARD_ANNOTATION_PROPERTIES: AnnotationProperty[] = [
  { id: "http://purl.org/dc/elements/1.1/description", label: "dc:description" },
  { id: "http://purl.org/dc/elements/1.1/title", label: "dc:title" },
  { id: "http://www.w3.org/2002/07/owl#backwardCompatibleWith", label: "owl:backwardCompatibleWith" },
  { id: "http://www.w3.org/2002/07/owl#deprecated", label: "owl:deprecated" },
  { id: "http://www.w3.org/2002/07/owl#incompatibleWith", label: "owl:incompatibleWith" },
  { id: "http://www.w3.org/2002/07/owl#priorVersion", label: "owl:priorVersion" },
  { id: "http://www.w3.org/2002/07/owl#versionInfo", label: "owl:versionInfo" },
  { id: "http://www.w3.org/2000/01/rdf-schema#comment", label: "rdfs:comment" },
  { id: "http://www.w3.org/2000/01/rdf-schema#isDefinedBy", label: "rdfs:isDefinedBy" },
  { id: "http://www.w3.org/2000/01/rdf-schema#label", label: "rdfs:label" },
  { id: "http://www.w3.org/2000/01/rdf-schema#seeAlso", label: "rdfs:seeAlso" },
];

export const mergeAnnotationProperties = (properties: AnnotationProperty[]): AnnotationProperty[] => {
  const merged = new Map<string, AnnotationProperty>();
  STANDARD_ANNOTATION_PROPERTIES.forEach((property) => merged.set(property.id, property));
  properties.forEach((property) => {
    const existing = merged.get(property.id);
    merged.set(property.id, existing ? { ...existing, ...property } : property);
  });
  return Array.from(merged.values());
};

export const combineReasonerResults = (classificationPayload: any, statsPayload?: any) => {
  if (!classificationPayload || (classificationPayload.error && !classificationPayload.data)) {
    console.error("[Dashboard] Invalid classification response:", classificationPayload);
    return {
      classHierarchy: [],
      classHierarchyTree: [],
      objectPropertyHierarchy: [],
      dataPropertyHierarchy: [],
      equivalentClasses: [],
      unsatisfiableClasses: [],
      totalClasses: 0,
      stats: {
        classHierarchyNodes: 0,
        objectPropertyNodes: 0,
        dataPropertyNodes: 0,
        individuals: 0,
        satisfiableClasses: 0,
        unsatisfiableClasses: 0,
        isConsistent: true,
      },
    };
  }

  const classificationData = extractResponseData(classificationPayload);
  const statsData = statsPayload ? extractResponseData(statsPayload) : null;
  const existingStats = (classificationData as any)?.stats || {};

  const rawClassHierarchy = (classificationData as any)?.classHierarchy;
  const classHierarchyArray = Array.isArray(rawClassHierarchy) ? rawClassHierarchy : [];
  const classHierarchyTree = buildHierarchyTree(classHierarchyArray);

  if (!statsData) {
    return {
      ...classificationData,
      classHierarchyTree,
    };
  }

  const unsatRaw = statsData.unsatisfiableClasses;
  const unsatCount = unsatRaw === -1 ? 0 : statsData.unsatisfiableClasses || 0;
  const isConsistent = statsData.isConsistent === false || unsatRaw === -1 ? false : true;

  return {
    ...classificationData,
    classHierarchyTree,
    stats: {
      ...existingStats,
      unsatisfiableClassesRaw: unsatRaw,
      classHierarchyNodes: statsData.classCount ?? existingStats.classHierarchyNodes ?? 0,
      objectPropertyNodes: statsData.propertyCount ?? existingStats.objectPropertyNodes ?? 0,
      dataPropertyNodes: statsData.dataPropertyCount ?? existingStats.dataPropertyNodes ?? 0,
      individuals: statsData.individualCount ?? existingStats.individuals ?? 0,
      satisfiableClasses: statsData.satisfiableClasses ?? existingStats.satisfiableClasses ?? 0,
      unsatisfiableClasses: unsatCount,
      isConsistent,
    },
  };
};

export const showNotification = (message: string, type: "info" | "error" | "warning" = "info") => {
  console.log(`[${type.toUpperCase()}]`, message);
  if (window.vscode) {
    window.vscode.postMessage({
      type: "notification",
      level: type,
      message,
    });
    return;
  }
  const titleByType: Record<typeof type, string> = {
    info: "Notice",
    warning: "Heads up",
    error: "Error",
  };
  notificationService.notify({
    title: titleByType[type],
    message,
    type: type === "warning" ? "warning" : type === "error" ? "error" : "info",
    duration: type === "error" ? 8000 : 5000,
  });
};

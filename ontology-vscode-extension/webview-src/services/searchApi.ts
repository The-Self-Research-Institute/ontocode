import apiClient from "./apiClient";

export type SearchEntityType =
  | "CLASS"
  | "OBJECT_PROPERTY"
  | "DATA_PROPERTY"
  | "ANNOTATION_PROPERTY"
  | "DATATYPE"
  | "INDIVIDUAL";

export const SEARCH_TYPE_BY_TAB: Record<string, SearchEntityType> = {
  Classes: "CLASS",
  ObjectProperties: "OBJECT_PROPERTY",
  DataProperties: "DATA_PROPERTY",
  AnnotationProperties: "ANNOTATION_PROPERTY",
  Datatypes: "DATATYPE",
  Individuals: "INDIVIDUAL",
};

export interface EntitySearchOptions {
  query: string;
  types: SearchEntityType[];
  annotations?: boolean;
  regex?: boolean;
  hideDeprecated?: boolean;
  hideBuiltins?: boolean;
  caseSensitive?: boolean;
  wholeWords?: boolean;
  ignoreWhitespace?: boolean;
  searchIris?: boolean;
  showAll?: boolean;
  limit?: number;
  scope?: string;
  userId?: string;
  draft?: boolean;
}

export interface EntitySearchMatch {
  kind: "label" | "iri" | "annotation";
  property?: string;
  propertyLabel?: string;
  value: string;
  ranges: number[][];
}

export interface EntitySearchHit {
  id: string;
  label: string;
  matches: EntitySearchMatch[];
}

export interface EntitySearchAncestor {
  id: string;
  depth: number;
}

export interface EntitySearchResponse {
  success: boolean;
  truncated: boolean;
  warming: boolean;
  groups: Partial<Record<SearchEntityType, EntitySearchHit[]>>;
  classAncestors: EntitySearchAncestor[];
}

export const buildSearchParams = (options: EntitySearchOptions): Record<string, string> => {
  const params: Record<string, string> = {
    q: options.query,
    types: options.types.join(","),
    annotations: String(options.annotations === true),
    regex: String(options.regex === true),
    hideDeprecated: String(options.hideDeprecated === true),
    hideBuiltins: String(options.hideBuiltins === true),
    limit: String(options.limit ?? 50),
    scope: options.scope ?? "active",
  };
  if (options.caseSensitive) {
    params.caseSensitive = "true";
  }
  if (options.wholeWords) {
    params.wholeWords = "true";
  }
  if (options.ignoreWhitespace) {
    params.ignoreWhitespace = "true";
  }
  if (options.searchIris === false) {
    params.searchIris = "false";
  }
  if (options.showAll) {
    params.showAll = "true";
  }
  if (options.userId) {
    params.userId = options.userId;
  }
  if (options.draft) {
    params.draft = "true";
  }
  return params;
};

export const buildSearchKey = (
  tab: string,
  query: string,
  annotations: boolean,
  regex: boolean,
  filters = "",
): string => [tab, query, annotations ? "1" : "0", regex ? "1" : "0", filters].join("|");

export const normalizeSearchResponse = (raw: any): EntitySearchResponse => ({
  success: raw?.success !== false,
  truncated: raw?.truncated === true,
  warming: raw?.warming === true,
  groups: raw?.groups && typeof raw.groups === "object" ? raw.groups : {},
  classAncestors: Array.isArray(raw?.classAncestors) ? raw.classAncestors : [],
});

export const hitIdsOf = (response: EntitySearchResponse, type: SearchEntityType): Set<string> =>
  new Set((response.groups[type] ?? []).map((hit) => hit.id));

export const searchEntities = async (
  projectId: string,
  options: EntitySearchOptions,
  signal?: AbortSignal,
): Promise<EntitySearchResponse> => {
  const raw = await apiClient.get<any>(
    `/api/ontology/search/${encodeURIComponent(projectId)}`,
    buildSearchParams(options),
    signal ? { signal } : undefined,
  );
  return normalizeSearchResponse(raw?.data ?? raw);
};

export interface BranchNode {
  id: string;
  children?: BranchNode[];
}

export const isBranchLoaded = (nodes: BranchNode[], id: string): boolean => {
  for (const node of nodes) {
    if (node.id === id) {
      return !!node.children && node.children.length > 0;
    }
    if (node.children && isBranchLoaded(node.children, id)) {
      return true;
    }
  }
  return false;
};

export const pathToNode = <T extends BranchNode>(nodes: T[], id: string): T[] | null => {
  for (const node of nodes) {
    if (node.id === id) {
      return [node];
    }
    const below = node.children ? pathToNode(node.children as T[], id) : null;
    if (below) {
      return [node, ...below];
    }
  }
  return null;
};

export const ancestorLevels =(ancestors: EntitySearchAncestor[], nodes: BranchNode[]): string[][] => {
  const byDepth = new Map<number, string[]>();
  const seen = new Set<string>();
  for (const ancestor of ancestors) {
    if (!ancestor?.id || seen.has(ancestor.id) || isBranchLoaded(nodes, ancestor.id)) {
      continue;
    }
    seen.add(ancestor.id);
    byDepth.set(ancestor.depth, [...(byDepth.get(ancestor.depth) ?? []), ancestor.id]);
  }
  return Array.from(byDepth.keys())
    .sort((a, b) => a - b)
    .map((depth) => byDepth.get(depth)!);
};

export const loadAncestorLevels = async (
  levels: string[][],
  loadChildren: (id: string) => Promise<void>,
  isCancelled: () => boolean,
): Promise<void> => {
  for (const level of levels) {
    if (isCancelled()) {
      return;
    }
    await Promise.all(level.map((id) => loadChildren(id)));
  }
};

export const SEARCH_TYPE_LABELS: Record<SearchEntityType, string> = {
  CLASS: "Class",
  OBJECT_PROPERTY: "Object property",
  DATA_PROPERTY: "Data property",
  ANNOTATION_PROPERTY: "Annotation property",
  DATATYPE: "Datatype",
  INDIVIDUAL: "Individual",
};

export const SEARCH_GROUP_LABELS: Record<SearchEntityType, string> = {
  CLASS: "Classes",
  OBJECT_PROPERTY: "Object properties",
  DATA_PROPERTY: "Data properties",
  ANNOTATION_PROPERTY: "Annotation properties",
  DATATYPE: "Datatypes",
  INDIVIDUAL: "Individuals",
};

export const ALL_SEARCH_TYPES: SearchEntityType[] = [
  "CLASS",
  "OBJECT_PROPERTY",
  "DATA_PROPERTY",
  "ANNOTATION_PROPERTY",
  "DATATYPE",
  "INDIVIDUAL",
];

export const TAB_BY_SEARCH_TYPE: Record<SearchEntityType, string> = {
  CLASS: "Classes",
  OBJECT_PROPERTY: "ObjectProperties",
  DATA_PROPERTY: "DataProperties",
  ANNOTATION_PROPERTY: "AnnotationProperties",
  DATATYPE: "Datatypes",
  INDIVIDUAL: "Individuals",
};

export interface FlatHit extends EntitySearchHit {
  type: SearchEntityType;
}

export interface HighlightSegment {
  text: string;
  hit: boolean;
}

export const shortIri = (iri: string): string => {
  const cut = Math.max(iri.lastIndexOf("#"), iri.lastIndexOf("/"));
  return cut >= 0 && cut < iri.length - 1 ? iri.slice(cut + 1) : iri;
};

export const highlightSegments = (text: string, ranges: number[][]): HighlightSegment[] => {
  const spans = (ranges ?? [])
    .map(([start, end]) => [Math.max(0, start), Math.min(text.length, end)] as const)
    .filter(([start, end]) => end > start)
    .sort((a, b) => a[0] - b[0]);
  const segments: HighlightSegment[] = [];
  let cursor = 0;
  for (const [start, end] of spans) {
    if (end <= cursor) {
      continue;
    }
    const from = Math.max(start, cursor);
    if (from > cursor) {
      segments.push({ text: text.slice(cursor, from), hit: false });
    }
    segments.push({ text: text.slice(from, end), hit: true });
    cursor = end;
  }
  if (cursor < text.length) {
    segments.push({ text: text.slice(cursor), hit: false });
  }
  return segments.length > 0 ? segments : [{ text, hit: false }];
};

export const describeMatch = (match: EntitySearchMatch): string => {
  if (match.kind === "label") {
    return "label";
  }
  if (match.kind === "iri") {
    return "IRI";
  }
  return match.propertyLabel || (match.property ? shortIri(match.property) : "annotation");
};

export const flattenHits = (
  response: EntitySearchResponse,
  types: SearchEntityType[] = ALL_SEARCH_TYPES,
): FlatHit[] =>
  types.flatMap((type) => (response.groups[type] ?? []).map((hit) => ({ ...hit, type })));

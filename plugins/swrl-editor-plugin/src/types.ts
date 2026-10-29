export interface SwrlRule {
  id: string;
  projectId?: string;
  ruleName: string;
  ruleText: string;
  comment?: string | null;
  category?: string | null;
  enabled: boolean;
  source?: string | null;
  readOnly?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface UnmigratedRule {
  id?: string;
  ruleName: string;
  ruleText?: string;
  error?: string;
}

export interface RulesListResponse {
  rules: SwrlRule[];
  unmigrated?: UnmigratedRule[];
}

export interface RuleWriteErrorBody {
  errors?: string[];
  suggestions?: string[];
  message?: string;
}

export interface ExportCheckResponse {
  disabledRules: { id: string; ruleName: string }[];
  warning?: string;
}

export interface PagedResponse<T> {
  content: T[];
  pageable: {
    pageNumber: number;
    pageSize: number;
  };
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface ValidationResult {
  valid: boolean;
  errorMessage: string | null;
  suggestions?: string[];

  parsedAtoms?: ParsedAtom[];
  usedBuiltIns?: string[];
  usedClasses?: string[];
  usedProperties?: string[];
}

export type AtomType = 
  | 'ClassAtom' 
  | 'DataPropertyAtom' 
  | 'ObjectPropertyAtom' 
  | 'SameIndividualAtom' 
  | 'DifferentIndividualsAtom' 
  | 'BuiltInAtom' 
  | 'DataRangeAtom';

export interface ParsedAtom {
  type: AtomType;
  predicate: string;
  arguments: string[];
}

export interface BuiltInCategory {
  prefix: string;
  name: string;
  description: string;
  builtIns: BuiltInInfo[];
}

export interface BuiltInInfo {
  name: string;
  fullName: string;
  description: string;
  signature: string;
  example?: string;
}

export interface InferredAxiom {
  axiomType: string;
  description: string;
  readable: string;
  category?: 'conclusion' | 'structure';
  subjectIri?: string | null;
  predicateIri?: string | null;
  objectIri?: string | null;
  objectLiteral?: string | null;
  literalDatatypeIri?: string | null;
}

export interface ExecutionResponse {
  success: boolean;
  executionTimeMs: number;
  inferredAxiomsCount: number;
  totalRulesExecuted: number;
  inferredAxioms: InferredAxiom[];
  errorMessage: string | null;
  executedRuleNames?: string[]; // Names of rules that were actually executed
  executionMode?: 'all' | 'selected' | 'test'; // Indicates if all rules or selected rules were executed
  stamp?: { ontologyRevision: string; draftVersion: string; rulesFingerprint: string } | null;
  conclusionCount?: number;
  status?: string;
  statusLabel?: string;
  message?: string;
  limitations?: string[];
  consistencyStatement?: string;
  rounds?: number;
  snapshotId?: string;
  engineVersions?: Record<string, string>;
  structureCount?: number;
  omittedTrivialCount?: number;
  truncated?: boolean;
  runId?: string;
  counts?: { conclusions: number; structure: number; omittedTrivial?: number };
  skippedRules?: Array<string | { ruleName?: string; name?: string; ruleId?: string; reason?: string }>;
}

export interface AxiomsPageResponse {
  axioms: InferredAxiom[];
  total: number;
  offset: number;
  limit: number;
  kind: string;
}

export interface PluginContext {
  projectId: string;
  ontology?: any | null;
}

export interface SQWRLResult {
  columns: string[];
  rows: Record<string, any>[];
  executionTimeMs?: number;
}

export interface SqwrlQueryResponse {
  success: boolean;
  columnNames: string[];
  rows: Record<string, any>[];
  rowCount: number;
  executionTimeMs: number;
  errorMessage?: string;
  totalRows?: number;
  totalRowsExact?: boolean;
  truncated?: boolean;
  rowLimit?: number;
  bytesCapped?: boolean;
  runId?: string;
  storedRows?: number;
  filteredRows?: number;
  pageSize?: number;
  nextOffset?: number;
  pageBytesCapped?: boolean;
}

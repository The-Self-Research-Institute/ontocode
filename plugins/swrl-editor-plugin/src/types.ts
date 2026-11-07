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
  status?: 'ok' | 'caution' | 'unavailable';
  note?: string;
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

export type ExplainAxiomType =
  | 'ClassAssertion'
  | 'SubClassOf'
  | 'ObjectPropertyAssertion'
  | 'DataPropertyAssertion'
  | 'ObjectPropertyDomain'
  | 'ObjectPropertyRange'
  | 'SubObjectPropertyOf'
  | 'InverseObjectProperties'
  | 'EquivalentClasses'
  | 'SameIndividual';

export interface ExplainFact {
  axiomType: ExplainAxiomType;
  subjectIri: string;
  predicateIri?: string | null;
  objectIri?: string | null;
  objectLiteral?: string | null;
  literalDatatypeIri?: string | null;
  objectIris?: string[] | null;
  objectOperator?: 'UNION' | 'INTERSECTION' | null;
  reasoner?: 'HERMIT' | 'ELK';
}

export type ExplainStatus =
  | 'EXPLAINED'
  | 'ASSERTED'
  | 'NOT_ENTAILED'
  | 'UNSUPPORTED'
  | 'TOO_LARGE'
  | 'TIMEOUT'
  | 'CANCELLED'
  | 'FAILED';

export type ExplainJobStatus = 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface ExplainEntity {
  iri: string;
  label: string;
  kind: string;
}

export interface ExplainAxiom {
  axiomType: string;
  role: 'ASSERTED' | 'RULE' | 'CONCLUSION';
  text: string;
  subjectIri: string | null;
  predicateIri: string | null;
  objectIri: string | null;
  objectLiteral: string | null;
  literalDatatypeIri: string | null;
  objectIris: string[] | null;
  objectOperator: 'UNION' | 'INTERSECTION' | null;
  entities: ExplainEntity[];
}

export interface ExplainSubmitResponse {
  success: boolean;
  async: boolean;
  jobId: string;
  status: ExplainJobStatus;
  queuePosition: number;
  estimatedWaitTimeMs: number;
}

export interface ExplainJobResponse {
  success?: boolean;
  jobId: string;
  jobType?: string;
  projectId: string;
  status: ExplainJobStatus;
  queuePosition?: number;
  estimatedWaitTimeMs?: number;
  executionTimeMs?: number;
  error?: string;
  explainStatus?: ExplainStatus;
  message?: string;
  conclusion?: ExplainAxiom;
  justification?: ExplainAxiom[];
  axiomCount?: number;
  minimal?: boolean;
  partial?: boolean;
  timeBudgetHit?: boolean;
  usesRules?: boolean;
  reasoner?: string;
  reasonerRequested?: string;
  reasonerFallback?: boolean;
  elapsedMs?: number;
  loadMs?: number;
  budgetMs?: number;
  ontologyAxiomCount?: number;
  entailmentChecks?: number;
  ontologyRevision?: number;
}

export interface ExplainRunOptions {
  draft?: boolean;
  pollIntervalMs?: number;
  timeoutMs?: number;
  signal?: AbortSignal;
  onUpdate?: (job: ExplainJobResponse) => void;
}

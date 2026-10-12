import type { CodeAssistantAction } from "../components/CodeAssistantPanel";

export interface AssistantSnapshot {
  projectId: string;
  documentPath: string;
  revision: number;
  actionType: CodeAssistantAction;
}

export interface AssistantBudget {
  retrievalCallsRemaining: number;
  maxRetrievalCalls: number;
}

export interface AssistantSession {
  sessionId: string;
  snapshot: AssistantSnapshot;
  budget: AssistantBudget;
  expiresAt: string;
}

export interface ReadContextTarget {
  type: "identifier" | "range" | "statement";
  value: string;
}

export interface ReadContextResultItem {
  source: string;
  range?: unknown;
  text: string;
  kind: string;
}

export interface ToolProvenance {
  revision: number;
  coverage?: "partial" | "complete";
  retrievalAttemptsRemaining?: number;
  tokenBudgetRemaining?: number;
}

export interface ReadContextResult {
  ok: true;
  result: { items: ReadContextResultItem[] };
  provenance: ToolProvenance;
  budget: AssistantBudget;
}

export interface SparqlResult {
  ok: true;
  result: { rows: unknown[]; truncated: boolean; rowCount: number };
  provenance: ToolProvenance;
}

export interface ReasonerResult {
  ok: true;
  result: Record<string, unknown>;
  truncated: boolean;
  provenance: ToolProvenance;
}

export interface SwrlResult {
  ok: true;
  result: Record<string, unknown>;
  provenance: ToolProvenance;
}

export interface FuzzyQueryResult {
  ok: true;
  result: { individuals: unknown[]; count: number };
  provenance: ToolProvenance;
}

export interface ProposedEdit {
  targetPath: string;
  range: unknown;
  originalText: string;
  newText: string;
}

export interface RenameIdentifierOperation {
  type: "rename_identifier";
  targetPath: string;
  targetIdentifier: string;
  replacementIdentifier: string;
}

export interface InferredAxiomInput {
  axiomType: string;
  subjectIri: string;
  predicateIri: string;
  objectIri?: string;
  objectLiteral?: string;
  literalDatatypeIri?: string;
  literalLangTag?: string;
}

export interface AddInferredAxiomsOperation {
  type: "add_inferred_axioms";
  targetPath: string;
  axioms: InferredAxiomInput[];
}

export interface FuzzyMembershipInput {
  entityIri: string;
  classIri: string;
  degree: number;
}

export interface AddFuzzyMembershipOperation {
  type: "add_fuzzy_membership";
  targetPath: string;
  memberships: FuzzyMembershipInput[];
}

export interface ProposedEditGroupInput {
  clientGroupId: string;
  edits?: ProposedEdit[];
  operation?: RenameIdentifierOperation | AddInferredAxiomsOperation | AddFuzzyMembershipOperation;
}

export interface ProposedDiffEntry {
  targetPath: string;
  before: string;
  after: string;
  startLine?: number | null;
  lineCount?: number | null;
}

export interface ProposedCheckResult {
  name: string;
  passed: boolean;
  detail?: string;
  status?: "pending";
}

export interface ProposedEditGroupResult {
  clientGroupId: string;
  serverGroupId: string;
  validation: { passed: boolean; checks: ProposedCheckResult[] };
  diff: ProposedDiffEntry[];
}

export interface ProposeResult {
  ok: true;
  groups: ProposedEditGroupResult[];
}

export interface AppliedRange {
  format: string;
  startLine: number;
  lineCount: number;
}

export interface ApplyResult {
  ok: true;
  applied: true;
  newRevision: number;
  remappedPendingGroups: Array<{ serverGroupId: string; remapped: boolean; stale?: boolean }>;
  appliedRanges?: AppliedRange[];
}

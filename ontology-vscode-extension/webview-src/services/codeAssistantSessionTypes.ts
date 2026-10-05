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

export interface ReadContextResult {
  ok: true;
  result: { items: ReadContextResultItem[] };
  provenance: { revision: number; coverage: "partial" | "complete" };
  budget: AssistantBudget;
}

export interface SparqlResult {
  ok: true;
  result: { rows: unknown[]; truncated: boolean; rowCount: number };
  provenance: { revision: number; coverage?: "partial" | "complete" };
}

export interface ReasonerResult {
  ok: true;
  result: Record<string, unknown>;
  truncated: boolean;
  provenance: { revision: number };
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

export interface ProposedEditGroupInput {
  clientGroupId: string;
  edits?: ProposedEdit[];
  operation?: RenameIdentifierOperation;
}

export interface ProposedDiffEntry {
  targetPath: string;
  before: string;
  after: string;
  startLine?: number | null;
  lineCount?: number | null;
}

export interface ProposedEditGroupResult {
  clientGroupId: string;
  serverGroupId: string;
  validation: { passed: boolean; checks: Array<{ name: string; passed: boolean; detail?: string }> };
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

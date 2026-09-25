import type { CodeAssistantAction } from "./codeAssistantPanelHelpers";
import type { ContextEvent } from "../services/codeAssistantLoop";
import type { ProviderUsage } from "../services/codeAssistantProviders";
import type { AppliedRange, ProposedEditGroupResult } from "../services/codeAssistantSession";
import type { DeadEnd } from "../services/codeAssistantDeadEnd";
import type { ApplyAllRunState, GroupDecision } from "./CodeAssistantReviewGroups";

export type ChatEntry =
  | { id: string; role: "user"; text: string; action: CodeAssistantAction }
  | { id: string; role: "assistant"; kind: "answer"; text: string; contextUsed: ContextEvent[]; usage?: ProviderUsage[] }
  | {
      id: string;
      role: "assistant";
      kind: "review";
      sessionId: string;
      projectId?: string;
      createdAt?: number;
      explanation?: string;
      groups: ProposedEditGroupResult[];
      decisions: Record<string, GroupDecision>;
      errors: Record<string, string>;
      contextUsed: ContextEvent[];
      usage?: ProviderUsage[];
      applyAllRun?: ApplyAllRunState | null;
      applyAllSummary?: string | null;
    }
  | {
      id: string;
      role: "assistant";
      kind: "error";
      text: string;
      deadEnd?: DeadEnd;
      retry?: PromptToRetry;
      retryAt?: number | null;
    };

export interface PromptToRetry {
  text: string;
  action: CodeAssistantAction;
}

export type ReviewEntry = Extract<ChatEntry, { kind: "review" }>;

export interface AppliedChanges {
  texts: string[];
  ranges: AppliedRange[];
}

export function nextEntryId(): string {
  return `ca-entry-${crypto.randomUUID()}`;
}

export function sanitizeEntryForStorage(entry: ChatEntry): ChatEntry {
  if (entry.role !== "assistant" || entry.kind !== "review") return entry;
  const decisions = entry.decisions;
  const hasStuckApplying = Object.values(decisions).some((d) => d === "applying");
  if (!entry.applyAllRun && !hasStuckApplying) return entry;
  return {
    ...entry,
    applyAllRun: null,
    decisions: hasStuckApplying
      ? Object.fromEntries(Object.entries(decisions).map(([id, d]) => [id, d === "applying" ? "pending" : d]))
      : decisions,
  };
}

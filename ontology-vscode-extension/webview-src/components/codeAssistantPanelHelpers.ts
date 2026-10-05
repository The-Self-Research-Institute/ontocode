import { MessageSquare, Pencil, Search, type LucideIcon } from "lucide-react";
import { getGatewayUrl } from "../config/deploymentConfig";
import type { HistoryTurn, LoopStageEvent } from "../services/codeAssistantLoop";
import type { ProviderUsage } from "../services/codeAssistantProviders";

export type CodeAssistantAction = "ask" | "local-edit" | "project-findings";

export const ACTIONS: Array<{ id: CodeAssistantAction; label: string; description: string; icon: LucideIcon }> = [
  {
    id: "ask",
    label: "Ask",
    description: "Ask a question about this document, grounded in its actual content.",
    icon: MessageSquare,
  },
  {
    id: "local-edit",
    label: "Local edit",
    description: "Propose a change scoped to this document, reviewed before anything is applied.",
    icon: Pencil,
  },
  {
    id: "project-findings",
    label: "Project findings",
    description: "Ask a question grounded in the whole project, not just this document.",
    icon: Search,
  },
];

export function getApiBaseUrl(): string {
  return getGatewayUrl();
}

export function buildSystemPrompt(action: CodeAssistantAction, documentPath?: string): string {
  const scope =
    action === "project-findings"
      ? "The user's question may require looking beyond the current document, across the project."
      : `Stay scoped to the current document${documentPath ? ` (${documentPath})` : ""} unless the user's request clearly needs more.`;
  const editable =
    action === "local-edit"
      ? [
          "The user wants a concrete edit. For the exact text and line range of an entity, call read_context with a \"statement\" target (its full IRI or prefixed name), then call propose_edit with grouped, dependent replacements.",
          "To rename an identifier, call propose_rename instead of editing each occurrence yourself.",
          "Insert new statements only between complete statements (after a line ending in \" .\"), never inside one.",
          "Never claim an edit was applied — applying is a separate human-approved step.",
        ].join(" ")
      : "This session cannot propose or apply edits — propose_edit and propose_rename will always be rejected. Answer the question directly. If the user is actually asking for a change, tell them to switch to Local edit mode and ask again there.";
  return [
    "You are an ontology-editing assistant with six tools: read_context, run_sparql (read-only), propose_edit, " +
      "propose_rename, check_consistency and explain_inconsistency.",
    scope,
    editable,
    "If asked whether the ontology is consistent, or to find/fix a logical contradiction, call check_consistency first " +
      "— it's cheap. Only call explain_inconsistency afterward, and only if check_consistency reported consistent: false; " +
      "it's slower (it rebuilds the reasoner every call) and pointless to call on a consistent ontology.",
    "Ground every claim in what read_context, run_sparql, check_consistency or explain_inconsistency actually returned. " +
      "If you don't have enough information, say so instead of guessing.",
  ].join("\n");
}

export interface HistoryEntryLike {
  role: "user" | "assistant";
  kind?: string;
  text?: string;
  groups?: Array<{ serverGroupId: string; diff: Array<{ targetPath: string; before: string; after: string; startLine?: number | null }> }>;
  decisions?: Record<string, string>;
}

const SUMMARY_SNIPPET_CHARS = 120;

function firstLine(text: string): string {
  const line = text.split("\n").find((l) => l.trim().length > 0) ?? "";
  return line.length > SUMMARY_SNIPPET_CHARS ? `${line.slice(0, SUMMARY_SNIPPET_CHARS)}...` : line;
}

export function summarizeReviewForHistory(entry: HistoryEntryLike): string {
  const groups = entry.groups ?? [];
  const parts = groups.map((group, index) => {
    const decision = entry.decisions?.[group.serverGroupId] ?? "pending";
    const edit = group.diff[0];
    const where = edit
      ? `${edit.targetPath}${typeof edit.startLine === "number" ? ` line ${edit.startLine + 1}` : ""}`
      : "no edits";
    const change = edit ? `: "${firstLine(edit.before)}" -> "${firstLine(edit.after)}"` : "";
    return `group ${index + 1} (${where}${change}) is ${decision}`;
  });
  const summary = `I proposed ${groups.length} change group${groups.length === 1 ? "" : "s"} for review. ${parts.join("; ")}.`;
  const applied = groups.filter((group) => entry.decisions?.[group.serverGroupId] === "applied");
  if (applied.length === 0) return summary;
  const shift = applied.reduce(
    (total, group) => total + group.diff.reduce((sum, edit) => sum + countLines(edit.after) - countLines(edit.before), 0),
    0,
  );
  const net = shift === 0 ? "" : ` (net ${shift > 0 ? "+" : ""}${shift} lines)`;
  return `${summary} The document changed after that${net}, so line numbers from before are stale. Call read_context again before proposing more edits.`;
}

function countLines(text: string): number {
  return text.length === 0 ? 0 : text.split("\n").length;
}

export const MAX_HISTORY_TURNS = 30;

export function buildConversationHistory(entries: HistoryEntryLike[]): HistoryTurn[] {
  const turns: HistoryTurn[] = [];
  entries.forEach((entry, index) => {
    if (entry.role === "user") {
      const next = entries[index + 1];
      if (next && next.role === "assistant" && next.kind === "error") return;
      turns.push({ role: "user", text: entry.text ?? "" });
      return;
    }
    if (entry.kind === "answer") turns.push({ role: "assistant", text: entry.text ?? "" });
    if (entry.kind === "review" && (entry.groups?.length ?? 0) > 0) {
      turns.push({ role: "assistant", text: summarizeReviewForHistory(entry) });
    }
  });
  return turns.length > MAX_HISTORY_TURNS ? turns.slice(turns.length - MAX_HISTORY_TURNS) : turns;
}

const CHAT_STORAGE_PREFIX = "ontocode.askAi.chat.";
const MAX_STORED_ENTRIES = 120;

export function chatHistoryStorageKey(projectId: string): string {
  return `${CHAT_STORAGE_PREFIX}${projectId}`;
}

export function loadStoredChatEntries<T>(projectId: string): T[] | null {
  try {
    const raw = window.localStorage.getItem(chatHistoryStorageKey(projectId));
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as T[]) : null;
  } catch {
    return null;
  }
}

export function saveStoredChatEntries(projectId: string, entries: unknown[]): void {
  try {
    const capped = entries.length > MAX_STORED_ENTRIES ? entries.slice(entries.length - MAX_STORED_ENTRIES) : entries;
    window.localStorage.setItem(chatHistoryStorageKey(projectId), JSON.stringify(capped));
  } catch {
  }
}

export function clearStoredChatEntries(projectId: string): void {
  try {
    window.localStorage.removeItem(chatHistoryStorageKey(projectId));
  } catch {
  }
}

const FRIENDLY_TOOL_NAMES: Record<string, string> = {
  check_consistency: "Checking whether the ontology is consistent",
  explain_inconsistency: "Figuring out what's causing the inconsistency",
};

function describeStage(event: LoopStageEvent): string {
  if (event.stage === "calling-provider") return event.detail || "Thinking...";
  if (event.stage === "calling-tool") {
    const friendly = event.detail ? FRIENDLY_TOOL_NAMES[event.detail] : undefined;
    return friendly ? `${friendly}...` : `Running ${event.detail}...`;
  }
  if (event.stage === "tool-result") return `Got a result from ${event.detail}`;
  if (event.stage === "propose") return "Preparing changes for review...";
  return "";
}

export function describeLoopStage(event: LoopStageEvent): string {
  const text = describeStage(event);
  if (!text || !event.step || !event.maxSteps) return text;
  return `Step ${event.step} of ${event.maxSteps} · ${text}`;
}

const STORED_RESULT_CHARS = 400;

export function compactEntryForStorage<T>(entry: T): T {
  const record = entry as unknown as { contextUsed?: Array<Record<string, unknown>> };
  if (!Array.isArray(record.contextUsed)) return entry;
  const contextUsed = record.contextUsed.map((event) => {
    const serialized = typeof event.result === "string" ? event.result : JSON.stringify(event.result ?? null);
    return serialized.length > STORED_RESULT_CHARS
      ? { ...event, result: `${serialized.slice(0, STORED_RESULT_CHARS)}... (trimmed when saved)` }
      : event;
  });
  return { ...(entry as object), contextUsed } as T;
}

export const UNSAVED_CODE_VIEW_MESSAGE =
  "Save or discard your Code View changes first, then ask again so the proposal matches the saved version.";

export const RECOVERY_LOCKED_APPLY_MESSAGE =
  "Applying is paused until the recovery notice above is resolved.";

export interface ApplyBlock {
  message: string;
  shortReason: string;
}

export function resolveApplyBlock(state: { hasUnsavedCodeViewChanges: boolean; recoveryLocked: boolean }): ApplyBlock | null {
  if (state.recoveryLocked) {
    return { message: RECOVERY_LOCKED_APPLY_MESSAGE, shortReason: "the project is locked for recovery" };
  }
  if (state.hasUnsavedCodeViewChanges) {
    return { message: UNSAVED_CODE_VIEW_MESSAGE, shortReason: "there are unsaved Code View changes" };
  }
  return null;
}

const TECHNICAL_ERROR_PATTERN = /Exception|\{[a-zA-Z]+=|com\.[a-z][\w.]*\.|Caused by:|StackTrace|at [\w.$]+\(/;

export function toFriendlyErrorMessage(raw: string): string {
  const looksTechnical = raw.length > 180 || TECHNICAL_ERROR_PATTERN.test(raw);
  if (!looksTechnical) return raw;
  // eslint-disable-next-line no-console
  console.error("[Ask AI] raw error:", raw);
  if (/timed?\s*out|timeout/i.test(raw)) {
    return "The server took too long to respond. Try again in a moment.";
  }
  if (/socket|connection|unreachable|refused/i.test(raw)) {
    return "Couldn't reach a required service on the server. Try again shortly.";
  }
  return "Something went wrong on the server. Try again in a moment.";
}

const USAGE_TOKEN_FIELDS = [
  ["inputTokens", "in"],
  ["outputTokens", "out"],
  ["cacheReadTokens", "cache read"],
  ["cacheWriteTokens", "cache write"],
] as const;

function formatCount(value: number): string {
  return Math.round(value).toLocaleString("en-US");
}

export function formatLatency(ms: number): string {
  const rounded = Math.round(ms);
  return rounded < 1000 ? `${rounded} ms` : `${(Math.round(ms / 100) / 10).toFixed(1)} s`;
}

export function formatUsageLine(usage: Pick<ProviderUsage, "latencyMs" | "inputTokens" | "outputTokens" | "cacheReadTokens" | "cacheWriteTokens">): string {
  const parts: string[] = USAGE_TOKEN_FIELDS.flatMap(([field, label]) => {
    const value = usage[field];
    return typeof value === "number" && Number.isFinite(value) ? [`${formatCount(value)} ${label}`] : [];
  });
  if (typeof usage.latencyMs === "number" && Number.isFinite(usage.latencyMs)) parts.push(formatLatency(usage.latencyMs));
  return parts.join(" · ");
}

export function totalUsage(usage: ProviderUsage[]): Pick<ProviderUsage, "latencyMs" | "inputTokens" | "outputTokens" | "cacheReadTokens" | "cacheWriteTokens"> {
  const total: Pick<ProviderUsage, "latencyMs" | "inputTokens" | "outputTokens" | "cacheReadTokens" | "cacheWriteTokens"> = {
    latencyMs: usage.reduce((sum, u) => sum + (typeof u.latencyMs === "number" && Number.isFinite(u.latencyMs) ? u.latencyMs : 0), 0),
  };
  for (const [field] of USAGE_TOKEN_FIELDS) {
    const values = usage.map((u) => u[field]).filter((v): v is number => typeof v === "number" && Number.isFinite(v));
    if (values.length > 0) total[field] = values.reduce((sum, v) => sum + v, 0);
  }
  return total;
}

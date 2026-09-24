import { MessageSquare, Pencil, Search, type LucideIcon } from "lucide-react";
import { getGatewayUrl, getRemoteApiBaseUrl } from "../config/deploymentConfig";
import { isDesktop } from "../utils/desktop";
import type { HistoryTurn, LoopStageEvent } from "../services/codeAssistantLoop";

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
  return isDesktop() ? getRemoteApiBaseUrl() : getGatewayUrl();
}

export function buildSystemPrompt(action: CodeAssistantAction, documentPath?: string): string {
  const scope =
    action === "project-findings"
      ? "The user's question may require looking beyond the current document, across the project."
      : `Stay scoped to the current document${documentPath ? ` (${documentPath})` : ""} unless the user's request clearly needs more.`;
  const editable =
    action === "local-edit"
      ? "The user wants a concrete edit. Use read_context to ground yourself, then call propose_edit with grouped, dependent replacements. Never claim an edit was applied — applying is a separate human-approved step."
      : "Answer the question directly. Only call propose_edit if the user explicitly asked for a change.";
  return [
    "You are an ontology-editing assistant with three tools: read_context, run_sparql (read-only), and propose_edit.",
    scope,
    editable,
    "Ground every claim in what read_context or run_sparql actually returned. If you don't have enough information, say so instead of guessing.",
  ].join("\n");
}

export interface HistoryEntryLike {
  role: "user" | "assistant";
  kind?: string;
  text?: string;
}

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
  });
  return turns;
}

export function describeLoopStage(event: LoopStageEvent): string {
  if (event.stage === "calling-provider") return event.detail || "Thinking...";
  if (event.stage === "calling-tool") return `Running ${event.detail}...`;
  if (event.stage === "tool-result") return `Got a result from ${event.detail}`;
  if (event.stage === "propose") return "Preparing changes for review...";
  return "";
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

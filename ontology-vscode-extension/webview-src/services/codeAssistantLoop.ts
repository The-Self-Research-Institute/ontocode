import type {
  ToolDefinition,
  ConversationState,
  ToolResultForModel,
  ToolResultProvenance,
  ToolCallRequest,
  HistoryTurn,
  ProviderUsage,
} from "./codeAssistantProviders";
import { startAssistantConversation, requestNextTurn } from "./codeAssistantProviders";

export type { HistoryTurn };
import { validateAgainstSchema } from "./codeAssistantValidation";
import {
  readContext,
  runSparql,
  proposeEditGroups,
  AssistantApiError,
  isDeadEndErrorCode,
  type AssistantErrorCode,
  type AssistantSession,
  type ProposedEditGroupInput,
  type ProposedEdit,
  type ReadContextTarget,
  type ProposeResult,
} from "./codeAssistantSession";

export const READ_CONTEXT_TOOL: ToolDefinition = {
  name: "read_context",
  description:
    "Read definitions, diagnostics, or references for identifiers, statements, or line ranges in the pinned document snapshot.",
  parameters: {
    type: "object",
    required: ["targets", "kind"],
    properties: {
      targets: {
        type: "array",
        items: {
          type: "object",
          required: ["type", "value"],
          properties: {
            type: {
              type: "string",
              enum: ["identifier", "range", "statement"],
              description:
                "\"identifier\" looks up an entity by IRI. \"range\" reads raw lines. " +
                "\"statement\" returns every statement block in which the entity is the subject, each with its exact line range " +
                "(\"<startLine>-<lineCount>\"), which is the easiest way to get the text and range to edit.",
            },
            value: {
              type: "string",
              description:
                "For type \"identifier\": a full IRI. For type \"statement\": a full IRI or a prefixed name such as ex:Pizza. " +
                "For type \"range\": \"<format>:<startLine>-<lineCount>\", e.g. \"turtle:100-50\" for 50 lines starting at line 100. " +
                "format is one of turtle, rdfxml, manchester, functional.",
            },
          },
        },
      },
      kind: {
        type: "string",
        enum: ["definitions", "diagnostics", "references"],
        description:
          "\"definitions\" returns the declarations and axioms of the targets. " +
          "\"diagnostics\" returns the real parse errors and warnings the document currently has, each item's text starting with " +
          "\"ERROR:\" or \"WARNING:\" and carrying the line range it applies to. \"references\" returns where the targets are used.",
      },
    },
  },
};

export const RUN_SPARQL_TOOL: ToolDefinition = {
  name: "run_sparql",
  description: "Run a single read-only SPARQL SELECT query against the pinned snapshot. Capped in rows, bytes, and time.",
  parameters: {
    type: "object",
    required: ["query"],
    properties: {
      query: { type: "string" },
    },
  },
};

export const PROPOSE_EDIT_TOOL: ToolDefinition = {
  name: "propose_edit",
  description:
    "Propose one or more grouped, dependent edits for human review. Nothing is applied until the user approves a group. " +
    "To rename an identifier, use propose_rename instead of writing the edits by hand: it finds every occurrence for you.",
  parameters: {
    type: "object",
    required: ["groups"],
    properties: {
      groups: {
        type: "array",
        items: {
          type: "object",
          required: ["edits"],
          properties: {
            edits: {
              type: "array",
              items: {
                type: "object",
                required: ["targetPath", "range", "originalText", "newText"],
                properties: {
                  targetPath: {
                    type: "string",
                    description:
                      "The serialization format this edit is written in: one of turtle, rdfxml, owlxml, manchester, functional.",
                  },
                  range: {
                    type: "object",
                    required: ["startLine", "lineCount"],
                    description:
                      "The 0-indexed line range this edit replaces in the document, from a prior read_context range read. " +
                      "For a pure insertion with nothing to replace, set lineCount to 0 and originalText to an empty string.",
                    properties: {
                      startLine: { type: "integer", description: "0-indexed line number where this edit starts." },
                      lineCount: { type: "integer", description: "Number of original lines this edit replaces, starting at startLine." },
                    },
                  },
                  originalText: { type: "string" },
                  newText: { type: "string" },
                },
              },
            },
          },
        },
      },
    },
  },
};

export const PROPOSE_RENAME_TOOL: ToolDefinition = {
  name: "propose_rename",
  description:
    "Propose renaming one identifier everywhere it occurs in the document, for human review. The server finds and rewrites every " +
    "occurrence and validates the result, so do not read or list the occurrences first. Call it alone in its turn. " +
    "Nothing is applied until the user approves it.",
  parameters: {
    type: "object",
    required: ["targetPath", "targetIdentifier", "replacementIdentifier"],
    properties: {
      targetPath: {
        type: "string",
        description: "The serialization format to rename in: one of turtle, rdfxml, owlxml, manchester, functional.",
      },
      targetIdentifier: {
        type: "string",
        description: "The identifier to rename, as a full IRI or a prefixed name exactly as it appears in the document.",
      },
      replacementIdentifier: {
        type: "string",
        description: "The new identifier, in the same form (full IRI or prefixed name) as targetIdentifier.",
      },
    },
  },
};

export const ASSISTANT_TOOLS: ToolDefinition[] = [READ_CONTEXT_TOOL, RUN_SPARQL_TOOL, PROPOSE_EDIT_TOOL, PROPOSE_RENAME_TOOL];

const PROPOSAL_TOOLS = new Set([PROPOSE_EDIT_TOOL.name, PROPOSE_RENAME_TOOL.name]);

const MAX_LOOP_ITERATIONS = 12;
const MAX_CALLS_PER_TURN = 8;

export interface LoopContext {
  apiBaseUrl: string;
  token: string | undefined;
  session: AssistantSession;
}

export type LoopOutcome =
  | { kind: "answer"; text: string }
  | { kind: "propose"; result: ProposeResult }
  | { kind: "stopped"; reason: string; errorCode?: AssistantErrorCode };

export interface LoopStageEvent {
  stage: "calling-provider" | "calling-tool" | "tool-result" | "answer" | "propose" | "stopped";
  detail?: string;
}

export interface ContextEvent {
  tool: string;
  args: Record<string, unknown>;
  result: unknown;
  isError: boolean;
}

function newClientGroupId(): string {
  return `grp_${Math.random().toString(36).slice(2, 10)}`;
}

function describeToolFailure(result: unknown): string {
  if (result && typeof result === "object") {
    const r = result as Record<string, unknown>;
    if (typeof r.message === "string" && r.message.trim()) return r.message;
    if (typeof r.error === "string" && r.error.trim()) {
      const details = Array.isArray(r.details) ? r.details.join("; ") : "";
      return details ? `${r.error}: ${details}` : r.error;
    }
  }
  return "unknown reason";
}

interface DispatchOutcome {
  result: unknown;
  isError: boolean;
  proposeResult?: ProposeResult;
  errorCode?: AssistantErrorCode;
  errorMessage?: string;
  revision?: number;
}

const PROVENANCE_REASON_CHARS = 160;

function shorten(text: string, max: number = PROVENANCE_REASON_CHARS): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > max ? `${flat.slice(0, max)}...` : flat;
}

function rangeTargetParts(value: string): { format?: string; range?: string } {
  const match = /^([a-z]+):(\d+-\d+)$/i.exec(value.trim());
  return match ? { format: match[1].toLowerCase(), range: match[2] } : {};
}

export function buildToolProvenance(
  session: AssistantSession,
  name: string,
  args: Record<string, unknown>,
  revision: number | undefined,
  step: number,
): ToolResultProvenance {
  const base = { tool: name, revision: typeof revision === "number" ? revision : session.snapshot.revision, step };
  if (name === "read_context") {
    const targets = (Array.isArray(args.targets) ? args.targets : []).map((t) => {
      const rec = (t ?? {}) as Record<string, unknown>;
      return { type: String(rec.type ?? ""), value: String(rec.value ?? "") };
    });
    const rangeParts = targets.filter((t) => t.type === "range").map((t) => rangeTargetParts(t.value));
    const format = rangeParts.find((p) => p.format)?.format;
    const ranges = rangeParts.map((p) => p.range).filter((r): r is string => Boolean(r));
    const kind = typeof args.kind === "string" ? args.kind : "definitions";
    const reason = shorten(`${kind} for ${targets.map((t) => `${t.type} ${t.value}`).join(", ") || "no targets"}`);
    return {
      ...base,
      ...(format ? { format } : { targetPath: session.snapshot.documentPath }),
      ...(ranges.length > 0 ? { range: ranges.join(",") } : {}),
      reason,
    };
  }
  if (name === "run_sparql") {
    return { ...base, format: "sparql", reason: shorten(`query: ${String(args.query ?? "")}`) };
  }
  return { ...base, targetPath: session.snapshot.documentPath, reason: shorten(`${name} ${JSON.stringify(args ?? {})}`) };
}

async function dispatchToolCall(
  ctx: LoopContext,
  name: string,
  args: Record<string, unknown>,
  signal?: AbortSignal,
): Promise<DispatchOutcome> {
  const tool = ASSISTANT_TOOLS.find((t) => t.name === name);
  if (!tool) {
    return { result: { error: `Unknown tool "${name}".` }, isError: true };
  }

  const validation = validateAgainstSchema(tool.parameters, args);
  if (!validation.valid) {
    return { result: { error: "Invalid arguments", details: validation.errors }, isError: true };
  }

  try {
    if (name === "read_context") {
      const targetsRaw = Array.isArray(args.targets) ? args.targets : [];
      const targets = targetsRaw.map((t) => {
        const rec = t as Record<string, unknown>;
        const type: ReadContextTarget["type"] = rec.type === "range" || rec.type === "statement" ? rec.type : "identifier";
        return { type, value: String(rec.value ?? "") };
      });
      const kind = args.kind === "diagnostics" || args.kind === "references" ? args.kind : "definitions";
      const res = await readContext(ctx.apiBaseUrl, ctx.token, ctx.session.sessionId, { targets, kind }, signal);
      return { result: res.result, isError: false, revision: res.provenance?.revision };
    }
    if (name === "run_sparql") {
      const res = await runSparql(ctx.apiBaseUrl, ctx.token, ctx.session.sessionId, String(args.query ?? ""), signal);
      return { result: res.result, isError: false, revision: res.provenance?.revision };
    }
    if (name === "propose_edit") {
      const groupsRaw = Array.isArray(args.groups) ? args.groups : [];
      const groups: ProposedEditGroupInput[] = groupsRaw.map((g) => {
        const rec = g as Record<string, unknown>;
        const editsRaw = Array.isArray(rec.edits) ? rec.edits : [];
        const edits: ProposedEdit[] = editsRaw.map((e) => {
          const editRec = e as Record<string, unknown>;
          return {
            targetPath: String(editRec.targetPath ?? ""),
            range: editRec.range,
            originalText: String(editRec.originalText ?? ""),
            newText: String(editRec.newText ?? ""),
          };
        });
        return { clientGroupId: newClientGroupId(), edits };
      });
      const res = await proposeEditGroups(ctx.apiBaseUrl, ctx.token, ctx.session.sessionId, groups, signal);
      return { result: { groupCount: res.groups.length }, isError: false, proposeResult: res };
    }
    if (name === "propose_rename") {
      const targetPath = String(args.targetPath ?? "").trim();
      const targetIdentifier = String(args.targetIdentifier ?? "").trim();
      const replacementIdentifier = String(args.replacementIdentifier ?? "").trim();
      if (!targetPath || !targetIdentifier || !replacementIdentifier) {
        return { result: { error: "targetPath, targetIdentifier and replacementIdentifier must all be non-empty." }, isError: true };
      }
      if (targetIdentifier === replacementIdentifier) {
        return { result: { error: "replacementIdentifier is the same as targetIdentifier, so there is nothing to rename." }, isError: true };
      }
      const group: ProposedEditGroupInput = {
        clientGroupId: newClientGroupId(),
        operation: { type: "rename_identifier", targetPath, targetIdentifier, replacementIdentifier },
      };
      const res = await proposeEditGroups(ctx.apiBaseUrl, ctx.token, ctx.session.sessionId, [group], signal);
      return { result: { groupCount: res.groups.length }, isError: false, proposeResult: res };
    }
    return { result: { error: `Tool "${name}" has no dispatcher.` }, isError: true };
  } catch (e) {
    if (e instanceof AssistantApiError) {
      return {
        result: { errorCode: e.errorCode, message: e.message },
        isError: true,
        errorCode: e.errorCode,
        errorMessage: e.message,
      };
    }
    return { result: { message: e instanceof Error ? e.message : "Unknown error calling the tool endpoint." }, isError: true };
  }
}

export async function runAssistantLoop(
  ctx: LoopContext,
  systemPrompt: string,
  userMessage: string,
  onStage: (event: LoopStageEvent) => void,
  signal?: AbortSignal,
  history: HistoryTurn[] = [],
  onContext?: (event: ContextEvent) => void,
  onUsage?: (usage: ProviderUsage) => void,
): Promise<LoopOutcome> {
  let conversation: ConversationState = await startAssistantConversation(systemPrompt, userMessage, history);

  for (let i = 0; i < MAX_LOOP_ITERATIONS; i++) {
    const step = i + 1;
    const resultFor = (call: ToolCallRequest, result: unknown, isError: boolean, revision?: number): ToolResultForModel => ({
      toolCallId: call.toolCallId,
      name: call.name,
      result,
      isError,
      provenance: buildToolProvenance(ctx.session, call.name, call.args, revision, step),
    });
    onStage({ stage: "calling-provider" });
    const { turn, advance } = await requestNextTurn(conversation, ASSISTANT_TOOLS, signal, (attempt, maxAttempts, status) => {
      onStage({ stage: "calling-provider", detail: `Provider busy (HTTP ${status}) — retrying ${attempt}/${maxAttempts}...` });
    });

    if (turn.kind === "answer") {
      onStage({ stage: "answer" });
      return { kind: "answer", text: turn.text };
    }

    const proposalNames = [...new Set(turn.calls.map((c) => c.name).filter((n) => PROPOSAL_TOOLS.has(n)))];
    const hasPropose = proposalNames.length > 0;
    if (hasPropose && turn.calls.length > 1) {
      const label = proposalNames.join(" and ");
      onStage({ stage: "stopped", detail: `${label} mixed with other calls` });
      const results: ToolResultForModel[] = turn.calls.map((c) =>
        resultFor(
          c,
          { error: `${label} must be the only tool call in a turn. Call it alone once you're ready to propose changes.` },
          true,
        ),
      );
      conversation = advance(results);
      continue;
    }

    if (hasPropose) {
      const proposeCall = turn.calls[0];
      onStage({ stage: "calling-tool", detail: proposeCall.name });
      const outcome = await dispatchToolCall(ctx, proposeCall.name, proposeCall.args, signal);
      if (outcome.isError || !outcome.proposeResult) {
        const detail = describeToolFailure(outcome.result);
        onStage({ stage: "stopped", detail: `${proposeCall.name} failed` });
        if (isDeadEndErrorCode(outcome.errorCode)) {
          return { kind: "stopped", reason: detail, errorCode: outcome.errorCode };
        }
        return { kind: "stopped", reason: `The proposed edit couldn't be applied: ${detail}`, errorCode: outcome.errorCode };
      }
      onStage({ stage: "propose" });
      return { kind: "propose", result: outcome.proposeResult };
    }

    if (turn.calls.length > MAX_CALLS_PER_TURN) {
      onStage({ stage: "stopped", detail: `too many tool calls in one turn (${turn.calls.length})` });
      const results: ToolResultForModel[] = turn.calls.map((c) =>
        resultFor(c, { error: `Too many tool calls in one turn (${turn.calls.length}). Call at most ${MAX_CALLS_PER_TURN} tools per turn.` }, true),
      );
      conversation = advance(results);
      continue;
    }

    onStage({ stage: "calling-tool", detail: turn.calls.map((c) => c.name).join(", ") });
    const outcomes = await Promise.all(turn.calls.map((call) => dispatchToolCall(ctx, call.name, call.args, signal)));
    onStage({ stage: "tool-result", detail: turn.calls.map((c) => c.name).join(", ") });
    turn.calls.forEach((call, idx) => {
      onContext?.({ tool: call.name, args: call.args, result: outcomes[idx].result, isError: outcomes[idx].isError });
    });
    const deadEnd = outcomes.find((o) => isDeadEndErrorCode(o.errorCode));
    if (deadEnd) {
      onStage({ stage: "stopped", detail: deadEnd.errorCode });
      return {
        kind: "stopped",
        reason: deadEnd.errorMessage ?? describeToolFailure(deadEnd.result),
        errorCode: deadEnd.errorCode,
      };
    }
    const results: ToolResultForModel[] = turn.calls.map((call, idx) =>
      resultFor(call, outcomes[idx].result, outcomes[idx].isError, outcomes[idx].revision),
    );

    conversation = advance(results);
  }

  onStage({ stage: "stopped", detail: "max iterations" });
  return { kind: "stopped", reason: "The assistant took too many steps without reaching an answer or a proposal." };
}

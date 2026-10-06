import { coerceJsonStrings, validateAgainstSchema } from "./codeAssistantValidation";
import type { ProviderConfig } from "./codeAssistantProviderConfig";
import { ASSISTANT_TOOLS } from "./codeAssistantLoopTools";
import {
  readContext,
  runSparql,
  checkConsistency,
  explainInconsistency,
  addSwrlRule,
  runSwrlRule,
  runFuzzyQuery,
  proposeEditGroups,
  AssistantApiError,
  type AssistantErrorCode,
  type AssistantSession,
  type ProposedEditGroupInput,
  type ProposedEdit,
  type ReadContextTarget,
  type ProposeResult,
  type InferredAxiomInput,
  type FuzzyMembershipInput,
} from "./codeAssistantSession";

export interface LoopContext {
  apiBaseUrl: string;
  token: string | undefined;
  session: AssistantSession;
  providerConfig?: ProviderConfig;
}

export interface DispatchBudget {
  retrievalAttemptsRemaining: number;
  tokenBudgetRemaining: number;
}

export interface DispatchOutcome {
  result: unknown;
  isError: boolean;
  proposeResult?: ProposeResult;
  errorCode?: AssistantErrorCode;
  errorMessage?: string;
  retryAfterSeconds?: number;
  revision?: number;
  budget?: DispatchBudget;
}

function provenanceBudget(provenance?: { retrievalAttemptsRemaining?: number; tokenBudgetRemaining?: number }): DispatchBudget | undefined {
  if (!provenance || provenance.retrievalAttemptsRemaining == null || provenance.tokenBudgetRemaining == null) {
    return undefined;
  }
  return {
    retrievalAttemptsRemaining: provenance.retrievalAttemptsRemaining,
    tokenBudgetRemaining: provenance.tokenBudgetRemaining,
  };
}

/** Both fields only ever decrease during a session, so the lowest value seen across a
 * concurrently-dispatched batch is the true state after every call in it has landed,
 * regardless of which call happened to finish last. */
export function lowestBudget(outcomes: DispatchOutcome[]): DispatchBudget | undefined {
  return outcomes.reduce<DispatchBudget | undefined>((lowest, o) => {
    if (!o.budget) return lowest;
    if (!lowest) return o.budget;
    return {
      retrievalAttemptsRemaining: Math.min(lowest.retrievalAttemptsRemaining, o.budget.retrievalAttemptsRemaining),
      tokenBudgetRemaining: Math.min(lowest.tokenBudgetRemaining, o.budget.tokenBudgetRemaining),
    };
  }, undefined);
}

function newClientGroupId(): string {
  return `grp_${Math.random().toString(36).slice(2, 10)}`;
}

export function describeToolFailure(result: unknown): string {
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

async function dispatchReadContext(ctx: LoopContext, args: Record<string, unknown>, signal?: AbortSignal): Promise<DispatchOutcome> {
  const targetsRaw = Array.isArray(args.targets) ? args.targets : [];
  const targets = targetsRaw.map((t) => {
    const rec = t as Record<string, unknown>;
    const type: ReadContextTarget["type"] = rec.type === "range" || rec.type === "statement" ? rec.type : "identifier";
    return { type, value: String(rec.value ?? "") };
  });
  const kind = args.kind === "diagnostics" || args.kind === "references" ? args.kind : "definitions";
  const res = await readContext(ctx.apiBaseUrl, ctx.token, ctx.session.sessionId, { targets, kind }, signal);
  return { result: res.result, isError: false, revision: res.provenance?.revision, budget: provenanceBudget(res.provenance) };
}

async function dispatchRunSparql(ctx: LoopContext, args: Record<string, unknown>, signal?: AbortSignal): Promise<DispatchOutcome> {
  const res = await runSparql(ctx.apiBaseUrl, ctx.token, ctx.session.sessionId, String(args.query ?? ""), signal);
  return { result: res.result, isError: false, revision: res.provenance?.revision, budget: provenanceBudget(res.provenance) };
}

async function dispatchCheckConsistency(ctx: LoopContext, _args: Record<string, unknown>, signal?: AbortSignal): Promise<DispatchOutcome> {
  const res = await checkConsistency(ctx.apiBaseUrl, ctx.token, ctx.session.sessionId, signal);
  return { result: res.result, isError: false, revision: res.provenance?.revision, budget: provenanceBudget(res.provenance) };
}

async function dispatchExplainInconsistency(ctx: LoopContext, _args: Record<string, unknown>, signal?: AbortSignal): Promise<DispatchOutcome> {
  const res = await explainInconsistency(ctx.apiBaseUrl, ctx.token, ctx.session.sessionId, signal);
  return { result: res.result, isError: false, revision: res.provenance?.revision, budget: provenanceBudget(res.provenance) };
}

async function dispatchAddSwrlRule(ctx: LoopContext, args: Record<string, unknown>, signal?: AbortSignal): Promise<DispatchOutcome> {
  const ruleName = String(args.ruleName ?? "");
  const ruleText = String(args.ruleText ?? "");
  const res = await addSwrlRule(ctx.apiBaseUrl, ctx.token, ctx.session.sessionId, ruleName, ruleText, signal);
  return { result: res.result, isError: false, revision: res.provenance?.revision, budget: provenanceBudget(res.provenance) };
}

async function dispatchRunSwrlRule(ctx: LoopContext, _args: Record<string, unknown>, signal?: AbortSignal): Promise<DispatchOutcome> {
  const res = await runSwrlRule(ctx.apiBaseUrl, ctx.token, ctx.session.sessionId, signal);
  return { result: res.result, isError: false, revision: res.provenance?.revision, budget: provenanceBudget(res.provenance) };
}

async function dispatchRunFuzzyQuery(ctx: LoopContext, args: Record<string, unknown>, signal?: AbortSignal): Promise<DispatchOutcome> {
  const res = await runFuzzyQuery(ctx.apiBaseUrl, ctx.token, ctx.session.sessionId, String(args.query ?? ""), signal);
  return { result: res.result, isError: false, revision: res.provenance?.revision, budget: provenanceBudget(res.provenance) };
}

async function submitGroups(ctx: LoopContext, groups: ProposedEditGroupInput[], signal?: AbortSignal): Promise<DispatchOutcome> {
  const res = await proposeEditGroups(ctx.apiBaseUrl, ctx.token, ctx.session.sessionId, groups, signal);
  return { result: { groupCount: res.groups.length }, isError: false, proposeResult: res };
}

async function dispatchProposeEdit(ctx: LoopContext, args: Record<string, unknown>, signal?: AbortSignal): Promise<DispatchOutcome> {
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
  return submitGroups(ctx, groups, signal);
}

async function dispatchProposeRename(ctx: LoopContext, args: Record<string, unknown>, signal?: AbortSignal): Promise<DispatchOutcome> {
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
  return submitGroups(ctx, [group], signal);
}

async function dispatchAddInferredAxioms(ctx: LoopContext, args: Record<string, unknown>, signal?: AbortSignal): Promise<DispatchOutcome> {
  const targetPath = String(args.targetPath ?? "").trim();
  const axiomsRaw = Array.isArray(args.axioms) ? args.axioms : [];
  if (!targetPath || axiomsRaw.length === 0) {
    return { result: { error: "targetPath and a non-empty axioms array are required." }, isError: true };
  }
  const axioms: InferredAxiomInput[] = axiomsRaw.map((a) => {
    const rec = a as Record<string, unknown>;
    return {
      axiomType: String(rec.axiomType ?? ""),
      subjectIri: String(rec.subjectIri ?? ""),
      predicateIri: String(rec.predicateIri ?? ""),
      objectIri: rec.objectIri == null ? undefined : String(rec.objectIri),
      objectLiteral: rec.objectLiteral == null ? undefined : String(rec.objectLiteral),
      literalDatatypeIri: rec.literalDatatypeIri == null ? undefined : String(rec.literalDatatypeIri),
      literalLangTag: rec.literalLangTag == null ? undefined : String(rec.literalLangTag),
    };
  });
  const group: ProposedEditGroupInput = {
    clientGroupId: newClientGroupId(),
    operation: { type: "add_inferred_axioms", targetPath, axioms },
  };
  return submitGroups(ctx, [group], signal);
}

async function dispatchAddFuzzyMembership(ctx: LoopContext, args: Record<string, unknown>, signal?: AbortSignal): Promise<DispatchOutcome> {
  const targetPath = String(args.targetPath ?? "").trim();
  const membershipsRaw = Array.isArray(args.memberships) ? args.memberships : [];
  if (!targetPath || membershipsRaw.length === 0) {
    return { result: { error: "targetPath and a non-empty memberships array are required." }, isError: true };
  }
  const memberships: FuzzyMembershipInput[] = membershipsRaw.map((m) => {
    const rec = m as Record<string, unknown>;
    return {
      entityIri: String(rec.entityIri ?? ""),
      classIri: String(rec.classIri ?? ""),
      degree: Number(rec.degree),
    };
  });
  const group: ProposedEditGroupInput = {
    clientGroupId: newClientGroupId(),
    operation: { type: "add_fuzzy_membership", targetPath, memberships },
  };
  return submitGroups(ctx, [group], signal);
}

type ToolDispatcher = (ctx: LoopContext, args: Record<string, unknown>, signal?: AbortSignal) => Promise<DispatchOutcome>;

const DISPATCHERS: Record<string, ToolDispatcher> = {
  read_context: dispatchReadContext,
  run_sparql: dispatchRunSparql,
  propose_edit: dispatchProposeEdit,
  propose_rename: dispatchProposeRename,
  check_consistency: dispatchCheckConsistency,
  explain_inconsistency: dispatchExplainInconsistency,
  add_swrl_rule: dispatchAddSwrlRule,
  run_swrl_rule: dispatchRunSwrlRule,
  run_fuzzy_query: dispatchRunFuzzyQuery,
  add_inferred_axioms: dispatchAddInferredAxioms,
  add_fuzzy_membership: dispatchAddFuzzyMembership,
};

function toolErrorOutcome(e: unknown): DispatchOutcome {
  if (e instanceof AssistantApiError) {
    return {
      result: { errorCode: e.errorCode, message: e.message },
      isError: true,
      errorCode: e.errorCode,
      errorMessage: e.message,
      retryAfterSeconds: e.retryAfterSeconds,
    };
  }
  return { result: { message: e instanceof Error ? e.message : "Unknown error calling the tool endpoint." }, isError: true };
}

export async function dispatchToolCall(
  ctx: LoopContext,
  name: string,
  args: Record<string, unknown>,
  signal?: AbortSignal,
): Promise<DispatchOutcome> {
  const tool = ASSISTANT_TOOLS.find((t) => t.name === name);
  if (!tool) {
    return { result: { error: `Unknown tool "${name}".` }, isError: true };
  }

  const coercedArgs = coerceJsonStrings(tool.parameters, args) as Record<string, unknown>;
  const validation = validateAgainstSchema(tool.parameters, coercedArgs);
  if (!validation.valid) {
    return { result: { error: "Invalid arguments", details: validation.errors }, isError: true };
  }

  const dispatcher = Object.prototype.hasOwnProperty.call(DISPATCHERS, name) ? DISPATCHERS[name] : undefined;
  if (!dispatcher) return { result: { error: `Tool "${name}" has no dispatcher.` }, isError: true };
  try {
    return await dispatcher(ctx, coercedArgs, signal);
  } catch (e) {
    return toolErrorOutcome(e);
  }
}

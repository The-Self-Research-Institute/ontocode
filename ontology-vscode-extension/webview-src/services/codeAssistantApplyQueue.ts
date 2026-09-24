export type GroupDecision =
  | "pending"
  | "applying"
  | "applied"
  | "skipped"
  | "failed"
  | "stale"
  | "conflict"
  | "recovery";

export type ApplyFailureKind = "stale" | "conflict" | "recovery" | "locked" | "rejected" | "error";

export interface ApplyFailure {
  kind: ApplyFailureKind;
  decision: GroupDecision;
  message: string;
  reason: string;
  checkRecovery: boolean;
}

export interface ReviewGroupLike {
  serverGroupId: string;
  validation: { passed: boolean };
}

export interface RemapLike {
  serverGroupId: string;
  remapped: boolean;
  stale?: boolean;
  status?: string;
}

export type ApplyOneResult = { ok: true } | { ok: false; failure: ApplyFailure };

export type ApplyAllStep =
  | { kind: "apply"; serverGroupId: string; index: number }
  | { kind: "stop"; stop: "cancelled" | "blocked" }
  | { kind: "done" };

export interface ApplyAllProgress {
  serverGroupId: string;
  position: number;
  total: number;
}

export interface ApplyAllHooks {
  readDecisions: () => Record<string, GroupDecision>;
  applyOne: (serverGroupId: string) => Promise<ApplyOneResult>;
  isCancelRequested: () => boolean;
  blockedReason: () => string | null;
  onProgress?: (progress: ApplyAllProgress) => void;
}

export interface ApplyAllReport {
  total: number;
  applied: string[];
  failed: { serverGroupId: string; failure: ApplyFailure } | null;
  stale: string[];
  skipped: string[];
  notAttempted: string[];
  stoppedBy: "cancelled" | "blocked" | null;
  blockedReason: string | null;
}

const SHORT_REASON_LIMIT = 80;

function shortReason(message: string): string {
  const trimmed = message.trim().replace(/\.+$/, "");
  if (!trimmed) return "unexpected error";
  return trimmed.length <= SHORT_REASON_LIMIT ? trimmed : "unexpected error";
}

export function classifyApplyFailure(errorCode: string | undefined, status: number | undefined, message: string): ApplyFailure {
  if (errorCode === "RECOVERY_REQUIRED") {
    return { kind: "recovery", decision: "recovery", message, reason: "it failed partway and the project needs checking", checkRecovery: true };
  }
  if (errorCode === "PROJECT_RECOVERY_LOCKED" || status === 423) {
    return {
      kind: "locked",
      decision: "pending",
      message: "The project is locked until an unfinished change is checked.",
      reason: "the project is locked for recovery",
      checkRecovery: true,
    };
  }
  if (errorCode === "STALE_GROUP") {
    return { kind: "stale", decision: "stale", message, reason: "another change made it stale", checkRecovery: false };
  }
  if (errorCode === "CONFLICT") {
    return { kind: "conflict", decision: "conflict", message, reason: "the document changed since it was checked", checkRecovery: false };
  }
  if (errorCode === "VALIDATION_FAILED") {
    return { kind: "rejected", decision: "failed", message, reason: "the server can't apply it", checkRecovery: false };
  }
  return { kind: "error", decision: "pending", message, reason: shortReason(message), checkRecovery: false };
}

export function applyRemapResult(
  decisions: Record<string, GroupDecision>,
  appliedGroupId: string | null,
  remapped: RemapLike[],
): Record<string, GroupDecision> {
  const next: Record<string, GroupDecision> = { ...decisions };
  if (appliedGroupId !== null) next[appliedGroupId] = "applied";
  for (const remap of remapped) {
    if (isRemapStale(remap) && next[remap.serverGroupId] === "pending") next[remap.serverGroupId] = "stale";
  }
  return next;
}

export function isRemapStale(remap: RemapLike): boolean {
  return remap.stale === true || (typeof remap.status === "string" && remap.status.toUpperCase() === "STALE");
}

export function buildApplyAllQueue(groups: ReviewGroupLike[], decisions: Record<string, GroupDecision>): string[] {
  return groups
    .filter((g) => g.validation.passed && (decisions[g.serverGroupId] ?? "pending") === "pending")
    .map((g) => g.serverGroupId);
}

export function nextApplyAllStep(
  queue: string[],
  fromIndex: number,
  decisions: Record<string, GroupDecision>,
  control: { cancelRequested: boolean; blocked: boolean },
): ApplyAllStep {
  const nextIndex = queue.findIndex((id, i) => i >= fromIndex && (decisions[id] ?? "pending") === "pending");
  if (nextIndex < 0) return { kind: "done" };
  if (control.cancelRequested) return { kind: "stop", stop: "cancelled" };
  if (control.blocked) return { kind: "stop", stop: "blocked" };
  return { kind: "apply", serverGroupId: queue[nextIndex], index: nextIndex };
}

async function attempt(hooks: ApplyAllHooks, serverGroupId: string): Promise<ApplyOneResult> {
  try {
    return await hooks.applyOne(serverGroupId);
  } catch (e) {
    const message = e instanceof Error && e.message ? e.message : "Apply failed unexpectedly.";
    return { ok: false, failure: classifyApplyFailure(undefined, undefined, message) };
  }
}

export async function runApplyAll(queue: string[], hooks: ApplyAllHooks): Promise<ApplyAllReport> {
  let cursor = 0;
  let failed: ApplyAllReport["failed"] = null;
  let stoppedBy: ApplyAllReport["stoppedBy"] = null;
  let blockedReason: string | null = null;

  for (;;) {
    const blocked = hooks.blockedReason();
    const step = nextApplyAllStep(queue, cursor, hooks.readDecisions(), {
      cancelRequested: hooks.isCancelRequested(),
      blocked: blocked !== null,
    });
    if (step.kind === "done") break;
    if (step.kind === "stop") {
      stoppedBy = step.stop;
      blockedReason = step.stop === "blocked" ? blocked : null;
      break;
    }
    hooks.onProgress?.({ serverGroupId: step.serverGroupId, position: step.index + 1, total: queue.length });
    const result = await attempt(hooks, step.serverGroupId);
    cursor = step.index + 1;
    if (result.ok === false) {
      failed = { serverGroupId: step.serverGroupId, failure: result.failure };
      break;
    }
  }

  const finalDecisions = hooks.readDecisions();
  const report: ApplyAllReport = {
    total: queue.length,
    applied: [],
    failed,
    stale: [],
    skipped: [],
    notAttempted: [],
    stoppedBy,
    blockedReason,
  };
  for (const id of queue) {
    if (failed && failed.serverGroupId === id) continue;
    const decision = finalDecisions[id] ?? "pending";
    if (decision === "applied") report.applied.push(id);
    else if (decision === "stale") report.stale.push(id);
    else if (decision === "skipped") report.skipped.push(id);
    else report.notAttempted.push(id);
  }
  return report;
}

export function groupLabel(groups: ReviewGroupLike[], serverGroupId: string): string {
  const index = groups.findIndex((g) => g.serverGroupId === serverGroupId);
  return index >= 0 ? `group ${index + 1}` : "a group";
}

export function formatApplyAllSummary(report: ApplyAllReport, labelFor: (serverGroupId: string) => string): string {
  const parts = [`Applied ${report.applied.length}`];
  if (report.failed) parts.push(`Failed: ${labelFor(report.failed.serverGroupId)} (${report.failed.failure.reason})`);
  if (report.notAttempted.length > 0) parts.push(`Not attempted: ${report.notAttempted.length}`);
  if (report.stale.length > 0) parts.push(`Stale: ${report.stale.length}`);
  if (report.skipped.length > 0) parts.push(`Skipped: ${report.skipped.length}`);
  if (report.stoppedBy === "cancelled") parts.push("Stopped by you");
  if (report.stoppedBy === "blocked" && report.blockedReason) parts.push(`Stopped: ${report.blockedReason}`);
  return parts.join(" · ");
}

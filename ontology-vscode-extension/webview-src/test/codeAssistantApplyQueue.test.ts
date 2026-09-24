import { describe, it, expect } from "vitest";
import {
  applyRemapResult,
  buildApplyAllQueue,
  classifyApplyFailure,
  formatApplyAllSummary,
  groupLabel,
  nextApplyAllStep,
  runApplyAll,
  type ApplyAllHooks,
  type ApplyAllProgress,
  type ApplyOneResult,
  type GroupDecision,
} from "../services/codeAssistantApplyQueue";

const groups = [
  { serverGroupId: "g1", validation: { passed: true } },
  { serverGroupId: "g2", validation: { passed: true } },
  { serverGroupId: "g3", validation: { passed: true } },
  { serverGroupId: "g4", validation: { passed: true } },
];

function harness(
  initial: Record<string, GroupDecision>,
  behaviour: (id: string, decisions: Record<string, GroupDecision>) => ApplyOneResult | Promise<ApplyOneResult>,
  options: { cancelAfter?: string; blockedAfter?: string; blockedReason?: string } = {},
) {
  const decisions: Record<string, GroupDecision> = { ...initial };
  const calls: string[] = [];
  const progress: ApplyAllProgress[] = [];
  let cancel = false;
  let blocked: string | null = null;
  const hooks: ApplyAllHooks = {
    readDecisions: () => ({ ...decisions }),
    applyOne: async (id) => {
      calls.push(id);
      decisions[id] = "applying";
      const result = await behaviour(id, decisions);
      if (result.ok === false) decisions[id] = result.failure.decision;
      else decisions[id] = "applied";
      if (options.cancelAfter === id) cancel = true;
      if (options.blockedAfter === id) blocked = options.blockedReason ?? "blocked";
      return result;
    },
    isCancelRequested: () => cancel,
    blockedReason: () => blocked,
    onProgress: (p) => progress.push(p),
  };
  return { hooks, decisions, calls, progress };
}

const allPending: Record<string, GroupDecision> = { g1: "pending", g2: "pending", g3: "pending", g4: "pending" };
const ok = (): ApplyOneResult => ({ ok: true });

describe("buildApplyAllQueue", () => {
  it("keeps only validated groups that are still pending, in proposal order", () => {
    const queue = buildApplyAllQueue(
      [
        { serverGroupId: "a", validation: { passed: true } },
        { serverGroupId: "b", validation: { passed: false } },
        { serverGroupId: "c", validation: { passed: true } },
        { serverGroupId: "d", validation: { passed: true } },
        { serverGroupId: "e", validation: { passed: true } },
      ],
      { a: "pending", b: "failed", c: "skipped", d: "applied" },
    );
    expect(queue).toEqual(["a", "e"]);
  });
});

describe("nextApplyAllStep", () => {
  it("skips groups whose decision changed since the queue was built", () => {
    const step = nextApplyAllStep(["g1", "g2", "g3"], 0, { g1: "skipped", g2: "stale", g3: "pending" }, { cancelRequested: false, blocked: false });
    expect(step).toEqual({ kind: "apply", serverGroupId: "g3", index: 2 });
  });

  it("reports done rather than cancelled when nothing is left", () => {
    const step = nextApplyAllStep(["g1"], 1, { g1: "applied" }, { cancelRequested: true, blocked: false });
    expect(step).toEqual({ kind: "done" });
  });

  it("stops when cancel is requested and work remains", () => {
    expect(nextApplyAllStep(["g1"], 0, { g1: "pending" }, { cancelRequested: true, blocked: false })).toEqual({ kind: "stop", stop: "cancelled" });
    expect(nextApplyAllStep(["g1"], 0, { g1: "pending" }, { cancelRequested: false, blocked: true })).toEqual({ kind: "stop", stop: "blocked" });
  });
});

describe("runApplyAll", () => {
  it("applies every group strictly one after another", async () => {
    let inFlight = 0;
    let maxInFlight = 0;
    const h = harness(allPending, async () => {
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      await new Promise((r) => setTimeout(r, 1));
      inFlight -= 1;
      return ok();
    });
    const report = await runApplyAll(buildApplyAllQueue(groups, allPending), h.hooks);
    expect(h.calls).toEqual(["g1", "g2", "g3", "g4"]);
    expect(maxInFlight).toBe(1);
    expect(report.applied).toEqual(["g1", "g2", "g3", "g4"]);
    expect(report.failed).toBeNull();
    expect(h.progress.map((p) => `${p.position} of ${p.total}`)).toEqual(["1 of 4", "2 of 4", "3 of 4", "4 of 4"]);
  });

  it("stops at the first failure and never attempts the rest", async () => {
    const h = harness(allPending, (id) =>
      id === "g2" ? { ok: false, failure: classifyApplyFailure("CONFLICT", 409, "Document changed") } : ok(),
    );
    const report = await runApplyAll(buildApplyAllQueue(groups, allPending), h.hooks);
    expect(h.calls).toEqual(["g1", "g2"]);
    expect(report.applied).toEqual(["g1"]);
    expect(report.failed?.serverGroupId).toBe("g2");
    expect(report.failed?.failure.kind).toBe("conflict");
    expect(report.notAttempted).toEqual(["g3", "g4"]);
  });

  it("stops after a recovery error so nothing else is written to a possibly inconsistent project", async () => {
    const h = harness(allPending, (id) =>
      id === "g1" ? { ok: false, failure: classifyApplyFailure("RECOVERY_REQUIRED", 500, "Reimport failed") } : ok(),
    );
    const report = await runApplyAll(buildApplyAllQueue(groups, allPending), h.hooks);
    expect(h.calls).toEqual(["g1"]);
    expect(h.decisions.g1).toBe("recovery");
    expect(report.notAttempted).toEqual(["g2", "g3", "g4"]);
  });

  it("stops after a stale-group error", async () => {
    const h = harness(allPending, (id) =>
      id === "g3" ? { ok: false, failure: classifyApplyFailure("STALE_GROUP", 409, "stale") } : ok(),
    );
    const report = await runApplyAll(buildApplyAllQueue(groups, allPending), h.hooks);
    expect(h.calls).toEqual(["g1", "g2", "g3"]);
    expect(report.notAttempted).toEqual(["g4"]);
  });

  it("re-reads decisions so a group made stale or skipped mid-run is not applied", async () => {
    const h = harness(allPending, (id, decisions) => {
      if (id === "g1") {
        decisions.g2 = "stale";
        decisions.g3 = "skipped";
      }
      return ok();
    });
    const report = await runApplyAll(buildApplyAllQueue(groups, allPending), h.hooks);
    expect(h.calls).toEqual(["g1", "g4"]);
    expect(report.applied).toEqual(["g1", "g4"]);
    expect(report.stale).toEqual(["g2"]);
    expect(report.skipped).toEqual(["g3"]);
  });

  it("finishes the current group and then stops when cancelled", async () => {
    const h = harness(allPending, ok, { cancelAfter: "g2" });
    const report = await runApplyAll(buildApplyAllQueue(groups, allPending), h.hooks);
    expect(h.calls).toEqual(["g1", "g2"]);
    expect(report.stoppedBy).toBe("cancelled");
    expect(report.applied).toEqual(["g1", "g2"]);
    expect(report.notAttempted).toEqual(["g3", "g4"]);
  });

  it("stops when a blocking condition appears between groups", async () => {
    const h = harness(allPending, ok, { blockedAfter: "g1", blockedReason: "unsaved Code View changes" });
    const report = await runApplyAll(buildApplyAllQueue(groups, allPending), h.hooks);
    expect(h.calls).toEqual(["g1"]);
    expect(report.stoppedBy).toBe("blocked");
    expect(report.blockedReason).toBe("unsaved Code View changes");
  });

  it("treats a thrown error as a failure instead of carrying on", async () => {
    const h = harness(allPending, (id) => {
      if (id === "g1") throw new Error("Network down");
      return ok();
    });
    const report = await runApplyAll(buildApplyAllQueue(groups, allPending), h.hooks);
    expect(h.calls).toEqual(["g1"]);
    expect(report.failed?.failure.reason).toBe("Network down");
  });
});

describe("classifyApplyFailure", () => {
  it("maps each server error to a decision and whether to check recovery", () => {
    expect(classifyApplyFailure("RECOVERY_REQUIRED", undefined, "x")).toMatchObject({ decision: "recovery", checkRecovery: true });
    expect(classifyApplyFailure("PROJECT_RECOVERY_LOCKED", 423, "x")).toMatchObject({ kind: "locked", decision: "pending", checkRecovery: true });
    expect(classifyApplyFailure(undefined, 423, "x")).toMatchObject({ kind: "locked" });
    expect(classifyApplyFailure("STALE_GROUP", 409, "x")).toMatchObject({ decision: "stale", checkRecovery: false });
    expect(classifyApplyFailure("CONFLICT", 409, "x")).toMatchObject({ decision: "conflict" });
    expect(classifyApplyFailure("VALIDATION_FAILED", 400, "x")).toMatchObject({ decision: "failed" });
    expect(classifyApplyFailure(undefined, 500, "Gateway timeout.")).toMatchObject({ kind: "error", decision: "pending", reason: "Gateway timeout" });
    expect(classifyApplyFailure(undefined, 500, "y".repeat(200)).reason).toBe("unexpected error");
  });
});

describe("applyRemapResult", () => {
  it("marks the applied group and only siblings the server actually staled", () => {
    const next = applyRemapResult({ g1: "applying", g2: "pending", g3: "pending", g4: "skipped" }, "g1", [
      { serverGroupId: "g2", remapped: true },
      { serverGroupId: "g3", remapped: true, stale: true },
      { serverGroupId: "g4", remapped: true, status: "STALE" },
    ]);
    expect(next).toEqual({ g1: "applied", g2: "pending", g3: "stale", g4: "skipped" });
  });
});

describe("formatApplyAllSummary", () => {
  it("summarises applied, failed, not attempted and stale groups", async () => {
    const h = harness(allPending, (id, decisions) => {
      if (id === "g1") decisions.g2 = "stale";
      if (id === "g3") return { ok: false, failure: classifyApplyFailure("CONFLICT", 409, "changed") };
      return ok();
    });
    const extra = [...groups, { serverGroupId: "g5", validation: { passed: true } }];
    const report = await runApplyAll(buildApplyAllQueue(extra, { ...allPending, g5: "pending" }), h.hooks);
    expect(formatApplyAllSummary(report, (id) => groupLabel(extra, id))).toBe(
      "Applied 1 · Failed: group 3 (the document changed since it was checked) · Not attempted: 2 · Stale: 1",
    );
  });

  it("says the run was stopped without claiming anything was undone", async () => {
    const h = harness(allPending, ok, { cancelAfter: "g1" });
    const report = await runApplyAll(buildApplyAllQueue(groups, allPending), h.hooks);
    const summary = formatApplyAllSummary(report, (id) => groupLabel(groups, id));
    expect(summary).toBe("Applied 1 · Not attempted: 3 · Stopped by you");
    expect(summary.toLowerCase()).not.toMatch(/roll|undo|revert/);
  });
});

import { describe, expect, it } from "vitest";
import { toApplySummary } from "../services/codeAssistantApplySummary";
import { commitOutcome, describeSkipped, previewOutcome, sanitizeUndoStates } from "../services/codeAssistantUndo";
import type { ChangeSetResult } from "../services/changeSetService";

function result(overrides: Partial<ChangeSetResult>): ChangeSetResult {
  return { success: true, alreadyReverted: false, dryRun: true, direction: "UNDO", applied: [], skipped: [], status: 200, ...overrides };
}

const applied = (n: number) => Array.from({ length: n }, (_, i) => ({ changeId: `h${i}` }));

describe("toApplySummary", () => {
  it("uses the first meaningful line without markdown", () => {
    expect(toApplySummary("\n## Rename **A** to `B`\n\nMore detail")).toBe("Rename A to B");
  });

  it("caps long explanations at 200 characters", () => {
    const summary = toApplySummary("x".repeat(500));
    expect(summary).toHaveLength(200);
    expect(summary?.endsWith("…")).toBe(true);
  });

  it("returns undefined when there is nothing to say", () => {
    expect(toApplySummary(undefined)).toBeUndefined();
    expect(toApplySummary("  \n ")).toBeUndefined();
  });
});

describe("undo outcomes", () => {
  it("groups skipped reasons", () => {
    expect(describeSkipped([{ changeId: "h1", reason: "It was changed since this edit" }])).toBe("1 skipped: It was changed since this edit");
    expect(describeSkipped([])).toBeNull();
  });

  it("builds a preview with the count and skipped items", () => {
    const outcome = previewOutcome(result({ applied: applied(2), skipped: [{ changeId: "h9", reason: "Already undone" }] }), "UNDO");
    expect(outcome).toEqual({ kind: "preview", preview: { direction: "UNDO", count: 2, skipped: "1 skipped: Already undone" } });
  });

  it("treats an already reverted change set as done without changes", () => {
    expect(previewOutcome(result({ alreadyReverted: true }), "UNDO")).toEqual({ kind: "done", changed: false });
  });

  it("explains why nothing could be done", () => {
    const outcome = commitOutcome(
      result({ success: false, status: 409, skipped: [{ changeId: "h1", reason: "It was changed since this edit" }] }),
      "REDO",
    );
    expect(outcome).toEqual({ kind: "error", message: "Nothing can be redone. 1 skipped: It was changed since this edit." });
  });

  it("maps permission and missing errors to short messages", () => {
    expect(previewOutcome(result({ success: false, status: 403 }), "UNDO")).toEqual({
      kind: "error",
      message: "You don't have permission to undo this edit.",
    });
    expect(previewOutcome(result({ success: false, status: 404 }), "UNDO")).toEqual({ kind: "error", message: "This edit is no longer in history." });
    expect(previewOutcome(result({ success: false, status: undefined }), "UNDO")).toEqual({
      kind: "error",
      message: "Couldn't undo this edit. Try again.",
    });
  });

  it("drops transient undo state before saving", () => {
    const states = { g1: { undone: true, busy: true, preview: null, error: "x" }, g2: { undone: false } };
    expect(sanitizeUndoStates(states)).toEqual({ g1: { undone: true }, g2: { undone: false } });
    const clean = { g1: { undone: true } };
    expect(sanitizeUndoStates(clean)).toBe(clean);
  });
});

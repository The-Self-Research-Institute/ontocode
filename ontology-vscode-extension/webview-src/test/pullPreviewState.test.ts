import { describe, it, expect } from "vitest";
import {
  applyRefusal,
  BASELINE_LOST_FALLBACK,
  canKeepBoth,
  DIRECT_COMPARE_FALLBACK,
  phaseForAnalysis,
  rowKindLabel,
} from "../components/pullPreviewState";

describe("phaseForAnalysis", () => {
  it("never calls a lost baseline 'up to date'", () => {
    const result = phaseForAnalysis({ baselineLost: true, hasChanges: false, safeChanges: [], conflicts: [], message: "gone" });

    expect(result).toEqual({ phase: "baseline_lost", message: "gone", draftOnlyCount: 0 });
  });

  it("falls back to a clear message when the server sends none", () => {
    expect(phaseForAnalysis({ baselineLost: true }).message).toBe(BASELINE_LOST_FALLBACK);
  });

  it("offers the changes when Public has some", () => {
    expect(phaseForAnalysis({ safeChanges: [{ entityIri: "x" }], conflicts: [] }).phase).toBe("ready");
    expect(phaseForAnalysis({ safeChanges: [], conflicts: [{ entityIri: "x" }] }).phase).toBe("ready");
  });

  it("says up to date only when there is truly nothing", () => {
    expect(phaseForAnalysis({ safeChanges: [], conflicts: [] }).phase).toBe("no_changes");
    expect(phaseForAnalysis(undefined).phase).toBe("no_changes");
  });
});

describe("direct comparison after a lost baseline", () => {
  it("lists the differences for the user to choose from", () => {
    const result = phaseForAnalysis({
      baselineLost: true,
      directCompare: true,
      conflicts: [{ entityIri: "x", kind: "different" }],
      safeChanges: [],
      draftOnlyCount: 3,
      message: "choose",
    });

    expect(result).toEqual({ phase: "ready", message: "choose", draftOnlyCount: 3 });
  });

  it("uses a default explanation when the server sends none", () => {
    expect(
      phaseForAnalysis({ baselineLost: true, directCompare: true, conflicts: [{ entityIri: "x" }] }).message,
    ).toBe(DIRECT_COMPARE_FALLBACK);
  });

  it("says nothing to pull when the draft and Public only differ by the user's own items", () => {
    const result = phaseForAnalysis({ baselineLost: true, directCompare: true, conflicts: [], draftOnlyCount: 4 });

    expect(result.phase).toBe("no_changes");
    expect(result.draftOnlyCount).toBe(4);
  });

  it("labels each kind of difference", () => {
    expect(rowKindLabel("public_only")).toBe("New in Public");
    expect(rowKindLabel("different")).toBe("Changed");
    expect(rowKindLabel(undefined)).toBeNull();
  });
});

describe("canKeepBoth", () => {
  it("is offered when the item exists on both sides or the kind is unknown", () => {
    expect(canKeepBoth("different")).toBe(true);
    expect(canKeepBoth(undefined)).toBe(true);
  });

  it("is not offered for something that only exists in Public", () => {
    expect(canKeepBoth("public_only")).toBe(false);
  });
});

describe("applyRefusal", () => {
  it("returns the reason when the pull was refused", () => {
    expect(applyRefusal({ success: false, baselineLost: true, message: "lost" })).toBe("lost");
  });

  it("returns nothing for a normal pull", () => {
    expect(applyRefusal({ success: true, mergedCount: 2 })).toBeNull();
    expect(applyRefusal(undefined)).toBeNull();
  });
});

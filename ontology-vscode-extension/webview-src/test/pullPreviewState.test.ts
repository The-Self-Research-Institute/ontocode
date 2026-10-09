import { describe, it, expect } from "vitest";
import { applyRefusal, BASELINE_LOST_FALLBACK, phaseForAnalysis } from "../components/pullPreviewState";

describe("phaseForAnalysis", () => {
  it("never calls a lost baseline 'up to date'", () => {
    const result = phaseForAnalysis({ baselineLost: true, hasChanges: false, safeChanges: [], conflicts: [], message: "gone" });

    expect(result).toEqual({ phase: "baseline_lost", message: "gone" });
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

describe("applyRefusal", () => {
  it("returns the reason when the pull was refused", () => {
    expect(applyRefusal({ success: false, baselineLost: true, message: "lost" })).toBe("lost");
  });

  it("returns nothing for a normal pull", () => {
    expect(applyRefusal({ success: true, mergedCount: 2 })).toBeNull();
    expect(applyRefusal(undefined)).toBeNull();
  });
});

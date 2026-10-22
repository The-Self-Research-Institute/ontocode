import { describe, it, expect } from "vitest";
import {
  APPLY_FAILED_FALLBACK,
  applyFailure,
  applyRefusal,
  BASELINE_LOST_FALLBACK,
  canKeepBoth,
  DIRECT_COMPARE_FALLBACK,
  isSectionRow,
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

describe("ontology header and other axioms", () => {
  const iriRow = {
    entityIri: "header:ontologyIri",
    entityLabel: "Ontology IRI",
    kind: "header",
    field: "ontologyIri",
    publicAxioms: "Ontology IRI: http://ex.org/pizza → http://ex.org/pizza-v2",
  };
  const otherRow = { entityIri: "other-axioms", entityLabel: "Other axioms", kind: "other_axioms", added: 1, removed: 0 };

  it("counts an ontology IRI change alone as something to pull", () => {
    expect(phaseForAnalysis({ safeChanges: [iriRow], conflicts: [], hasChanges: true }).phase).toBe("ready");
  });

  it("counts other axioms alone as something to pull", () => {
    expect(phaseForAnalysis({ safeChanges: [otherRow], conflicts: [] }).phase).toBe("ready");
  });

  it("tells header and other-axiom rows apart from item rows", () => {
    expect(isSectionRow(iriRow)).toBe(true);
    expect(isSectionRow(otherRow)).toBe(true);
    expect(isSectionRow({ entityIri: "http://ex.org/A" })).toBe(false);
    expect(rowKindLabel("header")).toBe("Ontology header");
  });

  it("offers only keep mine or Public's for the ontology IRI and version IRI", () => {
    expect(canKeepBoth("header", "ontologyIri")).toBe(false);
    expect(canKeepBoth("header", "versionIri")).toBe(false);
    expect(canKeepBoth("header", "ontologyAnnotations")).toBe(true);
    expect(canKeepBoth("other_axioms", "otherAxioms")).toBe(true);
  });
});

describe("applyFailure", () => {
  it("returns the reason when the server asks for a choice first", () => {
    expect(applyFailure({ success: false, needsChoice: true, message: "choose" })).toBe("choose");
    expect(applyFailure({ success: false })).toBe(APPLY_FAILED_FALLBACK);
  });

  it("leaves lost baselines and successful pulls alone", () => {
    expect(applyFailure({ success: false, baselineLost: true })).toBeNull();
    expect(applyFailure({ success: true })).toBeNull();
    expect(applyFailure(undefined)).toBeNull();
  });
});

export const BASELINE_LOST_FALLBACK =
  "The starting snapshot of your draft was lost, so we can't tell what changed in Public since you started. " +
  "Publish your draft or re-add the missing items.";

export const DIRECT_COMPARE_FALLBACK =
  "The starting snapshot of your draft was lost, so we can't tell who changed what. Choose what to keep for each difference.";

export const APPLY_FAILED_FALLBACK = "The pull was not applied. Check the differences again and choose what to keep.";

export type AnalysisPhase = "baseline_lost" | "no_changes" | "ready";

export function phaseForAnalysis(data: any): { phase: AnalysisPhase; message: string; draftOnlyCount: number } {
  const draftOnlyCount = Number(data?.draftOnlyCount) || 0;
  const safe = data?.safeChanges || [];
  const conflicts = data?.conflicts || [];
  if (data?.baselineLost === true && data?.directCompare !== true) {
    return { phase: "baseline_lost", message: data.message || BASELINE_LOST_FALLBACK, draftOnlyCount };
  }
  const message = data?.directCompare === true ? data.message || DIRECT_COMPARE_FALLBACK : "";
  return {
    phase: safe.length === 0 && conflicts.length === 0 ? "no_changes" : "ready",
    message,
    draftOnlyCount,
  };
}

export function rowKindLabel(kind: unknown): string | null {
  if (kind === "public_only") {
    return "New in Public";
  }
  if (kind === "different") {
    return "Changed";
  }
  if (kind === "header") {
    return "Ontology header";
  }
  return null;
}

export function isSectionRow(row: any): boolean {
  return row?.kind === "header" || row?.kind === "other_axioms";
}

export function isSingleValueField(field: unknown): boolean {
  return field === "ontologyIri" || field === "versionIri";
}

export function canKeepBoth(kind: unknown, field?: unknown): boolean {
  return kind !== "public_only" && !isSingleValueField(field);
}

export function applyRefusal(data: any): string | null {
  if (data?.baselineLost === true) {
    return data.message || BASELINE_LOST_FALLBACK;
  }
  return null;
}

export function applyFailure(data: any): string | null {
  if (data?.success === false && data?.baselineLost !== true) {
    return data.message || data.error || APPLY_FAILED_FALLBACK;
  }
  return null;
}

export const BASELINE_LOST_FALLBACK =
  "The starting snapshot of your draft was lost, so we can't tell what changed in Public since you started. " +
  "Publish your draft or re-add the missing items.";

export type AnalysisPhase = "baseline_lost" | "no_changes" | "ready";

export function phaseForAnalysis(data: any): { phase: AnalysisPhase; message: string } {
  if (data?.baselineLost === true) {
    return { phase: "baseline_lost", message: data.message || BASELINE_LOST_FALLBACK };
  }
  const safe = data?.safeChanges || [];
  const conflicts = data?.conflicts || [];
  return { phase: safe.length === 0 && conflicts.length === 0 ? "no_changes" : "ready", message: "" };
}

export function applyRefusal(data: any): string | null {
  if (data?.baselineLost === true) {
    return data.message || BASELINE_LOST_FALLBACK;
  }
  return null;
}

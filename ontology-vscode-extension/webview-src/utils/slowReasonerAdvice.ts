const SLOW_REASONERS = new Set(["HERMIT", "FACTPLUSPLUS", "FACT++", "JFACT"]);

export const DEFAULT_HERMIT_LARGE_ONTOLOGY_AXIOMS = 50_000;

export function hermitLargeOntologyAxiomThreshold(): number {
  const raw = (import.meta as any)?.env?.VITE_HERMIT_LARGE_ONTOLOGY_AXIOMS;
  const parsed = Number(raw);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_HERMIT_LARGE_ONTOLOGY_AXIOMS;
}

export function isSlowReasoner(reasoner: string | null | undefined): boolean {
  return SLOW_REASONERS.has(String(reasoner ?? "").trim().toUpperCase());
}

export function isReasonerTooSlowMessage(message: string | null | undefined): boolean {
  const text = String(message ?? "").toLowerCase();
  return text.includes("timed out") || text.includes("too complex") || text.includes("took too long");
}

export function adviceAfterSlowFailure(reasoner: string, message: string | null | undefined): string | null {
  if (!isSlowReasoner(reasoner) || !isReasonerTooSlowMessage(message)) return null;
  return `${reasoner} can be very slow on large ontologies. ELK is much faster on them, but it supports fewer OWL constructs.`;
}

export function adviceBeforeRun(
  reasoner: string,
  axiomCount: number | null | undefined,
  threshold: number = hermitLargeOntologyAxiomThreshold(),
): string | null {
  if (String(reasoner ?? "").trim().toUpperCase() !== "HERMIT") return null;
  if (typeof axiomCount !== "number" || !Number.isFinite(axiomCount) || axiomCount <= threshold) return null;
  return `This ontology has ${axiomCount.toLocaleString("en-US")} axioms and HermiT can take many minutes on one this size. `
    + "ELK is much faster, but it supports fewer OWL constructs.";
}

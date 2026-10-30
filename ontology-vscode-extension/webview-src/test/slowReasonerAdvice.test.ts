import { describe, it, expect } from "vitest";
import {
  adviceAfterSlowFailure,
  adviceBeforeRun,
  DEFAULT_HERMIT_LARGE_ONTOLOGY_AXIOMS,
  hermitLargeOntologyAxiomThreshold,
  isReasonerTooSlowMessage,
} from "../utils/slowReasonerAdvice";

describe("slow reasoner advice", () => {
  it("defaults the large-ontology threshold to 50,000 axioms", () => {
    expect(DEFAULT_HERMIT_LARGE_ONTOLOGY_AXIOMS).toBe(50_000);
    expect(hermitLargeOntologyAxiomThreshold()).toBe(50_000);
  });

  it("recognises the worker timeout message", () => {
    expect(isReasonerTooSlowMessage("Reasoning timed out after 12 minutes. The ontology may be too complex for the server.")).toBe(true);
    expect(isReasonerTooSlowMessage("Ontology not found")).toBe(false);
  });

  it("suggests ELK after a HermiT timeout", () => {
    const advice = adviceAfterSlowFailure("HermiT", "Reasoning timed out after 12 minutes.");
    expect(advice).toContain("HermiT can be very slow on large ontologies");
    expect(advice).toContain("ELK");
  });

  it("gives no switch advice for ELK or for unrelated failures", () => {
    expect(adviceAfterSlowFailure("ELK", "Reasoning timed out after 12 minutes.")).toBeNull();
    expect(adviceAfterSlowFailure("HermiT", "Ontology not found")).toBeNull();
  });

  it("warns before a HermiT run only above the threshold", () => {
    expect(adviceBeforeRun("HermiT", 167_146)).toContain("167,146 axioms");
    expect(adviceBeforeRun("HermiT", 50_000)).toBeNull();
    expect(adviceBeforeRun("HermiT", 50_001)).not.toBeNull();
    expect(adviceBeforeRun("HermiT", 10_005)).toBeNull();
    expect(adviceBeforeRun("HermiT", 1000, 500)).not.toBeNull();
  });

  it("does not warn for other reasoners or an unknown size", () => {
    expect(adviceBeforeRun("ELK", 500_000)).toBeNull();
    expect(adviceBeforeRun("HermiT", undefined)).toBeNull();
  });
});

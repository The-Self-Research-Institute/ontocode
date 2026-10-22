import { describe, expect, it } from "vitest";
import {
  describeChange,
  getCategory,
  groupByCategory,
  mergeHeadline,
  mergeKindSentence,
  shortPropertyName,
} from "../utils/draftChangeHelpers";
import { conflictKeys, normalizeFullDiff } from "../components/DraftPRDetails";

describe("draft change categories", () => {
  it("puts ontology header, GCI, merge and expression records in their own groups", () => {
    expect(getCategory("updateOntologyIri")).toBe("Ontology header");
    expect(getCategory("deleteOntologyAnnotation")).toBe("Ontology header");
    expect(getCategory("addOntologyImport")).toBe("Ontology header");
    expect(getCategory("deletePrefix")).toBe("Ontology header");
    expect(getCategory("updateGCI")).toBe("GCI axioms");
    expect(getCategory("ontologyMerge")).toBe("Ontology merge");
    expect(getCategory("addClassExpression")).toBe("Class Axioms");
    expect(getCategory("addAxiomAnnotation")).toBe("Annotations");
    expect(getCategory("addPropertyRange")).toBe("Object Properties");
    expect(getCategory("structuralGraphEdit")).toBe("Other");
  });

  it("keeps the existing groups for existing records", () => {
    expect(getCategory("createClass")).toBe("Classes");
    expect(getCategory("addSubClassOf")).toBe("Class Axioms");
    expect(getCategory("addAnnotation")).toBe("Annotations");
    expect(getCategory("createIndividual")).toBe("Individuals");
    const grouped = groupByCategory([
      { id: "1", operationType: "createClass", operationData: {} },
      { id: "2", operationType: "addPrefix", operationData: {} },
    ]);
    expect(Object.keys(grouped).sort()).toEqual(["Classes", "Ontology header"]);
  });
});

describe("readable draft change text", () => {
  it("describes header changes with old and new values", () => {
    expect(describeChange("updateOntologyIri", { oldValue: "http://a/1", value: "http://a/2", oldVersionIri: "http://a/1/v1",
      versionIri: "http://a/1/v1" })).toEqual({ label: "Ontology IRI", lines: ["Ontology IRI: http://a/1 → http://a/2"] });
    expect(describeChange("updateOntologyAnnotation", { property: "http://purl.org/dc/terms/title",
      oldProperty: "http://www.w3.org/2000/01/rdf-schema#label", oldValue: "Old", value: "New", language: "en" }))
      .toEqual({ label: "Annotation dcterms:title", lines: ["rdfs:label \"Old\" → dcterms:title \"New\"@en"] });
    expect(describeChange("deleteOntologyImport", { value: "http://x/y.owl" }))
      .toEqual({ label: "Import removed", lines: ["http://x/y.owl"] });
    expect(describeChange("updatePrefix", { prefix: "new", oldPrefix: "old", oldValue: "http://n#", value: "http://n#" }))
      .toEqual({ label: "Prefix new:", lines: ["Renamed old: → new:"] });
    expect(describeChange("deletePrefix", { prefix: "ex", oldValue: "http://ex#" }))
      .toEqual({ label: "Prefix ex:", lines: ["Removed <http://ex#>"] });
  });

  it("describes axioms, citations and summary records", () => {
    expect(describeChange("deleteGCI", { value: "A SubClassOf B" })).toEqual({ label: "GCI", lines: ["Removed: A SubClassOf B"] });
    expect(describeChange("addPropertyDomain", { iri: "http://ex.org#hasPart", value: "Pizza or Topping" }))
      .toEqual({ label: "hasPart", lines: ["Domain added: Pizza or Topping"] });
    expect(describeChange("addClassExpression", { iri: "http://ex.org#Pizza", axiomType: "SubClassOf", value: "hasBase some Base" }))
      .toEqual({ label: "Pizza", lines: ["Class expression SubClassOf: hasBase some Base"] });
    expect(describeChange("addAxiomAnnotation", { iri: "http://ex.org#Pizza", target: "http://ex.org#Food", axiomType: "SubClassOf",
      property: "http://www.w3.org/2000/01/rdf-schema#comment", value: "why" }))
      .toEqual({ label: "Pizza", lines: ["Added axiom annotation rdfs:comment \"why\" on SubClassOf Food"] });
    expect(describeChange("deleteCitation", { value: "urn:citation:x" })).toEqual({ label: "Citation", lines: ["Removed urn:citation:x"] });
    expect(describeChange("bulkGraphEdit", { label: "12 statements", value: "10 added, 2 removed" }))
      .toEqual({ label: "12 statements", lines: ["10 added, 2 removed"] });
    expect(describeChange("createClass", { iri: "http://ex.org#A" })).toBeNull();
  });

  it("shortens well-known annotation properties", () => {
    expect(shortPropertyName("http://purl.org/dc/elements/1.1/creator")).toBe("dc:creator");
    expect(shortPropertyName("http://example.org/vocab#note")).toBe("note");
  });
});

describe("merge summaries", () => {
  it("names the kind once and previews the entity names", () => {
    expect(mergeKindSentence({ kind: "objectProperty", changed: ["p"], changedCount: 1, removed: ["q", "r"], removedCount: 2 }))
      .toBe("1 object property changed, 2 removed (q, r)");
    expect(mergeKindSentence({ kind: "individual", copies: ["a_imported"], copiesCount: 1 }))
      .toBe("1 individual copy added (a_imported)");
  });

  it("falls back to the stored description for older merge records", () => {
    expect(mergeHeadline(undefined, "Merged ontology: 3 added")).toBe("Ontology merge — Merged ontology: 3 added");
    expect(mergeHeadline(undefined)).toBe("Ontology merge");
  });
});

describe("full diff payloads", () => {
  it("fills in missing parts and lists the conflict keys", () => {
    const diff = normalizeFullDiff({
      available: true,
      changes: [{ entityIri: "http://ex.org/A", kind: "class", change: "changed", conflict: true }],
      header: [{ field: "versionIri", conflict: true }, { field: "imports", conflict: false }],
    });
    expect(diff.baselineLost).toBe(false);
    expect(diff.changes[0].yourAxioms).toEqual([]);
    expect(diff.otherAxioms).toEqual({ added: 0, removed: 0, items: [] });
    expect(conflictKeys(diff)).toEqual(["http://ex.org/A", "header:versionIri"]);
    expect(normalizeFullDiff(null).available).toBe(false);
  });
});

import { describe, expect, it } from "vitest";
import { resolveLabelOverlaySegments, type PrefixMapping } from "../components/codeAssistantLabelOverlay";

const PREFIXES: PrefixMapping[] = [
  { prefix: ":", namespace: "http://example.org/default#" },
  { prefix: "ex:", namespace: "http://example.org/" },
];

const LABELS = new Map<string, string>([
  ["http://example.org/Car", "Car"],
  ["http://example.org/hasEngine", "has engine"],
  ["http://example.org/default#Vehicle", "Vehicle"],
]);

describe("resolveLabelOverlaySegments", () => {
  it("resolves prefixed entities that have a label", () => {
    const segments = resolveLabelOverlaySegments("ex:Car ex:hasEngine ex:V8Engine .", LABELS, PREFIXES);
    const labeled = segments.filter((s) => s.label);
    expect(labeled.map((s) => [s.text, s.label])).toEqual([
      ["ex:Car", "Car"],
      ["ex:hasEngine", "has engine"],
    ]);
  });

  it("resolves the default (empty) prefix form, e.g. bare :Vehicle", () => {
    const segments = resolveLabelOverlaySegments(":Vehicle", LABELS, PREFIXES);
    expect(segments).toEqual([{ text: ":Vehicle", label: "Vehicle" }]);
  });

  it("leaves standard RDF/RDFS/OWL/XSD vocabulary raw even if present in prefixMappings", () => {
    const prefixesWithCore: PrefixMapping[] = [
      ...PREFIXES,
      { prefix: "rdfs:", namespace: "http://www.w3.org/2000/01/rdf-schema#" },
    ];
    const labelsWithCore = new Map(LABELS).set("http://www.w3.org/2000/01/rdf-schema#subClassOf", "subclass of");
    const segments = resolveLabelOverlaySegments("ex:Car rdfs:subClassOf ex:Car .", labelsWithCore, prefixesWithCore);
    const labeled = segments.filter((s) => s.label);
    expect(labeled.map((s) => s.text)).toEqual(["ex:Car", "ex:Car"]);
  });

  it("falls back to raw text for entities with no label", () => {
    const segments = resolveLabelOverlaySegments("ex:Unlabeled ex:AlsoUnlabeled", LABELS, PREFIXES);
    expect(segments.every((s) => !s.label)).toBe(true);
  });

  it("falls back to raw text for an unknown prefix not in prefixMappings", () => {
    const segments = resolveLabelOverlaySegments("foaf:name", LABELS, PREFIXES);
    expect(segments).toEqual([{ text: "foaf:name" }]);
  });

  it("resolves a full bracketed IRI directly, without prefix expansion", () => {
    const segments = resolveLabelOverlaySegments("<http://example.org/Car> a ex:Vehicle .", LABELS, PREFIXES);
    const labeled = segments.filter((s) => s.label);
    expect(labeled.map((s) => [s.text, s.label])).toEqual([["<http://example.org/Car>", "Car"]]);
  });

  it("does not treat a bare http(s) URL as a prefixed name", () => {
    const segments = resolveLabelOverlaySegments("see http://example.org/Car for details", LABELS, PREFIXES);
    expect(segments.some((s) => s.label)).toBe(false);
    expect(segments.map((s) => s.text).join("")).toBe("see http://example.org/Car for details");
  });

  it("does not false-positive on a port number after a host (ex:8080)", () => {
    const segments = resolveLabelOverlaySegments("http://example.org:8080/Car", LABELS, PREFIXES);
    expect(segments.some((s) => s.label)).toBe(false);
  });

  it("round-trips the original text exactly when segments are re-joined", () => {
    const text = '  ex:Car   rdfs:subClassOf  :Vehicle ;\n    ex:hasEngine ex:V8Engine ;\n    ex:note "a:b inside a literal" .';
    const segments = resolveLabelOverlaySegments(text, LABELS, PREFIXES);
    expect(segments.map((s) => s.text).join("")).toBe(text);
  });

  it("handles an empty string without throwing", () => {
    expect(resolveLabelOverlaySegments("", LABELS, PREFIXES)).toEqual([]);
  });
});

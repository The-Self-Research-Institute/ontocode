import { describe, expect, it } from "vitest";
import { computeAskAiHighlight, toCodeViewFormat } from "../components/codeViewHighlight";

const content = [
  "@prefix ex: <http://example.org/> .",
  "ex:Dog a owl:Class ;",
  '  rdfs:label "Dog"@en .',
  "ex:Cat a owl:Class ;",
  '  rdfs:label "Cat"@en .',
].join("\n");

describe("computeAskAiHighlight", () => {
  it("highlights the exact applied lines when the range format matches the view", () => {
    const result = computeAskAiHighlight(
      { texts: ['  rdfs:label "Cat"@en .'], ranges: [{ format: "turtle", startLine: 3, lineCount: 2 }] },
      content,
      "turtle",
      0,
    );
    expect(result.source).toBe("ranges");
    expect([...result.lines.keys()]).toEqual([4, 5]);
    expect(result.firstLine).toBe(4);
  });

  it("does not pick an earlier line that merely contains the same text", () => {
    const result = computeAskAiHighlight(
      { texts: ["  rdfs:label"], ranges: [{ format: "turtle", startLine: 4, lineCount: 1 }] },
      content,
      "turtle",
      0,
    );
    expect([...result.lines.keys()]).toEqual([5]);
  });

  it("offsets ranges by the page start in paged mode and drops lines outside the page", () => {
    const result = computeAskAiHighlight(
      { texts: [], ranges: [{ format: "turtle", startLine: 1001, lineCount: 10 }] },
      content,
      "turtle",
      1000,
    );
    expect([...result.lines.keys()]).toEqual([2, 3, 4, 5]);
  });

  it("falls back to text search when the view shows a different format", () => {
    const result = computeAskAiHighlight(
      { texts: ["ex:Cat a owl:Class ;"], ranges: [{ format: "rdfxml", startLine: 3, lineCount: 1 }] },
      content,
      "turtle",
      0,
    );
    expect(result.source).toBe("text");
    expect(result.firstLine).toBe(4);
  });

  it("reports nothing to highlight for a pure deletion", () => {
    const result = computeAskAiHighlight({ texts: [""], ranges: [] }, content, "turtle", 0);
    expect(result.source).toBe("none");
    expect(result.firstLine).toBeNull();
  });

  it("matches formats regardless of case and surrounding spaces", () => {
    const result = computeAskAiHighlight(
      { texts: [], ranges: [{ format: " Turtle ", startLine: 0, lineCount: 1 }] },
      content,
      "turtle",
      0,
    );
    expect([...result.lines.keys()]).toEqual([1]);
  });
});

describe("toCodeViewFormat", () => {
  it("maps the format names the assistant uses onto Code View formats", () => {
    expect(toCodeViewFormat("ttl")).toBe("turtle");
    expect(toCodeViewFormat(" RDFXML ")).toBe("rdfxml");
    expect(toCodeViewFormat("nt")).toBe("ntriples");
    expect(toCodeViewFormat("functionalsyntax")).toBe("functional");
    expect(toCodeViewFormat("yaml")).toBeNull();
  });

  it("treats format aliases as the same view when highlighting", () => {
    const result = computeAskAiHighlight({ texts: [], ranges: [{ format: "ttl", startLine: 0, lineCount: 1 }] }, "a\nb", "turtle", 0);
    expect(result.source).toBe("ranges");
  });
});

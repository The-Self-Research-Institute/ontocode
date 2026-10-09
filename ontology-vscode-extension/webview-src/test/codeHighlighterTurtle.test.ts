import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { highlightTurtleLine } from "../components/CodeHighlighter";

function visibleText(html: string): string {
  const element = document.createElement("div");
  element.innerHTML = html;
  return element.textContent || "";
}

describe("highlightTurtleLine", () => {
  it("draws a prefix line exactly as written, with no stray semicolons around the address", () => {
    const line = "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .";

    expect(visibleText(highlightTurtleLine(line))).toBe(line);
  });

  it("draws a long prefix address with t and g in it unchanged", () => {
    const line = "@prefix pizza2023: <http://www.semanticweb.org/v0cn037/ontologies/2023/6/PizzaTutorial#> .";

    expect(visibleText(highlightTurtleLine(line))).toBe(line);
  });

  it("colours the whole address as one piece", () => {
    const html = highlightTurtleLine("@prefix ex: <http://example.org/a#> .");

    expect(html).toContain("&lt;http://example.org/a#&gt;</span>");
    expect(html).not.toMatch(/&lt(?!;)/);
    expect(html).not.toMatch(/&gt(?!;)/);
  });

  it("keeps addresses that contain an ampersand intact", () => {
    const line = "ex:a ex:link <http://example.org/q?a=1&b=2> .";

    expect(visibleText(highlightTurtleLine(line))).toBe(line);
  });

  it("draws quoted values, language tags and escaped quotes exactly as written", () => {
    for (const line of [
      '  rdfs:label "Pizza"@en ;',
      '  rdfs:comment "He said \\"hi\\" to me" .',
      '  ex:path "C:\\temp" ; ex:n "3"^^xsd:integer .',
      '  ex:a "x" , "y" , "z" .',
    ]) {
      expect(visibleText(highlightTurtleLine(line))).toBe(line);
    }
  });

  it("colours a quoted value as one piece with no stray punctuation inside it", () => {
    const html = highlightTurtleLine('  rdfs:label "Pizza Margherita"@en .');

    expect(html).toContain("&quot;Pizza Margherita&quot;</span>");
    expect(html).not.toMatch(/&quot(?!;)/);
  });

  it("still draws ordinary statements unchanged", () => {
    const line = '  ex:Pizza a owl:Class ; rdfs:label "Pizza"@en .';

    expect(visibleText(highlightTurtleLine(line))).toBe(line);
  });
});

describe("CodeHighlighter patterns", () => {
  it("never uses a letter-exclusion class on escaped markers like &gt; or &quot;", () => {
    const source = readFileSync(resolve(__dirname, "../components/CodeHighlighter.tsx"), "utf-8");

    expect(source).not.toMatch(/\[\^&(?:gt|lt|quot);\]/);
  });
});

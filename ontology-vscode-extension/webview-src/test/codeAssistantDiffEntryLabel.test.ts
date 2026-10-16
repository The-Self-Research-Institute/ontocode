import { describe, expect, it } from "vitest";
import { formatName, lineLabel } from "../components/CodeAssistantDiffEntry";

describe("diff entry location label", () => {
  it("names the format whose line numbers are shown", () => {
    expect(formatName("turtle")).toBe("Turtle");
    expect(formatName("rdfxml")).toBe("RDF/XML");
    expect(lineLabel(41, 1, "turtle")).toBe("line 42 of the Turtle file");
    expect(lineLabel(41, 3, "rdfxml")).toBe("lines 42–44 of the RDF/XML file");
  });

  it("keeps an unknown format readable and the plain label when none is given", () => {
    expect(formatName("custom")).toBe("custom");
    expect(lineLabel(0, 1)).toBe("line 1");
  });
});

import { describe, expect, it } from "vitest";
import { formatName, lineLabel } from "../components/CodeAssistantDiffEntry";
import { proposalExplanation } from "../components/CodeAssistantTranscriptEntry";
import type { ProposedEditGroupResult } from "../services/codeAssistantSession";

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

  it("lets the verified diff provide the location for an edit proposal", () => {
    const explanation = "Add the prefix at line 9. Keep the existing declarations.";
    const group = { diff: [{ targetPath: "turtle", startLine: 10 }] } as ProposedEditGroupResult;
    expect(proposalExplanation({ explanation, groups: [group] }))
      .toBe("Add the prefix at the location in the diff below. Keep the existing declarations.");
    expect(proposalExplanation({ explanation, groups: [] })).toBe(explanation);
  });
});

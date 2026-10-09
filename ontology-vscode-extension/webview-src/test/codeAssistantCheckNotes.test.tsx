import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import {
  CodeAssistantCheckNotes,
  CodeAssistantFailedChecks,
  INSERTION_MOVED_CHECK,
  INSERTION_MOVED_PAST_SIBLING_CHECK,
} from "../components/CodeAssistantFailedChecks";

describe("CodeAssistantCheckNotes", () => {
  it("shows where an insertion was moved", () => {
    const html = renderToStaticMarkup(
      <CodeAssistantCheckNotes
        checks={[
          { name: "syntax_valid", passed: true },
          { name: INSERTION_MOVED_CHECK, passed: true, detail: "Moved from line 401 to line 409 so it doesn't split a statement." },
        ]}
      />,
    );
    expect(html).toContain("Moved from line 401 to line 409 so it doesn&#x27;t split a statement.");
  });

  it("shows where an insertion was moved past a sibling edit", () => {
    const html = renderToStaticMarkup(
      <CodeAssistantCheckNotes
        checks={[
          {
            name: INSERTION_MOVED_PAST_SIBLING_CHECK,
            passed: true,
            detail: "Moved the insertion at line 309 to line 310 so it lands after the edit right before it instead of inside it.",
          },
        ]}
      />,
    );
    expect(html).toContain("so it lands after the edit right before it instead of inside it.");
  });

  it("renders nothing when no adjustment was made", () => {
    expect(renderToStaticMarkup(<CodeAssistantCheckNotes checks={[{ name: "syntax_valid", passed: true }]} />)).toBe("");
  });
});

describe("CodeAssistantFailedChecks", () => {
  it("uses a failure-phrased label for a check whose default wording would contradict its own detail", () => {
    const html = renderToStaticMarkup(
      <CodeAssistantFailedChecks
        checks={[
          {
            name: "original_text_matches_live",
            passed: false,
            detail: "The document changed at line 6 since this edit was proposed.",
          },
        ]}
      />,
    );
    expect(html).toContain("The document has changed since this edit was proposed");
    expect(html).not.toContain("still matches the current document");
  });

  it("uses the normal label for every other failed check", () => {
    const html = renderToStaticMarkup(
      <CodeAssistantFailedChecks checks={[{ name: "syntax_valid", passed: false, detail: "Unexpected token" }]} />,
    );
    expect(html).toContain("The edited document parses");
  });
});

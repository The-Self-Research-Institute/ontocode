import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { CodeAssistantCheckNotes, INSERTION_MOVED_CHECK } from "../components/CodeAssistantFailedChecks";

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

  it("renders nothing when no adjustment was made", () => {
    expect(renderToStaticMarkup(<CodeAssistantCheckNotes checks={[{ name: "syntax_valid", passed: true }]} />)).toBe("");
  });
});

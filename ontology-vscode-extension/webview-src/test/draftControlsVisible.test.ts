import { describe, expect, it } from "vitest";
import { draftControlsVisible } from "../utils/draftScope";

describe("draftControlsVisible", () => {
  it("shows the draft buttons in private mode on web", () => {
    expect(draftControlsVisible("private", false)).toBe(true);
  });

  it("hides them on desktop, where there is no draft to pull into or discard", () => {
    expect(draftControlsVisible("private", true)).toBe(false);
  });

  it("hides them in public mode", () => {
    expect(draftControlsVisible("public", false)).toBe(false);
  });
});

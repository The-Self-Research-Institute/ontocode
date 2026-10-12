import { describe, it, expect } from "vitest";
import { isSaveDisabledInDraft } from "../components/dashboard-parts/draftSave";

describe("isSaveDisabledInDraft", () => {
  it("turns Save off in the web app while editing a draft", () => {
    expect(isSaveDisabledInDraft("private", false)).toBe(true);
  });

  it("keeps Save on when editing the live public version", () => {
    expect(isSaveDisabledInDraft("public", false)).toBe(false);
  });

  it("keeps Save on in the desktop app, which has no draft mode", () => {
    expect(isSaveDisabledInDraft("private", true)).toBe(false);
    expect(isSaveDisabledInDraft("public", true)).toBe(false);
  });
});

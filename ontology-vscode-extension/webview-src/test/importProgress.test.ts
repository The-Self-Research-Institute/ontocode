import { describe, expect, it } from "vitest";
import { isPostCompletionProgress } from "../utils/importProgress";

describe("isPostCompletionProgress", () => {
  it("is true for the messages the server sends after an import has completed", () => {
    expect(isPostCompletionProgress({ status: "PROCESSING", metadata: { stage: "background-indexing" } })).toBe(true);
    expect(isPostCompletionProgress({ status: "METADATA_READY", metadata: { stage: "metadata-ready" } })).toBe(true);
    expect(isPostCompletionProgress({ status: "METADATA_ERROR", metadata: { stage: "metadata-error" } })).toBe(true);
    expect(isPostCompletionProgress({ status: "METADATA_READY" })).toBe(true);
    expect(isPostCompletionProgress({ status: "PROCESSING", metadata: { stage: "metadata-ready" } })).toBe(true);
  });

  it("is false for progress that belongs to an import still running", () => {
    for (const stage of ["parsing", "graphdb-loading", "hierarchy-warming", "computing-metadata", "resolving-imports"]) {
      expect(isPostCompletionProgress({ status: "PROCESSING", metadata: { stage } })).toBe(false);
    }
  });

  it("is false when there is nothing to go on", () => {
    expect(isPostCompletionProgress(undefined)).toBe(false);
    expect(isPostCompletionProgress(null)).toBe(false);
    expect(isPostCompletionProgress({})).toBe(false);
    expect(isPostCompletionProgress({ status: "PROCESSING", metadata: null })).toBe(false);
    expect(isPostCompletionProgress({ status: "PROCESSING", metadata: { stage: 5 } })).toBe(false);
  });
});

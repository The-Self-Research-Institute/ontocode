import { describe, expect, it } from "vitest";
import { IMPORT_SETTLE_POLLS, isPostCompletionProgress, isProjectStatusCompleted } from "../utils/importProgress";

describe("isProjectStatusCompleted", () => {
  it("reads the status from a wrapped or a plain response", () => {
    expect(isProjectStatusCompleted({ data: { status: "COMPLETED" } })).toBe(true);
    expect(isProjectStatusCompleted({ status: "COMPLETED" })).toBe(true);
  });

  it("is false while an import is still running or when nothing came back", () => {
    expect(isProjectStatusCompleted({ data: { status: "PROCESSING" } })).toBe(false);
    expect(isProjectStatusCompleted({ status: "ERROR" })).toBe(false);
    expect(isProjectStatusCompleted(undefined)).toBe(false);
    expect(isProjectStatusCompleted(null)).toBe(false);
    expect(isProjectStatusCompleted({})).toBe(false);
  });

  it("asks for more than one confirming poll before giving up the wait", () => {
    expect(IMPORT_SETTLE_POLLS).toBeGreaterThan(1);
  });
});

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

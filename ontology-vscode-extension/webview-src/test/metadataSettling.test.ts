import { describe, expect, it } from "vitest";
import { metadataStillSettling } from "../utils/metadataSettling";

describe("metadataStillSettling", () => {
  it("stops asking once a project with classes answers", () => {
    expect(metadataStillSettling({ data: { classCount: 12, countsPending: true } })).toBe(false);
    expect(metadataStillSettling({ counts: { classes: 3 } })).toBe(false);
  });

  it("stops asking when an empty project's counts are final", () => {
    expect(metadataStillSettling({ data: { classCount: 0, projectStatus: "COMPLETED" } })).toBe(false);
    expect(metadataStillSettling({ data: { classCount: 0 } })).toBe(false);
  });

  it("keeps asking while the server is still counting", () => {
    expect(metadataStillSettling({ data: { classCount: 0, countsPending: true } })).toBe(true);
  });

  it("keeps asking while the file is still being loaded", () => {
    expect(metadataStillSettling({ data: { projectStatus: "PROCESSING" } })).toBe(true);
  });

  it("keeps asking when the first request failed", () => {
    expect(metadataStillSettling(null)).toBe(true);
    expect(metadataStillSettling(undefined)).toBe(true);
  });

  it("stops asking when loading failed", () => {
    expect(metadataStillSettling({ data: { classCount: 0, projectStatus: "ERROR" } })).toBe(false);
  });
});

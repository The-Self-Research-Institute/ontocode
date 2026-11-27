import { describe, expect, it, vi } from "vitest";
import { metadataStillSettling, retryUntilSettled } from "../utils/metadataSettling";

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
    expect(metadataStillSettling({ data: { projectStatus: "UPLOADED" } })).toBe(true);
    expect(metadataStillSettling({ data: { classCount: 0, projectStatus: "INDEXING" } })).toBe(true);
  });

  it("keeps asking when a completed project's counts failed and came back as zero", () => {
    expect(metadataStillSettling({ data: { classCount: 0, projectStatus: "COMPLETED", metricsFailed: true } })).toBe(true);
  });

  it("keeps asking when the first request failed", () => {
    expect(metadataStillSettling(null)).toBe(true);
    expect(metadataStillSettling(undefined)).toBe(true);
  });

  it("stops asking when loading failed", () => {
    expect(metadataStillSettling({ data: { classCount: 0, projectStatus: "ERROR" } })).toBe(false);
  });
});

describe("retryUntilSettled", () => {
  const noWait = () => Promise.resolve();

  it("retries a completed project whose zero came from failed counts, and applies the real answer", async () => {
    const fetchAgain = vi.fn()
      .mockResolvedValueOnce({ data: { classCount: 0, projectStatus: "COMPLETED", metricsFailed: true } })
      .mockResolvedValueOnce({ data: { classCount: 42, projectStatus: "COMPLETED" } });
    const apply = vi.fn();

    const settled = await retryUntilSettled(fetchAgain, apply, { attempts: 20, waitMs: 3000, stopped: () => false, wait: noWait });

    expect(settled).toBe(true);
    expect(fetchAgain).toHaveBeenCalledTimes(2);
    expect(apply).toHaveBeenCalledTimes(1);
    expect(apply).toHaveBeenCalledWith({ data: { classCount: 42, projectStatus: "COMPLETED" } });
  });

  it("stops after one answer for a project that really has no classes", async () => {
    const fetchAgain = vi.fn().mockResolvedValue({ data: { classCount: 0, projectStatus: "COMPLETED" } });
    const apply = vi.fn();

    await retryUntilSettled(fetchAgain, apply, { attempts: 20, waitMs: 3000, stopped: () => false, wait: noWait });

    expect(fetchAgain).toHaveBeenCalledTimes(1);
    expect(apply).toHaveBeenCalledTimes(1);
  });

  it("gives up after the attempt limit and survives failed requests", async () => {
    const fetchAgain = vi.fn().mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValue({ data: { classCount: 0, countsPending: true } });
    const apply = vi.fn();

    const settled = await retryUntilSettled(fetchAgain, apply, { attempts: 3, waitMs: 3000, stopped: () => false, wait: noWait });

    expect(settled).toBe(false);
    expect(fetchAgain).toHaveBeenCalledTimes(3);
    expect(apply).not.toHaveBeenCalled();
  });

  it("stops as soon as the page moves on", async () => {
    const fetchAgain = vi.fn();
    await retryUntilSettled(fetchAgain, vi.fn(), { attempts: 20, waitMs: 3000, stopped: () => true, wait: noWait });
    expect(fetchAgain).not.toHaveBeenCalled();
  });
});

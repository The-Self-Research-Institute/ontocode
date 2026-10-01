import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../services/apiClient", () => {
  class ApiError extends Error {
    status?: number;
    data?: unknown;
    constructor(message: string, status?: number, data?: unknown) {
      super(message);
      this.status = status;
      this.data = data;
    }
  }
  return { default: { post: vi.fn(), get: vi.fn() }, ApiError };
});

import apiClient, { ApiError } from "../services/apiClient";
import { changeTrackingService } from "../services/changeTrackingService";

const postMock = vi.mocked(apiClient.post);

const item = (changeId: string, reason?: string) => ({
  changeId,
  subChangeId: null,
  entityIRI: "http://ex.org/A",
  entityLabel: "A",
  predicate: null,
  reason: reason ?? null,
});

beforeEach(() => {
  postMock.mockReset();
  vi.spyOn(console, "error").mockImplementation(() => {});
  window.localStorage.clear();
});

describe("changeTrackingService change sets", () => {
  it("previews an undo with dryRun=true", async () => {
    postMock.mockResolvedValueOnce({
      success: true, alreadyReverted: false, dryRun: true, direction: "UNDO",
      applied: [item("h1"), item("h2")], skipped: [], auditId: null, message: "Preview ready",
    });

    const result = await changeTrackingService.undoChangeSet("proj 1", "grp/1", { dryRun: true });

    expect(postMock).toHaveBeenCalledTimes(1);
    expect(postMock.mock.calls[0][0]).toBe("/api/ontology/proj%201/change-sets/grp%2F1/undo?dryRun=true");
    expect(postMock.mock.calls[0][1]).toEqual({ userId: "anonymous", username: "Anonymous" });
    expect(result).toMatchObject({ success: true, dryRun: true, direction: "UNDO", status: 200 });
    expect(result.applied).toHaveLength(2);
  });

  it("runs a redo with dryRun=false", async () => {
    postMock.mockResolvedValueOnce({
      success: true, alreadyReverted: false, dryRun: false, direction: "REDO",
      applied: [item("h1")], skipped: [], auditId: "audit-9", message: "Rolled back",
    });

    const result = await changeTrackingService.redoChangeSet("p1", "g1", { dryRun: false });

    expect(postMock.mock.calls[0][0]).toBe("/api/ontology/p1/change-sets/g1/redo?dryRun=false");
    expect(result).toMatchObject({ success: true, dryRun: false, direction: "REDO", auditId: "audit-9" });
  });

  it("returns skipped items and the status when nothing could be undone", async () => {
    postMock.mockRejectedValueOnce(
      new ApiError("Conflict", 409, {
        success: false, alreadyReverted: false, dryRun: false, direction: "UNDO",
        applied: [], skipped: [item("h1", "It was changed since this edit")], error: "It was changed since this edit",
      }),
    );

    const result = await changeTrackingService.undoChangeSet("p1", "g1", { dryRun: false });

    expect(result.success).toBe(false);
    expect(result.status).toBe(409);
    expect(result.skipped[0].reason).toBe("It was changed since this edit");
    expect(result.error).toBe("It was changed since this edit");
  });

  it("returns a failed result without a body when the request fails", async () => {
    postMock.mockRejectedValueOnce(new Error("Network Error"));

    const result = await changeTrackingService.undoChangeSet("p1", "g1", { dryRun: true });

    expect(result).toMatchObject({ success: false, applied: [], skipped: [], direction: "UNDO", dryRun: true });
    expect(result.status).toBeUndefined();
  });
});

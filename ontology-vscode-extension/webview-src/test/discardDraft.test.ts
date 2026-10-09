import { describe, it, expect, vi } from "vitest";
import { discardDraftMessage, runDraftDiscard } from "../components/dashboard-parts/discardDraft";

function deps(discard: (p: string, u: string) => Promise<any>) {
  return { projectId: "p1", userId: "u1", discard: vi.fn(discard), onDiscarded: vi.fn(), onFailed: vi.fn() };
}

describe("discardDraftMessage", () => {
  it("says how many unsaved changes will be lost", () => {
    expect(discardDraftMessage(28)).toContain("all 28 unsaved draft changes");
    expect(discardDraftMessage(1)).toContain("all 1 unsaved draft change ");
  });

  it("falls back to a general wording when the count is unknown", () => {
    expect(discardDraftMessage(0)).toContain("all your draft changes");
  });

  it("warns that it can't be undone", () => {
    expect(discardDraftMessage(3)).toContain("can't be undone");
  });
});

describe("runDraftDiscard", () => {
  it("discards for the right project and user, then reports success", async () => {
    const d = deps(async () => ({ success: true, discardedCount: 5, message: "ok" }));

    const done = await runDraftDiscard(d);

    expect(done).toBe(true);
    expect(d.discard).toHaveBeenCalledWith("p1", "u1");
    expect(d.onDiscarded).toHaveBeenCalledTimes(1);
    expect(d.onFailed).not.toHaveBeenCalled();
  });

  it("shows the server's reason and changes nothing when the server declines", async () => {
    const d = deps(async () => ({ success: false, discardedCount: 0, message: "Another save operation is in progress." }));

    expect(await runDraftDiscard(d)).toBe(false);

    expect(d.onFailed).toHaveBeenCalledWith("Another save operation is in progress.");
    expect(d.onDiscarded).not.toHaveBeenCalled();
  });

  it("shows the error and changes nothing when the request fails", async () => {
    const d = deps(async () => {
      throw new Error("Network down");
    });

    expect(await runDraftDiscard(d)).toBe(false);

    expect(d.onFailed).toHaveBeenCalledWith("Network down");
    expect(d.onDiscarded).not.toHaveBeenCalled();
  });
});

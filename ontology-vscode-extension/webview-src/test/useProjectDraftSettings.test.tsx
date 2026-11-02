import { beforeEach, describe, expect, it, vi } from "vitest";
import { act } from "react";
import { flush, renderHook } from "./hookHarness";

vi.mock("../services/draftTrackingService", () => ({
  draftTrackingService: { getDraftSettings: vi.fn() },
}));

import { draftTrackingService } from "../services/draftTrackingService";
import { useProjectDraftSettings } from "../hooks/useProjectDraftSettings";

const getSettings = vi.mocked(draftTrackingService.getDraftSettings);

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

beforeEach(() => {
  getSettings.mockReset();
});

describe("useProjectDraftSettings", () => {
  it("sets the owner flag from the server for the current project", async () => {
    getSettings.mockResolvedValue({ requireDraftForMembers: true, isOwner: true });
    const h = renderHook((p: { id: string }) => useProjectDraftSettings(p.id), { id: "A" });
    await act(async () => {
      await h.result.current.loadDraftSettings("A", "u1");
    });
    expect(h.result.current.isProjectOwner).toBe(true);
    expect(h.result.current.requireDraftForMembers).toBe(true);
    h.unmount();
  });

  it("clears the flags when switching to a project where the request fails", async () => {
    getSettings.mockResolvedValueOnce({ requireDraftForMembers: true, isOwner: true });
    const h = renderHook((p: { id: string }) => useProjectDraftSettings(p.id), { id: "A" });
    await act(async () => {
      await h.result.current.loadDraftSettings("A", "u1");
    });
    expect(h.result.current.isProjectOwner).toBe(true);
    h.rerender({ id: "B" });
    expect(h.result.current.isProjectOwner).toBe(false);
    expect(h.result.current.requireDraftForMembers).toBe(false);
    getSettings.mockRejectedValueOnce(new Error("boom"));
    await act(async () => {
      await h.result.current.loadDraftSettings("B", "u1");
    });
    expect(h.result.current.isProjectOwner).toBe(false);
    h.unmount();
  });

  it("clears the flags when the new project returns isOwner false", async () => {
    getSettings.mockResolvedValueOnce({ requireDraftForMembers: false, isOwner: true });
    const h = renderHook((p: { id: string }) => useProjectDraftSettings(p.id), { id: "A" });
    await act(async () => {
      await h.result.current.loadDraftSettings("A", "u1");
    });
    h.rerender({ id: "B" });
    getSettings.mockResolvedValueOnce({ requireDraftForMembers: true, isOwner: false });
    await act(async () => {
      await h.result.current.loadDraftSettings("B", "u1");
    });
    expect(h.result.current.isProjectOwner).toBe(false);
    expect(h.result.current.requireDraftForMembers).toBe(true);
    h.unmount();
  });

  it("ignores a late response for the old project after switching", async () => {
    const late = deferred<{ requireDraftForMembers: boolean; isOwner: boolean }>();
    getSettings.mockReturnValueOnce(late.promise);
    const h = renderHook((p: { id: string }) => useProjectDraftSettings(p.id), { id: "A" });
    let pending!: Promise<unknown>;
    act(() => {
      pending = h.result.current.loadDraftSettings("A", "u1");
    });
    h.rerender({ id: "B" });
    getSettings.mockResolvedValueOnce({ requireDraftForMembers: false, isOwner: false });
    await act(async () => {
      await h.result.current.loadDraftSettings("B", "u1");
    });
    await act(async () => {
      late.resolve({ requireDraftForMembers: true, isOwner: true });
      await pending;
    });
    await flush();
    expect(await pending).toBeNull();
    expect(h.result.current.isProjectOwner).toBe(false);
    expect(h.result.current.requireDraftForMembers).toBe(false);
    h.unmount();
  });

  it("ignores a late response for the old project even when the new project never loads", async () => {
    const late = deferred<{ requireDraftForMembers: boolean; isOwner: boolean }>();
    getSettings.mockReturnValueOnce(late.promise);
    const h = renderHook((p: { id: string }) => useProjectDraftSettings(p.id), { id: "A" });
    let pending!: Promise<unknown>;
    act(() => {
      pending = h.result.current.loadDraftSettings("A", "u1");
    });
    h.rerender({ id: "B" });
    await act(async () => {
      late.resolve({ requireDraftForMembers: true, isOwner: true });
      await pending;
    });
    expect(h.result.current.isProjectOwner).toBe(false);
    h.unmount();
  });

  it("leaves the flags false when the request fails", async () => {
    getSettings.mockRejectedValue(new Error("403"));
    const h = renderHook((p: { id: string }) => useProjectDraftSettings(p.id), { id: "A" });
    let result: unknown = "unset";
    await act(async () => {
      result = await h.result.current.loadDraftSettings("A", "u1");
    });
    expect(result).toBeNull();
    expect(h.result.current.isProjectOwner).toBe(false);
    expect(h.result.current.requireDraftForMembers).toBe(false);
    h.unmount();
  });

  it("resetDraftSettings clears flags and cancels an in-flight load (shared or non-workspace)", async () => {
    const pendingReq = deferred<{ requireDraftForMembers: boolean; isOwner: boolean }>();
    getSettings.mockReturnValueOnce(pendingReq.promise);
    const h = renderHook((p: { id: string }) => useProjectDraftSettings(p.id), { id: "A" });
    let pending!: Promise<unknown>;
    act(() => {
      pending = h.result.current.loadDraftSettings("A", "u1");
    });
    act(() => h.result.current.resetDraftSettings());
    await act(async () => {
      pendingReq.resolve({ requireDraftForMembers: true, isOwner: true });
      await pending;
    });
    expect(h.result.current.isProjectOwner).toBe(false);
    expect(getSettings).toHaveBeenCalledTimes(1);
    h.unmount();
  });
});

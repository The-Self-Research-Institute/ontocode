import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../services/apiClient";
import { usePullPending } from "../hooks/usePullPending";

let root: Root;
let host: HTMLDivElement;
let latest: ReturnType<typeof usePullPending> | undefined;

function Probe({ enabled }: { enabled: boolean }) {
  latest = usePullPending("p1", "u1", enabled);
  return null;
}

const settle = () => act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });

describe("usePullPending", () => {
  beforeEach(() => {
    (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement("div");
    root = createRoot(host);
    latest = undefined;
  });

  afterEach(() => {
    act(() => root.unmount());
    vi.restoreAllMocks();
  });

  it("reports a pending pull when the server says Public moved on", async () => {
    const get = vi.spyOn(apiClient, "get").mockResolvedValue({ data: { pending: true } } as any);
    await act(async () => { root.render(<Probe enabled />); });
    await settle();
    expect(get).toHaveBeenCalledWith("/api/ontology/p1/draft/pull-status", { userId: "u1" });
    expect(latest?.pending).toBe(true);
  });

  it("reports nothing pending when the draft is up to date", async () => {
    vi.spyOn(apiClient, "get").mockResolvedValue({ data: { pending: false } } as any);
    await act(async () => { root.render(<Probe enabled />); });
    await settle();
    expect(latest?.pending).toBe(false);
  });

  it("does not call the server outside private mode", async () => {
    const get = vi.spyOn(apiClient, "get").mockResolvedValue({ data: { pending: true } } as any);
    await act(async () => { root.render(<Probe enabled={false} />); });
    await settle();
    expect(get).not.toHaveBeenCalled();
    expect(latest?.pending).toBe(false);
  });

  it("checks again when something changes in the ontology", async () => {
    const get = vi.spyOn(apiClient, "get").mockResolvedValue({ data: { pending: false } } as any);
    await act(async () => { root.render(<Probe enabled />); });
    await settle();
    get.mockResolvedValue({ data: { pending: true } } as any);
    await act(async () => { window.dispatchEvent(new CustomEvent("ontology:mutated", { detail: { projectId: "p1" } })); });
    await settle();
    expect(latest?.pending).toBe(true);
  });

  it("keeps working when the server call fails", async () => {
    vi.spyOn(apiClient, "get").mockRejectedValue(new Error("down"));
    await act(async () => { root.render(<Probe enabled />); });
    await settle();
    expect(latest?.pending).toBe(false);
  });
});

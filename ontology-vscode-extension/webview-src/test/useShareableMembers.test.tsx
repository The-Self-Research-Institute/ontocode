import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, renderHook } from "./hookHarness";

vi.mock("../services/apiClient", () => ({ default: { get: vi.fn() } }));

import apiClient from "../services/apiClient";
import { useShareableMembers } from "../hooks/useShareableMembers";

const get = vi.mocked(apiClient.get);

beforeEach(() => {
  get.mockReset();
  get.mockResolvedValue({
    data: {
      members: [
        { userId: "me", username: "Me", email: "me@x.com" },
        { userId: "1", username: "Zed", email: "zed@x.com" },
        { userId: "2", username: "Amy", email: "amy@x.com", role: "ADMIN" },
        { userId: "3", username: "Out", email: "OUT@x.com" },
      ],
    },
  });
});

describe("useShareableMembers", () => {
  it("excludes the current user and listed emails and sorts by name", async () => {
    const h = renderHook(
      () => useShareableMembers("w1", ["out@x.com"], 0, { userId: "me", email: "me@x.com" }),
      undefined,
    );
    await flush();
    expect(get).toHaveBeenCalledWith("/api/workspaces/w1");
    expect(h.result.current.workspaceMembers.map((m) => m.username)).toEqual(["Amy", "Zed"]);
    h.unmount();
  });

  it("reports an error without a workspace and does not call the API", async () => {
    const h = renderHook(() => useShareableMembers(undefined, []), undefined);
    await flush();
    expect(get).not.toHaveBeenCalled();
    expect(h.result.current.membersLoadError).toBe("Select a workspace before choosing specific members.");
    h.unmount();
  });

  it("reports a load failure", async () => {
    get.mockRejectedValue(new Error("boom"));
    vi.spyOn(console, "error").mockImplementation(() => {});
    const h = renderHook(() => useShareableMembers("w1", []), undefined);
    await flush();
    expect(h.result.current.membersLoadError).toBe("Could not load workspace members. Try again.");
    h.unmount();
  });
});

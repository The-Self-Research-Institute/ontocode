import { beforeEach, describe, expect, it, vi } from "vitest";
import React, { act } from "react";
import { flush, mount } from "./hookHarness";

vi.mock("../services/apiClient", () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));
vi.mock("../custom-hook/useAuth", () => ({
  useAuth: () => ({ user: { workspaceId: "w1", userId: "me", email: "me@x.com" } }),
}));
vi.mock("../utils/desktop", () => ({ isDesktop: () => false }));

import apiClient from "../services/apiClient";
import CreateProjectModal from "../components/CreateProjectModal";

const get = vi.mocked(apiClient.get);

beforeEach(() => {
  get.mockReset();
  get.mockResolvedValue({
    data: {
      members: [
        { userId: "1", username: "Boss", email: "boss@x.com", role: "OWNER" },
        { userId: "2", username: "Bob", email: "bob@x.com", role: "MEMBER" },
      ],
    },
  });
});

describe("CreateProjectModal", () => {
  it("renders nothing when closed", async () => {
    const h = mount();
    h.render(<CreateProjectModal isOpen={false} onClose={() => {}} onSuccess={() => {}} />);
    await flush();
    expect(h.container.innerHTML).toBe("");
    h.unmount();
  });

  it("shows the specific-members list with an Owner badge and Editor role", async () => {
    const h = mount();
    h.render(<CreateProjectModal isOpen onClose={() => {}} onSuccess={() => {}} />);
    await flush();
    expect(get).toHaveBeenCalledWith("/api/workspaces/w1");
    expect(h.container.textContent).toContain("Create New Project");
    expect(h.container.textContent).toContain("2 active workspace members");

    const specific = h.container.querySelector('input[value="specific"]') as HTMLInputElement;
    await act(async () => {
      specific.click();
    });
    await flush();
    const text = h.container.textContent || "";
    expect(text).toContain("Owner");
    expect(text).toContain("Boss");
    expect(text).toContain("Bob");
    expect(text).toContain("1 selected");
    h.unmount();
  });
});

import { beforeEach, describe, expect, it, vi } from "vitest";
import React, { act } from "react";
import { flush, mount } from "./hookHarness";

vi.mock("../services/apiClient", () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn(), put: vi.fn() },
}));
vi.mock("../custom-hook/useAuth", () => ({
  useAuth: () => ({
    user: { workspaceId: "w1", userId: "me", email: "me@x.com", username: "Me", workspaceRole: "ADMIN" },
    logout: vi.fn(),
    switchWorkspace: vi.fn(),
    refreshPermissions: vi.fn().mockResolvedValue(undefined),
  }),
}));
vi.mock("../hooks/useSubscription", () => ({
  useSubscription: () => ({
    plan: "pro",
    limits: { maxTeamMembers: 10 },
    canAccessFeature: () => true,
    isWithinLimit: () => true,
    isPro: true,
    isEnterprise: false,
    isFree: false,
  }),
}));
vi.mock("../utils/desktop", () => ({ isDesktop: () => false, prewarmDesktopReasoningServices: vi.fn() }));
vi.mock("../utils/appVersion", () => ({ getAppVersion: () => Promise.resolve("1.0.0") }));
vi.mock("../components/InviteMemberModal", () => ({ default: () => null }));
vi.mock("../components/SettingsModal", () => ({ default: () => null }));
vi.mock("../components/CreateProjectModal", () => ({ default: () => null }));
vi.mock("../components/AdminSettingsModal", () => ({ default: () => null }));
vi.mock("../components/UserGuideModal", () => ({ UserGuideModal: () => null }));
vi.mock("../components/AppVersionBadge", () => ({ AppVersionBadge: () => null }));
vi.mock("../components/OntoCodeLogo", () => ({ OntoCodeLogo: () => null }));
vi.mock("../components/PlanDetailsModal", () => ({ default: () => null }));

import apiClient from "../services/apiClient";
import ProjectDashboard from "../components/ProjectDashboard";

const get = vi.mocked(apiClient.get);

beforeEach(() => {
  get.mockReset();
  get.mockImplementation(async (url: string) => {
    if (url.startsWith("/api/projects/my")) {
      return {
        data: {
          projects: [
            {
              id: "p1",
              projectId: "p1",
              name: "Pizza",
              description: "tasty",
              ownerId: "other",
              members: [],
              memberCount: 2,
              fileCount: 1,
              updatedAt: "2026-01-01T00:00:00Z",
            },
          ],
        },
      };
    }
    return {
      data: {
        ownerId: "boss",
        name: "Team WS",
        members: [
          { userId: "boss", username: "Boss", email: "boss@x.com", role: "OWNER" },
          { userId: "me", username: "Me", email: "me@x.com", role: "ADMIN" },
        ],
      },
    };
  });
});

describe("ProjectDashboard", () => {
  it("renders projects, workspace members and role badges", async () => {
    const onSelect = vi.fn();
    const h = mount();
    h.render(<ProjectDashboard onSelectProject={onSelect} />);
    await flush();
    const text = h.container.textContent || "";
    expect(text).toContain("Team WS");
    expect(text).toContain("Pizza");
    expect(text).toContain("Workspace Members");
    expect(text).toContain("OWNER");
    expect(text).toContain("EDITOR");
    expect(text).toContain("Leave");
    expect(text).not.toContain("Make Owner");

    const card = Array.from(h.container.querySelectorAll("h3")).find((n) => n.textContent?.includes("Pizza"));
    await act(async () => {
      (card?.closest("div[title], div.cursor-pointer") as HTMLElement).click();
    });
    expect(onSelect).toHaveBeenCalledWith("p1", "Pizza");
    h.unmount();
  });

  it("shows the load error screen with retry when projects fail to load", async () => {
    get.mockReset();
    get.mockRejectedValue({ status: 500, message: "boom" });
    const h = mount();
    h.render(<ProjectDashboard onSelectProject={() => {}} />);
    await flush();
    const text = h.container.textContent || "";
    expect(text).toContain("open this workspace");
    expect(text).toContain("boom");
    expect(text).toContain("Retry");
    h.unmount();
  });
});

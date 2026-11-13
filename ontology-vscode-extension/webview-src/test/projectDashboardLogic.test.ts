import { describe, expect, it } from "vitest";
import {
  availableWorkspaceMembersForProject,
  canRemoveProjectMember,
  filterProjectsBySearch,
  isProjectMemberRoleLocked,
  mapWorkspaceMembersToTeam,
  roleBadgeStyle,
} from "../utils/projectDashboardLogic";
import type { Project, ProjectMember, TeamMember } from "../utils/projectDashboardTypes";

const member = (over: Partial<ProjectMember>): ProjectMember => ({
  userId: "u",
  username: "n",
  email: "e@x.io",
  role: "EDITOR",
  joinedAt: "",
  ...over,
});

describe("projectDashboardLogic", () => {
  it("maps workspace members, marking invitees without a user id as pending", () => {
    const out = mapWorkspaceMembersToTeam([
      { userId: "u1", username: "a", email: "a@x.io", role: "OWNER", joinedAt: "d" },
      { username: "b", email: "b@x.io", role: "MEMBER", invitationToken: "tok" },
    ]);
    expect(out[0]).toMatchObject({ id: "u1", status: "ACTIVE", roles: ["OWNER"] });
    expect(out[1]).toMatchObject({ id: "pending-b@x.io", status: "PENDING", invitationToken: "tok" });
  });

  it("filters projects by name or description", () => {
    const projects = [
      { name: "Alpha", description: "x" },
      { name: "Beta", description: "has alpha inside" },
      { name: "Gamma", description: "" },
    ] as Project[];
    expect(filterProjectsBySearch(projects, "alpha").map((p) => p.name)).toEqual(["Alpha", "Beta"]);
  });

  it("lists active workspace members not already on the project", () => {
    const team = [
      { id: "1", username: "a", email: "A@x.io", roles: [], status: "ACTIVE" },
      { id: "2", username: "b", email: "b@x.io", roles: [], status: "ACTIVE" },
      { id: "3", username: "c", email: "c@x.io", roles: [], status: "PENDING" },
    ] as TeamMember[];
    const project = { members: [member({ email: "a@x.io" })] } as Project;
    expect(availableWorkspaceMembersForProject(project, team).map((m) => m.id)).toEqual(["2"]);
    expect(availableWorkspaceMembersForProject(null, team)).toEqual([]);
  });

  it("locks and protects workspace owner and admin links on project members", () => {
    const owner = member({ workspaceEditorLink: "WORKSPACE_OWNER" });
    const admin = member({ workspaceEditorLink: "WORKSPACE_ADMIN" });
    expect(isProjectMemberRoleLocked(owner, true)).toBe(true);
    expect(isProjectMemberRoleLocked(admin, false)).toBe(true);
    expect(isProjectMemberRoleLocked(admin, true)).toBe(false);
    expect(canRemoveProjectMember(owner, true)).toBe(false);
    expect(canRemoveProjectMember(admin, false)).toBe(false);
    expect(canRemoveProjectMember(admin, true)).toBe(true);
    expect(canRemoveProjectMember(member({ role: "OWNER" }), true)).toBe(false);
  });

  it("falls back to the member badge style for unknown roles", () => {
    expect(roleBadgeStyle("owner")).toContain("yellow");
    expect(roleBadgeStyle("custom")).toBe(roleBadgeStyle("MEMBER"));
  });
});

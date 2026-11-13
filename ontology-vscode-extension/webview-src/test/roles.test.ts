import { describe, expect, it } from "vitest";
import { projectRoleSelectValue, resolveIsWorkspaceOwner, roleDisplayLabel } from "../utils/roles";

describe("roleDisplayLabel", () => {
  it("never shows Admin: an admin role is shown as Editor", () => {
    expect(roleDisplayLabel("ADMIN")).toBe("Editor");
    expect(roleDisplayLabel("admin")).toBe("Editor");
  });

  it("shows the project roles by their plain names", () => {
    expect(roleDisplayLabel("EDITOR")).toBe("Editor");
    expect(roleDisplayLabel("DRAFT_EDITOR")).toBe("Draft editor");
    expect(roleDisplayLabel("VIEWER")).toBe("Viewer");
    expect(roleDisplayLabel("OWNER")).toBe("Owner");
  });

  it("returns an empty string for a missing role", () => {
    expect(roleDisplayLabel(null)).toBe("");
    expect(roleDisplayLabel(undefined)).toBe("");
  });
});

describe("projectRoleSelectValue", () => {
  it("shows an admin member as Editor in a role dropdown", () => {
    expect(projectRoleSelectValue("ADMIN")).toBe("EDITOR");
  });

  it("keeps the other roles unchanged", () => {
    expect(projectRoleSelectValue("DRAFT_EDITOR")).toBe("DRAFT_EDITOR");
    expect(projectRoleSelectValue("viewer")).toBe("VIEWER");
  });
});

describe("resolveIsWorkspaceOwner", () => {
  it("is false for the previous owner even when the login token still says OWNER", () => {
    expect(
      resolveIsWorkspaceOwner({
        userId: "a",
        workspaceOwnerId: "b",
        teamRoles: ["ADMIN"],
        teamLoaded: true,
        tokenRole: "OWNER",
      }),
    ).toBe(false);
  });

  it("is true for the new owner by owner id", () => {
    expect(
      resolveIsWorkspaceOwner({ userId: "b", workspaceOwnerId: "b", teamRoles: ["OWNER"], teamLoaded: true, tokenRole: "MEMBER" }),
    ).toBe(true);
  });

  it("is true when the fresh member list says OWNER even if the owner id is missing", () => {
    expect(
      resolveIsWorkspaceOwner({ userId: "b", workspaceOwnerId: null, teamRoles: ["OWNER"], teamLoaded: true, tokenRole: null }),
    ).toBe(true);
  });

  it("falls back to the token only while nothing has loaded yet", () => {
    expect(resolveIsWorkspaceOwner({ userId: "a", workspaceOwnerId: null, teamRoles: [], teamLoaded: false, tokenRole: "OWNER" })).toBe(true);
    expect(resolveIsWorkspaceOwner({ userId: "a", workspaceOwnerId: null, teamRoles: [], teamLoaded: false, tokenRole: "MEMBER" })).toBe(false);
  });
});

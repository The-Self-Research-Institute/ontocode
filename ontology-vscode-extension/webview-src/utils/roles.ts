

export const WORKSPACE_ROLES = ["OWNER", "ADMIN", "MEMBER", "VIEWER"] as const;
export type WorkspaceRole = (typeof WORKSPACE_ROLES)[number];

export const PROJECT_ROLES = ["OWNER", "ADMIN", "EDITOR", "DRAFT_EDITOR", "VIEWER"] as const;
export type ProjectRole = (typeof PROJECT_ROLES)[number];

export function normalizeRole(role: string | null | undefined): string {
  return (role || "").trim().toUpperCase();
}

export function parseWorkspaceRole(
  workspaceRoleFromToken: string | null | undefined,
  teamMemberRoles?: string[] | null,
): WorkspaceRole | null {
  const fromToken = normalizeRole(workspaceRoleFromToken);
  if (fromToken && (WORKSPACE_ROLES as readonly string[]).includes(fromToken)) {
    return fromToken as WorkspaceRole;
  }
  if (teamMemberRoles?.length) {
    for (const r of teamMemberRoles) {
      const u = normalizeRole(r);
      if ((WORKSPACE_ROLES as readonly string[]).includes(u)) {
        return u as WorkspaceRole;
      }
    }
  }
  return null;
}

export function isWorkspaceOwnerRole(role: WorkspaceRole | null): boolean {
  return role === "OWNER";
}

export function isWorkspaceAdminRole(role: WorkspaceRole | null): boolean {
  return role === "ADMIN";
}

export function isWorkspaceMemberRole(role: WorkspaceRole | null): boolean {
  return role === "MEMBER";
}

export function isWorkspaceViewerRole(role: WorkspaceRole | null): boolean {
  return role === "VIEWER";
}

export function canCreateProjectsInWorkspace(workspaceRole: WorkspaceRole | null): boolean {
  if (!workspaceRole) return false;
  return workspaceRole !== "VIEWER";
}

export function canManageWorkspaceMembership(workspaceRole: WorkspaceRole | null): boolean {
  return workspaceRole === "OWNER" || workspaceRole === "ADMIN";
}

export function canAdministerAllProjectsInWorkspace(workspaceRole: WorkspaceRole | null): boolean {
  return workspaceRole === "OWNER" || workspaceRole === "ADMIN";
}

export function parseProjectRole(role: string | null | undefined): ProjectRole | null {
  const u = normalizeRole(role);
  return (PROJECT_ROLES as readonly string[]).includes(u) ? (u as ProjectRole) : null;
}

export function canEditProjectContent(
  projectRole: ProjectRole | null,
  workspaceRole: WorkspaceRole | null,
): boolean {
  if (projectRole === "OWNER" || projectRole === "ADMIN" || projectRole === "EDITOR" || projectRole === "DRAFT_EDITOR") return true;
  return canAdministerAllProjectsInWorkspace(workspaceRole);
}

export function canManageProjectSettings(
  projectRole: ProjectRole | null,
  workspaceRole: WorkspaceRole | null,
): boolean {
  return projectRole === "OWNER" || projectRole === "ADMIN" || canAdministerAllProjectsInWorkspace(workspaceRole);
}

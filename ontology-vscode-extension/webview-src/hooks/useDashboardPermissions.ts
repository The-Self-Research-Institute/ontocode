import { useMemo } from "react";
import {
  WORKSPACE_ROLES,
  type WorkspaceRole,
  normalizeRole,
  canCreateProjectsInWorkspace,
  canManageWorkspaceMembership,
  canEditProjectContent,
  canManageProjectSettings,
  parseProjectRole,
  resolveIsWorkspaceOwner,
} from "../utils/roles";
import type { AuthUser, Project, TeamMember } from "../utils/projectDashboardTypes";

export function useDashboardPermissions(
  user: AuthUser,
  teamMembers: TeamMember[],
  workspaceOwnerId: string | null,
  projectSettingsModal: Project | null,
) {
  const currentUserInTeam = useMemo(
    () => teamMembers.find((m) => m.id === user?.userId || m.email === user?.email || m.username === user?.username),
    [teamMembers, user],
  );
  const isWorkspaceOwner = resolveIsWorkspaceOwner({
    userId: user?.userId,
    workspaceOwnerId,
    teamRoles: currentUserInTeam?.roles,
    teamLoaded: teamMembers.length > 0,
    tokenRole: user?.workspaceRole,
  });

  const effectiveWorkspaceRole = useMemo((): WorkspaceRole | null => {
    if (user?.userId && workspaceOwnerId && user.userId === workspaceOwnerId) return "OWNER";
    const fromTeam = currentUserInTeam?.roles
      ?.map((r) => normalizeRole(r))
      .find((r) => (WORKSPACE_ROLES as readonly string[]).includes(r));
    if (fromTeam) return fromTeam as WorkspaceRole;
    const jwt = normalizeRole(user?.workspaceRole);
    return (WORKSPACE_ROLES as readonly string[]).includes(jwt) ? (jwt as WorkspaceRole) : null;
  }, [user?.workspaceRole, user?.userId, workspaceOwnerId, currentUserInTeam]);

  const projectRoleForUser = (project: Project | null): ReturnType<typeof parseProjectRole> => {
    if (!project || !user?.userId) return null;
    if (project.ownerId === user.userId) return "OWNER";
    const m = project.members?.find((mem) => mem.userId === user.userId);
    return parseProjectRole(m?.role);
  };

  const canEditOpenProject = projectSettingsModal
    ? canEditProjectContent(projectRoleForUser(projectSettingsModal), effectiveWorkspaceRole)
    : false;
  const canManageOpenProject = projectSettingsModal
    ? canManageProjectSettings(projectRoleForUser(projectSettingsModal), effectiveWorkspaceRole)
    : false;

  return {
    isWorkspaceOwner,
    canCreateProjects: canCreateProjectsInWorkspace(effectiveWorkspaceRole),
    canInviteMembers: canManageWorkspaceMembership(effectiveWorkspaceRole),
    projectRoleForUser,
    canEditOpenProject,
    canManageOpenProject,
    canManageProjectRow: (project: Project) =>
      canManageProjectSettings(projectRoleForUser(project), effectiveWorkspaceRole),
  };
}

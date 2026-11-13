import apiClient from "../services/apiClient";
import type { Project, ProjectMember, TeamMember } from "./projectDashboardTypes";

export const mapWorkspaceMembersToTeam = (members: any[]): TeamMember[] =>
  members.map((member: any) => {
    const isPending = member.status === "PENDING" || (!member.userId && member.invitationToken);
    return {
      id: member.userId || `pending-${member.email}`,
      username: member.username,
      email: member.email,
      roles: [member.role],
      lastLoginAt: member.joinedAt,
      status: isPending ? "PENDING" : "ACTIVE",
      invitationToken: member.invitationToken,
    };
  });

export const filterProjectsBySearch = (projects: Project[], searchQuery: string): Project[] =>
  projects.filter(
    (project) =>
      project.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      project.description.toLowerCase().includes(searchQuery.toLowerCase()),
  );

export const availableWorkspaceMembersForProject = (
  project: Project | null,
  teamMembers: TeamMember[],
): TeamMember[] => {
  if (!project) return [];
  const projectMemberEmails = new Set(project.members?.map((m) => m.email.toLowerCase()) || []);
  return teamMembers.filter(
    (member) => member.status === "ACTIVE" && !projectMemberEmails.has(member.email.toLowerCase()),
  );
};

export const isProjectMemberRoleLocked = (member: ProjectMember, isWorkspaceOwner: boolean) =>
  member.workspaceEditorLink === "WORKSPACE_OWNER" ||
  (member.workspaceEditorLink === "WORKSPACE_ADMIN" && !isWorkspaceOwner);

export const canRemoveProjectMember = (member: ProjectMember, isWorkspaceOwner: boolean) =>
  member.role !== "OWNER" &&
  member.workspaceEditorLink !== "WORKSPACE_OWNER" &&
  (member.workspaceEditorLink !== "WORKSPACE_ADMIN" || isWorkspaceOwner);

export const ROLE_BADGE_STYLES = {
  OWNER: "bg-yellow-500/20 text-yellow-500 border-yellow-500/30",
  ADMIN: "bg-purple-500/20 text-purple-500 border-purple-500/30",
  MEMBER: "bg-blue-500/20 text-blue-500 border-blue-500/30",
  VIEWER: "bg-gray-500/20 text-gray-500 border-gray-500/30",
};

export const roleBadgeStyle = (role: string) =>
  ROLE_BADGE_STYLES[role.toUpperCase() as keyof typeof ROLE_BADGE_STYLES] || ROLE_BADGE_STYLES.MEMBER;

export const fetchProjectDetails = async (projectId: string): Promise<Project> => {
  const response = await apiClient.get(`/api/projects/${projectId}`);
  return response?.data?.project || response?.project || response;
};

export const apiErrorMessage = (error: any, fallback: string) =>
  error?.error || error?.response?.data?.error || error?.message || fallback;

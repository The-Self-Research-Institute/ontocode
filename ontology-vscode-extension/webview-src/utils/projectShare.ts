import { roleDisplayLabel } from "./roles";

export interface WorkspaceMember {
  userId: string;
  username: string;
  email: string;
  role?: string;
  status?: string;
}

export type ProjectShareRole = "VIEWER" | "DRAFT_EDITOR" | "EDITOR";
export type ShareWith = "none" | "all" | "specific";

export interface ShareMemberSelection {
  email: string;
  role: ProjectShareRole;
}

export interface ShareSelection {
  shareWith: ShareWith;
  members: ShareMemberSelection[];
}

export const EMPTY_SHARE_SELECTION: ShareSelection = { shareWith: "none", members: [] };

export const shareSelectionForVisibility = (visibility?: string, hasOtherMembers = false): ShareSelection => {
  if (visibility === "WORKSPACE") return { shareWith: "all", members: [] };
  if (visibility === "SPECIFIC" || hasOtherMembers) return { shareWith: "specific", members: [] };
  return EMPTY_SHARE_SELECTION;
};

export const MEMBER_SEARCH_THRESHOLD = 5;

export const normalizeEmail = (email?: string | null) => (email || "").trim().toLowerCase();

export const isActiveWorkspaceMember = (member: WorkspaceMember) =>
  String(member.status || "ACTIVE").toUpperCase() === "ACTIVE" &&
  !!member.userId &&
  !!member.email;

export const isPrivilegedMember = (member: WorkspaceMember) =>
  member.role?.toUpperCase() === "OWNER" || member.role?.toUpperCase() === "ADMIN";

export const privilegedBadgeLabel = (member: WorkspaceMember) =>
  roleDisplayLabel(member.role?.toUpperCase() === "OWNER" ? "OWNER" : "EDITOR");

export function prepareShareableMembers(
  members: WorkspaceMember[],
  currentUserId?: string | null,
  currentUserEmail?: string | null,
  excludeEmails: Set<string> = new Set(),
): WorkspaceMember[] {
  const currentEmail = normalizeEmail(currentUserEmail);
  return members
    .filter((member) => isActiveWorkspaceMember(member))
    .filter((member) => {
      if (currentUserId && member.userId === currentUserId) return false;
      if (currentEmail && normalizeEmail(member.email) === currentEmail) return false;
      return !excludeEmails.has(normalizeEmail(member.email));
    })
    .map((member) => ({
      ...member,
      email: member.email.trim(),
      username: member.username || member.email.split("@")[0],
    }))
    .sort((a, b) => a.username.localeCompare(b.username, undefined, { sensitivity: "base" }));
}

export function filterVisibleMembers(members: WorkspaceMember[], search: string): WorkspaceMember[] {
  const query = search.trim().toLowerCase();
  const filtered = query
    ? members.filter(
        (member) =>
          member.username.toLowerCase().includes(query) ||
          member.email.toLowerCase().includes(query),
      )
    : members;
  const privileged = filtered.filter(isPrivilegedMember);
  const regular = filtered.filter((m) => !isPrivilegedMember(m));
  return [...privileged, ...regular];
}

export function extractCreateProjectError(error: any): string {
  return (
    error?.data?.error ||
    error?.data?.message ||
    error?.response?.data?.error ||
    error?.response?.data?.message ||
    error?.message ||
    "Failed to create project. Please try again."
  );
}

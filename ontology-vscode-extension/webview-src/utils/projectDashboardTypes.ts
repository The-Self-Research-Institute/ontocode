import type { Toast } from "../components/ToastNotification";
import type { useAuth } from "../custom-hook/useAuth";
import type { useSubscription } from "../hooks/useSubscription";

export type AuthUser = ReturnType<typeof useAuth>["user"];
export type SubscriptionInfo = ReturnType<typeof useSubscription>;

export interface ProjectMember {
  userId: string;
  username: string;
  email: string;
  role: string;
  joinedAt: string;
  workspaceEditorLink?: string | null;
}

export interface Project {
  id: string;
  projectId: string;
  name: string;
  description: string;
  workspaceId: string;
  ownerId: string;
  members: ProjectMember[];
  memberCount: number;
  status: string;
  tags: string[];
  fileCount: number;
  createdAt: string;
  updatedAt: string;
  visibility?: string;
  isPrivateRestricted?: boolean;
}

export interface TeamMember {
  id: string;
  username: string;
  email: string;
  roles: string[];
  lastLoginAt?: string;
  status?: "ACTIVE" | "PENDING";
  invitationToken?: string;
}

export type ToastType = Toast["type"];
export type ShowToast = (message: string, type?: ToastType) => void;

export interface ConfirmOptions {
  title: string;
  message: string;
  onConfirm: () => void;
  confirmText?: string;
  cancelText?: string;
  type?: "danger" | "warning" | "info";
}

export type ShowConfirm = (options: ConfirmOptions) => void;
export type ProjectSettingsTab = "general" | "members" | "danger";

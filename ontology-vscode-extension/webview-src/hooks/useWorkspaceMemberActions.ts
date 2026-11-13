import apiClient from "../services/apiClient";
import type { AuthUser, ShowConfirm, ShowToast, TeamMember } from "../utils/projectDashboardTypes";
import { apiErrorMessage } from "../utils/projectDashboardLogic";

interface Deps {
  user: AuthUser;
  switchWorkspace: () => void;
  showToast: ShowToast;
  showConfirm: ShowConfirm;
  showConfirmDialog: (title: string, message: string, confirmLabel?: string) => Promise<boolean>;
  closeConfirm: () => void;
  loadData: () => Promise<void>;
}

export function useWorkspaceMemberActions(deps: Deps) {
  const { user, switchWorkspace, showToast, showConfirm, showConfirmDialog, closeConfirm, loadData } = deps;

  const handleCancelInvitation = async (member: TeamMember) => {
    if (!member.invitationToken) {
      showToast("Cannot cancel invitation: Token not found", "error");
      return;
    }
    showConfirm({
      title: "Cancel Invitation",
      message: `Are you sure you want to cancel the invitation for ${member.email}? The invitation link will no longer work.`,
      type: "warning",
      confirmText: "Cancel Invitation",
      onConfirm: async () => {
        try {
          await apiClient.delete(`/api/invitations/${member.invitationToken}`);
          showToast(`Invitation for ${member.email} has been cancelled successfully.`, "success");
          await loadData();
        } catch (error: any) {
          console.error("[ProjectDashboard] Error cancelling invitation:", error);
          showToast(`Failed to cancel invitation: ${apiErrorMessage(error, "Failed to cancel invitation")}`, "error");
        }
        closeConfirm();
      },
    });
  };

  const handleRemoveMember = async (member: TeamMember) => {
    if (member.email === user?.email) {
      if (member.roles.some((r) => r.toUpperCase() === "OWNER")) {
        showToast("Workspace owners cannot leave. Transfer ownership or delete the workspace.", "warning");
        return;
      }
      const confirmed = await showConfirmDialog(
        "Leave workspace?",
        "You will lose access to this workspace and its projects. You can rejoin if invited again.",
        "Leave workspace",
      );
      if (!confirmed || !user?.workspaceId) return;
      try {
        await apiClient.post(`/api/workspaces/${user.workspaceId}/leave`);
        showToast("You have left the workspace", "success");
        switchWorkspace();
      } catch (error: any) {
        showToast(error?.error || error?.message || "Failed to leave workspace", "error");
      }
      return;
    }

    if (member.roles.some((r) => r.toUpperCase() === "OWNER")) {
      showToast("Cannot remove workspace owner. Please transfer ownership or delete the workspace.", "error");
      return;
    }

    if (member.status === "PENDING") {
      handleCancelInvitation(member);
      return;
    }

    showConfirm({
      title: "Remove Workspace Member",
      message: `Are you sure you want to remove ${member.username} (${member.email}) from this workspace? This action cannot be undone.`,
      type: "danger",
      confirmText: "Remove",
      onConfirm: async () => {
        try {
          await apiClient.delete(`/api/workspaces/${user?.workspaceId}/members/${encodeURIComponent(member.id)}`);
          showToast(`${member.username} has been removed from the workspace successfully.`, "success");
          await loadData();
        } catch (error: any) {
          console.error("Error removing member:", error);
          showToast(`Failed to remove member: ${apiErrorMessage(error, "Failed to remove member")}`, "error");
        }
        closeConfirm();
      },
    });
  };

  const handleTransferWorkspaceOwnership = async (member: TeamMember) => {
    if (!user?.workspaceId) return;
    showConfirm({
      title: "Transfer Ownership",
      message: `Make ${member.username} (${member.email}) the owner of this workspace? You will no longer be the owner and will not be able to delete this workspace or transfer its ownership.`,
      type: "warning",
      confirmText: "Transfer Ownership",
      onConfirm: async () => {
        try {
          await apiClient.put(`/api/workspaces/${user.workspaceId}/members/${member.id}/role`, {
            role: "OWNER",
          });
          showToast(`${member.username} is now the owner of this workspace`, "success");
          await loadData();
        } catch (error: any) {
          console.error("Error transferring workspace ownership:", error);
          showToast(apiErrorMessage(error, "Failed to transfer ownership"), "error");
        }
        closeConfirm();
      },
    });
  };

  return { handleRemoveMember, handleTransferWorkspaceOwnership, handleCancelInvitation };
}

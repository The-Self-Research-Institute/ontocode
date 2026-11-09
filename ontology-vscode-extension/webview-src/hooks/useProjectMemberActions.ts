import apiClient from "../services/apiClient";
import type { Project, ProjectMember, ShowConfirm, ShowToast } from "../utils/projectDashboardTypes";
import { fetchProjectDetails } from "../utils/projectDashboardLogic";

interface Deps {
  showToast: ShowToast;
  showConfirm: ShowConfirm;
  closeConfirm: () => void;
  loadData: () => Promise<void>;
  setProjectSettingsModal: (project: Project | null) => void;
}

export function useProjectMemberActions({
  showToast,
  showConfirm,
  closeConfirm,
  loadData,
  setProjectSettingsModal,
}: Deps) {
  const handleRemoveProjectMember = (project: Project, member: ProjectMember) => {
    showConfirm({
      title: "Remove Project Member",
      message: `Remove ${member.username} (${member.email}) from "${project.name}"? They will lose access to this project.`,
      type: "warning",
      confirmText: "Remove",
      onConfirm: async () => {
        try {
          await apiClient.delete(`/api/projects/${project.projectId}/members/${member.userId}`);
          showToast(`${member.username} has been removed from the project`, "success");
          setProjectSettingsModal(await fetchProjectDetails(project.projectId));
          loadData();
        } catch (error: any) {
          console.error("Error removing project member:", error);
          showToast(error?.error || error?.message || "Failed to remove member", "error");
        }
        closeConfirm();
      },
    });
  };

  const applyProjectMemberRoleUpdate = async (project: Project, member: ProjectMember, newRole: string) => {
    try {
      await apiClient.patch(`/api/projects/${project.projectId}/members/${member.userId}/role`, {
        role: newRole,
      });
      showToast(
        newRole === "OWNER"
          ? `${member.username} is now the owner of "${project.name}"`
          : `${member.username}'s role updated to ${newRole}`,
        "success",
      );
      setProjectSettingsModal(await fetchProjectDetails(project.projectId));
      loadData();
    } catch (error: any) {
      console.error("Error updating member role:", error);
      showToast(error?.error || error?.message || "Failed to update member role", "error");
    }
  };

  const handleUpdateProjectMemberRole = async (project: Project, member: ProjectMember, newRole: string) => {
    if (newRole === "OWNER") {
      showConfirm({
        title: "Transfer Ownership",
        message: `Make ${member.username} (${member.email}) the owner of "${project.name}"? You will no longer be the owner and will not be able to delete this project or transfer its ownership.`,
        type: "warning",
        confirmText: "Transfer Ownership",
        onConfirm: async () => {
          await applyProjectMemberRoleUpdate(project, member, newRole);
          closeConfirm();
        },
      });
      return;
    }
    await applyProjectMemberRoleUpdate(project, member, newRole);
  };

  return { handleRemoveProjectMember, handleUpdateProjectMemberRole };
}

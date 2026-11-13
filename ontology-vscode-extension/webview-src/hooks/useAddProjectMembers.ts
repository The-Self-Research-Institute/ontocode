import { useState } from "react";
import apiClient from "../services/apiClient";
import type { Project, ShowToast, TeamMember } from "../utils/projectDashboardTypes";
import { availableWorkspaceMembersForProject, fetchProjectDetails } from "../utils/projectDashboardLogic";
import { EMPTY_SHARE_SELECTION, type ShareSelection } from "../utils/projectShare";

interface Deps {
  projectSettingsModal: Project | null;
  setProjectSettingsModal: (project: Project | null) => void;
  teamMembers: TeamMember[];
  showToast: ShowToast;
  loadData: () => Promise<void>;
}

export function useAddProjectMembers({
  projectSettingsModal,
  setProjectSettingsModal,
  teamMembers,
  showToast,
  loadData,
}: Deps) {
  const [showAddMemberForm, setShowAddMemberForm] = useState(false);
  const [addMemberSelection, setAddMemberSelection] = useState<ShareSelection>(EMPTY_SHARE_SELECTION);
  const [addMemberSelectorKey, setAddMemberSelectorKey] = useState(0);
  const [addingMembers, setAddingMembers] = useState(false);

  const handleAddSelectedMembers = async () => {
    if (!projectSettingsModal) return;
    if (addMemberSelection.shareWith === "none") {
      setShowAddMemberForm(false);
      return;
    }

    const membersToAdd =
      addMemberSelection.shareWith === "all"
        ? availableWorkspaceMembersForProject(projectSettingsModal, teamMembers).map((m) => ({
            email: m.email,
            role: "VIEWER" as const,
          }))
        : addMemberSelection.members;

    if (membersToAdd.length === 0) {
      showToast("Select at least one member to add, or choose a different sharing option.", "warning");
      return;
    }

    try {
      setAddingMembers(true);

      const results: PromiseSettledResult<any>[] = [];
      for (const m of membersToAdd) {
        try {
          const res = await apiClient.post(`/api/projects/${projectSettingsModal.projectId}/members`, {
            email: m.email,
            role: m.role,
          });
          results.push({ status: "fulfilled", value: res });
        } catch (err) {
          results.push({ status: "rejected", reason: err });
        }
      }
      const failures = results.filter((r) => r.status === "rejected").length;
      if (failures === 0) {
        showToast(
          membersToAdd.length === 1
            ? "Access shared with 1 member successfully"
            : `Access shared with ${membersToAdd.length} members successfully`,
          "success",
        );
      } else {
        showToast(
          `Shared access with ${membersToAdd.length - failures} of ${membersToAdd.length} members. ${failures} failed.`,
          "warning",
        );
      }

      setAddMemberSelection(EMPTY_SHARE_SELECTION);
      setAddMemberSelectorKey((k) => k + 1);
      setShowAddMemberForm(false);

      setProjectSettingsModal(await fetchProjectDetails(projectSettingsModal.projectId));
      loadData();
    } catch (error: any) {
      console.error("Error adding project member(s):", error);
      showToast(error?.error || error?.message || "Failed to add members", "error");
    } finally {
      setAddingMembers(false);
    }
  };

  return {
    showAddMemberForm,
    setShowAddMemberForm,
    addMemberSelection,
    setAddMemberSelection,
    addMemberSelectorKey,
    addingMembers,
    handleAddSelectedMembers,
  };
}

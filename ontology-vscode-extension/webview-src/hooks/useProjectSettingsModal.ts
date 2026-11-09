import { useState } from "react";
import apiClient from "../services/apiClient";
import type { Project, ProjectSettingsTab, ShowToast } from "../utils/projectDashboardTypes";

export function useProjectSettingsModal(showToast: ShowToast, loadData: () => Promise<void>) {
  const [projectSettingsModal, setProjectSettingsModal] = useState<Project | null>(null);
  const [editingProjectName, setEditingProjectName] = useState("");
  const [editingProjectDescription, setEditingProjectDescription] = useState("");
  const [savingProject, setSavingProject] = useState(false);
  const [projectSettingsTab, setProjectSettingsTab] = useState<ProjectSettingsTab>("general");

  const openProjectSettings = (project: Project) => {
    setProjectSettingsModal(project);
    setEditingProjectName(project.name);
    setEditingProjectDescription(project.description || "");
    setProjectSettingsTab("general");
  };

  const handleSaveProjectSettings = async () => {
    if (!projectSettingsModal) return;

    try {
      setSavingProject(true);

      if (editingProjectName.trim() !== projectSettingsModal.name) {
        await apiClient.patch(`/api/projects/${projectSettingsModal.projectId}/rename`, {
          name: editingProjectName.trim(),
        });
      }

      if (editingProjectDescription !== (projectSettingsModal.description || "")) {
        await apiClient.patch(`/api/projects/${projectSettingsModal.projectId}`, {
          description: editingProjectDescription,
        });
      }

      showToast("Project settings saved successfully", "success");
      setProjectSettingsModal(null);
      loadData();
    } catch (error: any) {
      console.error("Error saving project settings:", error);
      showToast(error?.error || error?.message || "Failed to save project settings", "error");
    } finally {
      setSavingProject(false);
    }
  };

  return {
    projectSettingsModal,
    setProjectSettingsModal,
    editingProjectName,
    setEditingProjectName,
    editingProjectDescription,
    setEditingProjectDescription,
    savingProject,
    projectSettingsTab,
    setProjectSettingsTab,
    openProjectSettings,
    handleSaveProjectSettings,
  };
}

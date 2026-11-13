import { useState } from "react";
import apiClient from "../services/apiClient";
import type { Project, ShowConfirm, ShowToast } from "../utils/projectDashboardTypes";

interface Deps {
  showToast: ShowToast;
  showConfirm: ShowConfirm;
  closeConfirm: () => void;
  loadData: () => Promise<void>;
  closeProjectSettings: () => void;
}

export function useProjectActions({ showToast, showConfirm, closeConfirm, loadData, closeProjectSettings }: Deps) {
  const [renaming, setRenaming] = useState<{ projectId: string; currentName: string } | null>(null);
  const [newName, setNewName] = useState("");

  const handleRenameProject = async () => {
    if (!renaming || !newName.trim()) return;

    try {
      await apiClient.patch(`/api/projects/${renaming.projectId}/rename`, {
        name: newName.trim(),
      });

      setRenaming(null);
      setNewName("");
      loadData();
    } catch (error) {
      console.error("Error renaming project:", error);
      showToast("Failed to rename project", "error");
    }
  };

  const startRename = (projectId: string, currentName: string) => {
    setRenaming({ projectId, currentName });
    setNewName(currentName);
  };

  const cancelRename = () => {
    setRenaming(null);
    setNewName("");
  };

  const handleDeleteProject = (project: Project) => {
    showConfirm({
      title: "Delete Project",
      message: `Are you sure you want to delete "${project.name}"? This will permanently delete all files and data associated with this project. This action cannot be undone.`,
      type: "danger",
      confirmText: "Delete Project",
      onConfirm: async () => {
        try {
          await apiClient.delete(`/api/projects/${project.projectId}`);
          showToast(`Project "${project.name}" has been deleted`, "success");
          closeProjectSettings();
          loadData();
        } catch (error: any) {
          console.error("Error deleting project:", error);
          showToast(error?.error || error?.message || "Failed to delete project", "error");
        }
        closeConfirm();
      },
    });
  };

  return { renaming, newName, setNewName, handleRenameProject, startRename, cancelRename, handleDeleteProject };
}

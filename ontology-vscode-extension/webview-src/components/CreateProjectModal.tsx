import React, { useState, useEffect } from "react";
import { X, AlertCircle } from "lucide-react";
import apiClient from "../services/apiClient";
import { useAuth } from "../custom-hook/useAuth";
import { validateProjectName, validateDescription } from "../utils/validation";
import { isDesktop } from "../utils/desktop";
import { EMPTY_SHARE_SELECTION, extractCreateProjectError, type ShareSelection } from "../utils/projectShare";
import FreePlanDialog from "./createProject/FreePlanDialog";
import ShareWithSelector from "./shareMembers/ShareWithSelector";

interface CreateProjectModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

const CreateProjectModal: React.FC<CreateProjectModalProps> = ({ isOpen, onClose, onSuccess }) => {
  const { user } = useAuth();
  const desktop = isDesktop();
  const [projectName, setProjectName] = useState("");
  const [description, setDescription] = useState("");
  const [creating, setCreating] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [showFreePlanDialog, setShowFreePlanDialog] = useState(false);
  const [selection, setSelection] = useState<ShareSelection>(EMPTY_SHARE_SELECTION);
  const { shareWith, members: selectedMembers } = selection;

  useEffect(() => {
    if (!isOpen) setErrorMessage(null);
    setSelection(EMPTY_SHARE_SELECTION);
  }, [isOpen]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMessage(null);

    const workspaceId =
      user?.workspaceId || (desktop ? "desktop-workspace-local" : undefined);
    if (!workspaceId) {
      setErrorMessage("No workspace selected. Please select a workspace before creating a project.");
      return;
    }

    const nameValidation = validateProjectName(projectName);
    if (!nameValidation.isValid) {
      setErrorMessage(nameValidation.error || "Invalid project name.");
      return;
    }

    if (description) {
      const descValidation = validateDescription(description);
      if (!descValidation.isValid) {
        setErrorMessage(descValidation.error || "Invalid description.");
        return;
      }
    }

    if (!desktop && shareWith === "specific" && selectedMembers.length === 0) {
      setErrorMessage("Please select at least one member to share with, or choose a different sharing option.");
      return;
    }

    try {
      setCreating(true);

      const checkResponse = await apiClient.get(
        `/api/projects/check?name=${encodeURIComponent(projectName.trim())}&workspaceId=${workspaceId}`,
      );

      if (checkResponse?.data?.exists || checkResponse?.exists) {
        setErrorMessage(`A project named "${projectName.trim()}" already exists in this workspace. Please try a different name.`);
        setCreating(false);
        return;
      }

      const payload: any = {
        workspaceId,
        name: projectName.trim(),
        description: description.trim(),
        shareWith: desktop || shareWith === "none" ? null : shareWith,
      };

      if (!desktop && shareWith === "specific" && selectedMembers.length > 0) {
        payload.memberAccess = selectedMembers;
      }

      await apiClient.post("/api/projects", payload);

      setProjectName("");
      setDescription("");
      setSelection(EMPTY_SHARE_SELECTION);
      setErrorMessage(null);
      onSuccess();
      onClose();
    } catch (error: any) {
      console.error("Error creating project:", error);
      if (error?.status === 403 && error?.data?.requiresUpgrade) {
        setShowFreePlanDialog(true);
        return;
      }
      setErrorMessage(extractCreateProjectError(error));
    } finally {
      setCreating(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-xl shadow-xl max-w-2xl w-full max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between p-6 border-b border-gray-200">
          <h2 className="text-2xl font-bold text-gray-900">Create New Project</h2>
          <button onClick={onClose} className="p-2 hover:bg-gray-100 rounded-lg transition-colors">
            <X size={20} />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-6">
          {errorMessage && (
            <div className="flex items-start gap-2 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">
              <AlertCircle size={16} className="flex-shrink-0 mt-0.5" />
              <span>{errorMessage}</span>
            </div>
          )}

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">Project Name *</label>
            <input
              type="text"
              value={projectName}
              onChange={(e) => { setProjectName(e.target.value); setErrorMessage(null); }}
              placeholder="Enter project name"
              maxLength={255}
              className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
              required
            />
            <p className="text-xs text-gray-500 mt-1">{projectName.length}/255 characters</p>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">Description</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Enter project description"
              rows={3}
              maxLength={1000}
              className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent resize-none"
            />
            <div className="flex justify-between text-xs text-gray-500 mt-1">
              <span>Describe your project (optional)</span>
              <span className={description.length > 900 ? "text-orange-500 font-medium" : ""}>
                {description.length}/1000 characters
              </span>
            </div>
          </div>

          {!desktop && (
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-3">Share with</label>
              <ShareWithSelector
                workspaceId={user?.workspaceId ?? undefined}
                currentUser={user}
                autoIncludePrivileged
                selection={selection}
                onSelectionChange={setSelection}
                noneDescription="Start with a project only you can open"
                allDescriptionSuffix=". People who join the workspace later are added as Viewers too."
              />
            </div>
          )}

          <div className="flex justify-end gap-3 pt-4 border-t border-gray-200">
            <button
              type="button"
              onClick={onClose}
              className="px-6 py-2 text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={creating}
              className="px-6 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors disabled:opacity-50"
            >
              {creating ? "Creating..." : "Create Project"}
            </button>
          </div>
        </form>
      </div>

      {showFreePlanDialog && <FreePlanDialog onClose={() => setShowFreePlanDialog(false)} />}
    </div>
  );
};

export default CreateProjectModal;

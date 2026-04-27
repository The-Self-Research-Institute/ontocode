import React, { useState, useEffect } from "react";
import { X, Users, Check } from "lucide-react";
import apiClient from "../services/apiClient";
import { useAuth } from "../custom-hook/useAuth";
import { validateProjectName, validateDescription } from "../utils/validation";

interface CreateProjectModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

interface WorkspaceMember {
  userId: string;
  username: string;
  email: string;
}

const CreateProjectModal: React.FC<CreateProjectModalProps> = ({ isOpen, onClose, onSuccess }) => {
  const { user } = useAuth();
  const [projectName, setProjectName] = useState("");
  const [description, setDescription] = useState("");
  const [shareWith, setShareWith] = useState<"none" | "all" | "specific">("all");
  const [workspaceMembers, setWorkspaceMembers] = useState<WorkspaceMember[]>([]);
  const [selectedMembers, setSelectedMembers] = useState<string[]>([]);
  const [creating, setCreating] = useState(false);
  const [loadingMembers, setLoadingMembers] = useState(false);

  useEffect(() => {
    if (isOpen && shareWith === "specific") {
      loadWorkspaceMembers();
    }
  }, [isOpen, shareWith]);

  const loadWorkspaceMembers = async () => {
    try {
      setLoadingMembers(true);
      // Get workspace members from workspace endpoint
      const response = await apiClient.get(`/api/workspaces/${user?.workspaceId}`);
      console.log("Workspace response:", response);

      // Backend returns workspace data directly in response.data
      const workspaceData = response?.data || response;
      const members = workspaceData?.members || [];

      // Filter out the current user (project creator/owner) - they always have OWNER access
      const filteredMembers = members.filter((m: WorkspaceMember) => m.userId !== user?.id && m.email !== user?.email);
      console.log("Loaded workspace members (excluding owner):", filteredMembers);
      setWorkspaceMembers(filteredMembers);
    } catch (error) {
      console.error("Error loading workspace members:", error);
      setWorkspaceMembers([]);
    } finally {
      setLoadingMembers(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    // Check workspace context first
    if (!user?.workspaceId) {
      alert("⚠️ No Workspace Selected\n\nPlease select a workspace before creating a project.");
      return;
    }

    // Validate project name
    const nameValidation = validateProjectName(projectName);
    if (!nameValidation.isValid) {
      alert(`❌ Invalid Project Name\n\n${nameValidation.error}`);
      return;
    }

    // Validate description (optional, but if provided must be valid)
    if (description) {
      const descValidation = validateDescription(description);
      if (!descValidation.isValid) {
        alert(`❌ Invalid Description\n\n${descValidation.error}`);
        return;
      }
    }

    // Validate member selection for specific sharing
    if (shareWith === "specific" && selectedMembers.length === 0) {
      alert(
        "⚠️ No Members Selected\n\nPlease select at least one member to share with, or choose a different sharing option.",
      );
      return;
    }

    try {
      setCreating(true);

      // Check if project name already exists in workspace
      const checkResponse = await apiClient.get(
        `/api/projects/check?name=${encodeURIComponent(projectName.trim())}&workspaceId=${user?.workspaceId || "default"}`,
      );

      if (checkResponse?.data?.exists || checkResponse?.exists) {
        const data = checkResponse?.data || checkResponse;
        alert(
          `⚠️ Project Already Exists\n\n` +
            `A project named "${projectName.trim()}" already exists in this workspace.\n\n` +
            `Please try a different name.`,
        );
        setCreating(false);
        return;
      }

      // Prepare request payload
      const payload: any = {
        workspaceId: user?.workspaceId || "default",
        name: projectName.trim(),
        description: description.trim(),
        shareWith: shareWith === "none" ? null : shareWith,
      };

      // Add member usernames only when shareWith is 'specific' and members are selected
      if (shareWith === "specific" && selectedMembers.length > 0) {
        payload.memberUsernames = selectedMembers;
      }

      console.log("Creating project with payload:", payload);

      await apiClient.post("/api/projects", payload);

      setProjectName("");
      setDescription("");
      setShareWith("none");
      setSelectedMembers([]);
      onSuccess();
      onClose();
    } catch (error) {
      console.error("Error creating project:", error);
      alert("Failed to create project");
    } finally {
      setCreating(false);
    }
  };

  const toggleMember = (username: string) => {
    setSelectedMembers((prev) => (prev.includes(username) ? prev.filter((m) => m !== username) : [...prev, username]));
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
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">Project Name *</label>
            <input
              type="text"
              value={projectName}
              onChange={(e) => setProjectName(e.target.value)}
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

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-3">Share with</label>
            <div className="space-y-3">
              <label className="flex items-start gap-3 p-3 border border-gray-200 rounded-lg cursor-pointer hover:bg-gray-50">
                <input
                  type="radio"
                  name="shareWith"
                  value="none"
                  checked={shareWith === "none"}
                  onChange={() => setShareWith("none")}
                  className="mt-0.5"
                />
                <div>
                  <div className="font-medium text-gray-900">Private</div>
                  <div className="text-sm text-gray-500">Only you can access this project</div>
                </div>
              </label>

              <label className="flex items-start gap-3 p-3 border border-gray-200 rounded-lg cursor-pointer hover:bg-gray-50">
                <input
                  type="radio"
                  name="shareWith"
                  value="all"
                  checked={shareWith === "all"}
                  onChange={() => setShareWith("all")}
                  className="mt-0.5"
                />
                <div>
                  <div className="font-medium text-gray-900 flex items-center gap-2">
                    <Users size={16} />
                    All Workspace Members
                  </div>
                  <div className="text-sm text-gray-500">All members will have view access</div>
                </div>
              </label>

              <label className="flex items-start gap-3 p-3 border border-gray-200 rounded-lg cursor-pointer hover:bg-gray-50">
                <input
                  type="radio"
                  name="shareWith"
                  value="specific"
                  checked={shareWith === "specific"}
                  onChange={() => setShareWith("specific")}
                  className="mt-0.5"
                />
                <div className="flex-1">
                  <div className="font-medium text-gray-900 flex items-center gap-2">
                    Specific Members
                    {shareWith === "specific" && selectedMembers.length > 0 && (
                      <span className="px-2 py-0.5 bg-purple-100 text-purple-700 text-xs rounded-full">
                        {selectedMembers.length} selected
                      </span>
                    )}
                  </div>
                  <div className="text-sm text-gray-500 mb-2">Choose who can view this project</div>

                  {shareWith === "specific" && (
                    <div className="mt-3 space-y-2 max-h-48 overflow-y-auto border border-gray-200 rounded p-2">
                      {loadingMembers ? (
                        <div className="text-center text-sm text-gray-500 py-4">Loading members...</div>
                      ) : workspaceMembers.length === 0 ? (
                        <div className="text-center text-sm text-gray-500 py-4">No other members in workspace</div>
                      ) : (
                        workspaceMembers
                          .map((member) => (
                            <label
                              key={member.userId}
                              className="flex items-center gap-2 p-2 hover:bg-gray-50 rounded cursor-pointer"
                            >
                              <input
                                type="checkbox"
                                checked={selectedMembers.includes(member.username)}
                                onChange={() => toggleMember(member.username)}
                                className="rounded text-purple-600"
                              />
                              <div className="text-sm">
                                <div className="font-medium text-gray-900">{member.username}</div>
                                <div className="text-gray-500 text-xs">{member.email}</div>
                              </div>
                            </label>
                          ))
                      )}
                    </div>
                  )}
                </div>
              </label>
            </div>
          </div>

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
    </div>
  );
};

export default CreateProjectModal;

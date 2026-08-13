import React, { useState, useEffect, useMemo } from "react";
import { X, Users, AlertCircle, Search } from "lucide-react";
import apiClient from "../services/apiClient";
import { useAuth } from "../custom-hook/useAuth";
import { validateProjectName, validateDescription } from "../utils/validation";
import { isDesktop } from "../utils/desktop";

interface CreateProjectModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

interface WorkspaceMember {
  userId: string;
  username: string;
  email: string;
  role?: string;
  status?: string;
}

const MEMBER_SEARCH_THRESHOLD = 5;
type ProjectShareRole = "VIEWER" | "DRAFT_EDITOR" | "EDITOR";

const normalizeEmail = (email?: string | null) => (email || "").trim().toLowerCase();

const isActiveWorkspaceMember = (member: WorkspaceMember) =>
  String(member.status || "ACTIVE").toUpperCase() === "ACTIVE" &&
  !!member.userId &&
  !!member.email;

const isPrivilegedMember = (member: WorkspaceMember) =>
  member.role?.toUpperCase() === "OWNER" || member.role?.toUpperCase() === "ADMIN";

const CreateProjectModal: React.FC<CreateProjectModalProps> = ({ isOpen, onClose, onSuccess }) => {
  const { user } = useAuth();
  const desktop = isDesktop();
  const [projectName, setProjectName] = useState("");
  const [description, setDescription] = useState("");
  const [shareWith, setShareWith] = useState<"none" | "all" | "specific">("none");
  const [selectedMemberRoles, setSelectedMemberRoles] = useState<Record<string, ProjectShareRole>>({});
  const [workspaceMembers, setWorkspaceMembers] = useState<WorkspaceMember[]>([]);
  const [selectedMembers, setSelectedMembers] = useState<string[]>([]);
  const [memberSearch, setMemberSearch] = useState("");
  const [creating, setCreating] = useState(false);
  const [loadingMembers, setLoadingMembers] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [showFreePlanDialog, setShowFreePlanDialog] = useState(false);
  const [membersLoadError, setMembersLoadError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) {
      setErrorMessage(null);
      setMembersLoadError(null);
      return;
    }

    setShareWith("none");
    setSelectedMemberRoles({});
    setSelectedMembers([]);
    setMemberSearch("");

    if (desktop) {
      setWorkspaceMembers([]);
      setMembersLoadError(null);
      return;
    }

    if (user?.workspaceId) {
      loadWorkspaceMembers();
    } else {
      setWorkspaceMembers([]);
      setMembersLoadError("Select a workspace before choosing specific members.");
    }
  }, [isOpen, user?.workspaceId, desktop]);

  useEffect(() => {
    if (shareWith !== "specific") {
      setSelectedMembers([]);
      setSelectedMemberRoles({});
      setMemberSearch("");
      return;
    }

    const privileged = workspaceMembers.filter(isPrivilegedMember);
    if (privileged.length > 0) {
      setSelectedMembers((prev) => {
        const privilegedEmails = privileged.map((m) => m.email.trim());
        const nonPrivilegedPrev = prev.filter(
          (e) => !privileged.some((p) => normalizeEmail(p.email) === normalizeEmail(e))
        );
        return [...privilegedEmails, ...nonPrivilegedPrev];
      });
      setSelectedMemberRoles((prev) => {
        const next = { ...prev };
        privileged.forEach((m) => { next[normalizeEmail(m.email)] = "EDITOR"; });
        return next;
      });
    }
  }, [shareWith, workspaceMembers]);

  const loadWorkspaceMembers = async () => {
    if (!user?.workspaceId) {
      setWorkspaceMembers([]);
      setMembersLoadError("Select a workspace before choosing specific members.");
      return;
    }

    try {
      setLoadingMembers(true);
      setMembersLoadError(null);
      const response = await apiClient.get(`/api/workspaces/${user.workspaceId}`);
      const workspaceData = response?.data || response;
      const members = workspaceData?.members || [];
      const currentUserId = user?.userId;
      const currentEmail = normalizeEmail(user?.email);
      const filteredMembers = members
        .filter((member: WorkspaceMember) => isActiveWorkspaceMember(member))
        .filter((member: WorkspaceMember) => {
          if (currentUserId && member.userId === currentUserId) return false;
          if (currentEmail && normalizeEmail(member.email) === currentEmail) return false;
          return true;
        })
        .map((member: WorkspaceMember) => ({
          ...member,
          email: member.email.trim(),
          username: member.username || member.email.split("@")[0],
        }))
        .sort((a: WorkspaceMember, b: WorkspaceMember) =>
          a.username.localeCompare(b.username, undefined, { sensitivity: "base" }),
        );
      setWorkspaceMembers(filteredMembers);
    } catch (error) {
      console.error("Error loading workspace members:", error);
      setWorkspaceMembers([]);
      setMembersLoadError("Could not load workspace members. Try again.");
    } finally {
      setLoadingMembers(false);
    }
  };

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
        payload.memberAccess = selectedMembers.map((email) => ({
          email: email.trim(),
          role: selectedMemberRoles[normalizeEmail(email)] || "VIEWER",
        }));
      }

      await apiClient.post("/api/projects", payload);

      setProjectName("");
      setDescription("");
      setShareWith("none");
      setSelectedMemberRoles({});
      setSelectedMembers([]);
      setMemberSearch("");
      setErrorMessage(null);
      onSuccess();
      onClose();
    } catch (error: any) {
      console.error("Error creating project:", error);
      if (error?.status === 403 && error?.data?.requiresUpgrade) {
        setShowFreePlanDialog(true);
        return;
      }
      const msg =
        error?.data?.error ||
        error?.data?.message ||
        error?.response?.data?.error ||
        error?.response?.data?.message ||
        error?.message ||
        "Failed to create project. Please try again.";
      setErrorMessage(msg);
    } finally {
      setCreating(false);
    }
  };

  const toggleMember = (email: string, privileged = false) => {
    if (privileged) return; // owner/admin cannot be deselected
    const normalizedEmail = normalizeEmail(email);
    const trimmedEmail = email.trim();
    const isSelected = selectedMembers.some((value) => normalizeEmail(value) === normalizedEmail);

    if (isSelected) {
      setSelectedMembers((prev) => prev.filter((value) => normalizeEmail(value) !== normalizedEmail));
      setSelectedMemberRoles((roles) => {
        const nextRoles = { ...roles };
        delete nextRoles[normalizedEmail];
        return nextRoles;
      });
      return;
    }

    setSelectedMembers((prev) => [...prev, trimmedEmail]);
    setSelectedMemberRoles((roles) => ({
      ...roles,
      [normalizedEmail]: roles[normalizedEmail] || "VIEWER",
    }));
  };

  const getMemberRole = (email: string): ProjectShareRole =>
    selectedMemberRoles[normalizeEmail(email)] || "VIEWER";

  const handleMemberRoleChange = (email: string, role: ProjectShareRole) => {
    const normalizedEmail = normalizeEmail(email);
    setSelectedMemberRoles((roles) => ({
      ...roles,
      [normalizedEmail]: role,
    }));
  };

  const hasShareableMembers = workspaceMembers.length > 0;
  const shareableMemberCount = workspaceMembers.length;
  const visibleMembers = useMemo(() => {
    const query = memberSearch.trim().toLowerCase();
    const filtered = query
      ? workspaceMembers.filter(
          (member) =>
            member.username.toLowerCase().includes(query) ||
            member.email.toLowerCase().includes(query),
        )
      : workspaceMembers;

    const privileged = filtered.filter(isPrivilegedMember);
    const regular = filtered.filter((m) => !isPrivilegedMember(m));
    return [...privileged, ...regular];
  }, [workspaceMembers, memberSearch]);

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
                  <div className="font-medium text-gray-900">Only me</div>
                  <div className="text-sm text-gray-500">Start with a project only you can open</div>
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
                  <div className="text-sm text-gray-500">
                    {loadingMembers ? (
                      "Checking active workspace members..."
                    ) : shareableMemberCount === 0 ? (
                      "No other active members to add yet."
                    ) : (
                      <>
                        {shareableMemberCount} active workspace member{shareableMemberCount === 1 ? "" : "s"} will be added as <strong>Viewer</strong>
                      </>
                    )}
                  </div>
                  {(shareWith === "all" || shareWith === "specific") && (
                    <p className="text-xs text-gray-500 mt-1 leading-snug">
                      The workspace owner and workspace admins are always given at least <strong>Editor</strong> on shared projects. Only the workspace owner can remove them from the project later.
                    </p>
                  )}
                </div>
              </label>

              <label
                className={`flex items-start gap-3 p-3 border border-gray-200 rounded-lg ${
                  hasShareableMembers ? "cursor-pointer hover:bg-gray-50" : "opacity-60 cursor-not-allowed"
                }`}
              >
                <input
                  type="radio"
                  name="shareWith"
                  value="specific"
                  checked={shareWith === "specific"}
                  disabled={!hasShareableMembers && !loadingMembers}
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
                  <div className="text-sm text-gray-500">
                    Choose active workspace members and their project role
                  </div>
                  {shareWith === "specific" && workspaceMembers.some(isPrivilegedMember) && (
                    <p className="text-xs text-purple-600 mt-1">
                      Owner and admins are always included with Editor access.
                    </p>
                  )}
                  {!hasShareableMembers && !loadingMembers && (
                    <p className="text-xs text-gray-500 mt-1">
                      Invite another member to the workspace first, or use All Workspace Members.
                    </p>
                  )}
                </div>
              </label>

              {shareWith === "specific" && (
                <div className="ml-8 space-y-3 border border-gray-200 rounded-lg p-3 bg-gray-50">
                  {workspaceMembers.length >= MEMBER_SEARCH_THRESHOLD && !loadingMembers && !membersLoadError && (
                    <div className="relative">
                      <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                      <input
                        type="search"
                        value={memberSearch}
                        onChange={(e) => setMemberSearch(e.target.value)}
                        placeholder="Search by name or email"
                        className="w-full pl-9 pr-3 py-2 text-sm border border-gray-200 rounded-lg bg-white focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                      />
                    </div>
                  )}

                  <div className="space-y-2 max-h-48 overflow-y-auto">
                    {loadingMembers ? (
                      <div className="text-center text-sm text-gray-500 py-4">Loading members...</div>
                    ) : membersLoadError ? (
                      <div className="space-y-3 py-2">
                        <p className="text-sm text-red-600">{membersLoadError}</p>
                        <button
                          type="button"
                          onClick={() => loadWorkspaceMembers()}
                          className="text-sm font-medium text-purple-600 hover:text-purple-700"
                        >
                          Retry
                        </button>
                      </div>
                    ) : workspaceMembers.length === 0 ? (
                      <p className="text-center text-sm text-gray-500 py-4">
                        No other active members are available in this workspace.
                      </p>
                    ) : visibleMembers.length === 0 ? (
                      <p className="text-center text-sm text-gray-500 py-4">
                        No members match your search.
                      </p>
                    ) : (
                      visibleMembers.map((member) => {
                        const privileged = isPrivilegedMember(member);
                        const isSelected = privileged || selectedMembers.some(
                          (value) => normalizeEmail(value) === normalizeEmail(member.email),
                        );
                        const roleBadgeLabel = member.role?.toUpperCase() === "OWNER" ? "Owner" : "Admin";

                        return (
                          <div
                            key={member.userId || member.email}
                            className={`flex items-center gap-2 p-2 rounded border ${
                              privileged
                                ? "bg-purple-50 border-purple-100"
                                : "bg-white hover:bg-gray-50 border-transparent hover:border-gray-200"
                            }`}
                          >
                            <input
                              type="checkbox"
                              checked={isSelected}
                              onChange={() => toggleMember(member.email, privileged)}
                              disabled={privileged}
                              className="rounded text-purple-600 disabled:opacity-50 disabled:cursor-not-allowed"
                            />
                            <div className="text-sm flex-1 min-w-0">
                              <div className="flex items-center gap-1.5">
                                <span className="font-medium text-gray-900 truncate">{member.username}</span>
                                {privileged && (
                                  <span className="px-1.5 py-0.5 text-[10px] font-semibold rounded bg-purple-100 text-purple-700 flex-shrink-0">
                                    {roleBadgeLabel}
                                  </span>
                                )}
                              </div>
                              <div className="text-gray-500 text-xs truncate">{member.email}</div>
                            </div>
                            {privileged ? (
                              <span className="text-xs px-2 py-1 bg-purple-100 text-purple-700 rounded-lg font-medium shrink-0">
                                Editor
                              </span>
                            ) : (
                              <select
                                value={getMemberRole(member.email)}
                                onChange={(e) =>
                                  handleMemberRoleChange(member.email, e.target.value as ProjectShareRole)
                                }
                                disabled={!isSelected}
                                className="text-xs border border-gray-200 rounded-lg px-2 py-1 bg-white focus:ring-2 focus:ring-purple-500 disabled:bg-gray-100 disabled:text-gray-400 shrink-0"
                              >
                                <option value="VIEWER">Viewer</option>
                                <option value="DRAFT_EDITOR">Draft Editor</option>
                                <option value="EDITOR">Editor</option>
                              </select>
                            )}
                          </div>
                        );
                      })
                    )}
                  </div>
                </div>
              )}
            </div>
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

      {showFreePlanDialog && (
        <div
          className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[9999]"
          onClick={() => setShowFreePlanDialog(false)}
        >
          <div
            className="relative bg-white rounded-2xl shadow-2xl w-[420px] max-w-[92vw] overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="h-1.5 w-full bg-gradient-to-r from-violet-500 via-purple-500 to-indigo-500" />
            <div className="px-6 pt-5 pb-4 flex items-start gap-4">
              <div className="flex-shrink-0 w-11 h-11 rounded-xl bg-amber-50 border border-amber-200 flex items-center justify-center">
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-amber-500">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                  <circle cx="12" cy="12" r="3"/>
                  <line x1="2" y1="2" x2="22" y2="22"/>
                </svg>
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="text-[15px] font-semibold text-gray-900 leading-tight">Project Creation Not Available</h3>
                <p className="text-xs text-gray-400 mt-0.5">
                  Your workspace is on the <span className="font-medium text-gray-500">Free plan</span>
                </p>
              </div>
              <button
                onClick={() => setShowFreePlanDialog(false)}
                className="flex-shrink-0 text-gray-400 hover:text-gray-600 transition-colors p-1 rounded-lg hover:bg-gray-100 -mt-1 -mr-1"
                aria-label="Close"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                  <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </button>
            </div>
            <div className="px-6 pb-5">
              <div className="bg-gray-50 rounded-xl border border-gray-100 px-4 py-3.5 mb-4 text-sm text-gray-600 leading-relaxed">
                Creating new projects requires a <span className="font-medium text-gray-800">Pro plan</span>. Only the workspace owner can create projects on the Free plan.
              </div>
              <div className="flex items-start gap-2.5 text-sm text-gray-600">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="flex-shrink-0 mt-0.5 text-violet-500">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>
                </svg>
                <span>Ask your <span className="font-medium text-gray-800">workspace owner</span> to upgrade to Pro to unlock project creation for all members.</span>
              </div>
            </div>
            <div className="px-6 pb-5 flex justify-end">
              <button
                onClick={() => setShowFreePlanDialog(false)}
                className="px-5 py-2 text-sm font-medium rounded-lg bg-gray-900 text-white hover:bg-gray-700 transition-colors"
              >
                Got it
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default CreateProjectModal;

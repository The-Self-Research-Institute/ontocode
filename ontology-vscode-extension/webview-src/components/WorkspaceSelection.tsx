import React, { useState, useEffect, useRef, useCallback } from "react";
import { Loader2, Plus, Users, Crown, Building2, ChevronRight, Settings, Trash, AlertTriangle } from "lucide-react";
import apiClient from "../services/apiClient";
import SubscriptionPlanSelection from "./SubscriptionPlanSelection";
import { validateWorkspaceName, validateDescription, getMaxWorkspacesForPlan } from "../utils/validation";

interface WorkspaceMember {
  userId: string;
  username: string;
  email: string;
  role: string;
  joinedAt: string;
}

interface Workspace {
  id: string;
  workspaceId: string;
  name: string;
  description?: string;
  ownerId: string;
  memberCount: number;
  subscriptionPlan: string;
  collaborationEnabled: boolean;
  members: WorkspaceMember[];
  createdAt: string;
  updatedAt: string;
}

interface WorkspaceSelectionProps {
  username: string;
  isAdmin?: boolean;
  onWorkspaceSelected: (workspaceData: any) => void;
  onSkipWorkspace: () => void;
  onLogout: () => void;
}

const WorkspaceSelection: React.FC<WorkspaceSelectionProps> = ({
  username,
  isAdmin = false,
  onWorkspaceSelected,
  onSkipWorkspace,
  onLogout,
}) => {
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [loading, setLoading] = useState(true);
  const [selecting, setSelecting] = useState(false);
  const [error, setError] = useState("");
  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createDialogError, setCreateDialogError] = useState("");
  const [newWorkspaceName, setNewWorkspaceName] = useState("");
  const [newWorkspaceDescription, setNewWorkspaceDescription] = useState("");
  const [selectedPlan, setSelectedPlan] = useState("FREE");
  const [showPlanSelection, setShowPlanSelection] = useState(false);
  const [deletingWorkspace, setDeletingWorkspace] = useState<string | null>(null);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [workspaceToDelete, setWorkspaceToDelete] = useState<{ id: string; name: string } | null>(null);

  // In-app confirm dialog state (replaces window.confirm)
  const [confirmDialog, setConfirmDialog] = useState<{ title: string; message: string; confirmLabel: string } | null>(
    null,
  );
  const confirmResolveRef = useRef<((value: boolean) => void) | null>(null);

  const showConfirmDialog = useCallback((title: string, message: string, confirmLabel = "OK"): Promise<boolean> => {
    return new Promise<boolean>((resolve) => {
      confirmResolveRef.current = resolve;
      setConfirmDialog({ title, message, confirmLabel });
    });
  }, []);

  const handleConfirmDialogResponse = useCallback((accepted: boolean) => {
    setConfirmDialog(null);
    if (confirmResolveRef.current) {
      confirmResolveRef.current(accepted);
      confirmResolveRef.current = null;
    }
  }, []);

  useEffect(() => {
    loadWorkspaces();
  }, []);

  const loadWorkspaces = async () => {
    try {
      setLoading(true);
      setError("");
      console.log("[WorkspaceSelection] Fetching workspaces from /api/workspaces...");
      const response = await apiClient.get("/api/workspaces");
      console.log("[WorkspaceSelection] Raw response:", JSON.stringify(response, null, 2));

      // Handle both direct response (VS Code proxy) and response.data (axios browser) formats
      const data = response?.data || response;
      console.log("[WorkspaceSelection] Parsed data:", JSON.stringify(data, null, 2));

      const workspaceList = data?.workspaces || [];
      console.log("[WorkspaceSelection] Workspace list:", workspaceList);
      console.log("[WorkspaceSelection] Workspace count:", workspaceList.length);

      setWorkspaces(workspaceList);

      // Auto-create and select default workspace if none exists and user is admin
      // if (workspaceList.length === 0 && isAdmin) {
      //     console.log('[WorkspaceSelection] No workspaces found, auto-creating default workspace for admin...');
      //     await createAndSelectDefaultWorkspace();
      // }
    } catch (err: any) {
      console.error("[WorkspaceSelection] Error loading workspaces:", err);
      console.error("[WorkspaceSelection] Error details:", JSON.stringify(err, null, 2));
      const errorMsg = err.response?.data?.error || err.message || "Failed to load workspaces";
      // If backend is not reachable, show helpful errorl
      if (err.code === "ECONNREFUSED" || errorMsg.includes("Network Error")) {
        setError("Cannot connect to backend. Please ensure Docker services are running (docker-compose up -d)");
      } else {
        setError(errorMsg);
      }
    } finally {
      setLoading(false);
    }
  };

  const createAndSelectDefaultWorkspace = async () => {
    try {
      console.log("[WorkspaceSelection] Creating default workspace...");
      const response = await apiClient.post("/api/workspaces", {
        name: "My Workspace",
        description: "Default workspace for ontology projects",
        subscriptionPlan: "FREE",
      });
      console.log("[WorkspaceSelection] Create workspace response:", response);

      // Handle both direct response (VS Code proxy) and response.data (axios browser) formats
      const data = response?.data || response;
      if (data?.workspace) {
        const newWorkspace = data.workspace;
        console.log("[WorkspaceSelection] Default workspace created:", newWorkspace.workspaceId);

        // Automatically select the newly created workspace
        const selectResponse = await apiClient.post(`/api/workspaces/${newWorkspace.workspaceId}/select`);
        const selectData = selectResponse?.data || selectResponse;

        if (selectData?.jwt) {
          console.log("[WorkspaceSelection] Auto-selected default workspace");
          onWorkspaceSelected(selectData);
        }
      }
    } catch (err: any) {
      console.error("Error creating default workspace:", err);
      setError(err.response?.data?.error || "Failed to create default workspace");
    }
  };

  const handleSelectWorkspace = async (workspaceId: string) => {
    try {
      console.log("[WorkspaceSelection] 🎯 Selecting workspace:", workspaceId);
      setSelecting(true);
      setError("");

      const response = await apiClient.post(`/api/workspaces/${workspaceId}/select`);
      console.log("[WorkspaceSelection] 📥 Select workspace response:", response);
      console.log("[WorkspaceSelection] Response type:", typeof response);
      console.log("[WorkspaceSelection] Response keys:", response ? Object.keys(response) : "null");

      // Handle both direct response (VS Code proxy) and response.data (axios browser) formats
      const data = response?.data || response;
      console.log("[WorkspaceSelection] 📦 Extracted data:", data);

      if (data?.jwt) {
        console.log("[WorkspaceSelection] ✅ JWT found, calling onWorkspaceSelected");
        onWorkspaceSelected(data);
      } else {
        console.error("[WorkspaceSelection] ❌ No JWT in response data:", data);
        setError("No authentication token received from server");
      }
    } catch (err: any) {
      console.error("[WorkspaceSelection] ❌ Error selecting workspace:", err);
      console.error("[WorkspaceSelection] Error details:", err?.message, err?.status, err?.data);
      setError(err.response?.data?.error || err.message || "Failed to select workspace");
    } finally {
      setSelecting(false);
    }
  };

  const handlePlanSelected = (planId: string) => {
    setSelectedPlan(planId);
    setShowPlanSelection(false);
    // Return to create workspace modal instead of creating immediately
    setShowCreateDialog(true);
  };

  const handleOpenPlanSelection = () => {
    if (!newWorkspaceName.trim()) {
      setCreateDialogError("Please enter a workspace name first");
      return;
    }
    // Close modal and show plan selection screen
    setShowCreateDialog(false);
    setShowPlanSelection(true);
  };

  const handleCreateWorkspace = async (e: React.FormEvent) => {
    e.preventDefault();

    // Validate workspace name
    const nameValidation = validateWorkspaceName(newWorkspaceName);
    if (!nameValidation.isValid) {
      setCreateDialogError(nameValidation.error || "Invalid workspace name");
      return;
    }

    // Validate description (optional)
    if (newWorkspaceDescription) {
      const descValidation = validateDescription(newWorkspaceDescription);
      if (!descValidation.isValid) {
        setCreateDialogError(descValidation.error || "Invalid description");
        return;
      }
    }

    // Check workspace limit before creating
    const maxWorkspaces = getMaxWorkspacesForPlan(selectedPlan);
    if (workspaces.length >= maxWorkspaces) {
      setCreateDialogError(
        `Maximum workspace limit reached (${maxWorkspaces} for ${selectedPlan.toUpperCase()} plan). Please upgrade your subscription or delete existing workspaces.`,
      );
      return;
    }

    try {
      setCreating(true);
      setCreateDialogError("");

      // Check if workspace name already exists
      const checkResponse = await apiClient.get(
        `/api/workspaces/check?name=${encodeURIComponent(newWorkspaceName.trim())}`,
      );

      if (checkResponse?.data?.exists || checkResponse?.exists) {
        const data = checkResponse?.data || checkResponse;
        const shouldContinue = await showConfirmDialog(
          "Workspace Already Exists",
          `A workspace named "${newWorkspaceName.trim()}" already exists. Would you like to create a workspace with a different name instead?`,
          "Rename",
        );

        if (!shouldContinue) {
          setCreating(false);
          return;
        }

        // Generate unique name
        let copyNumber = 2;
        let uniqueName = `${newWorkspaceName.trim()} (${copyNumber})`;

        // Keep checking until we find a unique name
        while (true) {
          const recheckResponse = await apiClient.get(`/api/workspaces/check?name=${encodeURIComponent(uniqueName)}`);
          const recheckData = recheckResponse?.data || recheckResponse;

          if (!recheckData.exists) {
            setNewWorkspaceName(uniqueName);
            break;
          }
          copyNumber++;
          uniqueName = `${newWorkspaceName.trim()} (${copyNumber})`;
        }

        // Show the new name to user
        const confirmNewName = await showConfirmDialog(
          "Confirm New Name",
          `New workspace name will be: "${uniqueName}"`,
          "Create",
        );

        if (!confirmNewName) {
          setCreating(false);
          return;
        }
      }

      const response = await apiClient.post("/api/workspaces", {
        name: newWorkspaceName.trim(),
        description: newWorkspaceDescription.trim(),
        subscriptionPlan: selectedPlan,
      });
      console.log("[WorkspaceSelection] Create workspace response:", response);

      // Handle both direct response (VS Code proxy) and response.data (axios browser) formats
      const data = response?.data || response;
      if (data?.workspace) {
        setShowCreateDialog(false);
        setNewWorkspaceName("");
        setNewWorkspaceDescription("");
        setSelectedPlan("FREE");
        await loadWorkspaces();
      }
    } catch (err: any) {
      console.error("Error creating workspace:", err);
      setCreateDialogError(err.response?.data?.error || err.message || "Failed to create workspace");
    } finally {
      setCreating(false);
    }
  };

  const handleDeleteWorkspace = async () => {
    if (!workspaceToDelete) return;

    try {
      setDeletingWorkspace(workspaceToDelete.id);
      setError("");

      await apiClient.delete(`/api/workspaces/${workspaceToDelete.id}`);

      // Refresh workspace list
      await loadWorkspaces();

      setShowDeleteConfirm(false);
      setWorkspaceToDelete(null);
    } catch (err: any) {
      console.error("Error deleting workspace:", err);
      setError(err.response?.data?.error || err.message || "Failed to delete workspace");
    } finally {
      setDeletingWorkspace(null);
    }
  };

  const confirmDelete = (workspace: Workspace, e: React.MouseEvent) => {
    e.stopPropagation();
    setWorkspaceToDelete({ id: workspace.workspaceId, name: workspace.name });
    setShowDeleteConfirm(true);
  };

  const getRoleIcon = (workspace: Workspace, userId: string) => {
    if (workspace.ownerId === userId) {
      return <Crown size={16} className="text-yellow-400" />;
    }
    return <Users size={16} className="text-blue-400" />;
  };

  const getRoleBadge = (workspace: Workspace) => {
    const member = workspace.members.find((m) => m.username === username);
    if (!member) return null;

    const roleColors = {
      OWNER: "bg-yellow-500/20 text-yellow-400 border-yellow-400/30",
      ADMIN: "bg-purple-500/20 text-purple-400 border-purple-400/30",
      MEMBER: "bg-blue-500/20 text-blue-400 border-blue-400/30",
      VIEWER: "bg-gray-500/20 text-gray-400 border-gray-400/30",
    };

    return (
      <span
        className={`px-2 py-1 rounded text-xs border ${roleColors[member.role as keyof typeof roleColors] || roleColors.MEMBER}`}
      >
        {member.role}
      </span>
    );
  };

  if (loading) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 flex items-center justify-center p-4">
        <div className="text-center">
          <div className="w-20 h-20 rounded-2xl flex items-center justify-center shadow-lg mx-auto mb-6 bg-gradient-to-br from-purple-500 to-indigo-600">
            <Loader2 size={40} className="text-white animate-spin" />
          </div>
          <h2 className="text-2xl font-bold text-white mb-3">Loading Workspaces</h2>
          <p className="text-gray-300">Please wait...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 flex items-center justify-center p-4">
      <div className="absolute inset-0 overflow-hidden">
        <div className="absolute -top-40 -right-40 w-80 h-80 bg-purple-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse"></div>
        <div className="absolute -bottom-40 -left-40 w-80 h-80 bg-indigo-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse delay-1000"></div>
      </div>

      <div className="relative bg-white/10 backdrop-blur-xl border border-white/20 rounded-2xl shadow-2xl p-8 w-full max-w-4xl max-h-[90vh] flex flex-col">
        <div className="text-center mb-8">
          <h2 className="text-3xl font-bold text-white mb-2">Select a Workspace</h2>
          <p className="text-gray-300 mb-1">
            Welcome back, <span className="font-semibold">{username}</span>
          </p>
          <button onClick={onLogout} className="text-sm text-gray-400 hover:text-white transition-colors">
            Not you? Sign out
          </button>
        </div>

        {error && (
          <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-4 py-3 rounded-lg mb-6 text-sm backdrop-blur-sm">
            {error}
          </div>
        )}

        <div className="space-y-4 mb-6 overflow-y-auto min-h-0 flex-1 pr-1 workspace-scroll">
          {workspaces.length === 0 ? (
            <div className="text-center py-12">
              <Building2 size={64} className="text-gray-400 mx-auto mb-4 opacity-50" />
              <p className="text-gray-300 mb-6">You don't have any workspaces yet.</p>
              {isAdmin && (
                <button
                  onClick={() => setShowCreateDialog(true)}
                  className="px-6 py-3 bg-gradient-to-r from-purple-500 to-indigo-600 text-white font-medium rounded-lg hover:from-purple-600 hover:to-indigo-700 transition-all shadow-lg hover:shadow-purple-500/50"
                >
                  <Plus size={20} className="inline mr-2" />
                  Create Your First Workspace
                </button>
              )}
            </div>
          ) : (
            <>
              {workspaces.map((workspace) => (
                <div
                  key={workspace.id}
                  onClick={() => !selecting && handleSelectWorkspace(workspace.workspaceId)}
                  className="bg-white/5 backdrop-blur-sm border border-white/10 rounded-xl p-6 hover:bg-white/10 hover:border-purple-400/50 transition-all cursor-pointer group"
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-start space-x-4 flex-1 min-w-0">
                      <div className="w-12 h-12 rounded-lg bg-gradient-to-br from-purple-500 to-indigo-600 flex items-center justify-center flex-shrink-0">
                        <Building2 size={24} className="text-white" />
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center space-x-2 mb-1 min-w-0">
                          <h3 className="text-xl font-semibold text-white truncate flex-shrink min-w-0" title={workspace.name}>
                            {workspace.name}
                          </h3>
                          <div className="flex-shrink-0">
                            {getRoleBadge(workspace)}
                          </div>
                        </div>
                        {workspace.description && (
                          <p className="text-gray-400 text-sm mb-2 line-clamp-2" title={workspace.description}>
                            {workspace.description}
                          </p>
                        )}
                        <div className="flex items-center space-x-4 text-sm text-gray-400">
                          <span className="flex items-center space-x-1">
                            <Users size={14} />
                            <span>
                              {workspace.memberCount} member{workspace.memberCount !== 1 ? "s" : ""}
                            </span>
                          </span>
                          <span className="text-xs px-2 py-1 bg-white/5 rounded">{workspace.subscriptionPlan}</span>
                        </div>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      <button
                        onClick={(e) => confirmDelete(workspace, e)}
                        className="p-2 text-gray-400 hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors"
                        title="Delete workspace"
                      >
                        <Trash size={18} />
                      </button>
                      <ChevronRight
                        size={24}
                        className="text-gray-400 group-hover:text-purple-400 transition-colors flex-shrink-0"
                      />
                    </div>
                  </div>
                </div>
              ))}
            </>
          )}
        </div>

        {workspaces.length > 0 && isAdmin && (
          <button
            onClick={() => setShowCreateDialog(true)}
            className="w-full py-3 bg-white/5 border border-white/20 text-white font-medium rounded-lg hover:bg-white/10 transition-all flex items-center justify-center space-x-2"
          >
            <Plus size={20} />
            <span>Create New Workspace</span>
          </button>
        )}

        {/* Continue without workspace button - available for all users */}
        <button
          onClick={onSkipWorkspace}
          className="w-full py-3 bg-transparent border-2 border-white/20 text-gray-300 font-medium rounded-lg hover:bg-white/5 hover:border-purple-400/50 hover:text-white transition-all flex items-center justify-center space-x-2 mt-3"
        >
          <span>Continue without workspace</span>
        </button>

        {workspaces.length === 0 && !isAdmin && (
          <div className="text-center py-8">
            <p className="text-gray-400 mb-2">No workspaces available</p>
            <p className="text-gray-500 text-sm">
              You can continue without a workspace or contact an administrator to be added to one
            </p>
          </div>
        )}
      </div>

      {/* Create Workspace Dialog */}
      {showCreateDialog && (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="bg-slate-800 border border-white/20 rounded-2xl shadow-2xl p-8 w-full max-w-md">
            <h3 className="text-2xl font-bold text-gray-200 mb-6 text-center">Create New Workspace</h3>

            {createDialogError && (
              <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-4 py-3 rounded-lg mb-4 text-sm backdrop-blur-sm">
                {createDialogError}
              </div>
            )}

            <form onSubmit={handleCreateWorkspace} className="space-y-4">
              <div>
                <label htmlFor="workspaceName" className="block text-sm text-gray-200 mb-2">
                  Workspace Name *
                </label>
                <input
                  type="text"
                  id="workspaceName"
                  value={newWorkspaceName}
                  onChange={(e) => {
                    setNewWorkspaceName(e.target.value);
                    setCreateDialogError("");
                  }}
                  required
                  disabled={creating}
                  maxLength={255}
                  className="w-full px-4 py-3 bg-white/5 border border-white/20 rounded-lg text-gray-200 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-purple-500"
                  placeholder="My Ontology Workspace"
                />
                <p className={`text-xs mt-1 ${newWorkspaceName.length >= 255 ? 'text-red-400 font-medium' : newWorkspaceName.length > 240 ? 'text-orange-400 font-medium' : 'text-gray-400'}`}>
                  {newWorkspaceName.length}/255 characters
                  {newWorkspaceName.length >= 255 && ' (Maximum reached)'}
                </p>
              </div>
              <div>
                <label htmlFor="workspaceDescription" className="block text-sm text-gray-200 mb-2">
                  Description (Optional)
                </label>
                <textarea
                  id="workspaceDescription"
                  value={newWorkspaceDescription}
                  onChange={(e) => setNewWorkspaceDescription(e.target.value)}
                  disabled={creating}
                  rows={3}
                  maxLength={1000}
                  className="w-full px-4 py-3 bg-white/5 border border-white/20 rounded-lg text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-purple-500 resize-none"
                  placeholder="A workspace for my ontology projects..."
                />
                <div className="flex justify-between text-xs text-gray-400 mt-1">
                  <span>Describe your workspace</span>
                  <span className={newWorkspaceDescription.length > 900 ? "text-orange-400 font-medium" : ""}>
                    {newWorkspaceDescription.length}/1000 characters
                  </span>
                </div>
              </div>
              <div>
                <label className="block text-sm text-gray-200 mb-3">Subscription Plan *</label>
                <button
                  type="button"
                  onClick={handleOpenPlanSelection}
                  disabled={creating}
                  className="w-full p-4 rounded-lg border-2 border-white/20 bg-white/5 hover:border-purple-500 hover:bg-purple-500/10 transition-all disabled:opacity-50 disabled:cursor-not-allowed group"
                >
                  <div className="flex items-center justify-between">
                    <div className="text-left">
                      <h4 className="font-semibold text-sm text-gray-200 group-hover:text-purple-400 transition-colors">
                        {selectedPlan === "FREE" && "Free Plan"}
                        {selectedPlan === "PRO" && "Professional Plan"}
                        {selectedPlan === "ENTERPRISE" && "Enterprise Plan"}
                      </h4>
                      <p className="text-sm text-gray-400">Click to select a different plan</p>
                    </div>
                    <ChevronRight size={20} className="text-gray-400 group-hover:text-purple-400 transition-colors" />
                  </div>
                </button>
              </div>
              <div className="flex space-x-3 pt-4">
                <button
                  type="button"
                  onClick={() => {
                    setShowCreateDialog(false);
                    setNewWorkspaceName("");
                    setNewWorkspaceDescription("");
                    setSelectedPlan("FREE");
                    setCreateDialogError("");
                  }}
                  disabled={creating}
                  className="flex-1 px-4 py-3 bg-white/5 border border-white/20 text-white font-medium rounded-lg hover:bg-white/10 transition-all disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={creating || !newWorkspaceName.trim() || newWorkspaceName.length >= 255}
                  className="flex-1 px-4 py-3 bg-gradient-to-r from-purple-500 to-indigo-600 text-white font-medium rounded-lg hover:from-purple-600 hover:to-indigo-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center space-x-2"
                >
                  {creating ? (
                    <>
                      <Loader2 size={20} className="animate-spin" />
                      <span>Creating...</span>
                    </>
                  ) : (
                    <>
                      <Plus size={20} />
                      <span>Create</span>
                    </>
                  )}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Selection Loading Overlay */}
      {selecting && (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center z-50">
          <div className="text-center">
            <div className="w-20 h-20 rounded-2xl flex items-center justify-center shadow-lg mx-auto mb-6 bg-gradient-to-br from-purple-500 to-indigo-600">
              <Loader2 size={40} className="text-white animate-spin" />
            </div>
            <h2 className="text-2xl font-bold text-white mb-3">Entering Workspace</h2>
            <p className="text-gray-300">Please wait...</p>
          </div>
        </div>
      )}

      {/* Subscription Plan Selection Screen */}
      {showPlanSelection && (
        <div className="fixed inset-0 z-50">
          <SubscriptionPlanSelection
            username={username}
            workspaceId=""
            workspaceName={newWorkspaceName || "New Workspace"}
            onPlanSelected={handlePlanSelected}
            onSkip={() => {
              setShowPlanSelection(false);
              setShowCreateDialog(true);
            }}
            onLogout={onLogout}
          />
        </div>
      )}

      {/* In-App Confirm Dialog (replaces window.confirm) */}
      {confirmDialog && (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="bg-slate-800 border border-white/20 rounded-2xl shadow-2xl p-8 w-full max-w-md">
            <div className="text-center mb-6">
              <div className="w-16 h-16 bg-amber-500/20 rounded-full flex items-center justify-center mx-auto mb-4">
                <AlertTriangle size={32} className="text-amber-400" />
              </div>
              <h3 className="text-2xl font-bold text-white mb-2">{confirmDialog.title}</h3>
              <p className="text-gray-300">{confirmDialog.message}</p>
            </div>
            <div className="flex space-x-3">
              <button
                type="button"
                onClick={() => handleConfirmDialogResponse(false)}
                className="flex-1 px-4 py-3 bg-white/5 border border-white/20 text-white font-medium rounded-lg hover:bg-white/10 transition-all"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => handleConfirmDialogResponse(true)}
                className="flex-1 px-4 py-3 bg-indigo-600 text-white font-medium rounded-lg hover:bg-indigo-700 transition-all"
              >
                {confirmDialog.confirmLabel}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Delete Confirmation Dialog */}
      {showDeleteConfirm && workspaceToDelete && (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="bg-slate-800 border border-white/20 rounded-2xl shadow-2xl p-8 w-full max-w-md">
            <div className="text-center mb-6">
              <div className="w-16 h-16 bg-red-500/20 rounded-full flex items-center justify-center mx-auto mb-4">
                <Trash size={32} className="text-red-400" />
              </div>
              <h3 className="text-2xl font-bold text-white mb-2">Delete Workspace?</h3>
              <p className="text-gray-300">
                Are you sure you want to delete <strong className="text-white">{workspaceToDelete.name}</strong>?
              </p>
              <p className="text-red-400 text-sm mt-2">This action cannot be undone.</p>
            </div>

            {error && (
              <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-4 py-3 rounded-lg mb-4 text-sm backdrop-blur-sm">
                {error}
              </div>
            )}

            <div className="flex space-x-3">
              <button
                type="button"
                onClick={() => {
                  setShowDeleteConfirm(false);
                  setWorkspaceToDelete(null);
                  setError("");
                }}
                disabled={deletingWorkspace !== null}
                className="flex-1 px-4 py-3 bg-white/5 border border-white/20 text-white font-medium rounded-lg hover:bg-white/10 transition-all disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleDeleteWorkspace}
                disabled={deletingWorkspace !== null}
                className="flex-1 px-4 py-3 bg-red-600 text-white font-medium rounded-lg hover:bg-red-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center space-x-2"
              >
                {deletingWorkspace ? (
                  <>
                    <Loader2 size={20} className="animate-spin" />
                    <span>Deleting...</span>
                  </>
                ) : (
                  <>
                    <Trash size={20} />
                    <span>Delete</span>
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default WorkspaceSelection;

import React, { useState, useEffect, useMemo, Suspense, lazy } from "react";
import {
  FolderOpen,
  Users,
  Plus,
  Search,
  Grid,
  List,
  Settings,
  LogOut,
  ChevronRight,
  Calendar,
  Tag,
  MoreVertical,
  Edit,
  Trash2,
  Archive,
  UserPlus,
  AlertTriangle,
  CheckCircle,
  XCircle,
  X,
  Building2,
  FileText,
  Shield,
  UserMinus,
  Save,
  Loader2,
  Crown,
  Zap,
  Sparkles,
  Code2,
  ArrowLeft,
  HelpCircle,
  CreditCard,
} from "lucide-react";
import apiClient from "../services/apiClient";
import { useAuth } from "../custom-hook/useAuth";
import { useSubscription } from "../hooks/useSubscription";
import InviteMemberModal from "./InviteMemberModal";
import SettingsModal from "./SettingsModal";
import CreateProjectModal from "./CreateProjectModal";
import ConfirmationModal from "./ConfirmationModal";
const PlanDetailsModal = lazy(() => import("./PlanDetailsModal"));
import { UserGuideModal } from "./UserGuideModal";
import { ReportIssueModal } from "./ReportIssueModal";
import { Bug } from "lucide-react";

interface ProjectMember {
  userId: string;
  username: string;
  email: string;
  role: string;
  joinedAt: string;
}

interface Project {
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
}

interface WorkspaceMember {
  id: string;
  username: string;
  email: string;
  roles: string[];
  lastLoginAt?: string;
  status?: "ACTIVE" | "PENDING";
  invitationToken?: string; // Token for pending invitations (used to cancel)
}

interface ProjectDashboardProps {
  onSelectProject: (projectId: string, projectName: string) => void;
  pendingFile?: { fileName: string; fileContent: string; fileSize: number } | null;
  onOpenLocalFile?: () => void;
  onOpenEditor?: () => void;
  onManageSubscription?: () => void;
}

const ProjectDashboard: React.FC<ProjectDashboardProps> = ({
  onSelectProject,
  pendingFile,
  onOpenLocalFile,
  onOpenEditor,
  onManageSubscription,
}) => {
  const { user, logout, switchWorkspace, updateSubscriptionPlan } = useAuth();
  console.log("[ProjectDashboard] Rendered with user:", {
    email: user?.email,
    workspaceId: user?.workspaceId,
    workspaceName: user?.workspaceName,
  });
  console.log("[ProjectDashboard] switchWorkspace function:", typeof switchWorkspace);

  const subscription = useSubscription();
  const [projects, setProjects] = useState<Project[]>([]);
  const [workspaceMembers, setWorkspaceMembers] = useState<WorkspaceMember[]>([]);
  const [workspaceOwnerId, setWorkspaceOwnerId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [viewMode, setViewMode] = useState<"grid" | "list">("grid");
  const [searchQuery, setSearchQuery] = useState("");
  const [showCreateProject, setShowCreateProject] = useState(false);
  const [showInviteMember, setShowInviteMember] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showPlanDetails, setShowPlanDetails] = useState(false);
  const [showUserGuide, setShowUserGuide] = useState(false);
  const [isReportIssueModalOpen, setIsReportIssueModalOpen] = useState(false);
  const [renaming, setRenaming] = useState<{ projectId: string; currentName: string } | null>(null);
  const [newName, setNewName] = useState("");
  const [newProjectName, setNewProjectName] = useState("");
  const [newProjectDescription, setNewProjectDescription] = useState("");
  const [creating, setCreating] = useState(false);

  // Project settings modal state
  const [projectSettingsModal, setProjectSettingsModal] = useState<Project | null>(null);
  const [editingProjectName, setEditingProjectName] = useState("");
  const [editingProjectDescription, setEditingProjectDescription] = useState("");
  const [savingProject, setSavingProject] = useState(false);
  const [projectSettingsTab, setProjectSettingsTab] = useState<"general" | "members" | "danger">("general");
  const [showAddMemberForm, setShowAddMemberForm] = useState(false);
  const [newMemberEmail, setNewMemberEmail] = useState("");
  const [newMemberUsername, setNewMemberUsername] = useState("");
  const [newMemberRole, setNewMemberRole] = useState("VIEWER");
  const [addingMember, setAddingMember] = useState(false);

  // Toast notification state
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "warning" | "info" } | null>(null);
  const [upgradingPlan, setUpgradingPlan] = useState(false);
  const [openMenuProjectId, setOpenMenuProjectId] = useState<string | null>(null); // Track which project menu is open

  // Confirmation modal state
  const [confirmModal, setConfirmModal] = useState<{
    title: string;
    message: string;
    onConfirm: () => void;
    confirmText?: string;
    cancelText?: string;
    type?: "danger" | "warning" | "info";
  } | null>(null);

  const currentUserInTeam = useMemo(() => teamMembers.find(m => m.id === user?.userId || m.email === user?.email || m.username === user?.username), [teamMembers, user]);
  const isWorkspaceOwner = user?.userId === workspaceOwnerId || user?.workspaceRole?.toUpperCase() === "OWNER" || currentUserInTeam?.roles?.some(r => r.toUpperCase() === "OWNER");
  const isWorkspaceAdmin = user?.workspaceRole?.toUpperCase() === "ADMIN" || currentUserInTeam?.roles?.some(r => r.toUpperCase() === "ADMIN");
  const canInviteMembers = isWorkspaceOwner || isWorkspaceAdmin;
  const isViewer = user?.workspaceRole?.toUpperCase() === "VIEWER" || (!currentUserInTeam?.roles?.some(r => ["OWNER", "ADMIN", "EDITOR"].includes(r.toUpperCase())));

  // Handle workspace subscription plan upgrade
  const handleUpgradePlan = async (planId: string) => {
    setUpgradingPlan(true);
    const upperCasePlan = planId.toUpperCase();
    try {
      console.log("[ProjectDashboard] Upgrading plan from", subscription.plan, "to", upperCasePlan);

      // Attempt to update subscription plan (backend will validate Stripe subscription)
      await updateSubscriptionPlan(upperCasePlan);

      // Close modal
      setShowPlanDetails(false);

      // Show success toast
      showToast(`Successfully upgraded to ${upperCasePlan} plan!`, "success");

      // Reload data to update subscription limits and workspace info
      await loadData();
    } catch (error: any) {
      console.error("Failed to upgrade workspace plan:", error);
      const errMsg = error?.error || error?.data?.error || error?.response?.data?.error || error?.message || "";
      const requiresBilling = error?.data?.requiresBilling || error?.requiresBilling || error?.response?.data?.requiresBilling;

      console.log("[ProjectDashboard] Upgrade error analysis:", { requiresBilling, errMsg });

      if (requiresBilling || errMsg.toLowerCase().includes("payment required")) {
        // Backend requires Stripe billing setup — redirect user to workspace selection
        // which contains the card collection + subscription flow
        console.log("[ProjectDashboard] Redirecting to billing setup. Setting localStorage intent.");
        setShowPlanDetails(false);
        showToast("Redirecting to billing setup...", "info");
        // Store intent so WorkspaceSelection can automatically pop the billing modal
        localStorage.setItem("pendingUpgradeWorkspaceId", user?.workspaceId || "");
        localStorage.setItem("pendingUpgradePlan", upperCasePlan);
        // Use switchWorkspace to go back to workspace selection (which has the Stripe flow)
        setTimeout(() => {
          console.log("[ProjectDashboard] Executing switchWorkspace to trigger redirect");
          switchWorkspace();
        }, 500);
      } else {
        console.log("[ProjectDashboard] Showing standard error toast");
        showToast(errMsg || "Failed to upgrade plan. Please try again.", "error");
      }
    } finally {
      setUpgradingPlan(false);
    }
  };

  // Helper function to show toast
  const showToast = (message: string, type: "success" | "error" | "warning" | "info" = "info") => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 4000);
  };

  // Helper function to show confirmation modal
  const showConfirm = (options: {
    title: string;
    message: string;
    onConfirm: () => void;
    confirmText?: string;
    cancelText?: string;
    type?: "danger" | "warning" | "info";
  }) => {
    setConfirmModal(options);
  };

  useEffect(() => {
    loadData();
  }, []);

  // Poll for workspace member changes (e.g. when invitees accept)
  useEffect(() => {
    if (!user?.workspaceId) return;
    const interval = setInterval(async () => {
      try {
        const workspaceResponse = await apiClient.get(`/api/workspaces/${user.workspaceId}`);
        const workspaceData = workspaceResponse?.data || workspaceResponse;
        const members = workspaceData?.members || [];
        const teamMembersList = members.map((member: any) => {
          const isPending = member.status === "PENDING" || (!member.userId && member.invitationToken);
          return {
            id: member.userId || `pending-${member.email}`,
            username: member.username,
            email: member.email,
            roles: [member.role],
            lastLoginAt: member.joinedAt,
            status: isPending ? "PENDING" : "ACTIVE",
            invitationToken: member.invitationToken,
          };
        });
        setTeamMembers((prev) => {
          if (JSON.stringify(prev) !== JSON.stringify(teamMembersList)) {
            console.log("[ProjectDashboard] Members updated via polling");
            return teamMembersList;
          }
          return prev;
        });
        setWorkspaceOwnerId(workspaceData?.ownerId || null);
      } catch (e) {
        // Silently ignore polling errors
      }
    }, 15000);
    return () => clearInterval(interval);
  }, [user?.workspaceId]);

  // Refresh project list on workspace events (e.g. PROJECT_DELETED broadcast via WebSocket)
  useEffect(() => {
    const handler = async (e: Event) => {
      const event = (e as CustomEvent).detail;
      if (event?.type === "PROJECT_DELETED" || event?.type === "PROJECT_CREATED") {
        try {
          const resp = await apiClient.get(`/api/projects/my?workspaceId=${user?.workspaceId}`);
          const data = resp?.data || resp;
          setProjects(data?.projects || []);
        } catch {
          // silently ignore
        }
      }
    };
    window.addEventListener("workspaceEvent", handler);
    return () => window.removeEventListener("workspaceEvent", handler);
  }, [user?.workspaceId]);

  // Close menu when clicking outside
  useEffect(() => {
    const handleClickOutside = () => setOpenMenuProjectId(null);
    if (openMenuProjectId) {
      document.addEventListener("click", handleClickOutside);
      return () => document.removeEventListener("click", handleClickOutside);
    }
  }, [openMenuProjectId]);

  const loadData = async () => {
    try {
      setLoading(true);
      setLoadError(null);

      // Load projects for current workspace only
      const projectsResponse = user?.workspaceId
        ? await apiClient.get(`/api/projects/my?workspaceId=${user.workspaceId}`)
        : await apiClient.get(`/api/projects/my`);
      const projectsData = projectsResponse?.data || projectsResponse;
      console.log("[ProjectDashboard] Projects API response:", JSON.stringify(projectsData, null, 2));
      const loadedProjects = projectsData?.projects || [];
      console.log(
        "[ProjectDashboard] Loaded projects:",
        loadedProjects.length,
        loadedProjects.map((p: any) => ({
          name: p.name,
          memberCount: p.memberCount,
          fileCount: p.fileCount,
          members: p.members?.length,
          files: p.files?.length,
        })),
      );
      setProjects(loadedProjects);

      if (loadedProjects.length === 0) {
        console.log("[ProjectDashboard] No projects returned from API");
      }

      // Load workspace members from workspace (includes both active and pending members)
      if (user?.workspaceId) {
        try {
          const workspaceResponse = await apiClient.get(`/api/workspaces/${user.workspaceId}`);
          const workspaceData = workspaceResponse?.data || workspaceResponse;
          const members = workspaceData?.members || [];

          // Store workspace owner ID
          setWorkspaceOwnerId(workspaceData?.ownerId || null);

          console.log("[ProjectDashboard] Workspace members from backend:", members);
          console.log("[ProjectDashboard] Workspace owner ID:", workspaceData?.ownerId);

          // Convert workspace members to workspace members format
          // Now includes both ACTIVE and PENDING members from the workspace
          const teamMembersList = members.map((member: any) => {
            // Determine status: if userId is null, it's a pending member
            const isPending = member.status === "PENDING" || (!member.userId && member.invitationToken);
            return {
              id: member.userId || `pending-${member.email}`,
              username: member.username,
              email: member.email,
              roles: [member.role],
              lastLoginAt: member.joinedAt,
              status: isPending ? "PENDING" : "ACTIVE", // Status from backend or inferred
              invitationToken: member.invitationToken, // Token for pending members (for cancellation)
            };
          });

          const activeCount = teamMembersList.filter((m: any) => m.status === "ACTIVE").length;
          const pendingCount = teamMembersList.filter((m: any) => m.status === "PENDING").length;

          console.log("[ProjectDashboard] Team members loaded:", activeCount, "active +", pendingCount, "pending");
          setTeamMembers(teamMembersList);
        } catch (error) {
          console.error("[ProjectDashboard] Error loading workspace members:", error);
        }
      }
    } catch (error) {
      console.error("Error loading dashboard data:", error);
      setLoadError("Failed to load projects. Click retry to try again.");
      showToast("Failed to load projects", "error");
    } finally {
      setLoading(false);
    }
  };

  const handleCreateProject = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!newProjectName.trim()) return;

    try {
      setCreating(true);

      const response = await apiClient.post("/api/projects", {
        workspaceId: user?.workspaceId || "default",
        name: newProjectName.trim(),
        description: newProjectDescription.trim(),
      });

      setShowCreateProject(false);
      setNewProjectName("");
      setNewProjectDescription("");
      loadData();
    } catch (error) {
      console.error("Error creating project:", error);
      showToast("Failed to create project", "error");
    } finally {
      setCreating(false);
    }
  };

  const handleInviteMember = async (email: string, role: string) => {
    try {
      // Check if member already exists in workspace
      const workspaceResponse = await apiClient.get(`/api/workspaces/${user?.workspaceId}`);
      const workspace = workspaceResponse?.data?.workspace || workspaceResponse?.data;
      const members = workspace?.members || [];

      // Check if user is already a member
      const existingMember = members.find((m: any) => m.email?.toLowerCase() === email.toLowerCase());

      if (existingMember) {
        // User already exists - show warning but allow invitation
        showToast(
          `User "${email}" is already a member with role: ${existingMember.role}. Sending invitation anyway.`,
          "warning",
        );
      }

      // Check pending invitations too
      try {
        const pendingResponse = await apiClient.get(`/api/invitations/workspace/${user?.workspaceId}`);
        const pendingInvites = pendingResponse?.data || pendingResponse;

        const pendingInvite = pendingInvites.find(
          (inv: any) => inv.email?.toLowerCase() === email.toLowerCase() && inv.status === "PENDING",
        );

        if (pendingInvite) {
          showToast(
            `An invitation was already sent to "${email}" on ${new Date(pendingInvite.createdAt).toLocaleDateString()}. Please wait for them to accept.`,
            "warning",
          );
          throw { message: "Invitation email already sent to this address" };
        }
      } catch (pendingError) {
        // If we can't check pending invitations, continue anyway
        console.warn("Could not check pending invitations:", pendingError);
      }

      const response = await apiClient.post("/api/invitations/send", {
        workspaceId: user?.workspaceId || "default",
        email: email,
        role: role,
      });

      console.log("Invitation sent successfully:", response?.message || "Invitation sent");
      return response;
    } catch (error: any) {
      console.error("Error inviting member:", error);
      // Re-throw with proper error structure
      throw {
        message: error?.error || error?.message || "Failed to send invitation",
        response: error,
      };
    }
  };

  const handleRemoveMember = async (member: TeamMember, projectId?: string) => {
    // Prevent removing yourself
    if (member.email === user?.email) {
      showToast(
        "You cannot remove yourself from the workspace. Please contact the workspace owner if you want to leave.",
        "warning",
      );
      return;
    }

    // Prevent removing workspace owner
    if (member.roles.some(r => r.toUpperCase() === "OWNER")) {
      showToast("Cannot remove workspace owner. Please transfer ownership or delete the workspace.", "error");
      return;
    }

    // If member is PENDING, use cancel invitation flow
    if (member.status === "PENDING") {
      handleCancelInvitation(member);
      return;
    }

    // Show confirmation modal for active members
    showConfirm({
      title: "Remove Workspace Member",
      message: `Are you sure you want to remove ${member.username} (${member.email}) from this workspace? This action cannot be undone.`,
      type: "danger",
      confirmText: "Remove",
      onConfirm: async () => {
        try {
          console.log("[ProjectDashboard] Removing active member:", member.email);
          await apiClient.delete(`/api/workspaces/${user?.workspaceId}/members/${member.email}`);
          showToast(`${member.username} has been removed from the workspace successfully.`, "success");
          await loadData();
        } catch (error: any) {
          console.error("Error removing member:", error);
          const errorMessage =
            error?.error || error?.response?.data?.error || error?.message || "Failed to remove member";
          showToast(`Failed to remove member: ${errorMessage}`, "error");
        }
        setConfirmModal(null);
      },
    });
  };

  const handleCancelInvitation = async (member: TeamMember) => {
    if (!member.invitationToken) {
      showToast("Cannot cancel invitation: Token not found", "error");
      return;
    }

    console.log(
      "[ProjectDashboard] Opening cancel invitation modal for:",
      member.email,
      "token:",
      member.invitationToken,
    );

    // Show confirmation modal
    showConfirm({
      title: "Cancel Invitation",
      message: `Are you sure you want to cancel the invitation for ${member.email}? The invitation link will no longer work.`,
      type: "warning",
      confirmText: "Cancel Invitation",
      onConfirm: async () => {
        try {
          console.log("[ProjectDashboard] Cancelling invitation with token:", member.invitationToken);
          await apiClient.delete(`/api/invitations/${member.invitationToken}`);
          console.log("[ProjectDashboard] Invitation cancelled successfully");
          showToast(`Invitation for ${member.email} has been cancelled successfully.`, "success");
          await loadData();
        } catch (error: any) {
          console.error("[ProjectDashboard] Error cancelling invitation:", error);
          // Handle ApiError and other error formats
          const errorMessage =
            error?.error || error?.response?.data?.error || error?.message || "Failed to cancel invitation";
          showToast(`Failed to cancel invitation: ${errorMessage}`, "error");
        }
        setConfirmModal(null);
      },
    });
  };

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

  // Open project settings modal
  const openProjectSettings = (project: Project) => {
    setProjectSettingsModal(project);
    setEditingProjectName(project.name);
    setEditingProjectDescription(project.description || "");
    setProjectSettingsTab("general");
  };

  // Save project settings (name and description)
  const handleSaveProjectSettings = async () => {
    if (!projectSettingsModal) return;

    try {
      setSavingProject(true);

      // Update project name if changed
      if (editingProjectName.trim() !== projectSettingsModal.name) {
        await apiClient.patch(`/api/projects/${projectSettingsModal.projectId}/rename`, {
          name: editingProjectName.trim(),
        });
      }

      // Update project description if changed
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

  // Delete project
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
          setProjectSettingsModal(null);
          loadData();
        } catch (error: any) {
          console.error("Error deleting project:", error);
          showToast(error?.error || error?.message || "Failed to delete project", "error");
        }
        setConfirmModal(null);
      },
    });
  };

  // Remove member from project
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
          // Refresh project data
          const updatedProjectResponse = await apiClient.get(`/api/projects/${project.projectId}`);
          const updatedProject =
            updatedProjectResponse?.data?.project || updatedProjectResponse?.project || updatedProjectResponse;
          setProjectSettingsModal(updatedProject);
          loadData();
        } catch (error: any) {
          console.error("Error removing project member:", error);
          showToast(error?.error || error?.message || "Failed to remove member", "error");
        }
        setConfirmModal(null);
      },
    });
  };

  // Update member role in project
  const handleUpdateProjectMemberRole = async (project: Project, member: ProjectMember, newRole: string) => {
    try {
      await apiClient.patch(`/api/projects/${project.projectId}/members/${member.userId}/role`, {
        role: newRole,
      });
      showToast(`${member.username}'s role updated to ${newRole}`, "success");
      // Refresh project data
      const updatedProjectResponse = await apiClient.get(`/api/projects/${project.projectId}`);
      const updatedProject =
        updatedProjectResponse?.data?.project || updatedProjectResponse?.project || updatedProjectResponse;
      setProjectSettingsModal(updatedProject);
      loadData();
    } catch (error: any) {
      console.error("Error updating member role:", error);
      showToast(error?.error || error?.message || "Failed to update member role", "error");
    }
  };

  // Add member to project
  const handleAddProjectMember = async () => {
    if (!projectSettingsModal || !newMemberUsername.trim()) return;

    try {
      setAddingMember(true);

      // Add member to project
      await apiClient.post(`/api/projects/${projectSettingsModal.projectId}/members`, {
        username: newMemberUsername.trim(),
        role: newMemberRole,
      });

      showToast(`Member added successfully`, "success");

      // Reset form
      setNewMemberEmail("");
      setNewMemberUsername("");
      setNewMemberRole("VIEWER");
      setShowAddMemberForm(false);

      // Refresh project data
      const updatedProjectResponse = await apiClient.get(`/api/projects/${projectSettingsModal.projectId}`);
      const updatedProject =
        updatedProjectResponse?.data?.project || updatedProjectResponse?.project || updatedProjectResponse;
      console.log("[ProjectDashboard] Updated project data:", updatedProject);
      setProjectSettingsModal(updatedProject);
      loadData();
    } catch (error: any) {
      console.error("Error adding project member:", error);
      showToast(error?.error || error?.message || "Failed to add member", "error");
    } finally {
      setAddingMember(false);
    }
  };

  // Get available workspace members (not already in project)
  const getAvailableTeamMembers = () => {
    if (!projectSettingsModal) return [];

    const projectMemberUsernames = new Set(projectSettingsModal.members?.map((m) => m.username.toLowerCase()) || []);

    return teamMembers.filter(
      (member) => !projectMemberUsernames.has(member.username.toLowerCase()) && member.status === "ACTIVE",
    );
  };

  const filteredProjects = useMemo(() => {
    console.log("[ProjectDashboard] Recalculating filteredProjects, projects:", projects.length);

    // Backend already filters projects by membership for non-owner/admin users
    // Just apply the local search filter here
    return projects.filter(
      (project) =>
        project.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        project.description.toLowerCase().includes(searchQuery.toLowerCase()),
    );
  }, [projects, searchQuery]);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <div className="text-center">
          <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-purple-600 mx-auto mb-4"></div>
          <p className="text-gray-600">Loading dashboard...</p>
        </div>
      </div>
    );
  }

  if (loadError) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <div className="text-center">
          <XCircle size={48} className="text-red-400 mx-auto mb-4" />
          <p className="text-gray-700 mb-4">{loadError}</p>
          <button
            onClick={loadData}
            className="px-6 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors"
          >
            Retry
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="h-screen flex flex-col bg-gray-50 overflow-hidden">
      {/* Toast Notification */}
      {toast && (
        <div
          className={`fixed top-4 right-4 z-[100] px-4 py-3 rounded-lg shadow-lg flex items-center gap-2 max-w-md animate-in slide-in-from-top-2 ${
            toast.type === "success"
              ? "bg-green-500 text-white"
              : toast.type === "error"
                ? "bg-red-500 text-white"
                : toast.type === "warning"
                  ? "bg-amber-500 text-white"
                  : "bg-blue-500 text-white"
          }`}
        >
          {toast.type === "success" && <CheckCircle size={18} />}
          {toast.type === "error" && <XCircle size={18} />}
          {toast.type === "warning" && <AlertTriangle size={18} />}
          {toast.type === "info" && <AlertTriangle size={18} />}
          <span className="text-sm font-medium">{toast.message}</span>
          <button onClick={() => setToast(null)} className="ml-2 hover:opacity-80">
            <X size={16} />
          </button>
        </div>
      )}

      {/* Browser-mode: open a local file when there is no pending file */}
      {!pendingFile && onOpenLocalFile && (
        <div className="bg-gradient-to-r from-indigo-600 to-blue-600 text-white px-4 py-3 flex-shrink-0">
          <div className="max-w-7xl mx-auto flex items-center justify-between">
            <div className="flex items-center gap-3">
              <FolderOpen size={22} className="flex-shrink-0" />
              <div>
                <p className="font-semibold text-sm">Open a local ontology file</p>
                <p className="text-xs text-indigo-100">Pick a .owl / .rdf / .ttl file from your computer</p>
              </div>
            </div>
            <button
              onClick={onOpenLocalFile}
              className="px-4 py-1.5 bg-white text-indigo-700 font-semibold text-sm rounded-lg hover:bg-indigo-50 transition-colors"
            >
              Browse…
            </button>
          </div>
        </div>
      )}

      {/* Pending File Upload Banner */}
      {pendingFile && (
        <div className="bg-gradient-to-r from-purple-600 to-blue-600 text-white px-4 py-3 flex-shrink-0">
          <div className="max-w-7xl mx-auto flex items-center justify-between">
            <div className="flex items-center gap-3">
              <FileText size={24} className="flex-shrink-0" />
              <div>
                <p className="font-semibold text-sm">📎 File Ready to Upload: {pendingFile.fileName}</p>
                <p className="text-xs text-purple-100">
                  Select a project below to upload this file ({(pendingFile.fileSize / (1024 * 1024)).toFixed(2)} MB)
                </p>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <CheckCircle size={20} className="text-green-300 animate-pulse" />
            </div>
          </div>
        </div>
      )}

      {/* Header */}
      <header className="bg-white border-b border-gray-200 flex-shrink-0">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex flex-col sm:flex-row sm:justify-between sm:items-center gap-3 py-3 sm:py-4">
            <div className="flex items-center gap-3 min-w-0">
              <button
                onClick={() => {
                  console.log("[ProjectDashboard] 🔙 Back to workspace clicked");
                  switchWorkspace();
                }}
                className="p-1.5 text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded-lg transition-colors"
                title="Back to Workspace"
              >
                <ArrowLeft size={20} />
              </button>
              <div className="min-w-0">
                <h1 className="text-xl sm:text-2xl font-bold text-gray-900 truncate">OntoCode</h1>
                <p className="text-xs sm:text-sm text-gray-500 truncate">
                  Welcome, {user?.username}
                  {user?.workspaceName && (
                    <button
                      onClick={() => {
                        console.log("[ProjectDashboard] 🔘 Switch workspace button clicked (inline)");
                        switchWorkspace();
                      }}
                      className="ml-2 px-2.5 py-0.5 bg-gradient-to-r from-purple-50 to-indigo-50 text-purple-700 border border-purple-200 rounded-full text-xs hover:from-purple-100 hover:to-indigo-100 hover:border-purple-300 hover:shadow-sm transition-all inline-flex items-center gap-1.5 font-medium max-w-[55vw] sm:max-w-none"
                      title="Click to switch workspace"
                    >
                      <Building2 size={11} />
                      <span className="truncate">{user.workspaceName}</span>
                    </button>
                  )}
                </p>
              </div>
            </div>
            <div className="flex items-center gap-2 sm:gap-3 flex-wrap sm:flex-nowrap justify-start sm:justify-end w-full sm:w-auto">
              {/* Workspace Subscription Plan Badge */}
              <button
                onClick={() => setShowPlanDetails(true)}
                className={`flex items-center gap-1.5 px-3 py-2 text-xs font-semibold rounded-lg transition-all hover:shadow-lg cursor-pointer ${
                  subscription.isEnterprise
                    ? "bg-gradient-to-r from-amber-500 to-orange-500 text-white hover:from-amber-600 hover:to-orange-600"
                    : subscription.isPro
                      ? "bg-gradient-to-r from-purple-500 to-blue-500 text-white hover:from-purple-600 hover:to-blue-600"
                      : "bg-gray-100 text-gray-700 hover:bg-gray-200"
                }`}
                title={`Workspace Plan: ${subscription.plan.toUpperCase()} - Click for details`}
              >
                {subscription.isEnterprise ? (
                  <Crown size={14} />
                ) : subscription.isPro ? (
                  <Zap size={14} />
                ) : (
                  <Sparkles size={14} />
                )}
                {subscription.plan.toUpperCase()}
              </button>
              <button
                onClick={() => {
                  console.log("[ProjectDashboard] 🔘 Switch workspace button clicked (main button)");
                  switchWorkspace();
                }}
                className="flex items-center gap-1.5 px-3 py-2 text-xs font-semibold rounded-lg transition-all hover:shadow-lg cursor-pointer bg-gradient-to-r from-indigo-500 to-purple-500 text-white hover:from-indigo-600 hover:to-purple-600"
                title="Switch Workspace"
              >
                <Building2 size={14} />
                <span className="hidden sm:inline">Switch Workspace</span>
              </button>
              {!isViewer && (
                <button
                  onClick={() => setShowCreateProject(true)}
                  className="flex items-center gap-1.5 px-3 py-2 text-xs font-semibold rounded-lg transition-all hover:shadow-lg cursor-pointer bg-gradient-to-r from-purple-500 to-blue-500 text-white hover:from-purple-600 hover:to-blue-600"
                  title="Create New Project"
                >
                  <Plus size={14} />
                  New Project
                </button>
              )}
              {onOpenEditor && (
                <button
                  onClick={onOpenEditor}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 text-xs text-blue-600 border border-blue-300 bg-blue-50 rounded-md hover:bg-blue-100 transition-colors font-medium"
                  title="Open OntoCode Editor"
                >
                  <Code2 size={14} />
                  Editor
                </button>
              )}
              <button
                onClick={() => setShowUserGuide(true)}
                className="p-2 text-gray-600 hover:bg-gray-100 rounded-lg"
                title="User Guide"
              >
                <HelpCircle size={20} />
              </button>
              <button
                onClick={() => setIsReportIssueModalOpen(true)}
                className="p-2 text-gray-600 hover:bg-gray-100 rounded-lg"
                title="Report Issue"
              >
                <Bug size={20} />
              </button>
              {onManageSubscription && (
                <button
                  onClick={onManageSubscription}
                  className="p-2 text-purple-600 hover:bg-purple-50 rounded-lg"
                  title="Manage subscription"
                >
                  <CreditCard size={20} />
                </button>
              )}
              <button
                onClick={() => setShowSettings(true)}
                className="p-2 text-gray-600 hover:bg-gray-100 rounded-lg"
                title="Settings"
              >
                <Settings size={20} />
              </button>
              <button
                onClick={logout}
                className="flex items-center gap-2 px-3 sm:px-4 py-2 text-red-600 hover:bg-red-50 rounded-lg transition-colors"
              >
                <LogOut size={20} />
                Logout
              </button>
            </div>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-1 overflow-y-auto">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4 sm:py-8">
          {/* Search and View Controls */}
          <div className="flex justify-between items-center mb-6">
            <div className="flex-1 max-w-lg">
              <div className="relative">
                <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" size={20} />
                <input
                  type="text"
                  placeholder="Search projects..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full pl-10 pr-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                />
              </div>
            </div>
            <div className="flex gap-2 ml-4">
              <button
                onClick={() => setViewMode("grid")}
                className={`p-2 rounded-lg ${viewMode === "grid" ? "bg-purple-100 text-purple-600" : "text-gray-600 hover:bg-gray-100"}`}
              >
                <Grid size={20} />
              </button>
              <button
                onClick={() => setViewMode("list")}
                className={`p-2 rounded-lg ${viewMode === "list" ? "bg-purple-100 text-purple-600" : "text-gray-600 hover:bg-gray-100"}`}
              >
                <List size={20} />
              </button>
            </div>
          </div>

          {/* Projects Grid/List */}
          <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
            <div className="flex items-center justify-between mb-6">
              <h2 className="text-xl font-semibold text-gray-900 flex items-center gap-2">
                <FolderOpen size={24} className="text-purple-600" />
                Projects
                <span className="text-sm font-normal text-gray-500">({filteredProjects.length})</span>
              </h2>
            </div>

            <div className="pr-2 custom-scrollbar">
              {filteredProjects.length === 0 ? (
                <div className="text-center py-12">
                  <FolderOpen size={64} className="text-gray-300 mx-auto mb-4" />
                  <p className="text-gray-500 mb-4">
                    {searchQuery ? "No projects found matching your search" : "No projects yet"}
                  </p>
                  {!searchQuery && !isViewer && (
                    <button
                      onClick={() => setShowCreateProject(true)}
                      className="px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700"
                    >
                      Create Your First Project
                    </button>
                  )}
                </div>
              ) : (
                <div
                  className={viewMode === "grid" ? "grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4" : "space-y-3"}
                >
                  {filteredProjects.map((project) => (
                    <div
                      key={project.id}
                      onClick={() => onSelectProject(project.projectId, project.name)}
                      className={`
                                        border border-gray-200 rounded-lg p-4 cursor-pointer
                                        hover:border-purple-400 hover:shadow-md transition-all flex
                                        ${viewMode === "list" ? "items-center" : "items-start"}
                                    `}
                    >
                      <div className="flex-1 min-w-0">
                        <h3 className="font-semibold text-gray-900 flex items-center gap-2 mb-2">
                          <FolderOpen size={20} className="text-purple-600 flex-shrink-0" />
                          <span className="truncate">{project.name}</span>
                        </h3>
                        <p className="text-sm text-gray-600 mb-3 line-clamp-2">
                          {project.description || "No description"}
                        </p>
                        <div className="flex items-center gap-4 text-xs text-gray-500">
                          <span className="flex items-center gap-1">
                            <Users size={14} />
                            {project.memberCount} members
                          </span>
                          <span className="flex items-center gap-1">
                            <FolderOpen size={14} />
                            {project.fileCount} files
                          </span>
                          <span className="flex items-center gap-1">
                            <Calendar size={14} />
                            {new Date(project.updatedAt).toLocaleDateString()}
                          </span>
                        </div>
                      </div>
                      <div className="flex items-center gap-2 flex-shrink-0 ml-2">
                        <div className="relative">
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                setOpenMenuProjectId(
                                  openMenuProjectId === project.projectId ? null : project.projectId,
                                );
                              }}
                              className="p-1 hover:bg-gray-100 rounded"
                            >
                              <MoreVertical size={16} className="text-gray-400" />
                            </button>
                            {openMenuProjectId === project.projectId && (
                              <div className="absolute right-0 mt-1 w-48 bg-white border border-gray-200 rounded-lg shadow-lg z-10">
                                <button
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    openProjectSettings(project);
                                  }}
                                  className="w-full px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50 flex items-center gap-2"
                                >
                                  <Settings size={14} />
                                  Project Settings
                                </button>
                                {!isViewer && (project.ownerId === user?.userId || isWorkspaceOwner) && (
                                  <>
                                    <button
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        startRename(project.projectId, project.name);
                                      }}
                                      className="w-full px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50 flex items-center gap-2"
                                    >
                                      <Edit size={14} />
                                      Rename
                                    </button>
                                    <div className="border-t border-gray-100 my-1"></div>
                                    <button
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        handleDeleteProject(project);
                                      }}
                                      className="w-full px-4 py-2 text-left text-sm text-red-600 hover:bg-red-50 flex items-center gap-2"
                                    >
                                      <Trash2 size={14} />
                                      Delete Project
                                    </button>
                                  </>
                                )}
                              </div>
                            )}
                          </div>
                        {viewMode === "list" && <ChevronRight size={20} className="text-gray-400" />}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>

          {/* Workspace Members Section */}
          <div className="mt-8 bg-white rounded-lg shadow-sm border border-gray-200 p-6">
            <div className="flex items-center justify-between mb-6">
              <h2 className="text-xl font-semibold text-gray-900 flex items-center gap-2">
                <Users size={24} className="text-purple-600" />
                Workspace Members
                <span className="text-sm font-normal text-gray-500">({workspaceMembers.length})</span>
              </h2>
              {!subscription.canAccessFeature("hasBasicCollaboration") ? (
                // FREE plan — collaboration locked
                <div
                  className="flex items-center gap-2 px-3 py-2 text-sm border border-purple-200 rounded-lg bg-purple-50 text-purple-500 cursor-not-allowed"
                  title="Upgrade to Professional to invite workspace members"
                >
                  <UserPlus size={16} />
                  Invite Member
                  <span className="bg-purple-500 text-white text-[10px] px-1.5 py-0.5 rounded">PRO</span>
                </div>
              ) : (isWorkspaceOwner || isWorkspaceAdmin) ? (
                <button
                  onClick={() => setShowInviteMember(true)}
                  className={`flex items-center gap-2 px-3 py-2 text-sm border border-gray-300 rounded-lg hover:bg-gray-50 ${
                    !subscription.isWithinLimit(teamMembers.length, "maxTeamMembers")
                      ? "border-amber-300 bg-amber-50"
                      : ""
                  }`}
                  title={
                    !subscription.isWithinLimit(workspaceMembers.length, "maxTeamMembers")
                      ? `Limit reached (${subscription.limits.maxTeamMembers} members). Upgrade to add more.`
                      : "Invite a new workspace member"
                  }
                >
                  <UserPlus size={16} />
                  Invite Member
                  {!subscription.isWithinLimit(teamMembers.length, "maxTeamMembers") && (
                    <span className="bg-amber-500 text-white text-[10px] px-1 py-0.5 rounded">LIMIT</span>
                  )}
                </button>
              ) : (
                <div
                  className="flex items-center gap-2 px-3 py-2 text-sm border border-gray-200 rounded-lg bg-gray-50 text-gray-400 cursor-not-allowed"
                  title="Only workspace owners and admins can invite members"
                >
                  <UserPlus size={16} />
                  Invite Member
                  <Crown size={14} className="text-purple-400" />
                </div>
              )}
            </div>

            <div className="pr-2 custom-scrollbar">
              {teamMembers.length === 0 ? (
                <div className="text-center py-8 text-gray-500">No workspace members yet</div>
              ) : (
                <div className="space-y-2">
                  {teamMembers.map((member) => (
                    <div
                      key={member.id}
                      className="flex items-center justify-between p-3 border border-gray-200 rounded-lg hover:bg-gray-50"
                    >
                      <div className="flex items-center gap-3">
                        <div
                          className={`w-10 h-10 rounded-full flex items-center justify-center font-semibold ${
                            member.status === "PENDING"
                              ? "bg-yellow-100 text-yellow-600"
                              : "bg-purple-100 text-purple-600"
                          }`}
                        >
                          {member.username.charAt(0).toUpperCase()}
                        </div>
                        <div>
                          <div className="flex items-center gap-2">
                            <p className="font-medium text-gray-900">{member.username}</p>
                            {member.status === "PENDING" && (
                              <span className="px-2 py-0.5 text-xs bg-yellow-100 text-yellow-700 rounded-full">
                                Pending
                              </span>
                            )}
                          </div>
                          <p className="text-sm text-gray-500">{member.email}</p>
                        </div>
                      </div>
                      <div className="flex items-center gap-2">
                        <div className="flex items-center gap-2">
                          {member.roles.map((role) => {
                            const upperRole = role.toUpperCase();
                            const roleStyles = {
                              OWNER: "bg-yellow-500/20 text-yellow-500 border-yellow-500/30",
                              ADMIN: "bg-purple-500/20 text-purple-500 border-purple-500/30",
                              MEMBER: "bg-blue-500/20 text-blue-500 border-blue-500/30",
                              VIEWER: "bg-gray-500/20 text-gray-500 border-gray-500/30",
                            };
                            const style = roleStyles[upperRole as keyof typeof roleStyles] || roleStyles.MEMBER;
                            return (
                              <span key={role} className={`px-2 py-0.5 text-[10px] font-bold rounded border ${style}`}>
                                {upperRole}
                              </span>
                            );
                          })}
                        </div>
                        {(isWorkspaceOwner || isWorkspaceAdmin) &&
                          member.email !== user?.email &&
                          member.status !== "PENDING" &&
                          !member.roles.some(r => r.toUpperCase() === "OWNER") && (
                            <button
                              onClick={() => handleRemoveMember(member)}
                              className="p-1.5 hover:bg-red-50 rounded text-red-600 hover:text-red-700 transition-colors"
                              title="Remove member"
                            >
                              <Trash2 size={16} />
                            </button>
                          )}
                        {(isWorkspaceOwner || isWorkspaceAdmin) && member.status === "PENDING" && (
                          <button
                            onClick={() => handleCancelInvitation(member)}
                            className="p-1.5 hover:bg-red-50 rounded text-red-600 hover:text-red-700 transition-colors"
                            title="Cancel invitation"
                          >
                            <Trash2 size={16} />
                          </button>
                        )}
                        {member.email === user?.email && (
                          <span className="px-2 py-1 text-xs bg-blue-100 text-blue-700 rounded-full">You</span>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      </main>

      {/* Create Project Modal */}
      <CreateProjectModal isOpen={showCreateProject} onClose={() => setShowCreateProject(false)} onSuccess={loadData} />

      {/* Rename Project Modal */}
      {renaming && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-lg shadow-xl max-w-md w-full p-6">
            <h3 className="text-xl font-semibold mb-4">Rename Project</h3>
            <div className="mb-4">
              <label className="block text-sm font-medium text-gray-700 mb-2">New Project Name *</label>
              <input
                type="text"
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500"
                placeholder="Enter new name"
                autoFocus
                onKeyPress={(e) => {
                  if (e.key === "Enter") {
                    handleRenameProject();
                  }
                }}
              />
            </div>
            <div className="flex gap-3">
              <button
                onClick={() => {
                  setRenaming(null);
                  setNewName("");
                }}
                className="flex-1 px-4 py-2 border border-gray-300 rounded-lg hover:bg-gray-50"
              >
                Cancel
              </button>
              <button
                onClick={handleRenameProject}
                className="flex-1 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700"
              >
                Rename
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Invite Member Modal */}
      <InviteMemberModal
        isOpen={showInviteMember}
        onClose={() => setShowInviteMember(false)}
        workspaceId={user?.workspaceId || "default"}
        workspaceName={user?.workspaceName || "Workspace"}
        subscriptionPlan={user?.subscriptionPlan || "FREE"}
        currentMemberCount={teamMembers.length}
        maxMembers={subscription.limits.maxTeamMembers}
        existingMemberEmails={teamMembers.map((m) => m.email)}
        isWorkspaceOwner={isWorkspaceOwner}
        onUpgradePlan={() => {
          // Navigate to subscription plan page
          // if (window.vscode) {
          window.vscode.postMessage({ type: "showSubscriptionPlans" });
          // }
        }}
        onInvite={handleInviteMember}
      />

      {/* Confirmation Modal */}
      {confirmModal && (
        <ConfirmationModal
          isOpen={true}
          title={confirmModal.title}
          message={confirmModal.message}
          onConfirm={confirmModal.onConfirm}
          onCancel={() => setConfirmModal(null)}
          confirmText={confirmModal.confirmText}
          cancelText={confirmModal.cancelText}
          type={confirmModal.type}
        />
      )}

      {/* Settings Modal */}
      <SettingsModal
        isOpen={showSettings}
        onClose={() => setShowSettings(false)}
        onLogout={logout}
        user={{
          username: user?.username || "",
          email: user?.email,
          workspaceName: user?.workspaceName,
          workspaceId: user?.workspaceId,
        }}
      />

      {/* Project Settings Modal */}
      {projectSettingsModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-xl shadow-2xl max-w-2xl w-full max-h-[85vh] overflow-hidden flex flex-col">
            {/* Header */}
            <div className="flex items-center justify-between p-6 border-b">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 bg-purple-100 rounded-full flex items-center justify-center">
                  <FolderOpen size={20} className="text-purple-600" />
                </div>
                <div>
                  <h3 className="text-xl font-semibold text-gray-900">Project Settings</h3>
                  <p className="text-sm text-gray-500">{projectSettingsModal.name}</p>
                </div>
              </div>
              <button
                onClick={() => setProjectSettingsModal(null)}
                className="p-2 hover:bg-gray-100 rounded-lg transition-colors"
              >
                <X size={20} className="text-gray-500" />
              </button>
            </div>

            {/* Tabs */}
            <div className="flex border-b">
              <button
                onClick={() => setProjectSettingsTab("general")}
                className={`flex-1 px-4 py-3 text-sm font-medium transition-colors flex items-center justify-center gap-2 ${
                  projectSettingsTab === "general"
                    ? "text-purple-600 border-b-2 border-purple-600 bg-purple-50"
                    : "text-gray-600 hover:text-gray-900 hover:bg-gray-50"
                }`}
              >
                <FileText size={16} />
                General
              </button>
              <button
                onClick={() => setProjectSettingsTab("members")}
                className={`flex-1 px-4 py-3 text-sm font-medium transition-colors flex items-center justify-center gap-2 ${
                  projectSettingsTab === "members"
                    ? "text-purple-600 border-b-2 border-purple-600 bg-purple-50"
                    : "text-gray-600 hover:text-gray-900 hover:bg-gray-50"
                }`}
              >
                <Users size={16} />
                Members ({projectSettingsModal.members?.length || 0})
              </button>
              {!isViewer && (
                <button
                  onClick={() => setProjectSettingsTab("danger")}
                  className={`flex-1 px-4 py-3 text-sm font-medium transition-colors flex items-center justify-center gap-2 ${
                    projectSettingsTab === "danger"
                      ? "text-red-600 border-b-2 border-red-600 bg-red-50"
                      : "text-gray-600 hover:text-gray-900 hover:bg-gray-50"
                  }`}
                >
                  <AlertTriangle size={16} />
                  Danger Zone
                </button>
              )}
            </div>

            {/* Content */}
            <div className="flex-1 overflow-y-auto p-6">
              {/* General Tab */}
              {projectSettingsTab === "general" && (
                <div className="space-y-6">
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-2">Project Name</label>
                    <input
                      type="text"
                      value={editingProjectName}
                      onChange={(e) => setEditingProjectName(e.target.value)}
                      className={`w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent ${isViewer ? "bg-gray-100 cursor-not-allowed" : ""}`}
                      placeholder="Enter project name"
                      disabled={isViewer}
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-2">Description</label>
                    <textarea
                      value={editingProjectDescription}
                      onChange={(e) => setEditingProjectDescription(e.target.value)}
                      rows={4}
                      className={`w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent resize-none ${isViewer ? "bg-gray-100 cursor-not-allowed" : ""}`}
                      placeholder="Enter project description"
                      disabled={isViewer}
                    />
                  </div>
                  <div className="bg-gray-50 rounded-lg p-4">
                    <h4 className="text-sm font-medium text-gray-700 mb-2">Project Info</h4>
                    <div className="grid grid-cols-2 gap-4 text-sm">
                      <div>
                        <span className="text-gray-500">Created:</span>
                        <span className="ml-2 text-gray-900">
                          {new Date(projectSettingsModal.createdAt).toLocaleDateString()}
                        </span>
                      </div>
                      <div>
                        <span className="text-gray-500">Last Updated:</span>
                        <span className="ml-2 text-gray-900">
                          {new Date(projectSettingsModal.updatedAt).toLocaleDateString()}
                        </span>
                      </div>
                      <div>
                        <span className="text-gray-500">Files:</span>
                        <span className="ml-2 text-gray-900">{projectSettingsModal.fileCount}</span>
                      </div>
                      <div>
                        <span className="text-gray-500">Members:</span>
                        <span className="ml-2 text-gray-900">{projectSettingsModal.memberCount}</span>
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {/* Members Tab */}
              {projectSettingsTab === "members" && (
                <div className="space-y-4">
                  <div className="flex items-center justify-between">
                    <p className="text-sm text-gray-600">
                      {isViewer ? "View who has access to this project and their permissions." : "Manage who has access to this project and their permissions."}
                    </p>
                    {!isViewer && (projectSettingsModal.ownerId === user?.userId || isWorkspaceOwner) && (
                      <button
                        onClick={() => setShowAddMemberForm(!showAddMemberForm)}
                        className="flex items-center gap-2 px-3 py-2 bg-purple-600 text-white text-sm rounded-lg hover:bg-purple-700 transition-colors"
                      >
                        <UserPlus size={16} />
                        {showAddMemberForm ? "Cancel" : "Add Member"}
                      </button>
                    )}
                  </div>

                  {/* Add Member Form */}
                  {showAddMemberForm && (
                    <div className="bg-purple-50 border border-purple-200 rounded-lg p-4 space-y-3">
                      <h4 className="font-medium text-gray-900">Add New Member</h4>
                      {getAvailableTeamMembers().length > 0 ? (
                        <>
                          <div>
                            <label className="block text-sm font-medium text-gray-700 mb-1">Select Workspace Member</label>
                            <select
                              value={newMemberUsername}
                              onChange={(e) => {
                                setNewMemberUsername(e.target.value);
                                // Also set email for display purposes if needed
                                const selectedMember = getAvailableTeamMembers().find(
                                  (m) => m.username === e.target.value,
                                );
                                if (selectedMember) {
                                  setNewMemberEmail(selectedMember.email);
                                }
                              }}
                              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                              disabled={addingMember}
                            >
                              <option value="">-- Select a workspace member --</option>
                              {getAvailableTeamMembers().map((member) => (
                                <option key={member.id} value={member.username}>
                                  {member.username} ({member.email})
                                </option>
                              ))}
                            </select>
                            <p className="text-xs text-gray-500 mt-1">Choose from workspace workspace members</p>
                          </div>
                          <div>
                            <label className="block text-sm font-medium text-gray-700 mb-1">Role</label>
                            <select
                              value={newMemberRole}
                              onChange={(e) => setNewMemberRole(e.target.value)}
                              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500"
                              disabled={addingMember}
                            >
                              <option value="VIEWER">Viewer - Read only access</option>
                              <option value="EDITOR">Editor - Can edit files</option>
                              <option value="ADMIN">Admin - Full access</option>
                            </select>
                          </div>
                          <button
                            onClick={handleAddProjectMember}
                            disabled={addingMember || !newMemberUsername.trim()}
                            className="w-full px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
                          >
                            {addingMember ? (
                              <>
                                <Loader2 size={16} className="animate-spin" />
                                Adding...
                              </>
                            ) : (
                              <>
                                <UserPlus size={16} />
                                Add Member
                              </>
                            )}
                          </button>
                        </>
                      ) : (
                        <div className="text-center py-4">
                          <p className="text-gray-600 mb-2">All workspace members have been added to this project.</p>
                          <p className="text-sm text-gray-500">
                            Invite more members to the workspace to add them here.
                          </p>
                        </div>
                      )}
                    </div>
                  )}

                  {projectSettingsModal.members && projectSettingsModal.members.length > 0 ? (
                    <div className="space-y-3">
                      {projectSettingsModal.members.map((member) => (
                        <div
                          key={member.userId}
                          className="flex items-center justify-between p-3 border border-gray-200 rounded-lg"
                        >
                          <div className="flex items-center gap-3">
                            <div className="w-10 h-10 bg-purple-100 rounded-full flex items-center justify-center font-semibold text-purple-600">
                              {member.username.charAt(0).toUpperCase()}
                            </div>
                            <div>
                              <p className="font-medium text-gray-900">{member.username}</p>
                              <p className="text-sm text-gray-500">{member.email}</p>
                            </div>
                          </div>
                          <div className="flex items-center gap-2">
                            {isViewer ? (
                              <span className="text-sm px-2 py-1 bg-gray-100 text-gray-700 rounded">
                                {member.role}
                              </span>
                            ) : (
                              <>
                                <select
                                  value={member.role}
                                  onChange={(e) =>
                                    handleUpdateProjectMemberRole(projectSettingsModal, member, e.target.value)
                                  }
                                  className="text-sm border border-gray-300 rounded-lg px-2 py-1 focus:ring-2 focus:ring-purple-500"
                                  disabled={member.role === "OWNER" || (projectSettingsModal.ownerId !== user?.userId && !isWorkspaceOwner)}
                                >
                                  <option value="OWNER">Owner</option>
                                  <option value="ADMIN">Admin</option>
                                  <option value="EDITOR">Editor</option>
                                  <option value="VIEWER">Viewer</option>
                                </select>
                                {member.role !== "OWNER" && (projectSettingsModal.ownerId === user?.userId || isWorkspaceOwner) && (
                                  <button
                                    onClick={() => handleRemoveProjectMember(projectSettingsModal, member)}
                                    className="p-2 text-red-600 hover:bg-red-50 rounded-lg transition-colors"
                                    title="Remove member"
                                  >
                                    <UserMinus size={16} />
                                  </button>
                                )}
                              </>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <div className="text-center py-8 text-gray-500">
                      <Users size={48} className="mx-auto mb-3 text-gray-300" />
                      <p>No members in this project yet</p>
                    </div>
                  )}
                </div>
              )}

              {/* Danger Zone Tab */}
              {projectSettingsTab === "danger" && (
                <div className="space-y-6">
                  <div className="bg-red-50 border border-red-200 rounded-lg p-4">
                    <h4 className="text-lg font-semibold text-red-800 mb-2 flex items-center gap-2">
                      <AlertTriangle size={20} />
                      Danger Zone
                    </h4>
                    <p className="text-sm text-red-700 mb-4">
                      Actions in this section are irreversible. Please be certain before proceeding.
                    </p>
                    <div className="space-y-4">
                      <div className="flex items-center justify-between p-4 bg-white border border-red-200 rounded-lg">
                        <div>
                          <h5 className="font-medium text-gray-900">Delete this project</h5>
                          <p className="text-sm text-gray-500">
                            Once deleted, all files and data will be permanently removed.
                          </p>
                        </div>
                        <button
                          onClick={() => handleDeleteProject(projectSettingsModal)}
                          className="px-4 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 transition-colors flex items-center gap-2"
                        >
                          <Trash2 size={16} />
                          Delete Project
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              )}
            </div>

            {/* Footer */}
            {projectSettingsTab === "general" && (
              <div className="flex items-center justify-end gap-3 p-6 border-t bg-gray-50">
                <button
                  onClick={() => setProjectSettingsModal(null)}
                  className="px-6 py-2 border border-gray-300 rounded-lg hover:bg-gray-100 font-medium transition-colors"
                >
                  {isViewer ? "Close" : "Cancel"}
                </button>
                {!isViewer && (
                  <button
                    onClick={handleSaveProjectSettings}
                    disabled={savingProject || !editingProjectName.trim()}
                    className="px-6 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 font-medium transition-colors disabled:opacity-50 flex items-center gap-2"
                  >
                    {savingProject && <Loader2 size={16} className="animate-spin" />}
                    <Save size={16} />
                    Save Changes
                  </button>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Plan Details Modal */}
      {showPlanDetails && (
        <Suspense fallback={null}>
          <PlanDetailsModal
            isOpen={showPlanDetails}
            onClose={() => setShowPlanDetails(false)}
            onUpgrade={handleUpgradePlan}
            isUpgrading={upgradingPlan}
            workspaceId={user?.workspaceId || ''}
          />
        </Suspense>
      )}

      {/* User Guide Modal */}
      <UserGuideModal isOpen={showUserGuide} onClose={() => setShowUserGuide(false)} />

      {/* Report Issue Modal */}
      {isReportIssueModalOpen && (
        <ReportIssueModal onClose={() => setIsReportIssueModalOpen(false)} />
      )}
    </div>
  );
};;

export default ProjectDashboard;

import React, { useState, useEffect, useMemo, useRef, Suspense, lazy } from "react";
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
  Lock,
  UserMinus,
  Save,
  Loader2,
  Crown,
  Zap,
  Sparkles,
  ArrowLeft,
  HelpCircle,
  CreditCard,
} from "lucide-react";
import apiClient from "../services/apiClient";
import { useAuth } from "../custom-hook/useAuth";
import { useSubscription } from "../hooks/useSubscription";
import { isDesktop, prewarmDesktopReasoningServices } from "../utils/desktop";
import { clearSessionCache } from "../utils/sessionCleanup";
import InviteMemberModal from "./InviteMemberModal";
import SettingsModal from "./SettingsModal";
import CreateProjectModal from "./CreateProjectModal";
import ConfirmationModal from "./ConfirmationModal";
const PlanDetailsModal = lazy(() => import("./PlanDetailsModal"));
import { UserGuideModal } from "./UserGuideModal";
import { ReportIssueModal } from "./ReportIssueModal";
import { Bug, ListOrdered } from "lucide-react";
import AdminSettingsModal from "./AdminSettingsModal";
import { OntoCodeLogo } from "./OntoCodeLogo";
import { AppVersionBadge } from "./AppVersionBadge";
import { getAppVersion } from "../utils/appVersion";
import {
  WORKSPACE_ROLES,
  type WorkspaceRole,
  normalizeRole,
  canCreateProjectsInWorkspace,
  canManageWorkspaceMembership,
  canEditProjectContent,
  canManageProjectSettings,
  parseProjectRole,
} from "../utils/roles";


type ProjectShareRole = "VIEWER" | "DRAFT_EDITOR" | "EDITOR";
type ShareWithMode = "none" | "all" | "specific";

interface WorkspaceMemberOption {
  userId: string;
  username: string;
  email: string;
  role?: string;
  status?: string;
}

interface ShareMemberSelection {
  email: string;
  role: ProjectShareRole;
}

interface ShareSelection {
  shareWith: ShareWithMode;
  members: ShareMemberSelection[];
}

const EMPTY_SHARE_SELECTION: ShareSelection = { shareWith: "none", members: [] };

const SHARE_MEMBER_SEARCH_THRESHOLD = 5;

const shareNormalizeEmail = (email?: string | null) => (email || "").trim().toLowerCase();

const isActiveWorkspaceMemberOption = (member: WorkspaceMemberOption) =>
  String(member.status || "ACTIVE").toUpperCase() === "ACTIVE" && !!member.userId && !!member.email;

const isPrivilegedMemberOption = (member: WorkspaceMemberOption) =>
  member.role?.toUpperCase() === "OWNER" || member.role?.toUpperCase() === "ADMIN";

interface ShareWithSelectorProps {
  workspaceId?: string;
 
  excludeEmails?: string[];
  
  autoIncludePrivileged?: boolean;
  selection: ShareSelection;
  onSelectionChange: (selection: ShareSelection) => void;
  refreshKey?: number | string;
  noMembersHint?: string;
}

const ShareWithSelector: React.FC<ShareWithSelectorProps> = ({
  workspaceId,
  excludeEmails = [],
  autoIncludePrivileged = false,
  selection,
  onSelectionChange,
  refreshKey,
  noMembersHint = "Invite another member to the workspace first, or use All Workspace Members.",
}) => {
  const [workspaceMembers, setWorkspaceMembers] = useState<WorkspaceMemberOption[]>([]);
  const [loadingMembers, setLoadingMembers] = useState(false);
  const [membersLoadError, setMembersLoadError] = useState<string | null>(null);
  const [memberSearch, setMemberSearch] = useState("");

  const excludeSet = useMemo(() => new Set(excludeEmails.map(shareNormalizeEmail)), [excludeEmails]);

  const loadWorkspaceMembers = async () => {
    if (!workspaceId) {
      setWorkspaceMembers([]);
      setMembersLoadError("Select a workspace before choosing specific members.");
      return;
    }
    try {
      setLoadingMembers(true);
      setMembersLoadError(null);
      const response = await apiClient.get(`/api/workspaces/${workspaceId}`);
      const workspaceData = response?.data || response;
      const members: WorkspaceMemberOption[] = workspaceData?.members || [];
      const filtered = members
        .filter(isActiveWorkspaceMemberOption)
        .filter((member) => !excludeSet.has(shareNormalizeEmail(member.email)))
        .map((member) => ({
          ...member,
          email: member.email.trim(),
          username: member.username || member.email.split("@")[0],
        }))
        .sort((a, b) => a.username.localeCompare(b.username, undefined, { sensitivity: "base" }));
      setWorkspaceMembers(filtered);
    } catch (error) {
      console.error("Error loading workspace members:", error);
      setWorkspaceMembers([]);
      setMembersLoadError("Could not load workspace members. Try again.");
    } finally {
      setLoadingMembers(false);
    }
  };

  useEffect(() => {
    loadWorkspaceMembers();
    setMemberSearch("");
  }, [workspaceId, refreshKey]);

  useEffect(() => {
    if (!autoIncludePrivileged || selection.shareWith !== "specific") return;
    const privileged = workspaceMembers.filter(isPrivilegedMemberOption);
    if (privileged.length === 0) return;

    const existingEmails = new Set(selection.members.map((m) => shareNormalizeEmail(m.email)));
    const missing = privileged.filter((p) => !existingEmails.has(shareNormalizeEmail(p.email)));
    if (missing.length === 0) return;

    onSelectionChange({
      shareWith: selection.shareWith,
      members: [
        ...missing.map((m) => ({ email: m.email.trim(), role: "EDITOR" as ProjectShareRole })),
        ...selection.members,
      ],
    });
  }, [autoIncludePrivileged, workspaceMembers, selection.shareWith]);

  const setMode = (mode: ShareWithMode) => {
    onSelectionChange({ shareWith: mode, members: mode === "specific" ? selection.members : [] });
  };

  const toggleMember = (email: string, privileged = false) => {
    if (privileged) return;
    const normalizedEmail = shareNormalizeEmail(email);
    const isSelected = selection.members.some((m) => shareNormalizeEmail(m.email) === normalizedEmail);

    if (isSelected) {
      onSelectionChange({
        ...selection,
        members: selection.members.filter((m) => shareNormalizeEmail(m.email) !== normalizedEmail),
      });
    } else {
      onSelectionChange({
        ...selection,
        members: [...selection.members, { email: email.trim(), role: "VIEWER" }],
      });
    }
  };

  const getMemberRole = (email: string): ProjectShareRole =>
    selection.members.find((m) => shareNormalizeEmail(m.email) === shareNormalizeEmail(email))?.role || "VIEWER";

  const setMemberRole = (email: string, role: ProjectShareRole) => {
    const normalizedEmail = shareNormalizeEmail(email);
    onSelectionChange({
      ...selection,
      members: selection.members.map((m) => (shareNormalizeEmail(m.email) === normalizedEmail ? { ...m, role } : m)),
    });
  };

  const hasShareableMembers = workspaceMembers.length > 0;
  const shareableMemberCount = workspaceMembers.length;

  const visibleMembers = useMemo(() => {
    const query = memberSearch.trim().toLowerCase();
    const filtered = query
      ? workspaceMembers.filter(
          (member) =>
            member.username.toLowerCase().includes(query) || member.email.toLowerCase().includes(query),
        )
      : workspaceMembers;

    const privileged = filtered.filter(isPrivilegedMemberOption);
    const regular = filtered.filter((m) => !isPrivilegedMemberOption(m));
    return [...privileged, ...regular];
  }, [workspaceMembers, memberSearch]);

  return (
    <div className="space-y-3">
      <label className="flex items-start gap-3 p-3 border border-gray-200 rounded-lg cursor-pointer hover:bg-gray-50">
        <input
          type="radio"
          name="shareWith"
          value="none"
          checked={selection.shareWith === "none"}
          onChange={() => setMode("none")}
          className="mt-0.5"
        />
        <div>
          <div className="font-medium text-gray-900">Only me</div>
          <div className="text-sm text-gray-500">No one else will be added</div>
        </div>
      </label>

      <label className="flex items-start gap-3 p-3 border border-gray-200 rounded-lg cursor-pointer hover:bg-gray-50">
        <input
          type="radio"
          name="shareWith"
          value="all"
          checked={selection.shareWith === "all"}
          onChange={() => setMode("all")}
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
                {shareableMemberCount} active workspace member{shareableMemberCount === 1 ? "" : "s"} will be added as{" "}
                <strong>Viewer</strong>
              </>
            )}
          </div>
          {autoIncludePrivileged && (selection.shareWith === "all" || selection.shareWith === "specific") && (
            <p className="text-xs text-gray-500 mt-1 leading-snug">
              The workspace owner and workspace admins are always given at least <strong>Editor</strong> on shared
              projects. Only the workspace owner can remove them from the project later.
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
          checked={selection.shareWith === "specific"}
          disabled={!hasShareableMembers && !loadingMembers}
          onChange={() => setMode("specific")}
          className="mt-0.5"
        />
        <div className="flex-1">
          <div className="font-medium text-gray-900 flex items-center gap-2">
            Specific Members
            {selection.shareWith === "specific" && selection.members.length > 0 && (
              <span className="px-2 py-0.5 bg-purple-100 text-purple-700 text-xs rounded-full">
                {selection.members.length} selected
              </span>
            )}
          </div>
          <div className="text-sm text-gray-500">Choose active workspace members and their project role</div>
          {autoIncludePrivileged && selection.shareWith === "specific" && workspaceMembers.some(isPrivilegedMemberOption) && (
            <p className="text-xs text-purple-600 mt-1">Owner and admins are always included with Editor access.</p>
          )}
          {!hasShareableMembers && !loadingMembers && <p className="text-xs text-gray-500 mt-1">{noMembersHint}</p>}
        </div>
      </label>

      {selection.shareWith === "specific" && (
        <div className="ml-8 space-y-3 border border-gray-200 rounded-lg p-3 bg-gray-50">
          {workspaceMembers.length >= SHARE_MEMBER_SEARCH_THRESHOLD && !loadingMembers && !membersLoadError && (
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
              <p className="text-center text-sm text-gray-500 py-4">No members match your search.</p>
            ) : (
              visibleMembers.map((member) => {
                const privileged = autoIncludePrivileged && isPrivilegedMemberOption(member);
                const isSelected =
                  privileged ||
                  selection.members.some((m) => shareNormalizeEmail(m.email) === shareNormalizeEmail(member.email));
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
                        {isPrivilegedMemberOption(member) && (
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
                        onChange={(e) => setMemberRole(member.email, e.target.value as ProjectShareRole)}
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
  );
};


interface ProjectMember {
  userId: string;
  username: string;
  email: string;
  role: string;
  joinedAt: string;
  /** WORKSPACE_OWNER | WORKSPACE_ADMIN when auto-linked on shared projects */
  workspaceEditorLink?: string | null;
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
  isPrivateRestricted?: boolean;
}

interface TeamMember {
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
  onManageSubscription?: () => void;
  onOpenSubscriptionPlans?: () => void;
}

const ProjectDashboard: React.FC<ProjectDashboardProps> = ({
  onSelectProject,
  pendingFile,
  onOpenLocalFile,
  onManageSubscription,
  onOpenSubscriptionPlans,
}) => {
  const { user, logout, switchWorkspace, refreshPermissions } = useAuth();
  console.log("[ProjectDashboard] Rendered with user:", {
    email: user?.email,
    workspaceId: user?.workspaceId,
    workspaceName: user?.workspaceName,
  });
  console.log("[ProjectDashboard] switchWorkspace function:", typeof switchWorkspace);

  const subscription = useSubscription();
  const isOwner = user?.workspaceRole === "OWNER";
  const [projects, setProjects] = useState<Project[]>([]);
  const [teamMembers, setTeamMembers] = useState<TeamMember[]>([]);
  const [workspaceOwnerId, setWorkspaceOwnerId] = useState<string | null>(null);
  const [workspaceDisplayName, setWorkspaceDisplayName] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [viewMode, setViewMode] = useState<"grid" | "list">("grid");
  const [searchQuery, setSearchQuery] = useState("");
  const [showCreateProject, setShowCreateProject] = useState(false);
  const [showInviteMember, setShowInviteMember] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showAdminSettings, setShowAdminSettings] = useState(false);
  const [appVersion, setAppVersion] = useState("");
  const [showPlanDetails, setShowPlanDetails] = useState(false);
  const [showUserGuide, setShowUserGuide] = useState(false);
  const [reportIssueModalType, setReportIssueModalType] = useState<"Bug" | "Task" | null>(null);
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
  const [addMemberSelection, setAddMemberSelection] = useState<ShareSelection>(EMPTY_SHARE_SELECTION);
  const [addMemberSelectorKey, setAddMemberSelectorKey] = useState(0);
  const [addingMembers, setAddingMembers] = useState(false);

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
  const confirmResolveRef = useRef<((value: boolean) => void) | null>(null);

  const currentUserInTeam = useMemo(() => teamMembers.find(m => m.id === user?.userId || m.email === user?.email || m.username === user?.username), [teamMembers, user]);
  const isWorkspaceOwner =
    user?.userId === workspaceOwnerId ||
    user?.workspaceRole?.toUpperCase() === "OWNER" ||
    currentUserInTeam?.roles?.some((r) => r.toUpperCase() === "OWNER");

  const projectMemberRoleLocked = (member: ProjectMember) =>
    member.workspaceEditorLink === "WORKSPACE_OWNER" ||
    (member.workspaceEditorLink === "WORKSPACE_ADMIN" && !isWorkspaceOwner);

  const canRemoveThisProjectMember = (member: ProjectMember) =>
    member.role !== "OWNER" &&
    member.workspaceEditorLink !== "WORKSPACE_OWNER" &&
    (member.workspaceEditorLink !== "WORKSPACE_ADMIN" || isWorkspaceOwner);

  /** Resolved workspace role: JWT, team membership, or workspace owner id. */
  const workspaceRoleResolved = useMemo((): WorkspaceRole | null => {
    const jwt = normalizeRole(user?.workspaceRole);
    if ((WORKSPACE_ROLES as readonly string[]).includes(jwt)) return jwt as WorkspaceRole;
    if (user?.userId && workspaceOwnerId && user.userId === workspaceOwnerId) return "OWNER";
    const fromTeam = currentUserInTeam?.roles
      ?.map((r) => normalizeRole(r))
      .find((r) => (WORKSPACE_ROLES as readonly string[]).includes(r));
    return fromTeam ? (fromTeam as WorkspaceRole) : null;
  }, [user?.workspaceRole, user?.userId, workspaceOwnerId, currentUserInTeam]);

  const effectiveWorkspaceRole = workspaceRoleResolved;

  const canCreateProjects = canCreateProjectsInWorkspace(effectiveWorkspaceRole);
  const canInviteMembers = canManageWorkspaceMembership(effectiveWorkspaceRole);

  const projectRoleForUser = (project: Project | null): ReturnType<typeof parseProjectRole> => {
    if (!project || !user?.userId) return null;
    if (project.ownerId === user.userId) return "OWNER";
    const m = project.members?.find((mem) => mem.userId === user.userId);
    return parseProjectRole(m?.role);
  };

  const canEditOpenProject = projectSettingsModal
    ? canEditProjectContent(projectRoleForUser(projectSettingsModal), effectiveWorkspaceRole)
    : false;

  const canManageOpenProject = projectSettingsModal
    ? canManageProjectSettings(projectRoleForUser(projectSettingsModal), effectiveWorkspaceRole)
    : false;
  const currentWorkspaceName = isDesktop()
    ? "My projects"
    : workspaceDisplayName || user?.workspaceName || "Workspace";

  const canManageProjectRow = (project: Project) =>
    canManageProjectSettings(projectRoleForUser(project), effectiveWorkspaceRole);

  // Paid purchases are account-level and handled by the /subscription page.
  const handleUpgradePlan = async () => {
    setShowPlanDetails(false);
    setUpgradingPlan(false);
    if (onOpenSubscriptionPlans) {
      onOpenSubscriptionPlans();
    } else {
      window.vscode?.postMessage({ type: "showSubscriptionPlans" });
    }
  };

  // Helper function to show toast
  const showToast = (message: string, type: "success" | "error" | "warning" | "info" = "info") => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 4000);
  };

  // Settle any still-pending promise-based confirm before another dialog
  // replaces it: its awaiting caller must never hang, and a later cancel of
  // the replacing dialog must not resolve the stranded promise by mistake.
  const settlePendingConfirm = (value: boolean) => {
    if (confirmResolveRef.current) {
      confirmResolveRef.current(value);
      confirmResolveRef.current = null;
    }
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
    settlePendingConfirm(false);
    setConfirmModal(options);
  };

  const showConfirmDialog = (title: string, message: string, confirmLabel = "OK"): Promise<boolean> => {
    return new Promise<boolean>((resolve) => {
      settlePendingConfirm(false);
      confirmResolveRef.current = resolve;
      setConfirmModal({
        title,
        message,
        confirmText: confirmLabel,
        cancelText: "Cancel",
        type: "warning",
        onConfirm: () => {
          setConfirmModal(null);
          settlePendingConfirm(true);
        },
      });
    });
  };

  const clearCacheAndLogout = () => {
    clearSessionCache();
    logout();
  };

  useEffect(() => {
    loadData();
  }, []);

  // Warm Fuseki + SWRL in the background as soon as the dashboard is visible,
  // so their JVM startup happens during idle browsing rather than blocking
  // the first SPARQL/graph action or reasoning run inside a project later.
  useEffect(() => {
    prewarmDesktopReasoningServices();
  }, []);

  useEffect(() => {
    getAppVersion().then(setAppVersion).catch(() => setAppVersion(""));
  }, []);

  // Poll workspace state every 15s. Two responsibilities:
  //   1. Surface member changes (pending → active when invitees accept).
  //   2. Bug #38: detect when the owner upgrades / downgrades / cancels
  //      and force-refresh the JWT so members pick up the new plan
  //      without having to switch workspaces and back.
  useEffect(() => {
    if (isDesktop() || !user?.workspaceId) return;

    let lastSeenPlan = "";
    let lastSeenStatus = "";

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
        setWorkspaceDisplayName(workspaceData?.name || "");

        // ── Plan / billing-status change detection (Bug #38) ──
        const currentPlan = String(workspaceData?.subscriptionPlan || "").toUpperCase();
        const currentStatus = String(workspaceData?.billingStatus || "").toUpperCase();
        const planChanged = currentPlan && currentPlan !== lastSeenPlan;
        const statusChanged = currentStatus && currentStatus !== lastSeenStatus;
        if ((planChanged || statusChanged) && (lastSeenPlan || lastSeenStatus)) {
          console.log(
            "[ProjectDashboard] Subscription state changed (%s/%s -> %s/%s) — refreshing permissions",
            lastSeenPlan, lastSeenStatus, currentPlan, currentStatus,
          );
          // refreshPermissions re-issues the JWT and updates the user object,
          // which propagates the new `subscriptionPlan` through useSubscription.
          await refreshPermissions().catch(() => undefined);
        }
        lastSeenPlan = currentPlan || lastSeenPlan;
        lastSeenStatus = currentStatus || lastSeenStatus;
      } catch (e) {
        // Silently ignore polling errors
      }
    }, 15000);
    return () => clearInterval(interval);
  }, [user?.workspaceId, user?.subscriptionPlan, refreshPermissions]);

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

      // Workspace members / invites are cloud-only — desktop is single-user local.
      if (!isDesktop() && user?.workspaceId) {
        try {
          const workspaceResponse = await apiClient.get(`/api/workspaces/${user.workspaceId}`);
          const workspaceData = workspaceResponse?.data || workspaceResponse;
          const members = workspaceData?.members || [];

          // Store workspace owner ID
          setWorkspaceOwnerId(workspaceData?.ownerId || null);
          setWorkspaceDisplayName(workspaceData?.name || "");

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
    } catch (error: any) {
      // Bug #41: previously this auto-called clearCacheAndLogout(), which
      // wiped the session whenever projects failed to load (e.g. right
      // after the owner cancelled and the workspace lost paid access).
      // Result: the user couldn't even reach billing to renew. Now we
      // surface the error and offer routes back to billing / workspace
      // selection. Only an explicit 401 should force a re-login.
      console.error("Error loading dashboard data:", error);
      const status = error?.status ?? error?.response?.status;
      if (status === 401) {
        clearCacheAndLogout();
        return;
      }
      const fallback = "We couldn't load this workspace's projects.";
      const message = error?.data?.error || error?.message || fallback;
      setLoadError(
        status === 402 || status === 403
          ? `${message} This often means the workspace subscription was cancelled or expired. Renew the plan to restore access.`
          : message
      );
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
      const isActiveMember =
        existingMember &&
        existingMember.userId &&
        (existingMember.status === "ACTIVE" || !existingMember.status || existingMember.status === "active");

      if (isActiveMember) {
        showToast(`User "${email}" is already a member of this workspace.`, "warning");
        return;
      }

      // Check pending invitations (do not swallow duplicate detection in a catch)
      let pendingInvites: any[] = [];
      try {
        const pendingResponse = await apiClient.get(`/api/invitations/workspace/${user?.workspaceId}`);
        const raw = pendingResponse?.data ?? pendingResponse;
        pendingInvites = Array.isArray(raw) ? raw : raw?.invitations || [];
      } catch (e) {
        console.warn("Could not check pending invitations:", e);
        showToast("Could not verify pending invitations. Please try again.", "warning");
        return;
      }

      const pendingInvite = pendingInvites.find(
        (inv: any) => inv.email?.toLowerCase() === email.toLowerCase() && String(inv.status || "").toUpperCase() === "PENDING",
      );

      if (pendingInvite) {
        showToast(
          `An invitation was already sent to "${email}" on ${new Date(pendingInvite.createdAt).toLocaleDateString()}. Please wait for them to accept.`,
          "warning",
        );
        return;
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
    // Non-owner may leave the workspace (self-removal)
    if (member.email === user?.email) {
      if (member.roles.some((r) => r.toUpperCase() === "OWNER")) {
        showToast(
          "Workspace owners cannot leave. Transfer ownership or delete the workspace.",
          "warning",
        );
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

  
  const getAvailableWorkspaceMembersForProject = () => {
    if (!projectSettingsModal) return [];
    const projectMemberEmails = new Set(
      projectSettingsModal.members?.map((m) => m.email.toLowerCase()) || [],
    );
    return teamMembers.filter(
      (member) => member.status === "ACTIVE" && !projectMemberEmails.has(member.email.toLowerCase()),
    );
  };

  
  const handleAddSelectedMembers = async () => {
    if (!projectSettingsModal) return;
    if (addMemberSelection.shareWith === "none") {
      setShowAddMemberForm(false);
      return;
    }

    const membersToAdd =
      addMemberSelection.shareWith === "all"
        ? getAvailableWorkspaceMembersForProject().map((m) => ({ email: m.email, role: "VIEWER" as const }))
        : addMemberSelection.members;

    if (membersToAdd.length === 0) {
      showToast("Select at least one member to add, or choose a different sharing option.", "warning");
      return;
    }

    try {
      setAddingMembers(true);

      // Backend expects `email` (see ProjectController.AddMemberRequest) — one call per member.
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

      // Reset form
      setAddMemberSelection(EMPTY_SHARE_SELECTION);
      setAddMemberSelectorKey((k) => k + 1);
      setShowAddMemberForm(false);

      // Refresh project data
      const updatedProjectResponse = await apiClient.get(`/api/projects/${projectSettingsModal.projectId}`);
      const updatedProject =
        updatedProjectResponse?.data?.project || updatedProjectResponse?.project || updatedProjectResponse;
      console.log("[ProjectDashboard] Updated project data:", updatedProject);
      setProjectSettingsModal(updatedProject);
      loadData();
    } catch (error: any) {
      console.error("Error adding project member(s):", error);
      showToast(error?.error || error?.message || "Failed to add members", "error");
    } finally {
      setAddingMembers(false);
    }
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
      <div className="min-h-screen flex items-center justify-center bg-gray-50 p-6">
        <div className="text-center max-w-md">
          <XCircle size={48} className="text-red-400 mx-auto mb-4" />
          <p className="text-gray-800 font-semibold mb-2">Couldn't open this workspace</p>
          <p className="text-gray-600 mb-6 text-sm leading-relaxed">{loadError}</p>
          <div className="flex flex-wrap justify-center gap-3">
            <button
              onClick={loadData}
              className="px-5 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors"
            >
              Retry
            </button>
            {/* Bug #41: always offer a way out so the user isn't stranded. */}
            <button
              onClick={() => switchWorkspace()}
              className="px-5 py-2 bg-white border border-gray-300 text-gray-700 rounded-lg hover:bg-gray-100 transition-colors"
            >
              Switch Workspace
            </button>
            {isOwner && onManageSubscription && (
              <button
                onClick={onManageSubscription}
                className="px-5 py-2 bg-emerald-600 text-white rounded-lg hover:bg-emerald-700 transition-colors"
              >
                Manage Subscription
              </button>
            )}
            <button
              onClick={clearCacheAndLogout}
              className="px-5 py-2 border border-gray-300 text-gray-500 rounded-lg hover:bg-gray-100 transition-colors text-sm"
            >
              Log out
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="h-screen flex flex-col bg-gray-50 overflow-hidden">
      {/* Toast Notification */}
      {toast && (
        <div
          className={`fixed top-4 right-4 z-[100] px-4 py-3 rounded-lg shadow-lg flex items-center gap-2 max-w-md animate-in slide-in-from-top-2 ${toast.type === "success"
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
              {!isDesktop() && (
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
              )}
              <div className="min-w-0">
                <h1 className="text-xl sm:text-2xl font-bold text-gray-900 truncate flex items-center gap-2">
                  <OntoCodeLogo size={24} />
                  <span className="truncate">{currentWorkspaceName}</span>
                </h1>
                <p className="text-xs sm:text-sm text-gray-500 truncate">
                  OntoCode Studio Project Dashboard{appVersion ? ` v${appVersion}` : ""} · Welcome, {user?.username}
                  {!isDesktop() && currentWorkspaceName && (
                    <button
                      onClick={() => {
                        console.log("[ProjectDashboard] 🔘 Switch workspace button clicked (inline)");
                        switchWorkspace();
                      }}
                      className="ml-2 px-2.5 py-0.5 bg-gradient-to-r from-purple-50 to-indigo-50 text-purple-700 border border-purple-200 rounded-full text-xs hover:from-purple-100 hover:to-indigo-100 hover:border-purple-300 hover:shadow-sm transition-all inline-flex items-center gap-1.5 font-medium max-w-[55vw] sm:max-w-none"
                      title="Click to switch workspace"
                    >
                      <Building2 size={11} />
                      <span className="truncate">Switch workspace</span>
                    </button>
                  )}
                </p>
              </div>
            </div>
            <div className="flex items-center gap-2 sm:gap-3 flex-wrap sm:flex-nowrap justify-start sm:justify-end w-full sm:w-auto">
              <AppVersionBadge variant="header" />
              {/* Workspace Subscription Plan Badge — hidden in desktop (no plans/pricing) */}
              {!isDesktop() && (isWorkspaceOwner ? (
                <button
                  onClick={() => setShowPlanDetails(true)}
                  className={`h-9 inline-flex items-center justify-center gap-1.5 px-3 text-xs font-semibold rounded-lg transition-all hover:shadow-lg cursor-pointer ${subscription.isEnterprise
                      ? "bg-gradient-to-r from-amber-500 to-orange-500 text-white hover:from-amber-600 hover:to-orange-600"
                      : subscription.isPro
                        ? "bg-gradient-to-r from-purple-500 to-blue-500 text-white hover:from-purple-600 hover:to-blue-600"
                        : "bg-gray-100 text-gray-700 hover:bg-gray-200"
                    }`}
                  title={`Workspace Plan: ${subscription.plan.toUpperCase()} - Click for current plan details`}
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
              ) : (
                <span
                  className="h-8 inline-flex items-center justify-center gap-1.5 px-3 text-[11px] font-semibold rounded-full border border-slate-600/70 bg-slate-800/70 text-slate-300"
                  title={`Workspace Plan: ${subscription.plan.toUpperCase()}`}
                >
                  <Shield size={12} className="text-slate-400" />
                  Plan: {subscription.plan.toUpperCase()}
                </span>
              ))}
              {!isDesktop() && isWorkspaceOwner && onOpenSubscriptionPlans && !subscription.isEnterprise && (
                <button
                  onClick={onOpenSubscriptionPlans}
                  className="h-9 inline-flex items-center justify-center gap-1.5 px-3 text-xs font-semibold rounded-lg transition-all hover:shadow-lg bg-gradient-to-r from-emerald-500 to-teal-500 text-white hover:from-emerald-600 hover:to-teal-600"
                  title="Upgrade your account plan"
                >
                  <Crown size={14} />
                  Upgrade Plan
                </button>
              )}
              {!isDesktop() && (
                <button
                  onClick={() => {
                    console.log("[ProjectDashboard] 🔘 Switch workspace button clicked (main button)");
                    switchWorkspace();
                  }}
                  className="h-9 inline-flex items-center justify-center gap-1.5 px-3 text-xs font-semibold rounded-lg transition-all hover:shadow-lg cursor-pointer bg-gradient-to-r from-indigo-500 to-purple-500 text-white hover:from-indigo-600 hover:to-purple-600"
                  title="Switch Workspace"
                >
                  <Building2 size={14} />
                  <span className="hidden sm:inline">Switch Workspace</span>
                </button>
              )}
              {canCreateProjects && (
                <button
                  onClick={() => setShowCreateProject(true)}
                  className="h-9 inline-flex items-center justify-center gap-1.5 px-3 text-xs font-semibold rounded-lg transition-all hover:shadow-lg cursor-pointer bg-gradient-to-r from-purple-500 to-blue-500 text-white hover:from-purple-600 hover:to-blue-600"
                  title="Create New Project"
                >
                  <Plus size={14} />
                  New Project
                </button>
              )}
              <button
                onClick={() => setShowUserGuide(true)}
                className="h-9 w-9 inline-flex items-center justify-center text-gray-600 hover:bg-gray-100 rounded-lg"
                title="User Guide"
              >
                <HelpCircle size={20} />
              </button>
              <button
                onClick={() => setReportIssueModalType("Bug")}
                className="h-9 w-9 inline-flex items-center justify-center text-gray-600 hover:bg-gray-100 rounded-lg"
                title="Report Issue"
              >
                <Bug size={20} />
              </button>
              <button
                onClick={() => setReportIssueModalType("Task")}
                className="h-9 w-9 inline-flex items-center justify-center text-gray-600 hover:bg-gray-100 rounded-lg"
                title="Request a Feature"
              >
                <ListOrdered size={20} />
              </button>
              {!isDesktop() && (
                <button
                  onClick={() => window.dispatchEvent(new CustomEvent('navigate-desktop-download'))}
                  className="h-9 w-9 inline-flex items-center justify-center text-purple-600 hover:bg-purple-50 rounded-lg"
                  title="Download Desktop App"
                >
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="2" y="3" width="20" height="14" rx="2" /><path d="M8 21h8M12 17v4" /></svg>
                </button>
              )}
              {isOwner && onManageSubscription && !user?.enterpriseDomainBypass && (
                <button
                  onClick={onManageSubscription}
                  className="h-9 w-9 inline-flex items-center justify-center text-purple-600 hover:bg-purple-50 rounded-lg"
                  title="Manage subscription"
                >
                  <CreditCard size={20} />
                </button>
              )}
              {user?.isAdmin && (
                <button
                  onClick={() => setShowAdminSettings(true)}
                  className="h-9 w-9 inline-flex items-center justify-center text-purple-600 hover:bg-purple-50 rounded-lg"
                  title="Admin Settings"
                >
                  <Shield size={20} />
                </button>
              )}
              <button
                onClick={() => setShowSettings(true)}
                className="h-9 w-9 inline-flex items-center justify-center text-gray-600 hover:bg-gray-100 rounded-lg"
                title="Settings"
              >
                <Settings size={20} />
              </button>
              {!isDesktop() && (
                <button
                  onClick={() => logout()}
                  className="h-9 inline-flex items-center justify-center gap-2 px-3 sm:px-4 text-red-600 hover:bg-red-50 rounded-lg transition-colors"
                >
                  <LogOut size={20} />
                  Logout
                </button>
              )}
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
                  {!searchQuery && canCreateProjects && (
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
                      onClick={() => {
                        if (project.isPrivateRestricted) return;
                        onSelectProject(project.projectId, project.name);
                      }}
                      className={`
                                        border rounded-lg p-4 transition-all flex
                                        ${project.isPrivateRestricted
                          ? "border-gray-200 bg-gray-50 cursor-not-allowed opacity-70"
                          : "border-gray-200 cursor-pointer hover:border-purple-400 hover:shadow-md"}
                                        ${viewMode === "list" ? "items-center" : "items-start"}
                                    `}
                      title={project.isPrivateRestricted ? "Private project — you can rename or delete but cannot open it" : undefined}
                    >
                      <div className="flex-1 min-w-0">
                        <h3 className="font-semibold text-gray-900 flex items-center gap-2 mb-2">
                          {project.isPrivateRestricted
                            ? <Lock size={20} className="text-gray-400 flex-shrink-0" />
                            : <FolderOpen size={20} className="text-purple-600 flex-shrink-0" />}
                          <span className="truncate">{project.name}</span>
                          {project.isPrivateRestricted && (
                            <span className="ml-1 text-xs text-gray-400 font-normal">(private)</span>
                          )}
                        </h3>
                        <p className="text-sm text-gray-600 mb-3 line-clamp-2">
                          {project.description || "No description"}
                        </p>
                        <div className="flex items-center gap-4 text-xs text-gray-500">
                          {!isDesktop() && (
                            <span className="flex items-center gap-1">
                              <Users size={14} />
                              {project.memberCount} members
                            </span>
                          )}
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
                              {!project.isPrivateRestricted && (
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
                              )}
                              {canManageProjectRow(project) && (
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
                        {viewMode === "list" && !project.isPrivateRestricted && <ChevronRight size={20} className="text-gray-400" />}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>

          {/* Workspace Members — cloud collaboration only */}
          {!isDesktop() && (
            <div className="mt-8 bg-white rounded-lg shadow-sm border border-gray-200 p-6">
              <div className="flex items-center justify-between mb-6">
                <h2 className="text-xl font-semibold text-gray-900 flex items-center gap-2">
                  <Users size={24} className="text-purple-600" />
                  Workspace Members
                  <span className="text-sm font-normal text-gray-500">({teamMembers.length})</span>
                </h2>
                {!subscription.canAccessFeature("hasBasicCollaboration") ? (
                  isWorkspaceOwner && onOpenSubscriptionPlans ? (
                    <button
                      onClick={onOpenSubscriptionPlans}
                      className="h-9 min-w-[150px] inline-flex items-center justify-center gap-2 px-3 text-sm border border-purple-200 rounded-lg bg-purple-50 text-purple-600 hover:bg-purple-100 transition-colors"
                      title="Upgrade to Professional to invite workspace members"
                    >
                      <UserPlus size={16} />
                      Invite Member
                      <span className="bg-purple-500 text-white text-[10px] px-1.5 py-0.5 rounded">PRO</span>
                    </button>
                  ) : (
                    <div
                      className="h-9 min-w-[150px] inline-flex items-center justify-center gap-2 px-3 text-sm border border-purple-200 rounded-lg bg-purple-50 text-purple-500 cursor-not-allowed"
                      title="Only the workspace owner can upgrade to invite workspace members"
                    >
                      <UserPlus size={16} />
                      Invite Member
                      <span className="bg-purple-500 text-white text-[10px] px-1.5 py-0.5 rounded">PRO</span>
                    </div>
                  )
                ) : canInviteMembers ? (
                  <button
                    onClick={() => setShowInviteMember(true)}
                    className={`h-9 min-w-[150px] inline-flex items-center justify-center gap-2 px-3 text-sm border border-gray-300 rounded-lg hover:bg-gray-50 ${!subscription.isWithinLimit(teamMembers.length, "maxTeamMembers")
                        ? "border-amber-300 bg-amber-50"
                        : ""
                      }`}
                    title={
                      !subscription.isWithinLimit(teamMembers.length, "maxTeamMembers")
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
                    className="h-9 min-w-[150px] inline-flex items-center justify-center gap-2 px-3 text-sm border border-gray-200 rounded-lg bg-gray-50 text-gray-400 cursor-not-allowed"
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
                            className={`w-10 h-10 rounded-full flex items-center justify-center font-semibold ${member.status === "PENDING"
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
                          {canInviteMembers &&
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
                          {member.email === user?.email &&
                            !member.roles.some((r) => r.toUpperCase() === "OWNER") &&
                            member.status !== "PENDING" && (
                              <button
                                onClick={() => handleRemoveMember(member)}
                                className="px-2 py-1 text-xs font-medium text-red-600 hover:bg-red-50 rounded border border-red-200"
                                title="Leave this workspace"
                              >
                                Leave
                              </button>
                            )}
                          {canInviteMembers && member.status === "PENDING" && (
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
          )}
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

      {/* Invite Member Modal — cloud only */}
      {!isDesktop() && (
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
            setShowInviteMember(false);
            if (onOpenSubscriptionPlans) {
              onOpenSubscriptionPlans();
            } else {
              window.vscode?.postMessage({ type: "showSubscriptionPlans" });
            }
          }}
          onInvite={handleInviteMember}
        />
      )}

      {/* Confirmation Modal */}
      {confirmModal && (
        <ConfirmationModal
          isOpen={true}
          title={confirmModal.title}
          message={confirmModal.message}
          onConfirm={confirmModal.onConfirm}
          onCancel={() => {
            setConfirmModal(null);
            settlePendingConfirm(false);
          }}
          confirmText={confirmModal.confirmText}
          cancelText={confirmModal.cancelText}
          type={confirmModal.type}
        />
      )}

      {/* Admin Settings Modal */}
      <AdminSettingsModal
        isOpen={showAdminSettings}
        onClose={() => setShowAdminSettings(false)}
      />

      {/* Settings Modal */}
      <SettingsModal
        isOpen={showSettings}
        onClose={() => setShowSettings(false)}
        onLogout={logout}
        user={{
          username: user?.username || "",
          email: user?.email,
          workspaceName: currentWorkspaceName,
          workspaceId: user?.workspaceId,
        }}
        isWorkspaceOwner={isWorkspaceOwner}
        onWorkspaceRenamed={(workspaceName) => {
          setWorkspaceDisplayName(workspaceName);
          refreshPermissions().catch(() => undefined);
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
                className={`flex-1 px-4 py-3 text-sm font-medium transition-colors flex items-center justify-center gap-2 ${projectSettingsTab === "general"
                    ? "text-purple-600 border-b-2 border-purple-600 bg-purple-50"
                    : "text-gray-600 hover:text-gray-900 hover:bg-gray-50"
                  }`}
              >
                <FileText size={16} />
                General
              </button>
              {!isDesktop() && (
                <button
                  onClick={() => setProjectSettingsTab("members")}
                  className={`flex-1 px-4 py-3 text-sm font-medium transition-colors flex items-center justify-center gap-2 ${projectSettingsTab === "members"
                      ? "text-purple-600 border-b-2 border-purple-600 bg-purple-50"
                      : "text-gray-600 hover:text-gray-900 hover:bg-gray-50"
                    }`}
                >
                  <Users size={16} />
                  Members ({projectSettingsModal.members?.length || 0})
                </button>
              )}
              {canManageOpenProject && (
                <button
                  onClick={() => setProjectSettingsTab("danger")}
                  className={`flex-1 px-4 py-3 text-sm font-medium transition-colors flex items-center justify-center gap-2 ${projectSettingsTab === "danger"
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
                      className={`w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent ${!canEditOpenProject ? "bg-gray-100 cursor-not-allowed" : ""}`}
                      placeholder="Enter project name"
                      disabled={!canEditOpenProject}
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-2">Description</label>
                    <textarea
                      value={editingProjectDescription}
                      onChange={(e) => setEditingProjectDescription(e.target.value)}
                      rows={4}
                      className={`w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent resize-none ${!canEditOpenProject ? "bg-gray-100 cursor-not-allowed" : ""}`}
                      placeholder="Enter project description"
                      disabled={!canEditOpenProject}
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
                      {!isDesktop() && (
                        <div>
                          <span className="text-gray-500">Members:</span>
                          <span className="ml-2 text-gray-900">{projectSettingsModal.memberCount}</span>
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              )}

              {/* Members Tab — cloud collaboration only */}
              {!isDesktop() && projectSettingsTab === "members" && (
                <div className="space-y-4">
                  <div className="flex items-center justify-between">
                    <p className="text-sm text-gray-600">
                      {canManageOpenProject
                        ? "Manage who has access to this project and their project-level role (separate from workspace role)."
                        : "View who has access to this project and their project role."}
                    </p>
                    {canManageOpenProject && (
                      <button
                        onClick={() => {
                          if (showAddMemberForm) {
                            setAddMemberSelection(EMPTY_SHARE_SELECTION);
                          }
                          setShowAddMemberForm(!showAddMemberForm);
                        }}
                        className="flex items-center gap-2 px-3 py-2 bg-purple-600 text-white text-sm rounded-lg hover:bg-purple-700 transition-colors"
                      >
                        <UserPlus size={16} />
                        {showAddMemberForm ? "Cancel" : "Share with Members"}
                      </button>
                    )}
                  </div>

                  {/* Add Member — same "Share with" picker used in Create New Project */}
                  {showAddMemberForm && (
                    <div className="bg-purple-50 border border-purple-200 rounded-lg p-4 space-y-4">
                      <h4 className="font-medium text-gray-900">Share with</h4>
                      <ShareWithSelector
                        workspaceId={user?.workspaceId}
                        excludeEmails={projectSettingsModal.members?.map((m) => m.email) || []}
                        selection={addMemberSelection}
                        onSelectionChange={setAddMemberSelection}
                        refreshKey={addMemberSelectorKey}
                        noMembersHint="All active workspace members are already on this project."
                        autoIncludePrivileged
                      />
                      <button
                        onClick={handleAddSelectedMembers}
                        disabled={
                          addingMembers ||
                          (addMemberSelection.shareWith === "specific" && addMemberSelection.members.length === 0)
                        }
                        className="w-full px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
                      >
                        {addingMembers ? (
                          <>
                            <Loader2 size={16} className="animate-spin" />
                            Sharing...
                          </>
                        ) : (
                          <>
                            <UserPlus size={16} />
                            Share Access
                          </>
                        )}
                      </button>
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
                              {member.workspaceEditorLink && (
                                <p className="text-xs text-amber-700 mt-0.5">
                                  {member.workspaceEditorLink === "WORKSPACE_OWNER"
                                    ? "Workspace owner — always on shared projects"
                                    : "Workspace admin — removable only by workspace owner"}
                                </p>
                              )}
                            </div>
                          </div>
                          <div className="flex items-center gap-2">
                            {!canManageOpenProject ? (
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
                                  disabled={member.role === "OWNER" || projectMemberRoleLocked(member)}
                                >
                                  {member.role === "OWNER" && <option value="OWNER">Owner</option>}
                                  {member.role === "ADMIN" && <option value="ADMIN">Admin</option>}
                                  <option value="EDITOR">Editor</option>
                                  <option value="DRAFT_EDITOR">Draft Editor</option>
                                  <option value="VIEWER">Viewer</option>
                                </select>
                                {member.role !== "OWNER" && canManageOpenProject && canRemoveThisProjectMember(member) && (
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
                  {!canEditOpenProject ? "Close" : "Cancel"}
                </button>
                {canEditOpenProject && (
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
            currentPlanOnly
          />
        </Suspense>
      )}

      {/* User Guide Modal */}
      <UserGuideModal isOpen={showUserGuide} onClose={() => setShowUserGuide(false)} />

      {/* Report Issue Modal */}
      {reportIssueModalType && (
        <ReportIssueModal
          initialIssueType={reportIssueModalType}
          onClose={() => setReportIssueModalType(null)}
        />
      )}
    </div>
  );
}

export default ProjectDashboard;
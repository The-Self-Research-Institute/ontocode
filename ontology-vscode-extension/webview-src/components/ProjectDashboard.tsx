import React, { useState, useEffect, useMemo, Suspense, lazy } from "react";
import { useAuth } from "../custom-hook/useAuth";
import { useSubscription } from "../hooks/useSubscription";
import { useDashboardFeedback } from "../hooks/useDashboardFeedback";
import { useWorkspaceTeam } from "../hooks/useWorkspaceTeam";
import { useDashboardData } from "../hooks/useDashboardData";
import { useDashboardPermissions } from "../hooks/useDashboardPermissions";
import { useWorkspaceInvite } from "../hooks/useWorkspaceInvite";
import { useWorkspaceMemberActions } from "../hooks/useWorkspaceMemberActions";
import { useProjectSettingsModal } from "../hooks/useProjectSettingsModal";
import { useProjectActions } from "../hooks/useProjectActions";
import { useProjectMemberActions } from "../hooks/useProjectMemberActions";
import { useAddProjectMembers } from "../hooks/useAddProjectMembers";
import { isDesktop, prewarmDesktopReasoningServices } from "../utils/desktop";
import { clearSessionCache } from "../utils/sessionCleanup";
import { getAppVersion } from "../utils/appVersion";
import { filterProjectsBySearch } from "../utils/projectDashboardLogic";
import InviteMemberModal from "./InviteMemberModal";
import SettingsModal from "./SettingsModal";
import CreateProjectModal from "./CreateProjectModal";
import ConfirmationModal from "./ConfirmationModal";
import { UserGuideModal } from "./UserGuideModal";
import AdminSettingsModal from "./AdminSettingsModal";
import ToastNotification from "./ToastNotification";
import DashboardBanners from "./projectDashboard/DashboardBanners";
import DashboardHeader from "./projectDashboard/DashboardHeader";
import DashboardLoadError from "./projectDashboard/DashboardLoadError";
import ProjectsPanel from "./projectDashboard/ProjectsPanel";
import TeamMembersPanel from "./projectDashboard/TeamMembersPanel";
import RenameProjectModal from "./projectDashboard/RenameProjectModal";
import ProjectSettingsModal from "./projectDashboard/ProjectSettingsModal";
const PlanDetailsModal = lazy(() => import("./PlanDetailsModal"));

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
  const subscription = useSubscription();
  const feedback = useDashboardFeedback();
  const { toasts, dismissToast, showToast, confirmModal, setConfirmModal, showConfirm, showConfirmDialog } = feedback;
  const closeConfirm = () => setConfirmModal(null);

  const team = useWorkspaceTeam(user, refreshPermissions);
  const { teamMembers, workspaceOwnerId, workspaceDisplayName, setWorkspaceDisplayName } = team;

  const clearCacheAndLogout = () => {
    clearSessionCache();
    logout();
  };

  const { projects, loading, loadError, loadData } = useDashboardData(user, team, clearCacheAndLogout);
  const settings = useProjectSettingsModal(showToast, loadData);
  const { projectSettingsModal, setProjectSettingsModal, openProjectSettings } = settings;
  const perms = useDashboardPermissions(user, teamMembers, workspaceOwnerId, projectSettingsModal);
  const { isWorkspaceOwner } = perms;

  const { handleInviteMember } = useWorkspaceInvite(user, showToast);
  const memberActions = useWorkspaceMemberActions({
    user,
    switchWorkspace,
    showToast,
    showConfirm,
    showConfirmDialog,
    closeConfirm,
    loadData,
  });
  const projectActions = useProjectActions({
    showToast,
    showConfirm,
    closeConfirm,
    loadData,
    closeProjectSettings: () => setProjectSettingsModal(null),
  });
  const projectMemberActions = useProjectMemberActions({
    showToast,
    showConfirm,
    closeConfirm,
    loadData,
    setProjectSettingsModal,
  });
  const addMembers = useAddProjectMembers({
    projectSettingsModal,
    setProjectSettingsModal,
    teamMembers,
    showToast,
    loadData,
  });

  const [viewMode, setViewMode] = useState<"grid" | "list">("grid");
  const [searchQuery, setSearchQuery] = useState("");
  const [showCreateProject, setShowCreateProject] = useState(false);
  const [showInviteMember, setShowInviteMember] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showAdminSettings, setShowAdminSettings] = useState(false);
  const [appVersion, setAppVersion] = useState("");
  const [showPlanDetails, setShowPlanDetails] = useState(false);
  const [showUserGuide, setShowUserGuide] = useState(false);
  const [upgradingPlan, setUpgradingPlan] = useState(false);
  const [openMenuProjectId, setOpenMenuProjectId] = useState<string | null>(null);

  const currentWorkspaceName = isDesktop() ? "My projects" : workspaceDisplayName || user?.workspaceName || "Workspace";

  const openSubscriptionPlans = () => {
    if (onOpenSubscriptionPlans) {
      onOpenSubscriptionPlans();
    } else {
      window.vscode?.postMessage({ type: "showSubscriptionPlans" });
    }
  };

  const handleUpgradePlan = async () => {
    setShowPlanDetails(false);
    setUpgradingPlan(false);
    openSubscriptionPlans();
  };

  useEffect(() => {
    prewarmDesktopReasoningServices();
  }, []);

  useEffect(() => {
    getAppVersion().then(setAppVersion).catch(() => setAppVersion(""));
  }, []);

  useEffect(() => {
    const handleClickOutside = () => setOpenMenuProjectId(null);
    if (openMenuProjectId) {
      document.addEventListener("click", handleClickOutside);
      return () => document.removeEventListener("click", handleClickOutside);
    }
  }, [openMenuProjectId]);

  const filteredProjects = useMemo(() => filterProjectsBySearch(projects, searchQuery), [projects, searchQuery]);

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
      <DashboardLoadError
        loadError={loadError}
        isOwner={isWorkspaceOwner}
        onRetry={loadData}
        onSwitchWorkspace={() => switchWorkspace()}
        onManageSubscription={onManageSubscription}
        onLogout={clearCacheAndLogout}
      />
    );
  }

  return (
    <div className="h-screen flex flex-col bg-gray-50 overflow-hidden">
      <ToastNotification toasts={toasts} onDismiss={dismissToast} />

      <DashboardBanners pendingFile={pendingFile} onOpenLocalFile={onOpenLocalFile} />

      <DashboardHeader
        user={user}
        currentWorkspaceName={currentWorkspaceName}
        appVersion={appVersion}
        subscription={subscription}
        isWorkspaceOwner={isWorkspaceOwner}
        canCreateProjects={perms.canCreateProjects}
        switchWorkspace={switchWorkspace}
        logout={logout}
        onCreateProject={() => setShowCreateProject(true)}
        onShowUserGuide={() => setShowUserGuide(true)}
        onShowAdminSettings={() => setShowAdminSettings(true)}
        onShowSettings={() => setShowSettings(true)}
        onShowPlanDetails={() => setShowPlanDetails(true)}
        onManageSubscription={onManageSubscription}
        onOpenSubscriptionPlans={onOpenSubscriptionPlans}
      />

      <main className="flex-1 overflow-y-auto">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4 sm:py-8">
          <ProjectsPanel
            projects={filteredProjects}
            searchQuery={searchQuery}
            onSearchChange={setSearchQuery}
            viewMode={viewMode}
            onViewModeChange={setViewMode}
            canCreateProjects={perms.canCreateProjects}
            onCreateProject={() => setShowCreateProject(true)}
            openMenuProjectId={openMenuProjectId}
            onOpenMenuChange={setOpenMenuProjectId}
            canManageProjectRow={perms.canManageProjectRow}
            onSelectProject={onSelectProject}
            onOpenSettings={openProjectSettings}
            onRename={projectActions.startRename}
            onDelete={projectActions.handleDeleteProject}
          />

          {!isDesktop() && (
            <TeamMembersPanel
              teamMembers={teamMembers}
              subscription={subscription}
              currentUserEmail={user?.email}
              isWorkspaceOwner={isWorkspaceOwner}
              canInviteMembers={perms.canInviteMembers}
              onInvite={() => setShowInviteMember(true)}
              onOpenSubscriptionPlans={onOpenSubscriptionPlans}
              onTransferOwnership={memberActions.handleTransferWorkspaceOwnership}
              onRemove={memberActions.handleRemoveMember}
              onCancelInvitation={memberActions.handleCancelInvitation}
            />
          )}
        </div>
      </main>

      <CreateProjectModal isOpen={showCreateProject} onClose={() => setShowCreateProject(false)} onSuccess={loadData} />

      {projectActions.renaming && (
        <RenameProjectModal
          newName={projectActions.newName}
          onNameChange={projectActions.setNewName}
          onRename={projectActions.handleRenameProject}
          onCancel={projectActions.cancelRename}
        />
      )}

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
            openSubscriptionPlans();
          }}
          onInvite={handleInviteMember}
        />
      )}

      {confirmModal && (
        <ConfirmationModal
          isOpen={true}
          title={confirmModal.title}
          message={confirmModal.message}
          onConfirm={confirmModal.onConfirm}
          onCancel={feedback.cancelConfirm}
          confirmText={confirmModal.confirmText}
          cancelText={confirmModal.cancelText}
          type={confirmModal.type}
        />
      )}

      <AdminSettingsModal isOpen={showAdminSettings} onClose={() => setShowAdminSettings(false)} />

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

      {projectSettingsModal && (
        <ProjectSettingsModal
          project={projectSettingsModal}
          settings={settings}
          add={addMembers}
          workspaceId={user?.workspaceId}
          canEdit={perms.canEditOpenProject}
          canManage={perms.canManageOpenProject}
          isWorkspaceOwner={isWorkspaceOwner}
          currentUserIsProjectOwner={perms.projectRoleForUser(projectSettingsModal) === "OWNER"}
          onDeleteProject={projectActions.handleDeleteProject}
          onRoleChange={projectMemberActions.handleUpdateProjectMemberRole}
          onRemoveMember={projectMemberActions.handleRemoveProjectMember}
        />
      )}

      {showPlanDetails && (
        <Suspense fallback={null}>
          <PlanDetailsModal
            isOpen={showPlanDetails}
            onClose={() => setShowPlanDetails(false)}
            onUpgrade={handleUpgradePlan}
            isUpgrading={upgradingPlan}
            workspaceId={user?.workspaceId || ""}
            currentPlanOnly
          />
        </Suspense>
      )}

      <UserGuideModal isOpen={showUserGuide} onClose={() => setShowUserGuide(false)} />
    </div>
  );
};

export default ProjectDashboard;

import React from "react";
import {
  ArrowLeft,
  Bug,
  Building2,
  CreditCard,
  HelpCircle,
  ListOrdered,
  LogOut,
  Plus,
  Settings,
  Shield,
} from "lucide-react";
import { isDesktop } from "../../utils/desktop";
import { openFeedbackPage } from "../../utils/feedback";
import { OntoCodeLogo } from "../OntoCodeLogo";
import { AppVersionBadge } from "../AppVersionBadge";
import type { AuthUser, SubscriptionInfo } from "../../utils/projectDashboardTypes";
import WorkspacePlanControls from "./WorkspacePlanControls";

interface DashboardHeaderProps {
  user: AuthUser;
  currentWorkspaceName: string;
  appVersion: string;
  subscription: SubscriptionInfo;
  isWorkspaceOwner: boolean;
  canCreateProjects: boolean;
  switchWorkspace: () => void;
  logout: () => void;
  onCreateProject: () => void;
  onShowUserGuide: () => void;
  onShowAdminSettings: () => void;
  onShowSettings: () => void;
  onShowPlanDetails: () => void;
  onManageSubscription?: () => void;
  onOpenSubscriptionPlans?: () => void;
}

const DashboardHeader: React.FC<DashboardHeaderProps> = ({
  user,
  currentWorkspaceName,
  appVersion,
  subscription,
  isWorkspaceOwner,
  canCreateProjects,
  switchWorkspace,
  logout,
  onCreateProject,
  onShowUserGuide,
  onShowAdminSettings,
  onShowSettings,
  onShowPlanDetails,
  onManageSubscription,
  onOpenSubscriptionPlans,
}) => (
  <header className="bg-white border-b border-gray-200 flex-shrink-0">
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
      <div className="flex flex-col sm:flex-row sm:justify-between sm:items-center gap-3 py-3 sm:py-4">
        <div className="flex items-center gap-3 min-w-0">
          {!isDesktop() && (
            <button
              onClick={() => switchWorkspace()}
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
                  onClick={() => switchWorkspace()}
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
          <WorkspacePlanControls
            subscription={subscription}
            isWorkspaceOwner={isWorkspaceOwner}
            onShowPlanDetails={onShowPlanDetails}
            onOpenSubscriptionPlans={onOpenSubscriptionPlans}
          />
          {!isDesktop() && (
            <button
              onClick={() => switchWorkspace()}
              className="h-9 inline-flex items-center justify-center gap-1.5 px-3 text-xs font-semibold rounded-lg transition-all hover:shadow-lg cursor-pointer bg-gradient-to-r from-indigo-500 to-purple-500 text-white hover:from-indigo-600 hover:to-purple-600"
              title="Switch Workspace"
            >
              <Building2 size={14} />
              <span className="hidden sm:inline">Switch Workspace</span>
            </button>
          )}
          {canCreateProjects && (
            <button
              onClick={onCreateProject}
              className="h-9 inline-flex items-center justify-center gap-1.5 px-3 text-xs font-semibold rounded-lg transition-all hover:shadow-lg cursor-pointer bg-gradient-to-r from-purple-500 to-blue-500 text-white hover:from-purple-600 hover:to-blue-600"
              title="Create New Project"
            >
              <Plus size={14} />
              New Project
            </button>
          )}
          <button
            onClick={onShowUserGuide}
            className="h-9 w-9 inline-flex items-center justify-center text-gray-600 hover:bg-gray-100 rounded-lg"
            title="User Guide"
          >
            <HelpCircle size={20} />
          </button>
          <button
            onClick={() => openFeedbackPage("bug")}
            className="h-9 w-9 inline-flex items-center justify-center text-gray-600 hover:bg-gray-100 rounded-lg"
            title="Report Issue"
          >
            <Bug size={20} />
          </button>
          <button
            onClick={() => openFeedbackPage("feature")}
            className="h-9 w-9 inline-flex items-center justify-center text-gray-600 hover:bg-gray-100 rounded-lg"
            title="Request a Feature"
          >
            <ListOrdered size={20} />
          </button>
          {!isDesktop() && (
            <button
              onClick={() => window.dispatchEvent(new CustomEvent("navigate-desktop-download"))}
              className="h-9 w-9 inline-flex items-center justify-center text-purple-600 hover:bg-purple-50 rounded-lg"
              title="Download Desktop App"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="2" y="3" width="20" height="14" rx="2" /><path d="M8 21h8M12 17v4" /></svg>
            </button>
          )}
          {isWorkspaceOwner && onManageSubscription && !user?.enterpriseDomainBypass && (
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
              onClick={onShowAdminSettings}
              className="h-9 w-9 inline-flex items-center justify-center text-purple-600 hover:bg-purple-50 rounded-lg"
              title="Admin Settings"
            >
              <Shield size={20} />
            </button>
          )}
          <button
            onClick={onShowSettings}
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
);

export default DashboardHeader;

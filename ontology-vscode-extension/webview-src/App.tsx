import React, { useState, useEffect, useCallback, useMemo, useRef, Suspense, lazy } from "react";
import { useAuth } from "./custom-hook/useAuth";
import apiClient, { updateBaseUrl, ApiError } from "./services/apiClient";
import { openOntologyFile, fileContentToBase64 } from "./utils/fileAccess";
import { getGatewayUrl } from "./config/deploymentConfig";
import { CollaborationProvider } from "./contexts/CollaborationContext";
import { EntityPreferencesProvider } from "./contexts/EntityPreferencesContext";
import { ThemeProvider } from "./contexts/ThemeContext";
import Dashboard from "./components/Dashboard";
import LoginForm from "./components/LoginForm";
import SignupForm from "./components/SignupForm";
import DeploymentSelector from "./components/DeploymentSelector";
const WorkspaceSelection = lazy(() => import("./components/WorkspaceSelection"));
import ProjectDashboard from "./components/ProjectDashboard";
import ProjectLibrary from "./components/ProjectLibrary";
import SubscriptionPlanSelection from "./components/SubscriptionPlanSelection";
import InviteAcceptPage from "./components/InviteAcceptPage";
import EmailVerificationNotice from "./components/EmailVerificationNotice";
import ForgotPasswordForm from "./components/ForgotPasswordForm";
import ResetPasswordForm from "./components/ResetPasswordForm";
import { Loader2, RefreshCw } from "lucide-react";
import { useRouter, RouteState } from "./hooks/useRouter";
import { clearLastOpenedProjectState, SUPPRESS_WORKSPACE_AUTO_OPEN_KEY } from "./utils/sessionCleanup";
import {
  loadDesktopActiveFile,
  saveDesktopActiveFile,
  clearDesktopActiveFile,
  pathsEqual,
} from "./utils/desktopActiveFile";
import { isDesktop, getDesktopLicense, isLicenseExpired, licensePlan, DesktopLicense, DESKTOP_LICENSE_UPDATED_EVENT } from "./utils/desktop";
import AdminSettingsModal from "./components/AdminSettingsModal";
import MaintenancePage from "./components/MaintenancePage";
const BillingManagement = lazy(() => import("./components/BillingManagement"));
const DesktopDownloadPage = lazy(() => import("./components/DesktopDownloadPage"));
const DesktopUpdateBanner = lazy(() => import("./components/DesktopUpdateBanner"));
const PaymentSetupModal = lazy(() => import("./components/PaymentSetupModal"));

const getInitialInvitationFromLocation = (): { token: string | null; email: string | null } => {
  const pathname = window.location.pathname;

  // Don't treat reset-password or verify-email URLs as invitation tokens
  if (pathname.startsWith("/reset-password") || pathname.startsWith("/verify-email")) {
    return { token: null, email: null };
  }

  const params = new URLSearchParams(window.location.search);
  let token = params.get("token") || params.get("invite");
  let email = params.get("email");

  // Only grab generic ?token= if we're on an invitation/invite path or the root
  if (pathname.startsWith("/invitation") || pathname.startsWith("/invite")) {
    token = token || params.get("token") || params.get("invite");
    email = email || params.get("email");
  } else if (pathname !== "/" && pathname !== "") {
    // For non-root, non-invitation paths, don't treat ?token= as invitation
    token = params.get("invite");
  }

  // Support hash-based invitation links: #/invitation?token=... or #/invite?token=...
  if (window.location.hash) {
    const hashPart = window.location.hash.substring(1);
    const [path, queryString] = hashPart.split("?");
    if ((path.startsWith("/invitation") || path.startsWith("/invite")) && queryString) {
      const hashParams = new URLSearchParams(queryString);
      token = token || hashParams.get("token") || hashParams.get("invite");
      email = email || hashParams.get("email");
    }
  }

  // In test-web mode, token may be present on parent URL
  try {
    if (window.parent && window.parent !== window) {
      const parentParams = new URLSearchParams(window.parent.location.search);
      token = token || parentParams.get("token") || parentParams.get("invite");
      email = email || parentParams.get("email");
    }
  } catch {
    // Ignore cross-origin access errors
  }

  return { token, email };
};

const AppContent = () => {
  const {
    user,
    loading,
    needsWorkspaceSelection,
    maintenanceActive,
    maintenanceMessage,
    selectWorkspace,
    logout,
    updateSubscriptionPlan,
    updateUserRole,
    refreshPermissions,
    resendVerification,
    verifyEmailAndLogin,
  } = useAuth();
  console.log(
    "[App] 🔄 AppContent render - user:",
    user?.email,
    "workspaceId:",
    user?.workspaceId,
    "needsWorkspaceSelection:",
    needsWorkspaceSelection,
  );

  // Capture invitation params before router initialization can rewrite the URL.
  const initialInvitation = getInitialInvitationFromLocation();

  const [isLoginView, setIsLoginView] = useState(true);
  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(null);
  const [selectedProjectName, setSelectedProjectName] = useState<string>("");
  const [selectedFileId, setSelectedFileId] = useState<string | null>(null);
  const [selectedFileName, setSelectedFileName] = useState<string>("");
  const [showSubscriptionPlan, setShowSubscriptionPlan] = useState(false);
  const [subscriptionCheckout, setSubscriptionCheckout] = useState<{
    clientSecret: string;
    publishableKey: string;
    planName: string;
    interval: "monthly" | "annual";
    trialEligible: boolean;
  } | null>(null);
  const [subscriptionPaymentError, setSubscriptionPaymentError] = useState<string | null>(null);
  const [workspaceBillingStatus, setWorkspaceBillingStatus] = useState<string | null>(null);
  const [accountSubscriptionStatus, setAccountSubscriptionStatus] = useState<string | null>(null);
  const [accountPlanName, setAccountPlanName] = useState<string | null>(null);
  const [accountBillingInterval, setAccountBillingInterval] = useState<"monthly" | "annual">("monthly");
  const [trialEligible, setTrialEligible] = useState(true);
  const [subscriptionPageRefreshing, setSubscriptionPageRefreshing] = useState(false);
  const [showManageSubscription, setShowManageSubscription] = useState(false);
  const [showBillingPage, setShowBillingPage] = useState(false);
  const [subscriptionReturnRoute, setSubscriptionReturnRoute] = useState<"billing" | null>(null);
  const [inviteToken, setInviteToken] = useState<string | null>(initialInvitation.token);
  const [inviteEmail, setInviteEmail] = useState<string | null>(initialInvitation.email);
  const [pendingFile, setPendingFile] = useState<{
    fileName: string;
    fileContent: string;
    fileSize: number;
    filePath?: string;
  } | null>(null);
  const pendingDesktopFilePathRef = useRef<string | null>(null);
  const [showAuthForInvitation, setShowAuthForInvitation] = useState(false); // Show login/signup form while keeping invite token
  const [needsDeploymentSelection, setNeedsDeploymentSelection] = useState(false);
  const [deploymentType, setDeploymentType] = useState<"self-hosted" | "cloud" | null>(() => {
    // Desktop build: always self-hosted — never show the deployment selector.
    if (isDesktop()) {
      try { localStorage.setItem("deploymentType", "self-hosted"); } catch { /* ignore */ }
      return "self-hosted";
    }
    // Auto-detect cloud mode when accessing from the cloud domain
    if (typeof window !== "undefined") {
      const hostname = window.location.hostname;
      if (hostname === "ontocode.selfresearch.org" || hostname === "ontocodeapi.selfresearch.org") {
        try { localStorage.setItem("deploymentType", "cloud"); } catch { /* ignore */ }
        return "cloud";
      }
    }
    // Restore from localStorage for returning users
    try {
      const stored = localStorage.getItem("deploymentType");
      if (stored === "self-hosted" || stored === "cloud") return stored;
    } catch {
      /* ignore */
    }
    return null;
  });
  const [forceShowWorkspace, setForceShowWorkspace] = useState(false);
  const [skipWorkspaceRequested, setSkipWorkspaceRequested] = useState(false);
  const [restoredRoute, setRestoredRoute] = useState<RouteState | null>(null);
  // Initialize reset-password state directly from the URL (same pattern as verify-email below).
  const _resetPath = window.location.pathname.startsWith("/reset-password");
  const _resetTokenFromUrl = _resetPath
    ? new URLSearchParams(window.location.search).get("token")
    : null;
  const [authSubView, setAuthSubView] = useState<
    "login" | "signup" | "forgotPassword" | "resetPassword" | "verifyEmail"
  >(_resetTokenFromUrl ? "resetPassword" : "login");
  const [verificationEmail, setVerificationEmail] = useState("");
  const [resetToken, setResetToken] = useState<string | null>(_resetTokenFromUrl);
  // Initialize verify-email state directly from the URL so the verify screen
  // shows immediately on the first render — before the auth loading spinner.
  const _verifyPath = window.location.pathname.startsWith("/verify-email");
  const _verifyTokenFromUrl = _verifyPath
    ? new URLSearchParams(window.location.search).get("token")
    : null;
  const [emailVerifyToken, setEmailVerifyToken] = useState<string | null>(_verifyTokenFromUrl);
  const [emailVerifyStatus, setEmailVerifyStatus] = useState<"idle" | "verifying" | "success" | "error">(
    _verifyTokenFromUrl ? "verifying" : "idle",
  );
  const [emailVerifyError, setEmailVerifyError] = useState<string>("");
  const [verifiedEmail, setVerifiedEmail] = useState<string>("");
  const [verifyResendEmail, setVerifyResendEmail] = useState<string>("");
  const [verifyResendMessage, setVerifyResendMessage] = useState<string>("");
  const [verifyResendError, setVerifyResendError] = useState<string>("");
  const [isVerifyResending, setIsVerifyResending] = useState(false);

  // Helper to check if workspace selection is required
  const shouldShowWorkspaceSelection = useCallback((): boolean => {
    // Desktop: single-user local app — projects only, no workspace picker or billing gate.
    if (isDesktop()) {
      return false;
    }

    console.log("[App] shouldShowWorkspaceSelection check:", {
      hasUser: !!user,
      userWorkspaceId: user?.workspaceId,
      needsWorkspaceSelection,
      forceShowWorkspace,
      deploymentType: localStorage.getItem("deploymentType"),
    });

    // Force show workspace if explicitly navigated to workspace route
    if (forceShowWorkspace) {
      console.log("[App] Returning true - forceShowWorkspace is set");
      return true;
    }

    if (!user) {
      console.log("[App] Returning false - no user");
      return false;
    }

    if (skipWorkspaceRequested) {
      console.log("[App] Returning false - skipWorkspaceRequested is set");
      return false;
    }

    if (user.workspaceId) {
      console.log("[App] Returning false - user already has workspace");
      return false;
    }

    const storedDeploymentType = localStorage.getItem("deploymentType") as "self-hosted" | "cloud" | null;

    // Cloud users without a workspace always need workspace selection
    // Use both the state variable and localStorage to handle auto-detected cloud mode
    if ((deploymentType === "cloud" || storedDeploymentType === "cloud") && !user.workspaceId) {
      return true;
    }

    // If user explicitly skipped workspace selection, don't show it
    if (!needsWorkspaceSelection) {
      console.log("[App] Returning false - needsWorkspaceSelection is false");
      return false;
    }

    // Fall back to needsWorkspaceSelection from auth context
    return needsWorkspaceSelection;
  }, [user, needsWorkspaceSelection, forceShowWorkspace, skipWorkspaceRequested]);

  // Reset local skip flag when user context is reset or workspace is explicitly selected.
  useEffect(() => {
    if (!user || !!user.workspaceId) {
      setSkipWorkspaceRequested(false);
    }
  }, [user]);

  // Keep a live billing status for the active workspace so the UI can hard-block pending workspaces.
  useEffect(() => {
    let cancelled = false;

    // Desktop has no billing — never fetch workspace billing status.
    if (isDesktop()) {
      setWorkspaceBillingStatus(null);
      return;
    }

    if (!user?.workspaceId) {
      setWorkspaceBillingStatus(null);
      return;
    }

    apiClient
      .get(`/api/workspaces/${user.workspaceId}`)
      .then((response: any) => {
        if (cancelled) return;
        const data = response?.data || response;
        setWorkspaceBillingStatus(data?.billingStatus || null);
      })
      .catch(() => {
        if (!cancelled) {
          setWorkspaceBillingStatus(null);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [user?.workspaceId]);

  const refreshAccountSubscription = useCallback(async () => {
    // Desktop has no billing account — the plan comes from the license file.
    if (isDesktop()) {
      setTrialEligible(false);
      setAccountPlanName((user?.subscriptionPlan || "FREE").toUpperCase());
      setAccountSubscriptionStatus("ACTIVE");
      setAccountBillingInterval("annual");
      return null;
    }

    if (!user) {
      setTrialEligible(true);
      setAccountPlanName(null);
      setAccountSubscriptionStatus(null);
      setAccountBillingInterval("monthly");
      return null;
    }

    const response = await apiClient.get("/api/billing/subscription");
    const data = response?.data || response;
    const planName = (data?.planName || "FREE").toUpperCase();
    setAccountPlanName(planName);
    setTrialEligible(data?.trialEligible !== false);
    setAccountSubscriptionStatus(data?.status || null);
    setAccountBillingInterval((data?.billingInterval || "").toLowerCase() === "yearly" || (data?.billingInterval || "").toLowerCase() === "annual"
      ? "annual"
      : "monthly");
    return data;
  }, [user?.userId]);

  useEffect(() => {
    let cancelled = false;

    if (!user) {
      setTrialEligible(true);
      setAccountSubscriptionStatus(null);
      setAccountBillingInterval("monthly");
      return;
    }

    refreshAccountSubscription()
      .then((response: any) => {
        if (cancelled) return;
        if (!response) return;
      })
      .catch(() => {
        if (!cancelled) {
          setAccountPlanName(null);
          setTrialEligible(true);
          setAccountSubscriptionStatus(null);
          setAccountBillingInterval("monthly");
        }
      });

    return () => {
      cancelled = true;
    };
  }, [refreshAccountSubscription]);

  const isWorkspacePaymentPending =
    !!user?.workspaceId &&
    (workspaceBillingStatus || "").toUpperCase() === "PENDING" &&
    (user?.subscriptionPlan || "FREE").toUpperCase() !== "FREE";

  // Desktop download page — independent state so it works before login too
  const [showDesktopDownload, setShowDesktopDownload] = React.useState(false);
  useEffect(() => {
    const handler = () => setShowDesktopDownload(true);
    window.addEventListener("navigate-desktop-download", handler);
    return () => window.removeEventListener("navigate-desktop-download", handler);
  }, []);

  // Desktop license — drives the "License expired" block screen.
  const [desktopLicense, setDesktopLicense] = React.useState<DesktopLicense | null>(null);
  useEffect(() => {
    if (!isDesktop()) return;
    let cancelled = false;
    const load = async () => {
      const lic = await getDesktopLicense();
      if (!cancelled) setDesktopLicense(lic);
    };
    load();
    const onUpdated = () => load();
    window.addEventListener(DESKTOP_LICENSE_UPDATED_EVENT, onUpdated);
    return () => {
      cancelled = true;
      window.removeEventListener(DESKTOP_LICENSE_UPDATED_EVENT, onUpdated);
    };
  }, []);

  const clearLastOpenedSelection = useCallback(() => {
    clearLastOpenedProjectState();
    apiClient.put("/api/auth/last-opened", { projectId: null, projectName: null, fileId: null, fileName: null }).catch(() => {});
    if (window.vscode) {
      window.vscode.postMessage({ type: "clearLastProjectState" });
    }
  }, []);

  const resetWorkspaceHubNavigation = useCallback(() => {
    setSelectedProjectId(null);
    setSelectedProjectName("");
    setSelectedFileId(null);
    setSelectedFileName("");
    clearLastOpenedSelection();
  }, [clearLastOpenedSelection]);

  // Capture the URL the user originally navigated to, before any routing changes it.
  // Used to detect file-editor links that should skip auto-restore.
  const initialUrlRef = useRef(window.location.pathname);

  // Auto-restore last project + file when workspace becomes available (e.g. after login with auto-select)
  const autoRestoredRef = useRef(false);
  useEffect(() => {
    // Desktop: always land on My projects first; session restore is web/cloud UX.
    if (isDesktop()) {
      autoRestoredRef.current = true;
      return;
    }
    if (!user?.workspaceId || autoRestoredRef.current || selectedProjectId || selectedFileId) return;
    autoRestoredRef.current = true;

    const goToDashboard = () => navigateTo({ view: "projectDashboard", projectId: null, projectName: "", fileId: null, fileName: "" });

    // Tab-switch restore threshold: if the user had a file open less than 30 minutes ago,
    // treat it as a VS Code tab-switch and restore directly to the editor.
    // Anything older is a fresh session → restore to project library so the user can choose.
    const EDITOR_RESTORE_THRESHOLD_MS = 30 * 60 * 1000;
    const lastEditorActiveAt = Number(localStorage.getItem("ontocode_lastEditorActiveAt") || "0");
    const wasRecentlyEditing = Date.now() - lastEditorActiveAt < EDITOR_RESTORE_THRESHOLD_MS;
    const isFreshTab = !sessionStorage.getItem("ontocode_tab_active");
    sessionStorage.setItem("ontocode_tab_active", "true");

    // If the user navigated directly to a file editor URL (/projects/:name/files/:file),
    // skip auto-restore — a separate effect will resolve the names to IDs and open it.
    // We use initialUrlRef instead of selectedProjectName/fileName because routing may have
    // already cleared those state values by the time this effect runs.
    if (/^\/projects\/[^/]+\/files\/[^/]+/.test(initialUrlRef.current)) {
      console.log("[App] Initial URL is file editor, skipping auto-restore:", initialUrlRef.current);
      return;
    }

    const restoreWithIds = async (projectId: string, projectName: string, fileId: string | null, fileName: string | null) => {
      // Validate project still exists
      try {
        await apiClient.get(`/api/projects/${encodeURIComponent(projectId)}`);
      } catch {
        console.warn("[App] Stored project no longer exists, going to project dashboard");
        clearLastOpenedSelection();
        goToDashboard();
        return;
      }
      if (fileId && fileName && wasRecentlyEditing && isFreshTab) {
        // New browser tab with a recent editor session — restore directly to the editor.
        console.log("[App] Tab-switch restore: reopening last file in editor:", fileId);
        navigateTo({ view: "dashboard", projectId, projectName, fileId, fileName });
      } else {
        // Fresh session or no recent editing — restore to project library
        console.log("[App] Auto-restoring last project to library:", projectId);
        navigateTo({ view: "projectLibrary", projectId, projectName, fileId: null, fileName: "" });
      }
    };

    // Try backend first (cross-device), fall back to localStorage
    apiClient.get<{ projectId?: string; projectName?: string; fileId?: string; fileName?: string }>('/api/auth/last-opened')
      .then((data) => {
        const projectId = data?.projectId || localStorage.getItem("ontocode_lastWorkspaceProjectId");
        const projectName = data?.projectName || localStorage.getItem("ontocode_lastWorkspaceProjectName");
        const fileId = data?.fileId || localStorage.getItem("ontocode_lastWorkspaceFileId");
        const fileName = data?.fileName || localStorage.getItem("ontocode_lastWorkspaceFileName");
        if (projectId && projectName) {
          restoreWithIds(projectId, projectName, fileId || null, fileName || null);
        }
      })
      .catch(() => {
        // Fall back to localStorage only
        try {
          const projectId = localStorage.getItem("ontocode_lastWorkspaceProjectId");
          const projectName = localStorage.getItem("ontocode_lastWorkspaceProjectName");
          const fileId = localStorage.getItem("ontocode_lastWorkspaceFileId");
          const fileName = localStorage.getItem("ontocode_lastWorkspaceFileName");
          if (projectId && projectName) {
            restoreWithIds(projectId, projectName, fileId, fileName);
          }
        } catch { /* ignore */ }
      });
  }, [clearLastOpenedSelection, user?.workspaceId]); // eslint-disable-line react-hooks/exhaustive-deps

  // When the user navigated directly to /projects/:name/files/:file, resolve names→IDs
  // and open the editor after login. Uses initialUrlRef so routing state changes can't
  // invalidate the original intent.
  const urlResolvedRef = useRef(false);
  useEffect(() => {
    if (!user?.workspaceId || isDesktop()) return;
    if (selectedProjectId && selectedFileId) return; // already resolved
    if (urlResolvedRef.current) return;

    const match = /^\/projects\/([^/]+)\/files\/([^/]+)$/.exec(initialUrlRef.current);
    if (!match) return;

    const projectNameFromUrl = decodeURIComponent(match[1]);
    const fileNameFromUrl = decodeURIComponent(match[2]);

    urlResolvedRef.current = true;
    let cancelled = false;
    console.log("[App] Resolving initial URL to IDs:", projectNameFromUrl, "/", fileNameFromUrl);

    apiClient.get<any>(`/api/projects/my`)
      .then((resp: any) => {
        if (cancelled) return;
        const data = resp?.data || resp;
        const projects: any[] = data?.projects || [];
        const project = projects.find((p: any) => p.name === projectNameFromUrl);
        if (!project?.projectId) {
          console.warn("[App] URL project not found:", projectNameFromUrl);
          return; // stay on projectDashboard — don't override with wrong nav
        }
        return apiClient.get<any>(`/api/projects/${project.projectId}/files`).then((filesResp: any) => {
          if (cancelled) return;
          const filesData = filesResp?.data || filesResp;
          const files: any[] = filesData?.files || [];
          const file = files.find((f: any) => (f.name || f.fileName) === fileNameFromUrl);
          if (!file?.id) {
            console.warn("[App] URL file not found:", fileNameFromUrl);
            if (!cancelled) navigateTo({ view: "projectLibrary", projectId: project.projectId, projectName: projectNameFromUrl, fileId: null, fileName: "" });
            return;
          }
          console.log("[App] ✅ URL resolved to:", project.projectId, file.id);
          if (!cancelled) navigateTo({ view: "dashboard", projectId: project.projectId, projectName: projectNameFromUrl, fileId: file.id, fileName: fileNameFromUrl });
        });
      })
      .catch((err: any) => {
        if (!cancelled) console.warn("[App] URL resolve failed:", err);
      });

    return () => { cancelled = true; };
  }, [user?.workspaceId, selectedProjectId, selectedFileId]); // eslint-disable-line react-hooks/exhaustive-deps


  const currentRoute: RouteState = useMemo(() => {
    // Deployment type must always be set first — no other screen is accessible without it
    // (except email verification which needs its own URL-based flow)
    if (!deploymentType && !(emailVerifyToken && emailVerifyStatus !== "idle")) {
      return { view: "deployment", deploymentType };
    }

    // While the verify-email flow is active, keep the router in a neutral state
    // so useRouter doesn't overwrite window.location to /deployment (which would
    // break the verify useEffect's ability to detect the original URL).
    if (emailVerifyToken && emailVerifyStatus !== "idle") {
      return { view: "login", isLoginView: true };
    }

    // If we have a restored route from navigation (back/forward), use it
    if (restoredRoute) {
      console.log("[App] Using restored route:", restoredRoute.view);
      return restoredRoute;
    }

    // Otherwise, calculate route based on current application state
    if (inviteToken && !showAuthForInvitation) {
      return { view: "invitation", inviteToken, showAuthForInvitation };
    }
    if (!deploymentType) {
      return { view: "deployment", deploymentType };
    }
    // Bug #48: billing has its own URL (/billing) and must outrank the
    // workspace-selection branch — otherwise the route is computed as
    // 'workspace' on the next render and the URL flips back. The page is
    // always account-level, so it doesn't depend on a workspace being
    // chosen.
    if (user && showBillingPage) {
      return { view: "billing" };
    }
    if (user && showSubscriptionPlan) {
      return { view: "subscription", showSubscriptionPlan };
    }
    if (!isDesktop() && user && (shouldShowWorkspaceSelection() || isWorkspacePaymentPending) && !selectedFileId) {
      return { view: "workspace" };
    }
    if (user && user.workspaceId && !showSubscriptionPlan && !selectedFileId && (!selectedProjectId || pendingFile)) {
      return { view: "projectDashboard", projectId: selectedProjectId, projectName: selectedProjectName };
    }
    if (user && user.workspaceId && selectedProjectId && !selectedFileId) {
      return { view: "projectLibrary", projectId: selectedProjectId, projectName: selectedProjectName };
    }
    if (user && selectedFileId && selectedFileId !== "__editor__") {
      return {
        view: "dashboard",
        projectId: selectedProjectId,
        projectName: selectedProjectName,
        fileId: selectedFileId,
        fileName: selectedFileName,
      };
    }
    if (user && !user.workspaceId) {
      // Cloud users without a workspace must select one first
      const storedDeployment = localStorage.getItem("deploymentType");
      if (storedDeployment === "cloud") {
        return { view: "workspace" };
      }
      // Self-hosted users still need to select a project
      return { view: "projectDashboard", projectId: null, projectName: "" };
    }
    // Login/Signup view
    return {
      view: isLoginView ? "login" : "signup",
      isLoginView,
      inviteToken,
      showAuthForInvitation,
    };
  }, [
    emailVerifyToken,
    emailVerifyStatus,
    restoredRoute,
    user,
    deploymentType,
    selectedProjectId,
    selectedProjectName,
    selectedFileId,
    selectedFileName,
    isLoginView,
    inviteToken,
    showAuthForInvitation,
    showSubscriptionPlan,
    showBillingPage,
    pendingFile,
    isWorkspacePaymentPending,
    shouldShowWorkspaceSelection,
  ]);

  // Handle route change from browser back/forward or programmatic navigation
  const handleRouteChange = useCallback((route: RouteState, fromBrowserNav: boolean = false) => {
    console.log("[App] Handling route change:", route.view, "fromBrowserNav:", fromBrowserNav);

    // Create a mutable copy of the route to update with restored IDs
    const updatedRoute = { ...route };

    // Restore IDs from name-based routes (works for refresh/init and browser navigation).
    try {
      if (updatedRoute.projectName && !updatedRoute.projectId) {
        const cachedMappings = JSON.parse(sessionStorage.getItem("project_name_id_map") || "{}");
        const projectId = cachedMappings[updatedRoute.projectName];
        if (projectId) {
          updatedRoute.projectId = projectId;
          console.log("[App] Restored projectId from cache:", projectId);
        }
      }
      if (updatedRoute.fileName && !updatedRoute.fileId) {
        const cachedMappings = JSON.parse(sessionStorage.getItem("file_name_id_map") || "{}");
        const fileId = cachedMappings[updatedRoute.fileName];
        if (fileId) {
          updatedRoute.fileId = fileId;
          console.log("[App] Restored fileId from cache:", fileId);
        }
      }
    } catch (e) {
      console.warn("[App] Failed to restore IDs from cache:", e);
    }

    // Only set restored route if this is from browser back/forward navigation
    if (fromBrowserNav) {
      // Set the restored route AFTER updating IDs
      setRestoredRoute(updatedRoute);
    } else {
      setRestoredRoute(null);

      // When navigating forward, cache the name→ID mappings to sessionStorage
      try {
        if (updatedRoute.projectName && updatedRoute.projectId) {
          const cachedMappings = JSON.parse(sessionStorage.getItem("project_name_id_map") || "{}");
          cachedMappings[updatedRoute.projectName] = updatedRoute.projectId;
          sessionStorage.setItem("project_name_id_map", JSON.stringify(cachedMappings));
        }
        if (updatedRoute.fileName && updatedRoute.fileId) {
          const cachedMappings = JSON.parse(sessionStorage.getItem("file_name_id_map") || "{}");
          cachedMappings[updatedRoute.fileName] = updatedRoute.fileId;
          sessionStorage.setItem("file_name_id_map", JSON.stringify(cachedMappings));
        }
      } catch (e) {
        console.warn("[App] Failed to cache name→ID mappings:", e);
      }
    }

    // Update deployment type
    if (updatedRoute.deploymentType !== undefined) {
      setDeploymentType(updatedRoute.deploymentType as "self-hosted" | "cloud" | null);
    }

    // Update project selection
    if (updatedRoute.projectId !== undefined) {
      setSelectedProjectId(updatedRoute.projectId);
    }
    if (updatedRoute.projectName !== undefined) {
      setSelectedProjectName(updatedRoute.projectName);
    }
    // Explicitly clear project when navigating to dashboard
    if (updatedRoute.view === "projectDashboard") {
      if (updatedRoute.projectId === undefined || updatedRoute.projectId === null) {
        setSelectedProjectId(null);
        setSelectedProjectName("");
      }
    }

    // Update file selection
    if (updatedRoute.fileId !== undefined) {
      setSelectedFileId(updatedRoute.fileId);
    }
    if (updatedRoute.fileName !== undefined) {
      setSelectedFileName(updatedRoute.fileName);
    }
    // Clear file selection when navigating to project views
    if (updatedRoute.view === "projectDashboard" || updatedRoute.view === "projectLibrary") {
      if (updatedRoute.fileId === undefined || updatedRoute.fileId === null) {
        setSelectedFileId(null);
        setSelectedFileName("");
      }
    }

    // Update login/signup view
    if (updatedRoute.isLoginView !== undefined) {
      setIsLoginView(updatedRoute.isLoginView);
    }

    // Update invitation state
    if (updatedRoute.inviteToken !== undefined) {
      setInviteToken(updatedRoute.inviteToken);
    }
    if (updatedRoute.showAuthForInvitation !== undefined) {
      setShowAuthForInvitation(updatedRoute.showAuthForInvitation);
    }

    // Update reset password state
    if (updatedRoute.resetToken) {
      setResetToken(updatedRoute.resetToken);
      setAuthSubView("resetPassword");
    }

    // Update subscription plan view
    if (updatedRoute.showSubscriptionPlan !== undefined) {
      setShowSubscriptionPlan(updatedRoute.showSubscriptionPlan);
    }
    if (updatedRoute.view === "subscription") {
      setShowSubscriptionPlan(true);
      setSkipWorkspaceRequested(true);
    }
    // Browser back away from subscription: always dismiss the overlay so the
    // user isn't snapped back to it once restoredRoute clears after 100ms.
    if (fromBrowserNav && updatedRoute.view !== "subscription") {
      setShowSubscriptionPlan(false);
    }

    // Update billing view
    if (updatedRoute.view === 'billing') {
      setShowBillingPage(true);
    } else if (updatedRoute.view === 'subscription') {
      setShowBillingPage(false);
    } else if (updatedRoute.view) {
      setShowBillingPage(false);
    }

    // Update view-specific flags
    if (updatedRoute.view === "workspace") {
      clearLastOpenedSelection();
      setSelectedFileId(null);
      setSelectedFileName("");
      setForceShowWorkspace(true);
    } else if (updatedRoute.view === "deployment") {
      setForceShowWorkspace(false);
      if (!updatedRoute.deploymentType) {
        setDeploymentType(null);
      }
    } else {
      setForceShowWorkspace(false);
    }

    if (updatedRoute.view === "projectDashboard") {
      clearLastOpenedSelection();
      if (updatedRoute.fileId === undefined || updatedRoute.fileId === null) {
        setSelectedFileId(null);
        setSelectedFileName("");
      }
    }

    // Restore non-workspace editor state when navigating to dashboard without project context
    if (updatedRoute.view === "dashboard" && !updatedRoute.projectId && !updatedRoute.projectName) {
      setSkipWorkspaceRequested(true);
      if (!updatedRoute.fileId) {
        setSelectedFileId("__editor__");
      }
    }
  }, [clearLastOpenedSelection]);

  // Initialize router
  const { clearHistory, navigateTo } = useRouter(currentRoute, handleRouteChange);

  const openAccountSubscription = useCallback(async () => {
    await refreshAccountSubscription().catch(() => null);
    setSubscriptionReturnRoute(currentRoute.view === "billing" || showBillingPage ? "billing" : null);
    setShowBillingPage(false);
    setShowSubscriptionPlan(true);
    setSkipWorkspaceRequested(true);
    setForceShowWorkspace(false);
    navigateTo({ view: "subscription", showSubscriptionPlan: true });
  }, [currentRoute.view, navigateTo, refreshAccountSubscription, showBillingPage]);

  useEffect(() => {
    if (!user || currentRoute.view !== "subscription") return;

    let cancelled = false;
    setSubscriptionPageRefreshing(true);
    refreshAccountSubscription()
      .catch((err) => {
        console.warn("[App] Failed to refresh subscription route state:", err);
      })
      .finally(() => {
        if (!cancelled) {
          setSubscriptionPageRefreshing(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [currentRoute.view, refreshAccountSubscription, user?.userId]);

  const goToWorkspaceHub = useCallback(() => {
    resetWorkspaceHubNavigation();
    setShowBillingPage(false);
    setShowSubscriptionPlan(false);
    setSubscriptionReturnRoute(null);
    if (user?.workspaceId) {
      navigateTo({
        view: "projectDashboard",
        projectId: null,
        projectName: "",
        fileId: null,
        fileName: "",
        replace: true,
      });
      return;
    }
    try { localStorage.setItem(SUPPRESS_WORKSPACE_AUTO_OPEN_KEY, "true"); } catch {}
    navigateTo({
      view: "workspace",
      projectId: null,
      projectName: "",
      fileId: null,
      fileName: "",
      replace: true,
    });
  }, [navigateTo, resetWorkspaceHubNavigation, user?.workspaceId]);

  // While a real file is open in the editor, keep the "last active" timestamp fresh
  // so a tab-switch restore within the session correctly lands back in the editor.
  useEffect(() => {
    if (selectedFileId && selectedFileId !== "__editor__") {
      const interval = setInterval(() => {
        try { localStorage.setItem("ontocode_lastEditorActiveAt", String(Date.now())); } catch { /* ignore */ }
      }, 60_000); // refresh every minute
      return () => clearInterval(interval);
    }
  }, [selectedFileId]);

  // Clear restoredRoute after it has been used in the render cycle
  // This ensures we go back to computing routes from state after handling browser navigation
  useEffect(() => {
    if (restoredRoute) {
      // Use a timeout to ensure the route has been fully processed
      const timer = setTimeout(() => {
        console.log("[App] Clearing restored route after processing");
        setRestoredRoute(null);
      }, 100);
      return () => clearTimeout(timer);
    }
  }, [restoredRoute]);

  // Send webviewReady on mount to ensure extension knows webview is loaded
  useEffect(() => {
    console.log("[App] Webview mounted, sending webviewReady signal");
    if (window.vscode) {
      window.vscode.postMessage({ type: "webviewReady" });
    }

    // Don't load stored deployment type - always show selector before login
    // Deployment type will be set after user selects in DeploymentSelector
  }, []);

  // Ref to prevent the verify fetch from running twice in React 18 StrictMode.
  // (useRouter's init effect changes window.location.pathname before this effect
  //  runs, so we cannot rely on a pathname guard — use a ref instead.)
  const _verifyFetchStarted = useRef(false);
  const paymentRecoveryAttempted = useRef(false);

  // Detect /verify-email?token=... URL, verify the account, and show success screen.
  // State is already initialised from the URL above; this effect just kicks off the fetch.
  useEffect(() => {
    if (!emailVerifyToken) return;
    // Guard against StrictMode double-invocation (ref persists across the simulated
    // unmount/remount cycle, unlike component state).
    if (_verifyFetchStarted.current) return;
    _verifyFetchStarted.current = true;

    verifyEmailAndLogin(emailVerifyToken)
      .then((email) => {
        setVerifiedEmail(email);
        setEmailVerifyStatus("success");
        window.history.replaceState({}, "", "/");
      })
      .catch((err: any) => {
        console.error("[App] Email verification failed:", err);
        const email = err?.data?.email || "";
        if (email) {
          setVerifiedEmail(email);
          setVerifyResendEmail(email);
        }
        setEmailVerifyStatus("error");
        setEmailVerifyError(err?.message || "Verification failed");
      });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Clear /reset-password URL on first render (token already captured in state above).
  useEffect(() => {
    if (_resetTokenFromUrl) {
      window.history.replaceState({}, "", "/");
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    // Check for invitation parameters in URL (query params, pathname routes, and hash-based routes)
    const pathname = window.location.pathname;

    // Skip invitation detection for verify-email and reset-password routes
    if (pathname.startsWith("/verify-email") || pathname.startsWith("/reset-password")) {
      console.log("[App] Skipping invitation detection for path:", pathname);
      return;
    }

    const params = new URLSearchParams(window.location.search);
    let token = params.get("token") || params.get("invite");
    let email = params.get("email");

    // Check pathname-based route for invitation (e.g., /invitation?token=xxx or /invite?token=xxx)
    if (pathname.startsWith("/invitation") || pathname.startsWith("/invite")) {
      token = token || params.get("token") || params.get("invite");
      email = email || params.get("email");
      console.log("[App] Found invitation in pathname route:", !!token, "email:", !!email);
    }

    // Check hash-based route for invitation (e.g., #/invitation?token=xxx or #/invite?token=xxx)
    if (window.location.hash) {
      const hashPart = window.location.hash.substring(1); // Remove the '#'
      const [path, queryString] = hashPart.split("?");

      if ((path.startsWith("/invitation") || path.startsWith("/invite")) && queryString) {
        const hashParams = new URLSearchParams(queryString);
        token = token || hashParams.get("token") || hashParams.get("invite");
        email = email || hashParams.get("email");
        console.log("[App] Found invitation in hash route:", !!token, "email:", !!email);
      }
    }

    // Also check parent window URL (for test-web environment)
    let parentToken: string | null = null;
    let parentEmail: string | null = null;
    try {
      if (window.parent && window.parent !== window) {
        const parentParams = new URLSearchParams(window.parent.location.search);
        parentToken = parentParams.get("token") || parentParams.get("invite");
        parentEmail = parentParams.get("email");
        console.log("[App] Checked parent window for token:", !!parentToken, "email:", !!parentEmail);
      }
    } catch (e) {
      // Cross-origin access blocked, ignore
      console.log("[App] Cannot access parent window (cross-origin)");
    }

    const finalToken = token || parentToken;
    const finalEmail = email || parentEmail;

    if (finalToken) {
      console.log("[App] 📧 Found invitation token in URL, setting state");
      setInviteToken(finalToken);
      if (finalEmail) {
        setInviteEmail(finalEmail);
      }
    } else {
      console.log("[App] No invitation token found in URL parameters, search, hash, or parent window");
    }
  }, []);

  // Listen for pending file upload and invitation token messages from extension
  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const message = event.data;
      console.log("[App] Received message from extension:", message.type, message);

      if (message.type === "pendingFileUpload") {
        console.log("[App] 📎 Received pending file upload:", message.fileName);
        setPendingFile({
          fileName: message.fileName,
          fileContent: message.fileContent,
          fileSize: message.fileSize,
        });
      } else if (message.type === "clearInvitationState") {
        console.log("[App] 🧹 Clearing existing invitation state for new invitation");
        setInviteToken(null);
        setInviteEmail(null);
        setShowAuthForInvitation(false);
      } else if (message.type === "invitationToken") {
        console.log("[App] 📧 Received invitation token from extension:", message.token?.substring(0, 20) + "...");
        console.log(
          "[App] Current state - inviteToken:",
          !!inviteToken,
          "showAuthForInvitation:",
          showAuthForInvitation,
        );
        // Reset any auth-related state that might block showing the invitation page
        setShowAuthForInvitation(false);
        setInviteToken(message.token);
        console.log("[App] 📧 Invitation token state updated, page should show now");
      } else if (message.type === "showSubscriptionPlans") {
        console.log("[App] 📋 Showing subscription plans page");
        openAccountSubscription();
      }
    };

    window.addEventListener("message", handleMessage);
    return () => window.removeEventListener("message", handleMessage);
  }, [openAccountSubscription]);

  const tryFocusExistingDesktopFile = useCallback(
    async (filePath: string, fileName: string): Promise<boolean> => {
      if (!isDesktop() || !filePath) return false;

      const api = (window as any).electronAPI;
      const mainActive: string = api?.getActiveFilePath ? await api.getActiveFilePath() : "";
      const stored = loadDesktopActiveFile();
      const isSamePath =
        pathsEqual(filePath, mainActive) || pathsEqual(filePath, stored?.filePath);

      if (!isSamePath) return false;

      setPendingFile(null);
      pendingDesktopFilePathRef.current = null;

      if (stored?.projectId && stored?.fileId) {
        setSelectedProjectId(stored.projectId);
        setSelectedProjectName(stored.projectName || "");
        setSelectedFileId(stored.fileId);
        setSelectedFileName(stored.fileName || fileName);
        navigateTo({
          view: "dashboard",
          projectId: stored.projectId,
          projectName: stored.projectName || "",
          fileId: stored.fileId,
          fileName: stored.fileName || fileName,
        });
      }

      return true;
    },
    [navigateTo],
  );

  // Browser-mode: upload a file directly via the API (no extension proxy needed)
  const uploadFileBrowserMode = useCallback(
    async (projectId: string, fileName: string, fileContent: string, fileSize: number) => {
      try {
        const base64Content = fileContentToBase64(fileContent);
        await apiClient.post(`/api/projects/${projectId}/files`, {
          fileName,
          fileData: `data:application/rdf+xml;base64,${base64Content}`,
          fileSize,
          fileType: "owl",
        });
        console.log("[App] ✅ Browser upload complete:", fileName);
      } catch (err) {
        console.error("[App] ❌ Browser upload failed:", err);
      }
    },
    [],
  );

  // Open a local file in browser/cloud/Electron mode (no VS Code extension)
  const handleOpenLocalFile = useCallback(async () => {
    const fileData = await openOntologyFile();
    if (!fileData) return;
    if (fileData.filePath && (await tryFocusExistingDesktopFile(fileData.filePath, fileData.fileName))) {
      return;
    }
    console.log("[App] 📂 File picked:", fileData.fileName);
    pendingDesktopFilePathRef.current = fileData.filePath || null;
    setPendingFile(fileData);
    setSelectedProjectId(null);
    setSelectedFileId(null);
    setSelectedFileName("");
    navigateTo({
      view: "projectDashboard",
      projectId: null,
      projectName: "",
      fileId: null,
      fileName: "",
    });
  }, [navigateTo, tryFocusExistingDesktopFile]);

  // Windows / macOS menu: File → Open Ontology File…
  useEffect(() => {
    if (!isDesktop()) return;
    const api = (window as any).electronAPI;
    if (!api?.onMenuOpenFile) return;
    const applyMenuFile = async (data: {
      fileName: string;
      fileContent: string;
      fileSize: number;
      filePath?: string;
    }) => {
      if (!data?.fileName || data.fileContent == null) return;
      if (data.filePath && (await tryFocusExistingDesktopFile(data.filePath, data.fileName))) {
        return;
      }
      pendingDesktopFilePathRef.current = data.filePath || null;
      setPendingFile({
        fileName: data.fileName,
        fileContent: data.fileContent,
        fileSize: data.fileSize ?? 0,
        filePath: data.filePath,
      });
      setSelectedProjectId(null);
      setSelectedFileId(null);
      setSelectedFileName("");
      navigateTo({
        view: "projectDashboard",
        projectId: null,
        projectName: "",
        fileId: null,
        fileName: "",
      });
    };
    api.onMenuOpenFile(applyMenuFile);

    // Also handle new-file creation from OpenFileDialog (desktop, no project)
    const onElectronFileOpened = (e: Event) => {
      const data = (e as CustomEvent).detail;
      if (data) void applyMenuFile(data);
    };
    window.addEventListener('electron:file-opened', onElectronFileOpened);
    return () => window.removeEventListener('electron:file-opened', onElectronFileOpened);
  }, [navigateTo, tryFocusExistingDesktopFile]);

  // Second-instance / Finder: focus existing file without re-importing
  useEffect(() => {
    if (!isDesktop()) return;
    const api = (window as any).electronAPI;
    if (!api?.onFocusExistingFile) return;
    const onFocus = (data: { filePath?: string; fileName?: string }) => {
      if (!data?.filePath) return;
      void tryFocusExistingDesktopFile(data.filePath, data.fileName || "");
    };
    api.onFocusExistingFile(onFocus);
  }, [tryFocusExistingDesktopFile]);

  // Desktop startup: ensure My projects is the home screen (hash routing may restore a stale editor route).
  const desktopHomeRoutedRef = useRef(false);
  useEffect(() => {
    if (!isDesktop() || !user || loading || desktopHomeRoutedRef.current) return;
    desktopHomeRoutedRef.current = true;
    if (!selectedProjectId && !selectedFileId && !pendingFile) {
      navigateTo({
        view: "projectDashboard",
        projectId: null,
        projectName: "",
        fileId: null,
        fileName: "",
      });
    }
  }, [user, loading, selectedProjectId, selectedFileId, pendingFile, navigateTo]);

  // Auto-upload for self-hosted users (no workspace)
  useEffect(() => {
    if (user && !user.workspaceId && pendingFile) {
      console.log("[App] 🚀 Auto-uploading file for self-hosted user:", pendingFile.fileName);
      const projectId = pendingFile.fileName.replace(/\.(owl|rdf|ttl|n3|nt|jsonld)$/i, "");

      if (window.vscode) {
        window.vscode.postMessage({
          type: "uploadOntology",
          projectId: projectId,
          fileName: pendingFile.fileName,
          fileContent: pendingFile.fileContent,
          ownerEmail: user.email || undefined,
          workspaceId: user.workspaceId || undefined,
          skipDuplicateCheck: false,
        });
      } else {
        // Browser / cloud / Electron: upload directly via API
        uploadFileBrowserMode(projectId, pendingFile.fileName, pendingFile.fileContent, pendingFile.fileSize);
      }

      // Clear pending file after triggering upload
      setPendingFile(null);
    }
  }, [user, pendingFile, uploadFileBrowserMode]);

  const toggleFormView = () => {
    const newIsLogin = !isLoginView;
    setAuthSubView(newIsLogin ? "login" : "signup");
    // Navigate using router to update browser history
    navigateTo({
      view: newIsLogin ? "login" : "signup",
      isLoginView: newIsLogin,
    });
  };

  const handleWorkspaceSelected = (workspaceData: any) => {
    setSkipWorkspaceRequested(false);
    selectWorkspace(workspaceData);
    setForceShowWorkspace(false); // Reset after workspace selection
    setRestoredRoute(null);
    clearLastOpenedSelection();
    setSelectedProjectId(null);
    setSelectedProjectName("");
    setSelectedFileId(null);
    setSelectedFileName("");
    navigateTo({
      view: "projectDashboard",
      projectId: null,
      projectName: "",
      fileId: null,
      fileName: "",
    });
    // Subscription plan is now selected during workspace creation
    // No need to show separate subscription plan screen
  };

  const handleProjectSelected = (projectId: string, projectName: string) => {
    console.log("[App] Project selected:", projectId, projectName);

    // Persist last selected project for auto-restore on next login
    try {
      localStorage.setItem("ontocode_lastWorkspaceProjectId", projectId);
      localStorage.setItem("ontocode_lastWorkspaceProjectName", projectName);
      localStorage.removeItem("ontocode_lastWorkspaceFileId");
      localStorage.removeItem("ontocode_lastWorkspaceFileName");
    } catch { /* ignore */ }
    // Also persist to backend for cross-device restore
    apiClient.put('/api/auth/last-opened', { projectId, projectName, fileId: null, fileName: null })
      .catch(() => { /* non-critical */ });

    // If there's a pending file, upload it to this project
    if (pendingFile) {
      console.log("[App] Uploading pending file to project:", pendingFile.fileName);
      if (window.vscode) {
        window.vscode.postMessage({
          type: "uploadFileToProject",
          projectId: projectId,
          fileName: pendingFile.fileName,
          fileContent: pendingFile.fileContent,
          fileSize: pendingFile.fileSize,
        });
      } else {
        // Browser / cloud / Electron: upload directly via API
        uploadFileBrowserMode(projectId, pendingFile.fileName, pendingFile.fileContent, pendingFile.fileSize);
      }
      // Clear pending file immediately after triggering upload
      setPendingFile(null);
    }

    // Navigate using router to update browser history
    navigateTo({
      view: "projectLibrary",
      projectId: projectId,
      projectName: projectName,
      fileId: null,
      fileName: "",
    });
  };

  const handleBackToProjects = () => {
    clearLastOpenedSelection();
    // Navigate using router to update browser history
    navigateTo({
      view: "projectDashboard",
      projectId: null,
      projectName: "",
      fileId: null,
      fileName: "",
    });
  };

  const handleFileSelected = (fileId: string, fileName: string) => {
    console.log("[App] File selected:", fileId, fileName);

    if (isDesktop() && pendingDesktopFilePathRef.current) {
      saveDesktopActiveFile({
        filePath: pendingDesktopFilePathRef.current,
        fileName,
        projectId: selectedProjectId || undefined,
        projectName: selectedProjectName || undefined,
        fileId,
      });
      (window as any).electronAPI?.setActiveFilePath?.(pendingDesktopFilePathRef.current);
      pendingDesktopFilePathRef.current = null;
    }

    // Persist last selected file for auto-restore on next login
    try {
      localStorage.setItem("ontocode_lastWorkspaceFileId", fileId);
      localStorage.setItem("ontocode_lastWorkspaceFileName", fileName);
      // Record when the user last had a file open — used to decide whether to
      // restore directly to the editor (recent tab-switch) vs project library (fresh session).
      localStorage.setItem("ontocode_lastEditorActiveAt", String(Date.now()));
    } catch { /* ignore */ }
    // Also persist to backend for cross-device restore
    apiClient.put('/api/auth/last-opened', {
      projectId: selectedProjectId,
      projectName: selectedProjectName,
      fileId,
      fileName,
    }).catch(() => { /* non-critical */ });

    // Navigate using router to update browser history
    navigateTo({
      view: "dashboard",
      projectId: selectedProjectId, // Preserve current project
      projectName: selectedProjectName,
      fileId: fileId,
      fileName: fileName,
    });
  };

  const startSubscriptionCheckout = async (planId: string, interval: "monthly" | "annual") => {
    setSubscriptionPaymentError(null);
    const response = await apiClient.post("/api/billing/setup", {});
    const data = response?.data || response;

    if (!data?.clientSecret || !data?.stripePublishableKey) {
      throw new Error("Missing payment configuration from server");
    }

    setSubscriptionCheckout({
      clientSecret: data.clientSecret,
      publishableKey: data.stripePublishableKey,
      planName: planId,
      interval,
      trialEligible: data?.trialEligible !== false,
    });
  };

  const handlePaymentConfirmed = async (
    setupIntentId: string,
    planName?: string,
    interval?: "monthly" | "annual",
  ) => {
    const resolvedPlan = planName ?? subscriptionCheckout?.planName;
    const resolvedInterval = interval ?? subscriptionCheckout?.interval ?? "monthly";

    if (!resolvedPlan) {
      throw new Error("Missing selected plan");
    }

    try {
      await apiClient.post("/api/billing/subscribe", {
        setupIntentId,
        planName: resolvedPlan,
        interval: resolvedInterval,
        workspaceId: "",
      });

      try { localStorage.removeItem("pendingPaymentRecovery"); } catch {}
      setSubscriptionPaymentError(null);
      setSubscriptionCheckout(null);
      setAccountPlanName(resolvedPlan.toUpperCase());
      setAccountBillingInterval(resolvedInterval === "annual" ? "annual" : "monthly");
      await refreshPermissions();
      await refreshAccountSubscription().catch(() => null);
      setShowSubscriptionPlan(false);
      setSubscriptionReturnRoute(null);
      resetWorkspaceHubNavigation();
      clearHistory();
      navigateTo({
        view: "billing",
        showSubscriptionPlan: false,
        projectId: null,
        projectName: "",
        fileId: null,
        fileName: "",
        replace: true,
      });
    } catch (error: any) {
      console.error("Failed to complete subscription:", error);
      const errMsg =
        (error instanceof ApiError && (error.data?.error || error.message)) ||
        error?.error ||
        error?.data?.error ||
        error?.response?.data?.error ||
        error?.message ||
        "";
      if (errMsg.toLowerCase().includes("already") && errMsg.toLowerCase().includes("subscription")) {
        try { localStorage.removeItem("pendingPaymentRecovery"); } catch {}
        setSubscriptionCheckout(null);
        if (resolvedPlan) {
          setAccountPlanName(resolvedPlan.toUpperCase());
        }
        setAccountBillingInterval(resolvedInterval === "annual" ? "annual" : "monthly");
        await refreshPermissions().catch(() => {});
        await refreshAccountSubscription().catch(() => null);
        setShowSubscriptionPlan(false);
        setSubscriptionReturnRoute(null);
        resetWorkspaceHubNavigation();
        clearHistory();
        navigateTo({
          view: "billing",
          showSubscriptionPlan: false,
          projectId: null,
          projectName: "",
          fileId: null,
          fileName: "",
          replace: true,
        });
        return;
      }
      const friendly =
        (typeof errMsg === "string" && errMsg.trim()) ||
        "We could not complete checkout. Check your card and try again, or use Manage Billing.";
      setSubscriptionPaymentError(friendly);
      throw new Error(friendly);
    }
  };

  const handlePlanSelected = async (planId: string, interval: "monthly" | "annual") => {
    console.log("Selected plan:", planId, "interval:", interval);
    setSubscriptionPaymentError(null);
    try {
      if (planId.toUpperCase() === "FREE") {
        // Free plan changes do not require Stripe. Keep this path for first-time setup.
        if (user?.workspaceId) {
          await updateSubscriptionPlan(planId);
        }
        setShowSubscriptionPlan(false);
        resetWorkspaceHubNavigation();
        if (subscriptionReturnRoute === "billing") {
          setShowBillingPage(true);
          navigateTo({
            view: "billing",
            showSubscriptionPlan: false,
            projectId: null,
            projectName: "",
            fileId: null,
            fileName: "",
            replace: true,
          });
        } else if (user?.workspaceId) {
          navigateTo({
            view: "projectDashboard",
            showSubscriptionPlan: false,
            projectId: null,
            projectName: "",
            fileId: null,
            fileName: "",
            replace: true,
          });
        } else {
          try { localStorage.setItem(SUPPRESS_WORKSPACE_AUTO_OPEN_KEY, "true"); } catch {}
          navigateTo({
            view: "workspace",
            showSubscriptionPlan: false,
            projectId: null,
            projectName: "",
            fileId: null,
            fileName: "",
            replace: true,
          });
        }
        setSubscriptionReturnRoute(null);
        return;
      }

      await startSubscriptionCheckout(planId, interval);
    } catch (error: any) {
      console.error("Failed to start subscription flow:", error);
      const msg =
        (error instanceof ApiError && (error.data?.error || error.message)) ||
        error?.message ||
        "Could not start payment setup. Check your connection and try again.";
      setSubscriptionPaymentError(msg);
    }
  };

  const handleSkipPlan = () => {
    setShowSubscriptionPlan(false);
    resetWorkspaceHubNavigation();
    // Explicit route — never rely on history stack.
    // Came from billing → go back to billing.
    // Everything else → workspace selection (new user or existing user upgrading).
    if (subscriptionReturnRoute === "billing") {
      setShowBillingPage(true);
      navigateTo({
        view: "billing",
        showSubscriptionPlan: false,
        projectId: null,
        projectName: "",
        fileId: null,
        fileName: "",
        replace: true,
      });
      setSubscriptionReturnRoute(null);
      return;
    }
    // Suppress WorkspaceSelection auto-trigger so it doesn't re-open the plan page.
    try { localStorage.setItem(SUPPRESS_WORKSPACE_AUTO_OPEN_KEY, "true"); } catch {}
    // Pass showSubscriptionPlan: false explicitly — navigateTo merges with the current
    // route which still has showSubscriptionPlan: true in the same render cycle, and
    // handleRouteChange would re-apply it, keeping the subscription page open.
    navigateTo({
      view: "workspace",
      showSubscriptionPlan: false,
      projectId: null,
      projectName: "",
      fileId: null,
      fileName: "",
      replace: true,
    });
    setSubscriptionReturnRoute(null);
  };

  useEffect(() => {
    // Only attempt payment recovery after the user is authenticated.
    // Running before login means no auth token → billing API returns 401 →
    // onUnauthorized fires logout() → clearSessionCache() removes the fresh token.
    if (!user?.token || paymentRecoveryAttempted.current) return;
    paymentRecoveryAttempted.current = true;

    const params = new URLSearchParams(window.location.search);
    const setupIntentId = params.get("setup_intent");
    const redirectStatus = params.get("redirect_status");
    if (setupIntentId) {
      window.history.replaceState({}, "", window.location.pathname);

      if (redirectStatus !== "succeeded") {
        try {
          localStorage.removeItem("pendingSubscription");
          localStorage.removeItem("pendingPaymentRecovery");
        } catch {}
        return;
      }

      try {
        const stored = localStorage.getItem("pendingSubscription");
        localStorage.removeItem("pendingSubscription");
        if (!stored) return;

        const { planName, interval } = JSON.parse(stored);
        localStorage.setItem(
          "pendingPaymentRecovery",
          JSON.stringify({ setupIntentId, workspaceId: "", planName, interval }),
        );
        handlePaymentConfirmed(setupIntentId, planName, interval).catch((err) => {
          console.error("[App] Failed to resume subscription after redirect:", err);
          const msg =
            (err instanceof ApiError && (err.data?.error || err.message)) ||
            err?.message ||
            "Subscription could not be completed after payment redirect.";
          setSubscriptionPaymentError(msg);
        });
      } catch (err) {
        console.error("[App] Failed to parse pending subscription:", err);
      }
      return;
    }

    try {
      const recoveryRaw = localStorage.getItem("pendingPaymentRecovery");
      if (!recoveryRaw) return;

      const { setupIntentId: recoveryIntentId, planName, interval } = JSON.parse(recoveryRaw);
      if (!recoveryIntentId || !planName) return;

      handlePaymentConfirmed(recoveryIntentId, planName, interval).catch((err) => {
        console.error("[App] Failed to retry pending subscription:", err);
        const msg =
          (err instanceof ApiError && (err.data?.error || err.message)) ||
          err?.message ||
          "Could not complete a pending subscription. Open Billing and try again.";
        setSubscriptionPaymentError(msg);
      });
    } catch {
      try { localStorage.removeItem("pendingPaymentRecovery"); } catch {}
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.token]);

  const handleDeploymentSelected = async (type: "self-hosted" | "cloud") => {
    console.log("[App] Deployment selected:", type);
    setDeploymentType(type);

    // Store preference
    localStorage.setItem("deploymentType", type);

    // Update apiClient base URL immediately
    updateBaseUrl(type);
    console.log("[App] API client base URL updated for deployment type:", type);

    // Get the API base URL from centralized config
    const baseUrl = getGatewayUrl(type);

    // Notify extension to update API URLs
    if (window.vscode) {
      window.vscode.postMessage({
        type: "setApiBaseUrl",
        url: baseUrl,
        deploymentType: type, // Send deployment type explicitly
      });
    }

    // If user is already logged in, update their role
    if (user) {
      try {
        await updateUserRole(type);
        console.log("[App] User role updated successfully");
      } catch (error) {
        console.error("[App] Failed to update user role:", error);
      }
    }

    // User will proceed to login/signup with deployment type already selected
  };

  const handleLogout = () => {
    // Reset all route state before logout
    setSelectedProjectId(null);
    setSelectedProjectName("");
    setSelectedFileId(null);
    setSelectedFileName("");
    setShowSubscriptionPlan(false);
    setInviteToken(null);
    setInviteEmail(null);
    setForceShowWorkspace(false); // Reset workspace view state
    setSkipWorkspaceRequested(false);
    autoRestoredRef.current = false; // Allow auto-restore on next login
    pendingDesktopFilePathRef.current = null;
    clearDesktopActiveFile();
    (window as any).electronAPI?.clearActiveFilePath?.();
    // Clear route history
    clearHistory();
    // Keep deployment type so user doesn't need to select again
    logout();
  };

  const handleBackToProjectDashboard = () => {
    clearLastOpenedSelection();
    // Reset state directly so showProjectDashboard condition passes immediately.
    // Without this, currentRoute can fall to the "login" branch when selectedFileId
    // is "__editor__", causing navigateTo to spread isLoginView:true into the route.
    setSelectedProjectId(null);
    setSelectedProjectName("");
    setSelectedFileId(null);
    setSelectedFileName("");
    navigateTo({
      view: "projectDashboard",
      projectId: null,
      projectName: "",
      fileId: null,
      fileName: "",
    });
  };

  const handleBackToProjectLibrary = () => {
    // Use deterministic route navigation so back works regardless of browser history state.
    navigateTo({
      view: "projectLibrary",
      projectId: selectedProjectId, // Preserve current project
      projectName: selectedProjectName,
      fileId: null,
      fileName: "",
    });
  };

  const handleInvitationAccepted = (workspaceData?: any) => {
    console.log("[App] ✅ Invitation accepted, workspace data:", workspaceData);
    // Clear invitation state
    setInviteToken(null);
    setInviteEmail(null);

    // Clear any previously-loaded editor state so the old workspace's file
    // is not shown after the user lands in the new workspace.
    clearLastOpenedProjectState();

    if (workspaceData) {
      console.log("[App] Successfully joined workspace:", workspaceData.workspaceId || workspaceData.workspace?.id);
      // Select the workspace the user just joined
      if (workspaceData.workspaceId) {
        // Trigger workspace selection to get proper JWT with workspace context
        // This will automatically navigate to the Project Dashboard
        selectWorkspace({
          workspaceId: workspaceData.workspaceId,
          workspaceName: workspaceData.workspaceName,
          jwt: workspaceData.jwt || workspaceData.workspace?.jwt,
        });
        console.log("[App] Workspace selected, navigating to Project Dashboard...");
      }
    }
  };

  const handleInvitationLoginRequired = (email: string) => {
    console.log("[App] ⚠️  Login required for invitation, email:", email);
    // Store invitation token to restore after login
    const currentToken = inviteToken;
    setInviteEmail(email);
    // Keep the token so user can accept invitation after logging in
    console.log("[App] Keeping invitation token for post-login acceptance:", currentToken);
    // Show auth form while keeping the invite token for later
    setShowAuthForInvitation(true);
    setIsLoginView(true);
  };

  const handleInvitationSignupRequired = (email: string) => {
    console.log("[App] 📝 Signup required for invitation, email:", email);
    setInviteEmail(email);
    // Show signup form while keeping the invite token for later
    setShowAuthForInvitation(true);
    setIsLoginView(false); // Show signup form instead of login
  };

  const handleInvitationError = () => {
    console.log("[App] ❌ Invitation error, clearing state and showing login");
    // Clear invitation and go to login
    setInviteToken(null);
    setInviteEmail(null);
    setShowAuthForInvitation(false);
    setIsLoginView(true);
  };

  // When user logs in successfully while having an invite, go back to invitation page
  useEffect(() => {
    if (user && showAuthForInvitation && inviteToken) {
      console.log("[App] User logged in with pending invitation, returning to invitation page");
      setShowAuthForInvitation(false); // Show invitation page again now that user is logged in
    }
  }, [user, showAuthForInvitation, inviteToken]);

  // Show email verification UI when arriving via /verify-email?token=...
  // Rendered BEFORE the loading spinner so it shows immediately on first paint.
  if (emailVerifyToken && emailVerifyStatus !== "idle") {
    const handleGoToLogin = () => {
      // Ensure deployment type is set so the login form renders (webapp = always cloud)
      if (!deploymentType) {
        setDeploymentType("cloud");
        localStorage.setItem("deploymentType", "cloud");
        updateBaseUrl("cloud");
      }
      // Pre-fill the verified email in the login form
      setVerificationEmail(verifiedEmail);
      setEmailVerifyToken(null);
      setEmailVerifyStatus("idle");
      setAuthSubView("login");
      setIsLoginView(true);
    };

    const handleResendVerification = async () => {
      const email = verifyResendEmail.trim();
      setVerifyResendMessage("");
      setVerifyResendError("");

      if (!email) {
        setVerifyResendError("Enter your email address to resend the verification link.");
        return;
      }

      setIsVerifyResending(true);
      try {
        const message = await resendVerification(email);
        setVerifyResendMessage(message);
      } catch (err) {
        setVerifyResendError(err instanceof Error ? err.message : "Failed to resend verification email");
      } finally {
        setIsVerifyResending(false);
      }
    };

    return (
      <div className="min-h-screen bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 flex items-center justify-center p-4">
        <div className="absolute inset-0 overflow-hidden pointer-events-none">
          <div className="absolute -top-40 -right-40 w-80 h-80 bg-purple-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse" />
          <div className="absolute -bottom-40 -left-40 w-80 h-80 bg-indigo-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse delay-1000" />
        </div>
        <div className="relative bg-white/10 backdrop-blur-xl border border-white/20 rounded-2xl shadow-2xl p-8 w-full max-w-md text-center">
          {emailVerifyStatus === "verifying" && (
            <>
              <Loader2 className="animate-spin mx-auto mb-4 text-purple-400" size={48} />
              <h2 className="text-xl font-bold text-white mb-2">Verifying your email...</h2>
              <p className="text-gray-300 text-sm">Please wait while we verify your account.</p>
            </>
          )}
          {emailVerifyStatus === "success" && (
            <>
              <div className="w-16 h-16 bg-green-500/20 rounded-full flex items-center justify-center mx-auto mb-4">
                <svg className="w-8 h-8 text-green-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                </svg>
              </div>
              <h2 className="text-2xl font-bold text-white mb-2">Email Verified!</h2>
              <p className="text-gray-300 text-sm mb-1">Your account has been verified successfully.</p>
              {verifiedEmail && (
                <p className="text-purple-300 text-sm font-medium mb-6">{verifiedEmail}</p>
              )}
              <button
                onClick={handleGoToLogin}
                className="w-full py-3 px-4 bg-gradient-to-r from-purple-500 to-indigo-500 hover:from-purple-600 hover:to-indigo-600 text-white rounded-lg text-sm font-medium transition-all duration-300 focus:outline-none focus:ring-2 focus:ring-purple-500"
              >
                Login to OntoCode
              </button>
            </>
          )}
          {emailVerifyStatus === "error" && (
            <>
              <div className="w-16 h-16 bg-red-500/20 rounded-full flex items-center justify-center mx-auto mb-4">
                <svg className="w-8 h-8 text-red-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </div>
              <h2 className="text-xl font-bold text-white mb-2">Verification Failed</h2>
              <p className="text-red-300 text-sm mb-4">{emailVerifyError}</p>
              <div className="bg-white/5 border border-white/10 rounded-xl p-4 mb-4 text-left">
                <label htmlFor="verify-resend-email" className="block text-xs font-medium text-gray-300 mb-2">
                  Resend verification link
                </label>
                <input
                  id="verify-resend-email"
                  type="email"
                  value={verifyResendEmail}
                  onChange={(e) => setVerifyResendEmail(e.target.value)}
                  placeholder="Enter your email address"
                  className="w-full bg-white/10 border border-white/20 rounded-lg px-3 py-2 text-sm text-white placeholder:text-gray-500 focus:outline-none focus:ring-2 focus:ring-purple-500"
                />
                {verifyResendMessage && (
                  <p className="mt-2 text-xs text-green-300">{verifyResendMessage}</p>
                )}
                {verifyResendError && (
                  <p className="mt-2 text-xs text-red-300">{verifyResendError}</p>
                )}
                <button
                  onClick={handleResendVerification}
                  disabled={isVerifyResending}
                  className="mt-3 w-full flex items-center justify-center gap-2 py-2.5 rounded-lg text-sm font-medium text-white transition-all duration-300 bg-white/10 border border-white/20 hover:bg-white/20 disabled:opacity-60"
                >
                  {isVerifyResending ? <Loader2 size={16} className="animate-spin" /> : <RefreshCw size={16} />}
                  {isVerifyResending ? "Sending..." : "Resend Verification Link"}
                </button>
              </div>
              <button
                onClick={handleGoToLogin}
                className="px-6 py-2 bg-gradient-to-r from-purple-500 to-indigo-500 hover:from-purple-600 hover:to-indigo-600 text-white rounded-lg text-sm font-medium"
              >
                Back to Sign In
              </button>
            </>
          )}
        </div>
      </div>
    );
  }

  if (maintenanceActive) {
    return <MaintenancePage message={maintenanceMessage} />;
  }

  if (loading) {
    return (
      <div
        className="min-h-screen flex items-center justify-center"
        style={{ backgroundColor: "var(--color-background)" }}
      >
        <div className="text-center p-8">
          <div
            className="w-20 h-20 rounded-2xl flex items-center justify-center shadow-lg mx-auto mb-6"
            style={{ background: "linear-gradient(to bottom right, var(--color-primary), var(--color-secondary))" }}
          >
            <Loader2 size={40} className="text-white animate-spin" />
          </div>
          <h2 className="text-2xl font-bold mb-3" style={{ color: "var(--color-text)" }}>
            Initializing OntoCode
          </h2>
          <p className="text-sm" style={{ color: "var(--color-text-secondary)" }}>
            Connecting to your workspace...
          </p>
        </div>
      </div>
    );
  }

  // Desktop: block the app when the imported (paid) license has expired.
  // The user must renew on the web. FREE/perpetual licenses never expire.
  if (isDesktop() && isLicenseExpired(desktopLicense)) {
    const expiredPlan = licensePlan(desktopLicense);
    return (
      <div
        className="min-h-screen flex items-center justify-center p-4"
        style={{ backgroundColor: "var(--color-background)" }}
      >
        <div
          className="max-w-md w-full rounded-2xl shadow-xl p-8 text-center"
          style={{ backgroundColor: "var(--color-surface)", border: "1px solid var(--color-border)" }}
        >
          <div className="w-16 h-16 rounded-full flex items-center justify-center mx-auto mb-6 bg-amber-500/15">
            <RefreshCw size={32} className="text-amber-500" />
          </div>
          <h2 className="text-2xl font-bold mb-2" style={{ color: "var(--color-text)" }}>
            License expired
          </h2>
          <p className="text-sm mb-6" style={{ color: "var(--color-text-secondary)" }}>
            Your {expiredPlan} license has expired. Renew it on the web to continue using OntoCode Desktop,
            then import the updated license file.
          </p>
          <button
            onClick={() => {
              const api = (window as any).electronAPI;
              if (api?.openPurchase) api.openPurchase((expiredPlan || "pro").toLowerCase());
            }}
            className="w-full py-3 px-4 rounded-lg text-sm font-medium text-white bg-gradient-to-r from-purple-500 to-indigo-500 hover:from-purple-600 hover:to-indigo-600 transition-all"
          >
            Renew on the web
          </button>
        </div>
      </div>
    );
  }

  // Show invitation acceptance page if there's an invite token (whether logged in or not)
  // But if user clicked login/signup, show auth form first
  if (inviteToken && !showAuthForInvitation) {
    console.log("[App] 🎫 Rendering InviteAcceptPage with token:", inviteToken.substring(0, 20) + "...");
    return (
      <InviteAcceptPage
        token={inviteToken}
        onAccepted={handleInvitationAccepted}
        onLoginRequired={handleInvitationLoginRequired}
        onSignupRequired={handleInvitationSignupRequired}
        onError={handleInvitationError}
      />
    );
  }

  // Debug: Log why we're not showing invitation page
  if (inviteToken) {
    console.log(
      "[App] ⚠️ Have invite token but not showing InviteAcceptPage. showAuthForInvitation:",
      showAuthForInvitation,
    );
  }

  // Show reset-password form immediately when accessed via email link,
  // bypassing the deployment-type guard (user has no context yet).
  if (!user && authSubView === "resetPassword" && resetToken) {
    return (
      <ResetPasswordForm
        onBackToLogin={() => {
          setAuthSubView("login");
          setIsLoginView(true);
          setResetToken(null);
        }}
        initialToken={resetToken}
      />
    );
  }

  // Show deployment selector if user hasn't selected deployment type yet (regardless of login state)
  if (!deploymentType) {
    return <DeploymentSelector onSelect={handleDeploymentSelected} />;
  }

  // Bug #44 / #50: BillingManagement is ALWAYS account-level (Model B —
  // one Stripe customer per user account, workspaces inherit the plan).
  // We pass an empty workspaceId so every API call hits the account
  // endpoints, and isOwner is always true because it's the user's own
  // account. Must be checked BEFORE the workspace-selection short-circuit
  // so navigating from WorkspaceSelection actually works.
  // Desktop download page — works before login via showDesktopDownload state.
  // Never shown inside the desktop app itself.
  if (showDesktopDownload && !isDesktop()) {
    return (
      <Suspense fallback={<div className="min-h-screen bg-slate-900" />}>
        <DesktopDownloadPage onBack={() => setShowDesktopDownload(false)} />
      </Suspense>
    );
  }

  if (user && showBillingPage && !isDesktop()) {
    console.log("[App] 🎨 Rendering BillingManagement page (account-level)");
    return (
      <Suspense fallback={
        <div className="min-h-screen flex items-center justify-center bg-[#0f172a]">
          <Loader2 size={40} className="text-purple-500 animate-spin" />
        </div>
      }>
        <BillingManagement
          workspace={{
            workspaceId: "", // always account-level
            name: "Your Account",
            subscriptionPlan: user.subscriptionPlan || "FREE",
            billingStatus: workspaceBillingStatus || "ACTIVE",
            billingInterval: (user as any).billingInterval || "monthly",
          }}
          // Account-level billing — the user is always the owner of their
          // own Stripe customer.
          isOwner={true}
          onBack={goToWorkspaceHub}
          onCancelled={goToWorkspaceHub}
          onUpgradePlan={openAccountSubscription}
        />
      </Suspense>
    );
  }

  // Show subscription plan selection before workspace selection. Billing is
  // account-level, so a selected workspace is not required to buy or renew.
  if (user && showSubscriptionPlan && !isDesktop()) {
    const status = (accountSubscriptionStatus || "").toLowerCase();
    // Use plan name from billing API (authoritative); fall back to JWT value only if API hasn't loaded yet.
    const resolvedPlanId = accountPlanName || (user.subscriptionPlan ? user.subscriptionPlan.toUpperCase() : "FREE");
    const allowCurrentPlanSelection =
      resolvedPlanId !== "FREE"
      && status !== "active"
      && status !== "trialing";
    // Anyone who has had a paid plan before must not get a free trial again.
    const effectiveTrialEligible = trialEligible && resolvedPlanId === "FREE";
    if (subscriptionPageRefreshing) {
      return (
        <div className="min-h-screen flex items-center justify-center bg-[#0f172a]">
          <div className="flex flex-col items-center gap-3 text-slate-300">
            <Loader2 size={40} className="text-purple-500 animate-spin" />
            <p className="text-sm">Refreshing subscription details...</p>
          </div>
        </div>
      );
    }
    return (
      <>
        {subscriptionPaymentError && (
          <div className="mx-auto max-w-3xl mb-4 px-4 py-3 rounded-xl border border-red-400/40 bg-red-500/10 text-red-200 text-sm text-center">
            {subscriptionPaymentError}
          </div>
        )}
        <SubscriptionPlanSelection
          username={user.username}
          workspaceId=""
          workspaceName="Your Account"
          currentPlanId={resolvedPlanId}
          currentStatus={accountSubscriptionStatus || ""}
          currentBillingInterval={accountBillingInterval}
          trialEligible={effectiveTrialEligible}
          allowCurrentPlanSelection={allowCurrentPlanSelection}
          onPlanSelected={handlePlanSelected}
          onSkip={handleSkipPlan}
          onLogout={handleLogout}
        />
        {subscriptionCheckout && (
          <Suspense fallback={null}>
            <PaymentSetupModal
              publishableKey={subscriptionCheckout.publishableKey}
              clientSecret={subscriptionCheckout.clientSecret}
              planName={subscriptionCheckout.planName}
              interval={subscriptionCheckout.interval}
              workspaceId=""
              trialEligible={subscriptionCheckout.trialEligible}
              currentStatus={accountSubscriptionStatus || ""}
              onConfirmed={async (setupIntentId) => {
                await handlePaymentConfirmed(setupIntentId);
              }}
              onClose={() => {
                setSubscriptionCheckout(null);
                setSubscriptionPaymentError(null);
              }}
            />
          </Suspense>
        )}
      </>
    );
  }

  // Admin users bypass workspace selection entirely — they only configure the system
  if (user?.isAdmin) {
    return <AdminSettingsModal isOpen={true} onClose={() => {}} pageMode onLogout={handleLogout} />;
  }

  // Show workspace selection if user is logged in but hasn't selected a workspace
  const showWorkspaceSelectionScreen =
    !isDesktop() && user && (shouldShowWorkspaceSelection() || isWorkspacePaymentPending) && !selectedFileId;
  console.log("[App] Render decision - showWorkspaceSelectionScreen:", showWorkspaceSelectionScreen);

  if (showWorkspaceSelectionScreen) {
    console.log("[App] 🎨 Rendering WorkspaceSelection component");
    return (
      <Suspense fallback={
        <div className="min-h-screen flex items-center justify-center" style={{ backgroundColor: "var(--color-background)" }}>
          <Loader2 size={40} className="text-purple-500 animate-spin" />
        </div>
      }>
        <WorkspaceSelection
          username={user.username}
          isAdmin={user.isAdmin || false}
          // Bug #44: route the top-right "Manage Billing" pill to the new
          // BillingManagement page in account-level mode instead of the
          // legacy in-place modal. The page treats an empty workspaceId as
          // "your account" and the backend's billing endpoints accept it.
          onManageAccountBilling={(isDesktop() || user.enterpriseDomainBypass) ? undefined : () => navigateTo({ view: 'billing' })}
          onUpgradeAccountPlan={(isDesktop() || user.enterpriseDomainBypass) ? undefined : openAccountSubscription}
          onWorkspaceSelected={handleWorkspaceSelected}
          onSkipWorkspace={() => {
            console.log("[App] 🚀 User chose to continue without workspace");
            console.log("[App] Current needsWorkspaceSelection:", needsWorkspaceSelection);
            console.log("[App] Current user:", { email: user?.email, workspaceId: user?.workspaceId });
            setSkipWorkspaceRequested(true);
            // Update auth context to skip workspace selection
            selectWorkspace({ skipWorkspace: true });
            setForceShowWorkspace(false);
            setRestoredRoute(null);
            setSelectedProjectId(null);
            setSelectedProjectName("");
            setSelectedFileId("__editor__");
            setSelectedFileName("");
            navigateTo({
              view: "dashboard",
              projectId: null,
              projectName: "",
              fileId: "__editor__",
              fileName: "",
            });
            console.log("[App] ✅ Continue without workspace now routes to editor");
          }}
          onLogout={handleLogout}
        />
      </Suspense>
    );
  }

  // Show Project Dashboard for workspace members (both admins and non-admins)
  // Show only when no file is selected AND (no project selected OR has pending file to upload)
  const showProjectDashboard =
    user && user.workspaceId && !showSubscriptionPlan && !selectedFileId && (!selectedProjectId || pendingFile);
  console.log("[App] ProjectDashboard check:", {
    hasUser: !!user,
    hasWorkspaceId: !!user?.workspaceId,
    showSubscriptionPlan,
    selectedFileId,
    selectedProjectId,
    hasPendingFile: !!pendingFile,
    shouldShow: showProjectDashboard,
  });

  if (showProjectDashboard) {
    console.log(
      "[App] 🎨 Routing to ProjectDashboard - isAdmin:",
      user.isAdmin,
      "selectedFileId:",
      selectedFileId,
      "selectedProjectId:",
      selectedProjectId,
      "pendingFile:",
      !!pendingFile,
    );
    const workspacePlan = (user.subscriptionPlan || "FREE").toUpperCase();
    const isEnterpriseDomainBypass = user.enterpriseDomainBypass || false;
    const hasPaidPlan = !isEnterpriseDomainBypass && (workspacePlan === "PRO" || workspacePlan === "ENTERPRISE");
    return (
      <>
        <ProjectDashboard
          onSelectProject={handleProjectSelected}
          pendingFile={pendingFile}
          onOpenLocalFile={
            isDesktop() || (window as any).__ONTOCODE_BROWSER_BRIDGE__
              ? handleOpenLocalFile
              : undefined
          }
          onManageSubscription={(isDesktop() || !hasPaidPlan) ? undefined : () => navigateTo({ view: 'billing' })}
          onOpenSubscriptionPlans={(isDesktop() || isEnterpriseDomainBypass) ? undefined : openAccountSubscription}
        />
      </>
    );
  }
  

  // (BillingManagement render moved earlier \u2014 see top of render fn.)

  // Show Project Library when a project is selected but no file is selected
  // Available to all workspace members (both admins and non-admins)
  if (user && user.workspaceId && selectedProjectId && !selectedFileId) {
    console.log("[App] Routing to ProjectLibrary - isAdmin:", user.isAdmin, "projectId:", selectedProjectId);
    return (
      <ProjectLibrary
        projectId={selectedProjectId}
        projectName={selectedProjectName}
        onBack={handleBackToProjects}
        onFileSelect={handleFileSelected}
        onOpenEditor={() => {
          console.log("[App] Opening editor without file for project:", selectedProjectId);
          setSelectedFileId("__editor__");
          setSelectedFileName("");
        }}
      />
    );
  }

  // Show main Dashboard/Editor when:
  // 1. Workspace member (admin or non-admin) has selected a file, OR
  // 2. Non-workspace user (goes directly to editor without workspace flow)
  if (user) {
    console.log(
      "[App] Routing to Dashboard - isAdmin:",
      user.isAdmin,
      "workspaceId:",
      user.workspaceId,
      "selectedFileId:",
      selectedFileId,
      "selectedProjectId:",
      selectedProjectId,
    );
    return (
      <Dashboard
        onBackToProjects={
          isDesktop() || user.workspaceId
            ? handleBackToProjectLibrary
            : () => {
                clearLastOpenedSelection();
                setForceShowWorkspace(true);
              }
        }
        onGoToProjectDashboard={
          isDesktop() || user.workspaceId
            ? handleBackToProjectDashboard
            : () => {
                clearLastOpenedSelection();
                setForceShowWorkspace(true);
              }
        }
        onGoToWorkspace={
          isDesktop()
            ? undefined
            : () => {
                clearLastOpenedSelection();
                try {
                  localStorage.setItem(SUPPRESS_WORKSPACE_AUTO_OPEN_KEY, "true");
                } catch {
                  // ignore
                }
                setForceShowWorkspace(true);
                setRestoredRoute(null);
              }
        }
        onFileSelected={handleFileSelected}
        selectedFileId={selectedFileId || undefined}
        selectedFileName={selectedFileName || undefined}
        projectId={selectedProjectId || undefined}
      />
    );
  } else {
    // If there's an invitation, prefill the email in login/signup and show back button
    const handleBackToInvitation = () => {
      navigateTo({
        view: "invitation",
        showAuthForInvitation: false,
      });
    };

    const handleBackToWelcome = () => {
      localStorage.removeItem("deploymentType");
      navigateTo({
        view: "deployment",
        deploymentType: null,
      });
    };

    const handleBackToLogin = () => {
      setAuthSubView("login");
      setIsLoginView(true);
      setEmailVerifyToken(null);
      setEmailVerifyStatus("idle");
    };

    if (authSubView === "verifyEmail") {
      return <EmailVerificationNotice email={verificationEmail} onBackToLogin={handleBackToLogin} />;
    }

    if (authSubView === "forgotPassword") {
      return (
        <ForgotPasswordForm
          onBackToLogin={handleBackToLogin}
          onResetTokenReceived={() => setAuthSubView("resetPassword")}
        />
      );
    }

    if (authSubView === "resetPassword") {
      return <ResetPasswordForm onBackToLogin={handleBackToLogin} initialToken={resetToken || undefined} />;
    }

    return isLoginView ? (
      <LoginForm
        onToggleForm={toggleFormView}
        prefillEmail={inviteEmail || verificationEmail || undefined}
        onBackToInvitation={inviteToken ? handleBackToInvitation : undefined}
        onBackToWelcome={handleBackToWelcome}
        onForgotPassword={() => setAuthSubView("forgotPassword")}
      />
    ) : (
      <SignupForm
        onToggleForm={toggleFormView}
        prefillEmail={inviteEmail || undefined}
        onBackToInvitation={inviteToken ? handleBackToInvitation : undefined}
        onBackToWelcome={handleBackToWelcome}
        onVerificationRequired={(email) => {
          setVerificationEmail(email);
          setAuthSubView("verifyEmail");
        }}
      />
    );
  }
};

const App = () => {
  return (
    <ThemeProvider>
      <CollaborationProvider>
        <EntityPreferencesProvider>
          <Suspense fallback={null}>
            <DesktopUpdateBanner />
          </Suspense>
          <AppContent />
        </EntityPreferencesProvider>
      </CollaborationProvider>
    </ThemeProvider>
  );
};

export default App;

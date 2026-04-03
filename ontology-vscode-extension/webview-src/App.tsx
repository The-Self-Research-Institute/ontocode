import React, { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { useAuth } from "./custom-hook/useAuth";
import apiClient, { updateBaseUrl } from "./services/apiClient";
import { openOntologyFile, fileContentToBase64 } from "./utils/fileAccess";
import { getGatewayUrl } from "./config/deploymentConfig";
import { CollaborationProvider } from "./contexts/CollaborationContext";
import { EntityPreferencesProvider } from "./contexts/EntityPreferencesContext";
import { ThemeProvider } from "./contexts/ThemeContext";
import Dashboard from "./components/Dashboard";
import LoginForm from "./components/LoginForm";
import SignupForm from "./components/SignupForm";
import DeploymentSelector from "./components/DeploymentSelector";
import WorkspaceSelection from "./components/WorkspaceSelection";
import ProjectDashboard from "./components/ProjectDashboard";
import ProjectLibrary from "./components/ProjectLibrary";
import SubscriptionPlanSelection from "./components/SubscriptionPlanSelection";
import InviteAcceptPage from "./components/InviteAcceptPage";
import EmailVerificationNotice from "./components/EmailVerificationNotice";
import ForgotPasswordForm from "./components/ForgotPasswordForm";
import ResetPasswordForm from "./components/ResetPasswordForm";
import { Loader2 } from "lucide-react";
import { useRouter, RouteState } from "./hooks/useRouter";

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
    selectWorkspace,
    logout,
    updateSubscriptionPlan,
    updateUserRole,
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
  const [inviteToken, setInviteToken] = useState<string | null>(initialInvitation.token);
  const [inviteEmail, setInviteEmail] = useState<string | null>(initialInvitation.email);
  const [pendingFile, setPendingFile] = useState<{ fileName: string; fileContent: string; fileSize: number } | null>(
    null,
  );
  const [showAuthForInvitation, setShowAuthForInvitation] = useState(false); // Show login/signup form while keeping invite token
  const [needsDeploymentSelection, setNeedsDeploymentSelection] = useState(false);
  const [deploymentType, setDeploymentType] = useState<"self-hosted" | "cloud" | null>(null);
  const [forceShowWorkspace, setForceShowWorkspace] = useState(false);
  const [skipWorkspaceRequested, setSkipWorkspaceRequested] = useState(false);
  const [restoredRoute, setRestoredRoute] = useState<RouteState | null>(null);
  const [authSubView, setAuthSubView] = useState<
    "login" | "signup" | "forgotPassword" | "resetPassword" | "verifyEmail"
  >("login");
  const [verificationEmail, setVerificationEmail] = useState("");
  const [resetToken, setResetToken] = useState<string | null>(null);
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

  // Helper to check if workspace selection is required
  const shouldShowWorkspaceSelection = useCallback((): boolean => {
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

    // If user explicitly skipped workspace selection, don't show it
    if (!needsWorkspaceSelection) {
      console.log("[App] Returning false - needsWorkspaceSelection is false");
      return false;
    }

    const storedDeploymentType = localStorage.getItem("deploymentType") as "self-hosted" | "cloud" | null;

    // Cloud users always need workspace selection if they don't have one (unless they skipped)
    if (storedDeploymentType === "cloud") {
      return true;
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

  // Determine current route based on state
  const currentRoute: RouteState = useMemo(() => {
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
    if (!user && !deploymentType) {
      return { view: "deployment", deploymentType };
    }
    if (user && shouldShowWorkspaceSelection()) {
      return { view: "workspace" };
    }
    if (user && user.isAdmin && user.workspaceId && showSubscriptionPlan) {
      return { view: "subscription", showSubscriptionPlan };
    }
    if (user && user.workspaceId && !showSubscriptionPlan && !selectedFileId && (!selectedProjectId || pendingFile)) {
      return { view: "projectDashboard", projectId: selectedProjectId, projectName: selectedProjectName };
    }
    if (user && user.workspaceId && selectedProjectId && !selectedFileId) {
      return { view: "projectLibrary", projectId: selectedProjectId, projectName: selectedProjectName };
    }
    if (user && selectedFileId) {
      return {
        view: "dashboard",
        projectId: selectedProjectId,
        projectName: selectedProjectName,
        fileId: selectedFileId,
        fileName: selectedFileName,
      };
    }
    if (user && !user.workspaceId) {
      return { view: "dashboard" };
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
    pendingFile,
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

    // Update view-specific flags
    if (updatedRoute.view === "workspace") {
      setForceShowWorkspace(true);
    } else if (updatedRoute.view === "deployment") {
      setForceShowWorkspace(false);
      if (!updatedRoute.deploymentType) {
        setDeploymentType(null);
      }
    } else {
      setForceShowWorkspace(false);
    }

    // Restore non-workspace editor state when navigating to dashboard without project context
    if (updatedRoute.view === "dashboard" && !updatedRoute.projectId && !updatedRoute.projectName) {
      setSkipWorkspaceRequested(true);
      if (!updatedRoute.fileId) {
        setSelectedFileId("__editor__");
      }
    }
  }, []);

  // Initialize router
  const { clearHistory, navigateTo, goBack, goForward } = useRouter(currentRoute, handleRouteChange);

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

  // Detect /verify-email?token=... URL, verify the account, and show success screen.
  // State is already initialised from the URL above; this effect just kicks off the fetch.
  useEffect(() => {
    if (!emailVerifyToken) return;
    // Guard against StrictMode double-invocation (ref persists across the simulated
    // unmount/remount cycle, unlike component state).
    if (_verifyFetchStarted.current) return;
    _verifyFetchStarted.current = true;

    fetch(`/api/auth/verify?token=${encodeURIComponent(emailVerifyToken)}`)
      .then(async (res) => {
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
          throw new Error(data?.error || data?.message || "Verification failed");
        }
        const email = data?.email || "";
        setVerifiedEmail(email);
        setEmailVerifyStatus("success");
        window.history.replaceState({}, "", "/");
      })
      .catch((err: any) => {
        console.error("[App] Email verification failed:", err);
        setEmailVerifyStatus("error");
        setEmailVerifyError(err?.message || "Verification failed");
      });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Detect /reset-password?token=... URL and show reset form
  useEffect(() => {
    const pathname = window.location.pathname;
    if (pathname.startsWith("/reset-password")) {
      const params = new URLSearchParams(window.location.search);
      const token = params.get("token");
      if (token) {
        console.log("[App] 🔑 Found reset-password token in URL");
        setResetToken(token);
        setAuthSubView("resetPassword");
        window.history.replaceState({}, "", "/");
      }
    }
  }, []);

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
        setShowSubscriptionPlan(true);
      }
    };

    window.addEventListener("message", handleMessage);
    return () => window.removeEventListener("message", handleMessage);
  }, []);

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
    if (fileData) {
      console.log("[App] 📂 File picked in browser mode:", fileData.fileName);
      setPendingFile(fileData);
    }
  }, []);

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
    // Navigate using router to update browser history
    navigateTo({
      view: "dashboard",
      projectId: selectedProjectId, // Preserve current project
      projectName: selectedProjectName,
      fileId: fileId,
      fileName: fileName,
    });
  };

  const handlePlanSelected = async (planId: string) => {
    console.log("Selected plan:", planId);
    try {
      // Save plan to backend via auth context
      await updateSubscriptionPlan(planId);
      setShowSubscriptionPlan(false);
    } catch (error) {
      console.error("Failed to save subscription plan:", error);
    }
  };

  const handleSkipPlan = () => {
    setShowSubscriptionPlan(false);
  };

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
    // Clear route history
    clearHistory();
    // Keep deployment type so user doesn't need to select again
    logout();
  };

  const handleBackToProjectDashboard = () => {
    // Use deterministic route navigation so back works regardless of browser history state.
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

  // Show deployment selector BEFORE login if user hasn't selected deployment type yet
  if (!user && !deploymentType) {
    return <DeploymentSelector onSelect={handleDeploymentSelected} />;
  }

  // Show workspace selection if user is logged in but hasn't selected a workspace
  const showWorkspaceSelectionScreen = user && shouldShowWorkspaceSelection();
  console.log("[App] Render decision - showWorkspaceSelectionScreen:", showWorkspaceSelectionScreen);

  if (showWorkspaceSelectionScreen) {
    console.log("[App] 🎨 Rendering WorkspaceSelection component");
    return (
      <WorkspaceSelection
        username={user.username}
        isAdmin={user.isAdmin || false}
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
    );
  }

  // Show subscription plan selection for admins only (not for workspace members)
  if (user && user.isAdmin && user.workspaceId && showSubscriptionPlan) {
    return (
      <SubscriptionPlanSelection
        username={user.username}
        workspaceId={user.workspaceId}
        workspaceName={user.workspaceName || "Workspace"}
        onPlanSelected={handlePlanSelected}
        onSkip={handleSkipPlan}
        onLogout={handleLogout}
      />
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
    return (
      <ProjectDashboard
        onSelectProject={handleProjectSelected}
        pendingFile={pendingFile}
        onOpenLocalFile={(window as any).__ONTOCODE_BROWSER_BRIDGE__ ? handleOpenLocalFile : undefined}
        onOpenEditor={() => {
          console.log("[App] Opening editor from Project Dashboard (no file)");
          setSelectedFileId("__editor__");
          setSelectedFileName("");
        }}
      />
    );
  }

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
        key={selectedFileId || "default"} // Force remount when file changes
        onBackToProjects={user.workspaceId ? handleBackToProjectLibrary : undefined}
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
          <AppContent />
        </EntityPreferencesProvider>
      </CollaborationProvider>
    </ThemeProvider>
  );
};

export default App;

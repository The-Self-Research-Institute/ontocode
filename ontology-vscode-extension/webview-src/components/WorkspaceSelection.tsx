import React, { useState, useEffect, useRef, useCallback, useMemo } from "react";
import { Loader2, Plus, Users, Crown, Building2, ChevronRight, Trash, AlertTriangle, Bug, CheckCircle, Shield, CreditCard } from "lucide-react";
import { loadStripe, StripeElementsOptions } from "@stripe/stripe-js";
import { Elements, PaymentElement, useStripe, useElements } from "@stripe/react-stripe-js";
import apiClient from "../services/apiClient";
import SubscriptionPlanSelection from "./SubscriptionPlanSelection";
import PaymentSetupModal from "./PaymentSetupModal";
import ManageSubscriptionModal from "./ManageSubscriptionModal";
import { getGatewayUrl } from "../config/deploymentConfig";
import { usePlanPricing } from "../hooks/usePlanPricing";
import { ReportIssueModal } from "./ReportIssueModal";
import { validateWorkspaceName, validateDescription } from "../utils/validation";
import { useAuth } from "../custom-hook/useAuth";
import { SUPPRESS_WORKSPACE_AUTO_OPEN_KEY } from "../utils/sessionCleanup";

// ─── Payment edge-case helpers ────────────────────────────────────────────────

function safeGetStorage(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}
function safeSetStorage(key: string, value: string): void {
  try { localStorage.setItem(key, value); } catch {}
}
function safeRemoveStorage(key: string): void {
  try { localStorage.removeItem(key); } catch {}
}

async function fetchWithTimeout(url: string, options: RequestInit, timeoutMs = 15000): Promise<Response> {
  const controller = new AbortController();
  const id = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } catch (err: any) {
    if (err?.name === "AbortError") throw new Error("Request timed out. Check your internet connection and try again.");
    throw err;
  } finally {
    clearTimeout(id);
  }
}

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
  billingStatus?: string;
  billingInterval?: string;
  collaborationEnabled: boolean;
  members: WorkspaceMember[];
  createdAt: string;
  updatedAt: string;
}

const isPendingPaidWorkspace = (workspace: Workspace) => {
  const plan = (workspace.subscriptionPlan || "FREE").toUpperCase();
  if (plan === "FREE") return false;
  
  // If collaboration is enabled, it's NOT pending (it's active)
  if (workspace.collaborationEnabled) return false;

  const status = (workspace.billingStatus || "").toUpperCase();
  if (status === "PENDING" || status === "PAYMENT_FAILED") return true;
  
  // If it's a paid plan but collaboration is still off, it might need activation/payment
  return true;
};

// Model B: billing is account-level, but we still mirror the plan on the workspace for quick access.
function workspaceStatusBadge(workspace: Workspace): { label: string; cls: string } | null {
  const plan = (workspace.subscriptionPlan || "FREE").toUpperCase();
  if (plan === "ENTERPRISE") return { label: "ENTERPRISE", cls: "bg-amber-500/20 text-amber-300 border border-amber-500/30" };
  if (plan === "PRO") return { label: "PRO", cls: "bg-purple-500/20 text-purple-300 border border-purple-500/30" };
  return { label: "FREE", cls: "bg-white/5 text-gray-400 border border-white/10" };
}


// ─── Inline payment form rendered inside the create-workspace dialog ──────────
const InlinePaymentStep: React.FC<{
  planName: string;
  interval: "monthly" | "annual";
  workspaceName: string;
  workspaceId: string;
  onConfirmed: (setupIntentId: string) => void;
  onSkip: () => void;
}> = ({ planName, interval, workspaceName, workspaceId, onConfirmed, onSkip }) => {
  const stripe = useStripe();
  const elements = useElements();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { getShortPrice, trialPeriodDays } = usePlanPricing();
  const price = getShortPrice(planName, interval);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!stripe || !elements) return;
    setSubmitting(true);
    setError(null);
    // Save sub params before confirmSetup in case of a 3DS redirect (page unloads)
    safeSetStorage("pendingSubscription", JSON.stringify({ workspaceId, planName, interval }));
    const { error: confirmError, setupIntent } = await stripe.confirmSetup({
      elements,
      confirmParams: { return_url: window.location.href.split("?")[0] },
      redirect: "if_required",
    });
    if (confirmError) {
      const isExpired =
        confirmError.code === "setup_intent_unexpected_state" ||
        (confirmError.message ?? "").toLowerCase().includes("expired");
      setError(isExpired
        ? "Payment session expired. Please close and try again."
        : (confirmError.message ?? "Payment setup failed. Please try again."));
      setSubmitting(false);
      safeRemoveStorage("pendingSubscription");
      return;
    }
    if (setupIntent?.status === "succeeded") {
      // Combine all recovery data into one key so a page reload can retry /subscribe
      safeSetStorage("pendingPaymentRecovery", JSON.stringify({ setupIntentId: setupIntent.id, workspaceId, planName, interval }));
      safeRemoveStorage("pendingSubscription");
      onConfirmed(setupIntent.id);
    } else {
      setError("Card setup did not complete. Please try again.");
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="flex items-center gap-3 bg-green-500/10 border border-green-500/20 rounded-xl px-4 py-3">
        <CheckCircle size={18} className="text-green-400 flex-shrink-0" />
        <div>
          <p className="text-sm font-medium text-white">"{workspaceName}" created</p>
          <p className="text-xs text-gray-400">Now set up payment to activate your plan</p>
        </div>
      </div>
      <div className="flex items-center justify-between bg-white/5 border border-white/10 rounded-xl px-4 py-3">
        <div>
          <p className="text-[11px] uppercase tracking-widest text-gray-400 mb-0.5">Plan</p>
          <p className="text-white font-semibold">{planName.charAt(0) + planName.slice(1).toLowerCase()} · {interval}</p>
        </div>
        <div className="text-right">
          <p className="text-purple-300 font-semibold">{price}</p>
          <p className="text-[11px] text-green-400">First {trialPeriodDays} days free</p>
        </div>
      </div>
      <div>
        <p className="flex items-center gap-2 text-sm text-gray-300 mb-2">
          <CreditCard size={15} /> Card details
        </p>
        <PaymentElement options={{ layout: "tabs" }} />
      </div>
      {error && (
        <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-4 py-3 rounded-xl text-sm">{error}</div>
      )}
      <div className="flex items-center gap-2 text-[11px] text-gray-400">
        <Shield size={13} className="text-green-400 flex-shrink-0" />
        <span>Renewal reminders are sent 15, 7, and 1 day before renewal. Canceling blocks workspace access until renewed.</span>
      </div>
      <div className="hidden">
        <Shield size={13} className="text-green-400 flex-shrink-0" />
        <span>Card saved securely — not charged for {trialPeriodDays} days — cancel any time</span>
      </div>
      <div className="flex gap-3">
        <button type="button" onClick={onSkip} disabled={submitting}
          className="flex-1 py-3 rounded-xl border border-white/20 bg-white/5 text-gray-300 font-medium hover:bg-white/10 transition-all disabled:opacity-40 text-sm">
          Skip for now
        </button>
        <button type="submit" disabled={submitting || !stripe || !elements}
          className="flex-[2] py-3 rounded-xl bg-gradient-to-r from-purple-600 to-indigo-600 text-white font-semibold hover:from-purple-700 hover:to-indigo-700 transition-all disabled:opacity-50 flex items-center justify-center gap-2 text-sm shadow-lg shadow-purple-600/30">
          {submitting ? <><Loader2 size={16} className="animate-spin" />Setting up…</> : <><CheckCircle size={16} />Start {trialPeriodDays}-day free trial</>}
        </button>
      </div>
    </form>
  );
};

interface WorkspaceSelectionProps {
  username: string;
  isAdmin?: boolean;
  onWorkspaceSelected: (workspaceData: any) => void;
  onSkipWorkspace: () => void;
  onLogout: () => void;
  /**
   * Called when the user clicks the top-right "Manage Billing" pill on the
   * workspace selection screen. Bug #44: the button used to open the legacy
   * modal in-place; the host should now route to the new BillingManagement
   * page in account-level mode (synthetic "Your Account" workspace).
   * Falls back to the local modal if not provided.
   */
  onManageAccountBilling?: () => void;
}

const WorkspaceSelection: React.FC<WorkspaceSelectionProps> = ({
  username,
  isAdmin = false,
  onWorkspaceSelected,
  onSkipWorkspace,
  onLogout,
  onManageAccountBilling,
}) => {
  const { user, refreshPermissions } = useAuth();
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
  const [billingInterval, setBillingInterval] = useState<"monthly" | "annual">("monthly");
  const [showPlanSelection, setShowPlanSelection] = useState(false);
  const [showAccountPlanSelection, setShowAccountPlanSelection] = useState(false);
  const [setupClientSecret, setSetupClientSecret] = useState<string | null>(null);
  const [setupPublishableKey, setSetupPublishableKey] = useState<string>("");
  const [pendingSubParams, setPendingSubParams] = useState<{ planName: string; interval: "monthly" | "annual"; workspaceId: string } | null>(null);
  const [deletingWorkspace, setDeletingWorkspace] = useState<string | null>(null);
  const [openingPortal, setOpeningPortal] = useState<string | null>(null);
  const [managingWorkspace, setManagingWorkspace] = useState<Workspace | null>(null);
  const [createStep, setCreateStep] = useState<"details" | "payment">("details");
  const [pendingCreateWorkspaceId, setPendingCreateWorkspaceId] = useState<string | null>(null);
  const [pendingCreateWorkspaceName, setPendingCreateWorkspaceName] = useState<string>("");

  const stripePromise = useMemo(
    () => (setupPublishableKey ? loadStripe(setupPublishableKey) : null),
    [setupPublishableKey],
  );
  const inlineElementsOptions: StripeElementsOptions | undefined = setupClientSecret
    ? {
        clientSecret: setupClientSecret,
        appearance: {
          theme: "night",
          variables: {
            colorPrimary: "#8b5cf6", colorBackground: "#1e1b4b", colorText: "#e2e8f0",
            colorTextSecondary: "#94a3b8", colorDanger: "#f87171", borderRadius: "10px",
          },
          rules: {
            ".Input": { backgroundColor: "rgba(255,255,255,0.06)", border: "1px solid rgba(255,255,255,0.15)", color: "#e2e8f0", boxShadow: "none" },
            ".Input:focus": { border: "1px solid #8b5cf6", boxShadow: "0 0 0 3px rgba(139,92,246,0.25)" },
            ".Label": { color: "#94a3b8", fontWeight: "500" },
            ".Tab": { backgroundColor: "rgba(255,255,255,0.04)", border: "1px solid rgba(255,255,255,0.12)", color: "#94a3b8" },
            ".Tab--selected": { backgroundColor: "rgba(139,92,246,0.15)", border: "1px solid rgba(139,92,246,0.5)", color: "#c4b5fd" },
          },
        },
      }
    : undefined;
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [workspaceToDelete, setWorkspaceToDelete] = useState<{ id: string; name: string } | null>(null);

  // In-app confirm dialog state (replaces window.confirm)
  const [confirmDialog, setConfirmDialog] = useState<{ title: string; message: string; confirmLabel: string } | null>(
    null,
  );
  const confirmResolveRef = useRef<((value: boolean) => void) | null>(null);
  const autoRetryAttempted = useRef(false);
  const firstTimePlanShown = useRef(false);
  const suppressAutoOpenRef = useRef(safeGetStorage(SUPPRESS_WORKSPACE_AUTO_OPEN_KEY) === "true");

  // Account-level subscription state (Model B)
  const [accountSubscription, setAccountSubscription] = useState<{ planName: string; status: string; billingInterval: string } | null>(null);

  useEffect(() => {
    if (suppressAutoOpenRef.current) {
      safeRemoveStorage(SUPPRESS_WORKSPACE_AUTO_OPEN_KEY);
    }
  }, []);

  // Sync accountSubscription with user context (refreshed via JWT)
  useEffect(() => {
    if (user?.subscriptionPlan) {
      setAccountSubscription(prev => ({
        planName: user.subscriptionPlan || "FREE",
        status: (user.subscriptionPlan && user.subscriptionPlan !== "FREE") ? "active" : "active",
        billingInterval: prev?.billingInterval || "monthly"
      }));
    }
  }, [user?.subscriptionPlan]);
  const [showManageAccount, setShowManageAccount] = useState(false);

  // Report Issue modal state — available in workspace selection screen
  const [isReportIssueModalOpen, setIsReportIssueModalOpen] = useState(false);

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

  // Handle auto-starting billing checkout from ProjectDashboard redirects
  useEffect(() => {
    console.log("[WorkspaceSelection] Auto-checkout effect running. Workspaces:", workspaces.length, "Loading:", loading);
    if (workspaces.length === 0 || loading) return;

    const pendingUpgradeWorkspaceId = localStorage.getItem("pendingUpgradeWorkspaceId");
    const pendingUpgradePlan = localStorage.getItem("pendingUpgradePlan");
    const pendingUpgradeInterval = localStorage.getItem("pendingUpgradeInterval");

    console.log("[WorkspaceSelection] Pending upgrade params:", { pendingUpgradeWorkspaceId, pendingUpgradePlan, pendingUpgradeInterval });

    if (pendingUpgradeWorkspaceId && pendingUpgradePlan) {
      localStorage.removeItem("pendingUpgradeWorkspaceId");
      localStorage.removeItem("pendingUpgradePlan");
      localStorage.removeItem("pendingUpgradeInterval");

      const targetWs = workspaces.find((w) => w.workspaceId === pendingUpgradeWorkspaceId);
      console.log("[WorkspaceSelection] Target workspace found:", targetWs);
      
      if (targetWs) {
        // Assume interval from workspace if available, default to monthly
        const interval =
          pendingUpgradeInterval === "annual" || pendingUpgradeInterval === "yearly"
            ? "annual"
            : (targetWs.billingInterval === "annual" || targetWs.billingInterval === "yearly" ? "annual" : "monthly");
        
        console.log("[WorkspaceSelection] Starting billing checkout with:", { pendingUpgradeWorkspaceId, pendingUpgradePlan, interval });
        // Start billing checkout
        startBillingCheckout(pendingUpgradeWorkspaceId, pendingUpgradePlan, interval).catch((err) => {
           console.error("[WorkspaceSelection] Auto-checkout failed:", err);
           setError(err.message || "Failed to start payment setup");
        });
      } else {
        console.warn("[WorkspaceSelection] Could not find target workspace for auto-checkout:", pendingUpgradeWorkspaceId);
        // Fallback: try to start checkout anyway without workspace ID (account level)
        const interval =
          pendingUpgradeInterval === "annual" || pendingUpgradeInterval === "yearly" ? "annual" : "monthly";
        startBillingCheckout("", pendingUpgradePlan, interval).catch((err) => {
           console.error("[WorkspaceSelection] Fallback auto-checkout failed:", err);
           setError(err.message || "Failed to start payment setup");
        });
      }
    }
  }, [workspaces, loading]);

  useEffect(() => {
    apiClient.get("/api/billing/subscription")
      .then((res: any) => {
        const d = res?.data || res;
        setAccountSubscription({ planName: d.planName || "FREE", status: d.status || "", billingInterval: d.billingInterval || "monthly" });
      })
      .catch(() => {
        // Failed to fetch — treat as FREE/no subscription
        setAccountSubscription({ planName: "FREE", status: "", billingInterval: "monthly" });
      });
  }, []);

  // First-time user: show plan selection automatically when login reveals no workspaces
  useEffect(() => {
    if (suppressAutoOpenRef.current) return;
    if (firstTimePlanShown.current) return;
    if (loading) return;
    if (accountSubscription === null) return;
    if (workspaces.length > 0) return;
    const hasActivePlan = accountSubscription.status === "active" || accountSubscription.status === "trialing";
    if (!hasActivePlan) {
      firstTimePlanShown.current = true;
      setShowAccountPlanSelection(true);
    }
  }, [loading, workspaces, accountSubscription]);

  // Recover from a 3DS redirect: URL contains ?setup_intent=si_xxx&redirect_status=...
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const setupIntentId = params.get("setup_intent");
    const redirectStatus = params.get("redirect_status");
    if (!setupIntentId) return;

    // Always clear URL so a page reload doesn't re-trigger
    window.history.replaceState({}, "", window.location.pathname);

    if (redirectStatus !== "succeeded") {
      safeRemoveStorage("pendingSubscription");
      safeRemoveStorage("pendingPaymentRecovery");
      const msg =
        redirectStatus === "failed"
          ? "Card authentication failed. Please try again."
          : redirectStatus === "canceled"
          ? "Payment was canceled. Click the workspace again to retry."
          : "Payment did not complete. Please try again.";
      setError(msg);
      return;
    }

    const stored = safeGetStorage("pendingSubscription");
    safeRemoveStorage("pendingSubscription");
    if (stored) {
      try {
        const { planName, interval, workspaceId } = JSON.parse(stored);
        // Combine all recovery data so a subsequent network cut can still recover on reload
        safeSetStorage("pendingPaymentRecovery", JSON.stringify({ setupIntentId, workspaceId, planName, interval }));
        handlePaymentConfirmed(setupIntentId, planName, interval, workspaceId);
      } catch {
        setError("Failed to resume subscription after authentication. Please try again.");
      }
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Auto-retry: if a previous session stored pendingPaymentRecovery but /subscribe never completed
  useEffect(() => {
    if (loading || autoRetryAttempted.current) return;
    const recoveryRaw = safeGetStorage("pendingPaymentRecovery");
    if (!recoveryRaw) return;

    autoRetryAttempted.current = true;
    try {
      const { setupIntentId, workspaceId, planName, interval } = JSON.parse(recoveryRaw);
      const ws = workspaces.find((w) => w.workspaceId === workspaceId);
      if (ws && (!ws.billingStatus || ws.billingStatus === "PENDING" || ws.billingStatus === "PAYMENT_FAILED")) {
        safeRemoveStorage("pendingPaymentRecovery");
        handlePaymentConfirmed(setupIntentId, planName, interval, workspaceId);
      } else {
        // Already active or workspace gone — clear stale data
        safeRemoveStorage("pendingPaymentRecovery");
      }
    } catch {
      safeRemoveStorage("pendingPaymentRecovery");
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading, workspaces]);

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

      const workspace = workspaces.find((item) => item.workspaceId === workspaceId);
      if (workspace && isPendingPaidWorkspace(workspace)) {
        const interval =
          workspace.billingInterval === "annual" ||
          workspace.billingInterval === "yearly" ||
          workspace.billingInterval === "year"
            ? "annual"
            : "monthly";
        await startBillingCheckout(workspaceId, workspace.subscriptionPlan, interval);
        return;
      }

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

  const handlePlanSelected = (planId: string, interval: "monthly" | "annual") => {
    setSelectedPlan(planId);
    setBillingInterval(interval);
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

  const startBillingCheckout = async (workspaceId: string, planName: string, interval: "monthly" | "annual" = "monthly") => {
    try {
      const response = await apiClient.post("/api/billing/setup", {});
      const data = response?.data || response;

      if (!data?.clientSecret || !data?.stripePublishableKey) {
        throw new Error("Missing payment configuration from server");
      }

      setPendingSubParams({ planName, interval, workspaceId });
      setSetupPublishableKey(data.stripePublishableKey);
      setSetupClientSecret(data.clientSecret);
    } catch (err: any) {
      if (err?.status === 401 || err?.status === 403 || err?.response?.status === 401 || err?.response?.status === 403) {
        setError("Your session has expired. Please sign in again.");
        onLogout();
        return;
      }
      
      const errMsg = (err?.response?.data?.error || err?.data?.error || err?.message || "").toLowerCase();
      if (errMsg.includes("already") && (errMsg.includes("active") || errMsg.includes("subscription"))) {
        // Already active — just refresh to show current status
        await loadWorkspaces();
        return;
      }
      
      throw new Error(err?.response?.data?.error || err?.data?.error || err?.message || "Failed to create payment setup");
    }
  };

  const handlePaymentConfirmed = async (
    setupIntentId: string,
    planName?: string,
    interval?: string,
    workspaceId?: string,
  ) => {
    const resolvedPlan = planName ?? pendingSubParams?.planName ?? "";
    const resolvedInterval = interval ?? pendingSubParams?.interval ?? "monthly";
    const resolvedWorkspace = workspaceId ?? pendingSubParams?.workspaceId ?? "";

    try {
      const payload = {
        setupIntentId,
        planName: resolvedPlan,
        interval: resolvedInterval,
        workspaceId: resolvedWorkspace,
      };

      const response = await apiClient.post("/api/billing/subscribe", payload);
      const result = response?.data || response;

      safeRemoveStorage("pendingPaymentRecovery");
      setSetupClientSecret(null);
      setPendingSubParams(null);

      // Force refresh JWT token if needed, then reload workspaces to show active status
      refreshPermissions().catch(() => {});
      await loadWorkspaces();

      // If we just subscribed a new workspace, automatically select it
      if (resolvedWorkspace) {
        await handleSelectWorkspace(resolvedWorkspace);
      }
    } catch (err: any) {
      if (err?.status === 401 || err?.status === 403 || err?.response?.status === 401 || err?.response?.status === 403) {
        setError("Your session has expired. Please sign in again.");
        onLogout();
        return;
      }

      const errMsg = (err?.response?.data?.error || err?.data?.error || err?.message || "").toLowerCase();
      // Duplicate tab / retry race — already subscribed, treat as success
      if (errMsg.includes("already") && (errMsg.includes("active") || errMsg.includes("subscription"))) {
        safeRemoveStorage("pendingPaymentRecovery");
        setSetupClientSecret(null);
        setPendingSubParams(null);
        refreshPermissions().catch(() => {});
        await loadWorkspaces();
        if (resolvedWorkspace) {
          await handleSelectWorkspace(resolvedWorkspace);
        }
        return;
      }
      
      // Network/timeout — setupIntentId still in localStorage, reload will auto-retry
      if (err?.code === "TIMEOUT" || err?.message?.toLowerCase().includes("network")) {
        setError("Network error. Your card was saved — reload the page to complete activation.");
        return;
      }
      
      setError(err?.response?.data?.error || err?.data?.error || err?.message || "Failed to activate subscription. Please contact support.");
    }
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
      });
      console.log("[WorkspaceSelection] Create workspace response:", response);

      // Handle both direct response (VS Code proxy) and response.data (axios browser) formats
      const data = response?.data || response;
      if (data?.workspace) {
        const createdWorkspace = data.workspace;

        setShowCreateDialog(false);
        setNewWorkspaceName("");
        setNewWorkspaceDescription("");

        await loadWorkspaces();
        await handleSelectWorkspace(createdWorkspace.workspaceId);
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
      const errorMsg = err.response?.data?.error || err.message || "Failed to delete workspace";
      const requiresAction = err.response?.data?.requiresAction;
      const actions = err.response?.data?.actions;
      
      // Check if this is a billing-related deletion error
      if (err.response?.status === 402) {
        setError(`❌ ${errorMsg}\n\n📋 ${requiresAction}`);
      } else {
        setError(errorMsg);
      }
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
    const member = workspace.members.find((m) => m.userId === user?.userId || m.email === user?.email || m.username === user?.username || m.username === username);
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
      <div className="min-h-screen overflow-y-auto bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 flex items-center justify-center p-4">
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
    <div className="dark-surface min-h-[100dvh] overflow-y-auto bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 flex flex-col items-center justify-start py-12 px-4">

      {/* Account-level Manage Billing Modal (for existing PRO/ENTERPRISE subscribers) */}
      {showManageAccount && accountSubscription && (
        <ManageSubscriptionModal
          workspace={{
            workspaceId: "",
            name: "Your Account",
            subscriptionPlan: accountSubscription.planName,
            billingStatus: accountSubscription.status?.toUpperCase(),
            billingInterval: accountSubscription.billingInterval,
          }}
          onClose={() => setShowManageAccount(false)}
          onCancelled={() => {
            setShowManageAccount(false);
            setAccountSubscription({
              planName: accountSubscription?.planName || "FREE",
              status: "canceled",
              billingInterval: accountSubscription?.billingInterval || "monthly",
            });
            loadWorkspaces();
          }}
          onUpgradePlan={() => {
            setShowManageAccount(false);
            setShowAccountPlanSelection(true);
          }}
        />
      )}

      {/* Account-level Plan Upgrade / First-time Setup Screen */}
      {showAccountPlanSelection && (
        <div className="fixed inset-0 z-50">
          <SubscriptionPlanSelection
            username={username}
            workspaceId=""
            workspaceName="Your Account"
            currentPlanId={accountSubscription?.planName || "FREE"}
            currentStatus={accountSubscription?.status || ""}
            allowCurrentPlanSelection={
              !!accountSubscription?.planName &&
              accountSubscription.planName !== "FREE" &&
              accountSubscription.status !== "active" &&
              accountSubscription.status !== "trialing"
            }
            onPlanSelected={(planId, interval) => {
              setShowAccountPlanSelection(false);
              if (planId === "FREE") {
                // FREE plan — no payment needed, go straight to workspace creation
                setAccountSubscription({ planName: "FREE", status: "active", billingInterval: interval });
                setShowCreateDialog(true);
              } else {
                startBillingCheckout("", planId, interval).catch((err) =>
                  setError(err.message || "Failed to start payment setup"),
                );
              }
            }}
            onSkip={() => {
              setShowAccountPlanSelection(false);
              setShowCreateDialog(true);
            }}
            onLogout={onLogout}
          />
        </div>
      )}

      {/* Manage Subscription Modal */}
      {managingWorkspace && (
        <ManageSubscriptionModal
          workspace={managingWorkspace}
          onClose={() => setManagingWorkspace(null)}
          onCancelled={() => {
            setManagingWorkspace(null);
            loadWorkspaces();
          }}
          onCompletePayment={() => {
            const ws = managingWorkspace;
            setManagingWorkspace(null);
            const interval: "monthly" | "annual" =
              ws.billingInterval === "annual" || ws.billingInterval === "yearly" ? "annual" : "monthly";
            startBillingCheckout(ws.workspaceId, ws.subscriptionPlan, interval).catch((err) =>
              setError(err.message || "Failed to start payment setup"),
            );
          }}
          onUpgradePlan={() => {
            setManagingWorkspace(null);
            setShowAccountPlanSelection(true);
          }}
        />
      )}

      {/* Native Stripe Payment Modal — only for retry flow (not inline create) */}
      {setupClientSecret && setupPublishableKey && pendingSubParams && !showCreateDialog && (
        <PaymentSetupModal
          publishableKey={setupPublishableKey}
          clientSecret={setupClientSecret}
          planName={pendingSubParams.planName}
          interval={pendingSubParams.interval}
          workspaceId={pendingSubParams.workspaceId}
          onConfirmed={(setupIntentId) => handlePaymentConfirmed(setupIntentId)}
          onClose={() => {
            setSetupClientSecret(null);
            setPendingSubParams(null);
            loadWorkspaces();
          }}
        />
      )}

      <div className="absolute inset-0 overflow-hidden">
        <div className="absolute -top-40 -right-40 w-80 h-80 bg-purple-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse"></div>
        <div className="absolute -bottom-40 -left-40 w-80 h-80 bg-indigo-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse delay-1000"></div>
      </div>

      <div className="relative bg-white/10 backdrop-blur-xl border border-white/20 rounded-2xl shadow-2xl p-8 w-full max-w-4xl flex flex-col my-auto">
        <div className="absolute top-4 right-4 flex items-center gap-2">
          {accountSubscription && (accountSubscription.status === "active" || accountSubscription.status === "trialing") && accountSubscription.planName !== "FREE" ? (
            <button
              type="button"
              // Bug #44: route to the new full-page BillingManagement view in
              // account-level mode when the host provided a navigator;
              // otherwise fall back to the legacy in-place modal.
              onClick={() => {
                if (onManageAccountBilling) {
                  onManageAccountBilling();
                } else {
                  setShowManageAccount(true);
                }
              }}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 border border-white/20 text-xs font-medium text-white transition-colors backdrop-blur-sm"
              title={`${accountSubscription.planName} plan — manage billing`}
            >
              <CreditCard size={14} />
              Manage Billing
            </button>
          ) : (
            <button
              type="button"
              onClick={() => setShowAccountPlanSelection(true)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-purple-600/80 hover:bg-purple-600 border border-purple-500/50 text-xs font-medium text-white transition-colors backdrop-blur-sm"
              title={accountSubscription?.planName && accountSubscription.planName !== "FREE" ? "Renew or upgrade your account plan" : "Upgrade your account to PRO or ENTERPRISE"}
            >
              <CreditCard size={14} />
              {accountSubscription?.planName && accountSubscription.planName !== "FREE" ? "Renew Plan" : "Upgrade Plan"}
            </button>
          )}
          <button
            type="button"
            onClick={() => setIsReportIssueModalOpen(true)}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 border border-white/20 text-xs font-medium text-white transition-colors backdrop-blur-sm"
            title="Report an issue or request a feature"
          >
            <Bug size={14} />
            Report Issue
          </button>
        </div>

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

        <div className="space-y-4 mb-6">
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
                          <span className={`text-[10px] uppercase tracking-wider px-2 py-0.5 rounded-full font-bold ${workspaceStatusBadge(workspace)?.cls}`}>
                            {workspaceStatusBadge(workspace)?.label}
                          </span>
                        </div>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      {(workspace.members.find(m => m.email === user?.email || (m.userId === user?.userId && m.email === user?.email))?.role?.toUpperCase() === "OWNER") && (
                        <button
                          onClick={(e) => confirmDelete(workspace, e)}
                          className="p-2 text-gray-400 hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors"
                          title="Delete workspace"
                        >
                          <Trash size={18} />
                        </button>
                      )}
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
        {/* <button
          onClick={onSkipWorkspace}
          className="w-full py-3 bg-transparent border-2 border-white/20 text-gray-300 font-medium rounded-lg hover:bg-white/5 hover:border-purple-400/50 hover:text-white transition-all flex items-center justify-center space-x-2 mt-3"
        >
          <span>Continue without workspace</span>
        </button> */}

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
            <h3 className="text-2xl font-bold text-gray-200 mb-6 text-center">
              Create New Workspace
            </h3>

            <>
            {createDialogError && (
              <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-4 py-3 rounded-lg mb-4 text-sm backdrop-blur-sm">
                <p>{createDialogError}</p>
                {createDialogError.includes("Workspace limit reached") && (
                  <button
                    type="button"
                    onClick={() => { setShowCreateDialog(false); setShowAccountPlanSelection(true); }}
                    className="mt-2 inline-flex items-center gap-1.5 px-3 py-1.5 bg-purple-600 hover:bg-purple-700 text-white text-xs font-medium rounded-lg transition-colors"
                  >
                    <CreditCard size={13} />
                    Upgrade Account
                  </button>
                )}
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
              <div className="flex space-x-3 pt-4">
                <button
                  type="button"
                  onClick={() => {
                    setShowCreateDialog(false);
                    setNewWorkspaceName("");
                    setNewWorkspaceDescription("");
                    setSelectedPlan("FREE");
                    setBillingInterval("monthly");
                    setCreateDialogError("");
                    setCreateStep("details");
                    setPendingCreateWorkspaceId(null);
                    setPendingCreateWorkspaceName("");
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
            </>
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

      {/* Report Issue Modal — available from workspace selection */}
      {isReportIssueModalOpen && (
        <ReportIssueModal onClose={() => setIsReportIssueModalOpen(false)} />
      )}
    </div>
  );
};

export default WorkspaceSelection;

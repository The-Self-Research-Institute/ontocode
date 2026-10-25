import React, { useState, useEffect, useCallback, useRef } from "react";
import { X, GitPullRequest, Check, XCircle, RefreshCw, ChevronDown, ChevronUp, ChevronRight, AlertTriangle } from "lucide-react";
import apiClient from "../services/apiClient";
import { failureOf } from "../services/prRevertService";
import RevertPrDialog from "./RevertPrDialog";
import {
  DraftChange,
  CATEGORY_ORDER,
  getEntityName,
  getEntityIri,
  extractLocalName,
  getActionMeta,
  getOpLabel,
  groupByCategory,
  describeChange,
} from "../utils/draftChangeHelpers";
import { DraftFullChanges, DraftMergeRow, FullDiff, Resolution, conflictKeys, normalizeFullDiff } from "./DraftPRDetails";

interface DraftPR {
  id: string;
  projectId: string;
  authorId: string;
  authorUsername: string;
  title: string;
  description?: string;
  status: "OPEN" | "APPROVED" | "REJECTED" | "REVERTED";
  changeCount: number;
  createdAt: string;
  reviewedAt?: string;
  reviewerId?: string;
  reviewNote?: string;
  revertReason?: string;
  restoredAt?: string;
  submittedChangeIds?: string[];
  snapshotKey?: string;
  draftChanged?: boolean;
  needsCopy?: boolean;
  currentChangeCount?: number;
}

interface DraftPRPanelProps {
  isOpen: boolean;
  onClose: () => void;
  projectId: string;
  userId: string;
  username: string;
  /** true if the current user can approve/reject PRs (OWNER, ADMIN, EDITOR) */
  canReview: boolean;
  /** true if the current user is in draft mode and can raise a PR */
  canRaisePR: boolean;
  /** current draft change count for the user */
  draftCount: number;
  onPRApproved?: () => void;
}

export const DraftPRPanel: React.FC<DraftPRPanelProps> = ({
  isOpen,
  onClose,
  projectId,
  userId,
  username,
  canReview,
  canRaisePR,
  draftCount,
  onPRApproved,
}) => {
  const [prs, setPRs] = useState<DraftPR[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<"open" | "closed">("open");
  const [liveDraftCount, setLiveDraftCount] = useState(draftCount);

  // Raise PR form
  const [showRaiseForm, setShowRaiseForm] = useState(false);
  const [prTitle, setPrTitle] = useState("");
  const [prDescription, setPrDescription] = useState("");
  const [raising, setRaising] = useState(false);
  const [raiseError, setRaiseError] = useState<string | null>(null);

  // Review
  const [reviewingPrId, setReviewingPrId] = useState<string | null>(null);
  const [reviewNote, setReviewNote] = useState("");
  const [reviewing, setReviewing] = useState(false);
  const [expandedPrId, setExpandedPrId] = useState<string | null>(null);

  // Per-PR draft changes and conflict analysis
  const [prChangesMap, setPrChangesMap] = useState<Record<string, DraftChange[]>>({});
  const [prConflictMap, setPrConflictMap] = useState<Record<string, {
    conflictType: string;
    mainChangedSinceDraft: boolean;
    conflicts: Array<{ entityIRI: string; entityLabel: string; changedBy: string }>;
  }>>({});
  const [prExpandedCategories, setPrExpandedCategories] = useState<Record<string, Set<string>>>({});
  const [prFullDiffMap, setPrFullDiffMap] = useState<Record<string, FullDiff>>({});
  const [prTabMap, setPrTabMap] = useState<Record<string, "notes" | "full">>({});
  const [prResolutions, setPrResolutions] = useState<Record<string, Record<string, Resolution>>>({});
  const [prConflictNotice, setPrConflictNotice] = useState<Record<string, string>>({});
  const [expandedMerges, setExpandedMerges] = useState<Set<string>>(new Set());
  const fetchedConflictsRef = useRef<Set<string>>(new Set());

  const fetchPRs = useCallback(async (): Promise<DraftPR[] | undefined> => {
    if (!projectId) return undefined;
    setLoading(true);
    setError(null);
    try {
      const res = await apiClient.get<any>(`/api/ontology/${projectId}/draft-prs`);
      const data = res?.data || res;
      setPRs(data.prs || []);
      return data.prs || [];
    } catch (e: any) {
      setError("Failed to load pull requests");
      return undefined;
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    setLiveDraftCount(draftCount);
  }, [draftCount]);

  const refreshDraftCount = useCallback(() => {
    apiClient.get<any>(`/api/ontology/${projectId}/drafts/stats`, { userId })
      .then((res: any) => {
        const data = res?.data || res;
        const count = data?.unappliedDrafts ?? data?.totalDrafts;
        if (typeof count === 'number') setLiveDraftCount(count);
      })
      .catch(() => {});
  }, [projectId, userId]);

  const loadPRDetails = async (pr: DraftPR) => {
    if (fetchedConflictsRef.current.has(pr.id)) return;
    fetchedConflictsRef.current.add(pr.id);
    apiClient.get<any>(`/api/ontology/${projectId}/drafts/full-diff`, { userId: pr.snapshotKey || pr.authorId })
      .then((res: any) => {
        const diff = normalizeFullDiff(res?.data || res);
        setPrFullDiffMap((prev) => (prev[pr.id] ? prev : { ...prev, [pr.id]: diff }));
      })
      .catch(() => {
        setPrFullDiffMap((prev) => (prev[pr.id] ? prev : { ...prev, [pr.id]: normalizeFullDiff({ available: false }) }));
      });
    try {
      const [changesRes, previewRes] = await Promise.all([
        apiClient.get<any>(`/api/ontology/${projectId}/draft-prs/${pr.id}/changes`),
        apiClient.get<any>(`/api/ontology/${projectId}/drafts/publish-preview`, { userId: pr.snapshotKey || pr.authorId }),
      ]);
      const changesData = changesRes?.data || changesRes;
      const raised: DraftChange[] = changesData.drafts || [];
      setPrChangesMap((prev) => ({ ...prev, [pr.id]: raised }));
      const preview = previewRes?.data || previewRes;
      if (preview) {
        setPrConflictMap((prev) => ({
          ...prev,
          [pr.id]: {
            conflictType: preview.conflictType || "NONE",
            mainChangedSinceDraft: !!preview.mainChangedSinceDraft,
            conflicts: preview.conflicts || [],
          },
        }));
      }
    } catch {
      fetchedConflictsRef.current.delete(pr.id);
    }
  };

  useEffect(() => {
    if (isOpen) {
      fetchPRs();
      setShowRaiseForm(false);
      setRaiseError(null);
      fetchedConflictsRef.current = new Set();
      setPrConflictMap({});
      setPrChangesMap({});
      setPrExpandedCategories({});
      setPrFullDiffMap({});
      setPrTabMap({});
      setPrResolutions({});
      setPrConflictNotice({});
      setExpandedMerges(new Set());
      if (canRaisePR) {
        refreshDraftCount();
      }
    }
  }, [isOpen, fetchPRs, canRaisePR, refreshDraftCount]);

  const handleRaisePR = async () => {
    setRaising(true);
    setRaiseError(null);
    try {
      await apiClient.post(`/api/ontology/${projectId}/draft-prs`, {
        userId,
        username,
        title: prTitle.trim() || undefined,
        description: prDescription.trim() || undefined,
      });
      setPrTitle("");
      setPrDescription("");
      setShowRaiseForm(false);
      await fetchPRs();
    } catch (e: any) {
      const msg = e?.response?.data?.error || e?.message || "Failed to raise pull request";
      setRaiseError(msg);
    } finally {
      setRaising(false);
    }
  };

  const handleApprove = async (pr: DraftPR) => {
    setReviewing(true);
    try {
      await apiClient.post(`/api/ontology/${projectId}/draft-prs/${pr.id}/approve`, {
        reviewerId: userId,
        reviewNote: reviewNote.trim() || undefined,
        resolutions: prResolutions[pr.id] || {},
      });
      setPrConflictNotice((prev) => ({ ...prev, [pr.id]: "" }));
      setReviewingPrId(null);
      setReviewNote("");
      await fetchPRs();
      refreshDraftCount();
      window.dispatchEvent(new CustomEvent("ontology:mutated", { detail: { projectId, ops: ["draftPrApproved"] } }));
      onPRApproved?.();
    } catch (e: any) {
      if (e?.status === 409 && e?.data?.conflicts) {
        setPrFullDiffMap((prev) => ({ ...prev, [pr.id]: normalizeFullDiff(e.data.conflicts) }));
        setPrTabMap((prev) => ({ ...prev, [pr.id]: "full" }));
        setPrConflictNotice((prev) => ({
          ...prev,
          [pr.id]: "This draft conflicts with Public. Choose Keep draft or Take Public's for each highlighted row, then approve again.",
        }));
        return;
      }
      const msg = e?.data?.error || e?.response?.data?.error || e?.message || "Failed to approve pull request";
      setError(msg);
    } finally {
      setReviewing(false);
    }
  };

  const handleUpdatePR = async (pr: DraftPR) => {
    setReviewing(true);
    try {
      await apiClient.post(`/api/ontology/${projectId}/draft-prs/${pr.id}/update`, { userId });
      fetchedConflictsRef.current.delete(pr.id);
      setPrChangesMap((prev) => {
        const next = { ...prev };
        delete next[pr.id];
        return next;
      });
      setPrFullDiffMap((prev) => {
        const next = { ...prev };
        delete next[pr.id];
        return next;
      });
      const fresh = await fetchPRs();
      const updated = fresh?.find((candidate) => candidate.id === pr.id);
      if (updated) {
        void loadPRDetails(updated);
      }
      refreshDraftCount();
    } catch (e: any) {
      const msg = e?.data?.error || e?.response?.data?.error || e?.message || "Failed to update pull request";
      setError(msg);
    } finally {
      setReviewing(false);
    }
  };

  const [revertNotice, setRevertNotice] = useState<Record<string, string>>({});
  const [revertingPrId, setRevertingPrId] = useState<string | null>(null);

  const handleRestore = async (pr: DraftPR) => {
    setReviewing(true);
    try {
      const res = await apiClient.post<any>(`/api/ontology/${projectId}/draft-prs/${pr.id}/restore`, { userId, username: userId });
      setRevertNotice((prev) => ({ ...prev, [pr.id]: res?.message || "Restored into your draft" }));
      refreshDraftCount();
      window.dispatchEvent(new CustomEvent("ontology:mutated", { detail: { projectId, ops: ["draftPrRestored"] } }));
    } catch (e: any) {
      setRevertNotice((prev) => ({ ...prev, [pr.id]: failureOf(e).message }));
    } finally {
      setReviewing(false);
    }
  };

  const handleReject = async (pr: DraftPR) => {
    setReviewing(true);
    try {
      await apiClient.post(`/api/ontology/${projectId}/draft-prs/${pr.id}/reject`, {
        reviewerId: userId,
        reviewNote: reviewNote.trim() || undefined,
      });
      setReviewingPrId(null);
      setReviewNote("");
      await fetchPRs();
    } catch (e: any) {
      const msg = e?.response?.data?.error || e?.message || "Failed to reject pull request";
      setError(msg);
    } finally {
      setReviewing(false);
    }
  };

  const openPRs = prs.filter((p) => p.status === "OPEN");
  const closedPRs = prs.filter((p) => p.status !== "OPEN");
  const displayedPRs = activeTab === "open" ? openPRs : closedPRs;

  const myOpenPR = prs.find((p) => p.status === "OPEN" && p.authorId === userId);

  const formatDate = (raw: string | number | null | undefined) => {
    if (!raw) return "—";
    try {
      const asNum = Number(raw);
      if (!isNaN(asNum) && asNum > 0) {
        // epoch seconds (< 1e11) → convert to ms for JS Date
        const ms = asNum < 1e11 ? asNum * 1000 : asNum;
        return new Date(ms).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
      }
      return new Date(raw as string).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
    } catch {
      return String(raw);
    }
  };

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) onClose();
      }}
    >
      <div
        className="relative w-full max-w-xl rounded-lg shadow-xl border"
        style={{
          backgroundColor: "var(--color-background)",
          borderColor: "var(--color-border)",
          color: "var(--color-text)",
          maxHeight: "85vh",
          display: "flex",
          flexDirection: "column",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div
          className="flex items-center justify-between px-4 py-3 border-b flex-shrink-0"
          style={{ borderColor: "var(--color-border)" }}
        >
          <div className="flex items-center gap-2 font-semibold text-sm">
            <GitPullRequest size={16} />
            Pull Requests
            {openPRs.length > 0 && (
              <span className="px-1.5 py-0.5 text-[10px] rounded-full bg-blue-100 text-blue-700 font-bold">
                {openPRs.length}
              </span>
            )}
          </div>
          <button onClick={onClose} className="opacity-60 hover:opacity-100 transition-opacity">
            <X size={16} />
          </button>
        </div>

        {/* Raise PR section */}
        {canRaisePR && (
          <div className="px-4 py-3 border-b flex-shrink-0" style={{ borderColor: "var(--color-border)" }}>
            {myOpenPR ? (
              <div className="text-xs rounded px-3 py-2 bg-amber-50 border border-amber-200 text-amber-700">
                You have an open pull request: <strong>{myOpenPR.title}</strong>. Wait for it to be reviewed before raising another.
                {(myOpenPR.draftChanged || myOpenPR.needsCopy) && (
                  <div className="mt-2 flex flex-wrap items-center gap-2">
                    <span>
                      {myOpenPR.snapshotKey
                        ? `Your draft has changes that are not in it (${myOpenPR.changeCount} in the PR, ${myOpenPR.currentChangeCount ?? "more"} in your draft now).`
                        : "This PR has no saved copy yet, so approving it would publish your whole draft as it is then."}
                    </span>
                    <button
                      onClick={() => handleUpdatePR(myOpenPR)}
                      disabled={reviewing}
                      className="text-xs px-3 py-1 rounded border border-amber-400 font-medium bg-white text-amber-800 hover:bg-amber-100 disabled:opacity-60"
                    >
                      Update this PR to my current draft
                    </button>
                  </div>
                )}
              </div>
            ) : !showRaiseForm ? (
              <button
                onClick={() => setShowRaiseForm(true)}
                disabled={liveDraftCount === 0}
                className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded border font-medium transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                style={{
                  borderColor: "var(--color-border)",
                  backgroundColor: "var(--color-background-secondary, var(--color-background))",
                }}
                title={liveDraftCount === 0 ? "Make draft changes before raising a PR" : `Raise a PR with ${liveDraftCount} draft change${liveDraftCount !== 1 ? "s" : ""}`}
              >
                <GitPullRequest size={13} />
                Raise Pull Request
                {liveDraftCount > 0 && (
                  <span className="ml-1 px-1.5 py-0.5 rounded-full bg-blue-100 text-blue-700 text-[10px] font-bold">
                    {liveDraftCount}
                  </span>
                )}
              </button>
            ) : (
              <div className="flex flex-col gap-2">
                <input
                  className="w-full text-xs px-2 py-1.5 rounded border outline-none"
                  style={{ borderColor: "var(--color-border)", backgroundColor: "var(--color-input, var(--color-background))", color: "var(--color-text)" }}
                  placeholder={`Title (default: Draft changes by ${username})`}
                  value={prTitle}
                  onChange={(e) => setPrTitle(e.target.value)}
                  maxLength={200}
                />
                <textarea
                  className="w-full text-xs px-2 py-1.5 rounded border outline-none resize-none"
                  style={{ borderColor: "var(--color-border)", backgroundColor: "var(--color-input, var(--color-background))", color: "var(--color-text)" }}
                  placeholder="Description (optional)"
                  value={prDescription}
                  onChange={(e) => setPrDescription(e.target.value)}
                  rows={3}
                  maxLength={2000}
                />
                {raiseError && <div className="text-xs text-red-500">{raiseError}</div>}
                <div className="flex gap-2">
                  <button
                    onClick={handleRaisePR}
                    disabled={raising}
                    className="flex items-center gap-1 text-xs px-3 py-1.5 rounded font-medium bg-blue-600 text-white hover:bg-blue-700 transition-colors disabled:opacity-60"
                  >
                    {raising ? <RefreshCw size={12} className="animate-spin" /> : <GitPullRequest size={12} />}
                    {raising ? "Submitting…" : `Submit PR (${liveDraftCount} change${liveDraftCount !== 1 ? "s" : ""})`}
                  </button>
                  <button
                    onClick={() => { setShowRaiseForm(false); setRaiseError(null); setPrTitle(""); setPrDescription(""); }}
                    className="text-xs px-3 py-1.5 rounded border transition-colors"
                    style={{ borderColor: "var(--color-border)" }}
                  >
                    Cancel
                  </button>
                </div>
              </div>
            )}
          </div>
        )}

        {/* Tabs */}
        <div className="flex border-b flex-shrink-0 px-4" style={{ borderColor: "var(--color-border)" }}>
          {(["open", "closed"] as const).map((tab) => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              className={`text-xs px-3 py-2 border-b-2 transition-colors font-medium ${
                activeTab === tab
                  ? "border-blue-500 text-blue-600"
                  : "border-transparent opacity-60 hover:opacity-100"
              }`}
            >
              {tab === "open" ? `Open (${openPRs.length})` : `Closed (${closedPRs.length})`}
            </button>
          ))}
          <button onClick={fetchPRs} className="ml-auto self-center opacity-50 hover:opacity-100 transition-opacity" title="Refresh">
            <RefreshCw size={12} className={loading ? "animate-spin" : ""} />
          </button>
        </div>

        {/* PR list */}
        <div className="flex-1 overflow-y-auto px-4 py-3 flex flex-col gap-3">
          {error && <div className="text-xs text-red-500 mb-1">{error}</div>}
          {loading && <div className="text-xs opacity-60 text-center py-4">Loading…</div>}
          {!loading && displayedPRs.length === 0 && (
            <div className="text-xs opacity-50 text-center py-8">
              {activeTab === "open" ? "No open pull requests" : "No closed pull requests"}
            </div>
          )}
          {displayedPRs.map((pr) => (
            <div
              key={pr.id}
              className="rounded border text-xs"
              style={{ borderColor: "var(--color-border)", backgroundColor: "var(--color-background-secondary, transparent)" }}
            >
              {/* PR header row */}
              <div
                className="flex items-start gap-2 px-3 py-2.5 cursor-pointer"
                onClick={() => {
                  const newId = expandedPrId === pr.id ? null : pr.id;
                  setExpandedPrId(newId);
                  if (newId && pr.status === "OPEN" && canReview) loadPRDetails(pr);
                }}
              >
                <GitPullRequest
                  size={14}
                  className={`mt-0.5 flex-shrink-0 ${pr.status === "OPEN" ? "text-blue-500" : pr.status === "APPROVED" ? "text-green-500" : pr.status === "REVERTED" ? "text-amber-500" : "text-red-400"}`}
                />
                <div className="flex-1 min-w-0">
                  <div className="font-medium truncate">{pr.title}</div>
                  <div className="opacity-60 mt-0.5">
                    {pr.authorUsername} · {formatDate(pr.createdAt)} · {pr.changeCount} change{pr.changeCount !== 1 ? "s" : ""}
                    {pr.status !== "OPEN" && pr.reviewedAt && (
                      <> · {pr.status === "APPROVED" ? "Approved" : pr.status === "REVERTED" ? "Reverted" : "Rejected"} {formatDate(pr.reviewedAt)}</>
                    )}
                  </div>
                </div>
                <div className="flex items-center gap-2 flex-shrink-0">
                  {pr.status === "OPEN" && prConflictMap[pr.id]?.conflictType === "IRI_OVERLAP" && (
                    <span title="Merge conflicts detected" className="inline-flex"><AlertTriangle size={12} className="text-amber-500" /></span>
                  )}
                  <span className={`px-1.5 py-0.5 rounded text-[10px] font-semibold ${
                    pr.status === "OPEN" ? "bg-blue-100 text-blue-700"
                    : pr.status === "APPROVED" ? "bg-green-100 text-green-700"
                    : pr.status === "REVERTED" ? "bg-amber-100 text-amber-700"
                    : "bg-red-100 text-red-600"
                  }`}>
                    {pr.status}
                  </span>
                  {expandedPrId === pr.id ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
                </div>
              </div>

              {/* Expanded details */}
              {expandedPrId === pr.id && (
                <div className="px-3 pb-3 border-t" style={{ borderColor: "var(--color-border)" }}>
                  {pr.description && (
                    <div className="mt-2 opacity-80 whitespace-pre-wrap text-xs">{pr.description}</div>
                  )}
                  {pr.reviewNote && (
                    <div className="mt-2 italic opacity-70 text-xs">Review note: {pr.reviewNote}</div>
                  )}
                  {pr.status === "REVERTED" && pr.revertReason && (
                    <div className="mt-2 italic opacity-70 text-xs">Reverted because: {pr.revertReason}</div>
                  )}
                  {!pr.description && !pr.reviewNote && !canReview && pr.status === "OPEN" && (
                    <div className="mt-2 text-xs opacity-50 italic">Awaiting review</div>
                  )}
                  {pr.status === "APPROVED" && canReview && (
                    <div className="mt-2">
                      <button
                        onClick={() => setRevertingPrId(pr.id)}
                        disabled={reviewing}
                        className="text-xs px-3 py-1.5 rounded border font-medium transition-colors disabled:opacity-60"
                        style={{ borderColor: "var(--color-border)", color: "var(--color-text)" }}
                      >
                        Revert this pull request...
                      </button>
                      {revertingPrId === pr.id && (
                        <RevertPrDialog
                          projectId={projectId}
                          prId={pr.id}
                          title={pr.title}
                          onClose={() => setRevertingPrId(null)}
                          onDone={() => {
                            setRevertingPrId(null);
                            setRevertNotice((prev) => ({ ...prev, [pr.id]: "Reverted" }));
                            void fetchPRs();
                          }}
                        />
                      )}
                    </div>
                  )}
                  {pr.status === "REVERTED" && pr.authorId === userId && pr.restoredAt && (
                    <div className="mt-2 text-xs opacity-80">These changes were restored into your draft.</div>
                  )}
                  {pr.status === "REVERTED" && pr.authorId === userId && !pr.restoredAt && (
                    <div className="mt-2">
                      <button
                        onClick={() => handleRestore(pr)}
                        disabled={reviewing}
                        className="text-xs px-3 py-1.5 rounded border font-medium transition-colors disabled:opacity-60"
                        style={{ borderColor: "var(--color-border)", color: "var(--color-text)" }}
                      >
                        Restore these changes to my draft
                      </button>
                      <div className="mt-1 text-[11px] opacity-60">
                        Puts the reverted changes back into your draft. Raise a new pull request when you are ready.
                      </div>
                    </div>
                  )}
                  {revertNotice[pr.id] && (
                    <div className="mt-2 text-xs opacity-80">{revertNotice[pr.id]}</div>
                  )}
                  {pr.status === "OPEN" && pr.draftChanged && (
                    <div className="mt-2 text-xs rounded border border-amber-500 p-2">
                      {pr.snapshotKey
                        ? (pr.authorId === userId
                          ? `Your draft now has ${pr.currentChangeCount ?? "more"} changes and this PR has ${pr.changeCount}. The newer ones are not in this PR. They stay in your draft and go in your next PR. Approving this PR publishes only its ${pr.changeCount}.`
                          : `The author's draft now has ${pr.currentChangeCount ?? "more"} changes and this PR has ${pr.changeCount}. Approving publishes only the ${pr.changeCount} this PR was raised with.`)
                        : `This PR was raised with ${pr.changeCount} change${pr.changeCount === 1 ? "" : "s"}, and the draft now has ${pr.currentChangeCount ?? "more"}. The list below shows the whole current draft, and approving publishes all of it.`}
                    </div>
                  )}
                  {pr.status === "OPEN" && pr.authorId === userId && (pr.draftChanged || pr.needsCopy) && (
                    <div className="mt-2">
                      <button
                        onClick={() => handleUpdatePR(pr)}
                        disabled={reviewing}
                        className="text-xs px-3 py-1.5 rounded border font-medium transition-colors disabled:opacity-60"
                        style={{ borderColor: "var(--color-border)", color: "var(--color-text)" }}
                      >
                        Update this PR to my current draft
                      </button>
                      <div className="mt-1 text-[11px] opacity-60">
                        {pr.snapshotKey
                          ? "Only if you want the newer changes in this PR. Otherwise leave it and raise another PR later."
                          : "Saves a copy of your draft as it is now, so approving publishes exactly that and your later changes stay in your draft."}
                      </div>
                    </div>
                  )}

                  {/* Full change list for reviewers */}
                  {canReview && pr.status === "OPEN" && (() => {
                    const fullDiff = prFullDiffMap[pr.id];
                    const fullConflicts = fullDiff ? conflictKeys(fullDiff).length : 0;
                    const fullAvailable = !!fullDiff && ((fullDiff.available && !fullDiff.baselineLost) || fullConflicts > 0);
                    const tab = fullAvailable ? prTabMap[pr.id] || "notes" : "notes";
                    const tabs = fullAvailable ? (
                      <div role="tablist" className="mt-2 flex gap-1 text-[11px]">
                        {(["notes", "full"] as const).map((value) => (
                          <button
                            key={value}
                            type="button"
                            role="tab"
                            aria-selected={tab === value}
                            onClick={() => setPrTabMap((prev) => ({ ...prev, [pr.id]: value }))}
                            className={`px-2 py-1 rounded border font-medium transition-opacity ${tab === value ? "border-blue-500 text-blue-600" : "opacity-60 hover:opacity-100"}`}
                            style={tab === value ? undefined : { borderColor: "var(--color-border)" }}
                          >
                            {value === "notes" ? "Change notes" : `Full changes${fullConflicts ? ` · ${fullConflicts} conflict${fullConflicts !== 1 ? "s" : ""}` : ""}`}
                          </button>
                        ))}
                      </div>
                    ) : null;
                    const unavailable = fullDiff && !fullAvailable ? (
                      <div className="mt-2 text-[11px] opacity-60">
                        {fullDiff.baselineLost
                          ? "The full comparison is unavailable because this draft's starting point was lost. The change notes are listed below."
                          : "The full comparison is unavailable for this draft. The change notes are listed below."}
                      </div>
                    ) : null;
                    if (tab === "full" && fullDiff) {
                      return (
                        <>
                          {tabs}
                          <DraftFullChanges
                            diff={fullDiff}
                            resolutions={prResolutions[pr.id] || {}}
                            notice={prConflictNotice[pr.id] || null}
                            onResolve={(key, value) => setPrResolutions((prev) => {
                              const next = { ...(prev[pr.id] || {}) };
                              if (value) {
                                next[key] = value;
                              } else {
                                delete next[key];
                              }
                              return { ...prev, [pr.id]: next };
                            })}
                          />
                        </>
                      );
                    }

                    const prChanges = prChangesMap[pr.id];
                    const ci = prConflictMap[pr.id];
                    const conflictIris = new Set<string>((ci?.conflicts || []).map((c) => c.entityIRI));

                    if (!prChanges && !fetchedConflictsRef.current.has(pr.id)) {
                      return <div className="mt-2 text-[11px] opacity-40">Loading changes…</div>;
                    }
                    if (!prChanges) return tabs || unavailable ? <>{tabs}{unavailable}</> : null;

                    const grouped = groupByCategory(prChanges);
                    const categories = CATEGORY_ORDER.filter((cat) => grouped[cat]?.length);
                    const expandedCats = prExpandedCategories[pr.id] || new Set<string>();

                    const toggleCat = (cat: string) => {
                      setPrExpandedCategories((prev) => {
                        const s = new Set(prev[pr.id] || []);
                        s.has(cat) ? s.delete(cat) : s.add(cat);
                        return { ...prev, [pr.id]: s };
                      });
                    };

                    return (
                      <>
                      {tabs}
                      {unavailable}
                      <div className="mt-2 flex flex-col gap-1.5 text-[11px]">
                        {/* Conflict banners */}
                        {ci?.conflictType === "IRI_OVERLAP" && (
                          <div
                            className="rounded px-2 py-1.5 border"
                            style={{ borderColor: "rgba(234,179,8,0.4)", backgroundColor: "rgba(234,179,8,0.06)" }}
                          >
                            <div className="flex items-center gap-1 font-semibold text-amber-600">
                              <AlertTriangle size={11} />
                              {ci.conflicts.length} merge conflict{ci.conflicts.length !== 1 ? "s" : ""} — same entities changed publicly
                            </div>
                            <ul className="mt-1 opacity-70 pl-1 space-y-0.5">
                              {ci.conflicts.slice(0, 5).map((c) => (
                                <li key={c.entityIRI}>
                                  · {c.entityLabel || extractLocalName(c.entityIRI)}
                                  {c.changedBy && <span className="opacity-60"> (by {c.changedBy})</span>}
                                </li>
                              ))}
                              {ci.conflicts.length > 5 && (
                                <li className="opacity-50">…and {ci.conflicts.length - 5} more</li>
                              )}
                            </ul>
                          </div>
                        )}
                        {ci?.conflictType === "MAIN_CHANGED" && ci.conflicts.length === 0 && (
                          <div className="flex items-center gap-1 opacity-60">
                            <AlertTriangle size={11} className="text-amber-500" />
                            Public ontology updated since this draft was created — no direct conflicts.
                          </div>
                        )}

                        {/* Change summary header */}
                        <div className="font-semibold opacity-70 mt-1">
                          {prChanges.length} change{prChanges.length !== 1 ? "s" : ""} by {pr.authorUsername}
                        </div>

                        {/* Grouped categories */}
                        {categories.map((cat) => {
                          const items = grouped[cat];
                          const isExpanded = expandedCats.has(cat);
                          const hasCatConflict = items.some((c) => conflictIris.has(getEntityIri(c.operationData)));
                          return (
                            <div key={cat} className="rounded border" style={{ borderColor: "var(--color-border)" }}>
                              <button
                                className="w-full flex items-center justify-between px-2 py-1.5 font-medium hover:opacity-80 transition-opacity"
                                onClick={() => toggleCat(cat)}
                              >
                                <span className="flex items-center gap-1.5">
                                  {isExpanded ? <ChevronDown size={11} /> : <ChevronRight size={11} />}
                                  {cat}
                                  {hasCatConflict && <AlertTriangle size={10} className="text-amber-500" />}
                                </span>
                                <span className="opacity-50">{items.length}</span>
                              </button>
                              {isExpanded && (
                                <div className="border-t divide-y" style={{ borderColor: "var(--color-border)" }}>
                                  {items.map((c) => {
                                    if (c.operationType === "ontologyMerge") {
                                      const mergeKey = `${pr.id}:${c.id}`;
                                      return (
                                        <DraftMergeRow
                                          key={c.id}
                                          change={c}
                                          expanded={expandedMerges.has(mergeKey)}
                                          onToggle={() => setExpandedMerges((prev) => {
                                            const next = new Set(prev);
                                            if (next.has(mergeKey)) {
                                              next.delete(mergeKey);
                                            } else {
                                              next.add(mergeKey);
                                            }
                                            return next;
                                          })}
                                        />
                                      );
                                    }
                                    const described = describeChange(c.operationType, c.operationData);
                                    const { symbol, cls } = getActionMeta(c.operationType);
                                    const name = getEntityName(c.operationType, c.operationData);
                                    const iri = getEntityIri(c.operationData);
                                    const isConflict = iri ? conflictIris.has(iri) : false;
                                    const parentIri = c.operationData?.parent as string | undefined;
                                    const parentLabel = parentIri ? extractLocalName(parentIri) : null;
                                    const targetIri = c.operationData?.target as string | undefined;
                                    const targetLabel = targetIri ? extractLocalName(targetIri) : null;
                                    return (
                                      <div key={c.id} className="flex items-start gap-2 px-2 py-1.5">
                                        <span className={`font-bold flex-shrink-0 mt-0.5 ${cls}`}>{symbol}</span>
                                        <div className="flex-1 min-w-0">
                                          <div className="flex items-center gap-1.5">
                                            <span className="opacity-80 truncate">{described ? described.label : name}</span>
                                            {isConflict && (
                                              <span title="Conflict with public" className="inline-flex flex-shrink-0"><AlertTriangle size={10} className="text-amber-500" /></span>
                                            )}
                                          </div>
                                          {described && described.lines.map((line, i) => (
                                            <div key={i} className="opacity-60 text-[10px] mt-0.5 break-all">{line}</div>
                                          ))}
                                          {!described && parentLabel && (
                                            <div className="opacity-40 text-[10px] mt-0.5">⊂ {parentLabel}</div>
                                          )}
                                          {!described && targetLabel && (
                                            <div className="opacity-40 text-[10px] mt-0.5 truncate" title={targetIri}>→ {targetLabel}</div>
                                          )}
                                        </div>
                                        <span className="opacity-40 flex-shrink-0 text-[10px] mt-0.5">
                                          {getOpLabel(c.operationType)}
                                        </span>
                                      </div>
                                    );
                                  })}
                                </div>
                              )}
                            </div>
                          );
                        })}
                      </div>
                      </>
                    );
                  })()}

                  {/* Review actions — only for open PRs and users who can review */}
                  {canReview && pr.status === "OPEN" && (
                    <div className="mt-3">
                      {reviewingPrId === pr.id ? (
                        <div className="flex flex-col gap-2">
                          <textarea
                            className="w-full text-xs px-2 py-1.5 rounded border outline-none resize-none"
                            style={{ borderColor: "var(--color-border)", backgroundColor: "var(--color-input, var(--color-background))", color: "var(--color-text)" }}
                            placeholder="Review note (optional)"
                            value={reviewNote}
                            onChange={(e) => setReviewNote(e.target.value)}
                            rows={2}
                          />
                          {(() => {
                            const diff = prFullDiffMap[pr.id];
                            const chosen = prResolutions[pr.id] || {};
                            const pending = diff ? conflictKeys(diff).filter((key) => !chosen[key]).length : 0;
                            return pending > 0 ? (
                              <div className="text-[11px] text-amber-600">
                                {pending} conflict{pending !== 1 ? "s" : ""} still need{pending === 1 ? "s" : ""} a choice in Full changes
                              </div>
                            ) : null;
                          })()}
                          <div className="flex gap-2">
                            <button
                              onClick={() => handleApprove(pr)}
                              disabled={reviewing}
                              className="flex items-center gap-1 text-xs px-3 py-1.5 rounded font-medium bg-green-600 text-white hover:bg-green-700 transition-colors disabled:opacity-60"
                            >
                              {reviewing ? <RefreshCw size={11} className="animate-spin" /> : <Check size={11} />}
                              Approve & Merge
                            </button>
                            <button
                              onClick={() => handleReject(pr)}
                              disabled={reviewing}
                              className="flex items-center gap-1 text-xs px-3 py-1.5 rounded font-medium bg-red-600 text-white hover:bg-red-700 transition-colors disabled:opacity-60"
                            >
                              {reviewing ? <RefreshCw size={11} className="animate-spin" /> : <XCircle size={11} />}
                              Reject
                            </button>
                            <button
                              onClick={() => { setReviewingPrId(null); setReviewNote(""); }}
                              className="text-xs px-3 py-1.5 rounded border transition-colors"
                              style={{ borderColor: "var(--color-border)" }}
                            >
                              Cancel
                            </button>
                          </div>
                        </div>
                      ) : (
                        <button
                          onClick={() => { setReviewingPrId(pr.id); setReviewNote(""); }}
                          className="text-xs px-3 py-1.5 rounded border font-medium transition-colors"
                          style={{ borderColor: "var(--color-border)" }}
                        >
                          Review
                        </button>
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

export default DraftPRPanel;

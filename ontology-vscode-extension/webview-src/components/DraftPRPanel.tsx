import React, { useState, useEffect, useCallback } from "react";
import { X, GitPullRequest, Check, XCircle, RefreshCw, ChevronDown, ChevronUp } from "lucide-react";
import apiClient from "../services/apiClient";

interface DraftPR {
  id: string;
  projectId: string;
  authorId: string;
  authorUsername: string;
  title: string;
  description?: string;
  status: "OPEN" | "APPROVED" | "REJECTED";
  changeCount: number;
  createdAt: string;
  reviewedAt?: string;
  reviewerId?: string;
  reviewNote?: string;
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

  const fetchPRs = useCallback(async () => {
    if (!projectId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await apiClient.get<any>(`/api/ontology/${projectId}/draft-prs`);
      const data = res?.data || res;
      setPRs(data.prs || []);
    } catch (e: any) {
      setError("Failed to load pull requests");
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    setLiveDraftCount(draftCount);
  }, [draftCount]);

  useEffect(() => {
    if (isOpen) {
      fetchPRs();
      setShowRaiseForm(false);
      setRaiseError(null);
      if (canRaisePR) {
        apiClient.get<any>(`/api/ontology/${projectId}/drafts/stats`, { userId })
          .then((res: any) => {
            const data = res?.data || res;
            const count = data?.unappliedDrafts ?? data?.totalDrafts;
            if (typeof count === 'number') setLiveDraftCount(count);
          })
          .catch(() => {});
      }
    }
  }, [isOpen, fetchPRs, canRaisePR, projectId, userId]);

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
      });
      setReviewingPrId(null);
      setReviewNote("");
      await fetchPRs();
      onPRApproved?.();
    } catch (e: any) {
      const msg = e?.response?.data?.error || e?.message || "Failed to approve pull request";
      setError(msg);
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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={onClose}>
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
                onClick={() => setExpandedPrId(expandedPrId === pr.id ? null : pr.id)}
              >
                <GitPullRequest
                  size={14}
                  className={`mt-0.5 flex-shrink-0 ${pr.status === "OPEN" ? "text-blue-500" : pr.status === "APPROVED" ? "text-green-500" : "text-red-400"}`}
                />
                <div className="flex-1 min-w-0">
                  <div className="font-medium truncate">{pr.title}</div>
                  <div className="opacity-60 mt-0.5">
                    {pr.authorUsername} · {formatDate(pr.createdAt)} · {pr.changeCount} change{pr.changeCount !== 1 ? "s" : ""}
                    {pr.status !== "OPEN" && pr.reviewedAt && (
                      <> · {pr.status === "APPROVED" ? "Approved" : "Rejected"} {formatDate(pr.reviewedAt)}</>
                    )}
                  </div>
                </div>
                <div className="flex items-center gap-2 flex-shrink-0">
                  <span className={`px-1.5 py-0.5 rounded text-[10px] font-semibold ${
                    pr.status === "OPEN" ? "bg-blue-100 text-blue-700"
                    : pr.status === "APPROVED" ? "bg-green-100 text-green-700"
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
                    <div className="mt-2 opacity-80 whitespace-pre-wrap">{pr.description}</div>
                  )}
                  {pr.reviewNote && (
                    <div className="mt-2 italic opacity-70">Review note: {pr.reviewNote}</div>
                  )}
                  {!pr.description && !pr.reviewNote && !canReview && pr.status === "OPEN" && (
                    <div className="mt-2 text-xs opacity-50 italic">Awaiting review</div>
                  )}

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

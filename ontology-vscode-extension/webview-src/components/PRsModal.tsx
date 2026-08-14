import React, { useState, useEffect, useCallback } from "react";
import {
  X,
  GitPullRequest,
  Check,
  XCircle,
  Loader2,
  RefreshCw,
  ChevronDown,
  ChevronRight,
  User,
  Plus,
  Trash2,
  Edit,
  Tag,
  AlertTriangle,
} from "lucide-react";
import apiClient from "../services/apiClient";

interface ChangeEntry {
  id: string;
  editId: string;
  timestamp: string;
  userId: string;
  username: string;
  changeType: string;
  operationType: string;
  entityType: string;
  entityIRI: string;
  entityLabel: string;
  oldValue: string;
  newValue: string;
  description: string;
  status: string;
  hasConflict: boolean;
  commentCount: number;
}

interface PRGroup {
  userId: string;
  username: string;
  changes: ChangeEntry[];
  expanded: boolean;
}

interface Props {
  projectId: string;
  currentUserId: string;
  currentUsername: string;
  isOwner: boolean;
  isOpen: boolean;
  onClose: () => void;
  onCountChange?: (count: number) => void;
}

const opIcon = (type: string) => {
  switch (type?.toUpperCase()) {
    case "ADD":
    case "CREATE":
      return <Plus size={13} className="text-green-600" />;
    case "REMOVE":
    case "DELETE":
      return <Trash2 size={13} className="text-red-500" />;
    case "MODIFY":
    case "UPDATE":
      return <Edit size={13} className="text-blue-500" />;
    case "ANNOTATION":
      return <Tag size={13} className="text-purple-500" />;
    default:
      return <Edit size={13} className="text-gray-400" />;
  }
};

const statusPill = (status: string) => {
  switch (status) {
    case "APPROVED":
      return (
        <span className="px-1.5 py-0.5 text-[10px] bg-green-100 text-green-700 rounded-full font-medium">
          Approved
        </span>
      );
    case "REJECTED":
      return (
        <span className="px-1.5 py-0.5 text-[10px] bg-red-100 text-red-600 rounded-full font-medium">
          Rejected
        </span>
      );
    default:
      return (
        <span className="px-1.5 py-0.5 text-[10px] bg-amber-100 text-amber-700 rounded-full font-medium">
          Pending
        </span>
      );
  }
};

const fmtTime = (ts: any) => {
  if (!ts) return "";
  // LocalDateTime serialized as array [year,month,day,hour,minute,second,nano] before Jackson fix
  if (Array.isArray(ts)) {
    const [y, mo, d, h, mi, s] = ts as number[];
    const date = new Date(y, mo - 1, d, h ?? 0, mi ?? 0, s ?? 0);
    if (!isNaN(date.getTime())) return date.toLocaleDateString();
    return "";
  }
  const d = new Date(ts);
  if (isNaN(d.getTime())) return "";
  const now = Date.now();
  const diff = now - d.getTime();
  if (diff < 60_000) return "just now";
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return d.toLocaleDateString();
};

const PRsModal: React.FC<Props> = ({
  projectId,
  currentUserId,
  currentUsername,
  isOwner,
  isOpen,
  onClose,
  onCountChange,
}) => {
  const [groups, setGroups] = useState<PRGroup[]>([]);
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState<Record<string, boolean>>({});
  const [filter, setFilter] = useState<"PENDING" | "APPROVED" | "REJECTED" | "ALL">("PENDING");

  const pendingCount = groups.reduce(
    (sum, g) => sum + g.changes.filter((c) => c.status === "PENDING").length,
    0
  );

  const load = useCallback(async () => {
    if (!projectId) return;
    setLoading(true);
    try {
      const url =
        filter === "ALL"
          ? `/api/ontology/${projectId}/changes/synced`
          : `/api/ontology/${projectId}/changes/synced?status=${filter}`;
      const res = await apiClient.get<{ changes: ChangeEntry[] }>(url);
      const changes: ChangeEntry[] = res?.changes || [];

      // Group by userId
      const byUser: Record<string, PRGroup> = {};
      for (const c of changes) {
        if (!byUser[c.userId]) {
          byUser[c.userId] = {
            userId: c.userId,
            username: c.username || c.userId,
            changes: [],
            expanded: true,
          };
        }
        byUser[c.userId].changes.push(c);
      }
      const grouped = Object.values(byUser).sort((a, b) => {
        const aHasPending = a.changes.some((c) => c.status === "PENDING") ? 0 : 1;
        const bHasPending = b.changes.some((c) => c.status === "PENDING") ? 0 : 1;
        return aHasPending - bHasPending;
      });
      setGroups(grouped);
      onCountChange?.(changes.filter((c) => c.status === "PENDING").length);
    } catch (e) {
      console.error("[PRsModal] load error", e);
    } finally {
      setLoading(false);
    }
  }, [projectId, filter, onCountChange]);

  useEffect(() => {
    if (isOpen) load();
  }, [isOpen, load]);

  const act = async (changeId: string, action: "approve" | "reject") => {
    setActionLoading((prev) => ({ ...prev, [changeId]: true }));
    try {
      await apiClient.post(`/api/ontology/${projectId}/changes/${changeId}/${action}`, {
        userId: currentUserId,
        username: currentUsername,
      });
      // Optimistically update
      setGroups((prev) =>
        prev.map((g) => ({
          ...g,
          changes: g.changes.map((c) =>
            c.id === changeId
              ? { ...c, status: action === "approve" ? "APPROVED" : "REJECTED" }
              : c
          ),
        }))
      );
      onCountChange?.(
        groups.reduce(
          (sum, g) =>
            sum +
            g.changes.filter(
              (c) => c.status === "PENDING" && c.id !== changeId
            ).length,
          0
        )
      );
    } catch (e) {
      console.error(`[PRsModal] ${action} error`, e);
    } finally {
      setActionLoading((prev) => ({ ...prev, [changeId]: false }));
    }
  };

  const actAll = async (userId: string, action: "approve" | "reject") => {
    const group = groups.find((g) => g.userId === userId);
    if (!group) return;
    const pending = group.changes.filter((c) => c.status === "PENDING");
    for (const c of pending) {
      await act(c.id, action);
    }
  };

  const toggleGroup = (userId: string) => {
    setGroups((prev) =>
      prev.map((g) => (g.userId === userId ? { ...g, expanded: !g.expanded } : g))
    );
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-2xl max-h-[90vh] flex flex-col overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-200">
          <div className="flex items-center gap-2.5">
            <GitPullRequest size={18} className="text-purple-600" />
            <span className="font-semibold text-gray-800 text-sm">Pull Requests</span>
            {pendingCount > 0 && (
              <span className="px-2 py-0.5 bg-amber-100 text-amber-700 text-xs rounded-full font-semibold">
                {pendingCount} pending
              </span>
            )}
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={load}
              className="p-1 rounded hover:bg-gray-100 text-gray-400 hover:text-gray-600"
              title="Refresh"
            >
              <RefreshCw size={14} />
            </button>
            <button onClick={onClose} className="p-1 rounded hover:bg-gray-100 text-gray-500">
              <X size={16} />
            </button>
          </div>
        </div>

        {/* Filter tabs */}
        <div className="flex border-b border-gray-200 px-4">
          {(["PENDING", "APPROVED", "REJECTED", "ALL"] as const).map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`px-3 py-2 text-xs font-medium border-b-2 -mb-px transition-colors ${
                filter === f
                  ? "text-purple-600 border-purple-600"
                  : "text-gray-500 border-transparent hover:text-gray-700"
              }`}
            >
              {f === "ALL" ? "All" : f.charAt(0) + f.slice(1).toLowerCase()}
            </button>
          ))}
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-4">
          {loading && (
            <div className="flex items-center justify-center py-12 text-gray-400">
              <Loader2 size={24} className="animate-spin" />
            </div>
          )}

          {!loading && groups.length === 0 && (
            <div className="flex flex-col items-center justify-center py-14 text-gray-400 gap-3">
              <GitPullRequest size={36} className="opacity-30" />
              <p className="text-sm">
                {filter === "PENDING" ? "No pending changes to review." : `No ${filter.toLowerCase()} changes.`}
              </p>
            </div>
          )}

          {!loading &&
            groups.map((group) => {
              const groupPending = group.changes.filter((c) => c.status === "PENDING");
              const canReview = isOwner || group.userId !== currentUserId;

              return (
                <div
                  key={group.userId}
                  className="mb-3 border border-gray-200 rounded-lg overflow-hidden"
                >
                  {/* Group header */}
                  <div
                    className="flex items-center justify-between px-4 py-2.5 bg-gray-50 cursor-pointer hover:bg-gray-100"
                    onClick={() => toggleGroup(group.userId)}
                  >
                    <div className="flex items-center gap-2.5">
                      {group.expanded ? (
                        <ChevronDown size={14} className="text-gray-400" />
                      ) : (
                        <ChevronRight size={14} className="text-gray-400" />
                      )}
                      <div className="w-6 h-6 rounded-full bg-purple-100 flex items-center justify-center">
                        <User size={12} className="text-purple-600" />
                      </div>
                      <span className="text-xs font-semibold text-gray-700">{group.username}</span>
                      <span className="text-[10px] text-gray-400">{group.userId}</span>
                      <span className="text-[10px] text-gray-500 ml-1">
                        {group.changes.length} change{group.changes.length !== 1 ? "s" : ""}
                      </span>
                      {groupPending.length > 0 && (
                        <span className="px-1.5 py-0.5 bg-amber-100 text-amber-700 text-[10px] rounded-full font-medium">
                          {groupPending.length} pending
                        </span>
                      )}
                    </div>

                    {canReview && groupPending.length > 0 && (
                      <div
                        className="flex gap-1.5"
                        onClick={(e) => e.stopPropagation()}
                      >
                        <button
                          onClick={() => actAll(group.userId, "approve")}
                          className="flex items-center gap-1 px-2 py-1 text-[10px] bg-green-50 text-green-700 hover:bg-green-100 border border-green-200 rounded"
                        >
                          <Check size={10} /> Approve all
                        </button>
                        <button
                          onClick={() => actAll(group.userId, "reject")}
                          className="flex items-center gap-1 px-2 py-1 text-[10px] bg-red-50 text-red-600 hover:bg-red-100 border border-red-200 rounded"
                        >
                          <XCircle size={10} /> Reject all
                        </button>
                      </div>
                    )}
                  </div>

                  {/* Change rows */}
                  {group.expanded && (
                    <div className="divide-y divide-gray-100">
                      {group.changes.map((change) => (
                        <div
                          key={change.id}
                          className="flex items-start gap-3 px-4 py-2.5 hover:bg-gray-50"
                        >
                          <div className="mt-0.5 flex-shrink-0">{opIcon(change.operationType)}</div>
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2 flex-wrap">
                              <span className="text-xs font-medium text-gray-800 truncate">
                                {change.entityLabel || change.entityIRI || change.description || "Change"}
                              </span>
                              {change.entityType && (
                                <span className="px-1 py-0.5 text-[10px] bg-gray-100 text-gray-500 rounded">
                                  {change.entityType}
                                </span>
                              )}
                              {statusPill(change.status)}
                              {change.hasConflict && (
                                <AlertTriangle size={11} className="text-amber-500" title="Conflict detected" />
                              )}
                            </div>
                            {change.description && (
                              <p className="text-[11px] text-gray-500 mt-0.5 line-clamp-2">
                                {change.description}
                              </p>
                            )}
                            <div className="text-[10px] text-gray-400 mt-0.5 flex gap-2">
                              <span>{fmtTime(change.timestamp)}</span>
                              {change.newValue && (
                                <span className="text-green-600 truncate max-w-[16rem]" title={change.newValue}>
                                  + {change.newValue.slice(0, 80)}
                                </span>
                              )}
                            </div>
                          </div>

                          {/* Action buttons — only for reviewers on pending changes */}
                          {canReview && change.status === "PENDING" && (
                            <div className="flex gap-1 flex-shrink-0">
                              {actionLoading[change.id] ? (
                                <Loader2 size={14} className="animate-spin text-gray-400" />
                              ) : (
                                <>
                                  <button
                                    onClick={() => act(change.id, "approve")}
                                    className="p-1 rounded hover:bg-green-50 text-green-600 hover:text-green-700"
                                    title="Approve"
                                  >
                                    <Check size={14} />
                                  </button>
                                  <button
                                    onClick={() => act(change.id, "reject")}
                                    className="p-1 rounded hover:bg-red-50 text-red-500 hover:text-red-600"
                                    title="Reject"
                                  >
                                    <XCircle size={14} />
                                  </button>
                                </>
                              )}
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
        </div>

        {/* Footer */}
        <div className="px-5 py-3 border-t border-gray-200 bg-gray-50 flex justify-between items-center">
          <span className="text-[11px] text-gray-400">
            {isOwner
              ? "As project owner you can approve or reject any change."
              : "You can review changes made by other members."}
          </span>
          <button
            onClick={onClose}
            className="px-3 py-1.5 text-xs text-gray-600 hover:bg-gray-200 rounded-md"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};

export default PRsModal;

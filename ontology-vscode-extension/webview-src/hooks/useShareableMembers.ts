import { useEffect, useMemo, useState } from "react";
import apiClient from "../services/apiClient";
import {
  normalizeEmail,
  prepareShareableMembers,
  type WorkspaceMember,
} from "../utils/projectShare";

export function useShareableMembers(
  workspaceId: string | undefined,
  excludeEmails: string[],
  refreshKey?: number | string,
  currentUser?: { userId?: string | null; email?: string | null } | null,
) {
  const [workspaceMembers, setWorkspaceMembers] = useState<WorkspaceMember[]>([]);
  const [loadingMembers, setLoadingMembers] = useState(false);
  const [membersLoadError, setMembersLoadError] = useState<string | null>(null);
  const [memberSearch, setMemberSearch] = useState("");

  const excludeSet = useMemo(() => new Set(excludeEmails.map(normalizeEmail)), [excludeEmails]);

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
      const members: WorkspaceMember[] = workspaceData?.members || [];
      setWorkspaceMembers(prepareShareableMembers(members, currentUser?.userId, currentUser?.email, excludeSet));
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

  return {
    workspaceMembers,
    loadingMembers,
    membersLoadError,
    memberSearch,
    setMemberSearch,
    loadWorkspaceMembers,
  };
}

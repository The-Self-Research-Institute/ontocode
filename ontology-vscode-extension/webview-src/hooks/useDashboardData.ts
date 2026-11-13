import { useEffect, useState } from "react";
import apiClient from "../services/apiClient";
import { isDesktop } from "../utils/desktop";
import type { AuthUser, Project } from "../utils/projectDashboardTypes";
import { mapWorkspaceMembersToTeam } from "../utils/projectDashboardLogic";
import type { useWorkspaceTeam } from "./useWorkspaceTeam";

export function useDashboardData(
  user: AuthUser,
  team: ReturnType<typeof useWorkspaceTeam>,
  clearCacheAndLogout: () => void,
) {
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const { setTeamMembers, setWorkspaceOwnerId, setWorkspaceDisplayName } = team;

  const loadData = async () => {
    try {
      setLoading(true);
      setLoadError(null);

      const projectsResponse = user?.workspaceId
        ? await apiClient.get(`/api/projects/my?workspaceId=${user.workspaceId}`)
        : await apiClient.get(`/api/projects/my`);
      const projectsData = projectsResponse?.data || projectsResponse;
      setProjects(projectsData?.projects || []);

      if (!isDesktop() && user?.workspaceId) {
        try {
          const workspaceResponse = await apiClient.get(`/api/workspaces/${user.workspaceId}`);
          const workspaceData = workspaceResponse?.data || workspaceResponse;
          setWorkspaceOwnerId(workspaceData?.ownerId || null);
          setWorkspaceDisplayName(workspaceData?.name || "");
          setTeamMembers(mapWorkspaceMembersToTeam(workspaceData?.members || []));
        } catch (error) {
          console.error("[ProjectDashboard] Error loading workspace members:", error);
        }
      }
    } catch (error: any) {
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
          : message,
      );
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  useEffect(() => {
    const handler = async (e: Event) => {
      const event = (e as CustomEvent).detail;
      if (event?.type === "PROJECT_DELETED" || event?.type === "PROJECT_CREATED") {
        try {
          const resp = await apiClient.get(`/api/projects/my?workspaceId=${user?.workspaceId}`);
          const data = resp?.data || resp;
          setProjects(data?.projects || []);
        } catch (error) {
          void error;
        }
      }
    };
    window.addEventListener("workspaceEvent", handler);
    return () => window.removeEventListener("workspaceEvent", handler);
  }, [user?.workspaceId]);

  return { projects, loading, loadError, loadData };
}

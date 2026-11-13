import { useEffect, useState } from "react";
import apiClient from "../services/apiClient";
import { isDesktop } from "../utils/desktop";
import type { AuthUser, TeamMember } from "../utils/projectDashboardTypes";
import { mapWorkspaceMembersToTeam } from "../utils/projectDashboardLogic";

export function useWorkspaceTeam(user: AuthUser, refreshPermissions: () => Promise<unknown>) {
  const [teamMembers, setTeamMembers] = useState<TeamMember[]>([]);
  const [workspaceOwnerId, setWorkspaceOwnerId] = useState<string | null>(null);
  const [workspaceDisplayName, setWorkspaceDisplayName] = useState<string>("");

  useEffect(() => {
    if (isDesktop() || !user?.workspaceId) return;

    let lastSeenPlan = "";
    let lastSeenStatus = "";

    const interval = setInterval(async () => {
      try {
        const workspaceResponse = await apiClient.get(`/api/workspaces/${user.workspaceId}`);
        const workspaceData = workspaceResponse?.data || workspaceResponse;
        const teamMembersList = mapWorkspaceMembersToTeam(workspaceData?.members || []);
        setTeamMembers((prev) => (JSON.stringify(prev) !== JSON.stringify(teamMembersList) ? teamMembersList : prev));
        setWorkspaceOwnerId(workspaceData?.ownerId || null);
        setWorkspaceDisplayName(workspaceData?.name || "");

        const currentPlan = String(workspaceData?.subscriptionPlan || "").toUpperCase();
        const currentStatus = String(workspaceData?.billingStatus || "").toUpperCase();
        const planChanged = currentPlan && currentPlan !== lastSeenPlan;
        const statusChanged = currentStatus && currentStatus !== lastSeenStatus;
        if ((planChanged || statusChanged) && (lastSeenPlan || lastSeenStatus)) {
          await refreshPermissions().catch(() => undefined);
        }
        lastSeenPlan = currentPlan || lastSeenPlan;
        lastSeenStatus = currentStatus || lastSeenStatus;
      } catch (e) {
        void e;
      }
    }, 15000);
    return () => clearInterval(interval);
  }, [user?.workspaceId, user?.subscriptionPlan, refreshPermissions]);

  return {
    teamMembers,
    setTeamMembers,
    workspaceOwnerId,
    setWorkspaceOwnerId,
    workspaceDisplayName,
    setWorkspaceDisplayName,
  };
}

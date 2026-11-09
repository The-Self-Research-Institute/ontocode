import apiClient from "../services/apiClient";
import type { AuthUser, ShowToast } from "../utils/projectDashboardTypes";

export function useWorkspaceInvite(user: AuthUser, showToast: ShowToast) {
  const handleInviteMember = async (email: string, role: string) => {
    try {
      const workspaceResponse = await apiClient.get(`/api/workspaces/${user?.workspaceId}`);
      const workspace = workspaceResponse?.data?.workspace || workspaceResponse?.data;
      const members = workspace?.members || [];

      const existingMember = members.find((m: any) => m.email?.toLowerCase() === email.toLowerCase());
      const isActiveMember =
        existingMember &&
        existingMember.userId &&
        (existingMember.status === "ACTIVE" || !existingMember.status || existingMember.status === "active");

      if (isActiveMember) {
        showToast(`User "${email}" is already a member of this workspace.`, "warning");
        return;
      }

      let pendingInvites: any[] = [];
      try {
        const pendingResponse = await apiClient.get(`/api/invitations/workspace/${user?.workspaceId}`);
        const raw = pendingResponse?.data ?? pendingResponse;
        pendingInvites = Array.isArray(raw) ? raw : raw?.invitations || [];
      } catch (e) {
        console.warn("Could not check pending invitations:", e);
        showToast("Could not verify pending invitations. Please try again.", "warning");
        return;
      }

      const pendingInvite = pendingInvites.find(
        (inv: any) =>
          inv.email?.toLowerCase() === email.toLowerCase() && String(inv.status || "").toUpperCase() === "PENDING",
      );

      if (pendingInvite) {
        showToast(
          `An invitation was already sent to "${email}" on ${new Date(pendingInvite.createdAt).toLocaleDateString()}. Please wait for them to accept.`,
          "warning",
        );
        return;
      }

      const response = await apiClient.post("/api/invitations/send", {
        workspaceId: user?.workspaceId || "default",
        email: email,
        role: role,
      });
      return response;
    } catch (error: any) {
      console.error("Error inviting member:", error);
      throw {
        message: error?.error || error?.message || "Failed to send invitation",
        response: error,
      };
    }
  };

  return { handleInviteMember };
}

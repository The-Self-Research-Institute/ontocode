import React from "react";
import { Crown, UserPlus } from "lucide-react";
import type { SubscriptionInfo } from "../../utils/projectDashboardTypes";

interface InviteMemberButtonProps {
  subscription: SubscriptionInfo;
  isWorkspaceOwner: boolean;
  canInviteMembers: boolean;
  memberCount: number;
  onInvite: () => void;
  onOpenSubscriptionPlans?: () => void;
}

const InviteMemberButton: React.FC<InviteMemberButtonProps> = ({
  subscription,
  isWorkspaceOwner,
  canInviteMembers,
  memberCount,
  onInvite,
  onOpenSubscriptionPlans,
}) => {
  if (!subscription.canAccessFeature("hasBasicCollaboration")) {
    return isWorkspaceOwner && onOpenSubscriptionPlans ? (
      <button
        onClick={onOpenSubscriptionPlans}
        className="h-9 min-w-[150px] inline-flex items-center justify-center gap-2 px-3 text-sm border border-purple-200 rounded-lg bg-purple-50 text-purple-600 hover:bg-purple-100 transition-colors"
        title="Upgrade to Professional to invite workspace members"
      >
        <UserPlus size={16} />
        Invite Member
        <span className="bg-purple-500 text-white text-[10px] px-1.5 py-0.5 rounded">PRO</span>
      </button>
    ) : (
      <div
        className="h-9 min-w-[150px] inline-flex items-center justify-center gap-2 px-3 text-sm border border-purple-200 rounded-lg bg-purple-50 text-purple-500 cursor-not-allowed"
        title="Only the workspace owner can upgrade to invite workspace members"
      >
        <UserPlus size={16} />
        Invite Member
        <span className="bg-purple-500 text-white text-[10px] px-1.5 py-0.5 rounded">PRO</span>
      </div>
    );
  }

  if (canInviteMembers) {
    const withinLimit = subscription.isWithinLimit(memberCount, "maxTeamMembers");
    return (
      <button
        onClick={onInvite}
        className={`h-9 min-w-[150px] inline-flex items-center justify-center gap-2 px-3 text-sm border border-gray-300 rounded-lg hover:bg-gray-50 ${!withinLimit ? "border-amber-300 bg-amber-50" : ""}`}
        title={
          !withinLimit
            ? `Limit reached (${subscription.limits.maxTeamMembers} members). Upgrade to add more.`
            : "Invite a new workspace member"
        }
      >
        <UserPlus size={16} />
        Invite Member
        {!withinLimit && <span className="bg-amber-500 text-white text-[10px] px-1 py-0.5 rounded">LIMIT</span>}
      </button>
    );
  }

  return (
    <div
      className="h-9 min-w-[150px] inline-flex items-center justify-center gap-2 px-3 text-sm border border-gray-200 rounded-lg bg-gray-50 text-gray-400 cursor-not-allowed"
      title="Only workspace owners and admins can invite members"
    >
      <UserPlus size={16} />
      Invite Member
      <Crown size={14} className="text-purple-400" />
    </div>
  );
};

export default InviteMemberButton;

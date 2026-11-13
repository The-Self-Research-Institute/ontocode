import React from "react";
import { Users } from "lucide-react";
import type { SubscriptionInfo, TeamMember } from "../../utils/projectDashboardTypes";
import InviteMemberButton from "./InviteMemberButton";
import TeamMemberRow from "./TeamMemberRow";

interface TeamMembersPanelProps {
  teamMembers: TeamMember[];
  subscription: SubscriptionInfo;
  currentUserEmail?: string;
  isWorkspaceOwner: boolean;
  canInviteMembers: boolean;
  onInvite: () => void;
  onOpenSubscriptionPlans?: () => void;
  onTransferOwnership: (member: TeamMember) => void;
  onRemove: (member: TeamMember) => void;
  onCancelInvitation: (member: TeamMember) => void;
}

const TeamMembersPanel: React.FC<TeamMembersPanelProps> = ({
  teamMembers,
  subscription,
  currentUserEmail,
  isWorkspaceOwner,
  canInviteMembers,
  onInvite,
  onOpenSubscriptionPlans,
  onTransferOwnership,
  onRemove,
  onCancelInvitation,
}) => (
  <div className="mt-8 bg-white rounded-lg shadow-sm border border-gray-200 p-6">
    <div className="flex items-center justify-between mb-6">
      <h2 className="text-xl font-semibold text-gray-900 flex items-center gap-2">
        <Users size={24} className="text-purple-600" />
        Workspace Members
        <span className="text-sm font-normal text-gray-500">({teamMembers.length})</span>
      </h2>
      <InviteMemberButton
        subscription={subscription}
        isWorkspaceOwner={isWorkspaceOwner}
        canInviteMembers={canInviteMembers}
        memberCount={teamMembers.length}
        onInvite={onInvite}
        onOpenSubscriptionPlans={onOpenSubscriptionPlans}
      />
    </div>

    <div className="pr-2 custom-scrollbar">
      {teamMembers.length === 0 ? (
        <div className="text-center py-8 text-gray-500">No workspace members yet</div>
      ) : (
        <div className="space-y-2">
          {teamMembers.map((member) => (
            <TeamMemberRow
              key={member.id}
              member={member}
              currentUserEmail={currentUserEmail}
              isWorkspaceOwner={isWorkspaceOwner}
              canInviteMembers={canInviteMembers}
              onTransferOwnership={onTransferOwnership}
              onRemove={onRemove}
              onCancelInvitation={onCancelInvitation}
            />
          ))}
        </div>
      )}
    </div>
  </div>
);

export default TeamMembersPanel;

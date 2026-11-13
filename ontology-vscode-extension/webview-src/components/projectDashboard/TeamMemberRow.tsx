import React from "react";
import { Trash2 } from "lucide-react";
import { roleDisplayLabel } from "../../utils/roles";
import { roleBadgeStyle } from "../../utils/projectDashboardLogic";
import type { TeamMember } from "../../utils/projectDashboardTypes";

interface TeamMemberRowProps {
  member: TeamMember;
  currentUserEmail?: string;
  isWorkspaceOwner: boolean;
  canInviteMembers: boolean;
  onTransferOwnership: (member: TeamMember) => void;
  onRemove: (member: TeamMember) => void;
  onCancelInvitation: (member: TeamMember) => void;
}

const TeamMemberRow: React.FC<TeamMemberRowProps> = ({
  member,
  currentUserEmail,
  isWorkspaceOwner,
  canInviteMembers,
  onTransferOwnership,
  onRemove,
  onCancelInvitation,
}) => {
  const isSelf = member.email === currentUserEmail;
  const isOwnerMember = member.roles.some((r) => r.toUpperCase() === "OWNER");

  return (
    <div className="flex items-center justify-between p-3 border border-gray-200 rounded-lg hover:bg-gray-50">
      <div className="flex items-center gap-3">
        <div
          className={`w-10 h-10 rounded-full flex items-center justify-center font-semibold ${member.status === "PENDING"
              ? "bg-yellow-100 text-yellow-600"
              : "bg-purple-100 text-purple-600"
            }`}
        >
          {member.username.charAt(0).toUpperCase()}
        </div>
        <div>
          <div className="flex items-center gap-2">
            <p className="font-medium text-gray-900">{member.username}</p>
            {member.status === "PENDING" && (
              <span className="px-2 py-0.5 text-xs bg-yellow-100 text-yellow-700 rounded-full">Pending</span>
            )}
          </div>
          <p className="text-sm text-gray-500">{member.email}</p>
        </div>
      </div>
      <div className="flex items-center gap-2">
        <div className="flex items-center gap-2">
          {member.roles.map((role) => (
            <span key={role} className={`px-2 py-0.5 text-[10px] font-bold rounded border ${roleBadgeStyle(role)}`}>
              {roleDisplayLabel(role).toUpperCase()}
            </span>
          ))}
        </div>
        {isWorkspaceOwner && !isSelf && member.status !== "PENDING" && !isOwnerMember && (
          <button
            onClick={() => onTransferOwnership(member)}
            className="px-2 py-1 text-xs font-medium text-purple-600 hover:bg-purple-50 rounded border border-purple-200"
            title="Transfer workspace ownership to this member"
          >
            Make Owner
          </button>
        )}
        {canInviteMembers && !isSelf && member.status !== "PENDING" && !isOwnerMember && (
          <button
            onClick={() => onRemove(member)}
            className="p-1.5 hover:bg-red-50 rounded text-red-600 hover:text-red-700 transition-colors"
            title="Remove member"
          >
            <Trash2 size={16} />
          </button>
        )}
        {isSelf && !isOwnerMember && member.status !== "PENDING" && (
          <button
            onClick={() => onRemove(member)}
            className="px-2 py-1 text-xs font-medium text-red-600 hover:bg-red-50 rounded border border-red-200"
            title="Leave this workspace"
          >
            Leave
          </button>
        )}
        {canInviteMembers && member.status === "PENDING" && (
          <button
            onClick={() => onCancelInvitation(member)}
            className="p-1.5 hover:bg-red-50 rounded text-red-600 hover:text-red-700 transition-colors"
            title="Cancel invitation"
          >
            <Trash2 size={16} />
          </button>
        )}
        {isSelf && <span className="px-2 py-1 text-xs bg-blue-100 text-blue-700 rounded-full">You</span>}
      </div>
    </div>
  );
};

export default TeamMemberRow;

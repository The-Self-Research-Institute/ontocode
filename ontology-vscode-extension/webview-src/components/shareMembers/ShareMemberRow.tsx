import React from "react";
import { isPrivilegedMember, privilegedBadgeLabel } from "../../utils/projectShare";
import type { ProjectShareRole, WorkspaceMember } from "../../utils/projectShare";

interface ShareMemberRowProps {
  member: WorkspaceMember;
  privileged: boolean;
  isSelected: boolean;
  role: ProjectShareRole;
  onToggle: () => void;
  onRoleChange: (role: ProjectShareRole) => void;
}

const ShareMemberRow: React.FC<ShareMemberRowProps> = ({
  member,
  privileged,
  isSelected,
  role,
  onToggle,
  onRoleChange,
}) => {
  return (
    <div
      className={`flex items-center gap-2 p-2 rounded border ${
        privileged
          ? "bg-purple-50 border-purple-100"
          : "bg-white hover:bg-gray-50 border-transparent hover:border-gray-200"
      }`}
    >
      <input
        type="checkbox"
        checked={isSelected}
        onChange={onToggle}
        disabled={privileged}
        className="rounded text-purple-600 disabled:opacity-50 disabled:cursor-not-allowed"
      />
      <div className="text-sm flex-1 min-w-0">
        <div className="flex items-center gap-1.5">
          <span className="font-medium text-gray-900 truncate">{member.username}</span>
          {isPrivilegedMember(member) && (
            <span className="px-1.5 py-0.5 text-[10px] font-semibold rounded bg-purple-100 text-purple-700 flex-shrink-0">
              {privilegedBadgeLabel(member)}
            </span>
          )}
        </div>
        <div className="text-gray-500 text-xs truncate">{member.email}</div>
      </div>
      {privileged ? (
        <span className="text-xs px-2 py-1 bg-purple-100 text-purple-700 rounded-lg font-medium shrink-0">
          Editor
        </span>
      ) : (
        <select
          value={role}
          onChange={(e) => onRoleChange(e.target.value as ProjectShareRole)}
          disabled={!isSelected}
          className="text-xs border border-gray-200 rounded-lg px-2 py-1 bg-white focus:ring-2 focus:ring-purple-500 disabled:bg-gray-100 disabled:text-gray-400 shrink-0"
        >
          <option value="VIEWER">Viewer</option>
          <option value="DRAFT_EDITOR">Draft Editor</option>
          <option value="EDITOR">Editor</option>
        </select>
      )}
    </div>
  );
};

export default ShareMemberRow;

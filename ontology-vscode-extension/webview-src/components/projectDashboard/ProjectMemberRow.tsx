import React from "react";
import { UserMinus } from "lucide-react";
import { projectRoleSelectValue, roleDisplayLabel } from "../../utils/roles";
import { canRemoveProjectMember, isProjectMemberRoleLocked } from "../../utils/projectDashboardLogic";
import type { ProjectMember } from "../../utils/projectDashboardTypes";

interface ProjectMemberRowProps {
  member: ProjectMember;
  canManage: boolean;
  isWorkspaceOwner: boolean;
  currentUserIsProjectOwner: boolean;
  onRoleChange: (newRole: string) => void;
  onRemove: () => void;
}

const ProjectMemberRow: React.FC<ProjectMemberRowProps> = ({
  member,
  canManage,
  isWorkspaceOwner,
  currentUserIsProjectOwner,
  onRoleChange,
  onRemove,
}) => {
  const roleLocked = isProjectMemberRoleLocked(member, isWorkspaceOwner);

  return (
    <div className="flex items-center justify-between p-3 border border-gray-200 rounded-lg">
      <div className="flex items-center gap-3">
        <div className="w-10 h-10 bg-purple-100 rounded-full flex items-center justify-center font-semibold text-purple-600">
          {member.username.charAt(0).toUpperCase()}
        </div>
        <div>
          <p className="font-medium text-gray-900">{member.username}</p>
          <p className="text-sm text-gray-500">{member.email}</p>
          {member.workspaceEditorLink && (
            <p className="text-xs text-amber-700 mt-0.5">
              {member.workspaceEditorLink === "WORKSPACE_OWNER"
                ? "Workspace owner — always on shared projects"
                : "Workspace admin — removable only by workspace owner"}
            </p>
          )}
        </div>
      </div>
      <div className="flex items-center gap-2">
        {!canManage ? (
          <span className="text-sm px-2 py-1 bg-gray-100 text-gray-700 rounded">{roleDisplayLabel(member.role)}</span>
        ) : (
          <>
            <select
              value={projectRoleSelectValue(member.role)}
              onChange={(e) => onRoleChange(e.target.value)}
              className="text-sm border border-gray-300 rounded-lg px-2 py-1 focus:ring-2 focus:ring-purple-500"
              disabled={member.role === "OWNER" || roleLocked}
            >
              {member.role === "OWNER" && <option value="OWNER">Owner</option>}
              {member.role !== "OWNER" && !roleLocked && currentUserIsProjectOwner && (
                <option value="OWNER">Make Owner</option>
              )}
              <option value="EDITOR">Editor</option>
              <option value="DRAFT_EDITOR">Draft Editor</option>
              <option value="VIEWER">Viewer</option>
            </select>
            {member.role !== "OWNER" && canManage && canRemoveProjectMember(member, isWorkspaceOwner) && (
              <button
                onClick={onRemove}
                className="p-2 text-red-600 hover:bg-red-50 rounded-lg transition-colors"
                title="Remove member"
              >
                <UserMinus size={16} />
              </button>
            )}
          </>
        )}
      </div>
    </div>
  );
};

export default ProjectMemberRow;

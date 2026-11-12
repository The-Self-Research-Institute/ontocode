import React from "react";
import { Loader2, UserPlus, Users } from "lucide-react";
import type { useAddProjectMembers } from "../../hooks/useAddProjectMembers";
import type { Project, ProjectMember } from "../../utils/projectDashboardTypes";
import { EMPTY_SHARE_SELECTION, shareSelectionForVisibility } from "../../utils/projectShare";
import ShareWithSelector from "../shareMembers/ShareWithSelector";
import ProjectMemberRow from "./ProjectMemberRow";

interface ProjectMembersTabProps {
  project: Project;
  workspaceId?: string;
  canManage: boolean;
  isWorkspaceOwner: boolean;
  currentUserIsProjectOwner: boolean;
  add: ReturnType<typeof useAddProjectMembers>;
  onRoleChange: (member: ProjectMember, newRole: string) => void;
  onRemoveMember: (member: ProjectMember) => void;
}

const ProjectMembersTab: React.FC<ProjectMembersTabProps> = ({
  project,
  workspaceId,
  canManage,
  isWorkspaceOwner,
  currentUserIsProjectOwner,
  add,
  onRoleChange,
  onRemoveMember,
}) => (
  <div className="space-y-4">
    <div className="flex items-center justify-between">
      <p className="text-sm text-gray-600">
        {canManage
          ? "Manage who has access to this project and their project-level role (separate from workspace role)."
          : "View who has access to this project and their project role."}
      </p>
      {canManage && (
        <button
          onClick={() => {
            add.setAddMemberSelection(
              add.showAddMemberForm ? EMPTY_SHARE_SELECTION : shareSelectionForVisibility(
                project.visibility,
                (project.members ?? []).some((m) => m.userId !== project.ownerId),
              ),
            );
            add.setShowAddMemberForm(!add.showAddMemberForm);
          }}
          className="flex items-center gap-2 px-3 py-2 bg-purple-600 text-white text-sm rounded-lg hover:bg-purple-700 transition-colors"
        >
          <UserPlus size={16} />
          {add.showAddMemberForm ? "Cancel" : "Share with Members"}
        </button>
      )}
    </div>

    {add.showAddMemberForm && (
      <div className="bg-purple-50 border border-purple-200 rounded-lg p-4 space-y-4">
        <h4 className="font-medium text-gray-900">Share with</h4>
        <ShareWithSelector
          workspaceId={workspaceId}
          excludeEmails={project.members?.map((m) => m.email) || []}
          selection={add.addMemberSelection}
          onSelectionChange={add.setAddMemberSelection}
          refreshKey={add.addMemberSelectorKey}
          noMembersHint="All active workspace members are already on this project."
          autoIncludePrivileged
        />
        <button
          onClick={add.handleAddSelectedMembers}
          disabled={
            add.addingMembers ||
            (add.addMemberSelection.shareWith === "specific" && add.addMemberSelection.members.length === 0)
          }
          className="w-full px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
        >
          {add.addingMembers ? (
            <>
              <Loader2 size={16} className="animate-spin" />
              Sharing...
            </>
          ) : (
            <>
              <UserPlus size={16} />
              Share Access
            </>
          )}
        </button>
      </div>
    )}

    {project.members && project.members.length > 0 ? (
      <div className="space-y-3">
        {project.members.map((member) => (
          <ProjectMemberRow
            key={member.userId}
            member={member}
            canManage={canManage}
            isWorkspaceOwner={isWorkspaceOwner}
            currentUserIsProjectOwner={currentUserIsProjectOwner}
            onRoleChange={(newRole) => onRoleChange(member, newRole)}
            onRemove={() => onRemoveMember(member)}
          />
        ))}
      </div>
    ) : (
      <div className="text-center py-8 text-gray-500">
        <Users size={48} className="mx-auto mb-3 text-gray-300" />
        <p>No members in this project yet</p>
      </div>
    )}
  </div>
);

export default ProjectMembersTab;

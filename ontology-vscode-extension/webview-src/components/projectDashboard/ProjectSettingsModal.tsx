import React from "react";
import { AlertTriangle, FileText, FolderOpen, Loader2, Save, Users, X } from "lucide-react";
import { isDesktop } from "../../utils/desktop";
import type { useAddProjectMembers } from "../../hooks/useAddProjectMembers";
import type { useProjectSettingsModal } from "../../hooks/useProjectSettingsModal";
import type { Project, ProjectMember } from "../../utils/projectDashboardTypes";
import ProjectGeneralTab from "./ProjectGeneralTab";
import ProjectMembersTab from "./ProjectMembersTab";
import ProjectDangerTab from "./ProjectDangerTab";

type SettingsState = ReturnType<typeof useProjectSettingsModal>;

interface ProjectSettingsModalProps {
  project: Project;
  settings: SettingsState;
  add: ReturnType<typeof useAddProjectMembers>;
  workspaceId?: string;
  canEdit: boolean;
  canManage: boolean;
  isWorkspaceOwner: boolean;
  currentUserIsProjectOwner: boolean;
  onDeleteProject: (project: Project) => void;
  onRoleChange: (project: Project, member: ProjectMember, newRole: string) => void;
  onRemoveMember: (project: Project, member: ProjectMember) => void;
}

const tabClass = (active: boolean, danger = false) =>
  `flex-1 px-4 py-3 text-sm font-medium transition-colors flex items-center justify-center gap-2 ${active
      ? danger
        ? "text-red-600 border-b-2 border-red-600 bg-red-50"
        : "text-purple-600 border-b-2 border-purple-600 bg-purple-50"
      : "text-gray-600 hover:text-gray-900 hover:bg-gray-50"
    }`;

const ProjectSettingsModal: React.FC<ProjectSettingsModalProps> = ({
  project,
  settings,
  add,
  workspaceId,
  canEdit,
  canManage,
  isWorkspaceOwner,
  currentUserIsProjectOwner,
  onDeleteProject,
  onRoleChange,
  onRemoveMember,
}) => {
  const tab = settings.projectSettingsTab;
  const close = () => settings.setProjectSettingsModal(null);

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-xl shadow-2xl max-w-2xl w-full max-h-[85vh] overflow-hidden flex flex-col">
        <div className="flex items-center justify-between p-6 border-b">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-purple-100 rounded-full flex items-center justify-center">
              <FolderOpen size={20} className="text-purple-600" />
            </div>
            <div>
              <h3 className="text-xl font-semibold text-gray-900">Project Settings</h3>
              <p className="text-sm text-gray-500">{project.name}</p>
            </div>
          </div>
          <button onClick={close} className="p-2 hover:bg-gray-100 rounded-lg transition-colors">
            <X size={20} className="text-gray-500" />
          </button>
        </div>

        <div className="flex border-b">
          <button onClick={() => settings.setProjectSettingsTab("general")} className={tabClass(tab === "general")}>
            <FileText size={16} />
            General
          </button>
          {!isDesktop() && (
            <button onClick={() => settings.setProjectSettingsTab("members")} className={tabClass(tab === "members")}>
              <Users size={16} />
              Members ({project.members?.length || 0})
            </button>
          )}
          {canManage && (
            <button onClick={() => settings.setProjectSettingsTab("danger")} className={tabClass(tab === "danger", true)}>
              <AlertTriangle size={16} />
              Danger Zone
            </button>
          )}
        </div>

        <div className="flex-1 overflow-y-auto p-6">
          {tab === "general" && (
            <ProjectGeneralTab
              project={project}
              canEdit={canEdit}
              name={settings.editingProjectName}
              description={settings.editingProjectDescription}
              onNameChange={settings.setEditingProjectName}
              onDescriptionChange={settings.setEditingProjectDescription}
            />
          )}
          {!isDesktop() && tab === "members" && (
            <ProjectMembersTab
              project={project}
              workspaceId={workspaceId}
              canManage={canManage}
              isWorkspaceOwner={isWorkspaceOwner}
              currentUserIsProjectOwner={currentUserIsProjectOwner}
              add={add}
              onRoleChange={(member, newRole) => onRoleChange(project, member, newRole)}
              onRemoveMember={(member) => onRemoveMember(project, member)}
            />
          )}
          {tab === "danger" && <ProjectDangerTab onDelete={() => onDeleteProject(project)} />}
        </div>

        {tab === "general" && (
          <div className="flex items-center justify-end gap-3 p-6 border-t bg-gray-50">
            <button
              onClick={close}
              className="px-6 py-2 border border-gray-300 rounded-lg hover:bg-gray-100 font-medium transition-colors"
            >
              {!canEdit ? "Close" : "Cancel"}
            </button>
            {canEdit && (
              <button
                onClick={settings.handleSaveProjectSettings}
                disabled={settings.savingProject || !settings.editingProjectName.trim()}
                className="px-6 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 font-medium transition-colors disabled:opacity-50 flex items-center gap-2"
              >
                {settings.savingProject && <Loader2 size={16} className="animate-spin" />}
                <Save size={16} />
                Save Changes
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default ProjectSettingsModal;

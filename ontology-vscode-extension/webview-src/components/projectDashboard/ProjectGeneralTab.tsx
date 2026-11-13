import React from "react";
import { isDesktop } from "../../utils/desktop";
import type { Project } from "../../utils/projectDashboardTypes";

interface ProjectGeneralTabProps {
  project: Project;
  canEdit: boolean;
  name: string;
  description: string;
  onNameChange: (value: string) => void;
  onDescriptionChange: (value: string) => void;
}

const ProjectGeneralTab: React.FC<ProjectGeneralTabProps> = ({
  project,
  canEdit,
  name,
  description,
  onNameChange,
  onDescriptionChange,
}) => (
  <div className="space-y-6">
    <div>
      <label className="block text-sm font-medium text-gray-700 mb-2">Project Name</label>
      <input
        type="text"
        value={name}
        onChange={(e) => onNameChange(e.target.value)}
        className={`w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent ${!canEdit ? "bg-gray-100 cursor-not-allowed" : ""}`}
        placeholder="Enter project name"
        disabled={!canEdit}
      />
    </div>
    <div>
      <label className="block text-sm font-medium text-gray-700 mb-2">Description</label>
      <textarea
        value={description}
        onChange={(e) => onDescriptionChange(e.target.value)}
        rows={4}
        className={`w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent resize-none ${!canEdit ? "bg-gray-100 cursor-not-allowed" : ""}`}
        placeholder="Enter project description"
        disabled={!canEdit}
      />
    </div>
    <div className="bg-gray-50 rounded-lg p-4">
      <h4 className="text-sm font-medium text-gray-700 mb-2">Project Info</h4>
      <div className="grid grid-cols-2 gap-4 text-sm">
        <div>
          <span className="text-gray-500">Created:</span>
          <span className="ml-2 text-gray-900">{new Date(project.createdAt).toLocaleDateString()}</span>
        </div>
        <div>
          <span className="text-gray-500">Last Updated:</span>
          <span className="ml-2 text-gray-900">{new Date(project.updatedAt).toLocaleDateString()}</span>
        </div>
        <div>
          <span className="text-gray-500">Files:</span>
          <span className="ml-2 text-gray-900">{project.fileCount}</span>
        </div>
        {!isDesktop() && (
          <div>
            <span className="text-gray-500">Members:</span>
            <span className="ml-2 text-gray-900">{project.memberCount}</span>
          </div>
        )}
      </div>
    </div>
  </div>
);

export default ProjectGeneralTab;

import React from "react";
import { FolderOpen, Grid, List, Search } from "lucide-react";
import type { Project } from "../../utils/projectDashboardTypes";
import ProjectCard from "./ProjectCard";

interface ProjectsPanelProps {
  projects: Project[];
  searchQuery: string;
  onSearchChange: (value: string) => void;
  viewMode: "grid" | "list";
  onViewModeChange: (mode: "grid" | "list") => void;
  canCreateProjects: boolean;
  onCreateProject: () => void;
  openMenuProjectId: string | null;
  onOpenMenuChange: (projectId: string | null) => void;
  canManageProjectRow: (project: Project) => boolean;
  onSelectProject: (projectId: string, projectName: string) => void;
  onOpenSettings: (project: Project) => void;
  onRename: (projectId: string, currentName: string) => void;
  onDelete: (project: Project) => void;
}

const ProjectsPanel: React.FC<ProjectsPanelProps> = ({
  projects,
  searchQuery,
  onSearchChange,
  viewMode,
  onViewModeChange,
  canCreateProjects,
  onCreateProject,
  openMenuProjectId,
  onOpenMenuChange,
  canManageProjectRow,
  onSelectProject,
  onOpenSettings,
  onRename,
  onDelete,
}) => (
  <>
    <div className="flex justify-between items-center mb-6">
      <div className="flex-1 max-w-lg">
        <div className="relative">
          <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" size={20} />
          <input
            type="text"
            placeholder="Search projects..."
            value={searchQuery}
            onChange={(e) => onSearchChange(e.target.value)}
            className="w-full pl-10 pr-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
          />
        </div>
      </div>
      <div className="flex gap-2 ml-4">
        <button
          onClick={() => onViewModeChange("grid")}
          className={`p-2 rounded-lg ${viewMode === "grid" ? "bg-purple-100 text-purple-600" : "text-gray-600 hover:bg-gray-100"}`}
        >
          <Grid size={20} />
        </button>
        <button
          onClick={() => onViewModeChange("list")}
          className={`p-2 rounded-lg ${viewMode === "list" ? "bg-purple-100 text-purple-600" : "text-gray-600 hover:bg-gray-100"}`}
        >
          <List size={20} />
        </button>
      </div>
    </div>

    <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-xl font-semibold text-gray-900 flex items-center gap-2">
          <FolderOpen size={24} className="text-purple-600" />
          Projects
          <span className="text-sm font-normal text-gray-500">({projects.length})</span>
        </h2>
      </div>

      <div className="pr-2 custom-scrollbar">
        {projects.length === 0 ? (
          <div className="text-center py-12">
            <FolderOpen size={64} className="text-gray-300 mx-auto mb-4" />
            <p className="text-gray-500 mb-4">
              {searchQuery ? "No projects found matching your search" : "No projects yet"}
            </p>
            {!searchQuery && canCreateProjects && (
              <button
                onClick={onCreateProject}
                className="px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700"
              >
                Create Your First Project
              </button>
            )}
          </div>
        ) : (
          <div className={viewMode === "grid" ? "grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4" : "space-y-3"}>
            {projects.map((project) => (
              <ProjectCard
                key={project.id}
                project={project}
                viewMode={viewMode}
                menuOpen={openMenuProjectId === project.projectId}
                canManage={canManageProjectRow(project)}
                onSelect={() => onSelectProject(project.projectId, project.name)}
                onToggleMenu={() =>
                  onOpenMenuChange(openMenuProjectId === project.projectId ? null : project.projectId)
                }
                onOpenSettings={() => onOpenSettings(project)}
                onRename={() => onRename(project.projectId, project.name)}
                onDelete={() => onDelete(project)}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  </>
);

export default ProjectsPanel;

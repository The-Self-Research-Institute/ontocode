import React from "react";
import { Calendar, ChevronRight, Edit, FolderOpen, Lock, MoreVertical, Settings, Trash2, Users } from "lucide-react";
import { isDesktop } from "../../utils/desktop";
import type { Project } from "../../utils/projectDashboardTypes";

interface ProjectCardProps {
  project: Project;
  viewMode: "grid" | "list";
  menuOpen: boolean;
  canManage: boolean;
  onSelect: () => void;
  onToggleMenu: () => void;
  onOpenSettings: () => void;
  onRename: () => void;
  onDelete: () => void;
}

const ProjectCard: React.FC<ProjectCardProps> = ({
  project,
  viewMode,
  menuOpen,
  canManage,
  onSelect,
  onToggleMenu,
  onOpenSettings,
  onRename,
  onDelete,
}) => (
  <div
    onClick={() => {
      if (project.isPrivateRestricted) return;
      onSelect();
    }}
    className={`
                                        border rounded-lg p-4 transition-all flex
                                        ${project.isPrivateRestricted
        ? "border-gray-200 bg-gray-50 cursor-not-allowed opacity-70"
        : "border-gray-200 cursor-pointer hover:border-purple-400 hover:shadow-md"}
                                        ${viewMode === "list" ? "items-center" : "items-start"}
                                    `}
    title={project.isPrivateRestricted ? "Private project — you can rename or delete but cannot open it" : undefined}
  >
    <div className="flex-1 min-w-0">
      <h3 className="font-semibold text-gray-900 flex items-center gap-2 mb-2">
        {project.isPrivateRestricted
          ? <Lock size={20} className="text-gray-400 flex-shrink-0" />
          : <FolderOpen size={20} className="text-purple-600 flex-shrink-0" />}
        <span className="truncate">{project.name}</span>
        {project.isPrivateRestricted && (
          <span className="ml-1 text-xs text-gray-400 font-normal">(private)</span>
        )}
      </h3>
      <p className="text-sm text-gray-600 mb-3 line-clamp-2">{project.description || "No description"}</p>
      <div className="flex items-center gap-4 text-xs text-gray-500">
        {!isDesktop() && (
          <span className="flex items-center gap-1">
            <Users size={14} />
            {project.memberCount} members
          </span>
        )}
        <span className="flex items-center gap-1">
          <FolderOpen size={14} />
          {project.fileCount} files
        </span>
        <span className="flex items-center gap-1">
          <Calendar size={14} />
          {new Date(project.updatedAt).toLocaleDateString()}
        </span>
      </div>
    </div>
    <div className="flex items-center gap-2 flex-shrink-0 ml-2">
      <div className="relative">
        <button
          onClick={(e) => {
            e.stopPropagation();
            onToggleMenu();
          }}
          className="p-1 hover:bg-gray-100 rounded"
        >
          <MoreVertical size={16} className="text-gray-400" />
        </button>
        {menuOpen && (
          <div className="absolute right-0 mt-1 w-48 bg-white border border-gray-200 rounded-lg shadow-lg z-10">
            {!project.isPrivateRestricted && (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onOpenSettings();
                }}
                className="w-full px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50 flex items-center gap-2"
              >
                <Settings size={14} />
                Project Settings
              </button>
            )}
            {canManage && (
              <>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    onRename();
                  }}
                  className="w-full px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50 flex items-center gap-2"
                >
                  <Edit size={14} />
                  Rename
                </button>
                <div className="border-t border-gray-100 my-1"></div>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    onDelete();
                  }}
                  className="w-full px-4 py-2 text-left text-sm text-red-600 hover:bg-red-50 flex items-center gap-2"
                >
                  <Trash2 size={14} />
                  Delete Project
                </button>
              </>
            )}
          </div>
        )}
      </div>
      {viewMode === "list" && !project.isPrivateRestricted && <ChevronRight size={20} className="text-gray-400" />}
    </div>
  </div>
);

export default ProjectCard;

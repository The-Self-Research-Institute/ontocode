import React from "react";
import { AlertTriangle, Trash2 } from "lucide-react";

const ProjectDangerTab: React.FC<{ onDelete: () => void }> = ({ onDelete }) => (
  <div className="space-y-6">
    <div className="bg-red-50 border border-red-200 rounded-lg p-4">
      <h4 className="text-lg font-semibold text-red-800 mb-2 flex items-center gap-2">
        <AlertTriangle size={20} />
        Danger Zone
      </h4>
      <p className="text-sm text-red-700 mb-4">
        Actions in this section are irreversible. Please be certain before proceeding.
      </p>
      <div className="space-y-4">
        <div className="flex items-center justify-between p-4 bg-white border border-red-200 rounded-lg">
          <div>
            <h5 className="font-medium text-gray-900">Delete this project</h5>
            <p className="text-sm text-gray-500">Once deleted, all files and data will be permanently removed.</p>
          </div>
          <button
            onClick={onDelete}
            className="px-4 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 transition-colors flex items-center gap-2"
          >
            <Trash2 size={16} />
            Delete Project
          </button>
        </div>
      </div>
    </div>
  </div>
);

export default ProjectDangerTab;

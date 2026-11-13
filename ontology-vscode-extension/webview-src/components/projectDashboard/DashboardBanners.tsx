import React from "react";
import { CheckCircle, FileText, FolderOpen } from "lucide-react";

interface DashboardBannersProps {
  pendingFile?: { fileName: string; fileContent: string; fileSize: number } | null;
  onOpenLocalFile?: () => void;
}

const DashboardBanners: React.FC<DashboardBannersProps> = ({ pendingFile, onOpenLocalFile }) => (
  <>
    {!pendingFile && onOpenLocalFile && (
      <div className="bg-gradient-to-r from-indigo-600 to-blue-600 text-white px-4 py-3 flex-shrink-0">
        <div className="max-w-7xl mx-auto flex items-center justify-between">
          <div className="flex items-center gap-3">
            <FolderOpen size={22} className="flex-shrink-0" />
            <div>
              <p className="font-semibold text-sm">Open a local ontology file</p>
              <p className="text-xs text-indigo-100">Pick a .owl / .rdf / .ttl file from your computer</p>
            </div>
          </div>
          <button
            onClick={onOpenLocalFile}
            className="px-4 py-1.5 bg-white text-indigo-700 font-semibold text-sm rounded-lg hover:bg-indigo-50 transition-colors"
          >
            Browse…
          </button>
        </div>
      </div>
    )}

    {pendingFile && (
      <div className="bg-gradient-to-r from-purple-600 to-blue-600 text-white px-4 py-3 flex-shrink-0">
        <div className="max-w-7xl mx-auto flex items-center justify-between">
          <div className="flex items-center gap-3">
            <FileText size={24} className="flex-shrink-0" />
            <div>
              <p className="font-semibold text-sm">📎 File Ready to Upload: {pendingFile.fileName}</p>
              <p className="text-xs text-purple-100">
                Select a project below to upload this file ({(pendingFile.fileSize / (1024 * 1024)).toFixed(2)} MB)
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <CheckCircle size={20} className="text-green-300 animate-pulse" />
          </div>
        </div>
      </div>
    )}
  </>
);

export default DashboardBanners;

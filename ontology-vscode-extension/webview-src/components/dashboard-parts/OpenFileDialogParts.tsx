import React from "react";
import { FileText, FolderOpen, Plus, Trash2 } from "lucide-react";
import type { FileInfo } from "./dashboardUtils";

interface OpenFileDialogFileRowProps {
  file: FileInfo;
  fileProjectId: string;
  isActive: boolean;
  isSharedFile: boolean;
  isPlanExpired?: boolean;
  usingProjectFiles: boolean;
  onOpen: () => void;
  onDeleteFile?: (projectId: string, fileName: string) => void;
}

export const OpenFileDialogFileRow: React.FC<OpenFileDialogFileRowProps> = ({
  file,
  fileProjectId,
  isActive,
  isSharedFile,
  isPlanExpired,
  usingProjectFiles,
  onOpen,
  onDeleteFile,
}) => (
  <div
    onClick={onOpen}
    className={`flex items-center gap-3 p-2 px-3 rounded-md transition-all ${isPlanExpired
        ? "opacity-50 cursor-not-allowed"
        : isActive
          ? "selected cursor-pointer"
          : "hover-overlay border border-transparent cursor-pointer"
      }`}
  >
    <FileText size={18} className={isSharedFile ? "text-blue-500" : "text-accent"} />
    <div className="flex-1 min-w-0">
      <div className="flex items-center gap-2">
        <span className="text-xs font-medium text-gray-900 truncate">{file.filename}</span>
        {isActive && (
          <span className="px-1.5 py-0.5 bg-green-100 text-green-700 text-[10px] font-semibold rounded">
            ACTIVE
          </span>
        )}
      </div>
    </div>
    {!usingProjectFiles && onDeleteFile && fileProjectId && !isSharedFile && (
      <button
        onClick={(event) => {
          event.stopPropagation();
          onDeleteFile(fileProjectId, file.filename);
        }}
        className="text-red-500 hover:text-red-700 p-1 rounded hover:bg-red-50"
        title="Delete file"
      >
        <Trash2 size={14} />
      </button>
    )}
  </div>
);

interface OpenFileDialogActionsProps {
  canOpenLocalFile: boolean;
  isPlanExpired?: boolean;
  onCreate: () => void;
  onOpenLocal: () => void;
}

export const OpenFileDialogActions: React.FC<OpenFileDialogActionsProps> = ({
  canOpenLocalFile,
  isPlanExpired,
  onCreate,
  onOpenLocal,
}) => (
  <div className="p-3 border-t space-y-2" style={{ borderColor: "var(--color-border)" }}>
    <button
      onClick={onCreate}
      disabled={!canOpenLocalFile || isPlanExpired}
      className="w-full flex items-center justify-center gap-2 px-3 py-2 text-xs rounded-md border hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
      style={{
        borderColor: "var(--color-border)",
        color: "var(--color-text)",
      }}
    >
      <Plus size={14} />
      Create New File
    </button>
    <button
      onClick={onOpenLocal}
      disabled={!canOpenLocalFile || isPlanExpired}
      className="w-full flex items-center justify-center gap-2 px-3 py-2 text-xs rounded-md border hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
      style={{
        borderColor: "var(--color-border)",
        color: "var(--color-text)",
      }}
    >
      <FolderOpen size={14} />
      Open Local File...
    </button>
  </div>
);

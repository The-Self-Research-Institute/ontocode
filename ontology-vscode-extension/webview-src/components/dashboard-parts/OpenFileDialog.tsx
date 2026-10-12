import React, { useState, useEffect } from "react";
import { Search, RefreshCw, AlertTriangle } from "lucide-react";
import apiClient from "../../services/apiClient";
import { notificationService } from "../../services/notificationService";
import { isDesktop } from "../../utils/desktop";
import { PromptDialog } from "./DashboardDialogs";
import type { FileInfo } from "./dashboardUtils";
import { buildEmptyOntologyRdfXml } from "./newOntologyTemplate";
import { OpenFileDialogActions, OpenFileDialogFileRow } from "./OpenFileDialogParts";

export const OpenFileDialog = ({
  isOpen,
  onClose,
  myFiles,
  sharedFiles,
  currentProjectId,
  currentFileId,
  currentFileName,
  onDeleteFile,
  onSwitchFile,
  parentProjectId,
  onLoadProjectFile,
  projectFiles,
  importMode,
  partitionStrategy,
  onImportModeChange,
  onPartitionStrategyChange,
  isWorkspaceMode,
  onRefresh,
  onCreateNewFile,
  isPlanExpired,
}: {
  isOpen: boolean;
  onClose: () => void;
  myFiles: FileInfo[];
  sharedFiles: FileInfo[];
  currentProjectId: string | null;
  currentFileId?: string | null;
  currentFileName?: string | null;
  onDeleteFile?: (projectId: string, fileName: string) => void;
  onSwitchFile: (projectId: string) => void;
  parentProjectId?: string;
  onLoadProjectFile?: (fileId: string, fileName: string) => void;
  projectFiles?: FileInfo[];
  importMode: "full" | "incremental" | "diff";
  partitionStrategy: "none" | "namespace";
  onImportModeChange: (mode: "full" | "incremental" | "diff") => void;
  onPartitionStrategyChange: (strategy: "none" | "namespace") => void;
  isWorkspaceMode?: boolean;
  onRefresh?: () => void;
  onCreateNewFile?: () => void;
  isPlanExpired?: boolean;
}) => {
  const [searchQuery, setSearchQuery] = useState("");
  const [showNewFileNamePrompt, setShowNewFileNamePrompt] = useState(false);
  const canOpenLocalFile = typeof window !== "undefined" && !!(window as any).vscode;
  const usingProjectFiles = !!parentProjectId;
  const NEW_FILE_VALID_EXTENSIONS = [".owl", ".rdf", ".ttl", ".n3", ".nt", ".jsonld"];

  const primaryFiles = usingProjectFiles ? projectFiles || [] : myFiles;
  const secondaryFiles = usingProjectFiles ? [] : sharedFiles;

  const handleOpenLocalFile = () => {
    if (!canOpenLocalFile || !window.vscode) {
      return;
    }
    window.vscode.postMessage({
      type: "openLocalFile",
      projectId: parentProjectId || undefined,
      importMode,
      partition: partitionStrategy,
    });
    onClose();
  };

  const handleCreateNewFile = async () => {
    if (isDesktop() && parentProjectId) {
      setShowNewFileNamePrompt(true);
      return;
    }
    if (isDesktop()) {
      const baseName = "my-ontology.owl";
      const ontologyIRI = `http://example.org/ontologies/my-ontology`;
      const content = buildEmptyOntologyRdfXml(ontologyIRI);
      const api = (window as any).electronAPI;
      if (!api?.saveAs) return;
      const savedPath = await api.saveAs(content, baseName);
      if (!savedPath) return;
      const fileName = savedPath.split(/[\\/]/).pop() || baseName;
      const fileContent = content;
      window.dispatchEvent(new CustomEvent("electron:file-opened", {
        detail: { fileName, fileContent, filePath: savedPath, fileSize: fileContent.length }
      }));
      onCreateNewFile?.();
      onClose();
      return;
    }
    if (!canOpenLocalFile || !window.vscode) {
      return;
    }

    onCreateNewFile?.();
    window.vscode.postMessage({
      type: "createNewFile",
      projectId: parentProjectId || undefined,
      importMode,
      partition: partitionStrategy,
    });
    onClose();
  };

  const handleConfirmNewFileName = async (trimmed: string) => {
    setShowNewFileNamePrompt(false);
    const ontologyIRI = `http://example.org/ontologies/${trimmed.replace(/\.[^/.]+$/, "")}`;
    const content = buildEmptyOntologyRdfXml(ontologyIRI);
    const file = new File([content], trimmed, { type: "application/rdf+xml" });
    const formData = new FormData();
    formData.append("file", file, trimmed);
    formData.append("fileName", trimmed);
    formData.append("fileType", "application/rdf+xml");
    let uploadedFileId: string | undefined;
    try {
      const uploadResult = await apiClient.post<{ fileId?: string; filename?: string }>(
        `/api/projects/${parentProjectId}/files`,
        formData,
        { headers: { "Content-Type": "multipart/form-data" } },
      );
      uploadedFileId = uploadResult?.fileId;
    } catch (error: any) {
      console.error("[OpenFileDialog] Failed to create new file:", error);
      notificationService.error(
        "Create File Failed",
        error?.response?.data?.error || error?.message || "Could not create the new file. See console for details.",
      );
      return;
    }
    onCreateNewFile?.();
    if (uploadedFileId && onLoadProjectFile) {
      onLoadProjectFile(uploadedFileId, trimmed);
    }
    onClose();
  };

  if (!isOpen) return null;

  const allFiles = [...primaryFiles, ...secondaryFiles];
  const filteredFiles = searchQuery
    ? allFiles.filter((f) => f.filename.toLowerCase().includes(searchQuery.toLowerCase()))
    : allFiles;

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) onClose();
      }}
    >
      <div
        className="bg-theme-surface rounded-lg shadow-2xl w-full max-w-md mx-4 max-h-[70vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="p-4 border-b" style={{ borderColor: "var(--color-border)" }}>
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-sm font-medium" style={{ color: "var(--color-text)" }}>
              {usingProjectFiles ? `Project Files (${filteredFiles.length})` : "Open File"}
            </h3>
          </div>
          <div className="flex items-center gap-2">
            <div className="relative flex-1">
              <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                type="text"
                placeholder="Search files..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                autoFocus
                className="w-full pl-10 pr-3 py-2 border rounded-lg focus:ring-2 text-sm"
                style={
                  {
                    borderColor: "var(--color-border)",
                    backgroundColor: "var(--color-surface)",
                    color: "var(--color-text)",
                    "--tw-ring-color": "var(--color-primary)",
                  } as React.CSSProperties
                }
              />
            </div>
            {usingProjectFiles && onRefresh && (
              <button
                onClick={onRefresh}
                className="p-2 rounded-md border hover:bg-gray-50 transition-colors"
                style={{
                  borderColor: "var(--color-border)",
                  color: "var(--color-text)",
                }}
                title="Refresh file list"
              >
                <RefreshCw size={16} />
              </button>
            )}
          </div>
        </div>
        {isPlanExpired && (
          <div className="mx-3 mt-3 px-3 py-2 rounded-lg border border-red-400/30 bg-red-500/10 flex items-center gap-2 text-xs text-red-400">
            <AlertTriangle size={13} className="flex-shrink-0" />
            <span>Plan validity has ended. Please renew your subscription to open files.</span>
          </div>
        )}
        <div className="flex-1 overflow-y-auto">
          {filteredFiles.length > 0 ? (
            <div className="p-3">
              <div className="space-y-0.5">
                {filteredFiles.map((file) => {
                  const fileProjectId =
                    file.projectId || file.id || (file.filename ? file.filename.replace(/\.[^/.]+$/, "") : "");
                  const isActiveById = currentFileId ? file.id === currentFileId : false;
                  const isActiveByName = currentFileName ? file.filename === currentFileName : false;
                  const isActiveByProjectId = currentProjectId
                    ? fileProjectId === currentProjectId || file.filename === currentProjectId
                    : false;
                  const isActive = isActiveById || isActiveByName || isActiveByProjectId;
                  const isSharedFile = sharedFiles.some((sf) => sf.id === file.id);

                  return (
                    <OpenFileDialogFileRow
                      key={file.id}
                      file={file}
                      fileProjectId={fileProjectId}
                      isActive={isActive}
                      isSharedFile={isSharedFile}
                      isPlanExpired={isPlanExpired}
                      usingProjectFiles={usingProjectFiles}
                      onOpen={() => {
                        if (isPlanExpired) return;
                        if (!isActive) {
                          if (parentProjectId && onLoadProjectFile) {
                            onLoadProjectFile(file.id, file.filename);
                          } else {
                            onSwitchFile(fileProjectId);
                          }
                        }
                        onClose();
                      }}
                      onDeleteFile={onDeleteFile}
                    />
                  );
                })}
              </div>
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center py-12 text-gray-400">
              <Search size={40} className="mb-3 opacity-30" />
              <p className="text-base font-medium text-gray-600 mb-1">No files found</p>
              <p className="text-xs text-gray-500 max-w-xs text-center">
                {searchQuery
                  ? `No files match "${searchQuery}". Try a different search.`
                  : "Upload or open a local file to get started."}
              </p>
            </div>
          )}
        </div>
        <OpenFileDialogActions
          canOpenLocalFile={canOpenLocalFile}
          isPlanExpired={isPlanExpired}
          onCreate={handleCreateNewFile}
          onOpenLocal={handleOpenLocalFile}
        />
      </div>
      <PromptDialog
        isOpen={showNewFileNamePrompt}
        title="New Ontology File"
        message="Enter a filename for the new ontology."
        defaultValue="my-ontology.owl"
        confirmLabel="Create"
        validate={(value) =>
          NEW_FILE_VALID_EXTENSIONS.some((ext) => value.toLowerCase().endsWith(ext))
            ? null
            : "File must have a valid extension: .owl, .rdf, .ttl, .n3, .nt, or .jsonld"
        }
        onConfirm={handleConfirmNewFileName}
        onCancel={() => setShowNewFileNamePrompt(false)}
      />
    </div>
  );
};

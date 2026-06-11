import React, { useState, useEffect, useRef } from "react";
import {
  ArrowLeft,
  Upload,
  FileText,
  Search,
  Grid,
  List,
  MoreVertical,
  Download,
  Trash2,
  Clock,
  User,
  Folder,
  Code2,
  Plus,
  Bug,
  Loader2,
  CheckCircle2,
  XCircle,
  FolderOpen,
} from "lucide-react";
import apiClient from "../services/apiClient";
import { useAuth } from "../custom-hook/useAuth";
import { isDesktop } from "../utils/desktop";
import { isAppOnline } from "../utils/connectivity";
import ReportIssueModal from "./ReportIssueModal";

interface ProjectLibraryProps {
  projectId: string;
  projectName: string;
  onBack: () => void;
  onFileSelect: (fileId: string, fileName: string) => void;
  onOpenEditor?: () => void;
}

interface FileItem {
  id: string;
  name: string;
  size: number;
  uploadedBy: string;
  uploadedByUserId: string;
  uploadedAt: string;
  type: string;
}

const ProjectLibrary: React.FC<ProjectLibraryProps> = ({
  projectId,
  projectName,
  onBack,
  onFileSelect,
  onOpenEditor,
}) => {
  const isMountedRef = useRef(true);
  const [files, setFiles] = useState<FileItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState("");
  const [viewMode, setViewMode] = useState<"grid" | "list">("grid");
  const [uploading, setUploading] = useState(false);
  const [isReportIssueModalOpen, setIsReportIssueModalOpen] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [processingFile, setProcessingFile] = useState<string | null>(null);
  const [selectedFile, setSelectedFile] = useState<string | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<{ show: boolean; fileId: string; fileName: string }>({
    show: false,
    fileId: "",
    fileName: "",
  });
  const [toast, setToast] = useState<{ show: boolean; message: string; type: "success" | "error" }>({
    show: false,
    message: "",
    type: "success",
  });
  const [showFreePlanDialog, setShowFreePlanDialog] = useState(false);
  const [showOwnerMustImportDialog, setShowOwnerMustImportDialog] = useState(false);
  const [openMenuFileId, setOpenMenuFileId] = useState<string | null>(null);
  const [userProjectRole, setUserProjectRole] = useState<string>("VIEWER");
  const [workspaceOwnerId, setWorkspaceOwnerId] = useState<string | null>(null);
  const [workspacePlan, setWorkspacePlan] = useState<string>("FREE");
  // Refs mirror the above state so async handlers always read the latest values.
  const workspaceOwnerIdRef = useRef<string | null>(null);
  const workspacePlanRef = useRef<string>("FREE");
  const workspaceLoadedRef = useRef<boolean>(false);
  const [storageUsage, setStorageUsage] = useState<{
    usedMB: string; limitGB: number; usagePercent: string; planName: string;
  } | null>(null);
  const { user } = useAuth();

  type FileImportState = { status: 'IMPORTING' | 'COMPLETED' | 'FAILED'; progress: number; message: string; graphSize?: number };
  const [fileImportStates, setFileImportStates] = useState<Record<string, FileImportState>>({});
  const importPollingRefs = useRef<Record<string, ReturnType<typeof setInterval>>>({});

  const loadStorageUsage = async () => {
    const workspaceId = user?.workspaceId;
    const query = workspaceId ? `?workspaceId=${encodeURIComponent(workspaceId)}` : "";
    try {
      const res: any = await apiClient.get(`/api/projects/storage-usage${query}`);
      const d = res?.data || res;
      setStorageUsage({
        usedMB: d.usedMB,
        limitGB: d.limitGB,
        usagePercent: d.usagePercent,
        planName: d.planName,
      });
    } catch {
      // Non-blocking
    }
  };

  const startFileImport = async (file: FileItem, { skipPost = false } = {}) => {
    const ontologyProjectId = `${projectId}--${file.id}`;

    setFileImportStates(prev => ({
      ...prev,
      [file.id]: { status: 'IMPORTING', progress: 0, message: skipPost ? 'Import in progress…' : 'Starting import…' },
    }));

    if (!skipPost) {
      try {
        await apiClient.post(
          `/api/ontology/upload-by-file-ref/${encodeURIComponent(ontologyProjectId)}`,
          null,
          {
            params: {
              fileId: file.id,
              parentProjectId: projectId,
              ownerEmail: user?.email || '',
              workspaceId: user?.workspaceId || '',
              action: 'replace',
            },
          },
        );
      } catch (err: any) {
        if (err?.status !== 202 && err?.status !== 200) {
          // POST failed — the file may already be importing (e.g. auto-triggered after upload).
          // Don't give up: poll the status API to discover actual import state.
          console.warn('[ProjectLibrary] Import trigger returned', err?.status, '— polling to check existing import status');
        }
      }
    }

    const pollStatus = async () => {
      try {
        const res: any = await apiClient.get(`/api/ontology/status/${encodeURIComponent(ontologyProjectId)}`);
        // API returns { success: true, data: { status, ... } }; axios wraps that in res.data.
        // Unwrap both layers so we reach the actual status fields.
        const envelope = res?.data || res;
        const data = envelope?.data || envelope;
        const status = data?.status || data?.state || null;
        const progress = typeof data?.progress === 'number' ? data.progress : 0;
        const message = data?.statusMessage || data?.message || (data?.metadata && typeof data.metadata.message === 'string' ? data.metadata.message : '') || '';
        const graphSize = typeof data?.graphSize === 'number' ? data.graphSize : undefined;

        if (!isMountedRef.current) return;

        const formatTriples = (n: number) =>
          n >= 1_000_000 ? `${(n / 1_000_000).toFixed(1)}M triples` : `${n.toLocaleString()} triples`;

        if (status === 'COMPLETED') {
          clearInterval(importPollingRefs.current[file.id]);
          delete importPollingRefs.current[file.id];
          const doneMsg = graphSize && graphSize > 0 ? `${formatTriples(graphSize)} — ready` : 'Ready to open';
          setFileImportStates(prev => ({
            ...prev,
            [file.id]: { status: 'COMPLETED', progress: 100, message: doneMsg, graphSize },
          }));
        } else if (status === 'ERROR' || status === 'FAILED') {
          clearInterval(importPollingRefs.current[file.id]);
          delete importPollingRefs.current[file.id];
          setFileImportStates(prev => ({
            ...prev,
            [file.id]: { status: 'FAILED', progress: 0, message: 'Import failed', graphSize },
          }));
        } else if (status) {
          const importingMsg = graphSize && graphSize > 0
            ? `${formatTriples(graphSize)} loaded…`
            : message || 'Importing…';
          setFileImportStates(prev => ({
            ...prev,
            [file.id]: { status: 'IMPORTING', progress, message: importingMsg, graphSize },
          }));
        }
      } catch {
        // Network hiccup — keep polling
      }
    };

    await pollStatus();
    if (importPollingRefs.current[file.id]) clearInterval(importPollingRefs.current[file.id]);
    importPollingRefs.current[file.id] = setInterval(pollStatus, 3000);
  };

  const showToast = (message: string, type: "success" | "error" = "success") => {
    setToast({ show: true, message, type });
    setTimeout(() => setToast({ show: false, message: "", type: "success" }), 3000);
  };

  const handleCreateNewFile = () => {
    console.log("[ProjectLibrary] 📝 Creating new file for project:", projectId);
    if (isDesktop()) {
      const fileName = window.prompt("Enter filename for new ontology:", "my-ontology.owl");
      if (!fileName?.trim()) return;
      const trimmed = fileName.trim();
      const validExtensions = [".owl", ".rdf", ".ttl", ".n3", ".nt", ".jsonld"];
      if (!validExtensions.some((ext) => trimmed.toLowerCase().endsWith(ext))) {
        showToast("File must have a valid extension: .owl, .rdf, .ttl, .n3, .nt, or .jsonld", "error");
        return;
      }
      const ontologyIRI = `http://example.org/ontologies/${trimmed.replace(/\.[^/.]+$/, "")}`;
      const content = `<?xml version="1.0"?>
<rdf:RDF xmlns="${ontologyIRI}#"
     xml:base="${ontologyIRI}"
     xmlns:owl="http://www.w3.org/2002/07/owl#"
     xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
     xmlns:xml="http://www.w3.org/XML/1998/namespace"
     xmlns:xsd="http://www.w3.org/2001/XMLSchema#"
     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
    <owl:Ontology rdf:about="${ontologyIRI}"/>
    <owl:Class rdf:about="http://www.w3.org/2002/07/owl#Thing"/>
</rdf:RDF>`;
      const file = new File([content], trimmed, { type: "application/rdf+xml" });
      performUpload(file);
      return;
    }
    if (window.vscode) {
      window.vscode.postMessage({
        type: "createNewFile",
        projectId: projectId,
      });
    }
  };

  useEffect(() => {
    loadFiles().then(async (filesArray) => {
      // After page refresh, resume progress cards for any imports still running on the backend.
      await Promise.all(filesArray.map(async (file: FileItem) => {
        const ontologyProjectId = `${projectId}--${file.id}`;
        try {
          const res: any = await apiClient.get(`/api/ontology/status/${encodeURIComponent(ontologyProjectId)}`);
          const envelope = res?.data || res;
          const status = envelope?.data?.status || envelope?.status;
          if (status === 'PROCESSING') {
            void startFileImport(file, { skipPost: true });
          }
        } catch {
          // Non-blocking — ignore failures for individual files
        }
      }));
    });
  }, [projectId]);

  useEffect(() => {
    loadStorageUsage();
  }, [user?.workspaceId]);

  useEffect(() => {
    if (!user?.workspaceId) {
      setWorkspaceOwnerId(null);
      workspaceOwnerIdRef.current = null;
      setWorkspacePlan("FREE");
      workspacePlanRef.current = "FREE";
      workspaceLoadedRef.current = true;
      return;
    }

    workspaceLoadedRef.current = false;
    apiClient
      .get(`/api/workspaces/${user.workspaceId}`)
      .then((response: any) => {
        const workspaceData = response?.data || response;
        const ownerId = workspaceData?.ownerId || null;
        const plan = String(workspaceData?.subscriptionPlan || "FREE").toUpperCase();
        setWorkspaceOwnerId(ownerId);
        workspaceOwnerIdRef.current = ownerId;
        setWorkspacePlan(plan);
        workspacePlanRef.current = plan;
        workspaceLoadedRef.current = true;
      })
      .catch(() => {
        const plan = (user?.subscriptionPlan || "FREE").toUpperCase();
        setWorkspaceOwnerId(null);
        workspaceOwnerIdRef.current = null;
        setWorkspacePlan(plan);
        workspacePlanRef.current = plan;
        workspaceLoadedRef.current = true;
      });
  }, [user?.workspaceId, user?.subscriptionPlan]);

  useEffect(() => {
    // Reset on every mount (including StrictMode's simulated remount) so async
    // callbacks like pollStatus don't see a stale false from the prior cleanup.
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      Object.values(importPollingRefs.current).forEach(clearInterval);
    };
  }, []);

  // Listen for file import completion messages from extension
  useEffect(() => {
    const handleMessage = async (event: MessageEvent) => {
      const message = event.data;

      // Refetch files when import completes or file is ready
      if (message.type === "fileReady" || message.type === "importStatusUpdate") {
        console.log(
          "[ProjectLibrary] 📋 Received import completion message:",
          "projectId:",
          message.projectId,
          "uploadedFileId:",
          message.uploadedFileId,
          "uploadedFileName:",
          message.uploadedFileName,
        );

        // Check if the message is for this project
        if (message.projectId === projectId || message.status?.status === "COMPLETED") {
          console.log("[ProjectLibrary] 📋 Refetching files after import completion");
          console.log("[ProjectLibrary] 📋 Current files count before refresh:", files.length);

          // Retry mechanism to ensure newly uploaded file appears in list
          const fetchWithRetry = async (retries = 3, delay = 1000) => {
            for (let attempt = 1; attempt <= retries; attempt++) {
              console.log(`[ProjectLibrary] 📋 Fetch attempt ${attempt}/${retries}...`);
              const fetchedFiles = await loadFiles();

              // If we're looking for a specific uploaded file, verify it's in the list
              if (message.uploadedFileId || message.uploadedFileName) {
                let found = false;

                // First try to match by fileId if available
                if (message.uploadedFileId) {
                  found = fetchedFiles.some((f) => f.id === message.uploadedFileId);
                  console.log(
                    `[ProjectLibrary] 📋 Looking for file ID ${message.uploadedFileId} in ${fetchedFiles.length} files, found: ${found}`,
                  );
                }

                // If not found by ID or no ID, try to match by filename
                if (!found && message.uploadedFileName) {
                  const normalizedTarget = message.uploadedFileName.toLowerCase();
                  const matchedFile = fetchedFiles.find((f) => f.name.toLowerCase() === normalizedTarget);
                  found = !!matchedFile;
                  console.log(
                    `[ProjectLibrary] 📋 Looking for filename "${message.uploadedFileName}" in ${fetchedFiles.length} files, found: ${found}`,
                  );
                  if (matchedFile) {
                    console.log(`[ProjectLibrary] 📋 Matched file by name - ID: ${matchedFile.id}`);
                  } else {
                    console.log(
                      `[ProjectLibrary] 📋 Available filenames:`,
                      fetchedFiles.map((f) => f.name),
                    );
                  }
                }

                if (found) {
                  console.log(`[ProjectLibrary] ✅ File found in list after ${attempt} attempt(s)!`);
                  return true;
                }

                if (attempt < retries) {
                  console.log(`[ProjectLibrary] ⏳ File not found, waiting ${delay}ms before retry ${attempt + 1}...`);
                  await new Promise((resolve) => setTimeout(resolve, delay));
                } else {
                  console.warn(
                    `[ProjectLibrary] ⚠️ File ${message.uploadedFileName || message.uploadedFileId} not found after ${retries} attempts`,
                  );
                  console.warn(`[ProjectLibrary] ⚠️ This may indicate a database synchronization delay`);
                  return false;
                }
              } else {
                // No specific file to look for, just refresh once
                console.log("[ProjectLibrary] ✅ File list refreshed (no specific file verification)");
                return true;
              }
            }
            return false;
          };

          fetchWithRetry().finally(() => {
            loadStorageUsage();
          });
        }
      }
    };

    window.addEventListener("message", handleMessage);
    return () => window.removeEventListener("message", handleMessage);
  }, [projectId]);

  // Close menu when clicking outside
  useEffect(() => {
    const handleClickOutside = () => setOpenMenuFileId(null);
    if (openMenuFileId) {
      document.addEventListener("click", handleClickOutside);
      return () => document.removeEventListener("click", handleClickOutside);
    }
  }, [openMenuFileId]);

  const loadFiles = async (): Promise<FileItem[]> => {
    try {
      setLoading(true);
      const response = await apiClient.get(`/api/projects/${projectId}/files`);
      console.log("[ProjectLibrary] Files response:", response);

      // Handle both response.data and response.data.files structures
      const fileList = response?.files || response?.data || [];
      console.log("[ProjectLibrary] Parsed file list:", fileList);

      if (response?.userProjectRole) {
        setUserProjectRole(response.userProjectRole);
      }

      const filesArray = Array.isArray(fileList) ? fileList : [];
      setFiles(filesArray);
      return filesArray;
    } catch (error) {
      console.error("Error loading files:", error);
      showToast("Failed to load files", "error");
      setFiles([]);
      return [];
    } finally {
      setLoading(false);
    }
  };

  const performUpload = async (file: File, replaceFileId?: string | null, overrideFileName?: string) => {
    try {
      setUploading(true);
      setUploadProgress(0);
      const targetFileName = overrideFileName || file.name;
      setProcessingFile(targetFileName);

      console.log(`[ProjectLibrary] Processing file: ${targetFileName} (${(file.size / (1024 * 1024)).toFixed(2)}MB)`);

      // For large files (>10MB), use chunked processing
      const isLargeFile = file.size > 10 * 1024 * 1024;

      if (isLargeFile) {
        showToast(`Processing large file: ${targetFileName}...`, "success");
      }

      setUploadProgress(10); // Starting upload

      console.log("[ProjectLibrary] Uploading file via multipart...");

      // Build multipart FormData — streams the file directly, no base64 encoding
      const formData = new FormData();
      formData.append("file", file, targetFileName);
      formData.append("fileName", targetFileName);
      formData.append("fileType", file.type || "application/rdf+xml");
      if (replaceFileId) {
        formData.append("replaceFileId", replaceFileId);
      }

      // Send as multipart/form-data
      const uploadResponse = await apiClient.post(`/api/projects/${projectId}/files`, formData, {
        timeout: 600000, // 10 minute timeout for very large files
        headers: {
          "Content-Type": "multipart/form-data",
        },
        onUploadProgress: (progressEvent) => {
          if (progressEvent.total) {
            const uploadPercent = Math.round((progressEvent.loaded / progressEvent.total) * 90);
            setUploadProgress(10 + uploadPercent); // 10-100% for uploading
          }
        },
      });

      console.log("[ProjectLibrary] Upload response:", uploadResponse);
      setUploadProgress(100);

      if (isLargeFile) {
        showToast(
          `Large file "${targetFileName}" ${replaceFileId ? "replaced" : "uploaded"} successfully! Processing in background...`,
          "success",
        );
      } else {
        showToast(
          replaceFileId ? "File replaced successfully" : `File "${targetFileName}" uploaded successfully`,
          "success",
        );
      }

      const responseData = (uploadResponse as any)?.data || uploadResponse;
      const uploadedFileId = responseData?.fileId || responseData?.id || null;

      // Upload bytes are on the server — clear the progress UI immediately so the
      // bar doesn't sit at 100% / "Uploading..." while loadFiles() refetches the list.
      setUploading(false);
      setUploadProgress(0);
      setProcessingFile(null);

      // Refresh file list in background, then auto-start import so the user
      // doesn't need a second click after the upload finishes.
      void loadFiles().then((files) => {
        if (uploadedFileId) {
          setSelectedFile(uploadedFileId);
          // Auto-import: find the newly uploaded file and kick off import immediately.
          const newFile = files?.find((f: FileItem) => f.id === uploadedFileId);
          if (newFile) {
            void startFileImport(newFile);
          }
        }
        return files;
      });
      void loadStorageUsage();
    } catch (error: any) {
      console.error("Error uploading file:", error);
      console.error("Error status:", error.status);
      console.error("Error data:", error.data);

      // Free plan restriction (cloud workspaces only)
      if (!isDesktop() && error.status === 403 && error.data?.requiresUpgrade) {
        setShowFreePlanDialog(true);
        return;
      }

      // Provide specific error messages
      // Note: apiClient transforms errors into ApiError with status and data properties (not response.status/response.data)
      let errorMessage = "Failed to upload file";
      if (error.code === "ECONNABORTED" || error.message?.includes("timeout")) {
        errorMessage = "Upload timeout. Please try a smaller file or check your connection.";
      } else if (error.status === 413) {
        // Storage limit exceeded or file too large
        const responseData = error.data;
        console.log("Storage limit response data:", responseData);
        if (responseData?.message) {
          errorMessage = responseData.message;
        } else if (responseData?.error) {
          errorMessage = responseData.error;
        } else {
          errorMessage = "Storage limit exceeded. Please upgrade your plan or delete existing files.";
        }
      } else if (error.data?.message) {
        errorMessage = error.data.message;
      } else if (error.data?.error) {
        errorMessage = error.data.error;
      } else if (error.message) {
        errorMessage = `Failed to upload file: ${error.message}`;
      }

      console.log("Final error message to display:", errorMessage);
      showToast(errorMessage, "error");
    } finally {
      if (!isMountedRef.current) {
        return;
      }
      setUploading(false);
      setUploadProgress(0);
      setProcessingFile(null);
    }
  };

  const handleFileUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    // Reset input so selecting same file again triggers change
    event.target.value = "";

    // Validate file size (max 1GB)
    const maxSize = 1024 * 1024 * 1024; // 1GB
    if (file.size > maxSize) {
      showToast(
        `File too large. Maximum size is 1GB. Your file is ${(file.size / (1024 * 1024)).toFixed(1)}MB`,
        "error",
      );
      return;
    }

    // Validate file type
    const validExtensions = [".owl", ".rdf", ".ttl", ".n3", ".nt", ".jsonld", ".zip"];
    const fileExtension = file.name.substring(file.name.lastIndexOf(".")).toLowerCase();
    if (!validExtensions.includes(fileExtension)) {
      showToast("Invalid file type. Only .owl, .rdf, .ttl, .n3, .nt, .jsonld, .zip files are allowed", "error");
      return;
    }

    // Check if file already exists in project
    try {
      console.log("[ProjectLibrary] Checking for duplicate file:", file.name);
      const checkResponse = await apiClient.get(
        `/api/projects/${projectId}/files/check?fileName=${encodeURIComponent(file.name)}`,
      );
      console.log("[ProjectLibrary] Duplicate check raw response:", JSON.stringify(checkResponse));

      const checkData = (checkResponse as any)?.data || checkResponse;
      console.log("[ProjectLibrary] Parsed check data:", JSON.stringify(checkData));

      if (checkData?.exists === true) {
        const existing = checkData.existingFile || {};
        const existingFileId = existing.fileId || existing.id || null;
        const existingFileName = existing.fileName || existing.name || file.name;

        console.log("[ProjectLibrary] Duplicate detected! File ID:", existingFileId, "Name:", existingFileName);
        console.log("[ProjectLibrary] Existing file object:", JSON.stringify(existing));

        if (existingFileId) {
          showToast(`File "${existingFileName}" already exists in this project.`, "error");
          return; // Stop upload process — stay on ProjectLibrary
        } else {
          console.error("[ProjectLibrary] Duplicate exists but no file ID found. Full response:", checkData);
          showToast(`File "${file.name}" already exists but cannot be opened. Please contact support.`, "error");
          return; // Stop upload process
        }
      }

      console.log("[ProjectLibrary] No duplicate found (exists=" + checkData?.exists + "), proceeding with upload");
    } catch (error: any) {
      // If check fails, log detailed error but continue with upload for backward compatibility console.error("[ProjectLibrary] Duplicate check failed with error:", error);
      console.error("[ProjectLibrary] Error details:", error?.message, error?.status, error?.data);
      showToast("Unable to check for duplicates. Proceeding with upload...", "error");
    }

    await performUpload(file);
  };

  const handleFileClick = async (file: FileItem) => {
    setSelectedFile(file.id);

    // Cloud-only: on FREE plan, non-owners must wait until the workspace owner
    // has opened/imported the file into GraphDB. Desktop is single-user local —
    // no teams, no owner/member distinction (see buildDesktopUser vs Mongo owner id).
    if (!isDesktop()) {
      // Wait for workspace data before deciding on plan/owner restrictions.
      // Clicking before the async workspace API returns would wrongly treat the user
      // as a "free non-owner" (workspaceOwnerId is null until the API responds).
      if (!workspaceLoadedRef.current) {
        await new Promise<void>((resolve) => {
          const check = setInterval(() => {
            if (workspaceLoadedRef.current) { clearInterval(check); resolve(); }
          }, 100);
          setTimeout(() => { clearInterval(check); resolve(); }, 3000);
        });
      }

      const effectivePlan = (workspacePlanRef.current || user?.subscriptionPlan || "FREE").toUpperCase();
      const isWorkspaceOwner =
        !!workspaceOwnerIdRef.current &&
        !!user?.userId &&
        workspaceOwnerIdRef.current === user.userId;

      if (effectivePlan === "FREE" && !isWorkspaceOwner) {
        try {
          const ontologyProjectId = `${projectId}--${file.id}`;
          const graphCheck: any = await apiClient.get(
            `/api/ontology/${encodeURIComponent(ontologyProjectId)}/graphdb/check?fileName=${encodeURIComponent(file.name)}&fileId=${encodeURIComponent(file.id)}`,
          );
          const graphData = graphCheck?.data || graphCheck;
          if (!graphData?.exists || (graphData.graphSize ?? 0) <= 0) {
            setShowOwnerMustImportDialog(true);
            return;
          }
        } catch {
          setShowOwnerMustImportDialog(true);
          return;
        }
        // Falls through to onFileSelect if already in GraphDB
      } else {
        // Owner or paid plan: show import card for files not yet in GraphDB

        // If we're already tracking this file's import, handle state transitions
        const existingImport = fileImportStates[file.id];
        if (existingImport) {
          if (existingImport.status === 'COMPLETED') {
            onFileSelect(file.id, file.name);
          } else if (existingImport.status === 'FAILED') {
            // Retry the import — previous attempt may have conflicted with an auto-triggered import
            await startFileImport(file);
          }
          // IMPORTING: do nothing, card already shows the state
          return;
        }

        // Check GraphDB — only navigate immediately if already imported
        try {
          const ontologyProjectId = `${projectId}--${file.id}`;
          const graphCheck: any = await apiClient.get(
            `/api/ontology/${encodeURIComponent(ontologyProjectId)}/graphdb/check?fileName=${encodeURIComponent(file.name)}&fileId=${encodeURIComponent(file.id)}`,
          );
          const graphData = graphCheck?.data || graphCheck;
          if (!graphData?.exists || (graphData.graphSize ?? 0) <= 0) {
            await startFileImport(file);
            return;
          }
        } catch {
          // Can't check — fall through to normal open
        }
      }
    }

    onFileSelect(file.id, file.name);
  };

  const handleFileDoubleClick = (file: FileItem) => {
    void handleFileClick(file);
  };

  const handleDeleteFile = async (fileId: string, fileName: string) => {
    setDeleteConfirm({ show: true, fileId, fileName });
  };

  const confirmDelete = async () => {
    const fileId = deleteConfirm.fileId;
    setDeleteConfirm({ show: false, fileId: "", fileName: "" });

    try {
      await apiClient.delete(`/api/projects/${projectId}/files/${fileId}`);
      showToast("File deleted successfully", "success");
      await loadFiles();
      await loadStorageUsage();
    } catch (error) {
      console.error("Error deleting file:", error);
      showToast("Failed to delete file", "error");
    }
  };

  const cancelDelete = () => {
    setDeleteConfirm({ show: false, fileId: "", fileName: "" });
  };

  const formatFileSize = (bytes: number) => {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
    return (bytes / (1024 * 1024)).toFixed(1) + " MB";
  };

  const formatDate = (dateString: string) => {
    const date = new Date(dateString);
    const now = new Date();
    const diff = now.getTime() - date.getTime();
    const days = Math.floor(diff / (1000 * 60 * 60 * 24));

    if (days === 0) return "Today";
    if (days === 1) return "Yesterday";
    if (days < 7) return `${days} days ago`;
    return date.toLocaleDateString();
  };

  const filteredFiles = files.filter((file) => file.name.toLowerCase().includes(searchQuery.toLowerCase()));

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <div className="bg-white border-b sticky top-0 z-10">
        <div className="max-w-7xl mx-auto px-6 py-4">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-4">
              <button onClick={onBack} className="p-2 hover:bg-gray-100 rounded-lg transition-colors">
                <ArrowLeft size={20} className="text-gray-600" />
              </button>
              <div>
                <h1 className="text-2xl font-bold text-gray-900">{projectName}</h1>
                <p className="text-sm text-gray-500">Project Library</p>
              </div>
            </div>

            {storageUsage && (
              <div className="flex flex-col gap-1 min-w-[180px]">
                <div className="flex items-center justify-between text-xs text-gray-500">
                  <span>{storageUsage.usedMB} MB used</span>
                  <span>{storageUsage.limitGB === -1 ? "Unlimited" : `${storageUsage.limitGB} GB`}</span>
                </div>
                <div className="h-1.5 rounded-full bg-gray-200 overflow-hidden">
                  <div
                    className={`h-full rounded-full transition-all ${
                      parseFloat(storageUsage.usagePercent) >= 90
                        ? "bg-red-500"
                        : parseFloat(storageUsage.usagePercent) >= 70
                          ? "bg-amber-400"
                          : "bg-purple-500"
                    }`}
                    style={{ width: `${Math.min(100, parseFloat(storageUsage.usagePercent))}%` }}
                  />
                </div>
              </div>
            )}

            <div className="flex items-center gap-3">
              <button
                onClick={handleCreateNewFile}
                className={`px-2.5 py-1.5 text-xs border rounded-md transition-colors flex items-center gap-1.5 font-medium ${
                  userProjectRole === "VIEWER"
                    ? "text-gray-400 border-gray-200 bg-gray-50 cursor-not-allowed opacity-60"
                    : "text-green-600 border-green-300 bg-green-50 hover:bg-green-100"
                }`}
                title={userProjectRole === "VIEWER" ? "Viewers cannot create files" : "Create a new ontology file"}
                disabled={userProjectRole === "VIEWER"}
              >
                <Plus size={14} />
                New File
              </button>
              {onOpenEditor && (
                <button
                  onClick={onOpenEditor}
                  className="px-2.5 py-1.5 text-xs text-blue-600 border border-blue-300 bg-blue-50 rounded-md hover:bg-blue-100 transition-colors flex items-center gap-1.5 font-medium"
                >
                  <Code2 size={14} />
                  Editor
                </button>
              )}
              <button
                onClick={() => {
                  if (!isAppOnline()) {
                    showToast("Connect to the internet to report an issue.", "error");
                    return;
                  }
                  setIsReportIssueModalOpen(true);
                }}
                className="px-2.5 py-1.5 text-xs text-gray-600 border border-gray-300 bg-gray-50 rounded-md hover:bg-gray-100 transition-colors flex items-center gap-1.5 font-medium"
                title="Report Issue"
              >
                <Bug size={14} />
                Report Issue
              </button>
              <label
                className={`flex items-center gap-2 px-4 py-2 text-sm font-semibold rounded-lg transition-all ${
                  userProjectRole === "VIEWER"
                    ? "bg-gray-300 text-gray-500 cursor-not-allowed opacity-60"
                    : "hover:shadow-lg cursor-pointer bg-gradient-to-r from-purple-500 to-blue-500 text-white hover:from-purple-600 hover:to-blue-600"
                }`}
                title={userProjectRole === "VIEWER" ? "Viewers cannot upload files" : ""}
              >
                <Upload
                  size={18}
                  className={uploading ? "animate-bounce" : ""}
                  style={{ color: userProjectRole === "VIEWER" ? "currentColor" : "white" }}
                />
                <span style={{ color: userProjectRole === "VIEWER" ? "currentColor" : "white" }}>
                  {uploading ? "Uploading..." : "Upload File"}
                </span>
                {userProjectRole !== "VIEWER" && (
                  <input
                    type="file"
                    onChange={handleFileUpload}
                    className="hidden"
                    accept=".owl,.rdf,.ttl,.n3,.nt,.jsonld,.zip"
                    disabled={uploading}
                  />
                )}
              </label>
            </div>
          </div>

          <div className="flex items-center gap-4">
            <div className="flex-1 relative">
              <Search size={18} className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search files..."
                className="w-full pl-10 pr-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
              />
            </div>

            <div className="flex items-center gap-2 border border-gray-300 rounded-lg p-1">
              <button
                onClick={() => setViewMode("grid")}
                className={`p-2 rounded ${viewMode === "grid" ? "bg-purple-100 text-purple-600" : "text-gray-600 hover:bg-gray-100"}`}
              >
                <Grid size={18} />
              </button>
              <button
                onClick={() => setViewMode("list")}
                className={`p-2 rounded ${viewMode === "list" ? "bg-purple-100 text-purple-600" : "text-gray-600 hover:bg-gray-100"}`}
              >
                <List size={18} />
              </button>
            </div>
          </div>

          {/* Upload Progress Bar */}
          {uploading && (
            <div className="mt-4">
              <div className="flex items-center justify-between mb-2">
                <span className="text-sm font-medium text-gray-700">
                  {processingFile ? `Processing: ${processingFile}` : "Uploading file..."}
                </span>
                <span className="text-sm text-gray-600">{uploadProgress}%</span>
              </div>
              <div className="w-full bg-gray-200 rounded-full h-2 overflow-hidden">
                <div
                  className="bg-purple-600 h-full transition-all duration-300 ease-out"
                  style={{ width: `${uploadProgress}%` }}
                />
              </div>
              <p className="text-xs text-gray-500 mt-1">
                {uploadProgress < 50
                  ? "Reading file..."
                  : uploadProgress < 100
                    ? "Uploading to server..."
                    : "Processing complete!"}
              </p>
            </div>
          )}
        </div>
      </div>

      {/* Content */}
      <div className="max-w-7xl mx-auto px-6 py-6 overflow-y-auto" style={{ maxHeight: "calc(100vh - 280px)" }}>
        {loading ? (
          <div className="py-4">
            <div className="flex items-center gap-2 mb-4 text-sm text-purple-600">
              <div className="animate-spin rounded-full h-4 w-4 border-2 border-purple-600 border-t-transparent" />
              <span>Loading project files…</span>
            </div>
            <div className={viewMode === "grid" ? "grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4" : "space-y-2"}>
              {[...Array(viewMode === "grid" ? 6 : 4)].map((_, i) => (
                <div
                  key={i}
                  className={`animate-pulse bg-gray-100 border border-gray-200 rounded-lg ${viewMode === "grid" ? "h-28" : "h-14"}`}
                />
              ))}
            </div>
          </div>
        ) : filteredFiles.length === 0 ? (
          <div className="text-center py-12">
            <Folder size={48} className="text-gray-400 mx-auto mb-4" />
            <h3 className="text-xl font-semibold text-gray-900 mb-2">
              {searchQuery ? "No files found" : "No files yet"}
            </h3>
            <p className="text-gray-600 mb-6">
              {searchQuery ? "Try a different search query" : "Upload your first ontology file to get started"}
            </p>
            {!searchQuery && (
              <div className="flex flex-col items-center gap-4">
                <label
                  className={`group flex items-center gap-3 px-8 py-4 text-lg font-semibold rounded-xl transition-all ${
                    userProjectRole === "VIEWER"
                      ? "bg-gray-300 text-gray-500 cursor-not-allowed opacity-60"
                      : "hover:shadow-lg cursor-pointer bg-gradient-to-r from-purple-500 to-blue-500 text-white hover:from-purple-600 hover:to-blue-600"
                  }`}
                  title={userProjectRole === "VIEWER" ? "Viewers cannot upload files" : ""}
                >
                  <Upload
                    size={24}
                    className={userProjectRole !== "VIEWER" ? "group-hover:animate-bounce" : ""}
                    style={{ color: userProjectRole === "VIEWER" ? "currentColor" : "white" }}
                  />
                  <span style={{ color: userProjectRole === "VIEWER" ? "currentColor" : "white" }}>
                    Upload Your First File
                  </span>
                  {userProjectRole !== "VIEWER" && (
                    <input
                      type="file"
                      onChange={handleFileUpload}
                      className="hidden"
                      accept=".owl,.rdf,.ttl,.n3,.nt,.jsonld,.zip"
                      disabled={uploading}
                    />
                  )}
                </label>
                <p className="text-sm text-gray-500">Supported formats: .owl, .rdf, .ttl, .n3 (up to 1GB)</p>
              </div>
            )}
          </div>
        ) : viewMode === "grid" ? (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
            {filteredFiles.map((file) => {
              const importState = fileImportStates[file.id];
              const isImporting = importState?.status === 'IMPORTING';
              const isImportDone = importState?.status === 'COMPLETED';
              const isImportFailed = importState?.status === 'FAILED';
              const importProgress = importState?.progress ?? 0;
              const importMessage = importState?.message || '';

              return (
                <div
                  key={file.id}
                  onClick={() => !isImporting && handleFileClick(file)}
                  className={`relative overflow-hidden bg-white rounded-lg border-2 p-4 transition-all hover:shadow-lg ${
                    isImporting
                      ? 'border-blue-300 cursor-default'
                      : isImportDone
                      ? 'border-green-400 cursor-pointer hover:border-green-500'
                      : isImportFailed
                      ? 'border-red-300 cursor-pointer'
                      : selectedFile === file.id
                      ? 'border-purple-500 shadow-lg cursor-pointer'
                      : 'border-gray-200 cursor-pointer'
                  }`}
                >
                  {/* Progress bar stripe at bottom */}
                  {isImporting && (
                    <div className="absolute bottom-0 left-0 right-0 h-1 bg-blue-100">
                      <div
                        className={`h-full bg-blue-500 transition-all duration-500 ${importProgress > 0 ? '' : 'animate-pulse w-1/3'}`}
                        style={importProgress > 0 ? { width: `${Math.min(100, importProgress)}%` } : {}}
                      />
                    </div>
                  )}

                  <div className="flex items-start justify-between mb-3">
                    <div className={`w-12 h-12 rounded-lg flex items-center justify-center ${
                      isImporting ? 'bg-blue-100' : isImportDone ? 'bg-green-100' : isImportFailed ? 'bg-red-100' : 'bg-purple-100'
                    }`}>
                      {isImporting
                        ? <Loader2 size={24} className="text-blue-600 animate-spin" />
                        : isImportDone
                        ? <CheckCircle2 size={24} className="text-green-600" />
                        : isImportFailed
                        ? <XCircle size={24} className="text-red-500" />
                        : <FileText size={24} className="text-purple-600" />
                      }
                    </div>
                    {!isImporting && (userProjectRole === "OWNER" ||
                      userProjectRole === "ADMIN" ||
                      (userProjectRole === "EDITOR" && file.uploadedByUserId === user?.userId)) && (
                      <div className="relative">
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setOpenMenuFileId(openMenuFileId === file.id ? null : file.id);
                          }}
                          className="p-1 hover:bg-gray-100 rounded"
                        >
                          <MoreVertical size={16} className="text-gray-400" />
                        </button>
                        {openMenuFileId === file.id && (
                          <div className="absolute right-0 mt-1 w-40 bg-white border border-gray-200 rounded-lg shadow-lg z-10">
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                handleDeleteFile(file.id, file.name);
                              }}
                              className="w-full px-4 py-2 text-left text-sm text-red-600 hover:bg-red-50 rounded-lg flex items-center gap-2"
                            >
                              <Trash2 size={14} />
                              Delete
                            </button>
                          </div>
                        )}
                      </div>
                    )}
                  </div>

                  <h3 className={`font-semibold mb-1 truncate ${
                    isImporting ? 'text-blue-800' : isImportDone ? 'text-green-800' : isImportFailed ? 'text-red-700' : 'text-gray-900'
                  }`} title={file.name}>
                    {file.name}
                  </h3>

                  {isImporting && (
                    <p className="text-xs text-blue-600 mb-1">
                      {importMessage}
                    </p>
                  )}
                  {isImportFailed && (
                    <p className="text-xs text-red-500 mb-1">{importMessage || 'Import failed — click to retry'}</p>
                  )}

                  <div className="flex items-center gap-4 text-xs text-gray-500 mb-2">
                    <span>{formatFileSize(file.size)}</span>
                    <span>•</span>
                    <span className="flex items-center gap-1">
                      <Clock size={12} />
                      {formatDate(file.uploadedAt)}
                    </span>
                  </div>

                  {isImportDone ? (
                    <button
                      data-testid="import-open-btn"
                      className="w-full mt-1 flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium bg-green-600 text-white hover:bg-green-700 transition-colors"
                      onClick={(e) => { e.stopPropagation(); onFileSelect(file.id, file.name); }}
                    >
                      <FolderOpen size={14} />
                      Open
                    </button>
                  ) : (
                    <div className="flex items-center gap-1 text-xs text-gray-600">
                      <User size={12} />
                      {file.uploadedBy}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        ) : (
          <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
            <table className="w-full">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    Name
                  </th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    Size
                  </th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    Uploaded By
                  </th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    Date
                  </th>
                  <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {filteredFiles.map((file) => {
                  const importState = fileImportStates[file.id];
                  const isImporting = importState?.status === 'IMPORTING';
                  const isImportDone = importState?.status === 'COMPLETED';
                  const isImportFailed = importState?.status === 'FAILED';
                  const importProgress = importState?.progress ?? 0;
                  const importMessage = importState?.message || '';

                  return (
                    <tr
                      key={file.id}
                      onClick={() => !isImporting && handleFileClick(file)}
                      className={`transition-colors ${
                        isImporting
                          ? 'bg-blue-50 cursor-default'
                          : isImportDone
                          ? 'bg-green-50 cursor-pointer hover:bg-green-100'
                          : isImportFailed
                          ? 'bg-red-50 cursor-pointer hover:bg-red-100'
                          : selectedFile === file.id
                          ? 'bg-purple-50 cursor-pointer hover:bg-gray-50'
                          : 'cursor-pointer hover:bg-gray-50'
                      }`}
                    >
                      <td className="px-6 py-4 whitespace-nowrap">
                        <div className="flex items-center gap-3">
                          <div className={`w-8 h-8 rounded flex items-center justify-center ${
                            isImporting ? 'bg-blue-100' : isImportDone ? 'bg-green-100' : isImportFailed ? 'bg-red-100' : 'bg-purple-100'
                          }`}>
                            {isImporting
                              ? <Loader2 size={16} className="text-blue-600 animate-spin" />
                              : isImportDone
                              ? <CheckCircle2 size={16} className="text-green-600" />
                              : isImportFailed
                              ? <XCircle size={16} className="text-red-500" />
                              : <FileText size={16} className="text-purple-600" />
                            }
                          </div>
                          <div>
                            <span className={`font-medium ${isImporting ? 'text-blue-800' : isImportDone ? 'text-green-800' : isImportFailed ? 'text-red-700' : 'text-gray-900'}`}>
                              {file.name}
                            </span>
                            {isImporting && (
                              <div className="text-xs text-blue-600 mt-0.5">
                                {importMessage || 'Importing…'}
                              </div>
                            )}
                          </div>
                        </div>
                        {isImporting && (
                          <div className="mt-1.5 h-1 bg-blue-100 rounded-full overflow-hidden">
                            <div
                              className={`h-full bg-blue-500 transition-all duration-500 ${importProgress > 0 ? '' : 'animate-pulse w-1/3'}`}
                              style={importProgress > 0 ? { width: `${Math.min(100, importProgress)}%` } : {}}
                            />
                          </div>
                        )}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-600">{formatFileSize(file.size)}</td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-600">{file.uploadedBy}</td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-600">{formatDate(file.uploadedAt)}</td>
                      <td className="px-6 py-4 whitespace-nowrap text-right text-sm">
                        {isImportDone ? (
                          <button
                            data-testid="import-open-btn"
                            className="flex items-center gap-1 px-3 py-1 rounded text-sm font-medium bg-green-600 text-white hover:bg-green-700 transition-colors"
                            onClick={(e) => { e.stopPropagation(); onFileSelect(file.id, file.name); }}
                          >
                            <FolderOpen size={13} />
                            Open
                          </button>
                        ) : (
                          !isImporting && (userProjectRole === "OWNER" ||
                            userProjectRole === "ADMIN" ||
                            (userProjectRole === "EDITOR" && file.uploadedByUserId === user?.userId)) && (
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                handleDeleteFile(file.id, file.name);
                              }}
                              className="text-red-600 hover:text-red-800"
                            >
                              <Trash2 size={16} />
                            </button>
                          )
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Delete Confirmation Dialog */}
      {deleteConfirm.show && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full mx-4 shadow-xl">
            <h3 className="text-lg font-semibold text-gray-900 mb-2">Delete File</h3>
            <p className="text-gray-600 mb-6">
              Are you sure you want to delete <span className="font-semibold">{deleteConfirm.fileName}</span>? This
              action cannot be undone.
            </p>
            <div className="flex justify-end gap-3">
              <button
                onClick={cancelDelete}
                className="px-4 py-2 text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={confirmDelete}
                className="px-4 py-2 bg-red-600 text-white hover:bg-red-700 rounded-lg transition-colors"
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Toast Notification */}
      {toast.show && (
        <div className="fixed bottom-4 right-4 z-50 animate-slide-up">
          <div
            className={`px-6 py-3 rounded-lg shadow-lg flex items-center gap-3 text-white ${
              toast.type === "success" ? "bg-green-600" : "bg-red-600"
            }`}
          >
            {toast.type === "success" ? (
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
                <path
                  fillRule="evenodd"
                  d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z"
                  clipRule="evenodd"
                />
              </svg>
            ) : (
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
                <path
                  fillRule="evenodd"
                  d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
                  clipRule="evenodd"
                />
              </svg>
            )}
            <span>{toast.message}</span>
          </div>
        </div>
      )}

      {/* Report Issue Modal */}
      {isReportIssueModalOpen && (
        <ReportIssueModal
          projectName={projectName}
          projectId={projectId}
          onClose={() => setIsReportIssueModalOpen(false)}
        />
      )}

      {/* Free Plan Member Open Restriction Dialog */}
      {showOwnerMustImportDialog && (
        <div
          className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[9999]"
          onClick={() => setShowOwnerMustImportDialog(false)}
        >
          <div
            className="relative bg-white rounded-2xl shadow-2xl w-[420px] max-w-[92vw] overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="h-1.5 w-full bg-gradient-to-r from-violet-500 via-purple-500 to-indigo-500" />
            <div className="px-6 pt-5 pb-4 flex items-start gap-4">
              <div className="flex-shrink-0 w-11 h-11 rounded-xl bg-amber-50 border border-amber-200 flex items-center justify-center">
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-amber-500">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                  <circle cx="12" cy="12" r="3"/>
                  <line x1="2" y1="2" x2="22" y2="22"/>
                </svg>
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="text-[15px] font-semibold text-gray-900 leading-tight">File Not Ready Yet</h3>
                <p className="text-xs text-gray-400 mt-0.5">
                  This workspace is on the <span className="font-medium text-gray-500">Free plan</span>
                </p>
              </div>
              <button
                onClick={() => setShowOwnerMustImportDialog(false)}
                className="flex-shrink-0 text-gray-400 hover:text-gray-600 transition-colors p-1 rounded-lg hover:bg-gray-100 -mt-1 -mr-1"
                aria-label="Close"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                  <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </button>
            </div>
            <div className="px-6 pb-5">
              <div className="bg-gray-50 rounded-xl border border-gray-100 px-4 py-3.5 mb-4 text-sm text-gray-600 leading-relaxed">
                The workspace owner must import this file first by opening it in the OntoCode editor.
              </div>
              <div className="flex items-start gap-2.5 text-sm text-gray-600">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="flex-shrink-0 mt-0.5 text-violet-500">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>
                </svg>
                <span>Ask the <span className="font-medium text-gray-800">workspace owner</span> to open this file once from the project library. After that, members can open it for viewing.</span>
              </div>
            </div>
            <div className="px-6 pb-5 flex justify-end">
              <button
                onClick={() => setShowOwnerMustImportDialog(false)}
                className="px-5 py-2 text-sm font-medium rounded-lg bg-gray-900 text-white hover:bg-gray-700 transition-colors"
              >
                Got it
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Free Plan Upload Restriction Dialog */}
      {showFreePlanDialog && (
        <div
          className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[9999]"
          onClick={() => setShowFreePlanDialog(false)}
        >
          <div
            className="relative bg-white rounded-2xl shadow-2xl w-[420px] max-w-[92vw] overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Top accent bar */}
            <div className="h-1.5 w-full bg-gradient-to-r from-violet-500 via-purple-500 to-indigo-500" />

            {/* Header */}
            <div className="px-6 pt-5 pb-4 flex items-start gap-4">
              <div className="flex-shrink-0 w-11 h-11 rounded-xl bg-amber-50 border border-amber-200 flex items-center justify-center">
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-amber-500">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                  <circle cx="12" cy="12" r="3"/>
                  <line x1="2" y1="2" x2="22" y2="22"/>
                </svg>
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="text-[15px] font-semibold text-gray-900 leading-tight">Upload Not Available</h3>
                <p className="text-xs text-gray-400 mt-0.5">
                  Your workspace is on the <span className="font-medium text-gray-500">Free plan</span>
                </p>
              </div>
              <button
                onClick={() => setShowFreePlanDialog(false)}
                className="flex-shrink-0 text-gray-400 hover:text-gray-600 transition-colors p-1 rounded-lg hover:bg-gray-100 -mt-1 -mr-1"
                aria-label="Close"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                  <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </button>
            </div>

            {/* Body */}
            <div className="px-6 pb-5">
              <div className="bg-gray-50 rounded-xl border border-gray-100 px-4 py-3.5 mb-4 text-sm text-gray-600 leading-relaxed">
                You can <span className="font-medium text-gray-800">browse and explore</span> this project, but file uploads require a <span className="font-medium text-gray-800">Pro plan</span>.
              </div>
              <div className="flex items-start gap-2.5 text-sm text-gray-600">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="flex-shrink-0 mt-0.5 text-violet-500">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>
                </svg>
                <span>Ask your <span className="font-medium text-gray-800">workspace owner</span> to upgrade to Pro to unlock file uploads for all members.</span>
              </div>
            </div>

            {/* Footer */}
            <div className="px-6 pb-5 flex justify-end">
              <button
                onClick={() => setShowFreePlanDialog(false)}
                className="px-5 py-2 text-sm font-medium rounded-lg bg-gray-900 text-white hover:bg-gray-700 transition-colors"
              >
                Got it
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ProjectLibrary;

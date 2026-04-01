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
} from "lucide-react";
import apiClient from "../services/apiClient";
import { useAuth } from "../custom-hook/useAuth";

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
  const [openMenuFileId, setOpenMenuFileId] = useState<string | null>(null); // Track which file menu is open
  const [userProjectRole, setUserProjectRole] = useState<string>("VIEWER");
  const { user } = useAuth();

  const showToast = (message: string, type: "success" | "error" = "success") => {
    setToast({ show: true, message, type });
    setTimeout(() => setToast({ show: false, message: "", type: "success" }), 3000);
  };

  const handleCreateNewFile = () => {
    console.log("[ProjectLibrary] 📝 Creating new file for project:", projectId);
    if (window.vscode) {
      window.vscode.postMessage({
        type: "createNewFile",
        projectId: projectId,
      });
    }
  };

  useEffect(() => {
    loadFiles();
  }, [projectId]);

  useEffect(() => {
    return () => {
      isMountedRef.current = false;
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

          fetchWithRetry();
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
      const uploadedFileName = responseData?.filename || targetFileName;

      // Always reload files to refresh the file list after upload
      await loadFiles();

      // Set the uploaded file as selected in the list, but don't automatically load it into the editor
      if (uploadedFileId) {
        setSelectedFile(uploadedFileId);
      }
    } catch (error: any) {
      console.error("Error uploading file:", error);
      console.error("Error status:", error.status);
      console.error("Error data:", error.data);

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
    const validExtensions = [".owl", ".rdf", ".ttl", ".n3"];
    const fileExtension = file.name.substring(file.name.lastIndexOf(".")).toLowerCase();
    if (!validExtensions.includes(fileExtension)) {
      showToast("Invalid file type. Only .owl, .rdf, .ttl, .n3 files are allowed", "error");
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

  const handleFileClick = (file: FileItem) => {
    setSelectedFile(file.id);
  };

  const handleFileDoubleClick = (file: FileItem) => {
    onFileSelect(file.id, file.name);
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
                    accept=".owl,.rdf,.ttl,.n3"
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
          <div className="text-center py-12">
            <div className="animate-spin rounded-full h-12 w-12 border-t-4 border-purple-600 mx-auto"></div>
            <p className="mt-4 text-gray-600">Loading files...</p>
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
                      accept=".owl,.rdf,.ttl,.n3"
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
            {filteredFiles.map((file) => (
              <div
                key={file.id}
                onClick={() => handleFileClick(file)}
                onDoubleClick={() => handleFileDoubleClick(file)}
                className={`bg-white rounded-lg border-2 p-4 cursor-pointer transition-all hover:shadow-lg ${
                  selectedFile === file.id ? "border-purple-500 shadow-lg" : "border-gray-200"
                }`}
              >
                <div className="flex items-start justify-between mb-3">
                  <div className="w-12 h-12 bg-purple-100 rounded-lg flex items-center justify-center">
                    <FileText size={24} className="text-purple-600" />
                  </div>
                  {(userProjectRole === "OWNER" ||
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

                <h3 className="font-semibold text-gray-900 mb-1 truncate" title={file.name}>
                  {file.name}
                </h3>

                <div className="flex items-center gap-4 text-xs text-gray-500 mb-2">
                  <span>{formatFileSize(file.size)}</span>
                  <span>•</span>
                  <span className="flex items-center gap-1">
                    <Clock size={12} />
                    {formatDate(file.uploadedAt)}
                  </span>
                </div>

                <div className="flex items-center gap-1 text-xs text-gray-600">
                  <User size={12} />
                  {file.uploadedBy}
                </div>
              </div>
            ))}
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
                {filteredFiles.map((file) => (
                  <tr
                    key={file.id}
                    onClick={() => handleFileClick(file)}
                    onDoubleClick={() => handleFileDoubleClick(file)}
                    className={`cursor-pointer hover:bg-gray-50 transition-colors ${
                      selectedFile === file.id ? "bg-purple-50" : ""
                    }`}
                  >
                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="flex items-center gap-3">
                        <div className="w-8 h-8 bg-purple-100 rounded flex items-center justify-center">
                          <FileText size={16} className="text-purple-600" />
                        </div>
                        <span className="font-medium text-gray-900">{file.name}</span>
                      </div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-600">{formatFileSize(file.size)}</td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-600">{file.uploadedBy}</td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-600">{formatDate(file.uploadedAt)}</td>
                    <td className="px-6 py-4 whitespace-nowrap text-right text-sm">
                      {(userProjectRole === "OWNER" ||
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
                      )}
                    </td>
                  </tr>
                ))}
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
    </div>
  );
};

export default ProjectLibrary;

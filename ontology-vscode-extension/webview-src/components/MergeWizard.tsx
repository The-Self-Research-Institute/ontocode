import React, { useState, useEffect, useMemo } from "react";
import {
  Upload,
  AlertTriangle,
  CheckCircle,
  GitMerge,
  FileText,
  Settings,
  ChevronRight,
  ChevronLeft,
  X,
  Download,
  Loader2,
  ChevronDown,
} from "lucide-react";
import apiClient, { getBaseUrl } from "../services/apiClient";

interface MergeConflict {
  entityIRI: string;
  entityType: string;
  conflictType: string;
  sourceDefinition: string;
  targetDefinition: string;
  severity: string;
  description?: string;
}

interface MergeAnalysisResult {
  conflicts: MergeConflict[];
  sourceClassCount: number;
  sourcePropertyCount: number;
  targetClassCount: number;
  targetPropertyCount: number;
  sourceIndividualCount: number;
  targetIndividualCount: number;
  sourceOnlyAxiomCount?: number;
  sourceOnlyClassCount?: number;
  sourceOnlyPropertyCount?: number;
  sourceOnlyIndividualCount?: number;
  targetOnlyAxiomCount?: number;
  targetOnlyClassCount?: number;
  targetOnlyPropertyCount?: number;
  targetOnlyIndividualCount?: number;
  sourceOnlyClasses?: string[];
  sourceOnlyProperties?: string[];
  sourceOnlyIndividuals?: string[];
  sourceOnlyClassLabels?: Record<string, string>;
  sourceOnlyPropertyLabels?: Record<string, string>;
  sourceOnlyIndividualLabels?: Record<string, string>;
  classHierarchy?: Record<string, string[]>;
  propertyHierarchy?: Record<string, string[]>;
}

interface MergeWizardProps {
  isOpen: boolean;
  onClose: () => void;
  projectId: string;
  projectTitle: string;
  initialProjectId?: string;
  availableFiles?: Array<string | { id?: string; name?: string; filename?: string }>;
  onMergeComplete?: (targetProjectId: string, isNewFile?: boolean) => Promise<void> | void;
  isViewOnly?: boolean;
  onProAction?: () => void;
}

interface MergeTargetOption {
  id: string;
  name: string;
}

type MergeStrategy = "SIMPLE_UNION" | "REPLACE_DUPLICATES" | "KEEP_BOTH" | "MANUAL_RESOLUTION";
type ResolutionAction = "KEEP_SOURCE" | "KEEP_TARGET" | "RENAME_SOURCE" | "MERGE" | "SKIP";

const MergeWizard: React.FC<MergeWizardProps> = ({
  isOpen,
  onClose,
  projectId,
  projectTitle,
  initialProjectId,
  availableFiles = [],
  onMergeComplete,
  isViewOnly = false,
  onProAction,
}) => {
  const toShortName = (iri: string) => {
    if (!iri) return "";
    return iri.split("#")[1] || iri.split("/").pop() || iri;
  };

  const toDisplay = (iri: string, labels?: Record<string, string>) => {
    const label = labels?.[iri];
    const local = toShortName(iri);
    if (!label) return local;
    if (label === local) return label;
    return `${label} (${local})`;
  };

  const [step, setStep] = useState(1);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [analysisResult, setAnalysisResult] = useState<MergeAnalysisResult | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [merging, setMerging] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [mergeStrategy, setMergeStrategy] = useState<MergeStrategy>("SIMPLE_UNION");
  const [renameSuffix, setRenameSuffix] = useState("_imported");
  const [conflictResolutions, setConflictResolutions] = useState<Map<string, ResolutionAction>>(new Map());
  const [mergeComplete, setMergeComplete] = useState(false);
  const [mergeResult, setMergeResult] = useState<any>(null);
  const [targetMode, setTargetMode] = useState<"current" | "existingFile" | "newFile">("current");
  const [selectedTargetFileName, setSelectedTargetFileName] = useState("");
  const [newOutputFileName, setNewOutputFileName] = useState("merged-output.owl");
  const [mergeError, setMergeError] = useState<string | null>(null);

  const targetOptions = useMemo<MergeTargetOption[]>(() => {
    return (availableFiles || [])
      .map((file) => {
        if (typeof file === "string") {
          return { id: file, name: file };
        }
        const id = file.id || file.filename || file.name || "";
        const name = file.filename || file.name || file.id || "";
        if (!id || !name) return null;
        return { id, name };
      })
      .filter((item): item is MergeTargetOption => !!item);
  }, [availableFiles]);

  useEffect(() => {
    if (!isOpen) {

      setStep(1);
      setSelectedFile(null);
      setAnalysisResult(null);
      setMergeComplete(false);
      setMergeResult(null);
      setConflictResolutions(new Map());
      setTargetMode("current");
      setSelectedTargetFileName("");
      setNewOutputFileName("merged-output.owl");
      setMergeError(null);
    }
  }, [isOpen, projectId]);

  const resolveTargetProjectId = () => {
    if (targetMode === "existingFile") {

      return projectId;
    }
    return null;
  };

  const resolveTargetFileName = () => {
    if (targetMode === "existingFile") {
      return selectedTargetFileName || null;
    }
    return null;
  };

  const handleFileSelect = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) {
      setSelectedFile(file);
    }
  };

  const handleAnalyze = async () => {
    if (!selectedFile) return;

    setAnalyzing(true);
    setMergeError(null);
    try {
      const formData = new FormData();
      formData.append("file", selectedFile);

      const targetProjectId = resolveTargetProjectId();
      const targetFileName = resolveTargetFileName();
      const queryParams = new URLSearchParams();
      if (targetProjectId) {
        queryParams.set("targetProjectId", targetProjectId);
      }
      if (targetFileName) {
        queryParams.set("targetFileName", targetFileName);
      }
      let analyzeUrl = `/api/projects/${projectId}/merge/analyze`;
      if (queryParams.toString()) {
        analyzeUrl += `?${queryParams.toString()}`;
      }
      const result = await apiClient.post<MergeAnalysisResult>(analyzeUrl, formData);

      setAnalysisResult(result);
      setStep(2);
    } catch (error: any) {
      console.error("Error analyzing ontologies:", error);
      const errMsg = error?.response?.data?.error || error?.data?.error || error?.message || "Unknown error";
      setMergeError("Analysis failed: " + errMsg);
    } finally {
      setAnalyzing(false);
    }
  };

  const handleMerge = async () => {
    if (isViewOnly) { onProAction?.(); return; }
    if (!selectedFile) return;

    setMerging(true);
    try {
      const formData = new FormData();
      formData.append("file", selectedFile);
      formData.append("strategy", mergeStrategy);

      if (mergeStrategy === "KEEP_BOTH" && renameSuffix) {
        formData.append("renameSuffix", renameSuffix);
      }
      if (mergeStrategy === "MANUAL_RESOLUTION") {
        const payload = buildConflictResolutionsPayload();
        formData.append("conflictResolutions", JSON.stringify(payload));
      }

      const targetProjectId = resolveTargetProjectId();
      const targetFileName = resolveTargetFileName();
      const outputFileName = targetMode === "newFile" ? newOutputFileName.trim() : "";

      const queryParams = new URLSearchParams();
      if (targetProjectId) {
        queryParams.set("targetProjectId", targetProjectId);
      }
      if (targetFileName) {
        queryParams.set("targetFileName", targetFileName);
      }
      if (outputFileName) {
        queryParams.set("outputFileName", outputFileName);
      }

      let url = `/api/projects/${projectId}/merge/execute`;
      if (queryParams.toString()) {
        url += `?${queryParams.toString()}`;
      }

      const result = await apiClient.post(url, formData);
      setMergeResult(result);

      const resultData = result?.data || result;
      const tempProjectId = resultData?.targetProjectId;

      if (targetMode === "newFile" && tempProjectId && initialProjectId) {

        console.log("[MergeWizard] Downloading merged file from temp project:", tempProjectId);

        const downloadRes = await fetch(
          `${getBaseUrl()}/api/ontology/files/${encodeURIComponent(tempProjectId)}/download`,
          {
            headers: {
              Authorization: `Bearer ${localStorage.getItem("authToken")}`,
            },
          },
        );

        if (!downloadRes.ok) {
          throw new Error("Failed to download merged file from server");
        }

        const blob = await downloadRes.blob();

        let uploadName = outputFileName || "merged-output.owl";
        if (!/\.(owl|rdf|ttl|n3)$/i.test(uploadName)) {
          uploadName += ".owl";
        }
        const uploadFormData = new FormData();
        uploadFormData.append("file", blob, uploadName);
        uploadFormData.append("fileName", uploadName);
        uploadFormData.append("fileType", "application/rdf+xml");

        console.log("[MergeWizard] Uploading merged file to project:", initialProjectId, "as:", uploadName);
        await apiClient.post(`/api/projects/${initialProjectId}/files`, uploadFormData);
        console.log("[MergeWizard] Upload complete — new file added to project");

        if (onMergeComplete) {
          await onMergeComplete(projectId, true);
        }
      } else {

        const actualTargetProjectId = tempProjectId || targetProjectId || projectId;
        if (onMergeComplete) {
          await onMergeComplete(actualTargetProjectId, false);
        }
      }
      setMergeComplete(true);
      setStep(4);
    } catch (error: any) {
      console.error("Error merging ontologies:", error);
      const status = error?.response?.status;
      const errMsg = error?.response?.data?.error || error?.data?.error || error?.message || "Unknown error";
      if (status === 403) {
        onClose();
        onProAction?.();
      } else {
        setMergeError("Merge failed: " + errMsg);
      }
    } finally {
      setMerging(false);
    }
  };

  const conflictKey = (c: MergeConflict) => `${c.entityIRI}::${c.entityType}`;

  const setResolution = (conflict: MergeConflict, action: ResolutionAction) => {
    const key = conflictKey(conflict);
    const newResolutions = new Map(conflictResolutions);
    newResolutions.set(key, action);

    if (analysisResult) {
      const hierarchy = {
        ...analysisResult.classHierarchy,
        ...analysisResult.propertyHierarchy,
      };

      const visited = new Set<string>();
      const queue = [conflict.entityIRI];
      while (queue.length > 0) {
        const current = queue.shift()!;
        if (visited.has(current)) continue;
        visited.add(current);
        const children = hierarchy[current];
        if (children) {
          for (const child of children) {
            queue.push(child);
          }
        }
      }
      visited.delete(conflict.entityIRI); // don't re-set self

      for (const c of analysisResult.conflicts) {
        if (visited.has(c.entityIRI)) {
          newResolutions.set(conflictKey(c), action);
        }
      }
    }

    setConflictResolutions(newResolutions);
  };

  const buildConflictResolutionsPayload = () => {
    const payload: Record<string, { action: ResolutionAction; renameSuffix?: string }> = {};
    conflictResolutions.forEach((action, compositeKey) => {

      payload[compositeKey] = {
        action,
        ...(action === "RENAME_SOURCE" ? { renameSuffix: renameSuffix || "_imported" } : {}),
      };
    });
    return payload;
  };

  const handleDownload = async () => {
    setDownloading(true);
    try {
      const downloadProjectId = mergeResult?.targetProjectId || projectId;

      const response = await fetch(`${getBaseUrl()}/api/ontology/files/${downloadProjectId}/download`, {
        headers: {
          Authorization: `Bearer ${localStorage.getItem("authToken")}`,
        },
      });

      if (!response.ok) {
        throw new Error("Download failed");
      }

      const blob = await response.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;

      const contentDisposition = response.headers.get("Content-Disposition");
      const filenameMatch = contentDisposition?.match(/filename="?([^"]+)"?/);
      const filename = filenameMatch ? filenameMatch[1] : `${projectTitle}-merged.owl`;

      a.download = filename;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.URL.revokeObjectURL(url);
    } catch (error: any) {
      console.error("Error downloading merged ontology:", error);
      alert("Download failed: " + (error.message || "Unknown error"));
    } finally {
      setDownloading(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-lg shadow-xl max-w-4xl w-full max-h-[90vh] overflow-y-auto">
        {}
        <div className="border-b p-4 bg-blue-50 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <GitMerge className="w-6 h-6 text-blue-600" />
            <div>
              <h2 className="text-lg font-semibold text-blue-900">Merge Ontologies</h2>
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-blue-700">
                {selectedFile && (
                  <span>
                    <span className="font-medium">Source:</span> {selectedFile.name}
                  </span>
                )}
                <span>
                  <span className="font-medium">Target:</span>{" "}
                  {targetMode === "newFile"
                    ? newOutputFileName || "merged-output.owl"
                    : targetMode === "existingFile"
                      ? selectedTargetFileName || "(select file)"
                      : projectTitle}
                </span>
              </div>
            </div>
          </div>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-700">
            <X className="w-5 h-5" />
          </button>
        </div>

        {}
        <div className="border-b p-4 bg-gray-50">
          <div className="flex items-center justify-between max-w-2xl mx-auto">
            <div className={`flex items-center gap-2 ${step >= 1 ? "text-blue-600" : "text-gray-400"}`}>
              <div
                className={`w-8 h-8 rounded-full flex items-center justify-center ${step >= 1 ? "bg-blue-600 text-white" : "bg-gray-300"}`}
              >
                1
              </div>
              <span className="text-sm font-medium">Upload</span>
            </div>
            <ChevronRight className="w-5 h-5 text-gray-400" />

            <div className={`flex items-center gap-2 ${step >= 2 ? "text-blue-600" : "text-gray-400"}`}>
              <div
                className={`w-8 h-8 rounded-full flex items-center justify-center ${step >= 2 ? "bg-blue-600 text-white" : "bg-gray-300"}`}
              >
                2
              </div>
              <span className="text-sm font-medium">Review</span>
            </div>
            <ChevronRight className="w-5 h-5 text-gray-400" />

            <div className={`flex items-center gap-2 ${step >= 3 ? "text-blue-600" : "text-gray-400"}`}>
              <div
                className={`w-8 h-8 rounded-full flex items-center justify-center ${step >= 3 ? "bg-blue-600 text-white" : "bg-gray-300"}`}
              >
                3
              </div>
              <span className="text-sm font-medium">Configure</span>
            </div>
            <ChevronRight className="w-5 h-5 text-gray-400" />

            <div className={`flex items-center gap-2 ${step >= 4 ? "text-green-600" : "text-gray-400"}`}>
              <div
                className={`w-8 h-8 rounded-full flex items-center justify-center ${step >= 4 ? "bg-green-600 text-white" : "bg-gray-300"}`}
              >
                4
              </div>
              <span className="text-sm font-medium">Complete</span>
            </div>
          </div>
        </div>

        {}
        <div className="p-6">
          {}
          {step === 1 && (
            <div className="space-y-4">
              <div>
                <h3 className="text-lg font-semibold mb-2">Select Source Ontology</h3>
                <p className="text-sm text-gray-600 mb-4">
                  Choose the ontology file you want to merge into {projectTitle}
                </p>
              </div>

              <div className="border-2 border-dashed border-gray-300 rounded-lg p-8 text-center">
                <Upload className="w-12 h-12 text-gray-400 mx-auto mb-4" />
                <label htmlFor="file-upload" className="cursor-pointer">
                  <span className="text-blue-600 hover:text-blue-700 font-medium">Choose file</span>
                  <input
                    id="file-upload"
                    type="file"
                    accept=".owl,.rdf,.ttl,.n3,.nt,.jsonld"
                    onChange={handleFileSelect}
                    className="hidden"
                  />
                </label>
                <p className="text-sm text-gray-500 mt-2">
                  Supported formats: OWL, RDF/XML, Turtle, N-Triples, JSON-LD
                </p>
              </div>

              {selectedFile && (
                <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                  <div className="flex items-center gap-3">
                    <FileText className="w-5 h-5 text-blue-600" />
                    <div className="flex-1">
                      <p className="font-medium text-blue-900">{selectedFile.name}</p>
                      <p className="text-sm text-blue-600">{(selectedFile.size / 1024).toFixed(2)} KB</p>
                    </div>
                  </div>
                </div>
              )}

              <div className="border rounded-lg p-4 bg-gray-50 space-y-3">
                <h4 className="font-semibold text-gray-900">Merge Destination</h4>

                <label className="flex items-center gap-2 text-sm text-gray-700">
                  <input
                    type="radio"
                    name="merge-target"
                    checked={targetMode === "current"}
                    onChange={() => setTargetMode("current")}
                  />
                  Merge into current file ({projectTitle})
                </label>

                <label className="flex items-center gap-2 text-sm text-gray-700">
                  <input
                    type="radio"
                    name="merge-target"
                    checked={targetMode === "existingFile"}
                    onChange={() => setTargetMode("existingFile")}
                  />
                  Merge into existing file in this project
                </label>
                {targetMode === "existingFile" && (
                  <select
                    className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm"
                    value={selectedTargetFileName}
                    onChange={(e) => {
                      const selectedValue = e.target.value;
                      const selected = targetOptions.find((opt) => opt.name === selectedValue);
                      setSelectedTargetFileName(selectedValue);
                    }}
                  >
                    <option value="">Select existing file</option>
                    {targetOptions.map((file) => (
                      <option key={file.name} value={file.name}>
                        {file.name}
                      </option>
                    ))}
                  </select>
                )}

                <label className="flex items-center gap-2 text-sm text-gray-700">
                  <input
                    type="radio"
                    name="merge-target"
                    checked={targetMode === "newFile"}
                    onChange={() => setTargetMode("newFile")}
                  />
                  Save as new file in this project
                </label>
                {targetMode === "newFile" && (
                  <input
                    type="text"
                    className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm"
                    placeholder="New file name (e.g. merged-output.owl)"
                    value={newOutputFileName}
                    onChange={(e) => setNewOutputFileName(e.target.value)}
                  />
                )}
              </div>

              {mergeError && (
                <div className="mt-3 flex items-start gap-2 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">
                  <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0" />
                  <span>{mergeError}</span>
                </div>
              )}
              <div className="flex justify-end gap-2 mt-6">
                <button
                  onClick={onClose}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleAnalyze}
                  disabled={
                    !selectedFile ||
                    analyzing ||
                    (targetMode === "existingFile" && !selectedTargetFileName) ||
                    (targetMode === "newFile" && !newOutputFileName.trim())
                  }
                  className="px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 disabled:bg-gray-300 disabled:cursor-not-allowed flex items-center gap-2"
                >
                  {analyzing ? (
                    <>
                      <Loader2 className="w-4 h-4 animate-spin" />
                      Analyzing...
                    </>
                  ) : (
                    <>
                      Analyze <ChevronRight className="w-4 h-4" />
                    </>
                  )}
                </button>
              </div>
            </div>
          )}

          {}
          {step === 2 && analysisResult && (
            <div className="space-y-4">
              <div>
                <h3 className="text-lg font-semibold mb-2">Analysis Results</h3>
                <p className="text-sm text-gray-600">
                  Review the conflicts detected between the source and target ontologies
                </p>
              </div>

              {}
              <div className="grid grid-cols-2 gap-4">
                <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                  <h4 className="font-semibold text-blue-900 mb-2">Source Ontology</h4>
                  {selectedFile && (
                    <p className="text-xs text-blue-600 mb-2 truncate" title={selectedFile.name}>
                      <FileText className="w-3 h-3 inline mr-1" />
                      {selectedFile.name}
                    </p>
                  )}
                  <div className="space-y-1 text-sm text-blue-700">
                    <p>Classes: {analysisResult.sourceClassCount}</p>
                    <p>Properties: {analysisResult.sourcePropertyCount}</p>
                    <p>Individuals: {analysisResult.sourceIndividualCount || 0}</p>
                  </div>
                </div>

                <div className="bg-green-50 border border-green-200 rounded-lg p-4">
                  <h4 className="font-semibold text-green-900 mb-2">Target Ontology</h4>
                  <p
                    className="text-xs text-green-600 mb-2 truncate"
                    title={
                      targetMode === "newFile"
                        ? newOutputFileName || "merged-output.owl"
                        : targetMode === "existingFile"
                          ? selectedTargetFileName || ""
                          : projectTitle
                    }
                  >
                    <FileText className="w-3 h-3 inline mr-1" />
                    {targetMode === "newFile"
                      ? newOutputFileName || "merged-output.owl"
                      : targetMode === "existingFile"
                        ? selectedTargetFileName || ""
                        : projectTitle}
                  </p>
                  <div className="space-y-1 text-sm text-green-700">
                    <p>Classes: {analysisResult.targetClassCount}</p>
                    <p>Properties: {analysisResult.targetPropertyCount}</p>
                    <p>Individuals: {analysisResult.targetIndividualCount || 0}</p>
                  </div>
                </div>
              </div>

              {}
              <div
                className={`border rounded-lg p-4 ${analysisResult.conflicts.length > 0 ? "bg-yellow-50 border-yellow-200" : "bg-green-50 border-green-200"}`}
              >
                <div className="flex items-center gap-3">
                  {analysisResult.conflicts.length > 0 ? (
                    <AlertTriangle className="w-5 h-5 text-yellow-600" />
                  ) : (
                    <CheckCircle className="w-5 h-5 text-green-600" />
                  )}
                  <div>
                    <p
                      className={`font-semibold ${analysisResult.conflicts.length > 0 ? "text-yellow-900" : "text-green-900"}`}
                    >
                      {analysisResult.conflicts.length > 0
                        ? `${analysisResult.conflicts.length} conflicts detected`
                        : "No conflicts detected"}
                    </p>
                    <p
                      className={`text-sm ${analysisResult.conflicts.length > 0 ? "text-yellow-700" : "text-green-700"}`}
                    >
                      {analysisResult.conflicts.length > 0
                        ? "You can choose a merge strategy to resolve these conflicts"
                        : "The ontologies can be merged without conflicts"}
                    </p>
                  </div>
                </div>
              </div>

              {}
              {analysisResult.conflicts.some(
                (c) => c.conflictType === "IDENTICAL_FILE_UPLOAD" || c.conflictType === "DUPLICATE_FILE_CONTENT",
              ) && (
                <div className="border-l-4 border-red-500 bg-red-50 p-4 rounded">
                  <div className="flex items-start gap-3">
                    <AlertTriangle className="w-5 h-5 text-red-600 mt-0.5 flex-shrink-0" />
                    <div>
                      <p className="font-semibold text-red-900">Duplicate File Detected</p>
                      <p className="text-sm text-red-800 mt-1">
                        {analysisResult.conflicts.some((c) => c.conflictType === "IDENTICAL_FILE_UPLOAD")
                          ? "The uploaded ontology is identical to the existing one. This appears to be a re-upload of the same file. Proceeding with the merge will not add any new content."
                          : "The uploaded ontology is nearly identical to the existing one (less than 1% difference). This may be a duplicate with only minor changes."}
                      </p>
                      <p className="text-sm text-red-700 mt-2 font-medium">
                        ✓ You can still proceed if you intended to re-merge, but consider if this action is necessary.
                      </p>
                    </div>
                  </div>
                </div>
              )}

              {}
              {analysisResult.conflicts.length > 0 && (
                <div className="border rounded-lg overflow-hidden max-h-96 overflow-y-auto">
                  <table className="w-full text-sm">
                    <thead className="bg-gray-50 border-b sticky top-0">
                      <tr>
                        <th className="px-4 py-2 text-left">Entity</th>
                        <th className="px-4 py-2 text-left">Type</th>
                        <th className="px-4 py-2 text-left">Conflict</th>
                        <th className="px-4 py-2 text-left">Severity</th>
                      </tr>
                    </thead>
                    <tbody>
                      {analysisResult.conflicts.map((conflict, index) => (
                        <tr key={index} className="border-b hover:bg-gray-50">
                          <td className="px-4 py-2 text-blue-600 font-mono text-xs break-all max-w-xs">
                            {conflict.entityIRI.split("#")[1] || conflict.entityIRI.split("/").pop()}
                          </td>
                          <td className="px-4 py-2">{conflict.entityType}</td>
                          <td className="px-4 py-2">{conflict.conflictType}</td>
                          <td className="px-4 py-2">
                            <span
                              className={`px-2 py-1 rounded text-xs font-medium ${
                                conflict.severity === "HIGH" || conflict.severity === "CRITICAL"
                                  ? "bg-red-100 text-red-800"
                                  : conflict.severity === "MEDIUM"
                                    ? "bg-yellow-100 text-yellow-800"
                                    : "bg-blue-100 text-blue-800"
                              }`}
                            >
                              {conflict.severity}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              <div className="flex justify-between mt-6">
                <button
                  onClick={() => setStep(1)}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50 flex items-center gap-2"
                >
                  <ChevronLeft className="w-4 h-4" /> Back
                </button>
                <button
                  onClick={() => setStep(3)}
                  className="px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 flex items-center gap-2"
                >
                  Configure Merge <ChevronRight className="w-4 h-4" />
                </button>
              </div>
            </div>
          )}

          {}
          {step === 3 && (
            <div className="space-y-4">
              <div>
                <h3 className="text-lg font-semibold mb-2">Configure Merge Strategy</h3>
                <p className="text-sm text-gray-600">Choose how conflicts should be resolved</p>
              </div>

              <div className="space-y-3">
                <label
                  className={`border rounded-lg p-4 cursor-pointer hover:bg-gray-50 block ${mergeStrategy === "SIMPLE_UNION" ? "border-blue-600 bg-blue-50" : "border-gray-300"}`}
                >
                  <input
                    type="radio"
                    name="strategy"
                    value="SIMPLE_UNION"
                    checked={mergeStrategy === "SIMPLE_UNION"}
                    onChange={(e) => setMergeStrategy(e.target.value as MergeStrategy)}
                    className="mr-3"
                  />
                  <span className="font-semibold">Simple Union</span>
                  <p className="text-sm text-gray-600 ml-6">
                    Combine all axioms from both ontologies. Duplicates are kept as-is.
                  </p>
                </label>

                <label
                  className={`border rounded-lg p-4 cursor-pointer hover:bg-gray-50 block ${mergeStrategy === "REPLACE_DUPLICATES" ? "border-blue-600 bg-blue-50" : "border-gray-300"}`}
                >
                  <input
                    type="radio"
                    name="strategy"
                    value="REPLACE_DUPLICATES"
                    checked={mergeStrategy === "REPLACE_DUPLICATES"}
                    onChange={(e) => setMergeStrategy(e.target.value as MergeStrategy)}
                    className="mr-3"
                  />
                  <span className="font-semibold">Replace Duplicates</span>
                  <p className="text-sm text-gray-600 ml-6">
                    Source ontology entities overwrite target ontology entities.
                  </p>
                </label>

                <label
                  className={`border rounded-lg p-4 cursor-pointer hover:bg-gray-50 block ${mergeStrategy === "KEEP_BOTH" ? "border-blue-600 bg-blue-50" : "border-gray-300"}`}
                >
                  <input
                    type="radio"
                    name="strategy"
                    value="KEEP_BOTH"
                    checked={mergeStrategy === "KEEP_BOTH"}
                    onChange={(e) => setMergeStrategy(e.target.value as MergeStrategy)}
                    className="mr-3"
                  />
                  <span className="font-semibold">Keep Both (Rename)</span>
                  <p className="text-sm text-gray-600 ml-6">
                    Rename conflicting entities in source ontology before merging.
                  </p>

                  {mergeStrategy === "KEEP_BOTH" && (
                    <div className="mt-3 ml-6">
                      <label className="block text-sm font-medium text-gray-700 mb-1">Rename Suffix</label>
                      <input
                        type="text"
                        value={renameSuffix}
                        onChange={(e) => setRenameSuffix(e.target.value)}
                        placeholder="_imported"
                        className="border border-gray-300 rounded px-3 py-1.5 text-sm w-48"
                      />
                    </div>
                  )}
                </label>

                {analysisResult && analysisResult.conflicts.length > 0 && (
                  <label
                    className={`border rounded-lg p-4 cursor-pointer hover:bg-gray-50 block ${mergeStrategy === "MANUAL_RESOLUTION" ? "border-blue-600 bg-blue-50" : "border-gray-300"}`}
                  >
                    <input
                      type="radio"
                      name="strategy"
                      value="MANUAL_RESOLUTION"
                      checked={mergeStrategy === "MANUAL_RESOLUTION"}
                      onChange={(e) => setMergeStrategy(e.target.value as MergeStrategy)}
                      className="mr-3"
                    />
                    <span className="font-semibold">Manual Resolution</span>
                    <p className="text-sm text-gray-600 ml-6">Specify how to resolve each conflict individually.</p>
                  </label>
                )}
              </div>

              {mergeStrategy === "MANUAL_RESOLUTION" && analysisResult && analysisResult.conflicts.length > 0 && (
                <div className="space-y-0 border border-gray-300 rounded-lg overflow-hidden">
                  {}
                  <div className="bg-gray-800 text-gray-200 px-4 py-2 flex items-center justify-between text-xs font-mono">
                    <span>
                      Merge Conflicts — {analysisResult.conflicts.length} conflict
                      {analysisResult.conflicts.length !== 1 ? "s" : ""}
                    </span>
                    <span className="text-gray-400">
                      {analysisResult.conflicts.filter((c) => conflictResolutions.has(conflictKey(c))).length} of{" "}
                      {analysisResult.conflicts.length} resolved
                    </span>
                  </div>
                  <div className="max-h-[32rem] overflow-y-auto">
                    {analysisResult.conflicts.map((conflict, index) => {
                      const currentAction = conflictResolutions.get(conflictKey(conflict)) || "";
                      const isResolved = conflictResolutions.has(conflictKey(conflict));
                      return (
                        <div
                          key={`${conflict.entityIRI}-resolution-${index}`}
                          className={`border-b border-gray-300 last:border-b-0 ${isResolved ? "opacity-60" : ""}`}
                        >
                          {}
                          <div className="bg-gray-100 border-b border-gray-200 px-3 py-1.5 flex items-center justify-between">
                            <div className="flex items-center gap-2 min-w-0">
                              <span className="font-mono text-xs font-bold text-gray-800 truncate">
                                {toShortName(conflict.entityIRI)}
                              </span>
                              <span className="text-[10px] text-gray-500 whitespace-nowrap">
                                {conflict.entityType} · {conflict.conflictType}
                              </span>
                              <span
                                className={`text-[10px] px-1.5 py-0.5 rounded font-semibold ${
                                  conflict.severity === "HIGH" || conflict.severity === "CRITICAL"
                                    ? "bg-red-100 text-red-700"
                                    : conflict.severity === "MEDIUM"
                                      ? "bg-yellow-100 text-yellow-700"
                                      : "bg-blue-100 text-blue-700"
                                }`}
                              >
                                {conflict.severity}
                              </span>
                              {isResolved && (
                                <span className="text-[10px] px-1.5 py-0.5 rounded bg-green-100 text-green-700 font-semibold">
                                  ✓ {currentAction.replace(/_/g, " ")}
                                </span>
                              )}
                            </div>
                          </div>

                          {}
                          <div className="bg-gray-50 px-3 py-1 border-b border-gray-200 flex flex-wrap items-center gap-1 text-[11px]">
                            <button
                              type="button"
                              onClick={() => setResolution(conflict, "KEEP_SOURCE")}
                              className={`px-1.5 py-0.5 rounded hover:bg-blue-100 transition ${
                                currentAction === "KEEP_SOURCE"
                                  ? "text-blue-700 font-bold bg-blue-50"
                                  : "text-blue-600 hover:text-blue-800"
                              }`}
                            >
                              Accept Source (Incoming)
                            </button>
                            <span className="text-gray-300">|</span>
                            <button
                              type="button"
                              onClick={() => setResolution(conflict, "KEEP_TARGET")}
                              className={`px-1.5 py-0.5 rounded hover:bg-green-100 transition ${
                                currentAction === "KEEP_TARGET"
                                  ? "text-green-700 font-bold bg-green-50"
                                  : "text-green-600 hover:text-green-800"
                              }`}
                            >
                              Accept Target (Current)
                            </button>
                            <span className="text-gray-300">|</span>
                            <button
                              type="button"
                              onClick={() => setResolution(conflict, "MERGE")}
                              className={`px-1.5 py-0.5 rounded hover:bg-purple-100 transition ${
                                currentAction === "MERGE"
                                  ? "text-purple-700 font-bold bg-purple-50"
                                  : "text-purple-600 hover:text-purple-800"
                              }`}
                            >
                              Accept Both
                            </button>
                            <span className="text-gray-300">|</span>
                            <button
                              type="button"
                              onClick={() => setResolution(conflict, "RENAME_SOURCE")}
                              className={`px-1.5 py-0.5 rounded hover:bg-orange-100 transition ${
                                currentAction === "RENAME_SOURCE"
                                  ? "text-orange-700 font-bold bg-orange-50"
                                  : "text-orange-600 hover:text-orange-800"
                              }`}
                            >
                              Rename Source
                            </button>
                            <span className="text-gray-300">|</span>
                            <button
                              type="button"
                              onClick={() => setResolution(conflict, "SKIP")}
                              className={`px-1.5 py-0.5 rounded hover:bg-gray-200 transition ${
                                currentAction === "SKIP"
                                  ? "text-gray-700 font-bold bg-gray-100"
                                  : "text-gray-500 hover:text-gray-700"
                              }`}
                            >
                              Skip
                            </button>
                            {currentAction === "RENAME_SOURCE" && (
                              <>
                                <span className="text-gray-300 ml-1">|</span>
                                <span className="text-gray-500 ml-1">suffix:</span>
                                <input
                                  type="text"
                                  value={renameSuffix}
                                  onChange={(e) => setRenameSuffix(e.target.value)}
                                  className="border border-gray-300 rounded px-1.5 py-0 text-[11px] w-24 bg-white"
                                  placeholder="_imported"
                                />
                              </>
                            )}
                          </div>

                          {}
                          <div className="border-b border-gray-200">
                            <div className="bg-green-50 border-l-4 border-green-400">
                              <div className="px-3 py-1 text-[10px] font-bold text-green-800 bg-green-100 border-b border-green-200 font-mono">
                                {"<<<<<<< Source (Incoming Change)"}
                              </div>
                              <pre className="px-3 py-2 text-[11px] whitespace-pre-wrap break-words text-green-900 font-mono leading-relaxed max-h-48 overflow-y-auto">
                                {conflict.sourceDefinition || "(empty)"}
                              </pre>
                            </div>
                          </div>

                          {}
                          <div className="bg-gray-200 px-3 py-0.5 text-[10px] text-gray-600 font-mono text-center">
                            {"======="}
                          </div>

                          {}
                          <div>
                            <div className="bg-blue-50 border-l-4 border-blue-400">
                              <pre className="px-3 py-2 text-[11px] whitespace-pre-wrap break-words text-blue-900 font-mono leading-relaxed max-h-48 overflow-y-auto">
                                {conflict.targetDefinition || "(empty)"}
                              </pre>
                              <div className="px-3 py-1 text-[10px] font-bold text-blue-800 bg-blue-100 border-t border-blue-200 font-mono">
                                {">>>>>>> Target (Current Change)"}
                              </div>
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}

              <div className="flex justify-between mt-6">
                <button
                  onClick={() => setStep(2)}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50 flex items-center gap-2"
                >
                  <ChevronLeft className="w-4 h-4" /> Back
                </button>
                <button
                  onClick={() => { setMergeError(null); handleMerge(); }}
                  disabled={merging}
                  className="px-4 py-2 bg-green-600 text-white rounded-md hover:bg-green-700 disabled:bg-gray-300 disabled:cursor-not-allowed flex items-center gap-2"
                >
                  {merging ? (
                    <>
                      <Loader2 className="w-4 h-4 animate-spin" />
                      Merging...
                    </>
                  ) : (
                    <>
                      <GitMerge className="w-4 h-4" /> Execute Merge
                    </>
                  )}
                </button>
              </div>
              {mergeError && (
                <div className="mt-3 flex items-start gap-2 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">
                  <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0" />
                  <span>{mergeError}</span>
                </div>
              )}
            </div>
          )}

          {}
          {step === 4 && mergeComplete && mergeResult && (
            <div className="space-y-4 text-center">
              <div className="flex justify-center">
                <CheckCircle className="w-16 h-16 text-green-600" />
              </div>

              <div>
                <h3 className="text-xl font-semibold text-green-900 mb-2">Merge Completed Successfully!</h3>
                <p className="text-gray-600">The source ontology has been merged into {projectTitle}</p>
              </div>

              <div className="bg-gray-50 border border-gray-200 rounded-lg p-6 text-left">
                <h4 className="font-semibold mb-3">Merge Statistics</h4>
                <div className="grid grid-cols-2 gap-4 text-sm">
                  <div>
                    <span className="text-gray-600">Axioms Added:</span>
                    <span className="ml-2 font-medium">{mergeResult.axiomsAdded}</span>
                  </div>
                  {mergeResult.axiomsReplaced > 0 && (
                    <div>
                      <span className="text-gray-600">Axioms Replaced:</span>
                      <span className="ml-2 font-medium">{mergeResult.axiomsReplaced}</span>
                    </div>
                  )}
                  {mergeResult.entitiesRenamed > 0 && (
                    <div>
                      <span className="text-gray-600">Entities Renamed:</span>
                      <span className="ml-2 font-medium">{mergeResult.entitiesRenamed}</span>
                    </div>
                  )}
                  {mergeResult.conflictsResolved > 0 && (
                    <div>
                      <span className="text-gray-600">Conflicts Resolved:</span>
                      <span className="ml-2 font-medium">{mergeResult.conflictsResolved}</span>
                    </div>
                  )}
                  <div>
                    <span className="text-gray-600">Duration:</span>
                    <span className="ml-2 font-medium">{(mergeResult.durationMs / 1000).toFixed(2)}s</span>
                  </div>
                </div>
              </div>

              <div className="flex justify-center gap-3 mt-6">
                <button
                  onClick={handleDownload}
                  disabled={downloading}
                  className="px-6 py-2 bg-green-600 text-white rounded-md hover:bg-green-700 disabled:bg-gray-300 disabled:cursor-not-allowed flex items-center gap-2"
                >
                  {downloading ? (
                    <>
                      <Loader2 className="w-4 h-4 animate-spin" />
                      Downloading...
                    </>
                  ) : (
                    <>
                      <Download className="w-4 h-4" />
                      Download Merged Ontology
                    </>
                  )}
                </button>
                <button onClick={onClose} className="px-6 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700">
                  Done
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default MergeWizard;

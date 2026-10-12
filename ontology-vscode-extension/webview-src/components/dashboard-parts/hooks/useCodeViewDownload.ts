import { useCallback, useEffect, useRef, useState } from "react";
import { getBaseUrl } from "../../../services/apiClient";
import { notificationService } from "../../../services/notificationService";

const EXTENSION_BY_FORMAT: Record<string, string> = {
  rdfxml: "owl",
  turtle: "ttl",
  ntriples: "nt",
  owlxml: "owlxml",
  manchester: "omn",
  functional: "ofn",
  jsonld: "jsonld",
};

const DOWNLOAD_SAFETY_TIMEOUT_MS = 60 * 60 * 1000;

interface PendingDownload {
  requestId: number;
  filename: string;
}

export function useCodeViewDownload(projectId: string | null | undefined, format: string) {
  const [isDownloading, setIsDownloading] = useState(false);
  const requestIdRef = useRef(0);
  const pendingRef = useRef<PendingDownload | null>(null);
  const safetyTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearPending = () => {
    if (safetyTimeoutRef.current) {
      clearTimeout(safetyTimeoutRef.current);
      safetyTimeoutRef.current = null;
    }
    pendingRef.current = null;
    setIsDownloading(false);
  };

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const message = event.data;
      if (!message || (message.type !== "downloadOntologyComplete" && message.type !== "downloadOntologyFailed")) return;
      const pending = pendingRef.current;
      if (!pending || message.requestId !== pending.requestId) return;
      clearPending();
      if (message.type === "downloadOntologyComplete") {
        notificationService.success("Export Complete", `${pending.filename} downloaded`);
      } else if (!message.cancelled) {
        notificationService.error("Export Failed", message.error || `Could not export ${pending.filename}`);
      }
    };
    window.addEventListener("message", handleMessage);
    return () => window.removeEventListener("message", handleMessage);
  }, []);

  const download = useCallback(() => {
    if (!projectId || isDownloading) return;
    const filename = `${projectId}.${EXTENSION_BY_FORMAT[format] || "owl"}`;
    const url = `${getBaseUrl()}/api/ontology/export/${encodeURIComponent(projectId)}?format=${format}`;
    if (!window.vscode) {
      window.open(url, "_blank");
      return;
    }
    requestIdRef.current += 1;
    const requestId = requestIdRef.current;
    pendingRef.current = { requestId, filename };
    setIsDownloading(true);
    window.vscode.postMessage({ type: "downloadOntology", url, filename, projectId, format, requestId });
    notificationService.info("Exporting…", `${filename} — this can take a few minutes for large ontologies`);
    if (safetyTimeoutRef.current) clearTimeout(safetyTimeoutRef.current);
    safetyTimeoutRef.current = setTimeout(() => {
      if (pendingRef.current?.requestId !== requestId) return;
      pendingRef.current = null;
      setIsDownloading(false);
      notificationService.error("Export Timed Out", `${filename} export did not finish in time. Please try again.`);
    }, DOWNLOAD_SAFETY_TIMEOUT_MS);
  }, [projectId, format, isDownloading]);

  return { isDownloading, download };
}

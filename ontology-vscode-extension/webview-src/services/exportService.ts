// services/exportService.ts
//
// Submit-then-poll ontology export, used instead of one long-blocking download
// request. A large ontology's export can take longer than a fixed client-side
// request timeout even though the backend/gateway/ingress are all configured
// for up to 2 hours — the request gets aborted client-side while the server is
// still working. See OntologyExportJobService.java (backend) for the job
// lifecycle this polls against.
import apiClient from './apiClient';

const POLL_INTERVAL_MS = 3000;
// Client-side ceiling, comfortably above the backend's own 45-min stuck-job
// watchdog — that watchdog is what actually converts a hung job into ERROR;
// this is just a last-resort guard against polling forever.
const MAX_POLL_MS = 60 * 60 * 1000;
/** Prefer streaming to disk when the export is larger than this (browser Blob OOM risk). */
const STREAM_TO_DISK_THRESHOLD_BYTES = 40 * 1024 * 1024;
const DOWNLOAD_TIMEOUT_MS = 30 * 60 * 1000;

interface SubmitExportResponse {
  success: boolean;
  jobId: string;
  status: string;
}

interface ExportJobStatusResponse {
  success: boolean;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'ERROR';
  error?: string;
}

async function submitExportJob(baseUrl: string, projectId: string, format: string): Promise<string> {
  const res = await apiClient.post<SubmitExportResponse>(
    `${baseUrl}/api/ontology/export-async/${encodeURIComponent(projectId)}?format=${encodeURIComponent(format)}`,
  );
  if (!res?.jobId) {
    throw new Error('Export could not be started.');
  }
  return res.jobId;
}

async function waitForExportJob(baseUrl: string, jobId: string): Promise<void> {
  const deadline = Date.now() + MAX_POLL_MS;
  // eslint-disable-next-line no-constant-condition
  while (true) {
    const res = await apiClient.get<ExportJobStatusResponse>(`${baseUrl}/api/ontology/export-async/status/${jobId}`);
    if (res?.status === 'COMPLETED') return;
    if (res?.status === 'ERROR') {
      throw new Error(res?.error || 'Export failed.');
    }
    if (Date.now() >= deadline) {
      throw new Error('Export is taking much longer than expected. Please try again later.');
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
  }
}

function authHeaders(): HeadersInit {
  const token = typeof localStorage !== 'undefined' ? localStorage.getItem('authToken') : null;
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function triggerBlobDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  // Delay revoke so the browser can start reading the blob URL.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

type SaveFilePickerWindow = Window & {
  showSaveFilePicker?: (options?: {
    suggestedName?: string;
    types?: Array<{ description?: string; accept: Record<string, string[]> }>;
  }) => Promise<{
    createWritable: () => Promise<{
      write: (data: Uint8Array | Blob | ArrayBuffer) => Promise<void>;
      close: () => Promise<void>;
      abort: () => Promise<void>;
    }>;
  }>;
};

async function streamResponseToDisk(response: Response, filename: string): Promise<boolean> {
  const w = window as SaveFilePickerWindow;
  if (!w.showSaveFilePicker || !response.body) {
    return false;
  }
  const handle = await w.showSaveFilePicker({ suggestedName: filename });
  const writable = await handle.createWritable();
  try {
    const reader = response.body.getReader();
    // eslint-disable-next-line no-constant-condition
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value) await writable.write(value);
    }
    await writable.close();
    return true;
  } catch (err) {
    try {
      await writable.abort();
    } catch {
      /* ignore */
    }
    throw err;
  }
}

/**
 * Submits an ontology export as a background job, polls until ready, then downloads
 * the result. For large exports, streams to disk via the File System Access API when
 * available so the browser never holds a full ~200MB+ Blob in memory (VS Code writes
 * to disk from the extension host; the web path used to OOM on Blob).
 */
export async function exportOntologyAsBlob(
  baseUrl: string,
  projectId: string,
  format: string,
  filename?: string,
): Promise<Blob | void> {
  const jobId = await submitExportJob(baseUrl, projectId, format);
  await waitForExportJob(baseUrl, jobId);

  const downloadUrl = `${baseUrl}/api/ontology/export-async/download/${jobId}`;
  const safeName = filename || `ontology-export.${format === 'turtle' ? 'ttl' : format === 'ntriples' ? 'nt' : 'owl'}`;

  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), DOWNLOAD_TIMEOUT_MS);
  try {
    const response = await fetch(downloadUrl, {
      headers: authHeaders(),
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new Error(`Export download failed (${response.status}).`);
    }

    const contentLength = Number(response.headers.get('content-length') || 0);
    const preferStream = !contentLength || contentLength >= STREAM_TO_DISK_THRESHOLD_BYTES;

    if (preferStream) {
      try {
        const streamed = await streamResponseToDisk(response, safeName);
        if (streamed) return;
      } catch (streamErr) {
        // User cancelled the save dialog — don't fall through to another Blob attempt.
        if (streamErr instanceof DOMException && streamErr.name === 'AbortError') {
          throw new Error('Export download cancelled.');
        }
        console.warn('[exportService] Stream-to-disk failed, falling back to Blob:', streamErr);
        // Body already consumed if streaming started — re-fetch for Blob fallback.
        const retry = await fetch(downloadUrl, { headers: authHeaders() });
        if (!retry.ok) {
          throw new Error(`Export download failed (${retry.status}).`);
        }
        const blob = await retry.blob();
        triggerBlobDownload(blob, safeName);
        return blob;
      }
    }

    const blob = await response.blob();
    triggerBlobDownload(blob, safeName);
    return blob;
  } finally {
    window.clearTimeout(timeout);
  }
}

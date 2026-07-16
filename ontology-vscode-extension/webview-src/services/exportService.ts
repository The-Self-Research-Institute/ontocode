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

async function waitForExportJob(baseUrl: string, jobId: string, signal?: AbortSignal): Promise<void> {
  const deadline = Date.now() + MAX_POLL_MS;
  // eslint-disable-next-line no-constant-condition
  while (true) {
    if (signal?.aborted) {
      throw new Error('Export download cancelled.');
    }
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

// ── Cancellation ────────────────────────────────────────────────────────────
// One in-flight export per project. The Dashboard's export pill (web/desktop)
// calls cancelOntologyExport(); VS Code exports run in the extension host with
// their own cancellable progress notification and never register here.
const activeExports = new Map<string, AbortController>();

export type ExportStatus = 'started' | 'completed' | 'cancelled' | 'failed';

function emitExportStatus(projectId: string, filename: string, status: ExportStatus) {
  window.dispatchEvent(new CustomEvent('ontocode:export-status', { detail: { projectId, filename, status } }));
}

/** Cancel the in-flight export for a project. Returns false when none is running. */
export function cancelOntologyExport(projectId: string): boolean {
  const controller = activeExports.get(projectId);
  if (!controller) return false;
  controller.abort();
  return true;
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

type SaveFileHandle = {
  createWritable: () => Promise<{
    write: (data: Uint8Array | Blob | ArrayBuffer) => Promise<void>;
    close: () => Promise<void>;
    abort: () => Promise<void>;
  }>;
};

type SaveFilePickerWindow = Window & {
  showSaveFilePicker?: (options?: {
    suggestedName?: string;
    types?: Array<{ description?: string; accept: Record<string, string[]> }>;
  }) => Promise<SaveFileHandle>;
};

/**
 * Ask for the destination file BEFORE the export job runs. showSaveFilePicker
 * requires transient user activation — a few seconds after the Export click —
 * and a large export job polls for minutes, so requesting the picker after the
 * job finishes throws SecurityError, which silently disabled stream-to-disk for
 * exactly the large exports it was built for.
 *
 * Returns null when the API is unsupported or not permitted (caller falls back
 * to a Blob download); throws "cancelled" when the user dismissed the dialog.
 */
async function acquireSaveFileHandleUpfront(filename: string): Promise<SaveFileHandle | null> {
  const w = window as SaveFilePickerWindow;
  if (!w.showSaveFilePicker) return null;
  try {
    return await w.showSaveFilePicker({ suggestedName: filename });
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') {
      throw new Error('Export download cancelled.');
    }
    console.warn('[exportService] Save dialog unavailable — will download as Blob:', err);
    return null;
  }
}

async function streamResponseToHandle(response: Response, handle: SaveFileHandle): Promise<boolean> {
  if (!response.body) return false;
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
  const safeName = filename || `ontology-export.${format === 'turtle' ? 'ttl' : format === 'ntriples' ? 'nt' : 'owl'}`;

  if (activeExports.has(projectId)) {
    throw new Error('An export is already in progress for this project.');
  }

  // Must be first — needs the Export click's transient user activation, which
  // expires long before a large export job finishes. Cancelling here also
  // skips submitting the job entirely (and never registers/announces the export).
  const handle = await acquireSaveFileHandleUpfront(safeName);

  const controller = new AbortController();
  const signal = controller.signal;
  activeExports.set(projectId, controller);
  emitExportStatus(projectId, safeName, 'started');

  // The download watchdog also aborts this controller — track which one fired
  // so a user cancel and a timeout report differently.
  let timedOut = false;
  let outcome: ExportStatus = 'failed';

  try {
    const jobId = await submitExportJob(baseUrl, projectId, format);
    await waitForExportJob(baseUrl, jobId, signal);

    const downloadUrl = `${baseUrl}/api/ontology/export-async/download/${jobId}`;
    const timeout = window.setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, DOWNLOAD_TIMEOUT_MS);
    try {
      const response = await fetch(downloadUrl, {
        headers: authHeaders(),
        signal,
      });
      if (!response.ok) {
        throw new Error(`Export download failed (${response.status}).`);
      }

      if (handle) {
        try {
          const streamed = await streamResponseToHandle(response, handle);
          if (streamed) {
            outcome = 'completed';
            return;
          }
        } catch (streamErr) {
          // Cancelled/timed out mid-stream — surface it, never fall back to a Blob retry.
          if (signal.aborted) {
            throw streamErr;
          }
          console.warn('[exportService] Stream-to-disk failed, falling back to Blob:', streamErr);
          // Body already consumed once streaming started — re-fetch for the Blob fallback.
          const retry = await fetch(downloadUrl, { headers: authHeaders(), signal });
          if (!retry.ok) {
            throw new Error(`Export download failed (${retry.status}).`);
          }
          const blob = await retry.blob();
          triggerBlobDownload(blob, safeName);
          outcome = 'completed';
          return blob;
        }
      }

      const blob = await response.blob();
      triggerBlobDownload(blob, safeName);
      outcome = 'completed';
      return blob;
    } finally {
      window.clearTimeout(timeout);
    }
  } catch (err) {
    if (signal.aborted && !timedOut) {
      outcome = 'cancelled';
      throw new Error('Export download cancelled.');
    }
    if (timedOut) {
      throw new Error('Export download timed out. Please try again.');
    }
    throw err;
  } finally {
    activeExports.delete(projectId);
    emitExportStatus(projectId, safeName, outcome);
  }
}

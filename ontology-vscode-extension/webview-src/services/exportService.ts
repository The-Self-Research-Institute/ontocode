

import apiClient from './apiClient';

const POLL_INTERVAL_MS = 3000;

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

const activeExports = new Map<string, AbortController>();

export type ExportStatus = 'started' | 'completed' | 'cancelled' | 'failed';

function emitExportStatus(projectId: string, filename: string, status: ExportStatus) {
  window.dispatchEvent(new CustomEvent('ontocode:export-status', { detail: { projectId, filename, status } }));
}

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

  const handle = await acquireSaveFileHandleUpfront(safeName);

  const controller = new AbortController();
  const signal = controller.signal;
  activeExports.set(projectId, controller);
  emitExportStatus(projectId, safeName, 'started');

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

          if (signal.aborted) {
            throw streamErr;
          }
          console.warn('[exportService] Stream-to-disk failed, falling back to Blob:', streamErr);

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

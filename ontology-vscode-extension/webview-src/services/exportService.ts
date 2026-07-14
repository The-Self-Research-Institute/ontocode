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

/** Submits an ontology export as a background job, polls until ready, then downloads the result as a Blob. */
export async function exportOntologyAsBlob(baseUrl: string, projectId: string, format: string): Promise<Blob> {
  const jobId = await submitExportJob(baseUrl, projectId, format);
  await waitForExportJob(baseUrl, jobId);
  return apiClient.get<Blob>(`${baseUrl}/api/ontology/export-async/download/${jobId}`, undefined, {
    responseType: 'blob' as any,
  });
}

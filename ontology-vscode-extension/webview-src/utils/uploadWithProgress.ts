

export interface UploadProgressInfo {
  percent: number;
  loaded: number;
  total: number;
}

export interface UploadWithProgressResult {
  ok: boolean;
  status: number;
  text: string;
}

export function extractUploadProjectId(url: string): string | undefined {
  const uploadMatch = url.match(/\/api\/ontology\/upload\/([^/?]+)/);
  if (uploadMatch) return decodeURIComponent(uploadMatch[1]);
  const filesMatch = url.match(/\/api\/projects\/([^/]+)\/files/);
  if (filesMatch) return decodeURIComponent(filesMatch[1]);
  return undefined;
}

export function postUploadProgress(
  projectId: string,
  info: UploadProgressInfo,
  message?: string,
): void {
  const statusMsg =
    message ??
    (info.percent >= 100
      ? "Upload complete. Processing on server..."
      : `Uploading: ${info.percent}%`);
  window.dispatchEvent(
    new MessageEvent("message", {
      data: {
        type: "uploadProgress",
        projectId,
        percent: info.percent,
        loaded: info.loaded,
        total: info.total,
        message: statusMsg,
      },
    }),
  );
}

export function uploadFormDataWithProgress(
  url: string,
  formData: FormData,
  options: {
    headers?: Record<string, string | undefined>;
    timeoutMs?: number;
    projectId?: string;
    onProgress?: (info: UploadProgressInfo) => void;
  } = {},
): Promise<UploadWithProgressResult> {
  const projectId = options.projectId ?? extractUploadProjectId(url);

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", url);
    if (options.timeoutMs) {
      xhr.timeout = options.timeoutMs;
    }

    if (options.headers) {
      for (const [key, value] of Object.entries(options.headers)) {
        if (value) {
          xhr.setRequestHeader(key, value);
        }
      }
    }

    xhr.upload.addEventListener("progress", (event) => {
      if (!event.lengthComputable) {
        return;
      }
      const info: UploadProgressInfo = {
        percent: Math.round((event.loaded / event.total) * 100),
        loaded: event.loaded,
        total: event.total,
      };
      options.onProgress?.(info);
      if (projectId) {
        postUploadProgress(projectId, info);
      }
    });

    xhr.addEventListener("load", () => {
      resolve({
        ok: xhr.status >= 200 && xhr.status < 300,
        status: xhr.status,
        text: xhr.responseText,
      });
    });
    xhr.addEventListener("error", () => reject(new Error("Upload network error")));
    xhr.addEventListener("timeout", () => reject(new Error("Upload timed out")));
    xhr.addEventListener("abort", () => reject(new Error("Upload aborted")));

    xhr.send(formData);
  });
}

function splitIntoChunks(data: Uint8Array, chunkSize: number): Uint8Array[] {
  const chunks: Uint8Array[] = [];
  for (let offset = 0; offset < data.length; offset += chunkSize) {
    chunks.push(data.slice(offset, offset + chunkSize));
  }
  return chunks;
}

async function sha256Hex(data: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", data as BufferSource);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function uploadBlobInChunks(
  projectId: string,
  data: Uint8Array,
  fileName: string,
  baseUrl: string,
  opts: {
    headers?: Record<string, string | undefined>;
    ownerEmail?: string;
    workspaceId?: string;
    importMode?: string;
    partition?: string;
    action?: string;
    compressed?: boolean;
  } = {},
): Promise<UploadWithProgressResult> {
  const CHUNK_SIZE = 20 * 1024 * 1024; // 20MB per chunk
  const MAX_RETRIES_PER_CHUNK = 3;

  const uploadId = crypto.randomUUID();
  const chunks = splitIntoChunks(data, CHUNK_SIZE);
  const totalChunks = chunks.length;
  const totalBytes = data.length;

  console.log(`[uploadBlobInChunks] Starting: uploadId=${uploadId}, ${totalChunks} chunks, ${(totalBytes / (1024 * 1024)).toFixed(1)}MB total`);

  let uploadedBytes = 0;
  let finalText = "";
  let finalStatus = 0;

  for (let i = 0; i < totalChunks; i++) {
    const chunk = chunks[i];
    const chunkHash = await sha256Hex(chunk);
    const isLastChunk = i === totalChunks - 1;

    let succeeded = false;
    let lastError: Error | null = null;

    for (let attempt = 0; attempt < MAX_RETRIES_PER_CHUNK; attempt++) {
      try {
        if (attempt > 0) {
          const delay = Math.pow(2, attempt) * 1000;
          await new Promise((resolve) => setTimeout(resolve, delay));
        }

        const form = new FormData();
        form.append("chunk", new Blob([chunk as BlobPart]), fileName);
        form.append("uploadId", uploadId);
        form.append("chunkIndex", String(i));
        form.append("totalChunks", String(totalChunks));
        form.append("chunkHash", chunkHash);
        form.append("fileName", fileName);
        if (opts.ownerEmail) form.append("ownerEmail", opts.ownerEmail);
        if (opts.action) form.append("action", opts.action);
        if (opts.importMode) form.append("importMode", opts.importMode);
        if (opts.partition) form.append("partition", opts.partition);
        if (opts.workspaceId) form.append("workspaceId", opts.workspaceId);
        form.append("compressed", String(!!opts.compressed));

        const headers: Record<string, string> = {};
        if (opts.headers) {
          for (const [key, value] of Object.entries(opts.headers)) {
            if (value) headers[key] = value;
          }
        }

        const chunkUrl = `${baseUrl}/api/ontology/upload-chunk/${encodeURIComponent(projectId)}`;
        const res = await fetch(chunkUrl, { method: "POST", headers, body: form });
        const text = await res.text();

        if (!res.ok) {
          throw new Error(`Chunk ${i + 1}/${totalChunks} failed with status ${res.status}: ${text.slice(0, 200)}`);
        }

        uploadedBytes += chunk.length;
        const percent = Math.round((uploadedBytes / totalBytes) * 100);
        postUploadProgress(
          projectId,
          { percent, loaded: uploadedBytes, total: totalBytes },
          isLastChunk ? "Upload complete. Processing on server..." : `Uploading chunk ${i + 1}/${totalChunks}: ${percent}%`,
        );

        if (isLastChunk) {
          finalText = text;
          finalStatus = res.status;
        }
        succeeded = true;
        break;
      } catch (err) {
        lastError = err instanceof Error ? err : new Error(String(err));
        console.error(`[uploadBlobInChunks] Chunk ${i + 1}/${totalChunks} attempt ${attempt + 1} failed:`, lastError);
      }
    }

    if (!succeeded) {
      throw lastError || new Error(`Failed to upload chunk ${i + 1}/${totalChunks}`);
    }
  }

  return { ok: finalStatus >= 200 && finalStatus < 300, status: finalStatus, text: finalText };
}

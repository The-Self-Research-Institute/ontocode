/**
 * Multipart upload with byte-level progress (fetch does not expose upload progress).
 */

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

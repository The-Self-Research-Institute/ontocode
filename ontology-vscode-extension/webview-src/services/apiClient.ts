
import axios, { AxiosError, AxiosInstance, AxiosRequestConfig } from 'axios';
import { getGatewayUrl, getStoredDeploymentType, type DeploymentType } from '../config/deploymentConfig';
import { isDesktop } from '../utils/desktop';
import { resolveMutationUserId } from '../utils/mutationActor';

let BASE_URL = getGatewayUrl();

if (typeof window !== 'undefined') {
  (window as any).API_BASE_URL = BASE_URL;
}

export const updateBaseUrl = (deploymentType: DeploymentType) => {
  BASE_URL = getGatewayUrl(deploymentType);

  if (typeof window !== 'undefined') {
    (window as any).API_BASE_URL = BASE_URL;
  }

  ApiClient.getInstance().updateAxiosBaseUrl(BASE_URL);
};

export const getBaseUrl = () => BASE_URL;

const TIMEOUT = 600_000; // Default API timeout (10 minutes)
const UPLOAD_TIMEOUT = 7_200_000; // Up to 1GB uploads through gateway/editor (2 hours)

function isUploadRequest(url: string): boolean {
  return url.includes('/api/ontology/upload/') || /\/api\/projects\/[^/]+\/files/.test(url);
}

function isLongRunningRequest(url: string): boolean {
  return /\/merge\/(analyze|execute)/.test(url) || url.includes('/content-page');
}

function requestTimeoutFor(url: string): number {
  return isUploadRequest(url) || isLongRunningRequest(url) ? UPLOAD_TIMEOUT : TIMEOUT;
}

function extractUploadProjectId(url: string): string | undefined {
  const uploadMatch = url.match(/\/api\/ontology\/upload\/([^/?]+)/);
  if (uploadMatch) return decodeURIComponent(uploadMatch[1]);
  const filesMatch = url.match(/\/api\/projects\/([^/]+)\/files/);
  if (filesMatch) return decodeURIComponent(filesMatch[1]);
  return undefined;
}

function dispatchUploadProgress(projectId: string, progressEvent: { loaded: number; total?: number }) {
  const total = progressEvent.total ?? 0;
  const percent = total ? Math.round((progressEvent.loaded * 100) / total) : 0;
  const message =
    percent >= 100
      ? 'Upload complete. Processing on server...'
      : `Uploading: ${percent}%`;
  window.dispatchEvent(
    new MessageEvent('message', {
      data: {
        type: 'uploadProgress',
        projectId,
        percent,
        loaded: progressEvent.loaded,
        total,
        message,
      },
    }),
  );
}

declare global {
  interface Window {
    vscode?: { postMessage: (message: any) => void };
    API_BASE_URL: string;
  }
}

export class ApiError extends Error {
  constructor(
    message: string,
    public status?: number,
    public data?: any,
    public code?: string
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

const genReqId = () => `req-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;

const pending = new Map<
  string,
  { resolve: (v: any) => void; reject: (r?: any) => void; timeout: ReturnType<typeof setTimeout>; url?: string }
>();

class ApiClient {
  private static _instance: ApiClient;
  private axiosClient: AxiosInstance | null = null;

  private isVSCode = typeof window !== 'undefined' &&
    !!window.vscode &&
    !(window as any).electronAPI &&
    !(window as any).__ONTOCODE_CONFIG__?.IS_WEB_EXTENSION &&
    !(window as any).__ONTOCODE_BROWSER_BRIDGE__;
  private listenerAttached = false;
  private onUnauthorized?: () => void; // Callback for 401 errors
  private onMaintenance?: (message: string) => void; // Callback for 503 maintenance

  static getInstance() {
    if (!this._instance) this._instance = new ApiClient();
    return this._instance;
  }

  setUnauthorizedCallback(callback: () => void) {
    this.onUnauthorized = callback;
  }

  setMaintenanceCallback(callback: (message: string) => void) {
    this.onMaintenance = callback;
  }

  updateAxiosBaseUrl(newBaseUrl: string) {
    if (this.axiosClient) {
      this.axiosClient.defaults.baseURL = newBaseUrl;
    }
  }

  private constructor() {

    if (this.isVSCode) {

      this.attachVSCodeListener();
    } else {

      this.setupAxios();
    }
  }

  private attachVSCodeListener() {
    if (this.listenerAttached) return;
    window.addEventListener('message', (event) => {
      const msg = event.data;
      if (msg?.type !== 'apiResponse') return; //

      const { requestId, response, error } = msg;
      const p = pending.get(requestId);
      if (!p) return;

      clearTimeout(p.timeout);
      if (error) {

        if (error.status === 401 && this.onUnauthorized) {
          this.onUnauthorized();
        }

        if (error.status === 503 && (error.data?.maintenance === true || error.maintenance === true) && this.onMaintenance) {
          const msg = error.data?.message || error.data?.error || error.message || 'System is under maintenance.';
          this.onMaintenance(msg);
        }
        p.reject(new ApiError(error.message || 'API request failed via proxy', error.status, error.data, error.code));
      } else {
        p.resolve(response);
      }
      pending.delete(requestId);
    });
    this.listenerAttached = true;
  }

  private postViaVSCode<T>(payload: { type: string; url: string;[k: string]: any }): Promise<T> {
    return new Promise((resolve, reject) => {
      if (!window.vscode) {
        reject(new ApiError('No VSCode webview detected', 0, null, 'VSCODE_NOT_AVAILABLE'));
        return;
      }
      const requestId = genReqId();
      const requestTimeout = requestTimeoutFor(payload.url);
      const timeout = setTimeout(() => {
        if (pending.has(requestId)) {
          pending.get(requestId)?.reject(new ApiError(`Request ${requestId} timed out after ${requestTimeout / 1000}s`, 408, null, 'TIMEOUT'));
          pending.delete(requestId);
        }
      }, requestTimeout);

      pending.set(requestId, { resolve, reject, timeout });

      const token = isDesktop() ? null : localStorage.getItem('authToken');
      window.vscode.postMessage({
        ...payload,
        requestId,
        headers: token ? { Authorization: `Bearer ${token}` } : undefined
      });
    });
  }

  private setupAxios() {
    this.axiosClient = axios.create({
      baseURL: BASE_URL,
      headers: {
        'Content-Type': 'application/json'
      },
      timeout: TIMEOUT,
      maxContentLength: Infinity,
      maxBodyLength: Infinity
    });

    this.axiosClient.interceptors.request.use((config) => {
      const desktopUrl = (window as any).__DESKTOP_API_URL__;
      if (desktopUrl && config.baseURL !== desktopUrl) {
        config.baseURL = desktopUrl;
        BASE_URL = desktopUrl;

        (window as any).API_BASE_URL = desktopUrl;
      }

      if (!isDesktop()) {
        const token = localStorage.getItem('authToken');
        if (token && config.headers) {
          config.headers.Authorization = `Bearer ${token}`;
        }
      }
      const mutationUserId = resolveMutationUserId();
      if (mutationUserId && config.headers) {
        config.headers['X-Ontocode-User-Id'] = mutationUserId;
      }

      if (config.method === 'get' && config.headers) {
        config.headers['Cache-Control'] = 'no-cache, no-store';
        config.headers['Pragma'] = 'no-cache';
      }
      return config;
    });

    this.axiosClient.interceptors.response.use(
      (resp) => resp,
      (err: AxiosError) => {
        const status = err.response?.status;
        const data = err.response?.data as any;
        let msg =
          (data && (data.message || data.error)) ||
          (typeof data === 'string' ? data : undefined) ||
          (status === 401 ? 'Unauthorized' : err.message || 'Unexpected error');

        if (status === 503 && (data?.maintenance === true) && this.onMaintenance) {
          const maintenanceMsg = data?.message || data?.error || 'System is under maintenance.';
          this.onMaintenance(maintenanceMsg);
        }

        if (status === 504 || status === 502 || status === 503) {
          msg = 'The server is busy or this request took too long. Please wait a moment and try again.';
        } else if (status === 408 || err.code === 'ECONNABORTED') {
          msg = 'Request timed out. Large ontologies may need a retry — your data is still safe.';
        } else if (!status && (err.code === 'ERR_NETWORK' || err.message?.includes('Network Error'))) {
          msg = 'Network error. Check your connection and try again.';
        }

        if (status === 401 && this.onUnauthorized) {
          this.onUnauthorized();
        }

        throw new ApiError(msg, status, data, err.code);
      }
    );
  }

  async get<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<T> {
    let data: T;
    if (this.isVSCode) {
      data = await this.postViaVSCode<T>({ type: 'apiGet', url, params, headers: config?.headers });
    } else {
      const resp = await this.axiosClient!.get(url, { ...config, params });
      data = resp.data as T;
    }
    return data;
  }

  async post<T = any>(url: string, body?: any, config?: AxiosRequestConfig): Promise<T> {
    let data: T;
    if (this.isVSCode) {

      if (body instanceof FormData) {
        const fileEntry = body.get('file') as File | null;
        const msgBody: Record<string, any> = {};
        body.forEach((val: FormDataEntryValue, key: string) => {
          if (key !== 'file') msgBody[key] = val;
        });
        if (fileEntry) {
          const buf = await fileEntry.arrayBuffer();

          const bytes = new Uint8Array(buf);
          const chunks: string[] = [];
          const chunkSize = 32768;
          for (let i = 0; i < bytes.length; i += chunkSize) {
            chunks.push(String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + chunkSize, bytes.length)) as any));
          }
          msgBody._fileBase64 = btoa(chunks.join(''));
          msgBody._fileFieldName = 'file';
          msgBody._originalFileName = fileEntry.name;

          let contentType = fileEntry.type;
          if (!contentType || contentType === '') {
            const fileName = fileEntry.name.toLowerCase();
            if (fileName.endsWith('.owl')) contentType = 'application/rdf+xml';
            else if (fileName.endsWith('.rdf')) contentType = 'application/rdf+xml';
            else if (fileName.endsWith('.ttl')) contentType = 'text/turtle';
            else if (fileName.endsWith('.n3')) contentType = 'text/n3';
            else contentType = 'application/octet-stream';
          }
          msgBody.fileType = contentType;
        }
        msgBody._isMultipart = true;
        data = await this.postViaVSCode<T>({ type: 'apiPost', url, body: msgBody, params: config?.params, headers: config?.headers });
      } else {
        data = await this.postViaVSCode<T>({ type: 'apiPost', url, body, params: config?.params, headers: config?.headers });
      }
    } else {

      const uploadProjectId = isUploadRequest(url) ? extractUploadProjectId(url) : undefined;
      const userOnUploadProgress = config?.onUploadProgress;
      const postConfig = body instanceof FormData
        ? {
            ...config,
            timeout: requestTimeoutFor(url),
            headers: { ...config?.headers, 'Content-Type': undefined },
            onUploadProgress: (progressEvent: { loaded: number; total?: number }) => {
              userOnUploadProgress?.(progressEvent as any);
              if (uploadProjectId && progressEvent.total) {
                dispatchUploadProgress(uploadProjectId, progressEvent);
              }
            },
          }
        : {
            ...config,
            timeout: isUploadRequest(url) || isLongRunningRequest(url) ? UPLOAD_TIMEOUT : config?.timeout ?? TIMEOUT,
            onUploadProgress: userOnUploadProgress
              ? (progressEvent: { loaded: number; total?: number }) => {
                  userOnUploadProgress(progressEvent as any);
                  if (uploadProjectId && progressEvent.total) {
                    dispatchUploadProgress(uploadProjectId, progressEvent);
                  }
                }
              : uploadProjectId
                ? (progressEvent: { loaded: number; total?: number }) => {
                    if (progressEvent.total) {
                      dispatchUploadProgress(uploadProjectId, progressEvent);
                    }
                  }
                : undefined,
          };
      const resp = await this.axiosClient!.post(url, body, postConfig);
      data = resp.data as T;
    }
    return data;
  }

  async put<T = any>(url: string, body?: any, config?: AxiosRequestConfig): Promise<T> {
    let data: T;
    if (this.isVSCode) {
      data = await this.postViaVSCode<T>({ type: 'apiPut', url, body, params: config?.params, headers: config?.headers });
    } else {
      const resp = await this.axiosClient!.put(url, body, config);
      data = resp.data as T;
    }
    return data;
  }

  async patch<T = any>(url: string, body?: any, config?: AxiosRequestConfig): Promise<T> {
    let data: T;
    if (this.isVSCode) {
      data = await this.postViaVSCode<T>({ type: 'apiPatch', url, body, params: config?.params, headers: config?.headers });
    } else {
      const resp = await this.axiosClient!.patch(url, body, config);
      data = resp.data as T;
    }
    return data;
  }

  async delete<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<T> {
    let data: T;
    if (this.isVSCode) {
      data = await this.postViaVSCode<T>({ type: 'apiDelete', url, params, headers: config?.headers });
    } else {
      const resp = await this.axiosClient!.delete(url, { ...config, params });
      data = resp.data as T;
    }
    return data;
  }
}

const apiClient = ApiClient.getInstance();
export default apiClient;
export type { AxiosRequestConfig };

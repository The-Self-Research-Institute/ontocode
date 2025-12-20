// services/apiClient.ts
import axios, { AxiosError, AxiosInstance, AxiosRequestConfig } from 'axios';

const BASE_URL = 'http://localhost:8082'; // Your backend gateway
const TIMEOUT = 120_000; // 120-second timeout for requests (increased from 60s for large ontologies)

// VS Code API detection
declare global {
  interface Window {
    vscode?: { postMessage: (message: any) => void };
    API_BASE_URL: string;
  }
}

/**
 * Custom error class to normalize backend and proxy errors.
 *
 */
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

// Generates a unique ID for proxy requests
const genReqId = () => `req-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;

// Map to track pending requests sent to the VS Code proxy
const pending = new Map<
  string,
  { resolve: (v: any) => void; reject: (r?: any) => void; timeout: ReturnType<typeof setTimeout> }
>();

/**
 * A singleton ApiClient that handles both standard web and VS Code proxy communication.
 *
 */
class ApiClient {
  private static _instance: ApiClient;
  private axiosClient: AxiosInstance | null = null;
  private isVSCode = typeof window !== 'undefined' && !!window.vscode; //
  private listenerAttached = false;
  private onUnauthorized?: () => void; // Callback for 401 errors

  static getInstance() {
    if (!this._instance) this._instance = new ApiClient();
    return this._instance;
  }

  /**
   * Register a callback to handle 401 Unauthorized errors (token expired)
   */
  setUnauthorizedCallback(callback: () => void) {
    this.onUnauthorized = callback;
  }

  private constructor() {
    if (this.isVSCode) {
      // If in VS Code, set up the message listener
      this.attachVSCodeListener();
    } else {
      // If in browser, set up a standard Axios client
      this.setupAxios();
    }
  }

  // ---------- VS Code proxy mode ----------

  /**
   * Listens for 'apiResponse' messages from the extension.
   *
   */
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
        // Check for 401 Unauthorized
        if (error.status === 401 && this.onUnauthorized) {
          console.log('[ApiClient] 401 Unauthorized - Token expired');
          this.onUnauthorized();
        }
        p.reject(new ApiError(error.message || 'API request failed via proxy', error.status, error.data, error.code));
      } else {
        p.resolve(response);
      }
      pending.delete(requestId);
    });
    this.listenerAttached = true;
  }

  /**
   * Sends a request to the extension proxy and waits for an 'apiResponse' message.
   *
   */
  private postViaVSCode<T>(payload: { type: string; url: string; [k: string]: any }): Promise<T> {
    return new Promise((resolve, reject) => {
      if (!window.vscode) {
        reject(new ApiError('No VSCode webview detected', 0, null, 'VSCODE_NOT_AVAILABLE'));
        return;
      }
      const requestId = genReqId();
      const timeout = setTimeout(() => {
        if (pending.has(requestId)) {
          pending.get(requestId)?.reject(new ApiError(`Request ${requestId} timed out after ${TIMEOUT / 1000}s`, 408, null, 'TIMEOUT'));
          pending.delete(requestId);
        }
      }, TIMEOUT);

      pending.set(requestId, { resolve, reject, timeout });

      // Get auth token from localStorage (managed by useAuth hook)
      const token = localStorage.getItem('authToken');
      window.vscode.postMessage({
        ...payload,
        requestId,
        headers: token ? { Authorization: `Bearer ${token}` } : undefined
      });
    });
  }

  // ---------- Axios (browser) mode ----------

  /**
   * Creates a standard Axios client for browser use.
   *
   */
  private setupAxios() {
    this.axiosClient = axios.create({
      baseURL: BASE_URL,
      headers: { 'Content-Type': 'application/json' },
      timeout: TIMEOUT
    });

    // Request interceptor to add auth token
    this.axiosClient.interceptors.request.use((config) => {
      const token = localStorage.getItem('authToken');
      if (token && config.headers) config.headers.Authorization = `Bearer ${token}`;
      return config;
    });

    // Response interceptor to normalize errors
    this.axiosClient.interceptors.response.use(
      (resp) => resp,
      (err: AxiosError) => {
        const data = err.response?.data as any;
        const msg =
          (data && (data.message || data.error)) ||
          (typeof data === 'string' ? data : undefined) ||
          (err.response?.status === 401 ? 'Unauthorized' : err.message || 'Unexpected error');
        
        // Check for 401 Unauthorized
        if (err.response?.status === 401 && this.onUnauthorized) {
          console.log('[ApiClient] 401 Unauthorized - Token expired');
          this.onUnauthorized();
        }
        
        throw new ApiError(msg, err.response?.status, data, err.code);
      }
    );
  }

  // ---------- Public API Methods ----------

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
      data = await this.postViaVSCode<T>({ type: 'apiPost', url, body, headers: config?.headers });
    } else {
      const resp = await this.axiosClient!.post(url, body, config);
      data = resp.data as T;
    }
    return data;
  }

  async put<T = any>(url: string, body?: any, config?: AxiosRequestConfig): Promise<T> {
    let data: T;
    if (this.isVSCode) {
      console.log(url, 'put via vs');
      data = await this.postViaVSCode<T>({ type: 'apiPut', url, body, headers: config?.headers });
    } else {
      const resp = await this.axiosClient!.put(url, body, config);
      data = resp.data as T;
    }
    console.log(data, url);
    return data;
  }

  async patch<T = any>(url: string, body?: any, config?: AxiosRequestConfig): Promise<T> {
    let data: T;
    if (this.isVSCode) {
      console.log(url, 'patch via vs');
      data = await this.postViaVSCode<T>({ type: 'apiPatch', url, body, headers: config?.headers });
    } else {
      const resp = await this.axiosClient!.patch(url, body, config);
      data = resp.data as T;
    }
    console.log(data, url);
    return data;
  }

  async delete<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<T> {
    let data: T;
    if (this.isVSCode) {
      console.log(url, 'delete via vs');
      data = await this.postViaVSCode<T>({ type: 'apiDelete', url, params, headers: config?.headers });
    } else {
      const resp = await this.axiosClient!.delete(url, { ...config, params });
      data = resp.data as T;
    }
    console.log(data, url);
    return data;
  }
}

// Export a singleton instance
const apiClient = ApiClient.getInstance();
export default apiClient;
export type { AxiosRequestConfig };
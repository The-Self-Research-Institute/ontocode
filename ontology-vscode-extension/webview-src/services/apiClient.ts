// services/apiClient.ts
import axios, { AxiosError, AxiosInstance, AxiosRequestConfig } from 'axios';
import { getGatewayUrl, getStoredDeploymentType, type DeploymentType } from '../config/deploymentConfig';

// Get initial base URL from centralized config
let BASE_URL = getGatewayUrl();

// Set window.API_BASE_URL for plugins (UMD bundles that don't have access to this module)
if (typeof window !== 'undefined') {
  (window as any).API_BASE_URL = BASE_URL;
}

// Allow updating base URL dynamically
export const updateBaseUrl = (deploymentType: DeploymentType) => {
  BASE_URL = getGatewayUrl(deploymentType);
  // Update window.API_BASE_URL as well for plugins
  if (typeof window !== 'undefined') {
    (window as any).API_BASE_URL = BASE_URL;
  }
  // Update the axios instance's baseURL so subsequent requests use the new URL
  ApiClient.getInstance().updateAxiosBaseUrl(BASE_URL);
  console.log('[ApiClient] Base URL updated to:', BASE_URL);
};

// Expose current base URL for WebSocket connection
export const getBaseUrl = () => BASE_URL;

const TIMEOUT = 600_000; // Allow up to 10 minutes for heavy ontology operations (increased for large files)

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
  // In web extension mode or browser bridge mode, bypass the VS Code proxy
  // Use direct axios/fetch instead
  private isVSCode = typeof window !== 'undefined' &&
    !!window.vscode &&
    !(window as any).__ONTOCODE_CONFIG__?.IS_WEB_EXTENSION &&
    !(window as any).__ONTOCODE_BROWSER_BRIDGE__;
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

  /**
   * Update the axios instance's baseURL when deployment type changes
   */
  updateAxiosBaseUrl(newBaseUrl: string) {
    if (this.axiosClient) {
      this.axiosClient.defaults.baseURL = newBaseUrl;
      console.log('[ApiClient] Axios baseURL updated to:', newBaseUrl);
    }
  }

  private constructor() {
    console.log('[ApiClient] Initializing - isVSCode:', this.isVSCode,
      'IS_WEB_EXTENSION:', (window as any).__ONTOCODE_CONFIG__?.IS_WEB_EXTENSION);

    if (this.isVSCode) {
      // If in VS Code desktop, set up the message listener for proxy
      console.log('[ApiClient] Using VS Code extension proxy for API requests');
      this.attachVSCodeListener();
    } else {
      // If in browser or web extension, set up a standard Axios client
      console.log('[ApiClient] Using direct axios for API requests (baseURL:', BASE_URL, ')');
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
  private postViaVSCode<T>(payload: { type: string; url: string;[k: string]: any }): Promise<T> {
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
      headers: {
        'Content-Type': 'application/json'
      },
      timeout: TIMEOUT,
      maxContentLength: Infinity,
      maxBodyLength: Infinity
    });

    // Request interceptor to add auth token
    this.axiosClient.interceptors.request.use((config) => {
      // Try to get token from localStorage (webview context shares localStorage with the page)
      // In VS Code webview, the extension updates localStorage when the token changes
      const token = localStorage.getItem('authToken');
      console.log('[ApiClient] Interceptor - URL:', config.url, '| Token present:', !!token, '| Token length:', token?.length);
      if (token && config.headers) {
        config.headers.Authorization = `Bearer ${token}`;
        console.log('[ApiClient] ✓ Authorization header added');
      } else {
        console.warn('[ApiClient] ✗ No token found in localStorage - request will fail if authentication required');
        console.warn('[ApiClient] localStorage keys:', Object.keys(localStorage));
      }
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
      // FormData cannot be serialized through postMessage — convert to transferable format
      if (body instanceof FormData) {
        const fileEntry = body.get('file') as File | null;
        const msgBody: Record<string, any> = {};
        body.forEach((val: FormDataEntryValue, key: string) => {
          if (key !== 'file') msgBody[key] = val;
        });
        if (fileEntry) {
          const buf = await fileEntry.arrayBuffer();
          // Use base64 encoding instead of Array.from() - avoids creating
          // a multi-million-element JS array which freezes the UI for large files
          const bytes = new Uint8Array(buf);
          const chunks: string[] = [];
          const chunkSize = 32768;
          for (let i = 0; i < bytes.length; i += chunkSize) {
            chunks.push(String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + chunkSize, bytes.length)) as any));
          }
          msgBody._fileBase64 = btoa(chunks.join(''));
          msgBody._fileFieldName = 'file';
          msgBody._originalFileName = fileEntry.name;
          // Detect OWL file types
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
          console.log(`[ApiClient] FormData bridge: file=${fileEntry.name}, size=${buf.byteLength}, type=${contentType}`);
        }
        msgBody._isMultipart = true;
        data = await this.postViaVSCode<T>({ type: 'apiPost', url, body: msgBody, headers: config?.headers });
      } else {
        data = await this.postViaVSCode<T>({ type: 'apiPost', url, body, headers: config?.headers });
      }
    } else {
      // When sending FormData, remove the default Content-Type so axios/browser
      // can auto-set multipart/form-data with the correct boundary.
      const postConfig = body instanceof FormData
        ? { ...config, headers: { ...config?.headers, 'Content-Type': undefined } }
        : config;
      const resp = await this.axiosClient!.post(url, body, postConfig);
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

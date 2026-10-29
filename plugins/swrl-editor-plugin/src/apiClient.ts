

declare global {
  interface Window {
    vscode?: { postMessage: (message: any) => void };
    API_BASE_URL?: string;
  }
}

class ApiClient {
  private isVSCode = typeof window !== 'undefined' && !!window.vscode;
  private pending = new Map<string, { resolve: (v: any) => void; reject: (r?: any) => void }>();

  constructor() {
    if (this.isVSCode) {
      window.addEventListener('message', (event) => {
        const message = event.data;
        if (message.type === 'proxyResponse' && message.reqId) {
          const p = this.pending.get(message.reqId);
          if (p) {
            this.pending.delete(message.reqId);
            if (message.error) {
              p.reject(message.error);
            } else {
              p.resolve(message.data);
            }
          }
        }
      });
    }
  }

  private async request<T>(method: string, url: string, data?: any): Promise<T> {
    if (this.isVSCode) {
      return this.fetchRequest(method, url, data);
    } else {
      return this.fetchRequest<T>(method, url, data);
    }
  }

  private proxyRequest<T>(method: string, url: string, data?: any): Promise<T> {
    return new Promise((resolve, reject) => {
      const reqId = `req-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
      this.pending.set(reqId, { resolve, reject });

      window.vscode?.postMessage({
        type: 'proxyRequest',
        config: {
          method,
          url,
          data,
          headers: { 'Content-Type': 'application/json' }
        },
        reqId
      });

      setTimeout(() => {
        if (this.pending.has(reqId)) {
          this.pending.delete(reqId);
          reject(new Error('Request timed out'));
        }
      }, 30000);
    });
  }

  private async fetchRequest<T>(method: string, url: string, data?: any): Promise<T> {
    const baseUrl = (window as any).__DESKTOP_API_URL__ || (window as any).__ONTOCODE_CONFIG__?.CLOUD_GATEWAY_URL || window.API_BASE_URL || '';
    const fullUrl = url.startsWith('http') ? url : `${baseUrl}${url}`;

    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    const token = localStorage.getItem('authToken');
    if (token) headers['Authorization'] = `Bearer ${token}`;

    const response = await fetch(fullUrl, {
      method,
      headers,
      body: data ? JSON.stringify(data) : undefined,
    });

    if (!response.ok) {
      let detail = '';
      let data: any = undefined;
      try {
        const body = await response.text();
        if (body) {
          const parsed = JSON.parse(body);
          data = parsed;
          // parsed.error is a boolean flag on this API's error shape ({error:true,
          // message:"..."}), not a description — `error || message` picked the boolean
          // first (truthy) and `new Error(true)` below stringified it to the literal
          // text "true", discarding the real message entirely. Prefer the string field.
          detail = parsed.message || (typeof parsed.error === 'string' ? parsed.error : '') || body;
        }
      } catch { /* ignore parse failure */ }
      const err: any = new Error(detail || `Request failed (${response.status})`);
      err.status = response.status;
      err.data = data;
      err.retryAfter = response.headers.get('Retry-After');
      throw err;
    }

    const text = await response.text();
    if (!text || text.trim() === '') {
      return {} as T;
    }

    return JSON.parse(text);
  }

  private async rawRequest(method: string, url: string, body?: FormData): Promise<Response> {
    const baseUrl = (window as any).__DESKTOP_API_URL__ || (window as any).__ONTOCODE_CONFIG__?.CLOUD_GATEWAY_URL || window.API_BASE_URL || '';
    const fullUrl = url.startsWith('http') ? url : `${baseUrl}${url}`;
    const headers: Record<string, string> = {};
    const token = localStorage.getItem('authToken');
    if (token) headers['Authorization'] = `Bearer ${token}`;
    const response = await fetch(fullUrl, { method, headers, body });
    if (!response.ok) {
      let detail = '';
      let data: any = undefined;
      try {
        const text = await response.text();
        if (text) {
          const parsed = JSON.parse(text);
          data = parsed;
          detail = parsed.message || (typeof parsed.error === 'string' ? parsed.error : '') || text;
        }
      } catch { /* ignore parse failure */ }
      const err: any = new Error(detail || `Request failed (${response.status})`);
      err.status = response.status;
      err.data = data;
      throw err;
    }
    return response;
  }

  public async postForm<T>(url: string, form: FormData): Promise<T> {
    const response = await this.rawRequest('POST', url, form);
    const text = await response.text();
    return (text && text.trim() ? JSON.parse(text) : {}) as T;
  }

  public async getBlob(url: string): Promise<Blob> {
    const response = await this.rawRequest('GET', url);
    return response.blob();
  }

  public get<T>(url: string): Promise<T> {
    return this.request<T>('GET', url);
  }

  public post<T>(url: string, data?: any): Promise<T> {
    return this.request<T>('POST', url, data);
  }

  public put<T>(url: string, data?: any): Promise<T> {
    return this.request<T>('PUT', url, data);
  }

  public delete<T>(url: string): Promise<T> {
    return this.request<T>('DELETE', url);
  }
}

export default new ApiClient();

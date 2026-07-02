// Simple API Client for Plugin
// Handles communication with backend, proxying through VS Code if necessary

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

      // Timeout after 30s
      setTimeout(() => {
        if (this.pending.has(reqId)) {
          this.pending.delete(reqId);
          reject(new Error('Request timed out'));
        }
      }, 30000);
    });
  }

  private async fetchRequest<T>(method: string, url: string, data?: any): Promise<T> {
    const baseUrl = window.API_BASE_URL || '';
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
      try {
        const body = await response.text();
        if (body) {
          const parsed = JSON.parse(body);
          detail = parsed.error || parsed.message || body;
        }
      } catch { /* ignore parse failure */ }
      throw new Error(detail || `Request failed (${response.status})`);
    }

    // Handle empty responses (e.g., DELETE operations)
    const text = await response.text();
    if (!text || text.trim() === '') {
      return {} as T;
    }

    return JSON.parse(text);
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

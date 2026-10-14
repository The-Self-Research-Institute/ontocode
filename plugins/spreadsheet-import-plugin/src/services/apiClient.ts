export interface HostApiClient {
  get<T = any>(url: string, params?: any, config?: any): Promise<T>;
  post<T = any>(url: string, body?: any, config?: any): Promise<T>;
  put<T = any>(url: string, body?: any, config?: any): Promise<T>;
  delete<T = any>(url: string, params?: any, config?: any): Promise<T>;
}

let host: HostApiClient | null = null;

export function setApiClient(client: HostApiClient | null | undefined): void {
  host = client ?? null;
}

function client(): HostApiClient {
  if (!host) {
    throw new Error("Spreadsheet Import is not connected to the editor. Reload the page and try again.");
  }
  return host;
}

const apiClient: HostApiClient = {
  get: (url, params, config) => client().get(url, params, config),
  post: (url, body, config) => client().post(url, body, config),
  put: (url, body, config) => client().put(url, body, config),
  delete: (url, params, config) => client().delete(url, params, config),
};

export default apiClient;

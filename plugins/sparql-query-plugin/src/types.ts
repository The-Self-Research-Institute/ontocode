/**
 * SPARQL Query Plugin Types
 */

export interface SparqlQuery {
  id: string;
  name: string;
  queryText: string;
  projectId: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface SparqlBinding {
  [key: string]: {
    type: string;
    value: string;
    datatype?: string;
    'xml:lang'?: string;
  };
}

export interface SparqlQueryResult {
  head: {
    vars: string[];
  };
  results: {
    bindings: SparqlBinding[];
  };
  executionTime?: number;
}

export interface OntologyPrefix {
  prefix: string;
  namespace: string;
}

export interface PluginContext {
  apiClient: {
    get: <T>(url: string) => Promise<T>;
    post: <T>(url: string, data?: any) => Promise<T>;
    put: <T>(url: string, data?: any) => Promise<T>;
    delete: <T>(url: string) => Promise<T>;
  };
  showNotification?: (message: string, type: 'info' | 'success' | 'warning' | 'error') => void;
}

export interface SparqlQueryEditorProps {
  projectId: string;
  prefixes?: OntologyPrefix[];
  context: PluginContext;
}

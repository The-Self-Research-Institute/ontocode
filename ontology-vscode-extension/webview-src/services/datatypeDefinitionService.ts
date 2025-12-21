import apiClient from './apiClient';

export interface DatatypeDefinition {
  id: string;
  projectId: string;
  datatypeIri: string;
  expression: string;
  definitionType: string;
  createdAt?: string;
  updatedAt?: string;
}

type ApiEnvelope<T> = {
  success: boolean;
  data?: T;
  error?: string;
};

const unwrap = <T,>(response: any): T => {
  const payload = response?.data || response;
  if (!payload?.success) {
    throw new Error(payload?.error || 'Request failed');
  }
  return payload.data as T;
};

export const datatypeDefinitionService = {
  async listDefinitions(projectId: string, datatypeIri: string): Promise<DatatypeDefinition[]> {
    const response = await apiClient.get<ApiEnvelope<DatatypeDefinition[]>>(
      `/api/ontology/datatypes/definitions/${projectId}?datatypeIri=${encodeURIComponent(datatypeIri)}`
    );
    return unwrap<DatatypeDefinition[]>(response);
  },

  async createDefinition(
    projectId: string,
    datatypeIri: string,
    expression: string,
    definitionType: string
  ): Promise<DatatypeDefinition> {
    const response = await apiClient.post<ApiEnvelope<DatatypeDefinition>>(
      `/api/ontology/datatypes/definitions/${projectId}`,
      { datatypeIri, expression, definitionType }
    );
    return unwrap<DatatypeDefinition>(response);
  },

  async deleteDefinition(projectId: string, definitionId: string): Promise<void> {
    const response = await apiClient.delete<ApiEnvelope<void>>(
      `/api/ontology/datatypes/definitions/${projectId}/${definitionId}`
    );
    unwrap<void>(response);
  }
};

export default datatypeDefinitionService;

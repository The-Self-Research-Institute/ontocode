import apiClient from './apiClient';

export interface AxiomAnnotation {
  property: string;
  value: string;
  language?: string;
}

function unwrap<T extends Record<string, unknown>>(res: T): T {
  if (res && typeof res === 'object' && 'data' in res && res.data && typeof res.data === 'object') {
    return res.data as T;
  }
  return res;
}

export const axiomAnnotationService = {
  async getAnnotations(
    projectId: string,
    entityIri: string,
    relatedIri: string,
    sectionName?: string,
  ): Promise<AxiomAnnotation[]> {
    const params = new URLSearchParams({
      entityIri,
      relatedIri,
    });
    if (sectionName) params.set('sectionName', sectionName);
    const res = await apiClient.get(
      `/api/ontology/${encodeURIComponent(projectId)}/axiom-annotations?${params.toString()}`,
    );
    const payload = unwrap(res as Record<string, unknown>);
    return (payload.annotations as AxiomAnnotation[]) || [];
  },

  async addAnnotation(
    projectId: string,
    payload: {
      entityIri: string;
      relatedIri: string;
      sectionName?: string;
      annotationProperty: string;
      value: string;
      language?: string;
    },
  ): Promise<void> {
    await apiClient.post(`/api/ontology/${encodeURIComponent(projectId)}/axiom-annotations`, payload);
  },

  async deleteAnnotation(
    projectId: string,
    payload: {
      entityIri: string;
      relatedIri: string;
      sectionName?: string;
      annotationProperty: string;
      value: string;
    },
  ): Promise<void> {
    const params = new URLSearchParams({
      entityIri: payload.entityIri,
      relatedIri: payload.relatedIri,
      annotationProperty: payload.annotationProperty,
      value: payload.value,
    });
    if (payload.sectionName) params.set('sectionName', payload.sectionName);
    await apiClient.delete(
      `/api/ontology/${encodeURIComponent(projectId)}/axiom-annotations?${params.toString()}`,
    );
  },
};

export default axiomAnnotationService;

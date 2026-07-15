import apiClient from './apiClient';
import ontologyMutationService from './ontologyMutationService';
import { resolveMutationUserId } from '../utils/mutationActor';
import { isDesktop } from '../utils/desktop';

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

/**
 * In private/draft mode, axiom-annotation writes must go to the user's draft graph.
 * The backend needs both draft=true and the userId (resolved the same way mutations are).
 */
function appendDraftParams(params: URLSearchParams): URLSearchParams {
  // Webapp-only: desktop is single-user with no public/draft split (keeps prior behavior).
  if (!isDesktop() && ontologyMutationService.isPrivateEditMode()) {
    params.set('draft', 'true');
    const uid = resolveMutationUserId();
    if (uid) params.set('userId', uid);
  }
  return params;
}

function draftParams(): URLSearchParams {
  return appendDraftParams(new URLSearchParams());
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
    appendDraftParams(params);
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
    const qs = draftParams().toString();
    await apiClient.post(
      `/api/ontology/${encodeURIComponent(projectId)}/axiom-annotations${qs ? `?${qs}` : ''}`,
      payload,
    );
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
    appendDraftParams(params);
    await apiClient.delete(
      `/api/ontology/${encodeURIComponent(projectId)}/axiom-annotations?${params.toString()}`,
    );
  },
};

export default axiomAnnotationService;

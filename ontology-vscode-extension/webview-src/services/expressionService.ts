import apiClient from './apiClient';

export type ClassAxiomType = 'EquivalentTo' | 'SubClassOf' | 'DisjointWith';
export type PropertyRelationType = 'Domain' | 'Range';

export function isSimpleOntologyIri(value: string): boolean {
  const t = value.trim();
  return (
    t.startsWith('http://') ||
    t.startsWith('https://') ||
    t.startsWith('urn:') ||
    /^[A-Za-z_][\w.-]*:[\w.-]+$/.test(t)
  );
}

export function isManchesterClassExpression(value: string): boolean {
  const t = value.trim();
  if (!t || isSimpleOntologyIri(t) || t.startsWith('_:')) return false;
  return true;
}

export interface ParseExpressionResult {
  success: boolean;
  manchester?: string;
  type?: string;
  isAnonymous?: boolean;
  error?: string;
}

function unwrap<T extends Record<string, unknown>>(res: T): T {
  if (res && typeof res === 'object' && 'data' in res && res.data && typeof res.data === 'object') {
    return res.data as T;
  }
  return res;
}

export const expressionService = {
  async parseExpression(projectId: string, expression: string): Promise<ParseExpressionResult> {
    const res = await apiClient.post<ParseExpressionResult>(
      `/api/ontology/${encodeURIComponent(projectId)}/expression/parse`,
      { expression },
    );
    const payload = unwrap(res as Record<string, unknown>);
    return {
      success: payload.success !== false,
      manchester: payload.manchester as string | undefined,
      type: payload.type as string | undefined,
      isAnonymous: payload.isAnonymous as boolean | undefined,
      error: payload.error as string | undefined,
    };
  },

  async addClassExpressionAxiom(
    projectId: string,
    classIri: string,
    axiomType: ClassAxiomType,
    expression: string,
    userId?: string,
    username?: string,
  ): Promise<void> {
    const params = new URLSearchParams();
    if (userId) params.set('userId', userId);
    if (username) params.set('username', username);
    const qs = params.toString();
    await apiClient.post(
      `/api/ontology/${encodeURIComponent(projectId)}/expression/add-class-axiom${qs ? `?${qs}` : ''}`,
      { classIri, axiomType, expression },
    );
  },

  async addPropertyExpressionAxiom(
    projectId: string,
    propertyIri: string,
    relationType: PropertyRelationType,
    expression: string,
    isDataProperty: boolean,
    userId?: string,
    username?: string,
  ): Promise<void> {
    const params = new URLSearchParams();
    if (userId) params.set('userId', userId);
    if (username) params.set('username', username);
    const qs = params.toString();
    await apiClient.post(
      `/api/ontology/${encodeURIComponent(projectId)}/expression/add-property-axiom${qs ? `?${qs}` : ''}`,
      { propertyIri, relationType, expression, isDataProperty },
    );
  },

  async deletePropertyExpressionAxiom(
    projectId: string,
    propertyIri: string,
    relationType: PropertyRelationType,
    expression: string,
    isDataProperty: boolean,
    userId?: string,
    username?: string,
  ): Promise<void> {
    const params = new URLSearchParams();
    if (userId) params.set('userId', userId);
    if (username) params.set('username', username);
    const qs = params.toString();
    await apiClient.post(
      `/api/ontology/${encodeURIComponent(projectId)}/expression/delete-property-axiom${qs ? `?${qs}` : ''}`,
      { propertyIri, relationType, expression, isDataProperty },
    );
  },
};

export default expressionService;

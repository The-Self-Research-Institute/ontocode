/** Resolve which Entities tab an ontology change belongs to. */
export function resolveEntitiesTab(entityType?: string, changeType?: string): string {
  const type = `${entityType || ''} ${changeType || ''}`.toLowerCase();
  if (type.includes('individual')) return 'Individuals';
  if (type.includes('objectproperty') || type.includes('object_property')) return 'ObjectProperties';
  if (type.includes('dataproperty') || type.includes('data_property') || type.includes('datatypeproperty')) {
    return 'DataProperties';
  }
  if (type.includes('annotationproperty') || type.includes('annotation_property')) return 'AnnotationProperties';
  if (type.includes('datatype')) return 'Datatypes';
  return 'Classes';
}

export interface CollaborationNavigateDetail {
  projectId?: string;
  entityIRI: string;
  entityLabel?: string;
  entityType?: string;
  changeType?: string;
}

export const COLLABORATION_NAVIGATE_EVENT = 'collaboration:navigate-to-entity';

export function dispatchCollaborationNavigate(detail: CollaborationNavigateDetail): void {
  window.dispatchEvent(new CustomEvent(COLLABORATION_NAVIGATE_EVENT, { detail }));
}

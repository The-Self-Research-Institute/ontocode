import { generateWarnings } from './draftWarnings';
import { ChangeAction, ChangeStats, ChangeStatus, ChangeType, OntologyChange } from './types';

export function extractLabelFromIRI(iri: string): string {
  if (!iri) return 'Unknown';
  const parts = iri.split(/[#/]/);
  return parts[parts.length - 1] || 'Unknown';
}

function mapOperationToType(operationType: string): ChangeType {
  if (!operationType) return 'axiom';
  const lower = operationType.toLowerCase();
  if (lower.includes('class')) return 'class';
  if (lower.includes('property')) return 'property';
  if (lower.includes('individual')) return 'individual';
  if (lower.includes('annotation')) return 'annotation';
  if (lower.includes('import')) return 'import';
  return 'axiom';
}

function mapOperationToAction(operationType: string): ChangeAction {
  if (!operationType) return 'modified';
  const lower = operationType.toLowerCase();
  if (lower.includes('create') || lower.includes('add')) return 'added';
  if (lower.includes('delete') || lower.includes('remove')) return 'deleted';
  return 'modified';
}

function formatDraftDescription(draft: any): string {
  const opType = draft.operationType || '';
  const label = draft.operationData?.label || 'entity';
  const descriptions: Record<string, string> = {
    'createClass': `Created class: ${label}`,
    'deleteClass': `Deleted class: ${label}`,
    'updateClassLabel': `Renamed class to: ${label}`,
    'addAnnotation': `Added annotation to: ${label}`,
    'updateAnnotation': `Updated annotation for: ${label}`,
    'deleteAnnotation': `Removed annotation from: ${label}`,
    'createObjectProperty': `Created object property: ${label}`,
    'createDataProperty': `Created data property: ${label}`,
    'createIndividual': `Created individual: ${label}`,
    'addSubClassOf': `Added subclass axiom for: ${label}`,
  };
  return descriptions[opType] || `${opType} operation on ${label}`;
}

function mapChangeType(operationType: string, entityType: string): ChangeType {
  const opLower = operationType?.toLowerCase() || '';
  const entityLower = entityType?.toLowerCase() || '';
  if (opLower.includes('class')) return 'class';
  if (opLower.includes('property')) return 'property';
  if (opLower.includes('individual')) return 'individual';
  if (opLower.includes('annotation')) return 'annotation';
  if (entityLower.includes('class')) return 'class';
  if (entityLower.includes('property')) return 'property';
  if (entityLower.includes('individual')) return 'individual';
  if (entityLower.includes('annotation')) return 'annotation';
  if (entityLower.includes('axiom')) return 'axiom';
  if (entityLower.includes('import')) return 'import';
  return 'axiom';
}

function mapAction(operationType: string): ChangeAction {
  const lowerOp = operationType?.toLowerCase() || '';
  if (lowerOp.includes('add') || lowerOp.includes('create') || lowerOp.includes('insert')) return 'added';
  if (lowerOp.includes('delete') || lowerOp.includes('remove')) return 'deleted';
  if (lowerOp.includes('modify') || lowerOp.includes('update') || lowerOp.includes('change') || lowerOp.includes('rename')) return 'modified';
  return 'modified';
}

export function parseDraft(draft: any): OntologyChange {
  return {
    id: draft.id || `draft-${Date.now()}-${Math.random()}`,
    timestamp: new Date(draft.timestamp),
    author: draft.username || 'You',
    authorEmail: draft.userId || '',
    type: mapOperationToType(draft.operationType),
    action: mapOperationToAction(draft.operationType),
    status: 'draft' as ChangeStatus,
    entityUri: draft.operationData?.iri || '',
    entityLabel: draft.operationData?.label || extractLabelFromIRI(draft.operationData?.iri),
    oldValue: draft.operationData?.oldValue,
    newValue: draft.operationData?.value || draft.operationData?.newValue,
    description: formatDraftDescription(draft),
    comments: [],
    conflicts: [],
    warnings: generateWarnings(draft)
  };
}

export function parseChange(change: any) {
  const originalOperationType = change.changeType || change.operationType || '';
  return {
    id: change.id,
    timestamp: new Date(change.timestamp),
    author: change.username || 'System',
    authorEmail: change.userId || '',
    type: mapChangeType(originalOperationType, change.changeCategory || change.entityType),
    action: mapAction(originalOperationType),
    status: (change.status?.toLowerCase() || 'saved') as ChangeStatus,
    entityUri: change.entityIRI,
    entityLabel: change.entityLabel || extractLabelFromIRI(change.entityIRI),
    oldValue: change.oldValue,
    newValue: change.newValue,
    description: change.description || `${originalOperationType}`,
    commitId: change.editId,
    branch: undefined,
    comments: [],
    conflicts: change.hasConflict ? [{ conflictType: 'concurrent_edit' as const, description: 'Conflict detected' }] : [],
    warnings: [],
    commentCount: change.commentCount || 0,
    operationType: originalOperationType,
    subChanges: change.subChanges || [],
    reverted: change.reverted || false,
    revertedBy: change.revertedBy,
    revertedAt: change.revertedAt,
    changeSetId: change.changeSetId ?? null,
    source: change.source ?? null,
    ai: change.ai ?? null,
    revertsChangeSetId: change.revertsChangeSetId ?? null,
    revertedAuditId: change.revertedAuditId,
    revertedWithSet: !!change.revertedWithSet,
    rollbackAuditId: change.rollbackAuditId ?? null
  };
}

export function computeStats(parsedChanges: any[], draftChanges: OntologyChange[]): ChangeStats {
  const totalWarnings = [...parsedChanges, ...draftChanges]
    .reduce((sum, c) => sum + (c.warnings?.length || 0), 0);
  return {
    totalChanges: parsedChanges.length,
    draftChanges: draftChanges.length,
    conflicts: parsedChanges.filter((c: any) => c.conflicts?.length > 0).length,
    activeAuthors: new Set(parsedChanges.map((c: any) => c.author)).size,
    warnings: totalWarnings
  };
}

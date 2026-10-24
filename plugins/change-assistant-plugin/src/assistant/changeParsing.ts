import { authorKey, fallbackLabel, isRollbackChange, normaliseAuthor, resolveChangeType } from './changeKinds';
import { generateWarnings } from './draftWarnings';
import { ChangeAction, ChangeStats, ChangeStatus, ChangeType, OntologyChange } from './types';
import { parseServerTime } from '../changeSets/timeFormat';

export function extractLabelFromIRI(iri: string): string {
  if (!iri) return '';
  const parts = iri.split(/[#/]/);
  return parts[parts.length - 1] || '';
}

function mapOperationToType(operationType: string): ChangeType {
  return resolveChangeType(operationType, undefined);
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
  return resolveChangeType(operationType, entityType);
}

export function changeMatchesType(change: OntologyChange, filterType: ChangeType): boolean {
  if (change.type === filterType) return true;
  return (change.subChanges || []).some(sc => sc.category?.toLowerCase() === filterType);
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
    timestamp: parseServerTime(draft.timestamp),
    author: normaliseAuthor(draft.username, draft.userId) || 'You',
    authorEmail: draft.userId || '',
    type: mapOperationToType(draft.operationType),
    action: mapOperationToAction(draft.operationType),
    status: 'draft' as ChangeStatus,
    entityUri: draft.operationData?.iri || '',
    entityLabel: draft.operationData?.label || extractLabelFromIRI(draft.operationData?.iri) || fallbackLabel({ operationType: draft.operationType }),
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
  const parsedLabel = change.entityLabel || extractLabelFromIRI(change.entityIRI);
  return {
    id: change.id,
    timestamp: parseServerTime(change.timestamp),
    author: normaliseAuthor(change.username, change.userId),
    authorEmail: change.userId || '',
    type: mapChangeType(originalOperationType, change.changeCategory || change.entityType),
    action: mapAction(originalOperationType),
    status: (change.status?.toLowerCase() || 'saved') as ChangeStatus,
    entityUri: change.entityIRI,
    entityLabel: fallbackLabel({
      entityLabel: parsedLabel,
      operationType: originalOperationType,
      description: change.description
    }),
    oldValue: change.oldValue,
    newValue: change.newValue,
    description: change.description || `${originalOperationType}`,
    commitId: change.editId,
    branch: undefined,
    comments: [],
    conflicts: change.hasConflict ? [{
      conflictType: 'concurrent_edit' as const,
      description: change.conflictPartnerUsername
        ? `${change.conflictPartnerUsername} changed this around the same time`
        : 'Conflict detected',
      conflictingChangeId: change.conflictPartnerChangeId ?? undefined,
      theirValue: change.conflictPartnerValue,
      theirUserId: change.conflictPartnerUserId,
      theirUsername: change.conflictPartnerUsername,
      theirTimestamp: change.conflictPartnerTimestamp,
    }] : [],
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
    rollbackAuditId: change.rollbackAuditId ?? null,
    undoable: typeof change.undoable === 'boolean' ? change.undoable : undefined,
    undoReason: change.undoReason ?? null,
    setOnly: change.setOnly === true,
    mergedPrReverted: change.mergedPrReverted === true,
    draft: !!change.draft
  };
}

export function countableChanges<T extends { source?: string | null; operationType?: string }>(changes: T[]): T[] {
  return changes.filter(change => !isRollbackChange(change));
}

export function isUndone(change: { reverted?: boolean; subChanges?: Array<{ reverted?: boolean }> }): boolean {
  const subs = change.subChanges || [];
  return !!change.reverted || (subs.length > 0 && subs.every(sub => sub.reverted));
}

export function activeChanges<T extends { source?: string | null; operationType?: string; reverted?: boolean; subChanges?: Array<{ reverted?: boolean }> }>(changes: T[]): T[] {
  return countableChanges(changes).filter(change => !isUndone(change));
}

export function countEntries(changes: Array<{ id: string; changeSetId?: string | null }>): number {
  return new Set(changes.map(change => change.changeSetId || change.id)).size;
}

export function computeStats(allChanges: any[], draftChanges: OntologyChange[]): ChangeStats {
  const parsedChanges = allChanges.filter((change: any) => !change.draft);
  const countable = countableChanges(parsedChanges);
  const totalWarnings = [...countable, ...draftChanges]
    .reduce((sum, c) => sum + (c.warnings?.length || 0), 0);
  return {
    totalChanges: countEntries(activeChanges(parsedChanges)),
    undoneChanges: countable.filter(isUndone).length,
    draftChanges: draftChanges.length,
    conflicts: countable.filter((c: any) => c.conflicts?.length > 0).length,
    activeAuthors: new Set(countable.map((c: any) => authorKey(c.author, c.authorEmail))).size,
    warnings: totalWarnings
  };
}

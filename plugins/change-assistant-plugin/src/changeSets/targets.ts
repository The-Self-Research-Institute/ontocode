import { ChangeSet, EntityRow, EventContext, OperationTarget, PropertyRow } from './types';

function entityContext(entity: EntityRow): EventContext {
  const entry = entity.entry;
  return {
    entityIRI: entry.entityUri,
    entityLabel: entity.label,
    entityType: entry.type,
    action: entry.action,
    originalAuthor: entry.author,
    oldValue: entry.oldValue,
    newValue: entry.newValue
  };
}

function setContext(set: ChangeSet): EventContext {
  if (set.entities.length === 1) return entityContext(set.entities[0]);
  const first = set.entries[0];
  return { entityIRI: first?.entityUri, entityType: first?.type, action: 'modified', originalAuthor: set.author };
}

export function setTarget(set: ChangeSet, direction: 'UNDO' | 'REDO'): OperationTarget | null {
  if (set.changeSetId) {
    return { kind: 'set', direction, changeSetId: set.changeSetId, label: set.title, context: setContext(set) };
  }
  if (direction === 'UNDO' && set.entities.length === 1) return entityTarget(set.entities[0]);
  if (direction === 'REDO' && set.entities.length === 1) return entityRedoTarget(set.entities[0]);
  return null;
}

export function redoTarget(auditId: string, label: string, context: EventContext): OperationTarget {
  return { kind: 'undo', direction: 'REDO', auditId, label, context };
}

export function entityRedoTarget(entity: EntityRow): OperationTarget | null {
  return entity.redoAuditId ? redoTarget(entity.redoAuditId, entity.label, entityContext(entity)) : null;
}

export function propertyRedoTarget(entity: EntityRow, property: PropertyRow, label: string): OperationTarget | null {
  if (!property.redoAuditId) return null;
  return redoTarget(property.redoAuditId, label, {
    ...entityContext(entity),
    action: 'modified',
    oldValue: property.addition ? undefined : property.rawValue,
    newValue: property.addition ? property.rawValue : undefined
  });
}

export function entityTarget(entity: EntityRow): OperationTarget | null {
  if (!entity.entry.entityUri) return null;
  return { kind: 'entry', direction: 'UNDO', changeId: entity.entry.id, label: entity.label, context: entityContext(entity) };
}

export function propertyTarget(entity: EntityRow, property: PropertyRow): OperationTarget | null {
  if (!property.subChangeId) return entityTarget(entity);
  const context: EventContext = {
    ...entityContext(entity),
    action: 'modified',
    oldValue: property.addition ? undefined : property.rawValue,
    newValue: property.addition ? property.rawValue : undefined
  };
  return {
    kind: 'property',
    direction: 'UNDO',
    changeId: property.changeId,
    subChangeId: property.subChangeId,
    label: `${property.label} on ${entity.label}`,
    context
  };
}

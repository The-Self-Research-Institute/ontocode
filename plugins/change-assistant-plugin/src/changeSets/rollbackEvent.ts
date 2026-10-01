import { currentActor } from '../authFetch';
import { OperationResponse, OperationTarget } from './types';

export function dispatchRollbackEvent(projectId: string, target: OperationTarget, data: OperationResponse): void {
  const ctx = target.context;
  const entityIRIs = Array.from(new Set(data.applied.map(item => item.entityIRI).filter(Boolean))) as string[];
  const redo = target.direction === 'REDO';
  const singleEntity = entityIRIs.length <= 1;
  window.dispatchEvent(new CustomEvent('ontologyRollback', {
    detail: {
      projectId,
      changeId: target.kind === 'entry' || target.kind === 'property' ? target.changeId : undefined,
      subChangeId: target.kind === 'property' ? target.subChangeId : undefined,
      changeSetId: target.kind === 'set' ? target.changeSetId : undefined,
      direction: target.direction,
      entityIRI: singleEntity ? ctx.entityIRI || entityIRIs[0] : undefined,
      entityIRIs,
      entityLabel: ctx.entityLabel,
      action: singleEntity ? (redo ? 'modified' : ctx.action) : undefined,
      entityType: ctx.entityType,
      username: currentActor().username,
      originalAuthor: ctx.originalAuthor,
      oldValue: redo ? ctx.oldValue : ctx.newValue,
      newValue: redo ? ctx.newValue : ctx.oldValue,
      success: true
    }
  }));
}

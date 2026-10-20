import { newOpId } from './opId';
export const COMMITTED_CHANGE_EVENT = 'ontocode:committed-change';

export type CommittedDirection = 'APPLY' | 'UNDO' | 'REDO';

export interface CommittedChange {
  opId: string;
  projectId: string;
  direction: CommittedDirection;
  changeSetId?: string;
  auditId?: string;
  entityIRIs: string[];
  opTypes: string[];
  draft: boolean;
  at: number;
}

export type CommittedChangeInput = Omit<CommittedChange, 'opId' | 'at' | 'entityIRIs' | 'opTypes'> & {
  opId?: string;
  entityIRIs?: string[];
  opTypes?: string[];
};

const MAX_REMEMBERED = 200;
const seen: string[] = [];

function remember(opId: string): boolean {
  if (seen.includes(opId)) {
    return false;
  }
  seen.push(opId);
  if (seen.length > MAX_REMEMBERED) {
    seen.shift();
  }
  return true;
}

export function emitCommittedChange(input: CommittedChangeInput): CommittedChange | null {
  const change: CommittedChange = {
    ...input,
    opId: input.opId || input.changeSetId || input.auditId || newOpId(),
    entityIRIs: Array.from(new Set((input.entityIRIs || []).filter(Boolean))),
    opTypes: input.opTypes || [],
    at: Date.now(),
  };
  const key = `${change.direction}:${change.opId}`;
  if (!remember(key)) {
    return null;
  }
  try {
    window.dispatchEvent(new CustomEvent<CommittedChange>(COMMITTED_CHANGE_EVENT, { detail: change }));
  } catch {
    return null;
  }
  return change;
}

export type EntityList = 'classes' | 'properties' | 'annotationProperties' | 'individuals' | 'datatypes';

const WHOLE_ONTOLOGY_OPS = ['ontologyMerge', 'draftPublish', 'draftPull'];

const OPS_BY_LIST: Record<EntityList, string[]> = {
  classes: ['createClass', 'deleteClass', 'addSubClassOf', 'deleteSubClassOf', 'updateSubClassOf', 'updateClassLabel', ...WHOLE_ONTOLOGY_OPS],
  properties: [
    'createObjectProperty',
    'createDataProperty',
    'deleteObjectProperty',
    'deleteDataProperty',
    'addSubPropertyOf',
    'deleteSubPropertyOf',
    'updateSubPropertyOf',
    ...WHOLE_ONTOLOGY_OPS,
  ],
  annotationProperties: ['createAnnotationProperty', 'deleteAnnotationProperty', ...WHOLE_ONTOLOGY_OPS],
  individuals: ['createIndividual', 'deleteIndividual', 'addClassAssertion', 'removeClassAssertion', ...WHOLE_ONTOLOGY_OPS],
  datatypes: ['createDatatype', 'deleteDatatype', ...WHOLE_ONTOLOGY_OPS],
};

export function affectedLists(change: CommittedChange): EntityList[] {
  if (change.direction !== 'APPLY') return [];
  return (Object.keys(OPS_BY_LIST) as EntityList[]).filter((list) =>
    change.opTypes.some((type) => OPS_BY_LIST[list].includes(type)),
  );
}

export function subscribeCommittedChange(handler: (change: CommittedChange) => void): () => void {
  const listener = (event: Event) => handler((event as CustomEvent<CommittedChange>).detail);
  window.addEventListener(COMMITTED_CHANGE_EVENT, listener);
  return () => window.removeEventListener(COMMITTED_CHANGE_EVENT, listener);
}

export function announceMutationApplied(
  projectId: string,
  ops: Array<{ type: string; iri?: string }>,
  changeSetId: string | undefined,
  draft: boolean,
): void {
  try {
    window.dispatchEvent(new CustomEvent('ontology:mutated', { detail: { projectId, ops: ops.map((op) => op.type) } }));
  } catch {
    return;
  }
  emitCommittedChange({
    projectId,
    direction: 'APPLY',
    changeSetId,
    entityIRIs: ops.map((op) => op.iri || ''),
    opTypes: ops.map((op) => op.type),
    draft,
  });
}

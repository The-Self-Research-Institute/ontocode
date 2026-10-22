import { ChangeType } from './types';

const KNOWN_BUCKETS: Record<string, ChangeType> = {
  PREFIX: 'prefix',
  RENAME: 'rename',
  IMPORT: 'import',
  RULE: 'rule',
  CITATION: 'citation',
  DATATYPE: 'datatype',
  INDIVIDUAL: 'individual',
  CLASS: 'class',
  PROPERTY: 'property',
  ANNOTATION: 'annotation',
  AXIOM: 'axiom'
};

export function bucketFromOperation(operationType: string | undefined | null): ChangeType | null {
  const upper = (operationType || '').toUpperCase();
  if (!upper) return null;
  if (upper.startsWith('PREFIX')) return 'prefix';
  if (upper.includes('RENAME')) return 'rename';
  if (upper.includes('IMPORT')) return 'import';
  if (upper.includes('SWRL') || upper.includes('RULE') || upper.includes('FUZZY')) return 'rule';
  if (upper.includes('CITATION')) return 'citation';
  if (upper.includes('DATATYPE')) return 'datatype';
  if (upper.includes('ASSERTION') || upper.includes('INDIVIDUAL')) return 'individual';
  if (upper.includes('CLASS')) return 'class';
  if (upper.includes('PROPERTY')) return 'property';
  if (upper.includes('ANNOTATION')) return 'annotation';
  if (upper.includes('AXIOM')) return 'axiom';
  return null;
}

export function bucketFromServer(entityType: string | undefined | null): ChangeType | null {
  return KNOWN_BUCKETS[(entityType || '').toUpperCase()] || null;
}

export function resolveChangeType(operationType: string | undefined, entityType: string | undefined): ChangeType {
  return bucketFromOperation(operationType) || bucketFromServer(entityType) || 'axiom';
}

export const TYPE_LABELS: Record<ChangeType, string> = {
  class: 'Classes',
  property: 'Properties',
  individual: 'Individuals',
  axiom: 'Axioms',
  annotation: 'Annotations',
  import: 'Imports',
  datatype: 'Datatypes',
  prefix: 'Prefixes',
  rename: 'Renames',
  rule: 'Rules',
  citation: 'Citations'
};

export const ALL_TYPES = Object.keys(TYPE_LABELS) as ChangeType[];

export function isRollbackChange(change: { source?: string | null; operationType?: string }): boolean {
  const op = (change.operationType || '').toUpperCase();
  return change.source === 'ROLLBACK' || op.startsWith('ROLLBACK_') || op.startsWith('REDO_');
}

function humanise(operationType: string): string {
  const spaced = operationType.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/_/g, ' ').trim();
  return spaced ? spaced.charAt(0).toUpperCase() + spaced.slice(1).toLowerCase() : '';
}

export function fallbackLabel(change: {
  operationType?: string;
  description?: string;
  entityLabel?: string;
  entityUri?: string;
}): string {
  if (change.entityLabel && change.entityLabel.trim()) return change.entityLabel;
  const tail = (change.entityUri || '').split(/[#/]/).filter(Boolean).pop();
  if (tail) return tail;
  if (change.description && change.description.trim()) return change.description.trim();
  return humanise(change.operationType || '') || 'Ontology change';
}

export function normaliseAuthor(username: string | undefined, userId: string | undefined): string {
  const name = (username || '').trim();
  const id = (userId || '').trim();
  const pick = name || id;
  if (!pick || pick === 'desktop-user-local' || pick === 'anonymous' || pick === 'system') {
    return pick === 'system' ? 'System' : 'Local user';
  }
  return pick;
}

export function authorKey(author: string, authorEmail?: string): string {
  const candidates = [authorEmail, author].map(value => (value || '').trim().toLowerCase()).filter(Boolean);
  return candidates.find(value => value.includes('@')) ?? candidates[0] ?? '';
}

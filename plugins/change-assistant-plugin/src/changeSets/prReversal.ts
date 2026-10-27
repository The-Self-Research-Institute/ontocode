import { ChangeSet } from './types';

const PR_IRI_PREFIX = 'urn:ontocode:pr:';

export function mergedPrOf(set: ChangeSet): { prId: string; title: string; reverted: boolean } | null {
  if (!set.changeSetId?.startsWith('pr:')) return null;
  return {
    prId: set.changeSetId.slice(3),
    title: set.title.replace(/^Merged PR: /, ''),
    reverted: set.entries.some(entry => entry.mergedPrReverted === true)
  };
}

export function prReversalOf(set: ChangeSet): { prId: string; title: string } | null {
  const entry = set.entries.length === 1 ? set.entries[0] : null;
  if (!entry || entry.operationType !== 'prReverted' || entry.reverted) return null;
  const iri = entry.entityUri || '';
  if (!iri.startsWith(PR_IRI_PREFIX)) return null;
  return { prId: iri.slice(PR_IRI_PREFIX.length), title: (entry.entityLabel || '').replace(/^Reverted PR: /, '') };
}

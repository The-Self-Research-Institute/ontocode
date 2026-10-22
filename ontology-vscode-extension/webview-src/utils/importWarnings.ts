export interface UpdatedImport {
  projectId: string;
  name?: string | null;
}

export interface SkippedImport extends UpdatedImport {
  owner?: string | null;
  reason?: string | null;
}

const label = (item: UpdatedImport): string => item.name || item.projectId;

export function formatSkippedImports(skipped: SkippedImport[] | undefined | null): string | null {
  if (!skipped || skipped.length === 0) return null;
  const list = skipped.map(item => (item.owner ? `${label(item)} (owner: ${item.owner})` : label(item))).join(', ');
  const noRights = skipped.filter(item => /edit rights/i.test(item.reason || ''));
  const failed = skipped.length - noRights.length;
  const count = skipped.length === 1 ? '1 ontology still imports' : `${skipped.length} ontologies still import`;
  const why = failed === 0
    ? "you can't edit them"
    : noRights.length === 0
      ? 'the update failed'
      : "you can't edit some of them and the update failed for the others";
  return `${count} the old IRI and ${skipped.length === 1 ? 'was' : 'were'} not updated because ${why}: ${list}. Ask the owner to update the import.`;
}

export function formatUpdatedImports(updated: UpdatedImport[] | undefined | null): string | null {
  if (!updated || updated.length === 0) return null;
  return `Imports updated in: ${updated.map(label).join(', ')}.`;
}

const GRAPH_CACHE_PREFIX = 'ontocode:graphView:';
const MUTATED_EVENT = 'ontology:mutated';
const COMMITTED_EVENT = 'ontocode:committed-change';
const CLASS_EVENTS = ['graph-view:class-created', 'graph-view:class-deleted'];
const CHANGE_EVENTS = [MUTATED_EVENT, COMMITTED_EVENT, ...CLASS_EVENTS];

export function clearGraphSessionCache(projectId?: string): void {
  try {
    if (projectId) {
      sessionStorage.removeItem(`${GRAPH_CACHE_PREFIX}${projectId}`);
      sessionStorage.removeItem(`${GRAPH_CACHE_PREFIX}${projectId}:draft`);
      return;
    }
    Object.keys(sessionStorage)
      .filter((key) => key.startsWith(GRAPH_CACHE_PREFIX))
      .forEach((key) => sessionStorage.removeItem(key));
  } catch {
    return;
  }
}

export function installGraphCacheInvalidation(): () => void {
  const listener = (event: Event) => clearGraphSessionCache((event as CustomEvent)?.detail?.projectId);
  [MUTATED_EVENT, COMMITTED_EVENT].forEach((name) => window.addEventListener(name, listener));
  return () => [MUTATED_EVENT, COMMITTED_EVENT].forEach((name) => window.removeEventListener(name, listener));
}

export function subscribeGraphRefresh(refresh: () => void, delayMs = 500): () => void {
  let queued = false;
  const listener = () => {
    if (queued) {
      return;
    }
    queued = true;
    setTimeout(() => {
      queued = false;
      refresh();
    }, delayMs);
  };
  CHANGE_EVENTS.forEach((name) => window.addEventListener(name, listener));
  return () => CHANGE_EVENTS.forEach((name) => window.removeEventListener(name, listener));
}

export function announceGraphMutation(
  projectId: string,
  ops: Array<{ type: string; iri?: string }>,
  changeSetId: string | undefined,
  draft: boolean,
): void {
  try {
    window.dispatchEvent(new CustomEvent(MUTATED_EVENT, { detail: { projectId, ops: ops.map((op) => op.type) } }));
    window.dispatchEvent(
      new CustomEvent(COMMITTED_EVENT, {
        detail: {
          projectId,
          direction: 'APPLY',
          opId: changeSetId || `graph-${Date.now()}-${Math.random().toString(36).slice(2)}`,
          changeSetId,
          entityIRIs: ops.map((op) => op.iri || '').filter(Boolean),
          opTypes: ops.map((op) => op.type),
          draft,
          at: Date.now(),
        },
      }),
    );
  } catch {
    return;
  }
}

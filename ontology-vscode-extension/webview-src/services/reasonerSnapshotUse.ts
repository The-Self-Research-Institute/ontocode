import {
  ReasonerSnapshotUnsupportedError,
  getReasonerSnapshot,
  invalidateReasonerSnapshots,
  type GetReasonerSnapshotOptions,
  type ReasonerSnapshot,
  type ReasonerSnapshotScope,
} from "./reasonerSnapshot";

const OWL_NOTHING = "http://www.w3.org/2002/07/owl#Nothing";
const INCONSISTENT_MESSAGE =
  "The ontology is inconsistent — reasoning cannot proceed. Use 'Explain inconsistency' to find the conflicting axioms.";

const list = (value: unknown): any[] => (Array.isArray(value) ? value : []);
const isRecord = (value: unknown): value is Record<string, any> =>
  !!value && typeof value === "object" && !Array.isArray(value);
const has = (record: Record<string, any>, key: string) => Object.prototype.hasOwnProperty.call(record, key);

export const snapshotClassTree = (snapshot: ReasonerSnapshot) => list(snapshot?.classTree);
export const snapshotObjectPropertyTree = (snapshot: ReasonerSnapshot) => list(snapshot?.objectPropertyTree);
export const snapshotDataPropertyTree = (snapshot: ReasonerSnapshot) => list(snapshot?.dataPropertyTree);
export const snapshotInferredAxioms = (snapshot: ReasonerSnapshot) => list(snapshot?.inferredAxioms?.axioms);
export const snapshotInferredAxiomsTotal = (snapshot: ReasonerSnapshot): number => {
  const axioms = snapshot?.inferredAxioms as any;
  const total = axioms?.totalInferredAxioms;
  return typeof total === "number" ? total : list(axioms?.axioms).length;
};
export const snapshotInconsistencyMessage = (snapshot: ReasonerSnapshot): string =>
  snapshot?.inconsistency?.message || INCONSISTENT_MESSAGE;

export function snapshotToReasonerResults(snapshot: ReasonerSnapshot): Record<string, any> {
  if (snapshot?.consistent === false) {
    return {
      success: true,
      ...(isRecord(snapshot.inconsistency) ? snapshot.inconsistency : {}),
      inconsistent: true,
      issues: list(snapshot.inconsistency?.issues),
      message: snapshotInconsistencyMessage(snapshot),
      reasonerType: snapshot.reasonerType,
    };
  }
  return {
    success: true,
    reasonerType: snapshot?.reasonerType,
    propertyReasonerType: snapshot?.propertyReasonerType,
    durationMs: snapshot?.durationMs,
    message: "Classification completed successfully",
    classHierarchy: list(snapshot?.classHierarchyFlat),
    equivalentClasses: list(snapshot?.equivalentClasses),
    unsatisfiableClasses: list(snapshot?.unsatisfiableClasses),
    objectPropertyHierarchy: list(snapshot?.objectPropertyHierarchyFlat),
    dataPropertyHierarchy: list(snapshot?.dataPropertyHierarchyFlat),
    totalClasses: snapshot?.totalClasses,
    stats: snapshot?.stats,
    ...(snapshot?.downgradedWarning ? { downgradedWarning: snapshot.downgradedWarning } : {}),
  };
}

export function snapshotToConsistencyResult(snapshot: ReasonerSnapshot, projectId: string): Record<string, any> {
  const consistent = snapshot?.consistent !== false;
  return {
    consistent,
    reasonerType: snapshot?.reasonerType,
    durationMs: snapshot?.durationMs,
    projectId,
    ...(consistent
      ? {}
      : {
          unsatisfiableClasses: list(snapshot?.unsatisfiableClasses),
          issues: list(snapshot?.inconsistency?.issues),
          message: snapshotInconsistencyMessage(snapshot),
        }),
  };
}

export interface SnapshotChild {
  iri: string;
  label: string;
  hasChildren: boolean;
  isUnsatisfiable?: boolean;
  equivalentClasses?: any[];
}

export function inferredChildrenFromSnapshot(snapshot: ReasonerSnapshot | null | undefined, parentIri: string): SnapshotChild[] | null {
  const children = snapshot?.classChildren;
  const nodes = snapshot?.classNodes;
  if (!isRecord(children) || !isRecord(nodes)) {
    return null;
  }
  if (parentIri === OWL_NOTHING) {
    return list(snapshot?.unsatisfiableClasses)
      .filter((cls) => cls?.iri)
      .map((cls) => ({ iri: cls.iri, label: cls.label || cls.iri, hasChildren: false, isUnsatisfiable: true }));
  }
  if (!has(children, parentIri) && !has(nodes, parentIri)) {
    return null;
  }
  return list(children[parentIri]).map((iri: string) => {
    const node = isRecord(nodes[iri]) ? nodes[iri] : {};
    return {
      iri,
      label: node.label || iri,
      hasChildren: list(children[iri]).length > 0,
      ...(node.isUnsatisfiable === true ? { isUnsatisfiable: true } : {}),
      ...(Array.isArray(node.equivalentClasses) ? { equivalentClasses: node.equivalentClasses } : {}),
    };
  });
}

export async function withReasonerSnapshot<T>(
  scope: ReasonerSnapshotScope,
  useSnapshot: (snapshot: ReasonerSnapshot) => T | Promise<T>,
  useLegacy: () => T | Promise<T>,
  options?: GetReasonerSnapshotOptions,
): Promise<T> {
  let snapshot: ReasonerSnapshot;
  try {
    snapshot = await getReasonerSnapshot(scope, options);
  } catch (error) {
    if (error instanceof ReasonerSnapshotUnsupportedError) {
      return useLegacy();
    }
    throw error;
  }
  return useSnapshot(snapshot);
}

const INVALIDATING_EVENTS = [
  "ontology:mutated",
  "ontologyRollback",
  "ontologyChangesSaved",
  "remoteEditReceived",
  "ownEditReceived",
];

export function watchReasonerSnapshotInvalidation(target: Window = window): () => void {
  const handler = (event: Event) => {
    const projectId = (event as CustomEvent).detail?.projectId;
    invalidateReasonerSnapshots(typeof projectId === "string" && projectId ? { projectId } : undefined);
  };
  INVALIDATING_EVENTS.forEach((name) => target.addEventListener(name, handler));
  return () => INVALIDATING_EVENTS.forEach((name) => target.removeEventListener(name, handler));
}

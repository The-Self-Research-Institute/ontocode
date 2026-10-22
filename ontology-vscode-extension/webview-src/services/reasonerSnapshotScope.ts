import { isDraftScopeActive } from "../utils/draftScope";
import { resolveMutationActor } from "../utils/mutationActor";
import { normalizeReasonerType } from "../components/dashboard-parts/dashboardUtils";
import { peekReasonerSnapshot, type ReasonerSnapshot, type ReasonerSnapshotScope } from "./reasonerSnapshot";

export function pageReasonerSnapshotScope(projectId: string, reasonerLabel: string): ReasonerSnapshotScope {
  const draft = isDraftScopeActive();
  return {
    projectId,
    reasonerType: normalizeReasonerType(reasonerLabel),
    draft,
    userId: draft ? resolveMutationActor().userId || "" : undefined,
  };
}

export function snapshotKnowsUnsatisfiable(snapshot: ReasonerSnapshot | null | undefined, classIri: string): boolean | null {
  if (!snapshot || snapshot.consistent !== true || snapshot.unsatisfiableKnown === false) {
    return null;
  }
  if (!Array.isArray(snapshot.unsatisfiableClasses)) {
    return null;
  }
  return snapshot.unsatisfiableClasses.some((cls: any) => cls?.iri === classIri);
}

export function cachedUnsatisfiable(projectId: string, reasonerLabel: string, classIri: string): boolean | null {
  return snapshotKnowsUnsatisfiable(peekReasonerSnapshot(pageReasonerSnapshotScope(projectId, reasonerLabel)), classIri);
}

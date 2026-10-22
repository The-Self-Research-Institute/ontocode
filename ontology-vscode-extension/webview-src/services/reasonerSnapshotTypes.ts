export interface ReasonerSnapshotScope {
  projectId: string;
  reasonerType: string;
  draft?: boolean;
  userId?: string | null;
}

export type ReasonerSnapshot = Record<string, any>;

export interface NormalizedSnapshotScope {
  projectId: string;
  reasonerType: string;
  draft: boolean;
  userId: string;
}

export class ReasonerSnapshotUnsupportedError extends Error {
  readonly unsupported = true;
  constructor() {
    super("This server does not provide reasoner snapshots");
    this.name = "ReasonerSnapshotUnsupportedError";
  }
}

export class ReasonerSnapshotError extends Error {
  constructor(message: string, public readonly payload?: any) {
    super(message);
    this.name = "ReasonerSnapshotError";
  }
}

const JOB_FIELDS = ["async", "jobId", "jobType", "status", "queuePosition", "estimatedWaitTimeMs", "executionTimeMs"];

export const normalizeSnapshotScope = (scope: ReasonerSnapshotScope): NormalizedSnapshotScope => ({
  projectId: scope.projectId,
  reasonerType: String(scope.reasonerType || "HERMIT").toUpperCase(),
  draft: scope.draft === true,
  userId: scope.userId ?? "",
});

export const reasonerSnapshotKey = (scope: ReasonerSnapshotScope): string => {
  const s = normalizeSnapshotScope(scope);
  return [s.projectId, s.draft ? "draft" : "public", s.userId, s.reasonerType].join("|");
};

export const snapshotScopeMatches = (scope: NormalizedSnapshotScope, match?: Partial<ReasonerSnapshotScope>) => {
  if (!match) {
    return true;
  }
  return (match.projectId === undefined || match.projectId === scope.projectId)
    && (match.draft === undefined || (match.draft === true) === scope.draft)
    && (match.userId === undefined || (match.userId ?? "") === scope.userId)
    && (match.reasonerType === undefined || String(match.reasonerType).toUpperCase() === scope.reasonerType);
};

export const snapshotUrl = (projectId: string) =>
  `/api/ontology/${encodeURIComponent(projectId)}/reasoner/snapshot`;

export const snapshotResponseBody = (response: any) =>
  response && typeof response === "object" && response.success === undefined && response.data && typeof response.data === "object"
    ? response.data
    : response;

export const snapshotFromJob = (job: any): ReasonerSnapshot => {
  if (job?.result && typeof job.result === "object" && !Array.isArray(job.result)) {
    return job.result;
  }
  const copy: ReasonerSnapshot = { ...(job || {}) };
  JOB_FIELDS.forEach((field) => delete copy[field]);
  return copy;
};

export const failIfUnsuccessful = (body: any): ReasonerSnapshot => {
  if (body?.success === false) {
    throw new ReasonerSnapshotError(body.error || body.message || "Reasoning failed", body);
  }
  return body as ReasonerSnapshot;
};

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

vi.mock("../services/apiClient", () => ({
  default: { get: vi.fn(), delete: vi.fn() },
}));

import apiClient from "../services/apiClient";
import {
  cancelReasonerSnapshots,
  getReasonerSnapshot,
  invalidateReasonerSnapshots,
  peekReasonerSnapshot,
  resetReasonerSnapshotState,
  ReasonerSnapshotError,
  ReasonerSnapshotUnsupportedError,
} from "../services/reasonerSnapshot";
import {
  inferredChildrenFromSnapshot,
  snapshotClassTree,
  snapshotDataPropertyTree,
  snapshotInferredAxioms,
  snapshotObjectPropertyTree,
  snapshotToConsistencyResult,
  snapshotToReasonerResults,
  watchReasonerSnapshotInvalidation,
  withReasonerSnapshot,
} from "../services/reasonerSnapshotUse";
import { ReasoningJobCancelledError } from "../services/reasoningJobWait";
import { cachedUnsatisfiable, pageReasonerSnapshotScope, snapshotKnowsUnsatisfiable } from "../services/reasonerSnapshotScope";
import { attachReasoningSocket, resetReasoningTopicState } from "../services/reasoningJobTopic";

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const del = apiClient.delete as unknown as ReturnType<typeof vi.fn>;

const THING = "http://www.w3.org/2002/07/owl#Thing";
const NOTHING = "http://www.w3.org/2002/07/owl#Nothing";
const scope = { projectId: "p1", reasonerType: "HERMIT", draft: false };

const snapshot = (overrides: Record<string, any> = {}) => ({
  success: true,
  projectId: "p1",
  async: false,
  consistent: true,
  reasonerType: "HermiT",
  classTree: [{ id: THING, label: "owl:Thing", children: [{ id: "A", label: "A", children: [], hasChildren: true }], hasChildren: true }],
  classChildren: { [THING]: ["A"], A: ["B"] },
  classNodes: { [THING]: { label: "owl:Thing" }, A: { label: "A" }, B: { label: "B", isUnsatisfiable: true } },
  classHierarchyFlat: [{ iri: "A", label: "A", depth: 0, childrenCount: 1 }],
  equivalentClasses: [],
  unsatisfiableClasses: [{ iri: "B", label: "B" }],
  objectPropertyTree: [{ id: "top-op", label: "owl:topObjectProperty", children: [] }],
  dataPropertyTree: [{ id: "top-dp", label: "owl:topDataProperty", children: [] }],
  objectPropertyHierarchyFlat: [{ iri: "op", label: "op", depth: 0 }],
  dataPropertyHierarchyFlat: [{ iri: "dp", label: "dp", depth: 0 }],
  stats: { classHierarchyNodes: 3, isConsistent: true },
  inferredAxioms: { totalInferredAxioms: 1, axioms: [{ axiomType: "SubClassOf", axiom: "x", readable: "x" }] },
  ...overrides,
});

const notFound = () => Object.assign(new Error("Not Found"), { status: 404 });

beforeEach(() => {
  resetReasonerSnapshotState();
  resetReasoningTopicState();
  get.mockReset();
  del.mockReset();
  del.mockResolvedValue({ success: true, cancelled: true });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("reasoner snapshot page cache", () => {
  it("serves the three inferred tabs, Start and consistency from one request", async () => {
    get.mockResolvedValue(snapshot());

    const [classTab, objectTab, dataTab, start, consistency] = await Promise.all([
      getReasonerSnapshot(scope),
      getReasonerSnapshot(scope),
      getReasonerSnapshot(scope),
      getReasonerSnapshot(scope),
      getReasonerSnapshot(scope),
    ]);

    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith("/api/ontology/p1/reasoner/snapshot", { reasonerType: "HERMIT", draft: "false" });
    expect(snapshotClassTree(classTab)[0].id).toBe(THING);
    expect(snapshotObjectPropertyTree(objectTab)[0].id).toBe("top-op");
    expect(snapshotDataPropertyTree(dataTab)[0].id).toBe("top-dp");
    const results = snapshotToReasonerResults(start);
    expect(results.classHierarchy).toEqual([{ iri: "A", label: "A", depth: 0, childrenCount: 1 }]);
    expect(results.objectPropertyHierarchy[0].iri).toBe("op");
    expect(results.dataPropertyHierarchy[0].iri).toBe("dp");
    expect(results.stats.classHierarchyNodes).toBe(3);
    expect(snapshotInferredAxioms(start)).toHaveLength(1);
    expect(snapshotToConsistencyResult(consistency, "p1")).toMatchObject({ consistent: true, projectId: "p1" });
  });

  it("reuses the cached answer and asks again after it is invalidated", async () => {
    get.mockResolvedValue(snapshot());
    await getReasonerSnapshot(scope);
    await getReasonerSnapshot(scope);
    expect(get).toHaveBeenCalledTimes(1);
    expect(peekReasonerSnapshot(scope)).toBeDefined();

    invalidateReasonerSnapshots({ projectId: "p1" });
    expect(peekReasonerSnapshot(scope)).toBeUndefined();
    await getReasonerSnapshot(scope);
    expect(get).toHaveBeenCalledTimes(2);
  });

  it("keys the cache by project, draft, user and reasoner", async () => {
    get.mockImplementation(async () => snapshot());
    await getReasonerSnapshot(scope);
    await getReasonerSnapshot({ ...scope, reasonerType: "ELK" });
    await getReasonerSnapshot({ ...scope, draft: true, userId: "u1" });
    await getReasonerSnapshot({ ...scope, projectId: "p2" });
    expect(get).toHaveBeenCalledTimes(4);
    expect(get).toHaveBeenCalledWith("/api/ontology/p1/reasoner/snapshot", { reasonerType: "HERMIT", draft: "true", userId: "u1" });

    invalidateReasonerSnapshots({ projectId: "p1" });
    expect(peekReasonerSnapshot({ ...scope, projectId: "p2" })).toBeDefined();
    expect(peekReasonerSnapshot(scope)).toBeUndefined();
  });

  it("clears the cache on local and remote edit events", async () => {
    get.mockImplementation(async () => snapshot());
    const stop = watchReasonerSnapshotInvalidation(window);
    try {
      await getReasonerSnapshot(scope);
      window.dispatchEvent(new CustomEvent("ontology:mutated", { detail: { projectId: "other" } }));
      expect(peekReasonerSnapshot(scope)).toBeDefined();
      window.dispatchEvent(new CustomEvent("remoteEditReceived", { detail: { type: "CLASS_ADDED" } }));
      expect(peekReasonerSnapshot(scope)).toBeUndefined();
      await getReasonerSnapshot(scope);
      window.dispatchEvent(new CustomEvent("ontology:mutated", { detail: { projectId: "p1" } }));
      expect(peekReasonerSnapshot(scope)).toBeUndefined();
    } finally {
      stop();
    }
  });

  it("does not cache an answer that was invalidated while it was running", async () => {
    let resolve: (value: any) => void = () => undefined;
    get.mockReturnValueOnce(new Promise((r) => { resolve = r; }));
    const pending = getReasonerSnapshot(scope);
    invalidateReasonerSnapshots();
    resolve(snapshot());
    await expect(pending).resolves.toMatchObject({ consistent: true });
    expect(peekReasonerSnapshot(scope)).toBeUndefined();
  });

  it("reports a failed snapshot without caching it", async () => {
    get.mockResolvedValueOnce({ success: false, error: "Reasoner crashed" });
    await expect(getReasonerSnapshot(scope)).rejects.toBeInstanceOf(ReasonerSnapshotError);
    expect(peekReasonerSnapshot(scope)).toBeUndefined();
  });

  it("falls back to the old endpoints when the server has no snapshot endpoint", async () => {
    get.mockRejectedValueOnce(notFound());
    await expect(getReasonerSnapshot(scope)).rejects.toBeInstanceOf(ReasonerSnapshotUnsupportedError);

    const legacy = vi.fn(async () => "legacy");
    const modern = vi.fn(async () => "snapshot");
    await expect(withReasonerSnapshot(scope, modern, legacy)).resolves.toBe("legacy");
    expect(get).toHaveBeenCalledTimes(1);
    expect(modern).not.toHaveBeenCalled();
  });
});

describe("reasoner snapshot jobs", () => {
  it("ends the wait on a socket update without polling", async () => {
    vi.useFakeTimers();
    const listeners = new Map<string, (payload: any) => void>();
    attachReasoningSocket((jobId, onMessage) => {
      listeners.set(jobId, onMessage);
      return () => listeners.delete(jobId);
    });
    get.mockResolvedValueOnce({ success: true, projectId: "p1", async: true, jobId: "j1" });

    const pending = getReasonerSnapshot(scope);
    await vi.advanceTimersByTimeAsync(0);
    expect(listeners.has("j1")).toBe(true);
    listeners.get("j1")?.({ jobId: "j1", status: "COMPLETED", result: snapshot({ cached: false }) });

    await expect(pending).resolves.toMatchObject({ consistent: true, cached: false });
    expect(get).toHaveBeenCalledTimes(1);
    expect(listeners.has("j1")).toBe(false);
  });

  it("polls every 2.5 s without a socket and every 10 s with one", async () => {
    vi.useFakeTimers();
    get
      .mockResolvedValueOnce({ success: true, async: true, jobId: "j2" })
      .mockResolvedValueOnce({ status: "PROCESSING" })
      .mockResolvedValueOnce({ status: "COMPLETED", ...snapshot() });

    const pending = getReasonerSnapshot(scope);
    await vi.advanceTimersByTimeAsync(2_499);
    expect(get).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(get).toHaveBeenCalledTimes(2);
    expect(get).toHaveBeenLastCalledWith("/api/ontology/p1/reasoner/jobs/j2");

    attachReasoningSocket(() => () => undefined);
    await vi.advanceTimersByTimeAsync(9_999);
    expect(get).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(get).toHaveBeenCalledTimes(3);
    const result = await pending;
    expect(result.classTree).toBeDefined();
    expect(result.status).toBeUndefined();
    expect(result.async).toBeUndefined();
  });

  it("stops polling on a terminal 4xx answer", async () => {
    vi.useFakeTimers();
    get
      .mockResolvedValueOnce({ success: true, async: true, jobId: "gone" })
      .mockRejectedValueOnce(Object.assign(new Error("Job not found"), { status: 404, data: { error: "Job not found" } }));

    const pending = getReasonerSnapshot(scope);
    const assertion = expect(pending).rejects.toThrow("Job not found");
    await vi.advanceTimersByTimeAsync(2_500);
    await assertion;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(get).toHaveBeenCalledTimes(2);
  });

  it("cancels the job when every waiter gives up", async () => {
    vi.useFakeTimers();
    get.mockResolvedValueOnce({ success: true, async: true, jobId: "j3" }).mockResolvedValue({ status: "PROCESSING" });
    const first = new AbortController();
    const second = new AbortController();
    const a = getReasonerSnapshot(scope, { signal: first.signal });
    const b = getReasonerSnapshot(scope, { signal: second.signal });
    await vi.advanceTimersByTimeAsync(0);

    first.abort();
    await expect(a).rejects.toBeInstanceOf(ReasoningJobCancelledError);
    expect(del).not.toHaveBeenCalled();

    second.abort();
    await expect(b).rejects.toBeInstanceOf(ReasoningJobCancelledError);
    expect(del).toHaveBeenCalledWith("/api/ontology/p1/reasoner/jobs/j3");
  });

  it("cancels running jobs for a scope on Stop or a switch", async () => {
    vi.useFakeTimers();
    get.mockImplementation(async (url: string) => {
      if (url.includes("/snapshot")) {
        return { success: true, async: true, jobId: url.includes("/p1/") ? "p1-job" : "p2-job" };
      }
      return { status: "PROCESSING" };
    });
    const p1 = getReasonerSnapshot(scope);
    const p2 = getReasonerSnapshot({ ...scope, projectId: "p2" });
    const p1Done = expect(p1).rejects.toBeInstanceOf(ReasoningJobCancelledError);
    await vi.advanceTimersByTimeAsync(0);

    expect(cancelReasonerSnapshots({ projectId: "p1" })).toBe(1);
    await p1Done;
    expect(del).toHaveBeenCalledTimes(1);
    expect(del).toHaveBeenCalledWith("/api/ontology/p1/reasoner/jobs/p1-job");

    const p2Done = expect(p2).rejects.toBeInstanceOf(ReasoningJobCancelledError);
    cancelReasonerSnapshots();
    await p2Done;
    expect(del).toHaveBeenCalledWith("/api/ontology/p2/reasoner/jobs/p2-job");
  });
});

describe("inferred class expand from the snapshot", () => {
  it("returns children from the class map with no request", () => {
    const children = inferredChildrenFromSnapshot(snapshot(), "A");
    expect(children).toEqual([{ iri: "B", label: "B", hasChildren: false, isUnsatisfiable: true }]);
    expect(inferredChildrenFromSnapshot(snapshot(), THING)?.[0]).toMatchObject({ iri: "A", hasChildren: true });
    expect(inferredChildrenFromSnapshot(snapshot(), "B")).toEqual([]);
    expect(inferredChildrenFromSnapshot(snapshot(), NOTHING)).toEqual([
      { iri: "B", label: "B", hasChildren: false, isUnsatisfiable: true },
    ]);
    expect(get).not.toHaveBeenCalled();
  });

  it("returns null when the map is missing so the caller can ask the server", () => {
    const omitted = snapshot({ classChildren: undefined, classNodes: undefined, classChildrenOmitted: true });
    expect(inferredChildrenFromSnapshot(omitted, "A")).toBeNull();
    expect(inferredChildrenFromSnapshot(snapshot(), "unknown")).toBeNull();
    expect(inferredChildrenFromSnapshot(undefined, "A")).toBeNull();
  });

  it("maps an inconsistent snapshot onto the old shapes", () => {
    const inconsistent = snapshot({
      consistent: false,
      inconsistency: { success: true, inconsistent: true, issues: [{ message: "x" }], message: "Found 1 inconsistency" },
    });
    expect(snapshotToReasonerResults(inconsistent)).toMatchObject({ inconsistent: true, message: "Found 1 inconsistency" });
    expect(snapshotToConsistencyResult(inconsistent, "p1")).toMatchObject({
      consistent: false,
      unsatisfiableClasses: [{ iri: "B", label: "B" }],
    });
  });
});

describe("class editor quick check from the page cache", () => {
  it("answers from a cached consistent snapshot and defers otherwise", async () => {
    expect(snapshotKnowsUnsatisfiable(snapshot(), "B")).toBe(true);
    expect(snapshotKnowsUnsatisfiable(snapshot(), "A")).toBe(false);
    expect(snapshotKnowsUnsatisfiable(snapshot({ unsatisfiableKnown: false }), "B")).toBeNull();
    expect(snapshotKnowsUnsatisfiable(snapshot({ consistent: false }), "B")).toBeNull();
    expect(snapshotKnowsUnsatisfiable(undefined, "B")).toBeNull();

    expect(cachedUnsatisfiable("p1", "HermiT", "B")).toBeNull();
    get.mockResolvedValueOnce(snapshot());
    await getReasonerSnapshot(pageReasonerSnapshotScope("p1", "HermiT"));
    expect(cachedUnsatisfiable("p1", "HermiT", "B")).toBe(true);
    expect(cachedUnsatisfiable("p1", "ELK", "B")).toBeNull();
  });
});

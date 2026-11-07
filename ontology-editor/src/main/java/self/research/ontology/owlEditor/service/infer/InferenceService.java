package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import self.research.ontology.common.RestRuleStage;
import self.research.ontology.common.RuleStage;
import self.research.ontology.owlEditor.service.SparqlQueryGuard;
import self.research.ontology.owlEditor.service.SparqlQueryScope;
import self.research.ontology.owlEditor.service.SparqlUserQueryResult;
import self.research.ontology.owlEditor.service.SparqlUserQueryRunner;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InferenceService {

    public record Answer(SparqlUserQueryResult result, Map<String, Object> inferred) {
    }

    private static final String IMPORTS_IDENTITY = "project-graphs-only";

    private final InferenceSource source;
    private final InferenceCapture capture;
    private final RuleStage ruleStage;
    private final InferenceSnapshotCache cache;
    private final InferenceMaterializer materializer;
    private final int maxAssertedForStatus;
    private final InferenceAdmission admission;
    private final SnapshotStoreFactory stores;
    private final HeapBudget heap;
    private final int maxGroup;
    private final int maxEquality;
    private volatile Map<String, Long> lastSteps = Map.of();
    private final ConcurrentHashMap<InferenceSnapshotKey, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public InferenceService(InferenceSource source, InferenceCapacity capacity,
                            ObjectProvider<InferenceAdmission> admission,
                            ObjectProvider<InferenceCacheAccounting> accounting) {
        this(source, RestRuleStage.shared(),
                new InferenceSnapshotCache(capacity.cacheSnapshots(), InferenceProfile.CACHE_TTL_MS, capacity.cacheTriples(),
                        System::currentTimeMillis),
                capacity.maxGroupSize(), capacity.maxEqualityTriples(), capacity.maxAssertedTriples(),
                new TieredSnapshotStores(), HeapBudget.runtime(),
                admission.getIfAvailable(() -> new LocalInferenceAdmission(capacity.busyWaitMs(), HeapBudget.runtime())),
                accounting.getIfAvailable(() -> InferenceCacheAccounting.NONE));
    }

    public InferenceService(InferenceSource source, RuleStage ruleStage, InferenceSnapshotCache cache, int maxGroup,
                            int maxEqualityTriples) {
        this(source, ruleStage, cache, maxGroup, maxEqualityTriples, InferenceProfile.MAX_ASSERTED_TRIPLES);
    }

    public InferenceService(InferenceSource source, RuleStage ruleStage, InferenceSnapshotCache cache, int maxGroup,
                            int maxEqualityTriples, int maxAsserted) {
        this(source, ruleStage, cache, maxGroup, maxEqualityTriples, maxAsserted, new TieredSnapshotStores(),
                HeapBudget.runtime(), new LocalInferenceAdmission(InferenceProfile.BUSY_WAIT_MS, HeapBudget.runtime()),
                InferenceCacheAccounting.NONE);
    }

    public InferenceService(InferenceSource source, RuleStage ruleStage, InferenceSnapshotCache cache, int maxGroup,
                            int maxEqualityTriples, int maxAsserted, SnapshotStoreFactory stores, HeapBudget heap,
                            InferenceAdmission admission) {
        this(source, ruleStage, cache, maxGroup, maxEqualityTriples, maxAsserted, stores, heap, admission,
                InferenceCacheAccounting.NONE);
    }

    public InferenceService(InferenceSource source, RuleStage ruleStage, InferenceSnapshotCache cache, int maxGroup,
                            int maxEqualityTriples, int maxAsserted, SnapshotStoreFactory stores, HeapBudget heap,
                            InferenceAdmission admission, InferenceCacheAccounting accounting) {
        this.stores = stores;
        this.heap = heap;
        this.admission = admission;
        this.maxGroup = maxGroup;
        this.maxEquality = maxEqualityTriples;
        this.source = source;
        this.capture = new InferenceCapture(source, maxAsserted);
        this.ruleStage = ruleStage;
        this.cache = cache;
        this.cache.setAccounting(accounting);
        this.materializer = new InferenceMaterializer(ruleStage, maxGroup, maxEqualityTriples);
        this.maxAssertedForStatus = maxAsserted;
    }

    public Map<String, Long> lastStepMillis() {
        return lastSteps;
    }

    public InferenceSnapshotCache cache() {
        return cache;
    }

    public Answer query(SparqlQueryScope scope, String userId, String queryText, boolean refresh, int graphCap) {
        SparqlQueryGuard.Checked checked = SparqlQueryGuard.check(queryText, new LinkedHashSet<>(scope.graphs()));
        InferredSnapshot snapshot = prepare(scope, userId, refresh);
        try {
            try (RepositoryConnection conn = snapshot.repository().getConnection()) {
                SparqlUserQueryResult result = SparqlUserQueryRunner.run(conn, checked, snapshot.graphs(), graphCap);
                return new Answer(result, InferredMetadata.of(snapshot));
            }
        } finally {
            snapshot.release();
        }
    }

    public Map<String, Object> status(SparqlQueryScope scope, String userId) {
        InferenceSnapshotKey lookup = lookupKey(scope, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<InferredSnapshot> hit = cache.acquire(lookup);
        boolean available = false;
        boolean tooLarge = cache.failure(lookup).filter(InferenceException.TOO_LARGE::equals).isPresent();
        Instant computedAt = cache.lastComputed(lookup).orElse(null);
        String message = null;
        if (hit.isPresent()) {
            InferredSnapshot snapshot = hit.get();
            available = !snapshot.isInconsistent();
            computedAt = snapshot.computedAt();
            message = snapshot.isInconsistent() ? snapshot.inconsistentMessage() : null;
            snapshot.release();
        } else if (tooLarge) {
            message = "The ontology has more than " + maxAssertedForStatus + " triples, which is above the limit for inferred queries.";
        } else if (computedAt != null) {
            message = "The ontology changed since inferred data was computed. Refresh to recompute.";
        }
        out.put("available", available);
        out.put("stale", !available && computedAt != null && !tooLarge);
        if (computedAt != null) {
            out.put("computedAt", computedAt.toString());
        }
        out.put("reasoner", InferenceProfile.REASONER);
        out.put("profile", InferenceProfile.VERSION);
        out.put("layers", InferenceProfile.LAYERS);
        out.put("reasonerUsed", available);
        out.put("tooLarge", tooLarge);
        out.put("busy", admission.isBusy() || heap.isLow());
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxAssertedTriples", maxAssertedForStatus);
        limits.put("maxEqualityTriples", maxEquality);
        limits.put("maxGroupSize", maxGroup);
        out.put("limits", limits);
        if (message != null) {
            out.put("message", message);
        }
        return out;
    }

    InferredSnapshot prepare(SparqlQueryScope scope, String userId, boolean refresh) {
        InferenceSnapshotKey lookup = lookupKey(scope, userId);
        Optional<InferredSnapshot> hit = cache.acquire(lookup);
        if (hit.isPresent()) {
            return usable(hit.get());
        }
        if (!refresh) {
            throw missing(lookup);
        }
        Object lock = locks.computeIfAbsent(lookup, k -> new Object());
        try {
            synchronized (lock) {
                Optional<InferredSnapshot> again = cache.acquire(lookup);
                if (again.isPresent()) {
                    return usable(again.get());
                }
                return usable(compute(scope, userId, lookup));
            }
        } finally {
            locks.remove(lookup, lock);
        }
    }

    private static InferredSnapshot usable(InferredSnapshot snapshot) {
        if (snapshot.isInconsistent()) {
            snapshot.release();
            throw new InconsistentOntologyException(snapshot.inconsistentMessage(), snapshot.inconsistentExplanation());
        }
        return snapshot;
    }

    private InferenceException missing(InferenceSnapshotKey lookup) {
        Optional<String> failure = cache.failure(lookup);
        if (failure.isPresent()) {
            return new InferenceException(failure.get(), "The last attempt to compute the inferred view failed (" + failure.get()
                    + "). Refresh to try again.");
        }
        Optional<Instant> previous = cache.lastComputed(lookup);
        if (previous.isPresent()) {
            return new InferenceException(InferenceException.STALE,
                    "The ontology changed since the inferred view was computed at " + previous.get() + ". Choose Refresh to compute them again.", previous.get());
        }
        return new InferenceException(InferenceException.NONE, "The inferred facts have not been computed yet. Choose Refresh to compute them.");
    }

    private InferredSnapshot compute(SparqlQueryScope scope, String userId, InferenceSnapshotKey lookup) {
        if (heap.isLow()) {
            cache.clear();
        }
        InferenceAdmission.Permit permit = admission.admit(userId, 0L);
        long started = System.currentTimeMillis();
        InferenceCapture.Captured captured = null;
        try {
            captured = capture.capture(scope, userId);
            long captureMs = System.currentTimeMillis() - started;
            InferenceSnapshotKey key = keyFor(scope, userId, captured.revision(), captured.rulesFingerprint());
            long budget = Math.max(1000L, InferenceProfile.TIME_LIMIT_MS - (System.currentTimeMillis() - started));
            Materialization materialization = materializer.materialize(captured.ontology(), budget);
            captured.ontology().getOWLOntologyManager().clearOntologies();
            long storeStart = System.currentTimeMillis();
            InferredSnapshot built = InferenceStoreBuilder.build(key, scope, captured, materialization, stores);
            Map<String, Long> steps = new LinkedHashMap<>();
            steps.put("captureAndParse", captureMs);
            steps.putAll(materialization.stepMillis());
            steps.put("buildStore", System.currentTimeMillis() - storeStart);
            lastSteps = steps;
            return cache.put(built);
        } catch (InconsistentOntologyException e) {
            InferenceSnapshotKey key = keyFor(scope, userId, captured.revision(), captured.rulesFingerprint());
            return cache.put(InferredSnapshot.inconsistent(key, scope.graphs(), Instant.now(), e.getMessage(), e.explanation()));
        } catch (InferenceException e) {
            if (!InferenceException.BUSY.equals(e.reason())) {
                cache.recordFailure(lookup, e.reason());
            }
            throw e;
        } finally {
            permit.close();
        }
    }

    private InferenceSnapshotKey lookupKey(SparqlQueryScope scope, String userId) {
        return keyFor(scope, userId, source.revision(scope, userId), "");
    }

    private InferenceSnapshotKey keyFor(SparqlQueryScope scope, String userId, String revision, String rulesFingerprint) {
        String engine = ruleStage == null ? "none" : ruleStage.describe();
        return InferenceSnapshotKey.of(scope.projectId(), scope.draft(), userId, revision, IMPORTS_IDENTITY,
                rulesFingerprint, engine);
    }
}

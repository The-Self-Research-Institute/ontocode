package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.repository.Repository;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class InferredSnapshot {

    private final InferenceSnapshotKey key;
    private final Repository repository;
    private final List<String> graphs;
    private final long assertedTriples;
    private final long inferredTriples;
    private final Instant computedAt;
    private final Materialization materialization;
    private final String inconsistentMessage;
    private final String inconsistentExplanation;
    private final AtomicInteger users = new AtomicInteger();
    private volatile boolean retired;
    private boolean closed;

    InferredSnapshot(InferenceSnapshotKey key, Repository repository, List<String> graphs, long assertedTriples,
                     long inferredTriples, Instant computedAt, Materialization materialization) {
        this(key, repository, graphs, assertedTriples, inferredTriples, computedAt, materialization, null, null);
    }

    private InferredSnapshot(InferenceSnapshotKey key, Repository repository, List<String> graphs, long assertedTriples,
                             long inferredTriples, Instant computedAt, Materialization materialization,
                             String inconsistentMessage, String inconsistentExplanation) {
        this.key = key;
        this.repository = repository;
        this.graphs = graphs;
        this.assertedTriples = assertedTriples;
        this.inferredTriples = inferredTriples;
        this.computedAt = computedAt;
        this.materialization = materialization;
        this.inconsistentMessage = inconsistentMessage;
        this.inconsistentExplanation = inconsistentExplanation;
    }

    static InferredSnapshot inconsistent(InferenceSnapshotKey key, List<String> graphs, Instant at, String message,
                                         String explanation) {
        return new InferredSnapshot(key, null, graphs, 0, 0, at, null, message, explanation);
    }

    public InferenceSnapshotKey key() {
        return key;
    }

    public Repository repository() {
        return repository;
    }

    public List<String> graphs() {
        return graphs;
    }

    public long assertedTriples() {
        return assertedTriples;
    }

    public long inferredTriples() {
        return inferredTriples;
    }

    public Instant computedAt() {
        return computedAt;
    }

    public Materialization materialization() {
        return materialization;
    }

    public boolean isInconsistent() {
        return inconsistentMessage != null;
    }

    public String inconsistentMessage() {
        return inconsistentMessage;
    }

    public String inconsistentExplanation() {
        return inconsistentExplanation;
    }

    public long size() {
        return assertedTriples + inferredTriples;
    }

    void acquire() {
        users.incrementAndGet();
    }

    public void release() {
        if (users.decrementAndGet() <= 0 && retired) {
            close();
        }
    }

    void retire(InferenceCacheAccounting accounting, long bytesPerTriple) {
        accounting.remove(size() * bytesPerTriple);
        retired = true;
        if (users.get() <= 0) {
            close();
        }
    }

    private synchronized void close() {
        if (!closed && repository != null) {
            closed = true;
            repository.shutDown();
        }
    }
}

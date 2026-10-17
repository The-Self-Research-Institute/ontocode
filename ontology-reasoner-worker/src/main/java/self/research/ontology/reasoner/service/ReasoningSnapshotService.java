package self.research.ontology.reasoner.service;

import lombok.extern.slf4j.Slf4j;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.common.ReasoningHeapMonitor;
import self.research.ontology.common.ReasoningSnapshot;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class ReasoningSnapshotService {

    record Key(String projectId, long revision, long tripleCount, String reasoner) {}

    private record Entry(Map<String, Object> snapshot, long storedAtMs) {}

    private record Built(Map<String, Object> snapshot, long revision, long tripleCount) {}

    static final class AbandonedSnapshotException extends RuntimeException {
        AbandonedSnapshotException(Throwable cause) {
            super("The run building this snapshot was stopped", cause);
        }
    }

    private final OntologySessionService sessionService;
    private final ReasoningSnapshotStore store;
    private final int maxEntries;
    private final long ttlMs;
    private final double heapPressureUsedRatio;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final ConcurrentHashMap<Key, CompletableFuture<Map<String, Object>>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong builds = new AtomicLong();

    @Autowired
    public ReasoningSnapshotService(OntologySessionService sessionService,
                                    ReasoningSnapshotStore store,
                                    @Value("${ontocode.reasoning.snapshot-cache.max-entries:6}") int maxEntries,
                                    @Value("${ontocode.reasoning.snapshot-cache.ttl-minutes:120}") long ttlMinutes,
                                    @Value("${ontocode.reasoning.heap-pressure-used-ratio:0.70}") double heapPressureUsedRatio) {
        this.sessionService = sessionService;
        this.store = store != null ? store : ReasoningSnapshotStore.disabled();
        this.maxEntries = Math.max(0, maxEntries);
        this.ttlMs = Math.max(1, ttlMinutes) * 60_000L;
        this.heapPressureUsedRatio = heapPressureUsedRatio;
    }

    public Map<String, Object> snapshot(String projectId, String reasonerType, String ownerEmail) throws Exception {
        ReasonerType requested = ReasonerType.fromRequest(reasonerType);
        String owner = ownerEmail == null || ownerEmail.isBlank() ? null : ownerEmail;
        if (owner != null) {
            return served(build(projectId, requested, owner).snapshot(), false);
        }
        Key key = cacheKey(projectId, requested);
        if (key == null) {
            return served(build(projectId, requested, null).snapshot(), false);
        }
        while (true) {
            Map<String, Object> hit = lookup(key);
            if (hit != null) {
                return served(hit, true);
            }
            CompletableFuture<Map<String, Object>> mine = new CompletableFuture<>();
            CompletableFuture<Map<String, Object>> running = inFlight.putIfAbsent(key, mine);
            if (running == null) {
                return buildAndShare(key, mine, requested);
            }
            log.info("[Snapshot] Waiting for the {} snapshot of project {} revision {} that is already being built",
                    key.reasoner(), projectId, key.revision());
            Map<String, Object> joined = await(running);
            if (joined != null) {
                return served(joined, false);
            }
            log.info("[Snapshot] The run building the {} snapshot of project {} revision {} was stopped — building it here",
                    key.reasoner(), projectId, key.revision());
        }
    }

    public Optional<Map<String, Object>> cached(String projectId, String reasonerType, long tripleCount) {
        if (tripleCount < 0) {
            return Optional.empty();
        }
        long revision = sessionService.currentRevision(projectId);
        if (revision <= 0) {
            return Optional.empty();
        }
        Map<String, Object> hit = lookup(new Key(projectId, revision, tripleCount,
                ReasonerType.fromRequest(reasonerType).name()));
        return hit != null ? Optional.of(served(hit, true)) : Optional.empty();
    }

    public Optional<Map<String, Object>> cachedOrInFlight(String projectId, String reasonerType) {
        Key key = cacheKey(projectId, ReasonerType.fromRequest(reasonerType));
        if (key == null) {
            return Optional.empty();
        }
        Map<String, Object> hit = lookup(key);
        if (hit != null) {
            return Optional.of(served(hit, true));
        }
        CompletableFuture<Map<String, Object>> running = inFlight.get(key);
        if (running == null) {
            return Optional.empty();
        }
        try {
            Map<String, Object> joined = await(running);
            return joined != null ? Optional.of(served(joined, false)) : Optional.empty();
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() || e instanceof ReasonerInterruptedException) {
                throw e instanceof RuntimeException re ? re : new ReasonerInterruptedException(e);
            }
            return Optional.empty();
        }
    }

    @Scheduled(fixedDelayString = "${ontocode.reasoning.snapshot-cache.sweep-interval-ms:600000}")
    public void evictExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        synchronized (entries) {
            Iterator<Entry> it = entries.values().iterator();
            while (it.hasNext()) {
                if (isExpired(it.next(), now)) {
                    it.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("[Snapshot] Dropped {} expired snapshot(s) from memory", removed);
        }
    }

    private Key cacheKey(String projectId, ReasonerType requested) {
        long revision = sessionService.currentRevision(projectId);
        if (revision <= 0) {
            return null;
        }
        long tripleCount = sessionService.currentTripleCount(projectId);
        if (tripleCount < 0) {
            return null;
        }
        return new Key(projectId, revision, tripleCount, requested.name());
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    long buildCount() {
        return builds.get();
    }

    private Map<String, Object> buildAndShare(Key key, CompletableFuture<Map<String, Object>> mine,
                                              ReasonerType requested) throws Exception {
        try {
            Map<String, Object> hit = lookupInMemory(key);
            if (hit != null) {
                inFlight.remove(key, mine);
                mine.complete(hit);
                return served(hit, true);
            }
            Built built = build(key.projectId(), requested, null);
            remember(key, built);
            inFlight.remove(key, mine);
            mine.complete(built.snapshot());
            return served(built.snapshot(), false);
        } catch (Throwable t) {
            inFlight.remove(key, mine);
            mine.completeExceptionally(wasStopped(t) ? new AbandonedSnapshotException(t) : t);
            throw asException(t);
        }
    }

    private Built build(String projectId, ReasonerType requested, String owner) throws Exception {
        builds.incrementAndGet();
        try (OntologySessionService.ReasoningSession session =
                     sessionService.openSnapshotSession(projectId, requested, owner)) {
            Map<String, Object> built = ReasoningSnapshot.build(session.ontology(), session.reasoner(),
                    session.propertyReasoner());
            Map<String, Object> response = new LinkedHashMap<>(built);
            response.put("success", true);
            response.put("reasonerType", session.actualReasonerType().getDisplayName());
            response.put("propertyReasonerType", session.propertyReasonerType().getDisplayName());
            response.put("requestedReasoner", requested.name());
            response.put("ontologyRevision", session.revision());
            response.put("draft", owner != null);
            if (session.downgradedWarning() != null) {
                response.put("downgradedWarning", session.downgradedWarning());
            }
            log.info("[Snapshot] Built {} snapshot for project {} revision {} with {} / {} in {} ms",
                    requested, projectId, session.revision(), session.actualReasonerType(),
                    session.propertyReasonerType(), built.get("durationMs"));
            return new Built(response, session.revision(), session.tripleCount());
        }
    }

    private void remember(Key requestedKey, Built built) {
        if (built.revision() <= 0 || built.tripleCount() < 0) {
            return;
        }
        Key key = new Key(requestedKey.projectId(), built.revision(), built.tripleCount(), requestedKey.reasoner());
        keepInMemory(key, built.snapshot());
        store.save(key.projectId(), key.revision(), key.tripleCount(), key.reasoner(), built.snapshot());
    }

    private void keepInMemory(Key key, Map<String, Object> snapshot) {
        if (maxEntries == 0) {
            return;
        }
        synchronized (entries) {
            entries.entrySet().removeIf(e -> e.getKey().projectId().equals(key.projectId())
                    && (e.getKey().revision() < key.revision()
                    || (e.getKey().revision() == key.revision() && e.getKey().tripleCount() != key.tripleCount())));
            entries.put(key, new Entry(snapshot, System.currentTimeMillis()));
            boolean squeezed = ReasoningHeapMonitor.usedRatio() >= heapPressureUsedRatio;
            Iterator<Key> it = entries.keySet().iterator();
            while (it.hasNext() && (entries.size() > maxEntries || squeezed)) {
                Key eldest = it.next();
                if (eldest.equals(key)) {
                    continue;
                }
                it.remove();
                squeezed = false;
            }
        }
    }

    private Map<String, Object> lookup(Key key) {
        Map<String, Object> inMemory = lookupInMemory(key);
        if (inMemory != null) {
            return inMemory;
        }
        Optional<Map<String, Object>> saved = store.find(key.projectId(), key.revision(), key.tripleCount(), key.reasoner());
        if (saved.isEmpty()) {
            return null;
        }
        log.info("[Snapshot] Using the saved {} snapshot of project {} revision {}",
                key.reasoner(), key.projectId(), key.revision());
        keepInMemory(key, saved.get());
        return saved.get();
    }

    private Map<String, Object> lookupInMemory(Key key) {
        synchronized (entries) {
            Entry entry = entries.get(key);
            if (entry == null) {
                return null;
            }
            if (isExpired(entry, System.currentTimeMillis())) {
                entries.remove(key);
                return null;
            }
            return entry.snapshot();
        }
    }

    private boolean isExpired(Entry entry, long now) {
        return now - entry.storedAtMs() > ttlMs;
    }

    private static Map<String, Object> served(Map<String, Object> snapshot, boolean cached) {
        Map<String, Object> copy = new LinkedHashMap<>(snapshot);
        copy.put("cached", cached);
        return copy;
    }

    private static Map<String, Object> await(CompletableFuture<Map<String, Object>> running) throws Exception {
        try {
            return running.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReasonerInterruptedException("Reasoning was stopped");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof AbandonedSnapshotException) {
                return null;
            }
            throw asException(cause);
        } catch (CancellationException e) {
            return null;
        }
    }

    static boolean wasStopped(Throwable error) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ReasonerInterruptedException
                    || t instanceof InterruptedException
                    || (t instanceof InterruptedIOException && !(t instanceof SocketTimeoutException))
                    || t instanceof ClosedByInterruptException
                    || t instanceof CancellationException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static Exception asException(Throwable t) {
        if (t instanceof Error error) {
            throw error;
        }
        return (Exception) t;
    }
}

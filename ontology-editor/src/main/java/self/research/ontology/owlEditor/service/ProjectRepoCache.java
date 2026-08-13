package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.GraphQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ProjectRepoCache {

    private static final Logger log = LoggerFactory.getLogger(ProjectRepoCache.class);

    @Value("${ontocode.cache.memstore.enabled:true}")
    private boolean enabled;

    @Value("${ontocode.cache.memstore.max-projects:3}")
    private int maxProjects;

    @Value("${ontocode.cache.memstore.max-triples:5000000}")
    private long maxTriples;

    @Value("${ontocode.cache.memstore.no-cache-threshold:1500000}")
    private long noCacheThreshold;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> loadLocks = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> lastEvictedAtMs = new ConcurrentHashMap<>();

    private final Set<String> knownLargeProjects = ConcurrentHashMap.newKeySet();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong loadMs = new AtomicLong();

    public boolean isEnabled() { return enabled; }

    public long getHits()   { return hits.get(); }
    public long getMisses() { return misses.get(); }
    public int  getSize()   { return cache.size(); }

    public Repository getOrLoad(String projectId, Loader loader) {
        if (!enabled || projectId == null) {
            return null;
        }
        Entry entry = cache.get(projectId);
        if (entry != null) {
            entry.touch();
            hits.incrementAndGet();
            return entry.repo;
        }

        if (knownLargeProjects.contains(projectId)) {
            return null;
        }

        try {
            long estimate = loader.estimateTripleCount(projectId);
            if (estimate < 0) {

                log.info("[MEMCACHE] estimateTripleCount returned {} for {} — skipping cache load",
                        estimate, projectId);
                return null;
            }
            if (estimate > noCacheThreshold) {
                knownLargeProjects.add(projectId);
                log.info("[MEMCACHE] Skipping cache for project={} (estimate={} > no-cache-threshold={}) — marked as large",
                        projectId, estimate, noCacheThreshold);
                return null;
            }
        } catch (Exception e) {
            log.debug("[MEMCACHE] estimateTripleCount failed for {}: {} — skipping cache load", projectId, e.getMessage());
            return null;
        }

        Object bgLock = new Object();
        Object existing = loadLocks.putIfAbsent(projectId, bgLock);
        if (existing == null) {

            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    long start = System.currentTimeMillis();
                    Entry loaded = load(projectId, loader);
                    if (loaded == null) return;
                    Long evictedAt = lastEvictedAtMs.get(projectId);
                    if (evictedAt != null && loaded.loadStartedAt < evictedAt) {
                        try { loaded.repo.shutDown(); } catch (Exception ignore) {}
                        log.info("[MEMCACHE] Background load discarded stale snapshot for project={}", projectId);
                        return;
                    }
                    evictIfNeeded();
                    cache.put(projectId, loaded);
                    long ms = System.currentTimeMillis() - start;
                    loadMs.addAndGet(ms);
                    misses.incrementAndGet();
                    log.info("[MEMCACHE] Background-loaded project={} triples={} in {}ms (cached={}/{})",
                            projectId, loaded.triples, ms, cache.size(), maxProjects);
                } catch (Exception e) {
                    log.warn("[MEMCACHE] Background load failed for project={}: {}", projectId, e.getMessage());
                } finally {
                    loadLocks.remove(projectId, bgLock);
                }
            });
        }

        return null;
    }

    public void evict(String projectId) {

        lastEvictedAtMs.put(projectId, System.currentTimeMillis());
        Entry removed = cache.remove(projectId);
        if (removed != null) {
            try { removed.repo.shutDown(); } catch (Exception ignore) {}
            log.info("[MEMCACHE] Evicted project={}", projectId);
        }
        knownLargeProjects.remove(projectId);
    }

    public boolean isKnownLarge(String projectId) {
        return knownLargeProjects.contains(projectId);
    }

    public void markKnownLarge(String projectId) {
        if (projectId != null && !projectId.isBlank()) {
            knownLargeProjects.add(projectId);
        }
    }

    public void evictAll() {
        for (String id : new ArrayList<>(cache.keySet())) {
            evict(id);
        }
    }

    private Entry load(String projectId, Loader loader) throws Exception {

        long loadStartedAt = System.currentTimeMillis();

        SailRepository repo = new SailRepository(new MemoryStore());
        repo.init();

        long triples = 0;
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            try (GraphQueryResult result = loader.streamTriples(projectId)) {
                ValueFactory vf = conn.getValueFactory();
                IRI graphContext = vf.createIRI(loader.graphUri(projectId));
                while (result.hasNext()) {
                    Statement st = result.next();
                    conn.add(st.getSubject(), st.getPredicate(), st.getObject(), graphContext);
                    triples++;
                    if (triples > maxTriples) {
                        conn.rollback();
                        log.warn("[MEMCACHE] Project={} exceeds maxTriples={} — skipping cache",
                                projectId, maxTriples);
                        repo.shutDown();
                        return null;
                    }
                }
            }
            conn.commit();
        } catch (Exception e) {
            try { repo.shutDown(); } catch (Exception ignore) {}
            throw e;
        }
        return new Entry(repo, triples, loadStartedAt);
    }

    private void evictIfNeeded() {
        while (cache.size() >= maxProjects) {

            String victim = null;
            long victimScore = Long.MAX_VALUE;
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Entry> e : cache.entrySet()) {
                Entry v = e.getValue();
                long ageSec = (now - v.lastAccessMs) / 1000;

                long score = (v.triples * 1000) / (ageSec + 1);
                if (score < victimScore) {
                    victimScore = score;
                    victim = e.getKey();
                }
            }
            if (victim == null) break;
            evict(victim);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[MEMCACHE] Shutting down — evicting {} cached projects", cache.size());
        evictAll();
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("size", cache.size());
        m.put("maxProjects", maxProjects);
        m.put("hits", hits.get());
        m.put("misses", misses.get());
        m.put("totalLoadMs", loadMs.get());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<String, Entry> e : cache.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("projectId", e.getKey());
            row.put("triples", e.getValue().triples);
            row.put("ageSec", (System.currentTimeMillis() - e.getValue().lastAccessMs) / 1000);
            entries.add(row);
        }
        m.put("projects", entries);
        return m;
    }

    public interface Loader {
        GraphQueryResult streamTriples(String projectId) throws Exception;
        String graphUri(String projectId);

        default long estimateTripleCount(String projectId) { return -1; }
    }

    private static final class Entry {
        final Repository repo;
        final long triples;
        final long loadStartedAt;
        volatile long lastAccessMs;

        Entry(Repository repo, long triples, long loadStartedAt) {
            this.repo = repo;
            this.triples = triples;
            this.loadStartedAt = loadStartedAt;
            this.lastAccessMs = System.currentTimeMillis();
        }

        void touch() { this.lastAccessMs = System.currentTimeMillis(); }
    }
}

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

/**
 * Phase C: per-project in-memory SPARQL repository cache.
 *
 * <p>Mirrors a project's GraphDB named graph into a local RDF4J
 * {@link MemoryStore} so that subsequent SPARQL SELECT / ASK / CONSTRUCT
 * queries run in microseconds without hitting GraphDB (bypassing the
 * GraphDB Free 2-concurrent-query cap).
 *
 * <p><b>Design choices:</b>
 * <ul>
 *   <li><b>Evict-and-lazy-reload on write:</b> mutations remain full-speed
 *       SPARQL UPDATEs against GraphDB. After the update we simply drop
 *       the cached repo; the next read repopulates it. No incremental
 *       patching (which would be orders of magnitude more code for very
 *       little added value on a read-heavy workload).</li>
 *   <li><b>Named-graph fidelity:</b> triples are loaded into the same
 *       {@code http://ontocode.org/project/{id}} context that GraphDB
 *       uses, so existing {@code FROM <graph>} injection in
 *       {@link GraphDBDatasetService} works unchanged against the cache.</li>
 *   <li><b>LRU eviction:</b> bounded number of projects live in RAM at a
 *       time ({@code ontocode.cache.memstore.max-projects}, default 3).</li>
 * </ul>
 */
@Service
public class ProjectRepoCache {

    private static final Logger log = LoggerFactory.getLogger(ProjectRepoCache.class);

    @Value("${ontocode.cache.memstore.enabled:true}")
    private boolean enabled;

    @Value("${ontocode.cache.memstore.max-projects:3}")
    private int maxProjects;

    /** Hard ceiling on triples per project — above this we refuse to cache
     *  (protects the JVM heap on huge ontologies). */
    @Value("${ontocode.cache.memstore.max-triples:5000000}")
    private long maxTriples;

    /**
     * Projects with more triples than this skip the in-memory cache entirely
     * and are always served from Fuseki directly.  Keeps the 65-second reload
     * penalty from blocking every concurrent request when a huge ontology is
     * evicted.  Default: 1_500_000 triples (~600 MB heap).
     */
    @Value("${ontocode.cache.memstore.no-cache-threshold:1500000}")
    private long noCacheThreshold;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> loadLocks = new ConcurrentHashMap<>();

    /**
     * Projects confirmed to exceed noCacheThreshold — skip COUNT query on subsequent requests.
     * Cleared on evict() so a project that shrinks below the threshold gets re-evaluated.
     */
    private final Set<String> knownLargeProjects = ConcurrentHashMap.newKeySet();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong loadMs = new AtomicLong();

    public boolean isEnabled() { return enabled; }

    public long getHits()   { return hits.get(); }
    public long getMisses() { return misses.get(); }
    public int  getSize()   { return cache.size(); }

    /**
     * Returns the cached repository for {@code projectId}, loading it from
     * GraphDB on the fly if it is not already cached. Returns {@code null}
     * when the cache is disabled, the project exceeds the size ceiling, or
     * loading fails — callers must then fall back to GraphDB directly.
     */
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
        // Fast path: if we already know this project is too large, skip the COUNT query entirely.
        if (knownLargeProjects.contains(projectId)) {
            return null;
        }

        // Pre-flight: ask Fuseki for the triple count.
        //   estimate > threshold  → mark as large, skip caching.
        //   estimate == -1        → COUNT timed out or failed; skip loading too — CONSTRUCT ALL
        //                           would be even slower than COUNT on a cold/large dataset.
        //   estimate <= threshold → proceed to cache-load.
        try {
            long estimate = loader.estimateTripleCount(projectId);
            if (estimate < 0) {
                // COUNT timed out (cold TDB2 or Fuseki unreachable) — skip cache loading.
                // The caller will fall back to direct Fuseki queries which use indexed
                // lookups and are much faster than a full CONSTRUCT ALL scan.
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

        // Coalesce concurrent loaders for the same project.
        Object lock = loadLocks.computeIfAbsent(projectId, k -> new Object());
        synchronized (lock) {
            entry = cache.get(projectId);
            if (entry != null) {
                entry.touch();
                hits.incrementAndGet();
                return entry.repo;
            }
            try {
                long start = System.currentTimeMillis();
                Entry loaded = load(projectId, loader);
                if (loaded == null) {
                    return null;
                }
                evictIfNeeded();
                cache.put(projectId, loaded);
                long ms = System.currentTimeMillis() - start;
                loadMs.addAndGet(ms);
                misses.incrementAndGet();
                log.info("[MEMCACHE] Loaded project={} triples={} in {}ms (cached={}/{})",
                        projectId, loaded.triples, ms, cache.size(), maxProjects);
                return loaded.repo;
            } catch (Exception e) {
                log.warn("[MEMCACHE] Failed to load project={} into memory cache: {} — falling back to GraphDB",
                        projectId, e.getMessage());
                return null;
            } finally {
                loadLocks.remove(projectId);
            }
        }
    }

    /**
     * Drop the cached repo for {@code projectId}. Call after every mutation.
     */
    public void evict(String projectId) {
        Entry removed = cache.remove(projectId);
        if (removed != null) {
            try { removed.repo.shutDown(); } catch (Exception ignore) {}
            log.info("[MEMCACHE] Evicted project={}", projectId);
        }
        knownLargeProjects.remove(projectId);
    }

    /** True if this project has been confirmed to exceed the no-cache threshold. */
    public boolean isKnownLarge(String projectId) {
        return knownLargeProjects.contains(projectId);
    }

    public void evictAll() {
        for (String id : new ArrayList<>(cache.keySet())) {
            evict(id);
        }
    }

    private Entry load(String projectId, Loader loader) throws Exception {
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
        return new Entry(repo, triples);
    }

    private void evictIfNeeded() {
        while (cache.size() >= maxProjects) {
            // Cost-aware eviction: prefer to evict the project with the lowest
            // reload cost (fewest triples).  When multiple projects have similar
            // sizes, break ties by least-recently-used.  This keeps expensive-
            // to-reload large ontologies in cache as long as possible.
            String victim = null;
            long victimScore = Long.MAX_VALUE; // lower = evict first
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Entry> e : cache.entrySet()) {
                Entry v = e.getValue();
                long ageSec = (now - v.lastAccessMs) / 1000;
                // score = triples * 1000 / (ageSec + 1)
                // small + old → low score → evicted first
                // large + young → high score → kept longest
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

    /** Simple stats snapshot, exposed for logging / actuator. */
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

    /** Supplied by GraphDBDatasetService so the cache doesn't know about HTTP itself. */
    public interface Loader {
        GraphQueryResult streamTriples(String projectId) throws Exception;
        String graphUri(String projectId);
        /** Quick COUNT query — return -1 if unknown / unsupported. */
        default long estimateTripleCount(String projectId) { return -1; }
    }

    private static final class Entry {
        final Repository repo;
        final long triples;
        volatile long lastAccessMs;

        Entry(Repository repo, long triples) {
            this.repo = repo;
            this.triples = triples;
            this.lastAccessMs = System.currentTimeMillis();
        }

        void touch() { this.lastAccessMs = System.currentTimeMillis(); }
    }
}

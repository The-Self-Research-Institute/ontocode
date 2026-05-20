package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.TopLevelClassCacheDoc;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.repository.TopLevelClassCacheRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistent cache for pre-computed top-level class results, backed by MongoDB.
 *
 * <p>Layer 2 in the read path (between Caffeine in-memory cache and Fuseki SPARQL):
 * <pre>
 *   Request → Caffeine (ms, same JVM) → MongoDB (ms, across restarts) → Fuseki (s-min, cold TDB2)
 * </pre>
 *
 * <p>All writes are non-blocking (callers pass the stored list after computation completes
 * in a CompletableFuture). Reads are synchronous because the response depends on the result.
 * Eviction is eager — called on every ontology mutation and import completion.
 */
@Service
public class TopLevelClassCacheService {

    private static final Logger log = LoggerFactory.getLogger(TopLevelClassCacheService.class);

    private final TopLevelClassCacheRepository repository;

    public TopLevelClassCacheService(TopLevelClassCacheRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the cached top-level class list for {@code projectId} trimmed to
     * {@code limit}, or {@code null} on cache miss, stale entry, or any error.
     * Returning null tells the caller to recompute from Fuseki.
     */
    public List<OntologyDto.TreeNode> get(String projectId, int limit) {
        try {
            Optional<TopLevelClassCacheDoc> opt = repository.findById(projectId);
            if (opt.isEmpty()) {
                log.debug("[TLCACHE] Miss for project={}", projectId);
                return null;
            }
            TopLevelClassCacheDoc doc = opt.get();
            if (doc.isStale()) {
                log.info("[TLCACHE] Stale (>24h) for project={} — recomputing", projectId);
                return null;
            }
            if (!doc.coversLimit(limit)) {
                log.info("[TLCACHE] Stored {} nodes with computedLimit={}, requested limit={} — recomputing",
                        doc.getNodes() == null ? 0 : doc.getNodes().size(), doc.getComputedWithLimit(), limit);
                return null;
            }
            List<OntologyDto.TreeNode> nodes = doc.getNodes();
            List<OntologyDto.TreeNode> result = nodes.size() > limit ? nodes.subList(0, limit) : nodes;
            log.info("[TLCACHE] HIT for project={} ({} nodes returned, limit={})", projectId, result.size(), limit);
            return result;
        } catch (Exception e) {
            log.warn("[TLCACHE] Read failed for project={}: {} — falling back to Fuseki", projectId, e.getMessage());
            return null;
        }
    }

    /**
     * Stores the fully-enriched result in MongoDB. Safe to call from a background thread.
     * Failures are logged and silently swallowed — a store failure degrades to cache miss,
     * not a user-visible error.
     */
    public void put(String projectId, List<OntologyDto.TreeNode> nodes, int computedWithLimit) {
        try {
            repository.save(new TopLevelClassCacheDoc(projectId, nodes, computedWithLimit));
            log.info("[TLCACHE] Stored {} nodes for project={} (computedWithLimit={})",
                    nodes.size(), projectId, computedWithLimit);
        } catch (Exception e) {
            log.warn("[TLCACHE] Store failed for project={}: {}", projectId, e.getMessage());
        }
    }

    /**
     * Removes the cached entry for {@code projectId}. Call on every ontology mutation
     * and import completion so stale data is never served.
     */
    public void evict(String projectId) {
        try {
            repository.deleteById(projectId);
            log.info("[TLCACHE] Evicted project={}", projectId);
        } catch (Exception e) {
            log.debug("[TLCACHE] Eviction failed for project={}: {}", projectId, e.getMessage());
        }
    }

    /** Evicts all projects — called on full cache invalidation (e.g., admin reset). */
    public void evictAll() {
        try {
            long count = repository.count();
            repository.deleteAll();
            log.info("[TLCACHE] Evicted all {} cached entries", count);
        } catch (Exception e) {
            log.warn("[TLCACHE] evictAll failed: {}", e.getMessage());
        }
    }
}

package self.research.ontology.owlEditor.service;

import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Evicts Spring/Caffeine ontology query caches for a single project without
 * flushing other projects' warm entries.
 */
@Service
public class OntologySpringCacheEvictionService {

    private static final Logger log = LoggerFactory.getLogger(OntologySpringCacheEvictionService.class);

    // All caches use keys of the form: projectId_...[_'public'|_'draft:'userId]
    // (see SparqlQueryContext.cacheKeyComponent()) — a plain prefix scan on "projectId_"
    // covers every one of them, public or draft, regardless of how many segments precede
    // the scope component.
    private static final List<String> PREFIX_KEY_CACHES = List.of(
            "topLevelClasses", "classChildren", "allClasses", "ontologyProperties",
            "ontologyAnnotationProperties", "ontologyIndividuals", "classInstances",
            "individualCount", "debugInfo", "classInstanceCounts"
    );

    @Autowired(required = false)
    private CacheManager cacheManager;

    public void evictForProject(String projectId) {
        if (cacheManager == null || projectId == null || projectId.isBlank()) {
            return;
        }
        String prefix = projectId + "_";
        int evicted = 0;
        for (String cacheName : PREFIX_KEY_CACHES) {
            evicted += evictKeysWithPrefix(cacheName, prefix);
        }
        org.springframework.cache.Cache topLevel = cacheManager.getCache("topLevelClasses");
        if (topLevel != null) {
            topLevel.evict(projectId + "_statusCount");
            evicted++;
        }
        // graphCache keys are ontology-IRI based; clear all on mutation (small cache, correctness first).
        org.springframework.cache.Cache graphCache = cacheManager.getCache("graphCache");
        if (graphCache != null) {
            graphCache.clear();
        }
        log.debug("[CACHE] Evicted {} Spring cache entries for project {}", evicted, projectId);
    }

    /**
     * Evict only the Caffeine L1 cache entries belonging to a single user.
     *
     * All caches now include userId in their key (projectId_..._userId), so a
     * single prefix+suffix scan covers everything — no special-casing needed.
     */
    public void evictForProjectAndUser(String projectId, String userId) {
        if (cacheManager == null || projectId == null || projectId.isBlank()
                || userId == null || userId.isBlank()) {
            return;
        }
        String prefix = projectId + "_";
        // Matches SparqlQueryContext.cacheKeyComponent()'s "draft:" + userId format, not bare
        // userId — these keys never end in a bare userId (see cacheKeyComponent doc).
        String suffix = "draft:" + userId;
        int evicted = 0;
        for (String cacheName : PREFIX_KEY_CACHES) {
            evicted += evictKeysWithPrefixAndSuffix(cacheName, prefix, suffix);
        }

        // statusCount entry also ends with draft:userId when in draft mode
        org.springframework.cache.Cache topLevel = cacheManager.getCache("topLevelClasses");
        if (topLevel != null) {
            topLevel.evict(projectId + "_statusCount_draft:" + userId);
            evicted++;
        }
        // graphCache: clear all — graph view may render draft data visible to the draft user.
        org.springframework.cache.Cache graphCache = cacheManager.getCache("graphCache");
        if (graphCache != null) {
            graphCache.clear();
        }
        log.info("[CACHE] evictForProjectAndUser project={} user={} total-evicted={}", projectId, userId, evicted);
    }

    private int evictKeysWithPrefix(String cacheName, String prefix) {
        org.springframework.cache.Cache springCache = cacheManager.getCache(cacheName);
        if (springCache == null) {
            return 0;
        }
        Object nativeCache = springCache.getNativeCache();
        if (!(nativeCache instanceof Cache<?, ?> caffeine)) {
            return 0;
        }
        List<Object> keys = new ArrayList<>();
        for (Object key : caffeine.asMap().keySet()) {
            if (key != null && key.toString().startsWith(prefix)) {
                keys.add(key);
            }
        }
        for (Object key : keys) {
            springCache.evict(key);
        }
        return keys.size();
    }

    private int evictKeysWithPrefixAndSuffix(String cacheName, String prefix, String suffix) {
        org.springframework.cache.Cache springCache = cacheManager.getCache(cacheName);
        if (springCache == null) {
            return 0;
        }
        Object nativeCache = springCache.getNativeCache();
        if (!(nativeCache instanceof Cache<?, ?> caffeine)) {
            return 0;
        }
        List<Object> keys = new ArrayList<>();
        for (Object key : caffeine.asMap().keySet()) {
            if (key != null) {
                String k = key.toString();
                if (k.startsWith(prefix) && k.endsWith(suffix)) {
                    keys.add(key);
                }
            }
        }
        for (Object key : keys) {
            springCache.evict(key);
        }
        return keys.size();
    }
}

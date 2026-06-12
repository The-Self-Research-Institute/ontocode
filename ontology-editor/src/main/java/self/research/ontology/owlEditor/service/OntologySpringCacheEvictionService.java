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

    private static final List<String> PREFIX_KEY_CACHES = List.of(
            "topLevelClasses", "classChildren", "allClasses", "ontologyProperties",
            "ontologyIndividuals", "classDetails", "classAnnotations", "classInstances"
    );

    private static final List<String> EXACT_KEY_CACHES = List.of(
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
        for (String cacheName : EXACT_KEY_CACHES) {
            org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.evict(projectId);
                evicted++;
            }
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
}

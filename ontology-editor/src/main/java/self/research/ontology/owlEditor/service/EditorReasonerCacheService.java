package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.common.ReasoningHeapMonitor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class EditorReasonerCacheService {

    private final ReasonerService reasonerService;

    @Value("${ontocode.reasoner.ontology-cache-max:2}")
    private int maxOntologies;

    @Value("${ontocode.reasoner.hierarchy-cache-max:8}")
    private int maxHierarchies;

    @Value("${ontocode.reasoner.ontology-cache-idle-minutes:15}")
    private int idleEvictionMinutes;

    @Value("${ontocode.reasoning.heap-comfort-used-ratio:0.40}")
    private double heapComfortUsedRatio;

    @Value("${ontocode.reasoning.heap-pressure-used-ratio:0.70}")
    private double heapPressureUsedRatio;

    private final LinkedHashMap<String, CachedOntology> ontologies = new LinkedHashMap<>();
    private final LinkedHashMap<String, CachedHierarchy> hierarchies = new LinkedHashMap<>();

    public EditorReasonerCacheService(ReasonerService reasonerService) {
        this.reasonerService = reasonerService;
    }

    public record HierarchyCacheEntry(List<Map<String, Object>> hierarchy, String reasonerType) {}

    private record CachedOntology(OWLOntology ontology, Instant lastAccessed) {}

    private record CachedHierarchy(HierarchyCacheEntry entry, Instant lastAccessed) {}

    public Optional<OWLOntology> getOntology(String projectId) {
        synchronized (ontologies) {
            CachedOntology cached = ontologies.remove(projectId);
            if (cached == null) {
                return Optional.empty();
            }
            ontologies.put(projectId, new CachedOntology(cached.ontology(), Instant.now()));
            return Optional.of(cached.ontology());
        }
    }

    public void putOntology(String projectId, OWLOntology ontology) {
        synchronized (ontologies) {
            ontologies.put(projectId, new CachedOntology(ontology, Instant.now()));
            log.debug("[EditorReasonerCache] Cached ontology {} ({} projects, heap {}% used)",
                    projectId, ontologies.size(), Math.round(ReasoningHeapMonitor.usedRatio() * 100));
        }
    }

    public void prepareForOntologyLoad(String projectId) {
        synchronized (ontologies) {
            if (ontologies.containsKey(projectId)) {
                getOntology(projectId);
                return;
            }
            makeRoomForOntology();
        }
    }

    public Optional<HierarchyCacheEntry> getHierarchy(String cacheKey) {
        synchronized (hierarchies) {
            CachedHierarchy cached = hierarchies.remove(cacheKey);
            if (cached == null) {
                return Optional.empty();
            }
            hierarchies.put(cacheKey, new CachedHierarchy(cached.entry(), Instant.now()));
            return Optional.of(cached.entry());
        }
    }

    public void putHierarchy(String cacheKey, HierarchyCacheEntry entry) {
        synchronized (hierarchies) {
            if (isHeapComfortable() && hierarchies.size() < maxHierarchies) {
                hierarchies.put(cacheKey, new CachedHierarchy(entry, Instant.now()));
                return;
            }
            while (hierarchies.size() >= maxHierarchies || isHeapUnderPressure()) {
                if (!evictOldestHierarchy()) {
                    break;
                }
            }
            hierarchies.put(cacheKey, new CachedHierarchy(entry, Instant.now()));
        }
    }

    public void invalidateOntology(String projectId) {
        synchronized (ontologies) {
            CachedOntology removed = ontologies.remove(projectId);
            if (removed != null) {
                reasonerService.releaseOntologyFromMemory(removed.ontology());
            }
        }
        synchronized (hierarchies) {
            hierarchies.keySet().removeIf(key -> key.startsWith(projectId + "-"));
        }
    }

    public void stopReasoning(String projectId, String reasonerType) {
        Optional<OWLOntology> ontOpt = getOntology(projectId);
        ontOpt.ifPresent(ont -> {
            if (reasonerType != null && !reasonerType.isBlank()) {
                try {
                    reasonerService.disposeReasoner(ont, ReasonerType.valueOf(reasonerType.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    log.warn("[EditorReasonerCache] Unknown reasoner type {} for stop", reasonerType);
                }
            } else {
                for (ReasonerType type : ReasonerType.values()) {
                    reasonerService.disposeReasoner(ont, type);
                }
            }
        });
        synchronized (hierarchies) {
            hierarchies.keySet().removeIf(key -> key.startsWith(projectId + "-"));
        }
        log.info("[EditorReasonerCache] Stopped reasoning for project {}", projectId);
    }

    public void clearAll() {
        synchronized (ontologies) {
            for (CachedOntology cached : ontologies.values()) {
                reasonerService.releaseOntologyFromMemory(cached.ontology());
            }
            ontologies.clear();
        }
        synchronized (hierarchies) {
            hierarchies.clear();
        }
        reasonerService.clearCache();
        log.info("[EditorReasonerCache] Cleared all ontology and hierarchy caches");
    }

    @Scheduled(fixedDelayString = "${ontocode.reasoner.cache-janitor-interval-ms:60000}")
    public void evictStaleIdleEntries() {
        if (!isHeapUnderPressure() && ontologies.size() <= maxOntologies
                && hierarchies.size() <= maxHierarchies) {
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofMinutes(Math.max(1, idleEvictionMinutes)));
        int evicted = 0;

        synchronized (ontologies) {
            List<String> stale = new ArrayList<>();
            for (var e : ontologies.entrySet()) {
                if (e.getValue().lastAccessed().isBefore(cutoff)) {
                    stale.add(e.getKey());
                }
            }
            for (String projectId : stale) {
                if (!needsOntologyEviction()) {
                    break;
                }
                invalidateOntology(projectId);
                evicted++;
            }
        }

        synchronized (hierarchies) {
            List<String> stale = new ArrayList<>();
            for (var e : hierarchies.entrySet()) {
                if (e.getValue().lastAccessed().isBefore(cutoff)) {
                    stale.add(e.getKey());
                }
            }
            for (String key : stale) {
                if (!needsHierarchyEviction()) {
                    break;
                }
                hierarchies.remove(key);
                evicted++;
            }
        }

        if (evicted > 0) {
            log.info("[EditorReasonerCache] Stale-idle pass evicted {} entries (heap {}% used)",
                    evicted, Math.round(ReasoningHeapMonitor.usedRatio() * 100));
        }
    }

    private void makeRoomForOntology() {
        if (isHeapComfortable() && ontologies.size() < maxOntologies) {
            return;
        }
        while (ontologies.size() >= maxOntologies || isHeapUnderPressure()) {
            if (!evictOldestOntology()) {
                break;
            }
        }
    }

    private boolean needsOntologyEviction() {
        return ontologies.size() >= maxOntologies || isHeapUnderPressure();
    }

    private boolean needsHierarchyEviction() {
        return hierarchies.size() > maxHierarchies || isHeapUnderPressure();
    }

    private boolean isHeapComfortable() {
        return ReasoningHeapMonitor.usedRatio() < heapComfortUsedRatio;
    }

    private boolean isHeapUnderPressure() {
        return ReasoningHeapMonitor.usedRatio() >= heapPressureUsedRatio;
    }

    private boolean evictOldestOntology() {
        var it = ontologies.entrySet().iterator();
        if (!it.hasNext()) {
            return false;
        }
        String oldestProject = it.next().getKey();
        invalidateOntology(oldestProject);
        log.info("[EditorReasonerCache] LRU evicted ontology {} (heap {}% used)",
                oldestProject, Math.round(ReasoningHeapMonitor.usedRatio() * 100));
        return true;
    }

    private boolean evictOldestHierarchy() {
        var it = hierarchies.entrySet().iterator();
        if (!it.hasNext()) {
            return false;
        }
        String oldestKey = it.next().getKey();
        hierarchies.remove(oldestKey);
        log.info("[EditorReasonerCache] LRU evicted hierarchy {}", oldestKey);
        return true;
    }
}

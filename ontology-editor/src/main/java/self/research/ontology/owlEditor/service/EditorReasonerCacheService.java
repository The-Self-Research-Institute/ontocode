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

    @Value("${ontocode.reasoner.snapshot-cache-max:4}")
    private int maxSnapshots;

    @Value("${ontocode.reasoner.ontology-cache-idle-minutes:15}")
    private int idleEvictionMinutes;

    @Value("${ontocode.reasoning.heap-comfort-used-ratio:0.40}")
    private double heapComfortUsedRatio;

    @Value("${ontocode.reasoning.heap-pressure-used-ratio:0.70}")
    private double heapPressureUsedRatio;

    private final LinkedHashMap<String, CachedOntology> ontologies = new LinkedHashMap<>();
    private final LinkedHashMap<String, CachedHierarchy> hierarchies = new LinkedHashMap<>();
    private final LinkedHashMap<String, CachedSnapshot> snapshots = new LinkedHashMap<>();

    public EditorReasonerCacheService(ReasonerService reasonerService) {
        this.reasonerService = reasonerService;
    }

    public record HierarchyCacheEntry(List<Map<String, Object>> hierarchy, String reasonerType) {}

    public static String draftHierarchyKey(String projectId, ReasonerType type, String userId, String draftVersion) {
        return projectId + "-" + type.name() + "-draft-" + userId + "@" + draftVersion;
    }

    private record CachedOntology(OWLOntology ontology, long version, Instant lastAccessed) {}

    private record CachedHierarchy(HierarchyCacheEntry entry, Instant lastAccessed) {}

    private record CachedSnapshot(Map<String, Object> snapshot, Instant lastAccessed) {}

    private static final String DRAFT_MARKER = "-draft-";

    public static String snapshotKey(String projectId, String requestedType, long revision) {
        return projectId + "-" + requestedType + "-snapshot@" + revision;
    }

    public static String draftSnapshotKey(String projectId, String requestedType, String userId, String draftVersion) {
        return projectId + "-" + requestedType + "-snapshot" + DRAFT_MARKER + userId + "@" + draftVersion;
    }

    public Optional<Map<String, Object>> getSnapshot(String cacheKey) {
        synchronized (snapshots) {
            CachedSnapshot cached = snapshots.remove(cacheKey);
            if (cached == null) {
                return Optional.empty();
            }
            snapshots.put(cacheKey, new CachedSnapshot(cached.snapshot(), Instant.now()));
            return Optional.of(cached.snapshot());
        }
    }

    public void putSnapshot(String cacheKey, Map<String, Object> snapshot) {
        synchronized (snapshots) {
            snapshots.remove(cacheKey);
            while (!snapshots.isEmpty() && (snapshots.size() >= maxSnapshots || isHeapUnderPressure())) {
                String oldest = snapshots.keySet().iterator().next();
                snapshots.remove(oldest);
                log.info("[EditorReasonerCache] LRU evicted snapshot {}", oldest);
            }
            snapshots.put(cacheKey, new CachedSnapshot(snapshot, Instant.now()));
        }
    }

    public Optional<OWLOntology> getOntology(String projectId) {
        synchronized (ontologies) {
            CachedOntology cached = ontologies.remove(projectId);
            if (cached == null) {
                return Optional.empty();
            }
            ontologies.put(projectId, new CachedOntology(cached.ontology(), cached.version(), Instant.now()));
            return Optional.of(cached.ontology());
        }
    }

    public Optional<OWLOntology> getOntologyAtVersion(String projectId, long version) {
        if (version < 0) {
            return Optional.empty();
        }
        synchronized (ontologies) {
            CachedOntology cached = ontologies.get(projectId);
            if (cached == null || cached.version() != version) {
                return Optional.empty();
            }
            ontologies.remove(projectId);
            ontologies.put(projectId, new CachedOntology(cached.ontology(), cached.version(), Instant.now()));
            return Optional.of(cached.ontology());
        }
    }

    public void putOntology(String projectId, OWLOntology ontology) {
        putOntology(projectId, ontology, -1L);
    }

    public void putOntology(String projectId, OWLOntology ontology, long version) {
        synchronized (ontologies) {
            ontologies.put(projectId, new CachedOntology(ontology, version, Instant.now()));
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
        synchronized (snapshots) {
            snapshots.keySet().removeIf(key -> key.startsWith(projectId + "-") && !key.contains(DRAFT_MARKER));
        }
    }

    public void invalidateDraft(String projectId, String userId) {
        String projectPrefix = projectId + "-";
        String userMarker = (userId == null || userId.isBlank()) ? DRAFT_MARKER : DRAFT_MARKER + userId + "@";
        synchronized (hierarchies) {
            hierarchies.keySet().removeIf(key -> key.startsWith(projectPrefix) && key.contains(userMarker));
        }
        synchronized (snapshots) {
            snapshots.keySet().removeIf(key -> key.startsWith(projectPrefix) && key.contains(userMarker));
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
        synchronized (snapshots) {
            snapshots.keySet().removeIf(key -> key.startsWith(projectId + "-"));
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
        synchronized (snapshots) {
            snapshots.clear();
        }
        reasonerService.clearCache();
        log.info("[EditorReasonerCache] Cleared all ontology and hierarchy caches");
    }

    @Scheduled(fixedDelayString = "${ontocode.reasoner.cache-janitor-interval-ms:60000}")
    public void evictStaleIdleEntries() {
        if (isHeapUnderPressure()) {
            synchronized (snapshots) {
                snapshots.clear();
            }
        }
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

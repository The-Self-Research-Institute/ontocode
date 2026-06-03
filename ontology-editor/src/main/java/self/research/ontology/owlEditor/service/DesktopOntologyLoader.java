package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Loads an OWL file into OWLAPI after Fuseki import completes and stores the
 * parsed model + structural reasoner in ProjectOntologyCache.
 *
 * Desktop-only — never active in cloud deployments.
 * This is the Protégé-style fast path: one in-memory parse, then instant hierarchy
 * and class details without Fuseki SPARQL.
 */
@Service
@ConditionalOnProperty(name = "ontocode.desktop.mode", havingValue = "true")
public class DesktopOntologyLoader {

    private static final Logger log = LoggerFactory.getLogger(DesktopOntologyLoader.class);

    @Autowired
    private ProjectOntologyCache cache;

    @Autowired
    private StorageManager storageManager;

    @Autowired(required = false)
    private DesktopHierarchyService desktopHierarchyService;

    private final Set<String> loadingInProgress = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> warmWaiters = new ConcurrentHashMap<>();

    /**
     * Block until the OWLAPI model is cached (or timeout). Used by the desktop UI
     * so the first screen uses in-memory APIs like Protégé instead of slow SPARQL.
     */
    public Map<String, Object> warmProject(String projectId, long timeoutMs) {
        if (cache.has(projectId)) {
            Map<String, Object> warm = new java.util.LinkedHashMap<>();
            warm.put("ready", true);
            warm.put("sparqlFallback", false);
            warm.put("elapsedMs", 0L);
            warm.putAll(declarationCountsFromCache(projectId));
            return warm;
        }

        long start = System.currentTimeMillis();
        CompletableFuture<Boolean> waiter = warmWaiters.computeIfAbsent(projectId, id -> new CompletableFuture<>());
        triggerLazyLoadIfNeeded(projectId);

        if (!loadingInProgress.contains(projectId) && !cache.has(projectId)) {
            // No file or heap guard rejected load before async started
            completeWarmWaiters(projectId, false);
            return Map.of(
                "ready", false,
                "sparqlFallback", true,
                "elapsedMs", System.currentTimeMillis() - start,
                "message", "OWLAPI load not started (missing file or insufficient heap)"
            );
        }

        try {
            boolean ready = waiter.get(timeoutMs, TimeUnit.MILLISECONDS);
            Map<String, Object> warm = new java.util.LinkedHashMap<>();
            warm.put("ready", ready);
            warm.put("sparqlFallback", !ready);
            warm.put("elapsedMs", System.currentTimeMillis() - start);
            if (ready) {
                warm.putAll(declarationCountsFromCache(projectId));
            }
            return warm;
        } catch (TimeoutException e) {
            return Map.of(
                "ready", cache.has(projectId),
                "pending", true,
                "sparqlFallback", !cache.has(projectId),
                "elapsedMs", System.currentTimeMillis() - start
            );
        } catch (Exception e) {
            log.warn("[Desktop] warmProject failed for {}: {}", projectId, e.getMessage());
            return Map.of(
                "ready", cache.has(projectId),
                "sparqlFallback", !cache.has(projectId),
                "elapsedMs", System.currentTimeMillis() - start,
                "error", e.getMessage() != null ? e.getMessage() : "warm failed"
            );
        } finally {
            warmWaiters.remove(projectId);
        }
    }

    public void triggerLazyLoadIfNeeded(String projectId) {
        if (cache.has(projectId) || loadingInProgress.contains(projectId)) {
            if (cache.has(projectId)) {
                completeWarmWaiters(projectId, true);
            }
            return;
        }
        findFastestParseSource(projectId).ifPresentOrElse(
            path -> {
                loadingInProgress.add(projectId);
                loadAndCacheAsync(projectId, path);
            },
            () -> completeWarmWaiters(projectId, false)
        );
    }

    private Optional<Path> findFastestParseSource(String projectId) {
        Path dir = storageManager.projectDir(projectId);
        List<String> fastFirst = List.of(
            "ontology.original.ofn",
            "ontology.original.ttl",
            "ontology.original.nt",
            "ontology.current.ttl",
            "ontology.original.owl",
            "ontology.current.owl"
        );
        for (String name : fastFirst) {
            Path p = dir.resolve(name);
            if (java.nio.file.Files.exists(p)) {
                log.info("[Desktop] Using {} for OWLAPI parse (fastest available)", name);
                return Optional.of(p);
            }
        }
        return storageManager.findCurrentOntology(projectId);
    }

    @Async("desktopModelExecutor")
    public void loadAndCacheAsync(String projectId, Path owlFilePath) {
        boolean success = false;
        try {
            success = loadIntoCache(projectId, owlFilePath);
        } finally {
            loadingInProgress.remove(projectId);
            completeWarmWaiters(projectId, success);
        }
    }

    private boolean loadIntoCache(String projectId, Path owlFilePath) {
        if (!owlFilePath.toFile().exists()) {
            log.warn("[Desktop] OWL file not found, skipping OWLAPI cache: {}", owlFilePath);
            return false;
        }

        long fileSizeMb = owlFilePath.toFile().length() / (1024 * 1024);
        Runtime rt = Runtime.getRuntime();
        long maxHeapMb = rt.maxMemory() / (1024 * 1024);
        long estimatedModelMb = Math.max(64, fileSizeMb * 3);
        long heapReserveMb = 384;
        if (estimatedModelMb > maxHeapMb - heapReserveMb) {
            log.info("[Desktop] File {} MB (~{} MB model) exceeds heap budget ({} MB heap) — using Fuseki SPARQL for project {}",
                fileSizeMb, estimatedModelMb, maxHeapMb, projectId);
            return false;
        }

        long start = System.currentTimeMillis();
        log.info("[Desktop] Loading OWLAPI model for project {} from {} ({} MB, heap {} MB)",
            projectId, owlFilePath, fileSizeMb, maxHeapMb);

        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            manager.setOntologyLoaderConfiguration(
                new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                    .setLoadAnnotationAxioms(true)
            );

            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(owlFilePath.toFile());
            long classCount = ontology.classesInSignature().count();
            log.info("[Desktop] OWLAPI parsed {} classes in {}ms", classCount,
                System.currentTimeMillis() - start);

            OWLReasoner reasoner = new StructuralReasonerFactory().createNonBufferingReasoner(ontology);
            reasoner.precomputeInferences();

            cache.put(projectId, ontology, reasoner, manager);
            log.info("[Desktop] OWLAPI model + reasoner cached for project {} in {}ms total",
                projectId, System.currentTimeMillis() - start);
            return true;

        } catch (OutOfMemoryError oom) {
            cache.evict(projectId);
            log.warn("[Desktop] Out of memory loading OWLAPI model for project {} — falling back to Fuseki SPARQL", projectId);
            return false;
        } catch (Exception e) {
            log.warn("[Desktop] Failed to cache OWLAPI model for project {}: {}", projectId, e.getMessage());
            return false;
        }
    }

    private Map<String, Object> declarationCountsFromCache(String projectId) {
        if (desktopHierarchyService != null) {
            return desktopHierarchyService.declarationCounts(projectId);
        }
        return Map.of();
    }

    private void completeWarmWaiters(String projectId, boolean success) {
        CompletableFuture<Boolean> waiter = warmWaiters.remove(projectId);
        if (waiter != null && !waiter.isDone()) {
            waiter.complete(success || cache.has(projectId));
        }
    }
}

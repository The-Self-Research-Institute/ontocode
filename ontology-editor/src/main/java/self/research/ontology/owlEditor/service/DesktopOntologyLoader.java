package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.config.FastOpenCondition;

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
 * Protégé-style fast path: one in-memory parse, then instant hierarchy without Fuseki SPARQL.
 * Active on desktop and on cloud when {@code ontocode.fastopen.enabled=true} (default).
 */
@Service
@Conditional(FastOpenCondition.class)
public class DesktopOntologyLoader {

    private static final Logger log = LoggerFactory.getLogger(DesktopOntologyLoader.class);

    @Autowired
    private ProjectOntologyCache cache;

    @Autowired
    private StorageManager storageManager;

    @Autowired(required = false)
    private DesktopHierarchyService desktopHierarchyService;

    @Autowired(required = false) @Lazy
    private HierarchyIndexService hierarchyIndexService;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    /** Cloud fast-open: skip reasoner precompute; serve asserted hierarchy immediately after parse. */
    @Value("${ontocode.fastopen.skip-reasoner-precompute:true}")
    private boolean skipReasonerPrecompute;

    private final Set<String> loadingInProgress = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> warmWaiters = new ConcurrentHashMap<>();

    /**
     * Block until the OWLAPI model is cached (or timeout). Used by the desktop UI
     * so the first screen uses in-memory APIs like Protégé instead of slow SPARQL.
     */
    public Map<String, Object> warmProject(String projectId, long timeoutMs) {
        if (isHierarchyReady(projectId)) {
            return warmResponse(projectId, true, 0L);
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
            waiter.get(timeoutMs, TimeUnit.MILLISECONDS);
            boolean ready = isHierarchyReady(projectId);
            if (!ready && cache.has(projectId)) {
                return warmResponse(projectId, false, System.currentTimeMillis() - start);
            }
            return warmResponse(projectId, ready, System.currentTimeMillis() - start);
        } catch (TimeoutException e) {
            Map<String, Object> warm = warmResponse(projectId, isHierarchyReady(projectId), System.currentTimeMillis() - start);
            warm.put("pending", !isHierarchyReady(projectId));
            return warm;
        } catch (Exception e) {
            log.warn("[Desktop] warmProject failed for {}: {}", projectId, e.getMessage());
            Map<String, Object> warm = warmResponse(projectId, isHierarchyReady(projectId), System.currentTimeMillis() - start);
            warm.put("error", e.getMessage() != null ? e.getMessage() : "warm failed");
            return warm;
        } finally {
            warmWaiters.remove(projectId);
        }
    }

    private boolean isHierarchyReady(String projectId) {
        if (!cache.has(projectId)) {
            return false;
        }
        if (desktopHierarchyService == null) {
            return true;
        }
        return desktopHierarchyService.topLevelClassTotal(projectId) > 0;
    }

    private Map<String, Object> warmResponse(String projectId, boolean ready, long elapsedMs) {
        Map<String, Object> warm = new java.util.LinkedHashMap<>();
        warm.put("ready", ready);
        warm.put("owlapiReady", cache.has(projectId));
        warm.put("sparqlFallback", !ready);
        warm.put("elapsedMs", elapsedMs);
        if (cache.has(projectId)) {
            warm.putAll(declarationCountsFromCache(projectId));
        }
        if (desktopHierarchyService != null && cache.has(projectId)) {
            int topLevel = desktopHierarchyService.topLevelClassTotal(projectId);
            warm.put("topLevelClasses", topLevel);
            warm.put("hierarchyReady", topLevel > 0);
        }
        if (!ready && cache.has(projectId)) {
            warm.put("message", "OWL parsed — class tree still loading");
        }
        return warm;
    }

    public void triggerLazyLoadIfNeeded(String projectId) {
        if (cache.has(projectId) || loadingInProgress.contains(projectId)) {
            if (cache.has(projectId)) {
                completeWarmWaiters(projectId, true);
            }
            return;
        }
        findFastestParseSource(projectId).ifPresentOrElse(
            path -> startParallelWarm(projectId, path),
            () -> completeWarmWaiters(projectId, false)
        );
    }

    /** Start OWLAPI parse in parallel with Fuseki import (Protégé-style fast-open). */
    public void startParallelWarm(String projectId, Path owlFilePath) {
        if (cache.has(projectId)) {
            completeWarmWaiters(projectId, true);
            return;
        }
        if (!loadingInProgress.add(projectId)) {
            return;
        }
        loadAndCacheAsync(projectId, owlFilePath);
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
        if (!loadingInProgress.contains(projectId)) {
            loadingInProgress.add(projectId);
        }
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
            log.info("[Desktop] File {} MB (~{} MB model) exceeds heap budget ({} MB heap) — using Fuseki SPARQL/snapshot for project {}",
                fileSizeMb, estimatedModelMb, maxHeapMb, projectId);
            if (hierarchyIndexService != null) {
                hierarchyIndexService.scheduleBuild(projectId);
            }
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

            boolean assertedOnly = !desktopMode && skipReasonerPrecompute;
            OWLReasoner reasoner = null;
            if (!assertedOnly) {
                reasoner = new StructuralReasonerFactory().createNonBufferingReasoner(ontology);
                reasoner.precomputeInferences();
            }

            cache.put(projectId, ontology, reasoner, manager, assertedOnly);
            log.info("[FastOpen] OWLAPI model cached for project {} in {}ms (assertedOnly={})",
                projectId, System.currentTimeMillis() - start, assertedOnly);
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

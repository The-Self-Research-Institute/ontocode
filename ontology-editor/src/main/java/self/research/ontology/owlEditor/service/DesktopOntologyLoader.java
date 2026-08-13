package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.config.FastOpenCondition;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.repository.ProjectRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@Conditional(FastOpenCondition.class)
public class DesktopOntologyLoader {

    private static final Logger log = LoggerFactory.getLogger(DesktopOntologyLoader.class);

    @Autowired
    private ProjectOntologyCache cache;

    @Autowired
    private StorageManager storageManager;

    @Autowired
    private ProjectMetadataService metadataService;

    @Autowired(required = false)
    private DesktopHierarchyService desktopHierarchyService;

    @Autowired(required = false) @Lazy
    private HierarchyIndexService hierarchyIndexService;

    @Autowired(required = false) @Lazy
    private SparqlDatasetService datasetService;

    @Autowired(required = false)
    private GridFSFileService gridFSFileService;

    @Autowired(required = false)
    private ProjectRepository projectRepository;

    @Autowired(required = false)
    private DesktopOpenMetricsService openMetricsService;

    @Autowired
    @Qualifier("desktopModelExecutor")
    private Executor backgroundWarmExecutor;

    @Autowired
    @Qualifier("desktopInteractiveWarmExecutor")
    private Executor interactiveWarmExecutor;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    @Value("${ontocode.fastopen.skip-reasoner-precompute:true}")
    private boolean skipReasonerPrecompute;

    @Value("${ontocode.fastopen.auto-warm:false}")
    private boolean autoWarm;

    @Value("${ontocode.desktop.owlapi-first:false}")
    private boolean owlApiFirst;

    public boolean isAutoWarmEnabled() {
        return autoWarm;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void preWarmRecentProjectsAsync() {
        if (!autoWarm || projectRepository == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                List<ProjectDocument> recent = projectRepository.findByStatusIn(List.of("COMPLETED"));
                recent.stream()
                    .filter(p -> p.getMetadata() != null)
                    .sorted((a, b) -> {
                        java.time.Instant ta = a.getUpdatedAt() != null ? a.getUpdatedAt() : java.time.Instant.EPOCH;
                        java.time.Instant tb = b.getUpdatedAt() != null ? b.getUpdatedAt() : java.time.Instant.EPOCH;
                        return tb.compareTo(ta);
                    })
                    .limit(cache.getMaxProjects())
                    .forEach(p -> {
                        try {
                            triggerLazyLoadIfNeeded(p.getId());
                        } catch (Exception e) {
                            log.debug("[Desktop] Pre-warm skipped for {}: {}", p.getId(), e.getMessage());
                        }
                    });
            } catch (Exception e) {
                log.warn("[Desktop] Startup pre-warm failed (non-fatal): {}", e.getMessage());
            }
        });
    }

    private final Set<String> loadingInProgress = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> warmWaiters = new ConcurrentHashMap<>();

    public Map<String, Object> warmProject(String projectId, long timeoutMs) {
        if (isHierarchyReady(projectId)) {
            return warmResponse(projectId, true, 0L);
        }

        long start = System.currentTimeMillis();
        CompletableFuture<Boolean> waiter = warmWaiters.computeIfAbsent(projectId, id -> new CompletableFuture<>());
        triggerLazyLoadIfNeeded(projectId, true);
        awaitLoadStart(projectId, 2_500);

        if (!loadingInProgress.contains(projectId) && !cache.has(projectId)) {
            materializeOntologyOnDiskIfMissing(projectId);
            triggerLazyLoadIfNeeded(projectId, true);
            awaitLoadStart(projectId, 2_500);
        }

        if (!loadingInProgress.contains(projectId) && !cache.has(projectId)) {
            if (desktopMode && owlApiFirst) {
                return Map.of(
                    "ready", false,
                    "sparqlFallback", false,
                    "pending", true,
                    "owlapiReady", false,
                    "elapsedMs", System.currentTimeMillis() - start,
                    "message", "OWLAPI warm pending — materializing ontology for fast open"
                );
            }
            completeWarmWaiters(projectId, false);
            return Map.of(
                "ready", false,
                "sparqlFallback", true,
                "pending", false,
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
            if (!isHierarchyReady(projectId)) {
                warm.put("pending", true);
                warm.put("sparqlFallback", false);
                warm.put("message", "OWLAPI warm still in progress");
            }
            return warm;
        } catch (Exception e) {
            log.warn("[Desktop] warmProject failed for {}: {}", projectId, e.getMessage());
            Map<String, Object> warm = warmResponse(projectId, isHierarchyReady(projectId), System.currentTimeMillis() - start);
            warm.put("error", e.getMessage() != null ? e.getMessage() : "warm failed");
            if (!isHierarchyReady(projectId) && loadingInProgress.contains(projectId)) {
                warm.put("pending", true);
                warm.put("sparqlFallback", false);
            }
            return warm;
        } finally {
            warmWaiters.remove(projectId);
        }
    }

    private boolean isHierarchyReady(String projectId) {

        return cache.has(projectId);
    }

    private void awaitLoadStart(String projectId, long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (loadingInProgress.contains(projectId) || cache.has(projectId)) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Map<String, Object> warmResponse(String projectId, boolean ready, long elapsedMs) {
        Map<String, Object> warm = new java.util.LinkedHashMap<>();
        warm.put("ready", ready);
        warm.put("owlapiReady", cache.has(projectId));

        boolean pending = !ready && (loadingInProgress.contains(projectId) || cache.has(projectId));
        warm.put("pending", pending);
        warm.put("sparqlFallback", !desktopMode || !owlApiFirst
                ? (!ready && !pending && !loadingInProgress.contains(projectId) && !cache.has(projectId))
                : false);
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

    public boolean isLoading(String projectId) {
        return loadingInProgress.contains(projectId);
    }

    public void triggerLazyLoadIfNeeded(String projectId) {
        triggerLazyLoadIfNeeded(projectId, false);
    }

    public void triggerLazyLoadIfNeeded(String projectId, boolean interactive) {
        if (!autoWarm) {
            return;
        }
        if (cache.has(projectId) || loadingInProgress.contains(projectId)) {
            if (cache.has(projectId)) {
                completeWarmWaiters(projectId, true);
            }
            return;
        }
        materializeOntologyOnDiskIfMissing(projectId);
        findFastestParseSource(projectId).ifPresentOrElse(
            path -> startParallelWarm(projectId, path, interactive),
            () -> completeWarmWaiters(projectId, false)
        );
    }

    private void materializeOntologyOnDiskIfMissing(String projectId) {
        if (storageManager.findCurrentOntology(projectId).isPresent()) {
            return;
        }
        if (gridFSFileService == null || projectRepository == null) {
            return;
        }
        try {
            var docOpt = projectRepository.findById(projectId);
            if (docOpt.isEmpty()) {
                return;
            }
            ProjectDocument doc = docOpt.get();
            String gridfsId = doc.getGridfsFileId();
            if (gridfsId == null || gridfsId.isBlank()) {
                return;
            }
            var resourceOpt = gridFSFileService.getFileById(gridfsId);
            if (resourceOpt.isEmpty()) {
                log.warn("[Desktop] GridFS content missing for {} (gridfsId={})", projectId, gridfsId);
                return;
            }
            Path projectDir = storageManager.prepareProjectDir(projectId);
            Path original = projectDir.resolve("ontology.original.owl");
            Path current = projectDir.resolve("ontology.current.owl");
            try (var in = resourceOpt.get().getInputStream()) {
                Files.copy(in, original, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(original, current, StandardCopyOption.REPLACE_EXISTING);
            log.info("[Desktop] Materialized ontology on disk from GridFS for OWLAPI warm: {}", projectId);
        } catch (Exception e) {
            log.warn("[Desktop] Could not materialize ontology on disk for {}: {}", projectId, e.getMessage());
        }
    }

    public void scheduleRewarm(String projectId) {
        if (!autoWarm) {
            return;
        }
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        if (cache.has(projectId) || loadingInProgress.contains(projectId)) {
            return;
        }
        log.info("[FastOpen] Scheduling async OWLAPI re-warm for project {}", projectId);
        triggerLazyLoadIfNeeded(projectId);
    }

    public void evictCache(String projectId) {
        cache.evict(projectId);
    }

    public long classCount(String projectId) {
        return cache.get(projectId)
                .map(cached -> cached.ontology().classesInSignature().count())
                .orElse(0L);
    }

    public void startParallelWarm(String projectId, Path owlFilePath) {
        startParallelWarm(projectId, owlFilePath, false);
    }

    public void startParallelWarm(String projectId, Path owlFilePath, boolean interactive) {
        if (!autoWarm) {
            return;
        }
        if (cache.has(projectId)) {
            completeWarmWaiters(projectId, true);
            return;
        }
        if (!loadingInProgress.add(projectId)) {
            return;
        }

        Executor lane = interactive ? interactiveWarmExecutor : backgroundWarmExecutor;
        try {
            lane.execute(() -> runLoadAndComplete(projectId, owlFilePath));
        } catch (Exception e) {
            loadingInProgress.remove(projectId);
            completeWarmWaiters(projectId, false);
            log.warn("[Desktop] Could not schedule OWLAPI warm for {}: {}", projectId, e.getMessage());
        }
    }

    private Optional<Path> findFastestParseSource(String projectId) {
        Path dir = storageManager.projectDir(projectId);

        Path draft = storageManager.draftOntologyPath(projectId);
        if (java.nio.file.Files.exists(draft)) {
            log.info("[Desktop] Unsaved draft found for {} — recovering from {}", projectId, draft);
            return Optional.of(draft);
        }

        Path dirtyMarker = dir.resolve("ontology.dirty");
        if (java.nio.file.Files.exists(dirtyMarker)) {
            try {
                Path fresh = storageManager.exportOntology(projectId, "rdfxml");
                for (String stale : List.of("ontology.original.ofn", "ontology.original.ttl",
                        "ontology.original.nt", "ontology.current.ttl", "ontology.current.owl")) {
                    Path p = dir.resolve(stale);
                    if (!p.equals(fresh)) {
                        java.nio.file.Files.deleteIfExists(p);
                    }
                }
                java.nio.file.Files.deleteIfExists(dirtyMarker);
                log.info("[Desktop] Project {} had post-import mutations — parsed source re-exported from Fuseki: {}",
                        projectId, fresh);
                return Optional.of(fresh);
            } catch (Exception e) {
                log.warn("[Desktop] Fresh Fuseki export failed for dirty project {} — trying on-disk copy: {}",
                        projectId, e.getMessage());
                try {
                    java.nio.file.Files.deleteIfExists(dirtyMarker);
                } catch (java.io.IOException ignored) {
                }
            }
        }

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
        Optional<Path> current = storageManager.findCurrentOntology(projectId);
        if (current.isPresent()) {
            return current;
        }
        if (datasetService != null && datasetService.hasGraphData(projectId)) {
            try {
                Path exported = storageManager.exportOntology(projectId, "rdfxml");
                log.info("[Desktop] Exported ontology from Fuseki for OWLAPI warm: {}", exported);
                return Optional.of(exported);
            } catch (Exception e) {
                log.warn("[Desktop] Fuseki export for OWLAPI warm failed for {}: {}", projectId, e.getMessage());
            }
        }
        return Optional.empty();
    }

    @Async("desktopModelExecutor")
    public void loadAndCacheAsync(String projectId, Path owlFilePath) {
        runLoadAndComplete(projectId, owlFilePath);
    }

    private void runLoadAndComplete(String projectId, Path owlFilePath) {
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

        long usedNowMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long heapReserveMb = Math.max(768, usedNowMb + 256);
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
            OWLOntologyManager manager = OWLManager.createConcurrentOWLOntologyManager();
            manager.setOntologyLoaderConfiguration(
                new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                    .setLoadAnnotationAxioms(true)
            );

            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(owlFilePath.toFile());
            long parseMs = System.currentTimeMillis() - start;
            long classCount = ontology.classesInSignature().count();
            log.info("[Desktop] OWLAPI parsed {} classes in {}ms", classCount, parseMs);

            if (openMetricsService != null) {
                openMetricsService.recordOwlApiLoad(
                        projectId,
                        owlFilePath.getFileName().toString(),
                        owlFilePath.toFile().length(),
                        parseMs,
                        owlFilePath.toString());
            }

            boolean assertedOnly = skipReasonerPrecompute;
            OWLReasoner reasoner = null;
            if (!assertedOnly) {
                reasoner = new StructuralReasonerFactory().createNonBufferingReasoner(ontology);
                reasoner.precomputeInferences();
            }

            cache.put(projectId, ontology, reasoner, manager, assertedOnly);
            cache.setCachedVersion(projectId, metadataService.getMutationVersion(projectId));
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

    public void persistToDisk(String projectId) throws java.io.IOException {
        Path target = storageManager.draftOntologyPath(projectId);
        writeModelTo(projectId, target);
        log.info("[Desktop] Autosaved OWLAPI model to draft {}", target);
    }

    public boolean saveProject(String projectId) throws java.io.IOException {
        if (storageManager.promoteDraft(projectId)) {
            return true;
        }
        if (cache.has(projectId)) {
            Path target = storageManager.findCurrentOntology(projectId)
                    .orElse(storageManager.resolveProjectFile(projectId, "ontology.current.owl"));
            writeModelTo(projectId, target);
            log.info("[Desktop] Saved OWLAPI model to {} (no draft present)", target);
            return true;
        }
        return false;
    }

    public void discardDraft(String projectId) throws java.io.IOException {
        storageManager.deleteDraft(projectId);
        cache.evict(projectId);
        triggerLazyLoadIfNeeded(projectId, true);
    }

    public boolean hasDraft(String projectId) {
        return storageManager.hasDraft(projectId);
    }

    private void writeModelTo(String projectId, Path target) throws java.io.IOException {
        var cached = cache.get(projectId);
        if (cached.isEmpty()) {
            throw new java.io.IOException("No OWLAPI model in memory for project: " + projectId);
        }
        Files.createDirectories(target.getParent());
        var ontology = cached.get().ontology();
        var manager = cached.get().manager();

        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (var out = Files.newOutputStream(tmp)) {
            manager.saveOntology(ontology, new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat(), out);
        } catch (org.semanticweb.owlapi.model.OWLOntologyStorageException e) {
            Files.deleteIfExists(tmp);
            throw new java.io.IOException("Failed to save ontology: " + e.getMessage(), e);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void completeWarmWaiters(String projectId, boolean success) {
        CompletableFuture<Boolean> waiter = warmWaiters.remove(projectId);
        if (waiter != null && !waiter.isDone()) {
            waiter.complete(success || cache.has(projectId));
        }
    }
}

package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.ImportOptions;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.service.TopLevelClassCacheService;
import self.research.ontology.owlEditor.model.ImportQueueItem;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.model.collaboration.ImportStatusMessage;
import self.research.ontology.owlEditor.model.collaboration.QueueStatusMessage;
import self.research.ontology.owlEditor.util.OWLFormatConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Background job runner that streams uploaded OWL files into TDB2 and refreshes metadata.
 */
@Service
public class ProjectImportService {

    private static final Logger log = LoggerFactory.getLogger(ProjectImportService.class);
    private static final Logger importLog = LoggerFactory.getLogger("IMPORT");
    private static final Logger perfLog = LoggerFactory.getLogger("PERFORMANCE");
    private static final AtomicLong IMPORT_RUN_SEQUENCE = new AtomicLong(1);

    // Prevent concurrent imports for the same project (which cause overlapping progress threads and GraphDB clears)
    private final Map<String, AtomicBoolean> importInProgress = new ConcurrentHashMap<>();
    private final Set<String> importReservations = ConcurrentHashMap.newKeySet();

    private final Executor owlParsingExecutor;
    private final Executor metadataExecutor;
    private final SparqlDatasetService datasetService;
    private final OntologyIndexService indexService;
    private final ProjectMetadataService metadataService;
    private final StorageManager storageManager;
    private final SimpMessagingTemplate messagingTemplate;
    private final ImportQueueManager queueManager;
    private final ImportTimeEstimator timeEstimator;

    // Desktop-only — null in cloud deployments (optional injection)
    @Autowired(required = false) @Nullable
    private DesktopOntologyLoader desktopOntologyLoader;

    @Autowired(required = false) @Nullable
    private DesktopOpenMetricsService openMetricsService;

    // Evict stale top-level and children caches after import so next open gets fresh SPARQL results
    @Autowired(required = false) @Nullable
    private TopLevelClassCacheService topLevelClassCacheService;

    @Autowired(required = false) @Nullable
    private org.springframework.cache.CacheManager cacheManager;

    @Autowired(required = false) @Nullable
    private OntologySpringCacheEvictionService cacheEvictionService;

    @Autowired(required = false) @Nullable
    private HierarchyIndexService hierarchyIndexService;

    @Autowired(required = false) @Nullable
    private EntityUsageIndexService entityUsageIndexService;

    @Autowired(required = false) @Nullable
    private ClassDetailCacheService classDetailCacheService;

    @Autowired(required = false) @Nullable
    private ProjectRepository projectRepository;

    @Autowired(required = false) @Nullable
    private DesktopFusekiSyncScheduler fusekiSyncScheduler;

    @Value("${ontocode.desktop.owlapi-first:false}")
    private boolean owlApiFirst;

    @Value("${ontocode.import.stuck-timeout-minutes:10}")
    private long stuckImportTimeoutMinutes;

    private final ScheduledExecutorService watchdogScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "import-watchdog");
                t.setDaemon(true);
                return t;
            });

    public ProjectImportService(@Qualifier("owlParsingExecutor") Executor owlParsingExecutor,
                                @Qualifier("metadataExecutor") Executor metadataExecutor,
                                SparqlDatasetService datasetService,
                                OntologyIndexService indexService,
                                ProjectMetadataService metadataService,
                                StorageManager storageManager,
                                SimpMessagingTemplate messagingTemplate,
                                ImportQueueManager queueManager,
                                ImportTimeEstimator timeEstimator) {
        this.owlParsingExecutor = owlParsingExecutor;
        this.metadataExecutor = metadataExecutor;
        this.datasetService = datasetService;
        this.indexService = indexService;
        this.metadataService = metadataService;
        this.storageManager = storageManager;
        this.messagingTemplate = messagingTemplate;
        this.queueManager = queueManager;
        this.timeEstimator = timeEstimator;
    }

    @PostConstruct
    public void startWatchdog() {
        log.info("[Import] Watchdog started (interval=30s, initialDelay=60s, timeoutMinutes={})",
                Math.max(1, stuckImportTimeoutMinutes));
        watchdogScheduler.scheduleWithFixedDelay(() -> {
            try {
                long timeoutMs = Math.max(1, stuckImportTimeoutMinutes) * 60_000L;
                QueueStatusMessage.QueueStats beforeStats = queueManager.getQueueStats();
                if (beforeStats.getActiveImports() > 0 || beforeStats.getQueuedImports() > 0) {
                    log.info("[Import] Watchdog tick (active={}, queued={}, activeProjectIds={})",
                            beforeStats.getActiveImports(),
                            beforeStats.getQueuedImports(),
                            beforeStats.getActiveProjectIds());
                }
                List<ImportQueueItem> expired = queueManager.expireStuckProcessing(timeoutMs);
                if (expired.isEmpty()) {
                    return;
                }

                log.warn("[Import] Watchdog expired {} import(s): {}",
                        expired.size(), expired.stream().map(ImportQueueItem::getProjectId).toList());

                for (ImportQueueItem item : expired) {
                    String projectId = item.getProjectId();
                    String filename = metadataService.readStatus(projectId)
                            .map(ProjectStatus::filename)
                            .filter(f -> f != null && !f.isBlank())
                            .orElse(item.getFilename());
                    String reason = item.getFailureReason() != null
                            ? item.getFailureReason()
                            : "Import timed out while processing";

                    metadataService.writeStatus(projectId, ProjectStatus.error(filename, reason));
                    sendImportNotification(projectId,
                            ImportStatusMessage.ImportStatusType.IMPORT_FAILED,
                            "ERROR",
                            "Import failed: " + reason,
                            filename,
                            Map.of("error", reason, "stage", "timeout-watchdog"));

                    importReservations.remove(projectId);
                    releaseImport(projectId);
                }

                processNextInQueue();
            } catch (Exception e) {
                log.warn("[Import] Watchdog scan failed", e);
            }
        }, 60, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stopWatchdog() {
        watchdogScheduler.shutdownNow();
    }

    /**
     * On startup, find any projects stuck in PROCESSING or INDEXING from a previous server run
     * and mark them as ERROR. The in-memory queue is lost on restart so these will never
     * complete; showing an error lets users re-import rather than waiting forever.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStuckImports() {
        if (projectRepository == null) {
            return;
        }
        try {
            var stuck = projectRepository.findByStatusIn(List.of("PROCESSING", "INDEXING"));
            if (stuck.isEmpty()) {
                return;
            }
            log.warn("[Import] Found {} project(s) stuck in PROCESSING/INDEXING from a previous run — resetting to ERROR",
                    stuck.size());
            for (var doc : stuck) {
                try {
                    metadataService.writeStatus(doc.getId(),
                            ProjectStatus.error(doc.getFilename(),
                                    "Import was interrupted (server restarted). Please re-import the file."));
                    log.info("[Import] Reset stuck import for project {}: {}", doc.getId(), doc.getFilename());
                } catch (Exception e) {
                    log.warn("[Import] Failed to reset stuck project {}: {}", doc.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[Import] Startup stuck-import recovery failed: {}", e.getMessage());
        }
    }

    public void submitImport(String projectId, Path owlFile) {
        submitImport(projectId, owlFile, null, ImportOptions.defaults());
    }

    public void submitImport(String projectId, Path owlFile, String ownerEmail) {
        submitImport(projectId, owlFile, ownerEmail, ImportOptions.defaults());
    }

    public void submitImport(String projectId, Path owlFile, String ownerEmail, ImportOptions options) {
        String filename = owlFile.getFileName().toString();

        importLog.info("[SUBMIT] project={} file={} size={}", projectId, filename, owlFile.toFile().length());
        log.info("[Import] Submitting import for project {}: {}", projectId, filename);

        if (!reserveImport(projectId)) {
            log.info("[Import] Import already active or queued for project {}, ignoring duplicate submit", projectId);
            return;
        }

        boolean submitted = false;
        try {

        // Write PROCESSING status synchronously BEFORE enqueueing so that any
        // status poll from the frontend sees PROCESSING instead of the stale
        // COMPLETED from the previous import.  This closes the race window
        // where the frontend polls, sees old COMPLETED, and fetches stale data
        // — especially noticeable with large ontologies (90k+ classes).
        //
        // Preserve the user-facing filename (e.g. "ontology-1235.owl") from the
        // existing status record rather than using the physical file name
        // (e.g. "ontology.current.owl") which is an internal storage detail.
        String displayFilename = metadataService.readStatus(projectId)
                .map(ProjectStatus::filename)
                .filter(f -> f != null && !f.isBlank())
                .orElse(filename);
        metadataService.writeStatus(projectId, ProjectStatus.processing(displayFilename));

        // Evict any cached binding decision so the next resolveBinding() re-evaluates whether
        // to use the shared vs. dedicated dataset (the new import will write to dedicated).
        datasetService.evictPerFileDataset(projectId);

        // Fast path for small files: skip queue overhead when no concurrent imports running
        long fileSizeBytes;
        try {
            fileSizeBytes = java.nio.file.Files.size(owlFile);
        } catch (Exception e) {
            fileSizeBytes = -1;
        }
        boolean isSmallFile = fileSizeBytes >= 0 && fileSizeBytes < 100 * 1024; // < 100KB
        
        if (isSmallFile && queueManager.canProcess() && queueManager.isEmpty()) {
            log.info("[Import] Fast path: small file ({} bytes), enqueue + immediate process", fileSizeBytes);
            queueManager.enqueue(projectId, filename, ownerEmail, owlFile, options);
            processNextInQueue();
            submitted = true;
            return;
        }

        // Add to queue
        queueManager.enqueue(projectId, filename, ownerEmail, owlFile, options);

        // Try to process next item in queue
        processNextInQueue();
        submitted = true;
        } finally {
            if (!submitted) {
                importReservations.remove(projectId);
                releaseImport(projectId);
            }
        }
    }

    public boolean isImportActiveOrQueued(String projectId) {
        AtomicBoolean guard = importInProgress.get(projectId);
        if (guard != null && guard.get()) {
            return true;
        }
        return queueManager.getStatus(projectId) != null;
    }

    private boolean reserveImport(String projectId) {
        AtomicBoolean guard = importInProgress.computeIfAbsent(projectId, id -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            return false;
        }
        importReservations.add(projectId);
        return true;
    }

    private void releaseImport(String projectId) {
        AtomicBoolean guard = importInProgress.get(projectId);
        if (guard != null) {
            guard.set(false);
        }
    }

    private void processNextInQueue() {
        QueueStatusMessage.QueueStats stats = queueManager.getQueueStats();
        if (!queueManager.canProcess()) {
            log.info("[Import] Cannot process - max concurrent imports reached (active={}, queued={}, activeProjectIds={})",
                    stats.getActiveImports(), stats.getQueuedImports(), stats.getActiveProjectIds());
            return;
        }

        owlParsingExecutor.execute(() -> {
            ImportQueueItem item = queueManager.dequeue();
            if (item == null) {
                QueueStatusMessage.QueueStats afterDequeueStats = queueManager.getQueueStats();
                log.debug("[Import] Dequeue returned null (active={}, queued={}, activeProjectIds={})",
                        afterDequeueStats.getActiveImports(),
                        afterDequeueStats.getQueuedImports(),
                        afterDequeueStats.getActiveProjectIds());
                return; // No items in queue
            }

            long runId = IMPORT_RUN_SEQUENCE.getAndIncrement();
            long startTime = System.currentTimeMillis();
            log.info("[Import {}|run:{}] Queue worker picked item on thread {}",
                    item.getProjectId(), runId, Thread.currentThread().getName());
            try {
                runImport(item, runId);
                long duration = System.currentTimeMillis() - startTime;
                queueManager.markCompleted(item.getProjectId(), duration);
            } catch (Exception e) {
                log.error("[Import {}|run:{}] Failed to process queue item", item.getProjectId(), runId, e);

                // Check if error is retryable (connection issues, timeouts, etc.)
                boolean shouldRetry = isRetryableError(e);
                String errorReason = extractErrorReason(e);

                queueManager.markFailed(item.getProjectId(), errorReason, shouldRetry);
            } catch (Throwable t) {
                // StackOverflowError, OutOfMemoryError etc. are Errors not Exceptions —
                // must catch Throwable or the item is stuck in PROCESSING forever.
                log.error("[Import {}|run:{}] Fatal JVM error", item.getProjectId(), runId, t);
                queueManager.markFailed(item.getProjectId(),
                        t.getClass().getSimpleName() + " during OWL parsing — file may be too large or deeply nested",
                        false);
            } finally {
                long duration = System.currentTimeMillis() - startTime;
                log.info("[Import {}|run:{}] Queue worker finished in {} ms. Triggering next dequeue.",
                        item.getProjectId(), runId, duration);
                // Try to process next item in queue
                processNextInQueue();
            }
        });
    }

    /**
     * Determine if an error is retryable (connection issues, timeouts)
     */
    private boolean isRetryableError(Exception e) {
        String message = e.getMessage();
        String causeMessage = e.getCause() != null ? e.getCause().getMessage() : "";
        
        // Check for connection-related errors
        String combined = (message != null ? message : "") + " " + causeMessage;
        String lower = combined.toLowerCase(Locale.ROOT);
        return lower.contains("socketexception") ||
                lower.contains("connection aborted") ||
                lower.contains("connection reset") ||
                lower.contains("connection timeout") ||
                lower.contains("timed out") ||
                lower.contains("no bytes") ||
                lower.contains("gsp post failed") ||
                lower.contains("nonrepeatablerequestexception");
    }

    /**
     * Extract a user-friendly error reason
     */
    private String extractErrorReason(Exception e) {
        if (isRetryableError(e)) {
            return "Connection lost during import. Large files may need more server memory.";
        }
        
        String message = e.getMessage();
        if (message != null) {
            if (message.contains("RDFParseException")) {
                return "Invalid RDF format in ontology file";
            }
            if (message.contains("OutOfMemoryError")) {
                return "Out of memory - file too large";
            }
        }
        
        return message != null ? message : "Unknown error";
    }

    private void runImport(ImportQueueItem item, long runId) {
        String projectId = item.getProjectId();
        Path owlFile = item.getOwlFile();
        AtomicBoolean guard = importInProgress.computeIfAbsent(projectId, id -> new AtomicBoolean(false));
        boolean reserved = importReservations.remove(projectId);
        if (!reserved && !guard.compareAndSet(false, true)) {
            log.warn("[Import {}] Import already running, rejecting duplicate request", projectId);
            Map<String, Object> errorMeta = new HashMap<>();
            errorMeta.put("error", "Import already in progress for this project");
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_FAILED,
                    "ERROR", "Import already in progress, please wait", owlFile.getFileName().toString(), errorMeta);
            return;
        }

        long importStart = System.nanoTime();
        importLog.info("[START] project={} runId={} file={}", projectId, runId, owlFile.getFileName());
        String stage = "initialization";
        String filename = metadataService.readStatus(projectId)
                .map(ProjectStatus::filename)
                .orElse(owlFile.getFileName().toString());

        // Cloud / legacy: warm OWLAPI in parallel with Fuseki. OWLAPI-first desktop handles warm separately.
        if (desktopOntologyLoader != null && !owlApiFirst) {
            desktopOntologyLoader.loadAndCacheAsync(projectId, owlFile);
        }

        // Track whether import was marked as COMPLETED (prevents overwriting to ERROR in catch block)
        AtomicBoolean importMarkedCompleted = new AtomicBoolean(false);

        log.info("[Import {}|run:{}] Starting import for file {}", projectId, runId, filename);

        // Notify: Import started
        sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_STARTED,
                "PROCESSING", "Import started", filename, null);

        metadataService.writeStatus(projectId, ProjectStatus.processing(filename));
        try {
            stage = "detect-format";
            long stageStart = System.nanoTime();
            RDFFormat format = detectFormat(owlFile);
            log.info("[Import {}] [TIMING] Format detection: {} ms (detected: {})", 
                    projectId, elapsedMillis(stageStart), format.getName());
            
            // Sanitize the file to fix malformed XML before import
            // NOTE: For large files (>50MB), sanitizeFileOnDisk uses an optimized path
            // that skips OWL API re-serialization to avoid loading the entire file into memory.
            stage = "sanitize";
            stageStart = System.nanoTime();
            long fileSizeForSanitize = Files.size(owlFile);
            if (fileSizeForSanitize > 50 * 1024 * 1024) {
                log.info("[Import {}] [PERFORMANCE] Large file ({} MB) - sanitization will use optimized path",
                        projectId, fileSizeForSanitize / (1024 * 1024));
            }
            try {
                OWLFormatConverter.sanitizeFileOnDisk(owlFile);
                log.info("[Import {}] [TIMING] Sanitization: {} ms", projectId, elapsedMillis(stageStart));
            } catch (Exception sanitizeEx) {
                log.warn("[Import {}] File sanitization failed: {}", projectId, sanitizeEx.getMessage());
                // Continue anyway - sanitization is best-effort
            }

            // Protégé-style desktop: parse OWLAPI from disk, mark ready immediately, sync Fuseki later.
            if (owlApiFirst && desktopOntologyLoader != null) {
                if (completeOwlApiFirstImport(projectId, owlFile, filename, format, importStart, importMarkedCompleted)) {
                    return;
                }
                throw new RuntimeException("OWLAPI failed to load ontology — file may be too large or invalid");
            }

            stage = "bulk-load";
            log.info("[Import {}] Loading data into GraphDB", projectId);
            long fileSizeBytes = Files.size(owlFile);
            log.info("File size: {} bytes ({} MB)", fileSizeBytes, fileSizeBytes / (1024 * 1024));

            AtomicLong lastProgressSentAt = new AtomicLong(System.nanoTime());
            AtomicInteger lastProgressPercent = new AtomicInteger(0);

            long bulkLoadStart = System.nanoTime();
            
            // Notify user that we're starting GraphDB bulk load (this may take time for large files)
            Map<String, Object> bulkLoadStartMeta = new HashMap<>();
            bulkLoadStartMeta.put("progress", 60);
            bulkLoadStartMeta.put("stage", "graphdb-loading");
            bulkLoadStartMeta.put("message", "Loading ontology data (large files may take several minutes)…");
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                    "PROCESSING", "Loading ontology data…", filename, bulkLoadStartMeta);
            // Also update status.json so polling clients get the progress message
            metadataService.writeStatus(projectId, ProjectStatus.processing(filename, "Loading ontology data…"));
            metadataService.writeImportProgress(projectId, 60, "graphdb-loading", "Loading ontology data…");

            // OWLAPI warm competes with Fuseki ingest for heap on large files — defer until after load.
            if (desktopOntologyLoader != null && fileSizeBytes < 50L * 1024 * 1024) {
                desktopOntologyLoader.startParallelWarm(projectId, owlFile);
            } else if (desktopOntologyLoader != null) {
                log.info("[Import {}] Deferring OWLAPI warm until after Fuseki load ({} MB) — protects editor heap",
                        projectId, fileSizeBytes / (1024 * 1024));
            }
            
            ImportOptions options = ImportOptions.builder()
                    .mode(item.getImportMode() != null ? item.getImportMode() : ImportOptions.ImportMode.FULL)
                    .partitionStrategy(item.getPartitionStrategy() != null ? item.getPartitionStrategy() : ImportOptions.PartitionStrategy.NONE)
                    .build();

            // Check if file needs conversion (e.g., OWL/XML -> RDF/XML)
            Path fileToLoad = owlFile;
            long actualFileSize = fileSizeBytes;
            boolean converted = false;

            if (isOwlXmlFormat(owlFile)) {
                log.info("[Import {}] Detected OWL/XML format, converting to RDF/XML", projectId);

                Map<String, Object> conversionMeta = new HashMap<>();
                conversionMeta.put("progress", 15);
                conversionMeta.put("stage", "format-conversion");
                sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                        "PROCESSING", "Converting OWL/XML to RDF/XML...", filename, conversionMeta);

                try {
                    long conversionStart = System.nanoTime();
                    fileToLoad = OWLFormatConverter.convertToRDFXML(owlFile);
                    converted = true;
                    format = RDFFormat.RDFXML;
                    actualFileSize = Files.size(fileToLoad);
                    long conversionDuration = elapsedMillis(conversionStart);
                    log.info("[Import {}] Format conversion completed in {} ms. New file: {} ({} bytes)",
                            projectId, conversionDuration, fileToLoad.getFileName(), actualFileSize);

                    // Log first 500 chars of converted file to verify format
                    try {
                        String preview = Files.readString(fileToLoad, StandardCharsets.UTF_8);
                        log.info("[Import {}] Converted file preview (first 500 chars):\n{}",
                                projectId, preview.substring(0, Math.min(500, preview.length())));
                    } catch (Exception previewEx) {
                        log.warn("[Import {}] Could not preview converted file: {}", projectId, previewEx.getMessage());
                    }
                } catch (Exception e) {
                    log.error("[Import {}] Format conversion failed", projectId, e);
                    throw new RuntimeException("Failed to convert OWL format: " + e.getMessage(), e);
                }
            } else {
                log.info("[Import {}] File format is compatible, no conversion needed", projectId);
            }

            log.info("[Import {}] Starting import: file={}, format={}, size={} bytes, converted={}",
                    projectId, fileToLoad.getFileName(), format, actualFileSize, converted);

            // Large files (≥50 MB): prefer single-shot GSP PUT — chunked GSP POST batches time out on Fuseki.
            boolean serverImportDone = false;
            boolean largeFile = actualFileSize >= 50L * 1024 * 1024;

            SparqlDatasetService.ProgressListener importProgressListener = progress -> {
                long totalBytes = progress.getTotalBytes();
                long bytesRead = progress.getBytesRead();
                long elapsedMs = progress.getElapsedMs();
                int percent;
                String message;
                if (totalBytes > 0 && bytesRead > 0) {
                    percent = (int) Math.min(99, Math.floor((bytesRead * 100.0) / totalBytes));
                    message = String.format("Importing... (%d%%)", percent);
                } else if (elapsedMs > 0) {
                    long elapsedSec = elapsedMs / 1000;
                    // Byte-level progress isn't available for a direct GSP PUT upload (the JDK
                    // HTTP client's file body publisher has no progress hook — see directHttpUpload's
                    // 5s heartbeat in SparqlDatasetService). The old formula only advanced 1% every
                    // 30s, so between heartbeat ticks the number sat frozen for ~25s stretches even
                    // while the upload was actively running — users read that as "stuck". Advance it
                    // every heartbeat tick instead and be explicit this is a time estimate, not a byte count.
                    percent = (int) Math.min(90, 10 + (elapsedSec / 5));
                    message = String.format("Uploading large file… still in progress (%dm %02ds elapsed) — this can take several minutes",
                            elapsedSec / 60, elapsedSec % 60);
                } else {
                    percent = 5;
                    message = "Loading ontology data…";
                }
                lastProgressPercent.set(percent);
                sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                        "PROCESSING", message, filename, Map.of("progress", percent, "stage", "graphdb-loading", "message", message));
                try {
                    metadataService.writeImportProgress(projectId, percent, "graphdb-loading", message);
                } catch (Exception mongoEx) {
                    log.warn("[Import {}] Progress write to MongoDB failed (non-fatal, Fuseki upload continues): {}", projectId, mongoEx.getMessage());
                }
            };

            if (largeFile) {
                try {
                    stageStart = System.nanoTime();
                    log.info("[Import {}] Large file ({} MB) — trying direct GSP PUT first", projectId, actualFileSize / (1024 * 1024));
                    serverImportDone = datasetService.directHttpUpload(projectId, fileToLoad, format, actualFileSize, options, importProgressListener);
                } catch (Exception directEx) {
                    log.info("[Import {}] [TIMING] Direct HTTP upload failed after {} ms: {}", projectId, elapsedMillis(stageStart), directEx.getMessage());
                }
            }

            // ⚡ FAST PATH: GraphDB server-side import (no-op on Fuseki; kept for GraphDB deployments)
            if (!serverImportDone && !largeFile) {
                try {
                    stageStart = System.nanoTime();
                    serverImportDone = datasetService.serverSideImport(projectId, fileToLoad, format, actualFileSize, options, progress -> {
                        long totalBytes = progress.getTotalBytes();
                        long bytesRead = progress.getBytesRead();
                        long elapsedMs = progress.getElapsedMs();
                        if (totalBytes <= 0) return;
                        int percent = (int) Math.min(99, Math.floor((bytesRead * 100.0) / totalBytes));
                        lastProgressPercent.set(percent);
                        String message = String.format("Importing... (%d%%)", percent);
                        sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                                "PROCESSING", message, filename, Map.of("progress", percent, "stage", "graphdb-loading", "message", message));
                        try {
                            metadataService.writeStatus(projectId, ProjectStatus.processing(filename, message));
                        } catch (Exception mongoEx) {
                            log.warn("[Import {}] Progress write to MongoDB failed (non-fatal): {}", projectId, mongoEx.getMessage());
                        }
                    });
                } catch (Exception serverEx) {
                    log.info("[Import {}] [TIMING] Server-side import failed after {} ms: {}", projectId, elapsedMillis(stageStart), serverEx.getMessage());
                }
            }

            // ⚡ MEDIUM PATH: Try direct HTTP upload (single POST, no batch commits)
            if (!serverImportDone) {
                try {
                    stageStart = System.nanoTime();
                    serverImportDone = datasetService.directHttpUpload(projectId, fileToLoad, format, actualFileSize, options, progress -> {
                        long totalBytes = progress.getTotalBytes();
                        long bytesRead = progress.getBytesRead();
                        if (totalBytes <= 0) return;
                        int percent = (int) Math.min(99, Math.floor((bytesRead * 100.0) / totalBytes));
                        lastProgressPercent.set(percent);
                        String message = String.format("Importing... (%d%%)", percent);
                        sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                                "PROCESSING", message, filename, Map.of("progress", percent, "stage", "graphdb-loading", "message", message));
                        try {
                            metadataService.writeStatus(projectId, ProjectStatus.processing(filename, message));
                        } catch (Exception mongoEx) {
                            log.warn("[Import {}] Progress write to MongoDB failed (non-fatal): {}", projectId, mongoEx.getMessage());
                        }
                    });
                } catch (Exception directEx) {
                    log.info("[Import {}] [TIMING] Direct HTTP upload failed after {} ms: {}", projectId, elapsedMillis(stageStart), directEx.getMessage());
                }
            }

            if (!serverImportDone) {
            // FALLBACK: Client-side chunked bulk load via RDF4J HTTP
            stageStart = System.nanoTime();
            log.info("[Import {}] Using chunked bulk load (client-side)", projectId);

            // Track re-serialized fallback file for cleanup
            Path[] owlApiFallbackFile = { null };

            try (InputStream in = Files.newInputStream(fileToLoad)) {
                try {
                datasetService.bulkLoadChunked(projectId, in, format, actualFileSize, options, progress -> {
                    long totalBytes = progress.getTotalBytes();
                    long bytesRead = progress.getBytesRead();
                    long elapsedMs = progress.getElapsedMs();

                    if (totalBytes <= 0) {
                        return;
                    }

                    int percent = (int) Math.min(99, Math.floor((bytesRead * 100.0) / totalBytes));
                    long now = System.nanoTime();
                    long lastSent = lastProgressSentAt.get();

                    // Send update if progress advanced or every 5 seconds
                    if (percent > lastProgressPercent.get() || (now - lastSent) >= 5_000_000_000L) {
                        if (lastProgressSentAt.compareAndSet(lastSent, now)) {
                            lastProgressPercent.set(percent);
                            double elapsedSeconds = elapsedMs / 1000.0;
                            // Use smoothed rate: ignore first 3 seconds (rate is unreliable early on)
                            long etaSeconds = -1;
                            if (elapsedSeconds >= 3.0 && bytesRead > 0) {
                                double rateBytesPerSec = bytesRead / elapsedSeconds;
                                long remainingBytes = Math.max(0, totalBytes - bytesRead);
                                etaSeconds = rateBytesPerSec > 0 ? (long) (remainingBytes / rateBytesPerSec) : -1;
                            }

                            String etaMessage;
                            if (etaSeconds < 0) {
                                etaMessage = "ETA calculating...";
                            } else if (etaSeconds == 0) {
                                etaMessage = "Almost done...";
                            } else {
                                etaMessage = String.format("ETA %d:%02d", etaSeconds / 60, etaSeconds % 60);
                            }

                            String message = String.format("Importing... (%d%%) | %s", percent, etaMessage);

                            Map<String, Object> progressMeta = new HashMap<>();
                            progressMeta.put("progress", percent);
                            progressMeta.put("stage", "graphdb-loading");
                            progressMeta.put("bytesRead", bytesRead);
                            progressMeta.put("totalBytes", totalBytes);
                            progressMeta.put("triplesProcessed", progress.getTriplesProcessed());
                            progressMeta.put("etaSeconds", etaSeconds);
                            progressMeta.put("message", message);

                            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                                    "PROCESSING", message, filename, progressMeta);

                            // Persist progress message for polling clients
                            try {
                                metadataService.writeStatus(projectId, ProjectStatus.processing(filename, message));
                            } catch (Exception mongoEx) {
                                log.warn("[Import {}] Progress write to MongoDB failed (non-fatal, chunked upload continues): {}", projectId, mongoEx.getMessage());
                            }
                        }
                    }
                });
                } catch (RuntimeException bulkEx) {
                    // If this is an XML structural error (unclosed elements, unescaped characters,
                    // etc.) and we haven't already run OWL API conversion, try it as a fallback.
                    // This covers large files like DOID where sanitizeFileOnDisk's
                    // reserializeWithOwlApi may have silently failed.
                    if (isXmlStructuralError(bulkEx) && format == RDFFormat.RDFXML) {
                        log.warn("[Import {}] Bulk load failed with XML structural error — retrying after OWL API re-serialization. Error: {}",
                                projectId, bulkEx.getMessage());
                        Path sourceForConversion = fileToLoad;
                        try {
                            Path reserialised = OWLFormatConverter.convertToRDFXML(sourceForConversion);
                            owlApiFallbackFile[0] = reserialised;
                            long reserialisedSize = Files.size(reserialised);
                            log.info("[Import {}] OWL API re-serialization successful ({} bytes), retrying bulk load",
                                    projectId, reserialisedSize);
                            try (InputStream retryIn = Files.newInputStream(reserialised)) {
                                datasetService.bulkLoadChunked(projectId, retryIn, RDFFormat.RDFXML,
                                        reserialisedSize, options, null);
                            }
                        } catch (Exception retryEx) {
                            log.error("[Import {}] Bulk load retry after OWL API re-serialization also failed",
                                    projectId, retryEx);
                            throw new RuntimeException(
                                    "Chunked bulk load failed even after OWL API re-serialization: " + retryEx.getMessage(),
                                    retryEx);
                        }
                    } else {
                        throw bulkEx;
                    }
                }
            } finally {
                // Clean up any OWL API fallback file
                if (owlApiFallbackFile[0] != null) {
                    try { Files.deleteIfExists(owlApiFallbackFile[0]); } catch (Exception ignored) {}
                }
                // Clean up format-converted file if it was created
                if (converted && fileToLoad != null && !fileToLoad.equals(owlFile)) {
                    try {
                        Files.deleteIfExists(fileToLoad);
                        log.debug("[Import {}] Cleaned up temporary converted file", projectId);
                    } catch (Exception e) {
                        log.warn("[Import {}] Failed to clean up converted file: {}", projectId, e.getMessage());
                    }
                }
            }
            } // end if (!serverImportDone)
            final long bulkLoadDurationMs = elapsedMillis(bulkLoadStart);
            log.info("[Import {}] [TIMING] GraphDB bulk load completed in {} ms (total import so far: {} ms)",
                    projectId, bulkLoadDurationMs, elapsedMillis(importStart));

            // Copy file to current location
            stage = "persist-copy";
            stageStart = System.nanoTime();
            Path current = storageManager.resolveProjectFile(projectId, "ontology.current." + extensionFor(format));
            Files.createDirectories(current.getParent());
            Files.copy(owlFile, current, StandardCopyOption.REPLACE_EXISTING);
            log.info("[Import {}] [TIMING] File copy to current: {} ms", projectId, elapsedMillis(stageStart));

            // Ensure OWLAPI warm is queued from persisted file if parallel parse did not finish.
            // Evict first: this file may be replacing a previously-loaded project (e.g. a
            // merge/re-import), and startParallelWarm's cache-hit fast-path would otherwise
            // keep serving the stale pre-write model instead of re-parsing what was just copied.
            if (desktopOntologyLoader != null) {
                desktopOntologyLoader.evictCache(projectId);
                desktopOntologyLoader.startParallelWarm(projectId, current);
            }

            // GraphDB ingest done — editor may still be building the class tree (OWLAPI warm)
            long durationMs = elapsedMillis(importStart);
            metadataService.writeStatus(projectId, ProjectStatus.completed(filename));
            importMarkedCompleted.set(true);  // Prevent catch block from overwriting to ERROR
            
            Map<String, Object> completionMeta = new HashMap<>();
            completionMeta.put("stage", "hierarchy-warming");
            completionMeta.put("durationMs", durationMs);
            completionMeta.put("hierarchyReady", false);
            completionMeta.put("message", "Loading class hierarchy…");
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                    "PROCESSING", "Loading class hierarchy…", filename, completionMeta);
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_COMPLETED,
                    "COMPLETED", "Loading class hierarchy…", filename, completionMeta);
            
            // Evict stale Caffeine + Mongo top-level/children caches so the next
            // hierarchy request recomputes from fresh Fuseki data (not a cached wrong tree).
            try {
                if (topLevelClassCacheService != null) topLevelClassCacheService.evict(projectId);
                // Evict only this project's entries via the dedicated eviction service,
                // which iterates Caffeine's native key set with a project prefix.
                // This avoids the prior chCache.clear() which flushed all projects.
                if (cacheEvictionService != null) {
                    cacheEvictionService.evictForProject(projectId);
                }
                log.info("[Import {}] Evicted hierarchy caches (topLevelClasses, classChildren, Mongo)", projectId);
            } catch (Exception cacheEx) {
                log.warn("[Import {}] Cache eviction failed (non-fatal): {}", projectId, cacheEx.getMessage());
            }

            // Hierarchy snapshot: own executor, starts immediately — users need the tree right away.
            if (hierarchyIndexService != null) {
                try {
                    hierarchyIndexService.evict(projectId);
                    hierarchyIndexService.scheduleBuild(projectId);
                    log.info("[Import {}] Scheduled hierarchy snapshot build", projectId);
                } catch (Exception hx) {
                    log.warn("[Import {}] Hierarchy snapshot schedule failed (non-fatal): {}", projectId, hx.getMessage());
                }
            }

            // Annotation pre-warm: starts immediately, fast (~60s for Mondo).
            // Entity usage index: chained to start AFTER annotation pre-warm finishes.
            // This prevents both from hammering Fuseki at the same time right after import,
            // ensuring annotations are in MongoDB before the heavier usage-index queries begin.
            if (classDetailCacheService != null) {
                try {
                    classDetailCacheService.dropAll(projectId);
                    CompletableFuture<Void> annotationsDone = classDetailCacheService.scheduleBuildAnnotations(projectId);
                    log.info("[Import {}] Scheduled annotation pre-warm (fast path, ~60s)", projectId);

                    if (entityUsageIndexService != null) {
                        final EntityUsageIndexService usageIndexRef = entityUsageIndexService;
                        entityUsageIndexService.dropAll(projectId);
                        annotationsDone.whenCompleteAsync((v, ex) -> {
                            try {
                                log.info("[Import {}] Annotation pre-warm done — starting entity usage index build", projectId);
                                usageIndexRef.scheduleBuild(projectId);
                            } catch (Exception ux) {
                                log.warn("[Import {}] Entity usage index schedule failed (non-fatal): {}", projectId, ux.getMessage());
                            }
                        }, metadataExecutor);
                    }
                } catch (Exception cx) {
                    log.warn("[Import {}] Annotation pre-warm schedule failed (non-fatal): {}", projectId, cx.getMessage());
                    // Fall back: start usage index immediately if annotation pre-warm failed to schedule
                    if (entityUsageIndexService != null) {
                        try {
                            entityUsageIndexService.dropAll(projectId);
                            entityUsageIndexService.scheduleBuild(projectId);
                        } catch (Exception ux) {
                            log.warn("[Import {}] Entity usage index fallback schedule failed: {}", projectId, ux.getMessage());
                        }
                    }
                }
            } else if (entityUsageIndexService != null) {
                try {
                    entityUsageIndexService.dropAll(projectId);
                    entityUsageIndexService.scheduleBuild(projectId);
                    log.info("[Import {}] Scheduled entity usage index build", projectId);
                } catch (Exception ux) {
                    log.warn("[Import {}] Entity usage index schedule failed (non-fatal): {}", projectId, ux.getMessage());
                }
            }

            log.info("✅ [Import {}] Marked as COMPLETED after {} ms. Metadata indexing continues in background.",
                    projectId, durationMs);
            importLog.info("[COMPLETED] project={} duration={}ms file={}", projectId, durationMs, filename);
            perfLog.info("[IMPORT] project={} duration={}ms file={}", projectId, durationMs, filename);

            // Compute metadata asynchronously in background (non-blocking)
            stage = "indexing";
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                    "PROCESSING", "Background: Computing metadata and statistics", filename, 
                    Map.of("stage", "background-indexing", "progress", 100));

            owlParsingExecutor.execute(() -> {
                try {
                    log.info("[Import {}] 🔄 Background task: Starting metadata indexing", projectId);
                    
                    // Resolve owl:imports BEFORE computing metadata so counts include imported triples.
                    // Wrapped in its own try-catch so a network failure doesn't abort metadata indexing.
                    Map<String, Object> importResolution = Map.of();
                    try {
                        importResolution = resolveOwlImports(projectId, filename, owlFile.getParent());
                    } catch (Exception importResolutionEx) {
                        log.warn("[Import {}] owl:imports resolution failed (continuing): {}",
                                projectId, importResolutionEx.getMessage());
                    }

                    log.info("[Import {}] Computing metadata statistics...", projectId);
                    long metadataStart = System.nanoTime();
                    Map<String, Object> meta = indexService.computeMetadata(projectId);
                    Integer classCount = toInteger(meta.get("classCount"));
                    Integer annotationCount = toInteger(meta.get("annotationPropertyCount"));
                    long totalDurationMs = elapsedMillis(importStart);

                    Map<String, Object> importMetrics = new HashMap<>();
                    importMetrics.put("fileSizeBytes", fileSizeBytes);
                    importMetrics.put("classCount", classCount);
                    importMetrics.put("annotationCount", annotationCount);
                    importMetrics.put("durationMs", totalDurationMs);
                    importMetrics.put("bulkLoadDurationMs", bulkLoadDurationMs);
                    importMetrics.put("importedAt", java.time.Instant.now().toString());
                    meta.put("importMetrics", importMetrics);
                    meta.put("importResolution", importResolution);
                    
                    long metadataComputeMs = elapsedMillis(metadataStart);
                    log.info("[Import {}] ✅ Metadata computed in {} ms", projectId, metadataComputeMs);
                    metadataService.writeMeta(projectId, meta);

                    timeEstimator.recordSample(fileSizeBytes, classCount, annotationCount, bulkLoadDurationMs);

                    log.info("✅ [Import {}] Background indexing complete. Total time: {} ms (GraphDB: {} ms, Metadata: {} ms)", 
                            projectId, totalDurationMs, totalDurationMs - metadataComputeMs, metadataComputeMs);

                    // Optional: Send notification that metadata is ready (frontend can refresh statistics)
                    Map<String, Object> metaReadyNotif = new HashMap<>();
                    metaReadyNotif.put("tripleCount", meta.get("tripleCount"));
                    metaReadyNotif.put("classCount", meta.get("classCount"));
                    metaReadyNotif.put("stage", "metadata-ready");
                    sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                            "METADATA_READY", "Metadata indexing complete", filename, metaReadyNotif);
                            
                } catch (Exception e) {
                    log.error("❌ [Import {}] Background metadata indexing failed (ontology still usable): {}", 
                            projectId, e.getMessage(), e);
                    // Note: We don't change status to ERROR here since the ontology is already loaded and usable
                    // Just log the error and notify about metadata issue
                    sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                            "METADATA_ERROR", "Metadata indexing failed: " + e.getMessage(), 
                            filename, Map.of("error", e.getMessage(), "stage", "metadata-error"));
                }
            });

        } catch (Exception e) {
            log.error("[Import {}|run:{}] Import failed while {}", projectId, runId, stage, e);
            importLog.error("[FAILED] project={} runId={} stage={} error={}", projectId, runId, stage, e.getMessage());

            // Only set ERROR status if import wasn't already marked as COMPLETED
            // This prevents overwriting COMPLETED -> ERROR after IMPORT_COMPLETED was sent
            if (!importMarkedCompleted.get()) {
                metadataService.writeStatus(projectId, ProjectStatus.error(filename, e.getMessage()));

                // Notify: Import failed
                Map<String, Object> errorMeta = new HashMap<>();
                errorMeta.put("error", e.getMessage());
                sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_FAILED,
                        "ERROR", "Import failed: " + e.getMessage(), filename, errorMeta);
            } else {
                // Import was already marked COMPLETED - log but don't change status
                log.warn("[Import {}|run:{}] Exception occurred after import was marked COMPLETED (status not changed): {}",
                        projectId, runId, e.getMessage());
            }
        } catch (Throwable t) {
            // StackOverflowError, OutOfMemoryError etc. are Errors, not Exceptions — catch (Exception e) above
            // does not intercept them, leaving metadata stuck as PROCESSING and the frontend spinner running forever.
            log.error("[Import {}|run:{}] Fatal JVM error while {}", projectId, runId, stage, t);
            importLog.error("[FAILED] project={} runId={} stage={} error={}", projectId, runId, stage, t.getClass().getSimpleName());
            if (!importMarkedCompleted.get()) {
                String reason = t.getClass().getSimpleName() + " during OWL parsing — file may be too large or deeply nested";
                metadataService.writeStatus(projectId, ProjectStatus.error(filename, reason));
                Map<String, Object> errorMeta = new HashMap<>();
                errorMeta.put("error", reason);
                sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_FAILED,
                        "ERROR", "Import failed: " + reason, filename, errorMeta);
            }
        } finally {
            log.info("[Import {}|run:{}] Finalizing import (lastStage={}, totalElapsedMs={})",
                    projectId, runId, stage, elapsedMillis(importStart));
            releaseImport(projectId);
        }
    }

    /**
     * Send WebSocket notification about import status
     */
    private void sendImportNotification(String projectId, ImportStatusMessage.ImportStatusType type,
                                       String status, String message, String filename, Object metadata) {
        try {
            // Extract progress if present in metadata
            Integer progress = null;
            if (metadata instanceof Map) {
                Object progressObj = ((Map<?, ?>) metadata).get("progress");
                if (progressObj instanceof Integer) {
                    progress = (Integer) progressObj;
                }
            }

            ImportStatusMessage notification = ImportStatusMessage.builder()
                    .type(type)
                    .projectId(projectId)
                    .status(status)
                    .statusMessage(message)
                    .filename(filename)
                    .progress(progress)
                    .timestamp(System.currentTimeMillis())
                    .metadata(metadata)
                    .build();

            // Send to all users subscribed to this project
            messagingTemplate.convertAndSend("/topic/import/" + projectId, notification);
            log.debug("Sent import notification for project {}: {}", projectId, type);
        } catch (Exception e) {
            log.warn("Failed to send import notification: {}", e.getMessage());
        }
    }

    private RDFFormat detectFormat(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        
        // Unambiguous extensions - trust the extension
        if (fileName.endsWith(".ttl") || fileName.endsWith(".turtle")) {
            return RDFFormat.TURTLE;
        } else if (fileName.endsWith(".nt") || fileName.endsWith(".ntriples")) {
            return RDFFormat.NTRIPLES;
        } else if (fileName.endsWith(".jsonld")) {
            return RDFFormat.JSONLD;
        } else if (fileName.endsWith(".n3")) {
            return RDFFormat.N3;
        }
        
        // For all other files (.owl, .rdf, .xml, or no extension), inspect content
        RDFFormat detectedFormat = detectFormatByContent(file);
        if (detectedFormat != null) {
            log.info("[Format Detection] Detected format by content for {}: {}", fileName, detectedFormat);
            return detectedFormat;
        }
        
        // Final fallback to RDF/XML
        log.warn("[Format Detection] Unable to detect format for {}, defaulting to RDF/XML", fileName);
        return RDFFormat.RDFXML;
    }
    
    /**
     * Detect RDF format by inspecting file content
     * @param file The file to inspect
     * @return Detected format or null if unable to detect
     */
    private RDFFormat detectFormatByContent(Path file) {
        try {
            // Read only the first 2KB to detect format (not the entire file)
            byte[] header;
            try (InputStream fis = Files.newInputStream(file)) {
                header = fis.readNBytes(2048);
            }
            int readLength = header.length;
            
            // Skip UTF-8 BOM if present
            int offset = 0;
            if (header.length >= 3 && header[0] == (byte) 0xEF && 
                header[1] == (byte) 0xBB && header[2] == (byte) 0xBF) {
                offset = 3;
                log.info("[Format Detection] Skipped UTF-8 BOM");
            }
            
            // Skip leading whitespace
            while (offset < readLength && (header[offset] == ' ' || header[offset] == '\t' || 
                   header[offset] == '\n' || header[offset] == '\r')) {
                offset++;
            }
            
            String content = new String(header, offset, Math.min(readLength - offset, 1024), 
                                       java.nio.charset.StandardCharsets.UTF_8);
            String contentLower = content.toLowerCase(Locale.ROOT);
            
            log.info("[Format Detection] File content preview (first 200 chars): {}", 
                    content.substring(0, Math.min(200, content.length())));
            
            // Check for XML markers FIRST (before N-Triples check which has a loose regex)
            if (contentLower.startsWith("<?xml") || contentLower.contains("<rdf:rdf") || 
                contentLower.contains("<owl:ontology") || contentLower.contains("<ontology")) {
                log.info("[Format Detection] Detected RDF/XML format (found XML markers)");
                return RDFFormat.RDFXML;
            }
            
            // Check for Turtle/N3 markers
            if (contentLower.startsWith("@prefix") || contentLower.startsWith("@base") ||
                contentLower.contains("@prefix ") || contentLower.contains("@base ")) {
                log.info("[Format Detection] Detected Turtle format (found @prefix or @base directive)");
                return RDFFormat.TURTLE;
            }
            
            // Check for N-Triples (subject-predicate-object with full URIs starting with http:// or https://)
            if (content.matches("(?s)^\\s*<https?://[^>]+>\\s+<[^>]+>\\s+.*")) {
                log.info("[Format Detection] Detected N-Triples format");
                return RDFFormat.NTRIPLES;
            }
            
            // Check for JSON-LD
            if (contentLower.trim().startsWith("{") && contentLower.contains("@context")) {
                log.info("[Format Detection] Detected JSON-LD format");
                return RDFFormat.JSONLD;
            }
            
            // Unable to detect - return null to use default
            log.warn("[Format Detection] Unable to detect format by content, will use default RDF/XML");
            return null;
            
        } catch (Exception e) {
            log.warn("[Format Detection] Failed to detect format by content: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Detect if a file is OWL/XML functional syntax (vs RDF/XML).
     * OWL/XML has <Ontology> root element with unqualified attributes like ontologyIRI.
     * RDF/XML has <rdf:RDF> root element.
     */
    private boolean isOwlXmlFormat(Path file) {
        try (BufferedInputStream bis = new BufferedInputStream(Files.newInputStream(file))) {
            bis.mark(16384);
            byte[] buffer = new byte[4096];
            int bytesRead = bis.read(buffer);
            bis.reset();
            
            if (bytesRead > 0) {
                String content = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                // Convert to lowercase for case-insensitive comparison
                String lowerContent = content.toLowerCase();
                
                // Check for OWL/XML markers (Ontology element or Declaration elements)
                boolean hasOntologyElement = content.contains("<Ontology") || lowerContent.contains("<ontology");
                boolean hasOWLNamespace = content.contains("http://www.w3.org/2002/07/owl#") && hasOntologyElement;
                boolean hasDeclarations = content.contains("<Declaration") || lowerContent.contains("<declaration");
                boolean hasOntologyIRI = content.contains("ontologyIRI");
                
                // OWL/XML typically has <Ontology> with ontologyIRI attribute (unqualified)
                if (hasOntologyElement && (hasOntologyIRI || hasDeclarations)) {
                    log.info("Detected OWL/XML format: has Ontology element with ontologyIRI or Declaration");
                    return true;
                }
                
                // RDF/XML has rdf:RDF root element
                boolean hasRDFRoot = content.contains("<rdf:RDF") || lowerContent.contains("<rdf:rdf");
                if (hasRDFRoot && !hasOntologyElement) {
                    log.info("Detected RDF/XML format: has rdf:RDF root");
                    return false;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to detect OWL/XML format: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Returns true when {@code ex} (or any cause in its chain) is a SAX parse
     * exception indicating the XML is structurally broken — e.g. unclosed
     * elements, unescaped angle-brackets in text nodes, duplicate root elements.
     *
     * This is intentionally narrower than "any parse error": namespace-not-bound
     * errors are also SAX errors but are handled by a separate namespace-injection
     * step, so we exclude them here.
     */
    private boolean isXmlStructuralError(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                // SAX well-formedness errors and GraphDB IRI validation errors
                if (lower.contains("must be terminated") ||
                    lower.contains("end-tag") ||
                    lower.contains("end tag") ||
                    lower.contains("unexpected end of file") ||
                    lower.contains("premature end of file") ||
                    lower.contains("content is not allowed in prolog") ||
                    lower.contains("invalid xml") ||
                    lower.contains("invalid iri") ||
                    lower.contains("invalidvalueexception") ||
                    lower.contains("illegalstateexception") ||
                    lower.contains("illegal state")) {
                    return true;
                }
                // RDF4J wraps the SAX error — check class names too
                if (t.getClass().getName().contains("SAXParseException")) {
                    boolean isNamespaceError = lower.contains("prefix") && (lower.contains("bound") || lower.contains("not bound"));
                    if (!isNamespaceError) {
                        return true;
                    }
                }
            }
            t = t.getCause();
        }
        return false;
    }


    /**
     * Detect and load any {@code owl:imports} that are declared in the project's named graph
     * but whose content has not yet been loaded.  Iterates transitively up to
     * {@code MAX_IMPORT_DEPTH} levels.  Uses INCREMENTAL mode so the existing graph triples
     * are preserved.  Import failures are logged as warnings and do not abort the import.
     */
    private Map<String, Object> resolveOwlImports(String projectId, String filename, Path baseDirectory) {
        final int MAX_IMPORT_DEPTH = 3;
        final int MAX_IMPORTS_TOTAL = 20;
        Set<String> loaded = new LinkedHashSet<>();
        Set<String> attempted = new LinkedHashSet<>();
        Set<String> declaredOnly = new LinkedHashSet<>();
        Map<String, String> failed = new HashMap<>();
        Set<String> toProcess = new LinkedHashSet<>();

        ImportOptions appendOptions = ImportOptions.builder()
                .mode(ImportOptions.ImportMode.INCREMENTAL)
                .partitionStrategy(ImportOptions.PartitionStrategy.NONE)
                .build();

        // Seed with direct imports from the main ontology
        toProcess.addAll(queryOwlImports(projectId));

        if (toProcess.isEmpty()) {
            log.info("[Import {}] No owl:imports statements found.", projectId);
            return Map.of(
                    "loaded", List.of(),
                    "declaredOnly", List.of(),
                    "failed", Map.of());
        }

        log.info("[Import {}] Resolving {} owl:imports: {}", projectId, toProcess.size(), toProcess);

        for (int depth = 0; depth < MAX_IMPORT_DEPTH && !toProcess.isEmpty(); depth++) {
            Set<String> nextRound = new LinkedHashSet<>();
            for (String importUri : toProcess) {
                if (attempted.size() >= MAX_IMPORTS_TOTAL) {
                    log.warn("[Import {}] Reached max import limit ({}), stopping", projectId, MAX_IMPORTS_TOTAL);
                    break;
                }
                if (attempted.contains(importUri)) {
                    continue;
                }
                attempted.add(importUri);

                try {
                    sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                            "PROCESSING", "Resolving import: " + importUri, filename,
                            Map.of("progress", 96, "stage", "resolving-imports"));

                    log.info("[Import {}] Resolving import (depth {}): {}", projectId, depth, importUri);
                    Path importTempFile = resolveImportToTemp(projectId, importUri, baseDirectory);
                    if (importTempFile == null) {
                        declaredOnly.add(importUri);
                        continue; // failed – warning already logged
                    }
                    try {
                        try {
                            log.warn("[Import {}] Sanitizing import file on disk: {}", projectId, importTempFile);
                            OWLFormatConverter.sanitizeFileOnDisk(importTempFile);
                        } catch (Exception sanitizeEx) {
                            log.warn("[Import {}] Sanitization of import {} failed: {}",
                                    projectId, importUri, sanitizeEx.getMessage());
                        }
                        RDFFormat importFormat = detectFormat(importTempFile);
                        long importSize = Files.size(importTempFile);
                        try (InputStream importIn = Files.newInputStream(importTempFile)) {
                            datasetService.bulkLoadChunked(projectId, importIn, importFormat,
                                    importSize, appendOptions, null);
                        }
                        log.info("[Import {}] Loaded import: {}", projectId, importUri);
                        loaded.add(importUri);
                        // Collect transitive imports from the newly loaded content
                        nextRound.addAll(queryOwlImports(projectId));
                    } finally {
                        Files.deleteIfExists(importTempFile);
                    }
                } catch (Exception importEx) {
                    log.warn("[Import {}] Could not load import {}: {}",
                            projectId, importUri, importEx.getMessage());
                    failed.put(importUri, importEx.getMessage());
                }
            }
            // Only process imports that weren't already loaded
            nextRound.removeAll(attempted);
            toProcess = nextRound;
        }

        if (!loaded.isEmpty()) {
            log.info("[Import {}] Resolved {} owl:imports", projectId, loaded.size());
        }
        return Map.of(
                "loaded", new ArrayList<>(loaded),
                "declaredOnly", new ArrayList<>(declaredOnly),
                "failed", failed);
    }

    /** Query the project graph for all {@code owl:imports} object values. */
    private List<String> queryOwlImports(String projectId) {
        List<String> uris = new ArrayList<>();
        try {
            TupleQueryResult result = datasetService.execSelect(projectId,
                    "SELECT DISTINCT ?importUri WHERE { " +
                    "?onto <http://www.w3.org/2002/07/owl#imports> ?importUri . }");
            while (result.hasNext()) {
                BindingSet bs = result.next();
                if (bs.hasBinding("importUri")) {
                    uris.add(bs.getValue("importUri").stringValue());
                }
            }
        } catch (Exception e) {
            log.warn("[Import] Failed to query owl:imports: {}", e.getMessage());
        }
        return uris;
    }

    /**
     * Resolve an import declaration to a temporary file. This supports the
     * Protégé-style cases that are safe on the server: HTTP(S), file:// paths
     * inside the project directory, and relative/bare filenames found under the
     * project directory. Imports outside the project directory remain declared
     * only; we do not read arbitrary server files.
     */
    private Path resolveImportToTemp(String projectId, String importUri, Path baseDirectory) {
        if (importUri.startsWith("http://") || importUri.startsWith("https://")) {
            return downloadToTemp(importUri);
        }

        Path local = resolveLocalImportPath(projectId, importUri, baseDirectory);
        if (local == null) {
            log.warn("[Import {}] Declared import is not resolvable on server: {}", projectId, importUri);
            return null;
        }

        try {
            String fileName = local.getFileName() != null ? local.getFileName().toString() : "import.owl";
            String suffix = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : ".owl";
            Path tmp = Files.createTempFile("owl-import-local-", suffix);
            Files.copy(local, tmp, StandardCopyOption.REPLACE_EXISTING);
            log.info("[Import {}] Resolved local import {} -> {} ({} bytes)",
                    projectId, importUri, local, Files.size(local));
            return tmp;
        } catch (Exception e) {
            log.warn("[Import {}] Failed to copy local import {}: {}", projectId, importUri, e.getMessage());
            return null;
        }
    }

    private Path resolveLocalImportPath(String projectId, String importUri, Path baseDirectory) {
        Path projectDir = storageManager.projectDir(projectId).toAbsolutePath().normalize();
        Path base = baseDirectory != null ? baseDirectory.toAbsolutePath().normalize() : projectDir;

        try {
            if (importUri.startsWith("file://")) {
                Path filePath = Path.of(URI.create(importUri)).toAbsolutePath().normalize();
                if (Files.isRegularFile(filePath) && filePath.startsWith(projectDir)) {
                    return filePath;
                }
                log.warn("[Import {}] file:// import is outside project directory or missing: {}", projectId, importUri);
                return null;
            }

            Path catalogMatch = resolveFromCatalog(projectId, importUri, projectDir, base);
            if (catalogMatch != null) {
                return catalogMatch;
            }

            Path candidate = base.resolve(importUri).normalize();
            if (Files.isRegularFile(candidate) && candidate.startsWith(projectDir)) {
                return candidate;
            }

            // If the exact relative path is not present, look for the same leaf
            // filename inside the project directory. This covers uploads where
            // supporting import files were placed beside/under the project.
            String leaf = Path.of(importUri).getFileName() != null
                    ? Path.of(importUri).getFileName().toString()
                    : importUri;
            if (leaf == null || leaf.isBlank()) {
                return null;
            }
            try (java.util.stream.Stream<Path> stream = Files.walk(projectDir, 4)) {
                Path leafMatch = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName() != null && leaf.equals(path.getFileName().toString()))
                        .findFirst()
                        .orElse(null);
                if (leafMatch != null) {
                    return leafMatch;
                }
            }

            return findOntologyDocumentByDeclaredIri(projectId, importUri, projectDir);
        } catch (Exception e) {
            log.warn("[Import {}] Could not resolve local import {}: {}", projectId, importUri, e.getMessage());
            return null;
        }
    }

    private Path resolveFromCatalog(String projectId, String importUri, Path projectDir, Path baseDirectory) {
        List<Path> catalogs = new ArrayList<>();
        catalogs.add(projectDir.resolve("catalog-v001.xml"));
        if (baseDirectory != null && !baseDirectory.equals(projectDir)) {
            catalogs.add(baseDirectory.resolve("catalog-v001.xml"));
        }
        catalogs.add(projectDir.resolve("ontology-library").resolve("catalog-v001.xml"));

        for (Path catalog : catalogs) {
            if (!Files.isRegularFile(catalog)) {
                continue;
            }
            try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(true);
            org.w3c.dom.Document doc = factory.newDocumentBuilder().parse(catalog.toFile());
            Path catalogBase = catalog.getParent() != null ? catalog.getParent().toAbsolutePath().normalize() : projectDir;
            org.w3c.dom.NodeList uriNodes = doc.getElementsByTagNameNS("*", "uri");
            for (int i = 0; i < uriNodes.getLength(); i++) {
                org.w3c.dom.Element entry = (org.w3c.dom.Element) uriNodes.item(i);
                String name = entry.getAttribute("name");
                String uri = entry.getAttribute("uri");
                if (!importUri.equals(name) || uri == null || uri.isBlank()) {
                    continue;
                }

                Path resolved = uri.startsWith("file:")
                        ? Path.of(URI.create(uri)).toAbsolutePath().normalize()
                        : catalogBase.resolve(uri).normalize();
                if (Files.isRegularFile(resolved) && resolved.startsWith(projectDir)) {
                    log.info("[Import {}] Catalog resolved {} -> {}", projectId, importUri, resolved);
                    return resolved;
                }
                log.warn("[Import {}] Catalog entry for {} points outside project directory or is missing: {}",
                        projectId, importUri, uri);
            }
            } catch (Exception e) {
                log.warn("[Import {}] Failed to read catalog-v001.xml at {}: {}", projectId, catalog, e.getMessage());
            }
        }
        return null;
    }

    private Path findOntologyDocumentByDeclaredIri(String projectId, String importUri, Path projectDir) {
        try (java.util.stream.Stream<Path> stream = Files.walk(projectDir, 4)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::looksLikeOntologyDocument)
                    .filter(path -> ontologyDocumentDeclaresIri(projectId, path, importUri))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[Import {}] Failed while scanning project directory for import {}: {}",
                    projectId, importUri, e.getMessage());
            return null;
        }
    }

    private boolean looksLikeOntologyDocument(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        return name.endsWith(".owl")
                || name.endsWith(".rdf")
                || name.endsWith(".xml")
                || name.endsWith(".ttl")
                || name.endsWith(".nt")
                || name.endsWith(".jsonld");
    }

    private boolean ontologyDocumentDeclaresIri(String projectId, Path path, String importUri) {
        try {
            if (Files.size(path) > 100L * 1024 * 1024) {
                log.debug("[Import {}] Skipping ontology IRI scan for large candidate {}", projectId, path);
                return false;
            }

            RDFFormat format = detectFormat(path);
            AtomicReference<String> ontologyIri = new AtomicReference<>();
            AtomicReference<String> versionIri = new AtomicReference<>();
            RDFParser parser = Rio.createParser(format);
            parser.setRDFHandler(new AbstractRDFHandler() {
                @Override
                public void handleStatement(org.eclipse.rdf4j.model.Statement st) throws RDFHandlerException {
                    if (st.getPredicate().equals(org.eclipse.rdf4j.model.vocabulary.RDF.TYPE)
                            && st.getObject().equals(org.eclipse.rdf4j.model.vocabulary.OWL.ONTOLOGY)
                            && st.getSubject().isIRI()) {
                        ontologyIri.set(st.getSubject().stringValue());
                    } else if (st.getPredicate().equals(org.eclipse.rdf4j.model.vocabulary.OWL.VERSIONIRI)
                            && st.getObject().isIRI()) {
                        versionIri.set(st.getObject().stringValue());
                    }

                    if (importUri.equals(ontologyIri.get()) || importUri.equals(versionIri.get())) {
                        throw new RDFHandlerException("MATCH");
                    }
                }
            });

            try (InputStream in = Files.newInputStream(path)) {
                parser.parse(in, path.toUri().toString());
            } catch (RDFHandlerException match) {
                if ("MATCH".equals(match.getMessage())) {
                    log.info("[Import {}] Folder scan resolved {} -> {}", projectId, importUri, path);
                    return true;
                }
                throw match;
            }

            boolean matched = importUri.equals(ontologyIri.get()) || importUri.equals(versionIri.get());
            if (matched) {
                log.info("[Import {}] Folder scan resolved {} -> {}", projectId, importUri, path);
            }
            return matched;
        } catch (Exception e) {
            log.debug("[Import {}] Candidate {} did not resolve import {}: {}",
                    projectId, path, importUri, e.getMessage());
            return false;
        }
    }

    /**
     * Download the resource at {@code uri} to a temp file and return its path.
     * Returns {@code null} and logs a warning if the download fails.
     */
    private Path downloadToTemp(String uri) {
        try {
            java.net.URL url = URI.create(uri).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15_000);   // 15 s
            conn.setReadTimeout(60_000);      // 60 s max — 10 min was causing hung imports
            conn.setRequestProperty("Accept",
                    "application/rdf+xml, text/turtle, application/n-triples, */*");
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                log.warn("[Import] HTTP {} fetching import: {}", code, uri);
                conn.disconnect();
                return null;
            }
            // Derive a sensible filename from the URI
            String uriPath = URI.create(uri).getPath();
            String leaf = uriPath.substring(uriPath.lastIndexOf('/') + 1);
            if (leaf.isBlank()) leaf = "import";
            String suffix = leaf.contains(".") ? leaf.substring(leaf.lastIndexOf('.')) : ".owl";
            Path tmp = Files.createTempFile("owl-import-", suffix);
            try (InputStream is = conn.getInputStream()) {
                Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                conn.disconnect();
            }
            log.info("[Import] Downloaded {} -> {} ({} bytes)", uri, tmp.getFileName(), Files.size(tmp));
            return tmp;
        } catch (Exception e) {
            log.warn("[Import] Download failed for {}: {}", uri, e.getMessage());
            return null;
        }
    }

    private String extensionFor(RDFFormat format) {
        if (format == null) {
            return "owl";
        }
        if (RDFFormat.TURTLE.equals(format)) {
            return "ttl";
        }
        if (RDFFormat.NTRIPLES.equals(format)) {
            return "nt";
        }
        if (RDFFormat.JSONLD.equals(format)) {
            return "jsonld";
        }
        return "owl";
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * Protégé-style import: persist file, warm OWLAPI, complete without blocking on Fuseki.
     * Fuseki sync runs lazily when SPARQL/graph features need it.
     */
    private boolean completeOwlApiFirstImport(String projectId,
                                             Path owlFile,
                                             String filename,
                                             RDFFormat format,
                                             long importStart,
                                             AtomicBoolean importMarkedCompleted) throws IOException {
        log.info("[Import {}] OWLAPI-first desktop import (Protégé-style)", projectId);

        Map<String, Object> warmMeta = new HashMap<>();
        warmMeta.put("progress", 40);
        warmMeta.put("stage", "owlapi-loading");
        warmMeta.put("message", "Opening ontology (OWLAPI)…");
        sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                "PROCESSING", "Opening ontology (OWLAPI)…", filename, warmMeta);
        metadataService.writeImportProgress(projectId, 40, "owlapi-loading", "Opening ontology (OWLAPI)…");

        Path current = storageManager.resolveProjectFile(projectId, "ontology.current." + extensionFor(format));
        Files.createDirectories(current.getParent());
        Files.copy(owlFile, current, StandardCopyOption.REPLACE_EXISTING);

        // This path runs for re-imports (e.g. merge results) as well as first-time imports —
        // if the project's OWLAPI model is already cached from before this file was written,
        // startParallelWarm's cache-hit fast-path would report success without ever re-parsing
        // the file we just copied, leaving the UI showing pre-merge/pre-import content. Evict
        // first so the warm below always reflects what's actually on disk now.
        desktopOntologyLoader.evictCache(projectId);
        desktopOntologyLoader.startParallelWarm(projectId, current);
        Map<String, Object> warm = desktopOntologyLoader.warmProject(projectId, 600_000L);
        boolean ready = Boolean.TRUE.equals(warm.get("ready")) || Boolean.TRUE.equals(warm.get("owlapiReady"));

        if (!ready) {
            log.warn("[Import {}] OWLAPI-first import failed: {}", projectId, warm.get("error"));
            return false;
        }

        // Non-blocking sanity check: warmProject() intentionally doesn't gate readiness on class
        // count (a legitimately empty ontology must not block open for minutes), but a 0-class
        // result after importing an existing file is almost always a sign the source content was
        // deficient (e.g. an export bug) rather than a genuinely empty ontology — this app's own
        // "new file" template always declares at least owl:Thing. Log loudly so this is visible
        // in server logs instead of silently reporting "ready" with an empty tree.
        long classCount = desktopOntologyLoader.classCount(projectId);
        if (classCount == 0) {
            log.warn("[Import {}] OWLAPI-first import reports ready but parsed 0 classes from {} — " +
                    "the source file may be empty/malformed rather than this being a genuinely empty ontology",
                    projectId, current);
        }

        markFusekiSyncPending(projectId);

        long durationMs = elapsedMillis(importStart);
        metadataService.writeStatus(projectId, ProjectStatus.hierarchyReady(filename));
        importMarkedCompleted.set(true);

        Map<String, Object> completionMeta = new HashMap<>();
        completionMeta.put("stage", "owlapi-ready");
        completionMeta.put("durationMs", durationMs);
        completionMeta.put("hierarchyReady", true);
        completionMeta.put("fusekiSynced", false);
        completionMeta.put("message", "Ontology ready — class tree available");
        sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_COMPLETED,
                "COMPLETED", "Ontology ready — class tree available", filename, completionMeta);

        log.info("[Import {}] OWLAPI-first import completed in {} ms (Fuseki sync deferred)", projectId, durationMs);
        if (openMetricsService != null) {
            openMetricsService.recordImportComplete(projectId, durationMs, true, false);
        }
        if (fusekiSyncScheduler != null) {
            fusekiSyncScheduler.scheduleAfterOpen(projectId);
        }
        return true;
    }

    /**
     * Per-project serialization for {@link #syncProjectToFuseki} — the UI's direct
     * sync (Fuseki-tab loader / Code View) and the background scheduler can fire
     * concurrently, and two simultaneous full uploads of a large ontology would
     * race each other into Fuseki. The second caller waits, then usually returns
     * {@code alreadyLoaded} from the pending-flag check.
     */
    private final ConcurrentHashMap<String, Object> fusekiSyncLocks = new ConcurrentHashMap<>();

    /** Lazy Fuseki sync for OWLAPI-first desktop (SPARQL tab, graph view, etc.). */
    public Map<String, Object> syncProjectToFuseki(String projectId) {
        Map<String, Object> result = new HashMap<>();
        if (!owlApiFirst) {
            result.put("synced", true);
            result.put("skipped", true);
            return result;
        }
        synchronized (fusekiSyncLocks.computeIfAbsent(projectId, id -> new Object())) {
        try {
            // Draft-aware: sync the working copy (unsaved draft when present) so
            // SPARQL/graph views mirror what the user is editing, not the last save.
            Optional<Path> file = storageManager.findWorkingOntology(projectId);
            if (file.isEmpty() || !Files.exists(file.get())) {
                result.put("synced", false);
                result.put("error", "No ontology file on disk");
                return result;
            }
            Path owlFile = file.get();
            if (datasetService.hasGraphData(projectId) && !isFusekiSyncPending(projectId)) {
                result.put("synced", true);
                result.put("alreadyLoaded", true);
                return result;
            }

            RDFFormat format = detectFormat(owlFile);
            long size = Files.size(owlFile);
            ImportOptions options = ImportOptions.builder()
                    .mode(ImportOptions.ImportMode.FULL)
                    .partitionStrategy(ImportOptions.PartitionStrategy.NONE)
                    .build();

            log.info("[Import {}] Lazy Fuseki sync starting ({} bytes)", projectId, size);
            boolean done = datasetService.directHttpUpload(projectId, owlFile, format, size, options, null);
            if (!done) {
                done = datasetService.serverSideImport(projectId, owlFile, format, size, options, null);
            }
            if (!done) {
                result.put("synced", false);
                result.put("error", "Fuseki sync failed — is the triple store running?");
                return result;
            }

            clearFusekiSyncPending(projectId);
            if (topLevelClassCacheService != null) {
                topLevelClassCacheService.evict(projectId);
            }
            if (cacheEvictionService != null) {
                cacheEvictionService.evictForProject(projectId);
            }
            result.put("synced", true);
            return result;
        } catch (Exception e) {
            log.warn("[Import {}] Lazy Fuseki sync failed: {}", projectId, e.getMessage());
            result.put("synced", false);
            result.put("error", e.getMessage());
            return result;
        }
        }
    }

    public boolean isFusekiSyncPending(String projectId) {
        return Files.exists(fusekiSyncPendingPath(projectId));
    }

    public void markFusekiSyncPendingPublic(String projectId) {
        markFusekiSyncPending(projectId);
    }

    private void markFusekiSyncPending(String projectId) {
        try {
            Files.writeString(fusekiSyncPendingPath(projectId), Instant.now().toString());
        } catch (IOException e) {
            log.warn("[Import {}] Could not write fuseki-sync-pending marker: {}", projectId, e.getMessage());
        }
    }

    private void clearFusekiSyncPending(String projectId) {
        try {
            Files.deleteIfExists(fusekiSyncPendingPath(projectId));
        } catch (IOException ignored) {
        }
    }

    private Path fusekiSyncPendingPath(String projectId) {
        return storageManager.projectDir(projectId).resolve("fuseki-sync-pending");
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}

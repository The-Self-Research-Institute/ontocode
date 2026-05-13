package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.ImportOptions;
import self.research.ontology.owlEditor.model.ImportQueueItem;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.model.collaboration.ImportStatusMessage;
import self.research.ontology.owlEditor.util.OWLFormatConverter;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
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

    // Prevent concurrent imports for the same project (which cause overlapping progress threads and GraphDB clears)
    private final Map<String, AtomicBoolean> importInProgress = new ConcurrentHashMap<>();

    private final Executor owlParsingExecutor;
    private final GraphDBDatasetService datasetService;
    private final OntologyIndexService indexService;
    private final ProjectMetadataService metadataService;
    private final StorageManager storageManager;
    private final SimpMessagingTemplate messagingTemplate;
    private final ImportQueueManager queueManager;
    private final ImportTimeEstimator timeEstimator;

    public ProjectImportService(@Qualifier("owlParsingExecutor") Executor owlParsingExecutor,
                                GraphDBDatasetService datasetService,
                                OntologyIndexService indexService,
                                ProjectMetadataService metadataService,
                                StorageManager storageManager,
                                SimpMessagingTemplate messagingTemplate,
                                ImportQueueManager queueManager,
                                ImportTimeEstimator timeEstimator) {
        this.owlParsingExecutor = owlParsingExecutor;
        this.datasetService = datasetService;
        this.indexService = indexService;
        this.metadataService = metadataService;
        this.storageManager = storageManager;
        this.messagingTemplate = messagingTemplate;
        this.queueManager = queueManager;
        this.timeEstimator = timeEstimator;
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

        // Fast path for small files: skip queue overhead when no concurrent imports running
        long fileSizeBytes;
        try {
            fileSizeBytes = java.nio.file.Files.size(owlFile);
        } catch (Exception e) {
            fileSizeBytes = -1;
        }
        boolean isSmallFile = fileSizeBytes >= 0 && fileSizeBytes < 100 * 1024; // < 100KB
        
        if (isSmallFile && queueManager.canProcess() && queueManager.isEmpty()) {
            log.info("[Import] Fast path: small file ({} bytes), processing immediately", fileSizeBytes);
            final long fSize = fileSizeBytes;
            owlParsingExecutor.execute(() -> {
                ImportQueueItem item = ImportQueueItem.builder()
                        .projectId(projectId)
                        .filename(filename)
                        .ownerEmail(ownerEmail)
                        .owlFile(owlFile)
                        .importMode(options.getMode())
                        .partitionStrategy(options.getPartitionStrategy())
                        .fileSizeBytes(fSize)
                        .status(ImportQueueItem.ImportStatus.PROCESSING)
                        .queuedAt(java.time.Instant.now())
                        .startedAt(java.time.Instant.now())
                        .build();
                long startTime = System.currentTimeMillis();
                try {
                    runImport(item);
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("[Import] Fast path completed in {} ms for project {}", duration, projectId);
                } catch (Exception e) {
                    log.error("[Import] Fast path failed for project {}", projectId, e);
                }
            });
            return;
        }

        // Add to queue
        queueManager.enqueue(projectId, filename, ownerEmail, owlFile, options);

        // Try to process next item in queue
        processNextInQueue();
    }

    private void processNextInQueue() {
        if (!queueManager.canProcess()) {
            log.debug("[Import] Cannot process - max concurrent imports reached");
            return;
        }

        owlParsingExecutor.execute(() -> {
            ImportQueueItem item = queueManager.dequeue();
            if (item == null) {
                return; // No items in queue
            }

            long startTime = System.currentTimeMillis();
            try {
                runImport(item);
                long duration = System.currentTimeMillis() - startTime;
                queueManager.markCompleted(item.getProjectId(), duration);
            } catch (Exception e) {
                log.error("[Import] Failed to process queue item for project {}", item.getProjectId(), e);
                
                // Check if error is retryable (connection issues, timeouts, etc.)
                boolean shouldRetry = isRetryableError(e);
                String errorReason = extractErrorReason(e);
                
                queueManager.markFailed(item.getProjectId(), errorReason, shouldRetry);
            } finally {
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
        return message != null && (
                message.contains("SocketException") ||
                message.contains("Connection aborted") ||
                message.contains("Connection reset") ||
                message.contains("Connection timeout") ||
                message.contains("NonRepeatableRequestException") ||
                causeMessage.contains("SocketException") ||
                causeMessage.contains("Connection aborted")
        );
    }

    /**
     * Extract a user-friendly error reason
     */
    private String extractErrorReason(Exception e) {
        if (isRetryableError(e)) {
            return "Connection to GraphDB lost. This usually means GraphDB ran out of memory for large files.";
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

    private void runImport(ImportQueueItem item) {
        String projectId = item.getProjectId();
        Path owlFile = item.getOwlFile();
        AtomicBoolean guard = importInProgress.computeIfAbsent(projectId, id -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            log.warn("[Import {}] Import already running, rejecting duplicate request", projectId);
            Map<String, Object> errorMeta = new HashMap<>();
            errorMeta.put("error", "Import already in progress for this project");
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_FAILED,
                    "ERROR", "Import already in progress, please wait", owlFile.getFileName().toString(), errorMeta);
            return;
        }

        long importStart = System.nanoTime();
        importLog.info("[START] project={} file={}", projectId, owlFile.getFileName());
        String stage = "initialization";
        String filename = metadataService.readStatus(projectId)
                .map(ProjectStatus::filename)
                .orElse(owlFile.getFileName().toString());

        // Track whether import was marked as COMPLETED (prevents overwriting to ERROR in catch block)
        AtomicBoolean importMarkedCompleted = new AtomicBoolean(false);

        log.info("[Import {}] Starting import for file {}", projectId, filename);

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
            bulkLoadStartMeta.put("message", "Loading data into GraphDB (this may take several minutes for large files)...");
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                    "PROCESSING", "Loading into GraphDB...", filename, bulkLoadStartMeta);
            // Also update status.json so polling clients get the progress message
            metadataService.writeStatus(projectId, ProjectStatus.processing(filename, "Loading into GraphDB..."));
            
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

            // ⚡ FAST PATH: Try GraphDB server-side import first (reads file from shared volume — no HTTP overhead)
            boolean serverImportDone = false;
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
                    metadataService.writeStatus(projectId, ProjectStatus.processing(filename, message));
                });
            } catch (Exception serverEx) {
                log.info("[Import {}] [TIMING] Server-side import failed after {} ms: {}", projectId, elapsedMillis(stageStart), serverEx.getMessage());
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
                        metadataService.writeStatus(projectId, ProjectStatus.processing(filename, message));
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
                            metadataService.writeStatus(projectId, ProjectStatus.processing(filename, message));
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
            log.info("[Import {}] [TIMING] GraphDB bulk load completed in {} ms (total import so far: {} ms)", 
                    projectId, elapsedMillis(bulkLoadStart), elapsedMillis(importStart));

            // Copy file to current location
            stage = "persist-copy";
            stageStart = System.nanoTime();
            Path current = storageManager.resolveProjectFile(projectId, "ontology.current." + extensionFor(format));
            Files.createDirectories(current.getParent());
            Files.copy(owlFile, current, StandardCopyOption.REPLACE_EXISTING);
            log.info("[Import {}] [TIMING] File copy to current: {} ms", projectId, elapsedMillis(stageStart));

            // ⚡ PERFORMANCE OPTIMIZATION: Mark import as COMPLETED immediately after GraphDB load
            // This allows frontend to start using the ontology without waiting for metadata indexing
            long durationMs = elapsedMillis(importStart);
            metadataService.writeStatus(projectId, ProjectStatus.completed(filename));
            importMarkedCompleted.set(true);  // Prevent catch block from overwriting to ERROR
            
            // Send IMPORT_COMPLETED notification NOW so frontend can start working
            Map<String, Object> completionMeta = new HashMap<>();
            completionMeta.put("stage", "graphdb-load-complete");
            completionMeta.put("durationMs", durationMs);
            completionMeta.put("message", "Ontology loaded and ready to use. Metadata indexing continues in background.");
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_COMPLETED,
                    "COMPLETED", "Ontology loaded successfully", filename, completionMeta);
            
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
                    importMetrics.put("importedAt", java.time.Instant.now().toString());
                    meta.put("importMetrics", importMetrics);
                    meta.put("importResolution", importResolution);
                    
                    long metadataComputeMs = elapsedMillis(metadataStart);
                    log.info("[Import {}] ✅ Metadata computed in {} ms", projectId, metadataComputeMs);
                    metadataService.writeMeta(projectId, meta);

                    timeEstimator.recordSample(fileSizeBytes, classCount, annotationCount, totalDurationMs);

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
            log.error("Import failed for {} while {}", projectId, stage, e);
            importLog.error("[FAILED] project={} stage={} error={}", projectId, stage, e.getMessage());
            
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
                log.warn("[Import {}] Exception occurred after import was marked COMPLETED (status not changed): {}", 
                        projectId, e.getMessage());
            }
        } finally {
            guard.set(false);
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

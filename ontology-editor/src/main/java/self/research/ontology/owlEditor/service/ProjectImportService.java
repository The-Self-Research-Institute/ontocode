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

/**
 * Background job runner that streams uploaded OWL files into TDB2 and refreshes metadata.
 */
@Service
public class ProjectImportService {

    private static final Logger log = LoggerFactory.getLogger(ProjectImportService.class);

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
        String stage = "initialization";
        String filename = metadataService.readStatus(projectId)
                .map(ProjectStatus::filename)
                .orElse(owlFile.getFileName().toString());

        log.info("[Import {}] Starting import for file {}", projectId, filename);

        // Notify: Import started
        sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_STARTED,
                "PROCESSING", "Import started", filename, null);

        metadataService.writeStatus(projectId, ProjectStatus.processing(filename));
        try {
            stage = "detect-format";
            RDFFormat format = detectFormat(owlFile);
            log.info("[Import {}] Detected RDF format: {} (name: {}, defaultFileExtension: {})", 
                    projectId, format, format.getName(), format.getDefaultFileExtension());
            
            // Sanitize the file to fix malformed XML before import
            stage = "sanitize";
            try {
                OWLFormatConverter.sanitizeFileOnDisk(owlFile);
                log.info("[Import {}] File sanitization completed", projectId);
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

            log.info("[Import {}] Starting bulkLoadChunked: file={}, format={}, size={} bytes, converted={}",
                    projectId, fileToLoad.getFileName(), format, actualFileSize, converted);

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

                    int percent = (int) Math.min(90, Math.floor((bytesRead * 100.0) / totalBytes));
                    long now = System.nanoTime();
                    long lastSent = lastProgressSentAt.get();

                    // Send update if progress advanced or every 5 seconds
                    if (percent > lastProgressPercent.get() || (now - lastSent) >= 5_000_000_000L) {
                        if (lastProgressSentAt.compareAndSet(lastSent, now)) {
                            lastProgressPercent.set(percent);
                            double elapsedSeconds = Math.max(1.0, elapsedMs / 1000.0);
                            double rateBytesPerSec = bytesRead / elapsedSeconds;
                            long remainingBytes = Math.max(0, totalBytes - bytesRead);
                            long etaSeconds = rateBytesPerSec > 0 ? (long) (remainingBytes / rateBytesPerSec) : -1;

                            String etaMessage = etaSeconds > 0
                                    ? String.format("ETA %d:%02d", etaSeconds / 60, etaSeconds % 60)
                                    : "ETA calculating...";

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
            log.info("[Import {}] GraphDB bulk load completed in {} ms", projectId, elapsedMillis(bulkLoadStart));

            // Copy file to current location
            stage = "persist-copy";
            Path current = storageManager.resolveProjectFile(projectId, "ontology.current." + extensionFor(format));
            Files.createDirectories(current.getParent());
            Files.copy(owlFile, current, StandardCopyOption.REPLACE_EXISTING);

            // ⚡ PERFORMANCE OPTIMIZATION: Mark import as COMPLETED immediately after GraphDB load
            // This allows frontend to start using the ontology without waiting for metadata indexing
            long durationMs = elapsedMillis(importStart);
            metadataService.writeStatus(projectId, ProjectStatus.completed(filename));
            
            // Send IMPORT_COMPLETED notification NOW so frontend can start working
            Map<String, Object> completionMeta = new HashMap<>();
            completionMeta.put("stage", "graphdb-load-complete");
            completionMeta.put("durationMs", durationMs);
            completionMeta.put("message", "Ontology loaded and ready to use. Metadata indexing continues in background.");
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_COMPLETED,
                    "COMPLETED", "Ontology loaded successfully", filename, completionMeta);
            
            log.info("✅ [Import {}] Marked as COMPLETED after {} ms. Metadata indexing continues in background.", 
                    projectId, durationMs);

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
                    try {
                        resolveOwlImports(projectId, filename);
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
            metadataService.writeStatus(projectId, ProjectStatus.error(filename, e.getMessage()));

            // Notify: Import failed
            Map<String, Object> errorMeta = new HashMap<>();
            errorMeta.put("error", e.getMessage());
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_FAILED,
                    "ERROR", "Import failed: " + e.getMessage(), filename, errorMeta);
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
            // Read first 2KB to detect format
            byte[] header = java.nio.file.Files.readAllBytes(file);
            int readLength = Math.min(2048, header.length);
            
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
                // SAX well-formedness errors
                if (lower.contains("must be terminated") ||
                    lower.contains("end-tag") ||
                    lower.contains("end tag") ||
                    lower.contains("unexpected end of file") ||
                    lower.contains("premature end of file") ||
                    lower.contains("content is not allowed in prolog") ||
                    lower.contains("invalid xml") ||
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
    private void resolveOwlImports(String projectId, String filename) {
        final int MAX_IMPORT_DEPTH = 3;
        final int MAX_IMPORTS_TOTAL = 20;
        Set<String> loaded = new LinkedHashSet<>();
        Set<String> toProcess = new LinkedHashSet<>();

        ImportOptions appendOptions = ImportOptions.builder()
                .mode(ImportOptions.ImportMode.INCREMENTAL)
                .partitionStrategy(ImportOptions.PartitionStrategy.NONE)
                .build();

        // Seed with direct imports from the main ontology
        toProcess.addAll(queryOwlImports(projectId));

        if (toProcess.isEmpty()) {
            log.info("[Import {}] No owl:imports statements found.", projectId);
            return;
        }

        log.info("[Import {}] Resolving {} owl:imports: {}", projectId, toProcess.size(), toProcess);

        for (int depth = 0; depth < MAX_IMPORT_DEPTH && !toProcess.isEmpty(); depth++) {
            Set<String> nextRound = new LinkedHashSet<>();
            for (String importUri : toProcess) {
                if (loaded.size() >= MAX_IMPORTS_TOTAL) {
                    log.warn("[Import {}] Reached max import limit ({}), stopping", projectId, MAX_IMPORTS_TOTAL);
                    break;
                }
                if (loaded.contains(importUri)) {
                    continue;
                }
                loaded.add(importUri);

                if (!importUri.startsWith("http://") && !importUri.startsWith("https://")) {
                    log.info("[Import {}] Skipping non-HTTP import: {}", projectId, importUri);
                    continue;
                }

                try {
                    sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                            "PROCESSING", "Resolving import: " + importUri, filename,
                            Map.of("progress", 96, "stage", "resolving-imports"));

                    log.info("[Import {}] Downloading import (depth {}): {}", projectId, depth, importUri);
                    Path importTempFile = downloadToTemp(importUri);
                    if (importTempFile == null) {
                        continue; // failed – warning already logged
                    }
                    try {
                        try {
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
                        // Collect transitive imports from the newly loaded content
                        nextRound.addAll(queryOwlImports(projectId));
                    } finally {
                        Files.deleteIfExists(importTempFile);
                    }
                } catch (Exception importEx) {
                    log.warn("[Import {}] Could not load import {}: {}",
                            projectId, importUri, importEx.getMessage());
                }
            }
            // Only process imports that weren't already loaded
            nextRound.removeAll(loaded);
            toProcess = nextRound;
        }

        if (!loaded.isEmpty()) {
            log.info("[Import {}] Resolved {} owl:imports", projectId, loaded.size());
        }
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
     * Download the resource at {@code uri} to a temp file and return its path.
     * Returns {@code null} and logs a warning if the download fails.
     */
    private Path downloadToTemp(String uri) {
        try {
            java.net.URL url = URI.create(uri).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15_000);   // 15 s
            conn.setReadTimeout(600_000);     // 10 min for large imports
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

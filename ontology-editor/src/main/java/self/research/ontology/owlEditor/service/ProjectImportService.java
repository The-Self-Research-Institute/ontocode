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

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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

            try (InputStream in = Files.newInputStream(fileToLoad)) {
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
            } finally {
                // Clean up converted file if it was created
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

            Map<String, Object> postLoadMeta = new HashMap<>();
            postLoadMeta.put("progress", 95);
            postLoadMeta.put("stage", "graphdb-load-complete");
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                    "PROCESSING", "GraphDB load finished, computing metadata", filename, postLoadMeta);

            // Copy file
            stage = "persist-copy";
            Path current = storageManager.resolveProjectFile(projectId, "ontology.current." + extensionFor(format));
            Files.createDirectories(current.getParent());
            Files.copy(owlFile, current, StandardCopyOption.REPLACE_EXISTING);

            // Compute metadata
            stage = "indexing";
            metadataService.writeStatus(projectId, ProjectStatus.indexing(filename));
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                    "PROCESSING", "Indexing and metadata computation started", filename, Map.of("stage", "indexing"));

            owlParsingExecutor.execute(() -> {
                try {
                    log.info("[Import {}] Computing metadata", projectId);
                    long metadataStart = System.nanoTime();
                    Map<String, Object> meta = indexService.computeMetadata(projectId);
                    Integer classCount = toInteger(meta.get("classCount"));
                    Integer annotationCount = toInteger(meta.get("annotationPropertyCount"));
                    long durationMs = elapsedMillis(importStart);

                    Map<String, Object> importMetrics = new HashMap<>();
                    importMetrics.put("fileSizeBytes", fileSizeBytes);
                    importMetrics.put("classCount", classCount);
                    importMetrics.put("annotationCount", annotationCount);
                    importMetrics.put("durationMs", durationMs);
                    importMetrics.put("importedAt", java.time.Instant.now().toString());
                    meta.put("importMetrics", importMetrics);
                    log.info("[Import {}] Metadata computed in {} ms", projectId, elapsedMillis(metadataStart));
                    metadataService.writeMeta(projectId, meta);

                    timeEstimator.recordSample(fileSizeBytes, classCount, annotationCount, durationMs);

                    // Update status
                    metadataService.writeStatus(projectId, ProjectStatus.completed(filename));
                    log.info("Completed import for project {} in {} ms", projectId, durationMs);

                    // Notify: Import completed
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("tripleCount", meta.get("tripleCount"));
                    metadata.put("classCount", meta.get("classCount"));
                    sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_COMPLETED,
                            "COMPLETED", "Import completed successfully", filename, metadata);
                } catch (Exception e) {
                    log.error("Metadata indexing failed for {}", projectId, e);
                    metadataService.writeStatus(projectId, ProjectStatus.error(filename, e.getMessage()));
                    sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_FAILED,
                            "ERROR", "Indexing failed: " + e.getMessage(), filename, Map.of("error", e.getMessage()));
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

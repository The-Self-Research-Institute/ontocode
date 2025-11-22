package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.model.collaboration.ImportStatusMessage;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    public ProjectImportService(@Qualifier("owlParsingExecutor") Executor owlParsingExecutor,
                                GraphDBDatasetService datasetService,
                                OntologyIndexService indexService,
                                ProjectMetadataService metadataService,
                                StorageManager storageManager,
                                SimpMessagingTemplate messagingTemplate,
                                ImportQueueManager queueManager) {
        this.owlParsingExecutor = owlParsingExecutor;
        this.datasetService = datasetService;
        this.indexService = indexService;
        this.metadataService = metadataService;
        this.storageManager = storageManager;
        this.messagingTemplate = messagingTemplate;
        this.queueManager = queueManager;
    }

    public void submitImport(String projectId, Path owlFile) {
        submitImport(projectId, owlFile, null);
    }

    public void submitImport(String projectId, Path owlFile, String ownerEmail) {
        String filename = owlFile.getFileName().toString();

        log.info("[Import] Submitting import for project {}: {}", projectId, filename);

        // Add to queue
        queueManager.enqueue(projectId, filename, ownerEmail, owlFile);

        // Try to process next item in queue
        processNextInQueue();
    }

    private void processNextInQueue() {
        if (!queueManager.canProcess()) {
            log.debug("[Import] Cannot process - max concurrent imports reached");
            return;
        }

        owlParsingExecutor.execute(() -> {
            self.research.ontology.owlEditor.model.ImportQueueItem item = queueManager.dequeue();
            if (item == null) {
                return; // No items in queue
            }

            long startTime = System.currentTimeMillis();
            try {
                runImport(item.getProjectId(), item.getOwlFile());
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

    private void runImport(String projectId, Path owlFile) {
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
            log.info("[Import {}] Detected RDF format: {}", projectId, format);

            // Clear dataset
            stage = "clear-dataset";
            log.info("[Import {}] Clearing dataset", projectId);
            long clearStart = System.nanoTime();
            datasetService.clearDataset(projectId);
            log.info("[Import {}] Dataset cleared in {} ms", projectId, elapsedMillis(clearStart));

            // Load data with progress updates
            stage = "bulk-load";
            log.info("[Import {}] Loading data into GraphDB", projectId);
            long fileSize = Files.size(owlFile);
            log.info("File size: {} bytes ({} MB)", fileSize, fileSize / (1024 * 1024));

            // Start a thread to send periodic progress updates
            AtomicBoolean loadingComplete = new AtomicBoolean(false);
            AtomicInteger progressCounter = new AtomicInteger(10);

            Thread progressThread = new Thread(() -> {
                try {
                    while (!loadingComplete.get() && progressCounter.get() < 90) {
                        Thread.sleep(5000); // Update every 5 seconds
                        if (!loadingComplete.get()) {
                            int progress = progressCounter.addAndGet(10);
                            progress = Math.min(progress, 90);

                            Map<String, Object> progressMeta = new HashMap<>();
                            progressMeta.put("progress", progress);
                            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                                    "PROCESSING", String.format("Importing... (%d%%)", progress), filename, progressMeta);
                            log.info("Import progress for {}: {}%", projectId, progress);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            progressThread.setDaemon(true);
            progressThread.start();

            long bulkLoadStart = System.nanoTime();
            
            // Notify user that we're starting GraphDB bulk load (this may take time for large files)
            Map<String, Object> bulkLoadStartMeta = new HashMap<>();
            bulkLoadStartMeta.put("progress", 60);
            bulkLoadStartMeta.put("stage", "graphdb-loading");
            bulkLoadStartMeta.put("message", "Loading data into GraphDB (this may take several minutes for large files)...");
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                    "PROCESSING", "Loading into GraphDB...", filename, bulkLoadStartMeta);
            
            try (InputStream in = Files.newInputStream(owlFile)) {
                datasetService.bulkLoadChunked(projectId, in, format);
            } finally {
                loadingComplete.set(true);
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
            stage = "metadata";
            log.info("[Import {}] Computing metadata", projectId);
            long metadataStart = System.nanoTime();
            Map<String, Object> meta = indexService.computeMetadata(projectId);
            log.info("[Import {}] Metadata computed in {} ms", projectId, elapsedMillis(metadataStart));
            metadataService.writeMeta(projectId, meta);

            // Update status
            stage = "complete";
            metadataService.writeStatus(projectId, ProjectStatus.completed(filename));
            log.info("Completed import for project {} in {} ms", projectId, elapsedMillis(importStart));

            // Notify: Import completed
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("tripleCount", meta.get("tripleCount"));
            metadata.put("classCount", meta.get("classes"));
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_COMPLETED,
                    "COMPLETED", "Import completed successfully", filename, metadata);

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
        String fileName = file.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".ttl") || fileName.endsWith(".turtle")) {
            return RDFFormat.TURTLE;
        } else if (fileName.endsWith(".nt") || fileName.endsWith(".ntriples")) {
            return RDFFormat.NTRIPLES;
        } else if (fileName.endsWith(".jsonld")) {
            return RDFFormat.JSONLD;
        } else if (fileName.endsWith(".n3")) {
            return RDFFormat.N3;
        }
        return RDFFormat.RDFXML; // default
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
}

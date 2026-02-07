package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.ImportOptions;
import self.research.ontology.owlEditor.model.ImportQueueItem;
import self.research.ontology.owlEditor.model.collaboration.QueueStatusMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages import queue with notifications and statistics
 */
@Slf4j
@Service
public class ImportQueueManager {

    private final SimpMessagingTemplate messagingTemplate;
    private final ImportTimeEstimator estimator;
    private final ProjectMetadataService metadataService;
    private final LinkedList<ImportQueueItem> queue = new LinkedList<>();
    private final Map<String, ImportQueueItem> activeImports = new ConcurrentHashMap<>();

    // Configuration
    private static final int MAX_CONCURRENT_IMPORTS = 1; // Process one at a time to avoid GraphDB conflicts
    private static final int MAX_RETRIES = 3; // Maximum retry attempts for failed imports
    private static final long RETRY_DELAY_MS = 10 * 1000; // 10 seconds delay before retry

    public ImportQueueManager(SimpMessagingTemplate messagingTemplate,
                              ImportTimeEstimator estimator,
                              ProjectMetadataService metadataService) {
        this.messagingTemplate = messagingTemplate;
        this.estimator = estimator;
        this.metadataService = metadataService;
    }

    /**
     * Add a project to the import queue
     */
    public synchronized ImportQueueItem enqueue(String projectId, String filename, String ownerEmail, Path owlFile) {
        return enqueue(projectId, filename, ownerEmail, owlFile, ImportOptions.defaults());
    }

    public synchronized ImportQueueItem enqueue(String projectId,
                                               String filename,
                                               String ownerEmail,
                                               Path owlFile,
                                               ImportOptions options) {
        // Check if already queued or processing
        ImportQueueItem existing = findInQueue(projectId);
        if (existing != null) {
            log.warn("[Queue] Project {} already in queue at position {}", projectId, existing.getQueuePosition());
            notifyQueueStatus(projectId);
            return existing;
        }

        if (activeImports.containsKey(projectId)) {
            log.warn("[Queue] Project {} is currently being processed", projectId);
            return activeImports.get(projectId);
        }

        ImportOptions resolvedOptions = options != null ? options : ImportOptions.defaults();
        long fileSizeBytes = resolveFileSize(owlFile);
        Integer classCount = readMetaCount(projectId, "classCount", "classes");
        Integer annotationCount = readMetaCount(projectId, "annotationPropertyCount", "annotationProperties");
        long estimatedDurationMs = estimator.estimateDurationMs(fileSizeBytes, classCount, annotationCount);

        // Create queue item
        ImportQueueItem item = ImportQueueItem.builder()
                .projectId(projectId)
                .filename(filename)
                .ownerEmail(ownerEmail)
                .owlFile(owlFile)
                .importMode(resolvedOptions.getMode())
                .partitionStrategy(resolvedOptions.getPartitionStrategy())
                .fileSizeBytes(fileSizeBytes)
                .classCount(classCount)
                .annotationCount(annotationCount)
                .queuedAt(Instant.now())
                .status(ImportQueueItem.ImportStatus.QUEUED)
                .estimatedDurationMs(estimatedDurationMs)
                .queuePosition(queue.size() + 1)
                .retryCount(0)
                .maxRetries(MAX_RETRIES)
                .build();

        queue.addLast(item);

        log.info("[Queue] Added project {} to queue at position {} (total in queue: {})",
                projectId, item.getQueuePosition(), queue.size());

        // Notify user about queue position
        notifyQueueStatus(projectId);

        // Broadcast queue stats to all users
        broadcastQueueStats();

        return item;
    }

    /**
     * Get next item from queue and mark as processing
     */
    public synchronized ImportQueueItem dequeue() {
        if (activeImports.size() >= MAX_CONCURRENT_IMPORTS || queue.isEmpty()) {
            return null;
        }

        ImportQueueItem item = queue.removeFirst();
        item.setStatus(ImportQueueItem.ImportStatus.PROCESSING);
        item.setStartedAt(Instant.now());
        item.setQueuePosition(0);

        activeImports.put(item.getProjectId(), item);

        log.info("[Queue] Started processing project {} (waited {} ms, queue size now: {})",
                item.getProjectId(), item.getWaitTimeMs(), queue.size());

        // Update queue positions for remaining items
        updateQueuePositions();

        // Notify that processing started
        notifyProcessingStarted(item);

        // Broadcast updated queue stats
        broadcastQueueStats();

        return item;
    }

    /**
     * Mark import as completed
     */
    public synchronized void markCompleted(String projectId, long durationMs) {
        ImportQueueItem item = activeImports.remove(projectId);
        if (item != null) {
            item.setStatus(ImportQueueItem.ImportStatus.COMPLETED);

            log.info("[Queue] Completed project {} in {} ms (avg: {} ms)",
                    projectId, durationMs, getAverageProcessingTime());
        }

        refreshQueuedEstimates();
        // Update queue positions
        updateQueuePositions();

        // Broadcast updated stats
        broadcastQueueStats();
    }

    /**
     * Mark import as failed with option to retry
     */
    public synchronized void markFailed(String projectId) {
        markFailed(projectId, "Unknown error", false);
    }

    /**
     * Mark import as failed with retry logic
     * @param shouldRetry true if this is a retryable error (e.g., connection timeout)
     */
    public synchronized void markFailed(String projectId, String reason, boolean shouldRetry) {
        ImportQueueItem item = activeImports.remove(projectId);
        if (item == null) {
            return;
        }

        item.setFailureReason(reason);
        item.setLastAttemptAt(Instant.now());

        // Check if should retry
        if (shouldRetry && item.getRetryCount() < item.getMaxRetries()) {
            item.setRetryCount(item.getRetryCount() + 1);
            item.setStatus(ImportQueueItem.ImportStatus.RETRYING);
            
            log.warn("[Queue] Failed project {} (attempt {}/{}): {}. Will retry in {} seconds",
                    projectId, item.getRetryCount(), item.getMaxRetries(), reason, RETRY_DELAY_MS / 1000);
            
            // Re-queue with delay
            scheduleRetry(item);
            
            // Notify user about retry
            notifyRetrying(item);
        } else {
            item.setStatus(ImportQueueItem.ImportStatus.FAILED);
            log.error("[Queue] Failed project {} permanently ({}): {}",
                    projectId, 
                    item.getRetryCount() >= item.getMaxRetries() ? "max retries exceeded" : "non-retryable error",
                    reason);
            
            // Notify user about failure
            notifyFailed(item);
        }

        // Update queue positions
        updateQueuePositions();

        // Broadcast updated stats
        broadcastQueueStats();
    }

    /**
     * Schedule a retry for a failed import
     */
    private void scheduleRetry(ImportQueueItem item) {
        new Thread(() -> {
            try {
                Thread.sleep(RETRY_DELAY_MS);
                synchronized (this) {
                    item.setStatus(ImportQueueItem.ImportStatus.QUEUED);
                    item.setQueuePosition(queue.size() + 1);
                    queue.addLast(item);
                    log.info("[Queue] Re-queued project {} for retry (attempt {}/{})",
                            item.getProjectId(), item.getRetryCount(), item.getMaxRetries());
                    notifyQueueStatus(item.getProjectId());
                    broadcastQueueStats();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[Queue] Retry scheduling interrupted for project {}", item.getProjectId());
            }
        }).start();
    }

    /**
     * Get queue status for a specific project
     */
    public synchronized ImportQueueItem getStatus(String projectId) {
        // Check if processing
        ImportQueueItem active = activeImports.get(projectId);
        if (active != null) {
            return active;
        }

        // Check if in queue
        return findInQueue(projectId);
    }

    public synchronized void updateItemMetrics(String projectId,
                                               Long fileSizeBytes,
                                               Integer classCount,
                                               Integer annotationCount) {
        ImportQueueItem item = findInQueue(projectId);
        if (item == null) {
            return;
        }

        if (fileSizeBytes != null && fileSizeBytes > 0) {
            item.setFileSizeBytes(fileSizeBytes);
        }
        if (classCount != null) {
            item.setClassCount(classCount);
        }
        if (annotationCount != null) {
            item.setAnnotationCount(annotationCount);
        }

        long updatedEstimate = estimator.estimateDurationMs(
                item.getFileSizeBytes(),
                item.getClassCount(),
                item.getAnnotationCount());
        item.setEstimatedDurationMs(updatedEstimate);
        notifyQueueStatus(projectId);
        broadcastQueueStats();
    }

    /**
     * Estimate wait time for a given project in the queue.
     */
    public synchronized long getEstimatedWaitTimeMs(String projectId) {
        ImportQueueItem item = getStatus(projectId);
        if (item == null) {
            return 0;
        }
        return calculateEstimatedWaitTime(item);
    }

    /**
     * Get overall queue statistics
     */
    public synchronized QueueStatusMessage.QueueStats getQueueStats() {
        long activeRemainingMs = activeImports.values().stream()
                .mapToLong(this::estimateRemainingTimeMs)
                .sum();

        long runningTotal = activeRemainingMs;
        List<QueueStatusMessage.QueuedProject> queuedProjects = new ArrayList<>();
        for (ImportQueueItem item : queue) {
            queuedProjects.add(QueueStatusMessage.QueuedProject.builder()
                    .projectId(item.getProjectId())
                    .filename(item.getFilename())
                    .position(item.getQueuePosition())
                    .estimatedWaitTimeMs(runningTotal)
                    .queuedSinceMs(item.getWaitTimeMs())
                    .build());

            runningTotal += estimateDurationMsForItem(item);
        }

        return QueueStatusMessage.QueueStats.builder()
                .activeImports(activeImports.size())
                .queuedImports(queue.size())
                .averageProcessingTimeMs(getAverageProcessingTime())
                .queue(queuedProjects)
                .build();
    }

    /**
     * Check if queue can accept more imports
     */
    public synchronized boolean canProcess() {
        return activeImports.size() < MAX_CONCURRENT_IMPORTS;
    }

    // Private helper methods

    private ImportQueueItem findInQueue(String projectId) {
        return queue.stream()
                .filter(item -> item.getProjectId().equals(projectId))
                .findFirst()
                .orElse(null);
    }

    private long calculateEstimatedWaitTime(ImportQueueItem target) {
        if (target == null) {
            return 0;
        }
        if (target.getStatus() == ImportQueueItem.ImportStatus.PROCESSING) {
            return 0;
        }

        long waitTimeMs = activeImports.values().stream()
                .mapToLong(this::estimateRemainingTimeMs)
                .sum();

        for (ImportQueueItem item : queue) {
            if (item.getProjectId().equals(target.getProjectId())) {
                break;
            }
            waitTimeMs += estimateDurationMsForItem(item);
        }

        return waitTimeMs;
    }

    private long estimateRemainingTimeMs(ImportQueueItem item) {
        long estimate = estimateDurationMsForItem(item);
        if (estimate <= 0) {
            return 0;
        }

        if (item.getStatus() == ImportQueueItem.ImportStatus.PROCESSING && item.getStartedAt() != null) {
            long elapsedMs = Instant.now().toEpochMilli() - item.getStartedAt().toEpochMilli();
            return Math.max(0, estimate - elapsedMs);
        }

        return estimate;
    }

    private long estimateDurationMsForItem(ImportQueueItem item) {
        if (item == null) {
            return 0;
        }
        long estimate = item.getEstimatedDurationMs();
        return estimate > 0 ? estimate : estimator.getAverageDurationMs();
    }

    private void refreshQueuedEstimates() {
        for (ImportQueueItem item : queue) {
            long updatedEstimate = estimator.estimateDurationMs(
                    item.getFileSizeBytes(),
                    item.getClassCount(),
                    item.getAnnotationCount());
            item.setEstimatedDurationMs(updatedEstimate);
        }
    }

    private void updateQueuePositions() {
        int position = 1;
        for (ImportQueueItem item : queue) {
            item.setQueuePosition(position++);
        }

        // Notify all queued projects about position changes
        for (ImportQueueItem item : queue) {
            notifyQueueStatus(item.getProjectId());
        }
    }

    private long resolveFileSize(Path owlFile) {
        try {
            return Files.size(owlFile);
        } catch (IOException e) {
            log.warn("[Queue] Failed to read file size for {}: {}", owlFile, e.getMessage());
            return 0;
        }
    }

    private Integer readMetaCount(String projectId, String primaryKey, String fallbackKey) {
        return metadataService.readMeta(projectId)
                .map(meta -> extractCount(meta, primaryKey, fallbackKey))
                .orElse(null);
    }

    private Integer extractCount(Map<String, Object> meta, String primaryKey, String fallbackKey) {
        Integer value = toInteger(meta.get(primaryKey));
        if (value != null) {
            return value;
        }

        Object counts = meta.get("counts");
        if (counts instanceof Map<?, ?> countMap) {
            return toInteger(countMap.get(fallbackKey));
        }

        return null;
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

    private long getAverageProcessingTime() {
        return estimator.getAverageDurationMs();
    }

    private void notifyQueueStatus(String projectId) {
        ImportQueueItem item = getStatus(projectId);
        if (item == null) {
            return;
        }

        long estimatedWaitTimeMs = calculateEstimatedWaitTime(item);
        QueueStatusMessage message = QueueStatusMessage.builder()
                .projectId(projectId)
                .queuePosition(item.getQueuePosition())
                .totalInQueue(queue.size())
                .estimatedWaitTimeMs(estimatedWaitTimeMs)
                .status(item.getStatus().name())
                .message(buildQueueMessage(item))
                .timestamp(System.currentTimeMillis())
                .build();

        try {
            messagingTemplate.convertAndSend("/topic/queue/" + projectId, message);
            log.debug("[Queue] Sent queue status for project {}: position {}/{}",
                    projectId, item.getQueuePosition(), queue.size());
        } catch (Exception e) {
            log.warn("[Queue] Failed to send queue status notification: {}", e.getMessage());
        }
    }

    private void notifyProcessingStarted(ImportQueueItem item) {
        QueueStatusMessage message = QueueStatusMessage.builder()
                .projectId(item.getProjectId())
                .queuePosition(0)
                .totalInQueue(queue.size())
                .estimatedWaitTimeMs(0)
                .status("PROCESSING")
                .message(String.format("Processing started for '%s' (waited %d seconds in queue)",
                        item.getFilename(), item.getWaitTimeMs() / 1000))
                .timestamp(System.currentTimeMillis())
                .build();

        try {
            messagingTemplate.convertAndSend("/topic/queue/" + item.getProjectId(), message);
        } catch (Exception e) {
            log.warn("[Queue] Failed to send processing started notification: {}", e.getMessage());
        }
    }

    private void broadcastQueueStats() {
        QueueStatusMessage.QueueStats stats = getQueueStats();

        QueueStatusMessage message = QueueStatusMessage.builder()
                .totalInQueue(stats.getQueuedImports())
                .status("QUEUE_STATS")
                .message(String.format("%d active, %d queued", stats.getActiveImports(), stats.getQueuedImports()))
                .timestamp(System.currentTimeMillis())
                .queueStats(stats)
                .build();

        try {
            messagingTemplate.convertAndSend("/topic/queue/stats", message);
        } catch (Exception e) {
            log.warn("[Queue] Failed to broadcast queue stats: {}", e.getMessage());
        }
    }

    private void notifyRetrying(ImportQueueItem item) {
        QueueStatusMessage message = QueueStatusMessage.builder()
                .projectId(item.getProjectId())
                .queuePosition(item.getQueuePosition())
                .totalInQueue(queue.size())
                .status("RETRYING")
                .message(String.format("Import failed: %s. Retrying in 10 seconds (attempt %d/%d)",
                        item.getFailureReason(), item.getRetryCount(), item.getMaxRetries()))
                .timestamp(System.currentTimeMillis())
                .build();

        try {
            messagingTemplate.convertAndSend("/topic/queue/" + item.getProjectId(), message);
        } catch (Exception e) {
            log.warn("[Queue] Failed to send retry notification: {}", e.getMessage());
        }
    }

    private void notifyFailed(ImportQueueItem item) {
        QueueStatusMessage message = QueueStatusMessage.builder()
                .projectId(item.getProjectId())
                .queuePosition(0)
                .totalInQueue(queue.size())
                .status("FAILED")
                .message(String.format("Import failed permanently: %s %s",
                        item.getFailureReason(),
                        item.getRetryCount() >= item.getMaxRetries() ? "(maximum retries exceeded)" : ""))
                .timestamp(System.currentTimeMillis())
                .build();

        try {
            messagingTemplate.convertAndSend("/topic/queue/" + item.getProjectId(), message);
        } catch (Exception e) {
            log.warn("[Queue] Failed to send failure notification: {}", e.getMessage());
        }
    }

    private String buildQueueMessage(ImportQueueItem item) {
        if (item.getStatus() == ImportQueueItem.ImportStatus.PROCESSING) {
            return String.format("Processing '%s'...", item.getFilename());
        }

        if (item.getQueuePosition() == 1) {
            return String.format("'%s' is next in queue (estimated wait: %d seconds)",
                    item.getFilename(),
                    calculateEstimatedWaitTime(item) / 1000);
        }

        return String.format("'%s' is at position %d in queue (%d ahead, estimated wait: %d seconds)",
                item.getFilename(),
                item.getQueuePosition(),
                item.getQueuePosition() - 1,
                calculateEstimatedWaitTime(item) / 1000);
    }
}

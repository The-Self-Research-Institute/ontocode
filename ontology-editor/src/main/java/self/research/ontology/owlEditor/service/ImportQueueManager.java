package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.ImportOptions;
import self.research.ontology.owlEditor.model.ImportQueueItem;
import self.research.ontology.owlEditor.model.collaboration.QueueStatusMessage;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ImportQueueManager {

    private final SimpMessagingTemplate messagingTemplate;
    private final ImportTimeEstimator estimator;
    private final ProjectMetadataService metadataService;
    private final LinkedList<ImportQueueItem> queue = new LinkedList<>();
    private final Map<String, ImportQueueItem> activeImports = new ConcurrentHashMap<>();
    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "import-retry-scheduler");
                t.setDaemon(true);
                return t;
            });

    @Value("${ontocode.import.max-concurrent:1}")
    private int maxConcurrentImports;

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 10 * 1000;

    private static final long MIN_PROCESSING_TIMEOUT_MS = 10 * 60 * 1000;

    private static final double PROCESSING_TIMEOUT_MULTIPLIER = 2.0;

    private static final long MAX_WAIT_TIME_MS = 20 * 60 * 1000;

    public ImportQueueManager(SimpMessagingTemplate messagingTemplate,
                              ImportTimeEstimator estimator,
                              ProjectMetadataService metadataService) {
        this.messagingTemplate = messagingTemplate;
        this.estimator = estimator;
        this.metadataService = metadataService;
    }

    public synchronized ImportQueueItem enqueue(String projectId, String filename, String ownerEmail, Path owlFile) {
        return enqueue(projectId, filename, ownerEmail, owlFile, ImportOptions.defaults());
    }

    public synchronized ImportQueueItem enqueue(String projectId,
                                               String filename,
                                               String ownerEmail,
                                               Path owlFile,
                                               ImportOptions options) {
        long enqueueStart = System.nanoTime();

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
        updateQueuePositions();

        log.info("[Queue] Added project {} to queue at position {} (total in queue: {}) in {} ms",
                projectId, item.getQueuePosition(), queue.size(), (System.nanoTime() - enqueueStart) / 1_000_000);

        notifyQueueStatus(projectId);

        broadcastQueueStats();

        return item;
    }

    public synchronized ImportQueueItem dequeue() {
        long dequeueStart = System.nanoTime();
        if (activeImports.size() >= maxConcurrentImports || queue.isEmpty()) {
            log.debug("[Queue] Dequeue skipped (active={}, max={}, queued={})",
                    activeImports.size(), maxConcurrentImports, queue.size());
            return null;
        }

        ImportQueueItem item = null;
        var it = queue.iterator();
        while (it.hasNext()) {
            ImportQueueItem candidate = it.next();
            if (!activeImports.containsKey(candidate.getProjectId())) {
                item = candidate;
                it.remove();
                break;
            }
        }
        if (item == null) {
            log.debug("[Queue] Dequeue found no eligible item (queued={}, activeProjectIds={})",
                    queue.size(), activeImports.keySet());
            return null;
        }

        item.setStatus(ImportQueueItem.ImportStatus.PROCESSING);
        item.setStartedAt(Instant.now());
        item.setQueuePosition(0);

        activeImports.put(item.getProjectId(), item);

        log.info("[Queue] Started processing project {} (waited {} ms in queue, dequeue took {} ms, queue size now: {})",
                item.getProjectId(), item.getWaitTimeMs(), (System.nanoTime() - dequeueStart) / 1_000_000, queue.size());

        updateQueuePositions();

        notifyProcessingStarted(item);

        broadcastQueueStats();

        return item;
    }

    public synchronized void markCompleted(String projectId, long durationMs) {
        ImportQueueItem item = activeImports.remove(projectId);
        if (item != null) {
            item.setStatus(ImportQueueItem.ImportStatus.COMPLETED);

            log.info("[Queue] Completed project {} in {} ms (avg: {} ms)",
                    projectId, durationMs, getAverageProcessingTime());
            } else {
                log.warn("[Queue] markCompleted called for {} but it was not in activeImports", projectId);
        }

        refreshQueuedEstimates();

        updateQueuePositions();

        broadcastQueueStats();
    }

    public synchronized void markFailed(String projectId) {
        markFailed(projectId, "Unknown error", false);
    }

    public synchronized void markFailed(String projectId, String reason, boolean shouldRetry) {
        ImportQueueItem item = activeImports.remove(projectId);
        if (item == null) {
            log.warn("[Queue] markFailed called for {} but it was not in activeImports. reason={}",
                    projectId, reason);
            return;
        }

        item.setFailureReason(reason);
        item.setLastAttemptAt(Instant.now());

        if (shouldRetry && item.getRetryCount() < item.getMaxRetries()) {
            item.setRetryCount(item.getRetryCount() + 1);
            item.setStatus(ImportQueueItem.ImportStatus.RETRYING);

            log.warn("[Queue] Failed project {} (attempt {}/{}): {}. Will retry in {} seconds",
                    projectId, item.getRetryCount(), item.getMaxRetries(), reason, RETRY_DELAY_MS / 1000);

            scheduleRetry(item);

            notifyRetrying(item);
        } else {
            item.setStatus(ImportQueueItem.ImportStatus.FAILED);
            log.error("[Queue] Failed project {} permanently ({}): {}",
                    projectId,
                    item.getRetryCount() >= item.getMaxRetries() ? "max retries exceeded" : "non-retryable error",
                    reason);

            notifyFailed(item);
        }

        updateQueuePositions();

        broadcastQueueStats();
    }

    private void scheduleRetry(ImportQueueItem item) {
        retryScheduler.schedule(() -> {
            synchronized (this) {
                item.setStatus(ImportQueueItem.ImportStatus.QUEUED);
                item.setQueuePosition(queue.size() + 1);
                queue.addLast(item);
                log.info("[Queue] Re-queued project {} for retry (attempt {}/{})",
                        item.getProjectId(), item.getRetryCount(), item.getMaxRetries());
                notifyQueueStatus(item.getProjectId());
                broadcastQueueStats();
            }
        }, RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        retryScheduler.shutdownNow();
    }

    public synchronized ImportQueueItem getStatus(String projectId) {

        ImportQueueItem active = activeImports.get(projectId);
        if (active != null) {
            return active;
        }

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

    public synchronized long getEstimatedWaitTimeMs(String projectId) {
        ImportQueueItem item = getStatus(projectId);
        if (item == null) {
            return 0;
        }
        return calculateEstimatedWaitTime(item);
    }

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
                .activeProjectIds(new java.util.ArrayList<>(activeImports.keySet()))
                .build();
    }

    public synchronized boolean canProcess() {
        return activeImports.size() < maxConcurrentImports;
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty() && activeImports.isEmpty();
    }

    public synchronized List<ImportQueueItem> expireStuckProcessing(long maxProcessingMs) {
        if (maxProcessingMs <= 0 || activeImports.isEmpty()) {
            return List.of();
        }

        log.info("[Queue] Scanning active imports for timeout (thresholdMs={}, active={}, queued={})",
                maxProcessingMs, activeImports.size(), queue.size());

        long nowMs = Instant.now().toEpochMilli();
        List<ImportQueueItem> expired = new ArrayList<>();

        for (ImportQueueItem item : new ArrayList<>(activeImports.values())) {
            if (item.getStartedAt() == null) {
                continue;
            }

            long elapsedMs = nowMs - item.getStartedAt().toEpochMilli();
            long itemTimeoutMs = calculateTimeoutMsForItem(item, maxProcessingMs);
            if (elapsedMs < itemTimeoutMs) {
                continue;
            }

            activeImports.remove(item.getProjectId());
            item.setStatus(ImportQueueItem.ImportStatus.FAILED);
                long estimateMs = Math.max(0, estimateDurationMsForItem(item));
            item.setFailureReason(String.format(
                "Import timed out after %d minutes while processing (timeout=%d minutes, estimate=%d minutes)",
                    Math.max(1, elapsedMs / 60_000),
                    Math.max(1, itemTimeoutMs / 60_000),
                    Math.max(1, estimateMs / 60_000)));
            expired.add(item);

            double fileSizeMb = item.getFileSizeBytes() > 0
                ? (item.getFileSizeBytes() / (1024.0 * 1024.0))
                : 0.0;
            log.error("[Queue] Expired stuck import for project {} after {} ms (timeoutMs={}, estimateMs={}, fileSizeMb={})",
                item.getProjectId(), elapsedMs, itemTimeoutMs, estimateMs, String.format("%.2f", fileSizeMb));
            notifyFailed(item);
        }

        if (!expired.isEmpty()) {
            log.warn("[Queue] Expired {} stuck import(s). Active now: {}, queued now: {}",
                    expired.size(), activeImports.size(), queue.size());
            updateQueuePositions();
            broadcastQueueStats();
        }

        return expired;
    }

    private long calculateTimeoutMsForItem(ImportQueueItem item, long configuredTimeoutMs) {
        long estimateMs = Math.max(0, estimateDurationMsForItem(item));
        long estimateBasedTimeoutMs = estimateMs > 0
                ? (long) Math.ceil(estimateMs * PROCESSING_TIMEOUT_MULTIPLIER)
                : 0;

        return Math.max(
                Math.max(configuredTimeoutMs, MIN_PROCESSING_TIMEOUT_MS),
                estimateBasedTimeoutMs);
    }

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

        return Math.min(waitTimeMs, MAX_WAIT_TIME_MS);
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

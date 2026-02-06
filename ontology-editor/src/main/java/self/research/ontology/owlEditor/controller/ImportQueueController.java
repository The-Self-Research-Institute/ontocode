package self.research.ontology.owlEditor.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.model.ImportQueueItem;
import self.research.ontology.owlEditor.model.collaboration.QueueStatusMessage;
import self.research.ontology.owlEditor.service.ImportQueueManager;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API for import queue management
 */
@Slf4j
@RestController
@RequestMapping("/api/import-queue")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class ImportQueueController {

    private final ImportQueueManager queueManager;

    public ImportQueueController(ImportQueueManager queueManager) {
        this.queueManager = queueManager;
    }

    /**
     * Get queue status for a specific project
     */
    @GetMapping("/status/{projectId}")
    public ResponseEntity<Map<String, Object>> getQueueStatus(@PathVariable String projectId) {
        try {
            ImportQueueItem item = queueManager.getStatus(projectId);

            if (item == null) {
                return ResponseEntity.ok(Map.of(
                        "status", "NOT_IN_QUEUE",
                        "message", "Project not in queue"
                ));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("projectId", item.getProjectId());
            response.put("filename", item.getFilename());
            response.put("status", item.getStatus().name());
            response.put("queuePosition", item.getQueuePosition());
            response.put("queuedAt", item.getQueuedAt().toEpochMilli());

            if (item.getStartedAt() != null) {
                response.put("startedAt", item.getStartedAt().toEpochMilli());
            }

            response.put("waitTimeMs", item.getWaitTimeMs());
            long estimatedWaitTimeMs = queueManager.getEstimatedWaitTimeMs(projectId);
            response.put("estimatedWaitTimeMs", estimatedWaitTimeMs);
            response.put("estimatedWaitMinutes", estimatedWaitTimeMs / 60000);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to get queue status for project {}", projectId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get queue status: " + e.getMessage()));
        }
    }

    /**
     * Get overall queue statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<QueueStatusMessage.QueueStats> getQueueStats() {
        try {
            QueueStatusMessage.QueueStats stats = queueManager.getQueueStats();
            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("Failed to get queue stats", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get queue position for a project
     */
    @GetMapping("/position/{projectId}")
    public ResponseEntity<Map<String, Object>> getQueuePosition(@PathVariable String projectId) {
        try {
            ImportQueueItem item = queueManager.getStatus(projectId);

            if (item == null) {
                return ResponseEntity.ok(Map.of(
                        "inQueue", false,
                        "message", "Not in queue"
                ));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("inQueue", true);
            response.put("position", item.getQueuePosition());
            response.put("status", item.getStatus().name());

            if (item.getQueuePosition() > 0) {
                QueueStatusMessage.QueueStats stats = queueManager.getQueueStats();
                response.put("totalInQueue", stats.getQueuedImports());
                response.put("filesAhead", item.getQueuePosition() - 1);
                long estimatedWait = queueManager.getEstimatedWaitTimeMs(projectId);
                response.put("estimatedWaitMs", estimatedWait);
                response.put("estimatedWaitMinutes", estimatedWait / 60000);

                response.put("message", String.format(
                        "Position #%d in queue (%d files ahead, estimated wait: %d minutes)",
                        item.getQueuePosition(),
                        item.getQueuePosition() - 1,
                        estimatedWait / 60000
                ));
            } else {
                response.put("message", "Processing now");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to get queue position for project {}", projectId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get queue position: " + e.getMessage()));
        }
    }
}

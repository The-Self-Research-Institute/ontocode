package self.research.ontology.owlEditor.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal REST endpoint that broadcasts workspace-level events over STOMP.
 * Called by the auth service (e.g. after project deletion) to notify connected clients.
 * Not exposed to end-users — auth service calls this service-to-service.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/workspace")
@RequiredArgsConstructor
public class WorkspaceEventController {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Broadcast an event to all clients subscribed to a workspace topic.
     * Body: { type: "PROJECT_DELETED", projectId: "...", deletedBy: "...", workspaceId: "..." }
     */
    @PostMapping("/{workspaceId}/event")
    public ResponseEntity<?> broadcastWorkspaceEvent(
            @PathVariable String workspaceId,
            @RequestBody Map<String, Object> event) {
        try {
            event.put("workspaceId", workspaceId);
            event.put("timestamp", System.currentTimeMillis());
            String topic = "/topic/workspace/" + workspaceId;
            messagingTemplate.convertAndSend(topic, event);
            log.info("[WorkspaceEvent] Broadcast {} to {}", event.get("type"), topic);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            log.error("[WorkspaceEvent] Failed to broadcast event: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}

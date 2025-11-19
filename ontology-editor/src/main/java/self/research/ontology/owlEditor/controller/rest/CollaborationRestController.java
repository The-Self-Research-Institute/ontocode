package self.research.ontology.owlEditor.controller.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.model.collaboration.EditOperation;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.List;
import java.util.Map;

/**
 * REST API endpoints for collaborative editing features.
 */
@RestController
@RequestMapping("/api/collaboration")
@RequiredArgsConstructor
public class CollaborationRestController {

    private final CollaborativeEditService collaborativeEditService;

    /**
     * Get active users in a project.
     */
    @GetMapping("/users/{projectId}")
    public ResponseEntity<List<Map<String, Object>>> getActiveUsers(@PathVariable String projectId) {
        List<Map<String, Object>> users = collaborativeEditService.getActiveUsers(projectId);
        return ResponseEntity.ok(users);
    }

    /**
     * Get operation history for a project.
     */
    @GetMapping("/history/{projectId}")
    public ResponseEntity<List<EditOperation>> getHistory(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "100") int limit) {
        List<EditOperation> history = collaborativeEditService.getHistory(projectId, limit);
        return ResponseEntity.ok(history);
    }

    /**
     * Force release a lock (admin only).
     */
    @DeleteMapping("/locks/{projectId}/{nodeId}")
    public ResponseEntity<Void> forceReleaseLock(
            @PathVariable String projectId,
            @PathVariable String nodeId,
            @RequestParam String adminUserId) {
        // TODO: Add admin permission check
        collaborativeEditService.releaseLock(projectId, nodeId, adminUserId, "admin");
        return ResponseEntity.ok().build();
    }
}

package self.research.ontology.owlEditor.controller.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.model.collaboration.EditOperation;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/collaboration")
@RequiredArgsConstructor
public class CollaborationRestController {

    private final CollaborativeEditService collaborativeEditService;

    @GetMapping("/users/{projectId}")
    public ResponseEntity<List<Map<String, Object>>> getActiveUsers(@PathVariable String projectId) {
        List<Map<String, Object>> users = collaborativeEditService.getActiveUsers(projectId);
        return ResponseEntity.ok(users);
    }

    @GetMapping("/history/{projectId}")
    public ResponseEntity<List<EditOperation>> getHistory(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "100") int limit) {
        List<EditOperation> history = collaborativeEditService.getHistory(projectId, limit);
        return ResponseEntity.ok(history);
    }

    @DeleteMapping("/locks/{projectId}/{nodeId}")
    public ResponseEntity<Void> forceReleaseLock(
            @PathVariable String projectId,
            @PathVariable String nodeId,
            @RequestParam String adminUserId) {

        collaborativeEditService.releaseLock(projectId, nodeId, adminUserId, "admin");
        return ResponseEntity.ok().build();
    }
}

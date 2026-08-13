package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.service.HierarchyIndexService;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/hierarchy-worker")
@CrossOrigin
public class HierarchyWorkerController {

    private final HierarchyIndexService hierarchyIndexService;

    public HierarchyWorkerController(HierarchyIndexService hierarchyIndexService) {
        this.hierarchyIndexService = hierarchyIndexService;
    }

    @PostMapping("/build/{projectId:.+}")
    public ResponseEntity<?> build(@PathVariable String projectId) {
        if (!hierarchyIndexService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Hierarchy snapshots disabled (ontocode.hierarchy.snapshot.enabled=false)"));
        }
        hierarchyIndexService.scheduleBuild(projectId);
        return ResponseEntity.accepted().body(Map.of(
                "success", true,
                "message", "Hierarchy index build scheduled",
                "projectId", projectId,
                "revision", Instant.now().toString()));
    }

    @GetMapping("/status/{projectId:.+}")
    public ResponseEntity<?> status(@PathVariable String projectId) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", hierarchyIndexService.statusPayload(projectId)));
    }
}

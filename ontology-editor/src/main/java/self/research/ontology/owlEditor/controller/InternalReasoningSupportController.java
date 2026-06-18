package self.research.ontology.owlEditor.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import self.research.ontology.owlEditor.service.ImportQueueManager;
import self.research.ontology.owlEditor.service.SparqlDatasetService;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalReasoningSupportController {

    private final SparqlDatasetService datasetService;
    private final ImportQueueManager importQueueManager;

    @GetMapping("/reasoning/{projectId}/triple-count")
    public ResponseEntity<Map<String, Object>> tripleCount(@PathVariable String projectId) {
        try {
            long count = datasetService.getDatasetSize(projectId);
            return ResponseEntity.ok(Map.of("projectId", projectId, "tripleCount", count));
        } catch (Exception e) {
            log.error("Triple count failed for {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Failed to read ontology size"
            ));
        }
    }

    @GetMapping(value = "/reasoning/{projectId}/export.ttl", produces = "text/turtle")
    public ResponseEntity<StreamingResponseBody> exportTurtle(@PathVariable String projectId) {
        StreamingResponseBody body = out -> datasetService.exportDatasetToStream(projectId, RDFFormat.TURTLE, out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/turtle"))
                .body(body);
    }

    @GetMapping("/import-queue/stats")
    public ResponseEntity<Map<String, Object>> importStats() {
        var stats = importQueueManager.getQueueStats();
        return ResponseEntity.ok(Map.of(
                "activeImports", stats.getActiveImports(),
                "queuedImports", stats.getQueuedImports()
        ));
    }
}

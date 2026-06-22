package self.research.ontology.owlEditor.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import self.research.ontology.owlEditor.service.ImportQueueManager;
import self.research.ontology.owlEditor.service.MainGraphRevisionService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalReasoningSupportController {

    private final SparqlDatasetService datasetService;
    private final ImportQueueManager importQueueManager;
    private final MainGraphRevisionService mainGraphRevisionService;

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
    public ResponseEntity<StreamingResponseBody> exportTurtle(
            @PathVariable String projectId,
            @RequestParam(required = false) String userId) {
        StreamingResponseBody body = out -> datasetService.exportDatasetToStream(projectId, userId, RDFFormat.TURTLE, out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/turtle"))
                .body(body);
    }

    @GetMapping(value = "/reasoning/{projectId}/export.nt", produces = "application/n-triples")
    public ResponseEntity<StreamingResponseBody> exportNTriples(
            @PathVariable String projectId,
            @RequestParam(required = false) String userId) {
        StreamingResponseBody body = out -> datasetService.exportDatasetToStream(projectId, userId, RDFFormat.NTRIPLES, out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/n-triples"))
                .body(body);
    }

    @GetMapping("/reasoning/{projectId}/revision")
    public ResponseEntity<Map<String, Object>> revision(@PathVariable String projectId) {
        try {
            long rev = mainGraphRevisionService.getRevision(projectId);
            return ResponseEntity.ok(Map.of("projectId", projectId, "revision", rev));
        } catch (Exception e) {
            log.error("Revision check failed for {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "Failed to read revision"));
        }
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

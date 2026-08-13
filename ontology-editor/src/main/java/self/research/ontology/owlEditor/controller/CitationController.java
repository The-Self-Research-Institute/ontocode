package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.service.CitationService;

import java.util.Map;

@RestController
@RequestMapping("/api/citations")
@CrossOrigin(originPatterns = "*")
public class CitationController {

    private static final Logger log = LoggerFactory.getLogger(CitationController.class);

    @Autowired
    private CitationService citationService;

    @PostMapping("/{projectId}/insert")
    public ResponseEntity<?> insertCitation(
            @PathVariable String projectId,
            @RequestBody InsertCitationRequest request) {

        try {
            log.info("[CitationController] Inserting citation into project: {}, format: {}",
                projectId, request.format());

            if (request.citation() == null || request.citation().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Citation content is required"
                ));
            }

            if (request.format() == null ||
                (!request.format().equals("turtle") && !request.format().equals("rdfxml"))) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Format must be 'turtle' or 'rdfxml'"
                ));
            }

            int lineNumber = request.lineNumber() != null ? request.lineNumber() : 0;
            citationService.insertCitation(projectId, request.citation(), request.format(), request.metadata(), lineNumber);

            log.info("[CitationController] Successfully inserted citation for project: {} at line: {}", projectId, lineNumber);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Citation inserted successfully",
                "projectId", projectId,
                "format", request.format()
            ));

        } catch (Exception e) {
            log.error("[CitationController] Error inserting citation for project: {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<?> getCitations(@PathVariable String projectId) {
        try {
            log.info("[CitationController] Retrieving citations for project: {}", projectId);

            var citations = citationService.getCitations(projectId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "citations", citations,
                "count", citations.size()
            ));

        } catch (Exception e) {
            log.error("[CitationController] Error retrieving citations for project: {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/validate-doi")
    public ResponseEntity<?> validateDoi(
            @RequestParam String doi,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String publicationTitle,
            @RequestParam(required = false) String year) {
        try {
            return ResponseEntity.ok(citationService.validateDoi(doi, title, publicationTitle, year));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "valid", false,
                "relevant", false,
                "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("[CitationController] Error validating DOI {}", doi, e);
            return ResponseEntity.status(502).body(Map.of(
                "valid", false,
                "relevant", false,
                "error", e.getMessage()
            ));
        }
    }

    @DeleteMapping("/{projectId}/{citationId}")
    public ResponseEntity<?> deleteCitation(
            @PathVariable String projectId,
            @PathVariable String citationId) {

        try {
            log.info("[CitationController] Deleting citation {} from project: {}", citationId, projectId);

            citationService.deleteCitation(projectId, citationId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Citation deleted successfully"
            ));

        } catch (Exception e) {
            log.error("[CitationController] Error deleting citation for project: {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    public record InsertCitationRequest(
        String citation,
        String format,
        Map<String, Object> metadata,
        Integer lineNumber
    ) {}
}

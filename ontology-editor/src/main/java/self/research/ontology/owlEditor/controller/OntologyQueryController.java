package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.OntologyQueryService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;

import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class OntologyQueryController {

    private final OntologyQueryService queryService;
    private final ProjectMetadataService metadataService;

    public OntologyQueryController(OntologyQueryService queryService,
                                   ProjectMetadataService metadataService) {
        this.queryService = queryService;
        this.metadataService = metadataService;
    }

    @GetMapping("/metadata/{projectId}")
    public ResponseEntity<?> metadata(@PathVariable String projectId) {
        return metadataService.readMeta(projectId)
                .map(meta -> ResponseEntity.ok(Map.of("success", true, "data", meta)))
                .orElseGet(() -> ResponseEntity.ok(Map.of("success", false, "error", "Metadata not ready")));
    }

    @GetMapping("/classes/top-level/{projectId}")
    public ResponseEntity<?> topLevel(@PathVariable String projectId,
                                      @RequestParam(defaultValue = "1000") int limit) {
        return ResponseEntity.ok(Map.of("success", true, "classes",
                queryService.topLevelClasses(projectId, limit)));
    }

    @GetMapping("/classes/children/{projectId}")
    public ResponseEntity<?> children(@PathVariable String projectId,
                                      @RequestParam String parentIri,
                                      @RequestParam(defaultValue = "1000") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(queryService.children(projectId, parentIri, limit, offset));
    }

    @GetMapping("/properties/{projectId}")
    public ResponseEntity<?> properties(@PathVariable String projectId,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(defaultValue = "100") int limit,
                                        @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.properties(projectId, type, limit, offset)));
    }

    @GetMapping("/individuals/{projectId}")
    public ResponseEntity<?> individuals(@PathVariable String projectId,
                                         @RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", queryService.individuals(projectId, limit, offset),
                "total", queryService.individualCount(projectId)
        ));
    }

    @GetMapping("/annotation-properties/{projectId}")
    public ResponseEntity<?> annotationProperties(@PathVariable String projectId,
                                                  @RequestParam(defaultValue = "100") int limit,
                                                  @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.annotationProperties(projectId, limit, offset)));
    }

    @GetMapping("/annotation-properties/{projectId}/usage")
    public ResponseEntity<?> annotationPropertyUsage(@PathVariable String projectId,
                                                     @RequestParam String propertyIri) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.annotationPropertyUsage(projectId, propertyIri)));
    }

    @GetMapping("/datatypes/{projectId}")
    public ResponseEntity<?> datatypes(@PathVariable String projectId,
                                       @RequestParam(defaultValue = "100") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.datatypes(projectId, limit, offset)));
    }

    @GetMapping("/classes/usage/{projectId}")
    public ResponseEntity<?> classUsage(@PathVariable String projectId,
                                       @RequestParam String classIri) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.classUsage(projectId, classIri)));
    }

    @GetMapping("/classes/details/{projectId}")
    public ResponseEntity<?> classDetails(@PathVariable String projectId,
                                         @RequestParam String classIri) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.classDetails(projectId, classIri)));
    }

    @GetMapping("/classes/instances/{projectId}")
    public ResponseEntity<?> classInstances(@PathVariable String projectId,
                                           @RequestParam String classIri) {
        return ResponseEntity.ok(queryService.getClassInstances(projectId, classIri));
    }

    @GetMapping("/{projectId}/individuals/{individualIri}")
    public ResponseEntity<?> individualDetails(@PathVariable String projectId,
                                              @PathVariable String individualIri) {
        return ResponseEntity.ok(queryService.getIndividualDetails(projectId, individualIri));
    }

    @GetMapping("/debug/{projectId}")
    public ResponseEntity<?> debug(@PathVariable String projectId) {
        return ResponseEntity.ok(queryService.debugInfo(projectId));
    }
}

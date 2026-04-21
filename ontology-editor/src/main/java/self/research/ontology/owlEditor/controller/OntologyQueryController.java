package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.OntologyMetadataService;
import self.research.ontology.owlEditor.service.OntologyQueryService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class OntologyQueryController {

    private final OntologyQueryService queryService;
    private final ProjectMetadataService projectMetadataService;
    private final OntologyMetadataService ontologyMetadataService;

    public OntologyQueryController(OntologyQueryService queryService,
                                   ProjectMetadataService projectMetadataService,
                                   OntologyMetadataService ontologyMetadataService) {
        this.queryService = queryService;
        this.projectMetadataService = projectMetadataService;
        this.ontologyMetadataService = ontologyMetadataService;
    }

    @GetMapping("/classes/top-level/{projectId:.+}")
    public ResponseEntity<?> topLevel(@PathVariable String projectId,
                                      @RequestParam(defaultValue = "1000") int limit) {
        try {
            return ResponseEntity.ok(Map.of("success", true, "classes",
                    queryService.topLevelClasses(projectId, limit)));
        } catch (Exception e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "error", "Query timed out or failed: " + e.getMessage()));
        }
    }

    @GetMapping("/classes/all/{projectId:.+}")
    public ResponseEntity<?> allClasses(@PathVariable String projectId,
                                        @RequestParam(defaultValue = "10000") int limit) {
        try {
            return ResponseEntity.ok(Map.of("success", true, "classes",
                    queryService.allClasses(projectId, limit)));
        } catch (Exception e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "error", "Query timed out or failed: " + e.getMessage()));
        }
    }

    @GetMapping("/classes/children/{projectId:.+}")
    public ResponseEntity<?> children(@PathVariable String projectId,
                                      @RequestParam String parentIri,
                                      @RequestParam(defaultValue = "1000") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(queryService.children(projectId, parentIri, limit, offset));
    }

    @GetMapping("/properties/{projectId:.+}")
    public ResponseEntity<?> properties(@PathVariable String projectId,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(defaultValue = "100") int limit,
                                        @RequestParam(defaultValue = "0") int offset) {
        try {
            return ResponseEntity.ok(Map.of("success", true, "data",
                    queryService.properties(projectId, type, limit, offset)));
        } catch (Exception e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "error", "Query timed out or failed: " + e.getMessage()));
        }
    }

    @GetMapping("/properties/detail/{projectId:.+}")
    public ResponseEntity<?> propertyDetail(@PathVariable String projectId,
                                            @RequestParam String iri) {
        try {
            return ResponseEntity.ok(Map.of("success", true, "data",
                    queryService.propertyDetail(projectId, iri)));
        } catch (Exception e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "error", "Query failed: " + e.getMessage()));
        }
    }

    @GetMapping("/individuals/{projectId:.+}")
    public ResponseEntity<?> individuals(@PathVariable String projectId,
                                         @RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        try {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", queryService.individuals(projectId, limit, offset),
                    "total", queryService.individualCount(projectId)
            ));
        } catch (Exception e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "error", "Query timed out or failed: " + e.getMessage()));
        }
    }

    @GetMapping("/annotation-properties/{projectId:.+}")
    public ResponseEntity<?> annotationProperties(@PathVariable String projectId,
                                                  @RequestParam(defaultValue = "100") int limit,
                                                  @RequestParam(defaultValue = "0") int offset) {
        try {
            return ResponseEntity.ok(Map.of("success", true, "data",
                    queryService.annotationProperties(projectId, limit, offset)));
        } catch (Exception e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "error", "Query timed out or failed: " + e.getMessage()));
        }
    }

    @GetMapping("/annotation-properties/{projectId}/usage")
    public ResponseEntity<?> annotationPropertyUsage(@PathVariable String projectId,
                                                     @RequestParam String propertyIri) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.annotationPropertyUsage(projectId, propertyIri)));
    }

    @GetMapping("/datatypes/{projectId:.+}")
    public ResponseEntity<?> datatypes(@PathVariable String projectId,
                                       @RequestParam(defaultValue = "100") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        try {
            return ResponseEntity.ok(Map.of("success", true, "data",
                    queryService.datatypes(projectId, limit, offset)));
        } catch (Exception e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "error", "Query timed out or failed: " + e.getMessage()));
        }
    }

    @GetMapping("/classes/usage/{projectId}")
    public ResponseEntity<?> classUsage(@PathVariable String projectId,
                                       @RequestParam String classIri) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.classUsage(projectId, classIri)));
    }

    @GetMapping("/properties/usage/{projectId}")
    public ResponseEntity<?> propertyUsage(@PathVariable String projectId,
                                          @RequestParam String propertyIri) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.propertyUsage(projectId, propertyIri)));
    }

    @GetMapping("/datatypes/usage/{projectId}")
    public ResponseEntity<?> datatypeUsage(@PathVariable String projectId,
                                          @RequestParam String datatypeIri) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.datatypeUsage(projectId, datatypeIri)));
    }

    @GetMapping("/individuals/usage/{projectId}")
    public ResponseEntity<?> individualUsage(@PathVariable String projectId,
                                            @RequestParam String individualIri) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.individualUsage(projectId, individualIri)));
    }

    @GetMapping("/classes/details/{projectId}")
    public ResponseEntity<?> classDetails(@PathVariable String projectId,
                                         @RequestParam String classIri) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.classDetails(projectId, classIri)));
    }

    /**
     * Fast-path: annotations-only class details. Runs a single SPARQL query
     * (typically <100ms). UI calls this first to render the Annotations panel
     * immediately, then fires the full /classes/details call in the background.
     */
    @GetMapping("/classes/annotations/{projectId}")
    public ResponseEntity<?> classAnnotations(@PathVariable String projectId,
                                              @RequestParam String classIri) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.classAnnotations(projectId, classIri)));
    }

    @GetMapping("/classes/instances/{projectId}")
    public ResponseEntity<?> classInstances(@PathVariable String projectId,
                                           @RequestParam String classIri) {
        return ResponseEntity.ok(queryService.getClassInstances(projectId, classIri));
    }

    @GetMapping("/classes/instance-counts/{projectId:.+}")
    public ResponseEntity<?> classInstanceCounts(@PathVariable String projectId) {
        try {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", queryService.getClassInstanceCounts(projectId)
            ));
        } catch (Exception e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "error", "Query timed out or failed: " + e.getMessage()));
        }
    }

    @GetMapping("/ontology/gci/{projectId}")
    public ResponseEntity<?> generalClassAxioms(@PathVariable String projectId,
                                                @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", queryService.generalClassAxioms(projectId, limit)
        ));
    }

    @GetMapping("/{projectId}/individuals/{individualIri}")
    public ResponseEntity<?> individualDetails(@PathVariable String projectId,
                                              @PathVariable String individualIri) {
        return ResponseEntity.ok(queryService.getIndividualDetails(projectId, individualIri));
    }

    @GetMapping("/individual-details/{projectId}")
    public ResponseEntity<?> individualDetailsByParam(@PathVariable String projectId,
                                                      @RequestParam String individualIri) {
        return ResponseEntity.ok(queryService.getIndividualDetails(projectId, individualIri));
    }

    @GetMapping("/debug/{projectId}")
    public ResponseEntity<?> debug(@PathVariable String projectId) {
        return ResponseEntity.ok(queryService.debugInfo(projectId));
    }

    @GetMapping("/{projectId}/schema")
    public ResponseEntity<?> getOntologySchema(@PathVariable String projectId) {
        return ResponseEntity.ok(queryService.getOntologySchema(projectId));
    }
}

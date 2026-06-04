package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.DesktopHierarchyService;
import self.research.ontology.owlEditor.service.HierarchyIndexService;
import self.research.ontology.owlEditor.service.OntologyMetadataService;
import self.research.ontology.owlEditor.service.OntologyQueryService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class OntologyQueryController {

    private final OntologyQueryService queryService;
    private final ProjectMetadataService projectMetadataService;
    private final OntologyMetadataService ontologyMetadataService;
    private final HierarchyIndexService hierarchyIndexService;

    // Desktop-only — null in cloud. Injected when ontocode.desktop.mode=true.
    @Autowired(required = false) @Nullable
    private DesktopHierarchyService desktopHierarchyService;

    @Autowired(required = false) @Nullable
    private self.research.ontology.owlEditor.service.DesktopOntologyLoader desktopOntologyLoader;

    public OntologyQueryController(OntologyQueryService queryService,
                                   ProjectMetadataService projectMetadataService,
                                   OntologyMetadataService ontologyMetadataService,
                                   HierarchyIndexService hierarchyIndexService) {
        this.queryService = queryService;
        this.projectMetadataService = projectMetadataService;
        this.ontologyMetadataService = ontologyMetadataService;
        this.hierarchyIndexService = hierarchyIndexService;
    }

    /** Desktop: tells the frontend whether the OWLAPI in-memory model is ready. */
    @GetMapping("/cache-status/{projectId:.+}")
    public ResponseEntity<?> cacheStatus(@PathVariable String projectId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(hierarchyIndexService.statusPayload(projectId));
        boolean owlapiReady = desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId);
        body.put("owlapiReady", owlapiReady);
        body.put("projectId", projectId);
        if (owlapiReady && desktopHierarchyService != null) {
            body.putAll(desktopHierarchyService.declarationCounts(projectId));
            body.put("hierarchyEngine", "owlapi");
            body.put("hierarchyReady", true);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Desktop: load the ontology into OWLAPI memory (Protégé-style) before the UI
     * runs heavy Fuseki SPARQL. Blocks up to timeoutMs (default 5 min).
     */
    @org.springframework.web.bind.annotation.PostMapping("/warm/{projectId:.+}")
    public ResponseEntity<?> warmOntology(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "300000") long timeoutMs) {
        if (desktopOntologyLoader == null) {
            return ResponseEntity.ok(Map.of("ready", false, "sparqlFallback", true, "message", "Not desktop mode"));
        }
        Map<String, Object> result = new HashMap<>(desktopOntologyLoader.warmProject(projectId, timeoutMs));
        result.put("success", true);
        result.put("projectId", projectId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/classes/top-level/{projectId:.+}")
    public ResponseEntity<?> topLevel(@PathVariable String projectId,
                                      @RequestParam(defaultValue = "5000") int limit) {
        try {
            // Desktop fast path: OWLAPI in-memory → instant, no network
            if (desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
                var classes = desktopHierarchyService.topLevelClasses(projectId, limit);
                int topLevelTotal = desktopHierarchyService.topLevelClassTotal(projectId);
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("success", true);
                body.put("classes", classes);
                body.put("topLevelReturned", classes.size());
                body.put("topLevelTotal", topLevelTotal);
                body.put("topLevelLimit", limit);
                body.put("truncated", topLevelTotal > limit);
                body.putAll(desktopHierarchyService.declarationCounts(projectId));
                return ResponseEntity.ok(body);
            }
            // Trigger lazy OWLAPI load; brief wait so first open gets in-memory counts + tree when possible.
            if (desktopOntologyLoader != null) {
                desktopOntologyLoader.triggerLazyLoadIfNeeded(projectId);
                for (int i = 0; i < 80 && desktopHierarchyService != null; i++) {
                    if (desktopHierarchyService.hasOntology(projectId)) {
                        var classes = desktopHierarchyService.topLevelClasses(projectId, limit);
                        int topLevelTotal = desktopHierarchyService.topLevelClassTotal(projectId);
                        Map<String, Object> body = new java.util.LinkedHashMap<>();
                        body.put("success", true);
                        body.put("classes", classes);
                        body.put("topLevelReturned", classes.size());
                        body.put("topLevelTotal", topLevelTotal);
                        body.put("topLevelLimit", limit);
                        body.put("truncated", topLevelTotal > limit);
                        body.putAll(desktopHierarchyService.declarationCounts(projectId));
                        return ResponseEntity.ok(body);
                    }
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            // Cloud: precomputed OWLAPI snapshot (Protégé-parity)
            Optional<Map<String, Object>> snapshot = hierarchyIndexService.topLevelResponse(projectId, limit);
            if (snapshot.isPresent()) {
                return ResponseEntity.ok(snapshot.get());
            }
            if (hierarchyIndexService.isEnabled() && !hierarchyIndexService.allowsLegacySparqlFallback()) {
                Map<String, Object> pending = new java.util.LinkedHashMap<>();
                pending.put("success", false);
                pending.put("hierarchyReady", false);
                pending.putAll(hierarchyIndexService.statusPayload(projectId));
                pending.put("message", "Class hierarchy index is building. Please wait and refresh.");
                return ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED).body(pending);
            }
            // Legacy fallback (disabled by default)
            return ResponseEntity.ok(Map.of("success", true, "classes",
                    queryService.topLevelClasses(projectId, limit),
                    "hierarchyEngine", "sparql"));
        } catch (Exception e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "error", "Query timed out or failed: " + e.getMessage()));
        }
    }

    @GetMapping("/classes/all/{projectId:.+}")
    public ResponseEntity<?> allClasses(@PathVariable String projectId,
                                        @RequestParam(defaultValue = "50000") int limit) {
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
        // Desktop fast path — also fixes the duplicate union-member display bug:
        // StructuralReasoner.getSubClasses(direct=true) returns only explicit rdfs:subClassOf,
        // never union members, so Viruses won't appear twice.
        if (desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
            return ResponseEntity.ok(desktopHierarchyService.children(projectId, parentIri, limit, offset));
        }
        Optional<List<self.research.ontology.owlEditor.dto.OntologyDto.TreeNode>> snapChildren =
                hierarchyIndexService.children(projectId, parentIri, limit, offset);
        if (snapChildren.isPresent()) {
            return ResponseEntity.ok(snapChildren.get());
        }
        if (hierarchyIndexService.isEnabled() && !hierarchyIndexService.allowsLegacySparqlFallback()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED)
                    .body(List.of());
        }
        return ResponseEntity.ok(queryService.children(projectId, parentIri, limit, offset));
    }

    @GetMapping("/properties/{projectId:.+}")
    public ResponseEntity<?> properties(@PathVariable String projectId,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(defaultValue = "2000") int limit,
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
                                                  @RequestParam(defaultValue = "2000") int limit,
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
        // Desktop fast path: compute usage from OWLAPI in-memory model
        if (desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
            return ResponseEntity.ok(Map.of("success", true, "data",
                    desktopHierarchyService.classUsage(projectId, classIri)));
        }
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
                                         @RequestParam String classIri,
                                         jakarta.servlet.http.HttpServletRequest httpRequest) {
        // Desktop: OWLAPI in-memory → instant, no SPARQL, nothing to cancel
        if (desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
            return ResponseEntity.ok(Map.of("success", true, "data",
                    desktopHierarchyService.classDetails(projectId, classIri)));
        }
        // Check if client disconnected before starting expensive SPARQL queries
        try {
            if (httpRequest.isAsyncStarted()) {
                Object ctx = httpRequest.getAttribute("asyncContext");
                if (ctx != null) return ResponseEntity.status(499).build();
            }
        } catch (Exception ignored) {}
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

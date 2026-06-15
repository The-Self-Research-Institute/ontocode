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

    @Autowired(required = false) @Nullable
    private self.research.ontology.owlEditor.service.GraphDBDatasetService datasetService;

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
        body.put("fastOpenAvailable", desktopOntologyLoader != null);
        if (desktopOntologyLoader != null) {
            body.put("fastOpenAutoWarm", desktopOntologyLoader.isAutoWarmEnabled());
        }
        body.put("projectId", projectId);
        if (owlapiReady && desktopHierarchyService != null) {
            body.putAll(desktopHierarchyService.declarationCounts(projectId));
            body.put("hierarchyEngine", "owlapi");
            int topLevelTotal = desktopHierarchyService.topLevelClassTotal(projectId);
            body.put("topLevelClasses", topLevelTotal);
            body.put("hierarchyReady", topLevelTotal > 0);
        } else if (hierarchyIndexService.isReady(projectId)) {
            body.put("hierarchyEngine", "snapshot");
            body.put("hierarchyReady", true);
        } else {
            int topLevel = 0;
            try {
                topLevel = queryService.topLevelClassCount(projectId);
            } catch (Exception ignored) {
                /* SPARQL may be warming */
            }
            boolean hierarchyReady = topLevel > 0;
            body.put("topLevelClasses", topLevel);
            body.put("hierarchyReady", hierarchyReady);
            body.put("sparqlFallback", true);
            if (hierarchyReady) {
                body.put("hierarchyEngine", "sparql");
            } else if (graphHasTriples(projectId)) {
                body.put("hierarchyEngine", "sparql");
                body.put("hierarchyWarming", true);
            }
        }
        return ResponseEntity.ok(body);
    }

    private boolean graphHasTriples(String projectId) {
        if (datasetService == null) {
            return false;
        }
        try {
            return datasetService.getGraphTripleCount(projectId) > 0;
        } catch (Exception ignored) {
            return false;
        }
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
            return ResponseEntity.ok(Map.of(
                    "ready", false, "sparqlFallback", true,
                    "message", "Fast-open disabled (set ontocode.fastopen.enabled=true)"));
        }
        Map<String, Object> result = new HashMap<>(desktopOntologyLoader.warmProject(projectId, timeoutMs));
        result.put("success", true);
        result.put("projectId", projectId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/classes/top-level/{projectId:.+}")
    public ResponseEntity<?> topLevel(@PathVariable String projectId,
                                      @RequestParam(defaultValue = "5000") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        try {
            // Desktop fast path: OWLAPI in-memory → instant, no network
            if (desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
                var classes = desktopHierarchyService.topLevelClasses(projectId, limit, offset);
                int topLevelTotal = desktopHierarchyService.topLevelClassTotal(projectId);
                boolean truncated = (offset + classes.size()) < topLevelTotal;
                if (offset == 0 && truncated) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                        "[Desktop] Top-level truncated for {}: total={} limit={}", projectId, topLevelTotal, limit);
                } else if (offset == 0) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).info(
                        "[Desktop] Top-level for {}: total={} (no truncation)", projectId, topLevelTotal);
                }
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("success", true);
                body.put("classes", classes);
                body.put("topLevelReturned", classes.size());
                body.put("topLevelTotal", topLevelTotal);
                body.put("topLevelOffset", offset);
                body.put("topLevelLimit", limit);
                body.put("truncated", truncated);
                body.put("hierarchyEngine", "owlapi");
                body.put("topLevelClasses", topLevelTotal);
                // OWLAPI is authoritative: 0 classes means the ontology is genuinely empty,
                // not still loading. Always return 200 so the frontend doesn't spin.
                body.put("hierarchyReady", true);
                if (offset == 0) body.putAll(desktopHierarchyService.declarationCounts(projectId));
                return ResponseEntity.ok(body);
            }
            // Trigger lazy OWLAPI load (non-blocking — the frontend's POST /warm is the
            // authoritative wait mechanism; we just ensure load is queued).
            if (desktopOntologyLoader != null) {
                desktopOntologyLoader.triggerLazyLoadIfNeeded(projectId);
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
            // SPARQL fallback when OWLAPI/snapshot unavailable (e.g. heap-skipped large files)
            var classes = queryService.topLevelClasses(projectId, limit);
            boolean owlapiLoading = desktopOntologyLoader != null && desktopOntologyLoader.isLoading(projectId);
            boolean inDesktopMode = desktopOntologyLoader != null;
            if (offset == 0 && classes.isEmpty()) {
                int topCount = 0;
                try {
                    topCount = queryService.topLevelClassCount(projectId);
                } catch (Exception ignored) {
                    /* still warming */
                }
                // In desktop mode, only return 202 while OWLAPI is actively loading; once it's
                // done (succeeded, failed, or skipped), fall through so we can return a final answer.
                // In cloud mode, keep the original topCount/graphHasTriples heuristic.
                boolean shouldReturn202 = owlapiLoading || (!inDesktopMode && (topCount > 0 || graphHasTriples(projectId)));
                if (shouldReturn202) {
                    Map<String, Object> pending = new java.util.LinkedHashMap<>();
                    pending.put("success", false);
                    pending.put("hierarchyReady", false);
                    pending.put("classes", List.of());
                    pending.put("hierarchyEngine", "sparql");
                    pending.put("sparqlFallback", true);
                    pending.put("message", owlapiLoading
                        ? "Loading ontology into memory…"
                        : "Triple store ready — loading class tree…");
                    return ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED).body(pending);
                }
            }
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("success", true);
            body.put("classes", classes);
            body.put("hierarchyEngine", "sparql");
            // Desktop: once OWLAPI is done (not loading), this is the final answer — always
            // signal hierarchyReady:true so the frontend stops polling even if SPARQL returned
            // empty (avoids infinite re-poll when empty SPARQL result returns hierarchyReady:false).
            body.put("hierarchyReady", !classes.isEmpty() || (inDesktopMode && !owlapiLoading));
            body.put("topLevelReturned", classes.size());
            return ResponseEntity.ok(body);
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
            Map<String, Object> pending = new java.util.LinkedHashMap<>();
            pending.put("success", false);
            pending.put("hierarchyReady", false);
            pending.put("children", List.of());
            pending.putAll(hierarchyIndexService.statusPayload(projectId));
            pending.put("message", "Class hierarchy index is building. Please wait and retry.");
            return ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED).body(pending);
        }
        try {
            return ResponseEntity.ok(queryService.children(projectId, parentIri, limit, offset));
        } catch (Exception e) {
            Map<String, Object> pending = new java.util.LinkedHashMap<>();
            pending.put("success", false);
            pending.put("hierarchyReady", false);
            pending.put("children", List.of());
            pending.put("message", "Children query is still running. Please retry.");
            pending.put("error", e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED).body(pending);
        }
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
        // Fast-open: OWLAPI in-memory class details (~5ms) including supplements
        // (disjointUnion, hasKey, GCIs, inferred axioms, multi-valued annotations).
        // Falls through to full SPARQL when the OWLAPI model is not warmed.
        if (desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
            Map<String, Object> details = new java.util.LinkedHashMap<>(
                    desktopHierarchyService.classDetails(projectId, classIri));
            if (!details.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", true, "data", details));
            }
        }
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
        if (desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
            return ResponseEntity.ok(Map.of("success", true, "data",
                    desktopHierarchyService.classAnnotations(projectId, classIri)));
        }
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.classAnnotations(projectId, classIri)));
    }

    @GetMapping("/classes/instances/{projectId}")
    public ResponseEntity<?> classInstances(@PathVariable String projectId,
                                           @RequestParam String classIri) {
        if (desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
            return ResponseEntity.ok(desktopHierarchyService.classInstances(projectId, classIri));
        }
        return ResponseEntity.ok(queryService.getClassInstances(projectId, classIri));
    }

    @GetMapping("/classes/instance-counts/{projectId:.+}")
    public ResponseEntity<?> classInstanceCounts(@PathVariable String projectId) {
        try {
            if (desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "data", desktopHierarchyService.classInstanceCounts(projectId)
                ));
            }
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

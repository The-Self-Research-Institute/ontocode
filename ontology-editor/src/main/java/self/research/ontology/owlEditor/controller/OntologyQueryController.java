package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.DesktopHierarchyService;
import self.research.ontology.owlEditor.service.owlapi.OwlApiAnnotationPropertyQueryService;
import self.research.ontology.owlEditor.service.owlapi.OwlApiDatatypeQueryService;
import self.research.ontology.owlEditor.service.owlapi.OwlApiIndividualQueryService;
import self.research.ontology.owlEditor.service.owlapi.OwlApiOntologyContext;
import self.research.ontology.owlEditor.service.owlapi.OwlApiPropertyQueryService;
import self.research.ontology.owlEditor.service.ClassDetailCacheService;
import self.research.ontology.owlEditor.service.EntityUsageIndexService;
import self.research.ontology.owlEditor.service.HierarchyIndexService;
import self.research.ontology.owlEditor.service.OntologyMetadataService;
import self.research.ontology.owlEditor.service.OntologyQueryService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.SparqlQueryContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class OntologyQueryController {

    private static final Logger log = LoggerFactory.getLogger(OntologyQueryController.class);

    private final OntologyQueryService queryService;
    private final ProjectMetadataService projectMetadataService;
    private final OntologyMetadataService ontologyMetadataService;
    private final HierarchyIndexService hierarchyIndexService;

    @Autowired(required = false) @Nullable
    private DesktopHierarchyService desktopHierarchyService;

    @Autowired(required = false) @Nullable
    private OwlApiOntologyContext owlApiContext;

    @Autowired(required = false) @Nullable
    private OwlApiPropertyQueryService owlApiPropertyQueryService;

    @Autowired(required = false) @Nullable
    private OwlApiIndividualQueryService owlApiIndividualQueryService;

    @Autowired(required = false) @Nullable
    private OwlApiAnnotationPropertyQueryService owlApiAnnotationPropertyQueryService;

    @Autowired(required = false) @Nullable
    private OwlApiDatatypeQueryService owlApiDatatypeQueryService;

    @Autowired(required = false) @Nullable
    private self.research.ontology.owlEditor.service.owlapi.OwlApiOntologyMetadataQueryService owlApiMetadataQueryService;

    @Autowired(required = false) @Nullable
    private EntityUsageIndexService entityUsageIndexService;

    @Autowired(required = false) @Nullable
    private ClassDetailCacheService classDetailCacheService;

    @Autowired(required = false) @Nullable
    private self.research.ontology.owlEditor.service.DesktopOntologyLoader desktopOntologyLoader;

    @Autowired(required = false) @Nullable
    private self.research.ontology.owlEditor.service.SparqlDatasetService datasetService;

    @Autowired(required = false) @Nullable
    private self.research.ontology.owlEditor.service.ProjectImportService projectImportService;

    @Autowired(required = false) @Nullable
    private self.research.ontology.owlEditor.service.ReasonerClassInstanceMerger reasonerClassInstanceMerger;

    @Autowired(required = false) @Nullable
    private self.research.ontology.owlEditor.service.ReasonerIndividualAssertionMerger reasonerIndividualAssertionMerger;

    @Value("${ontocode.desktop.owlapi-first:false}")
    private boolean owlApiFirst;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    public OntologyQueryController(OntologyQueryService queryService,
                                   ProjectMetadataService projectMetadataService,
                                   OntologyMetadataService ontologyMetadataService,
                                   HierarchyIndexService hierarchyIndexService) {
        this.queryService = queryService;
        this.projectMetadataService = projectMetadataService;
        this.ontologyMetadataService = ontologyMetadataService;
        this.hierarchyIndexService = hierarchyIndexService;
    }

    private boolean owlApiReady(String projectId) {
        return owlApiContext != null && owlApiContext.hasOntology(projectId);
    }

    private boolean preferOwlApiPath(String projectId) {
        if (!owlApiFirst) {
            return false;
        }
        if (desktopMode) {

            return owlApiContext != null || desktopOntologyLoader != null;
        }
        if (desktopOntologyLoader == null) {
            return false;
        }
        String userId = SparqlQueryContext.getUserId();
        if (userId != null && !userId.isBlank() && datasetService != null
                && datasetService.hasActiveDraftOverlay(projectId, userId)) {
            return false;
        }
        return true;
    }

    private ResponseEntity<?> sparqlListFallback(String projectId, Exception e) {
        if (desktopMode && owlApiFirst) {
            ensureOwlApiWarming(projectId);

            return owlApiWarmingListResponse();
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("success", false, "error", "Query timed out or failed: " + e.getMessage()));
    }

    private void ensureOwlApiWarming(String projectId) {
        if (desktopOntologyLoader != null) {
            desktopOntologyLoader.triggerLazyLoadIfNeeded(projectId);
        }
    }

    private ResponseEntity<?> owlApiWarmingListResponse() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("success", true);
        body.put("warming", true);
        body.put("owlapiReady", false);
        body.put("data", List.of());
        body.put("total", 0);
        body.put("message", "OWLAPI model is loading — retry shortly");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    private Optional<ResponseEntity<?>> owlApiOnlyOrWarming(String projectId, Supplier<ResponseEntity<?>> whenReady) {
        if (!preferOwlApiPath(projectId)) {
            return Optional.empty();
        }
        ensureOwlApiWarming(projectId);
        if (!owlApiReady(projectId)) {
            return Optional.of(owlApiWarmingListResponse());
        }
        try {
            return Optional.of(whenReady.get());
        } catch (Exception e) {
            if (e instanceof IllegalStateException
                    && e.getMessage() != null
                    && e.getMessage().contains("service unavailable")) {
                ensureOwlApiWarming(projectId);
                return Optional.of(owlApiWarmingListResponse());
            }
            if (desktopOntologyLoader != null && desktopOntologyLoader.isLoading(projectId)) {
                return Optional.of(owlApiWarmingListResponse());
            }
            if (!owlApiReady(projectId)) {
                return Optional.of(owlApiWarmingListResponse());
            }
            return Optional.of(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "error", "OWLAPI query failed: " + e.getMessage())));
        }
    }

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
                                      @RequestParam(defaultValue = "0") int offset,
                                      @RequestParam(defaultValue = "active") String scope) {
        org.semanticweb.owlapi.model.parameters.Imports importsScope =
                "closure".equalsIgnoreCase(scope)
                        ? org.semanticweb.owlapi.model.parameters.Imports.INCLUDED
                        : org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED;

        String ctxUserId = SparqlQueryContext.getUserId();
        boolean hasDraft = ctxUserId != null && datasetService != null
                && datasetService.hasActiveDraftOverlay(projectId, ctxUserId);
        try {

            if (preferOwlApiPath(projectId)
                    && desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
                var classes = desktopHierarchyService.topLevelClasses(projectId, limit, offset, importsScope);
                int topLevelTotal = desktopHierarchyService.topLevelClassTotal(projectId, importsScope);
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

                body.put("hierarchyReady", true);
                if (offset == 0) body.putAll(desktopHierarchyService.declarationCounts(projectId));
                return ResponseEntity.ok(body);
            }

            if (desktopOntologyLoader != null) {
                desktopOntologyLoader.triggerLazyLoadIfNeeded(projectId);
            }

            if (!hasDraft) {
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
            }

            boolean owlapiLoading = desktopOntologyLoader != null && desktopOntologyLoader.isLoading(projectId);
            if (preferOwlApiPath(projectId) && owlapiLoading) {
                Map<String, Object> pending = new java.util.LinkedHashMap<>();
                pending.put("success", true);
                pending.put("hierarchyReady", false);
                pending.put("classes", List.of());
                pending.put("hierarchyEngine", "owlapi-loading");
                pending.put("owlapiReady", false);
                pending.put("message", "Loading ontology into memory…");
                return ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED).body(pending);
            }

            var classes = queryService.topLevelClasses(projectId, limit);
            boolean inDesktopMode = desktopOntologyLoader != null;
            if (offset == 0 && classes.isEmpty()) {
                int topCount = 0;
                try {
                    topCount = queryService.topLevelClassCount(projectId);
                } catch (Exception ignored) {

                }

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

            boolean desktopFinalAnswer = inDesktopMode && !owlapiLoading;
            body.put("hierarchyReady", !classes.isEmpty() || desktopFinalAnswer);
            body.put("topLevelReturned", classes.size());

            if (desktopFinalAnswer && classes.isEmpty()) {
                body.put("topLevelTotal", 0);
            }
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "error", "Query timed out or failed: " + e.getMessage()));
        }
    }

    @GetMapping("/classes/all/{projectId:.+}")
    public ResponseEntity<?> allClasses(@PathVariable String projectId,
                                        @RequestParam(defaultValue = "50000") int limit) {

        if (preferOwlApiPath(projectId)
                && desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
            return ResponseEntity.ok(Map.of("success", true, "classes",
                    desktopHierarchyService.allClasses(projectId, limit)));
        }
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
                                      @RequestParam(defaultValue = "0") int offset,
                                      @RequestParam(defaultValue = "active") String scope) {
        org.semanticweb.owlapi.model.parameters.Imports importsScope =
                "closure".equalsIgnoreCase(scope)
                        ? org.semanticweb.owlapi.model.parameters.Imports.INCLUDED
                        : org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED;

        if (preferOwlApiPath(projectId)
                && desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
            return ResponseEntity.ok(desktopHierarchyService.children(projectId, parentIri, limit, offset, importsScope));
        }

        String childrenCtxUserId = SparqlQueryContext.getUserId();
        boolean childrenHasDraft = childrenCtxUserId != null && datasetService != null
                && datasetService.hasActiveDraftOverlay(projectId, childrenCtxUserId);
        if (!childrenHasDraft) {
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

    private Optional<List<self.research.ontology.owlEditor.dto.OntologyDto.TreeNode>> fetchChildrenOrEmpty(
            String projectId, String parentIri,
            org.semanticweb.owlapi.model.parameters.Imports importsScope) {
        if (preferOwlApiPath(projectId)
                && desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
            return Optional.of(desktopHierarchyService.children(projectId, parentIri, MAX_DESCENDANTS, 0, importsScope));
        }
        String userId = SparqlQueryContext.getUserId();
        boolean hasDraft = userId != null && datasetService != null
                && datasetService.hasActiveDraftOverlay(projectId, userId);
        if (!hasDraft) {
            Optional<List<self.research.ontology.owlEditor.dto.OntologyDto.TreeNode>> snap =
                    hierarchyIndexService.children(projectId, parentIri, MAX_DESCENDANTS, 0);
            if (snap.isPresent()) return snap;
            if (hierarchyIndexService.isEnabled() && !hierarchyIndexService.allowsLegacySparqlFallback()) {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(queryService.children(projectId, parentIri, MAX_DESCENDANTS, 0));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static final int MAX_DESCENDANTS = 5000;

    @GetMapping("/classes/descendants/{projectId:.+}")
    public ResponseEntity<?> descendants(@PathVariable String projectId,
                                         @RequestParam String parentIri,
                                         @RequestParam(defaultValue = "active") String scope) {
        org.semanticweb.owlapi.model.parameters.Imports importsScope =
                "closure".equalsIgnoreCase(scope)
                        ? org.semanticweb.owlapi.model.parameters.Imports.INCLUDED
                        : org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED;

        java.util.LinkedHashMap<String, String> found = new java.util.LinkedHashMap<>();
        java.util.ArrayDeque<String> frontier = new java.util.ArrayDeque<>();
        frontier.add(parentIri);
        boolean truncated = false;

        while (!frontier.isEmpty()) {
            String node = frontier.poll();
            Optional<List<self.research.ontology.owlEditor.dto.OntologyDto.TreeNode>> kids =
                    fetchChildrenOrEmpty(projectId, node, importsScope);
            if (kids.isEmpty()) {
                Map<String, Object> pending = new java.util.LinkedHashMap<>();
                pending.put("success", false);
                pending.put("message", "Class hierarchy is still loading. Please retry.");
                return ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED).body(pending);
            }
            for (self.research.ontology.owlEditor.dto.OntologyDto.TreeNode child : kids.get()) {
                if (child.getId() == null || found.containsKey(child.getId())) continue;
                if (found.size() >= MAX_DESCENDANTS) {
                    truncated = true;
                    break;
                }
                found.put(child.getId(), child.getLabel());
                frontier.add(child.getId());
            }
            if (truncated) break;
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("success", true);
        body.put("iris", new java.util.ArrayList<>(found.keySet()));
        body.put("labels", found);
        body.put("truncated", truncated);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/properties/{projectId:.+}")
    public ResponseEntity<?> properties(@PathVariable String projectId,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(defaultValue = "2000") int limit,
                                        @RequestParam(defaultValue = "0") int offset) {
        Optional<ResponseEntity<?>> owl = owlApiOnlyOrWarming(projectId, () -> {
            if (owlApiPropertyQueryService == null) {
                throw new IllegalStateException("OWLAPI property service unavailable");
            }
            return ResponseEntity.ok(Map.of("success", true, "data",
                    owlApiPropertyQueryService.list(projectId, type, limit, offset)));
        });
        if (owl.isPresent()) {
            return owl.get();
        }
        try {
            return ResponseEntity.ok(Map.of("success", true, "data",
                    queryService.properties(projectId, type, limit, offset)));
        } catch (Exception e) {
            return sparqlListFallback(projectId, e);
        }
    }

    @GetMapping("/properties/detail/{projectId:.+}")
    public ResponseEntity<?> propertyDetail(@PathVariable String projectId,
                                            @RequestParam String iri) {
        Optional<ResponseEntity<?>> owl = owlApiOnlyOrWarming(projectId, () -> {
            if (owlApiPropertyQueryService == null) {
                throw new IllegalStateException("OWLAPI property service unavailable");
            }
            return ResponseEntity.ok(Map.of("success", true, "data",
                    owlApiPropertyQueryService.detail(projectId, iri)));
        });
        if (owl.isPresent()) {
            return owl.get();
        }
        try {
            return ResponseEntity.ok(Map.of("success", true, "data",
                    queryService.propertyDetail(projectId, iri)));
        } catch (Exception e) {
            return sparqlListFallback(projectId, e);
        }
    }

    @GetMapping("/individuals/{projectId:.+}")
    public ResponseEntity<?> individuals(@PathVariable String projectId,
                                         @RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        Optional<ResponseEntity<?>> owl = owlApiOnlyOrWarming(projectId, () -> {
            if (owlApiIndividualQueryService == null) {
                throw new IllegalStateException("OWLAPI individual service unavailable");
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", owlApiIndividualQueryService.list(projectId, limit, offset),
                    "total", owlApiIndividualQueryService.count(projectId)
            ));
        });
        if (owl.isPresent()) {
            return owl.get();
        }
        try {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", queryService.individuals(projectId, limit, offset),
                    "total", queryService.individualCount(projectId)
            ));
        } catch (Exception e) {
            return sparqlListFallback(projectId, e);
        }
    }

    @GetMapping("/annotation-properties/{projectId:.+}")
    public ResponseEntity<?> annotationProperties(@PathVariable String projectId,
                                                  @RequestParam(defaultValue = "2000") int limit,
                                                  @RequestParam(defaultValue = "0") int offset) {
        Optional<ResponseEntity<?>> owl = owlApiOnlyOrWarming(projectId, () -> {
            if (owlApiAnnotationPropertyQueryService == null) {
                throw new IllegalStateException("OWLAPI annotation property service unavailable");
            }
            return ResponseEntity.ok(Map.of("success", true, "data",
                    owlApiAnnotationPropertyQueryService.list(projectId, limit, offset)));
        });
        if (owl.isPresent()) {
            return owl.get();
        }
        try {
            return ResponseEntity.ok(Map.of("success", true, "data",
                    queryService.annotationProperties(projectId, limit, offset)));
        } catch (Exception e) {
            return sparqlListFallback(projectId, e);
        }
    }

    @GetMapping("/annotation-properties/{projectId}/usage")
    public ResponseEntity<?> annotationPropertyUsage(@PathVariable String projectId,
                                                     @RequestParam String propertyIri) {
        Optional<ResponseEntity<?>> owl = owlApiOnlyOrWarming(projectId, () -> {
            if (owlApiAnnotationPropertyQueryService == null) {
                throw new IllegalStateException("OWLAPI annotation property service unavailable");
            }
            return ResponseEntity.ok(Map.of("success", true, "data",
                    owlApiAnnotationPropertyQueryService.usage(projectId, propertyIri)));
        });
        if (owl.isPresent()) {
            return owl.get();
        }
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.annotationPropertyUsage(projectId, propertyIri)));
    }

    @GetMapping("/datatypes/{projectId:.+}")
    public ResponseEntity<?> datatypes(@PathVariable String projectId,
                                       @RequestParam(defaultValue = "100") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        Optional<ResponseEntity<?>> owl = owlApiOnlyOrWarming(projectId, () -> {
            if (owlApiDatatypeQueryService == null) {
                throw new IllegalStateException("OWLAPI datatype service unavailable");
            }
            return ResponseEntity.ok(Map.of("success", true, "data",
                    owlApiDatatypeQueryService.list(projectId, limit, offset)));
        });
        if (owl.isPresent()) {
            return owl.get();
        }
        try {
            return ResponseEntity.ok(Map.of("success", true, "data",
                    queryService.datatypes(projectId, limit, offset)));
        } catch (Exception e) {
            return sparqlListFallback(projectId, e);
        }
    }

    @GetMapping("/classes/usage/{projectId}")
    public ResponseEntity<?> classUsage(@PathVariable String projectId,
                                       @RequestParam String classIri) {
        String usageUserId = SparqlQueryContext.getUserId();
        boolean usageHasDraft = usageUserId != null && datasetService != null
                && datasetService.hasActiveDraftOverlay(projectId, usageUserId);

        if (!usageHasDraft && desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
            return ResponseEntity.ok(Map.of("success", true, "data",
                    desktopHierarchyService.classUsage(projectId, classIri)));
        }

        if (!usageHasDraft && entityUsageIndexService != null) {
            var cached = entityUsageIndexService.getUsage(projectId, classIri);
            if (cached.isPresent()) {
                return ResponseEntity.ok(Map.of("success", true, "data", cached.get(), "source", "index"));
            }
        }

        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.classUsage(projectId, classIri)));
    }

    @GetMapping("/properties/usage/{projectId}")
    public ResponseEntity<?> propertyUsage(@PathVariable String projectId,
                                          @RequestParam String propertyIri) {
        Optional<ResponseEntity<?>> owl = owlApiOnlyOrWarming(projectId, () -> {
            if (owlApiPropertyQueryService == null) {
                throw new IllegalStateException("OWLAPI property service unavailable");
            }
            return ResponseEntity.ok(Map.of("success", true, "data",
                    owlApiPropertyQueryService.usage(projectId, propertyIri)));
        });
        if (owl.isPresent()) {
            return owl.get();
        }
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.propertyUsage(projectId, propertyIri)));
    }

    @GetMapping("/datatypes/usage/{projectId}")
    public ResponseEntity<?> datatypeUsage(@PathVariable String projectId,
                                          @RequestParam String datatypeIri) {
        Optional<ResponseEntity<?>> owl = owlApiOnlyOrWarming(projectId, () -> {
            if (owlApiDatatypeQueryService == null) {
                throw new IllegalStateException("OWLAPI datatype service unavailable");
            }
            return ResponseEntity.ok(Map.of("success", true, "data",
                    owlApiDatatypeQueryService.usage(projectId, datatypeIri)));
        });
        if (owl.isPresent()) {
            return owl.get();
        }
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.datatypeUsage(projectId, datatypeIri)));
    }

    @GetMapping("/individuals/usage/{projectId}")
    public ResponseEntity<?> individualUsage(@PathVariable String projectId,
                                            @RequestParam String individualIri) {
        Optional<ResponseEntity<?>> owl = owlApiOnlyOrWarming(projectId, () -> {
            if (owlApiIndividualQueryService == null) {
                throw new IllegalStateException("OWLAPI individual service unavailable");
            }
            return ResponseEntity.ok(Map.of("success", true, "data",
                    owlApiIndividualQueryService.usage(projectId, individualIri)));
        });
        if (owl.isPresent()) {
            return owl.get();
        }
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.individualUsage(projectId, individualIri)));
    }

    @GetMapping("/classes/details/{projectId}")
    public ResponseEntity<?> classDetails(@PathVariable String projectId,
                                         @RequestParam String classIri,
                                         jakarta.servlet.http.HttpServletRequest httpRequest) {

        String ctxUserId = SparqlQueryContext.getUserId();
        boolean hasDraft = ctxUserId != null && datasetService != null
                && datasetService.hasActiveDraftOverlay(projectId, ctxUserId);

        if (!hasDraft && desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
            Map<String, Object> details = new java.util.LinkedHashMap<>(
                    desktopHierarchyService.classDetails(projectId, classIri));
            if (!details.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", true, "data", details));
            }
        }

        if (!hasDraft && classDetailCacheService != null) {
            var cached = classDetailCacheService.getDetails(projectId, classIri);
            if (cached.isPresent()) {
                return ResponseEntity.ok(Map.of("success", true, "data", cached.get()));
            }
        }
        try {
            if (httpRequest.isAsyncStarted()) {
                Object ctx = httpRequest.getAttribute("asyncContext");
                if (ctx != null) return ResponseEntity.status(499).build();
            }
        } catch (Exception ignored) {}

        Map<String, Object> details = queryService.classDetails(projectId, classIri);
        if (!hasDraft && classDetailCacheService != null && !details.isEmpty()) {
            classDetailCacheService.putDetails(projectId, classIri, details);
        }
        return ResponseEntity.ok(Map.of("success", true, "data", details));
    }

    @GetMapping("/classes/annotations/{projectId}")
    public ResponseEntity<?> classAnnotations(@PathVariable String projectId,
                                              @RequestParam String classIri) {
        String annCtxUserId = SparqlQueryContext.getUserId();
        boolean annHasDraft = annCtxUserId != null && datasetService != null
                && datasetService.hasActiveDraftOverlay(projectId, annCtxUserId);

        if (!annHasDraft && desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
            return ResponseEntity.ok(Map.of("success", true, "data",
                    desktopHierarchyService.classAnnotations(projectId, classIri)));
        }

        if (!annHasDraft && classDetailCacheService != null) {
            var cached = classDetailCacheService.getAnnotations(projectId, classIri);
            if (cached.isPresent()) {
                return ResponseEntity.ok(Map.of("success", true, "data", cached.get()));
            }
        }

        Map<String, Object> annotations = queryService.classAnnotations(projectId, classIri);
        if (!annHasDraft && classDetailCacheService != null && !annotations.isEmpty()) {
            classDetailCacheService.putAnnotationsIfAbsent(projectId, classIri, annotations);
        }
        return ResponseEntity.ok(Map.of("success", true, "data", annotations));
    }

    @PostMapping("/annotations/batch/{projectId:.+}")
    public ResponseEntity<?> batchAnnotations(@PathVariable String projectId,
                                              @org.springframework.web.bind.annotation.RequestBody
                                              Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<String> iris = (List<String>) body.get("iris");
            String propertyIri = (String) body.get("propertyIri");
            if (propertyIri == null || propertyIri.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "propertyIri is required"));
            }
            if (iris == null || iris.isEmpty()) {
                return ResponseEntity.ok(Map.of());
            }

            if (iris.size() > 5000) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("[batchAnnotations] IRI list truncated from {} to 5000 for project {}", iris.size(), projectId);
                iris = iris.subList(0, 5000);
            }

            iris = iris.stream()
                .filter(iri -> iri != null && !iri.isBlank() && (iri.startsWith("http") || iri.startsWith("urn")))
                .collect(java.util.stream.Collectors.toList());
            if (iris.isEmpty()) {
                return ResponseEntity.ok(Map.of());
            }
            Map<String, String> result;
            if (desktopHierarchyService != null && desktopHierarchyService.hasOntology(projectId)) {
                result = desktopHierarchyService.batchAnnotations(projectId, iris, propertyIri);
            } else {
                result = queryService.batchAnnotations(projectId, iris, propertyIri);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/classes/instances/{projectId}")
    public ResponseEntity<?> classInstances(@PathVariable String projectId,
                                           @RequestParam String classIri) {

        Optional<ResponseEntity<?>> owl = owlApiOnlyOrWarming(projectId, () -> {
            if (desktopHierarchyService == null) {
                throw new IllegalStateException("OWLAPI hierarchy service unavailable");
            }
            return ResponseEntity.ok(desktopHierarchyService.classInstances(projectId, classIri));
        });
        if (owl.isPresent()) {
            return owl.get();
        }

        String instUserId = SparqlQueryContext.getUserId();
        boolean instHasDraft = instUserId != null && datasetService != null
                && datasetService.hasActiveDraftOverlay(projectId, instUserId);
        List<Map<String, Object>> instances = queryService.getClassInstances(projectId, classIri);
        log.info("[INSTANCES] path=sparql project={} class={} count={} hasDraft={}", projectId, classIri.substring(Math.max(0, classIri.lastIndexOf('#') + 1)), instances.size(), instHasDraft);
        if (reasonerClassInstanceMerger != null) {
            instances = reasonerClassInstanceMerger.mergeInferred(projectId, classIri, instances);
            log.info("[INSTANCES] path=sparql after-merge count={}", instances.size());
        }
        return ResponseEntity.ok(instances);
    }

    @GetMapping("/classes/instance-counts/{projectId:.+}")
    public ResponseEntity<?> classInstanceCounts(@PathVariable String projectId) {
        Optional<ResponseEntity<?>> owl = owlApiOnlyOrWarming(projectId, () -> {
            if (desktopHierarchyService == null) {
                throw new IllegalStateException("OWLAPI hierarchy service unavailable");
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", desktopHierarchyService.classInstanceCounts(projectId)
            ));
        });
        if (owl.isPresent()) {
            return owl.get();
        }
        try {
            Map<String, Map<String, Integer>> counts = queryService.getClassInstanceCounts(projectId);
            if (reasonerClassInstanceMerger != null) {
                counts = reasonerClassInstanceMerger.mergeInferredCounts(projectId, counts);
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", counts
            ));
        } catch (Exception e) {
            return sparqlListFallback(projectId, e);
        }
    }

    @GetMapping("/{projectId}/individuals/{individualIri}")
    public ResponseEntity<?> individualDetails(@PathVariable String projectId,
                                              @PathVariable String individualIri) {
        Optional<ResponseEntity<?>> owl = owlApiOnlyOrWarming(projectId, () -> {
            if (owlApiIndividualQueryService == null) {
                throw new IllegalStateException("OWLAPI individual service unavailable");
            }
            return ResponseEntity.ok(owlApiIndividualQueryService.details(projectId, individualIri));
        });
        if (owl.isPresent()) {
            return owl.get();
        }
        Map<String, Object> details = queryService.getIndividualDetails(projectId, individualIri);
        if (reasonerIndividualAssertionMerger != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> assertions =
                    (List<Map<String, Object>>) details.getOrDefault("propertyAssertions", List.of());
            details.put("propertyAssertions",
                    reasonerIndividualAssertionMerger.mergeInferred(projectId, individualIri, assertions));
        }
        return ResponseEntity.ok(details);
    }

    @GetMapping("/individual-details/{projectId}")
    public ResponseEntity<?> individualDetailsByParam(@PathVariable String projectId,
                                                      @RequestParam String individualIri) {
        Optional<ResponseEntity<?>> owl = owlApiOnlyOrWarming(projectId, () -> {
            if (owlApiIndividualQueryService == null) {
                throw new IllegalStateException("OWLAPI individual service unavailable");
            }
            return ResponseEntity.ok(owlApiIndividualQueryService.details(projectId, individualIri));
        });
        if (owl.isPresent()) {
            return owl.get();
        }
        Map<String, Object> details = queryService.getIndividualDetails(projectId, individualIri);
        if (reasonerIndividualAssertionMerger != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> assertions =
                    (List<Map<String, Object>>) details.getOrDefault("propertyAssertions", List.of());
            details.put("propertyAssertions",
                    reasonerIndividualAssertionMerger.mergeInferred(projectId, individualIri, assertions));
        }
        return ResponseEntity.ok(details);
    }

    @GetMapping("/debug/{projectId}")
    public ResponseEntity<?> debug(@PathVariable String projectId) {
        return ResponseEntity.ok(queryService.debugInfo(projectId));
    }

    @GetMapping("/{projectId}/schema")
    public ResponseEntity<?> getOntologySchema(@PathVariable String projectId) {
        Optional<ResponseEntity<?>> owl = owlApiOnlyOrWarming(projectId, () -> {
            if (owlApiMetadataQueryService == null) {
                throw new IllegalStateException("OWLAPI metadata service unavailable");
            }
            return ResponseEntity.ok(owlApiMetadataQueryService.schema(projectId));
        });
        if (owl.isPresent()) {
            return owl.get();
        }

        if (projectImportService != null) {
            projectImportService.syncProjectToFuseki(projectId);
        }
        return ResponseEntity.ok(queryService.getOntologySchema(projectId));
    }
}

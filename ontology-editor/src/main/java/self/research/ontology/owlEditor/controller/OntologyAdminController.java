package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.HierarchyIndexService;
import self.research.ontology.owlEditor.service.OntologyAdminService;
import self.research.ontology.owlEditor.service.TopLevelClassCacheService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology/ontology")
@CrossOrigin
public class OntologyAdminController {

    private final OntologyAdminService adminService;
    private final TopLevelClassCacheService topLevelCacheService;
    private final HierarchyIndexService hierarchyIndexService;

    public OntologyAdminController(OntologyAdminService adminService,
                                   TopLevelClassCacheService topLevelCacheService,
                                   HierarchyIndexService hierarchyIndexService) {
        this.adminService = adminService;
        this.topLevelCacheService = topLevelCacheService;
        this.hierarchyIndexService = hierarchyIndexService;
    }

    /** Evict stale top-level class hierarchy cache for a project.
     *  Call after deploying a fix to the orphan/hierarchy query.
     *  GET /api/ontology/ontology/cache/evict/{projectId}
     */
    @PostMapping("/cache/evict/{projectId:.+}")
    public ResponseEntity<?> evictHierarchyCache(@PathVariable String projectId) {
        topLevelCacheService.evict(projectId);
        hierarchyIndexService.evict(projectId);
        hierarchyIndexService.scheduleBuild(projectId);
        return ResponseEntity.ok(Map.of("success", true, "message",
            "Hierarchy caches evicted and snapshot rebuild scheduled for project " + projectId));
    }

    /** Evict ALL projects' top-level cache — use after a hierarchy query fix deployment. */
    @PostMapping("/cache/evict-all")
    public ResponseEntity<?> evictAllHierarchyCache() {
        topLevelCacheService.evictAll();
        return ResponseEntity.ok(Map.of("success", true, "message", "All legacy top-level SPARQL caches evicted"));
    }

    @PostMapping("/hierarchy/rebuild/{projectId:.+}")
    public ResponseEntity<?> rebuildHierarchySnapshot(@PathVariable String projectId) {
        hierarchyIndexService.scheduleBuild(projectId);
        return ResponseEntity.accepted().body(Map.of(
                "success", true,
                "message", "Protégé-parity hierarchy snapshot rebuild scheduled",
                "projectId", projectId));
    }

    @GetMapping("/id/{projectId}")
    public ResponseEntity<?> getOntologyId(@PathVariable String projectId) {
        return ResponseEntity.ok(Map.of("success", true, "data", adminService.getOntologyId(projectId)));
    }

    @PutMapping("/id/{projectId}")
    public ResponseEntity<?> updateOntologyId(@PathVariable String projectId,
                                              @RequestBody OntologyIdRequest request) {
        if (request == null || request.ontologyIRI == null || request.ontologyIRI.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "ontologyIRI is required"));
        }
        adminService.updateOntologyId(projectId, request.ontologyIRI, request.versionIRI);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/annotations/{projectId}")
    public ResponseEntity<?> listOntologyAnnotations(@PathVariable String projectId) {
        return ResponseEntity.ok(Map.of("success", true, "data", adminService.listOntologyAnnotations(projectId)));
    }

    @PostMapping("/annotations/{projectId}")
    public ResponseEntity<?> addOntologyAnnotation(@PathVariable String projectId,
                                                   @RequestBody OntologyAnnotationRequest request) {
        if (request == null || request.propertyIri == null || request.propertyIri.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "propertyIri is required"));
        }
        adminService.addOntologyAnnotation(projectId, request.propertyIri, request.value, request.datatypeIri);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PutMapping("/annotations/{projectId}")
    public ResponseEntity<?> updateOntologyAnnotation(@PathVariable String projectId,
                                                      @RequestBody OntologyAnnotationUpdateRequest request) {
        if (request == null || request.propertyIri == null || request.propertyIri.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "propertyIri is required"));
        }
        adminService.updateOntologyAnnotation(projectId, request.propertyIri, request.oldValue, request.newValue, request.datatypeIri);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/annotations/{projectId}")
    public ResponseEntity<?> deleteOntologyAnnotation(@PathVariable String projectId,
                                                      @RequestParam String propertyIri,
                                                      @RequestParam String value,
                                                      @RequestParam(required = false) String datatypeIri) {
        adminService.deleteOntologyAnnotation(projectId, propertyIri, value, datatypeIri);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/imports/{projectId}")
    public ResponseEntity<?> addImport(@PathVariable String projectId,
                                       @RequestBody ImportRequest request) {
        if (request == null || request.importIri == null || request.importIri.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "importIri is required"));
        }
        adminService.addImport(projectId, request.importIri);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/imports/{projectId}")
    public ResponseEntity<?> removeImport(@PathVariable String projectId,
                                          @RequestParam String importIri) {
        adminService.removeImport(projectId, importIri);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/prefixes/{projectId}")
    public ResponseEntity<?> getPrefixes(@PathVariable String projectId) {
        return ResponseEntity.ok(Map.of("success", true, "data", adminService.getPrefixes(projectId)));
    }

    @GetMapping("/imports/{projectId}")
    public ResponseEntity<?> getImports(@PathVariable String projectId) {
        return ResponseEntity.ok(Map.of("success", true, "data", adminService.getImports(projectId)));
    }

    @PutMapping("/prefixes/{projectId}")
    public ResponseEntity<?> updatePrefixes(@PathVariable String projectId,
                                            @RequestBody List<PrefixMappingRequest> prefixes) {
        Map<String, String> map = new HashMap<>();
        if (prefixes != null) {
            for (PrefixMappingRequest item : prefixes) {
                if (item == null || item.prefix == null) {
                    continue;
                }
                map.put(item.prefix, item.namespace);
            }
        }
        adminService.updatePrefixes(projectId, map);
        return ResponseEntity.ok(Map.of("success", true));
    }

    public static class OntologyIdRequest {
        public String ontologyIRI;
        public String versionIRI;
    }

    public static class OntologyAnnotationRequest {
        public String propertyIri;
        public String value;
        public String datatypeIri;
    }

    public static class OntologyAnnotationUpdateRequest {
        public String propertyIri;
        public String oldValue;
        public String newValue;
        public String datatypeIri;
    }

    public static class ImportRequest {
        public String importIri;
    }

    public static class PrefixMappingRequest {
        public String prefix;
        public String namespace;
    }
}

package self.research.ontology.owlEditor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.model.collaboration.EditOperation;
import self.research.ontology.owlEditor.service.OntologyMetadataService;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.OwlApiFastPathSupport;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.owlapi.OwlApiOntologyMetadataQueryService;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/ontology/metadata")
@CrossOrigin
public class OntologyMetadataController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OntologyMetadataService metadataService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ProjectImportService projectImportService;
    private final OntologyMutationService mutationService;
    private final OwlApiFastPathSupport owlApiFastPathSupport;
    private final OwlApiOntologyMetadataQueryService owlApiMetadataQueryService;

    public OntologyMetadataController(OntologyMetadataService metadataService,
                                      SimpMessagingTemplate messagingTemplate,
                                      ProjectImportService projectImportService,
                                      OntologyMutationService mutationService,
                                      OwlApiFastPathSupport owlApiFastPathSupport,
                                      @org.springframework.beans.factory.annotation.Autowired(required = false)
                                      OwlApiOntologyMetadataQueryService owlApiMetadataQueryService) {
        this.metadataService = metadataService;
        this.messagingTemplate = messagingTemplate;
        this.projectImportService = projectImportService;
        this.mutationService = mutationService;
        this.owlApiFastPathSupport = owlApiFastPathSupport;
        this.owlApiMetadataQueryService = owlApiMetadataQueryService;
    }

    private void broadcastMetadataChange(String projectId, EditOperation.OperationType opType,
                                         String nodeId, HttpServletRequest request) {
        try {
            String userId = "anonymous";
            String username = "Anonymous";
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String[] parts = authHeader.substring(7).split("\\.");
                if (parts.length == 3) {
                    byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
                    JsonNode claims = MAPPER.readTree(decoded);
                    if (claims.has("userId")) userId = claims.get("userId").asText();
                    if (claims.has("sub")) username = claims.get("sub").asText();
                }
            }
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", opType.name());
            msg.put("projectId", projectId);
            msg.put("nodeId", nodeId);
            msg.put("userId", userId);
            msg.put("username", username);
            msg.put("timestamp", System.currentTimeMillis());
            messagingTemplate.convertAndSend("/topic/ontology/" + projectId, msg);
        } catch (Exception e) {
            log.debug("Failed to broadcast metadata change: {}", e.getMessage());
        }
    }

    @GetMapping("/{projectId:.+}")
    public ResponseEntity<?> getMetadata(@PathVariable String projectId) {
        try {

            Map<String, Object> metadata = metadataService.getMetadata(projectId);
            return ResponseEntity.ok(Map.of("success", true, "data", metadata));
        } catch (Exception e) {
            log.error("Error fetching metadata", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/{projectId:.+}/annotations")
    public ResponseEntity<?> getAnnotations(@PathVariable String projectId) {
        if (owlApiMetadataQueryService != null) {
            Optional<ResponseEntity<?>> owlApiResponse = owlApiFastPathSupport.owlApiOnlyOrWarming(projectId,
                    () -> ResponseEntity.ok(Map.of("success", true,
                            "data", owlApiMetadataQueryService.annotations(projectId))));
            if (owlApiResponse.isPresent()) {
                return owlApiResponse.get();
            }
        }
        try {

            projectImportService.syncProjectToFuseki(projectId);
            List<Map<String, String>> annotations = metadataService.getOntologyAnnotations(projectId);
            return ResponseEntity.ok(Map.of("success", true, "data", annotations));
        } catch (Exception e) {
            log.error("Error fetching annotations", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/annotations")
    public ResponseEntity<?> addAnnotation(@PathVariable String projectId,
                                          @RequestBody Map<String, String> request,
                                          HttpServletRequest httpRequest) {
        try {
            String propertyIri = request.get("propertyIri");
            String value = request.get("value");
            String language = request.get("language");
            String datatype = request.get("datatype");
            boolean draft = Boolean.parseBoolean(request.get("draft"));
            String userId = request.get("userId");
            metadataService.addOntologyAnnotation(projectId, propertyIri, value, language, datatype, draft, userId);
            if (!draft) {
                broadcastMetadataChange(projectId, EditOperation.OperationType.ONTOLOGY_ANNOTATION_ADDED, propertyIri, httpRequest);
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error adding annotation", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PutMapping("/{projectId}/annotations")
    public ResponseEntity<?> updateAnnotation(@PathVariable String projectId,
                                             @RequestBody Map<String, String> request,
                                             HttpServletRequest httpRequest) {
        try {
            String propertyIri = request.get("propertyIri");
            String originalPropertyIri = request.getOrDefault("originalPropertyIri", propertyIri);
            String oldValue = request.get("oldValue");
            String newValue = request.get("newValue");
            String language = request.get("language");
            String datatype = request.get("datatype");
            boolean draft = Boolean.parseBoolean(request.get("draft"));
            String userId = request.get("userId");
            metadataService.updateOntologyAnnotation(projectId, propertyIri, oldValue, newValue, language, datatype,
                    originalPropertyIri, draft, userId);
            if (!draft) {
                broadcastMetadataChange(projectId, EditOperation.OperationType.ONTOLOGY_ANNOTATION_MODIFIED, propertyIri, httpRequest);
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error updating annotation", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @DeleteMapping("/{projectId}/annotations")
    public ResponseEntity<?> deleteAnnotation(@PathVariable String projectId,
                                             @RequestParam String propertyIri,
                                             @RequestParam String value,
                                             @RequestParam(required = false) String language,
                                             @RequestParam(required = false, defaultValue = "false") boolean draft,
                                             @RequestParam(required = false) String userId,
                                             HttpServletRequest httpRequest) {
        try {
            metadataService.deleteOntologyAnnotation(projectId, propertyIri, value, language, draft, userId);
            if (!draft) {
                broadcastMetadataChange(projectId, EditOperation.OperationType.ONTOLOGY_ANNOTATION_DELETED, propertyIri, httpRequest);
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error deleting annotation", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/{projectId:.+}/imports")
    public ResponseEntity<?> getImports(@PathVariable String projectId) {
        if (owlApiMetadataQueryService != null) {
            Optional<ResponseEntity<?>> owlApiResponse = owlApiFastPathSupport.owlApiOnlyOrWarming(projectId,
                    () -> ResponseEntity.ok(Map.of("success", true,
                            "data", owlApiMetadataQueryService.imports(projectId))));
            if (owlApiResponse.isPresent()) {
                return owlApiResponse.get();
            }
        }
        try {

            projectImportService.syncProjectToFuseki(projectId);
            List<String> imports = metadataService.getOntologyImports(projectId);
            return ResponseEntity.ok(Map.of("success", true, "data", imports));
        } catch (Exception e) {
            log.error("Error fetching imports", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/{projectId:.+}/imports/closure")
    public ResponseEntity<?> getImportClosure(@PathVariable String projectId) {
        try {

            projectImportService.syncProjectToFuseki(projectId);
            Map<String, List<Map<String, Object>>> closure = metadataService.getImportClosure(projectId);
            return ResponseEntity.ok(Map.of("success", true, "closure", closure));
        } catch (Exception e) {
            log.error("Error fetching import closure", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/imports")
    public ResponseEntity<?> addImport(@PathVariable String projectId,
                                       @RequestBody Map<String, String> request,
                                       HttpServletRequest httpRequest) {
        try {
            String importIri = request.get("importIri");
            boolean draft = Boolean.parseBoolean(request.get("draft"));
            String userId = request.get("userId");
            metadataService.addOntologyImport(projectId, importIri, draft, userId);

            Map<String, Object> resolution = resolveManualImportContent(projectId, importIri, draft, userId);

            if (!draft) {
                broadcastMetadataChange(projectId, EditOperation.OperationType.IMPORT_ADDED, importIri, httpRequest);
            }
            return ResponseEntity.ok(Map.of("success", true, "resolution", resolution));
        } catch (Exception e) {
            log.error("Error adding import", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private Map<String, Object> resolveManualImportContent(String projectId, String importIri, boolean draft, String userId) {
        Map<String, Object> resolution = new HashMap<>();
        try {
            ProjectImportService.ImportFetchResult fetch = projectImportService.fetchImportContent(projectId, importIri);
            switch (fetch.status()) {
                case LOADED -> {
                    String insertSparql = "INSERT DATA {\n" + fetch.insertTriplesBody() + "\n}";
                    mutationService.applyRawUpdate(projectId, insertSparql, draft, userId);
                    resolution.put("status", "loaded");
                    resolution.put("tripleCount", fetch.tripleCount());
                }
                case DECLARED_ONLY -> {
                    resolution.put("status", "declaredOnly");
                    resolution.put("reason", fetch.detail());
                }
                case TOO_LARGE -> {
                    resolution.put("status", "tooLarge");
                    resolution.put("reason", fetch.detail());
                }
                case FAILED -> {
                    resolution.put("status", "failed");
                    resolution.put("reason", fetch.detail());
                }
            }
        } catch (Exception e) {
            log.warn("[Import] Resolving content for manual import {} (project {}) failed: {}", importIri, projectId, e.getMessage());
            resolution.put("status", "declaredOnly");
            resolution.put("reason", e.getMessage());
        }
        return resolution;
    }

    @DeleteMapping("/{projectId}/imports")
    public ResponseEntity<?> deleteImport(@PathVariable String projectId,
                                         @RequestParam String importIri,
                                         @RequestParam(required = false, defaultValue = "false") boolean draft,
                                         @RequestParam(required = false) String userId,
                                         HttpServletRequest httpRequest) {
        try {
            metadataService.deleteOntologyImport(projectId, importIri, draft, userId);
            if (!draft) {
                broadcastMetadataChange(projectId, EditOperation.OperationType.IMPORT_REMOVED, importIri, httpRequest);
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error deleting import", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PutMapping("/{projectId}/iri")
    public ResponseEntity<?> updateOntologyIRIs(@PathVariable String projectId,
                                               @RequestBody Map<String, String> request) {
        try {
            String ontologyIri = request.get("ontologyIri");
            String versionIri = request.get("versionIri");
            boolean draft = Boolean.parseBoolean(request.get("draft"));
            String userId = request.get("userId");
            metadataService.updateOntologyIRIs(projectId, ontologyIri, versionIri, draft, userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error updating ontology IRIs", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/{projectId}/gci")
    public ResponseEntity<?> getGeneralClassAxioms(@PathVariable String projectId) {
        try {

            projectImportService.syncProjectToFuseki(projectId);
            List<Map<String, Object>> gcis = metadataService.getGeneralClassAxioms(projectId);
            return ResponseEntity.ok(Map.of("success", true, "data", gcis));
        } catch (Exception e) {
            log.error("Error fetching general class axioms", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/gci")
    public ResponseEntity<?> addGCI(@PathVariable String projectId,
                                   @RequestBody Map<String, String> request,
                                   HttpServletRequest httpRequest) {
        try {
            String subClass = request.get("subClass");
            String superClass = request.get("superClass");
            boolean draft = Boolean.parseBoolean(request.get("draft"));
            String userId = request.get("userId");
            metadataService.addGCI(projectId, subClass, superClass, draft, userId);
            if (!draft) {
                broadcastMetadataChange(projectId, EditOperation.OperationType.GCI_ADDED, subClass, httpRequest);
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error adding GCI", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PutMapping("/{projectId}/gci/{index}")
    public ResponseEntity<?> updateGCI(@PathVariable String projectId,
                                      @PathVariable int index,
                                      @RequestBody Map<String, String> request,
                                      HttpServletRequest httpRequest) {
        try {
            String oldValue = request.get("oldValue");
            String subClass = request.get("subClass");
            String superClass = request.get("superClass");
            boolean draft = Boolean.parseBoolean(request.get("draft"));
            String userId = request.get("userId");

            if (oldValue != null) {
                metadataService.deleteGCI(projectId, oldValue, draft, userId);
            }
            metadataService.addGCI(projectId, subClass, superClass, draft, userId);
            if (!draft) {
                broadcastMetadataChange(projectId, EditOperation.OperationType.GCI_ADDED, subClass, httpRequest);
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error updating GCI", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @DeleteMapping("/{projectId}/gci")
    public ResponseEntity<?> deleteGCI(@PathVariable String projectId,
                                      @RequestParam String value,
                                      @RequestParam(required = false, defaultValue = "false") boolean draft,
                                      @RequestParam(required = false) String userId,
                                      HttpServletRequest httpRequest) {
        try {
            metadataService.deleteGCI(projectId, value, draft, userId);
            if (!draft) {
                broadcastMetadataChange(projectId, EditOperation.OperationType.GCI_REMOVED, value, httpRequest);
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error deleting GCI", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/{projectId:.+}/prefixes")
    public ResponseEntity<?> getPrefixes(@PathVariable String projectId) {
        try {

            List<Map<String, String>> prefixes = metadataService.getPrefixes(projectId);
            return ResponseEntity.ok(Map.of("success", true, "data", prefixes));
        } catch (Exception e) {
            log.error("Error fetching prefixes", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/prefixes")
    public ResponseEntity<?> updatePrefix(@PathVariable String projectId,
                                         @RequestBody Map<String, String> request) {
        try {
            String prefix = request.get("prefix");
            String iri = request.get("iri");
            String oldPrefix = request.get("oldPrefix");
            metadataService.updatePrefix(projectId, prefix, iri, oldPrefix);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error updating prefix", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @DeleteMapping("/{projectId}/prefixes")
    public ResponseEntity<?> deletePrefix(@PathVariable String projectId,
                                         @RequestParam String prefix) {
        try {
            metadataService.deletePrefix(projectId, prefix);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error deleting prefix", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }
}

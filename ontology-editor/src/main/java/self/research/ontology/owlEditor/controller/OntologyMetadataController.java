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

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for ontology metadata operations (annotations, imports, GCIs)
 */
@Slf4j
@RestController
@RequestMapping("/api/ontology/metadata")
@CrossOrigin
public class OntologyMetadataController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OntologyMetadataService metadataService;
    private final SimpMessagingTemplate messagingTemplate;

    public OntologyMetadataController(OntologyMetadataService metadataService,
                                      SimpMessagingTemplate messagingTemplate) {
        this.metadataService = metadataService;
        this.messagingTemplate = messagingTemplate;
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

    // ========== Ontology Annotations ==========

    @GetMapping("/{projectId:.+}/annotations")
    public ResponseEntity<?> getAnnotations(@PathVariable String projectId) {
        try {
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
            metadataService.addOntologyAnnotation(projectId, propertyIri, value, language, datatype);
            broadcastMetadataChange(projectId, EditOperation.OperationType.ONTOLOGY_ANNOTATION_ADDED, propertyIri, httpRequest);
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
            metadataService.updateOntologyAnnotation(projectId, propertyIri, oldValue, newValue, language, datatype, originalPropertyIri);
            broadcastMetadataChange(projectId, EditOperation.OperationType.ONTOLOGY_ANNOTATION_MODIFIED, propertyIri, httpRequest);
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
                                             HttpServletRequest httpRequest) {
        try {
            metadataService.deleteOntologyAnnotation(projectId, propertyIri, value, language);
            broadcastMetadataChange(projectId, EditOperation.OperationType.ONTOLOGY_ANNOTATION_DELETED, propertyIri, httpRequest);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error deleting annotation", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ========== Ontology Imports ==========

    @GetMapping("/{projectId:.+}/imports")
    public ResponseEntity<?> getImports(@PathVariable String projectId) {
        try {
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
            metadataService.addOntologyImport(projectId, importIri);
            broadcastMetadataChange(projectId, EditOperation.OperationType.IMPORT_ADDED, importIri, httpRequest);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error adding import", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @DeleteMapping("/{projectId}/imports")
    public ResponseEntity<?> deleteImport(@PathVariable String projectId,
                                         @RequestParam String importIri,
                                         HttpServletRequest httpRequest) {
        try {
            metadataService.deleteOntologyImport(projectId, importIri);
            broadcastMetadataChange(projectId, EditOperation.OperationType.IMPORT_REMOVED, importIri, httpRequest);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error deleting import", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ========== Ontology IRIs ==========

    @PutMapping("/{projectId}/iri")
    public ResponseEntity<?> updateOntologyIRIs(@PathVariable String projectId,
                                               @RequestBody Map<String, String> request) {
        try {
            String ontologyIri = request.get("ontologyIri");
            String versionIri = request.get("versionIri");
            metadataService.updateOntologyIRIs(projectId, ontologyIri, versionIri);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error updating ontology IRIs", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ========== General Class Axioms ==========

    @GetMapping("/{projectId}/gci")
    public ResponseEntity<?> getGeneralClassAxioms(@PathVariable String projectId) {
        try {
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

    // ========== Prefixes ==========

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

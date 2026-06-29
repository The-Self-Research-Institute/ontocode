package self.research.ontology.owlEditor.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.service.EntityRenameService;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology/entity")
@CrossOrigin(originPatterns = "*")
public class EntityController {

    private static final String DESKTOP_USER_ID = "desktop-user-local";

    private final EntityRenameService renameService;
    private final CollaborativeEditService collaborativeEditService;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    public EntityController(EntityRenameService renameService,
                            CollaborativeEditService collaborativeEditService) {
        this.renameService = renameService;
        this.collaborativeEditService = collaborativeEditService;
    }

    @PostMapping("/{projectId}/rename")
    public ResponseEntity<?> renameEntity(@PathVariable String projectId,
                                          @RequestBody RenameEntityRequest request,
                                          @RequestParam(required = false, defaultValue = "true") boolean draft,
                                          @RequestParam(required = false) String userId,
                                          @RequestParam(required = false) String username) {
        if (request == null || isBlank(request.oldIri) || isBlank(request.newIri)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "oldIri and newIri are required"));
        }
        String effectiveUserId = desktopMode ? DESKTOP_USER_ID : (userId != null ? userId : "anonymous");
        String effectiveUsername = username != null ? username : "Anonymous";
        try {
            if (draft) {
                renameService.renameEntityDraft(projectId, effectiveUserId, request.oldIri.trim(), request.newIri.trim());
            } else {
                renameService.renameEntity(projectId, request.oldIri.trim(), request.newIri.trim());
            }
            collaborativeEditService.broadcastMutation(projectId,
                    new OntologyMutationService.MutationOp(
                            "renameEntity", request.oldIri, null, null, null, request.newIri,
                            null, null, null, null, null, null, null, null, null),
                    effectiveUserId,
                    effectiveUsername);
            return ResponseEntity.ok(Map.of(
                    "success", true, "draft", draft,
                    "oldIri", request.oldIri, "newIri", request.newIri));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Rename failed"));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static class RenameEntityRequest {
        public String oldIri;
        public String newIri;
    }
}

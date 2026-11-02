package self.research.ontology.owlEditor.service;

import org.springframework.http.ResponseEntity;

import java.util.Map;

public final class PublicEditGuard {

    public static final String CODE = "PUBLIC_EDIT_DISABLED";
    public static final String MESSAGE =
        "The project owner turned off public editing. Switch to Draft Mode to make your changes.";

    private PublicEditGuard() {
    }

    public static boolean blocks(ProjectMetadataService metadataService, String projectId, String userId) {
        if (!metadataService.isRequireDraftForMembers(projectId)) {
            return false;
        }
        String ownerEmail = metadataService.getOwnerEmail(projectId).orElse(null);
        return userId == null || !userId.equals(ownerEmail);
    }

    public static ResponseEntity<Map<String, Object>> rejection() {
        return ResponseEntity.status(403).body(Map.of(
            "success", false,
            "draft", false,
            "error", MESSAGE,
            "code", CODE,
            "requireDraftForMembers", true,
            "draftAllowed", true
        ));
    }
}

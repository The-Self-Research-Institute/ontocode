package self.research.ontology.auth.util;

import org.springframework.http.ResponseEntity;

import java.util.Map;

public final class WorkspaceErrors {

    private WorkspaceErrors() {
    }

    public static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    public static ResponseEntity<Map<String, String>> forbidden(String message) {
        return ResponseEntity.status(403).body(Map.of("error", message));
    }

    public static ResponseEntity<Map<String, String>> userNotFound() {
        return badRequest("User not found");
    }

    public static ResponseEntity<Map<String, String>> workspaceNotFound() {
        return badRequest("Workspace not found");
    }

    public static ResponseEntity<Map<String, String>> noWorkspaceAccess() {
        return forbidden("You don't have access to this workspace");
    }
}

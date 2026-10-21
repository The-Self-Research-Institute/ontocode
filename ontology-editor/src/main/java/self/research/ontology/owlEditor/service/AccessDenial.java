package self.research.ontology.owlEditor.service;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

public record AccessDenial(HttpStatus status, String message) {

    public static AccessDenial unauthenticated() {
        return new AccessDenial(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    public static AccessDenial forbidden(String message) {
        return new AccessDenial(HttpStatus.FORBIDDEN, message);
    }

    public ResponseEntity<Map<String, Object>> toResponse() {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", message));
    }
}

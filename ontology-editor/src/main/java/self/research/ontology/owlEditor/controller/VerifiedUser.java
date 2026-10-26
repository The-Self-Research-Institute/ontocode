package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.Map;
import java.util.Optional;

final class VerifiedUser {

    record Result(String userId, ResponseEntity<Map<String, Object>> denial) {}

    private VerifiedUser() {
    }

    static Result resolve(HttpServletRequest request, String claimedUserId) {
        if (request == null) {
            return new Result(claimedUserId, null);
        }
        Optional<String> verified = JwtIdentityExtractor.extractUserId(request);
        if (verified.isPresent()) {
            return new Result(verified.get(), null);
        }
        if (JwtIdentityExtractor.signatureRequired()) {
            return new Result(null, ResponseEntity.status(401).body(Map.of(
                    "success", false, "error", "Sign in again to continue")));
        }
        return new Result(claimedUserId, null);
    }
}

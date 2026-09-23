package self.research.ontology.owlEditor.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the caller's email out of an already-authenticated request's Bearer JWT.
 * Same trust assumption as the rest of this codebase's controllers: signature
 * verification happens upstream (gateway/auth filter) before a request reaches
 * here — this only decodes claims from a token already treated as trusted.
 */
@Slf4j
public final class JwtIdentityExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JwtIdentityExtractor() {
    }

    public static Optional<String> extractEmail(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        try {
            String token = authHeader.substring(7);
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return Optional.empty();
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = MAPPER.readValue(payload, Map.class);
            Object email = claims.get("email");
            return email != null ? Optional.of(email.toString()) : Optional.empty();
        } catch (Exception e) {
            log.warn("[Assistant] Failed to decode JWT for identity extraction: {}", e.getMessage());
            return Optional.empty();
        }
    }
}

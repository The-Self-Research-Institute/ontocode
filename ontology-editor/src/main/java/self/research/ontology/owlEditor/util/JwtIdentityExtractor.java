package self.research.ontology.owlEditor.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

@Slf4j
public final class JwtIdentityExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile SecretKey signatureKey;

    private JwtIdentityExtractor() {
    }

    public static void requireSignature(SecretKey key) {
        signatureKey = key;
    }

    public static boolean signatureRequired() {
        return signatureKey != null;
    }

    public static Optional<String> extractEmail(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authHeader.substring(7).trim();
        SecretKey key = signatureKey;
        return key != null ? verifiedEmail(token, key) : unverifiedEmail(token);
    }

    private static Optional<String> verifiedEmail(String token, SecretKey key) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Object email = claims.get("email");
            String identity = email != null ? email.toString() : claims.getSubject();
            return identity == null || identity.isBlank() ? Optional.empty() : Optional.of(identity);
        } catch (Exception e) {
            log.warn("[Assistant] Rejected a bearer token that failed signature verification: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Optional<String> unverifiedEmail(String token) {
        try {
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

package self.research.ontology.owlEditor.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;

/**
 * Lightweight JWT payload parsing for optional request parameters (same secret validation is done elsewhere).
 */
public final class JwtClaimUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JwtClaimUtils() {
    }

    public static String extractEmail(String authHeader) {
        JsonNode claims = parsePayload(authHeader);
        if (claims == null || !claims.has("email")) {
            return null;
        }
        JsonNode emailNode = claims.get("email");
        if (emailNode == null || emailNode.isNull() || !emailNode.isTextual()) {
            return null;
        }
        String email = emailNode.asText().trim();
        return !email.isEmpty() ? email : null;
    }

    /** @return { plan, userId } or null if token missing/invalid; userId null if absent or blank */
    public static String[] extractPlanAndUserId(String authHeader) {
        JsonNode claims = parsePayload(authHeader);
        if (claims == null) {
            return null;
        }
        String plan = "FREE";
        if (claims.has("plan") && !claims.get("plan").isNull()) {
            String p = claims.get("plan").asText().trim();
            if (!p.isEmpty()) {
                plan = p;
            }
        }
        String userId = null;
        if (claims.has("userId") && !claims.get("userId").isNull()) {
            String u = claims.get("userId").asText().trim();
            userId = u.isEmpty() ? null : u;
        }
        return new String[]{plan, userId};
    }

    private static JsonNode parsePayload(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String[] parts = authHeader.substring(7).trim().split("\\.");
        if (parts.length != 3) {
            return null;
        }
        String payload = parts[1];
        // Base64-URL payloads may omit padding; the decoder is strict on some JREs
        int r = payload.length() % 4;
        if (r == 2) {
            payload += "==";
        } else if (r == 3) {
            payload += "=";
        } else if (r == 1) {
            return null; // invalid segment length
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            return MAPPER.readTree(decoded);
        } catch (Exception e) {
            return null;
        }
    }
}

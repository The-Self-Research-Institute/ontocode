package self.research.ontology.owlEditor.config;

import jakarta.servlet.http.HttpServletRequest;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.Map;

public record CallerIdentity(String userId, String email) {

    public static CallerIdentity of(HttpServletRequest request) {
        Object verifiedEmail = request.getAttribute(EditorApiAuthInterceptor.VERIFIED_EMAIL_ATTRIBUTE);
        String email = verifiedEmail != null ? verifiedEmail.toString()
                : JwtIdentityExtractor.extractEmail(request).orElse(null);
        Object verifiedUserId = request.getAttribute(EditorApiAuthInterceptor.VERIFIED_USER_ID_ATTRIBUTE);
        String userId = verifiedUserId != null ? verifiedUserId.toString() : null;
        if (userId == null) {
            String[] claims = JwtClaimUtils.extractPlanAndUserId(request.getHeader("Authorization"));
            userId = claims == null ? null : claims[1];
        }
        return new CallerIdentity(userId, email);
    }

    public static CallerIdentity ofSession(Map<String, Object> attributes) {
        if (attributes == null) {
            return new CallerIdentity(null, null);
        }
        return new CallerIdentity((String) attributes.get("userId"), (String) attributes.get("email"));
    }

    public String userIdOr(String claimed) {
        return userId != null && !userId.isBlank() ? userId : claimed;
    }

    public boolean signedIn() {
        return (userId != null && !userId.isBlank()) || (email != null && !email.isBlank());
    }
}

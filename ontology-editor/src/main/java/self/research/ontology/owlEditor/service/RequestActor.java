package self.research.ontology.owlEditor.service;

import jakarta.servlet.http.HttpServletRequest;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

public record RequestActor(String userId, String username) {

    public static RequestActor of(HttpServletRequest request, String userIdParam, String usernameParam) {
        String verified = request == null ? null : JwtIdentityExtractor.extractEmail(request).orElse(null);
        String userId = verified != null ? verified : blank(userIdParam) ? "anonymous" : userIdParam;
        String username = !blank(usernameParam) ? usernameParam : verified != null ? verified : "Anonymous";
        return new RequestActor(userId, username);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

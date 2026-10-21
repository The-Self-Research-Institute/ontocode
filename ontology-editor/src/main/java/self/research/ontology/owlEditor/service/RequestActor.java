package self.research.ontology.owlEditor.service;

import jakarta.servlet.http.HttpServletRequest;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

public record RequestActor(String userId, String username) {

    public static RequestActor of(HttpServletRequest request, String userIdParam, String usernameParam) {
        String verified = request == null ? null : JwtIdentityExtractor.extractEmail(request).orElse(null);
        String userId = verified != null ? verified : blank(userIdParam) ? "anonymous" : userIdParam;
        String username = displayName(request, usernameParam);
        return new RequestActor(userId, username);
    }

    public static String displayName(HttpServletRequest request, String given) {
        if (!blank(given) && !"anonymous".equalsIgnoreCase(given.trim())) {
            return given;
        }
        String verified = request == null ? null : JwtIdentityExtractor.extractEmail(request).orElse(null);
        return verified != null ? verified : "Anonymous";
    }

    public static String userIdOr(HttpServletRequest request, String given) {
        if (!blank(given) || request == null) {
            return given;
        }
        return JwtIdentityExtractor.extractEmail(request).orElse(given);
    }

    public static boolean draftRequested(HttpServletRequest request, String bodyDraft) {
        if (Boolean.parseBoolean(bodyDraft)) {
            return true;
        }
        return request != null && ("true".equalsIgnoreCase(request.getParameter("draft"))
                || "true".equalsIgnoreCase(request.getParameter("useDraft")));
    }

    public static String recordedId(HttpServletRequest request, String given, boolean draft) {
        return draft || request == null ? given : JwtIdentityExtractor.extractEmail(request).orElse(given);
    }

    public static String forScope(HttpServletRequest request, String given, boolean draft) {
        if (!draft) {
            return recordedId(request, given, false);
        }
        boolean placeholder = blank(given) || "anonymous".equalsIgnoreCase(given);
        return placeholder && request != null ? JwtIdentityExtractor.extractUserId(request).orElse(given) : given;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

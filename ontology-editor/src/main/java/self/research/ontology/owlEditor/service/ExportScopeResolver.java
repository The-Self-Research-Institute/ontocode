package self.research.ontology.owlEditor.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.config.JwtClaimUtils;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

@Component
public class ExportScopeResolver {

    public record Scope(boolean draft, String userId, int status, String error) {
        static Scope publicScope() {
            return new Scope(false, null, 200, null);
        }

        static Scope refused(int status, String error) {
            return new Scope(false, null, status, error);
        }

        public boolean allowed() {
            return status == 200;
        }

        public StorageManager.ContentScope contentScope() {
            return draft ? new StorageManager.ContentScope(true, userId) : StorageManager.ContentScope.publicScope();
        }
    }

    public Scope resolve(HttpServletRequest request, boolean draft, String requestedUserId) {
        if (!draft) {
            return Scope.publicScope();
        }
        if (requestedUserId == null || requestedUserId.isBlank()) {
            return Scope.refused(400, "userId is required to export a private draft");
        }
        String verified = JwtIdentityExtractor.extractEmail(request).orElse(null);
        if (verified == null) {
            return JwtIdentityExtractor.signatureRequired()
                    ? Scope.refused(401, "Sign in to export your draft")
                    : new Scope(true, requestedUserId, 200, null);
        }
        String[] claims = JwtClaimUtils.extractPlanAndUserId(request.getHeader("Authorization"));
        String claimUserId = claims == null ? null : claims[1];
        boolean owns = requestedUserId.equalsIgnoreCase(verified)
                || (claimUserId != null && requestedUserId.equalsIgnoreCase(claimUserId));
        return owns ? new Scope(true, requestedUserId, 200, null)
                : Scope.refused(403, "You can only export your own draft");
    }
}

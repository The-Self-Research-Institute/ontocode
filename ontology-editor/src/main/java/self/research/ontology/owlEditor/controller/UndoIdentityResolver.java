package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import self.research.ontology.owlEditor.config.JwtClaimUtils;
import self.research.ontology.owlEditor.service.UserUndoService.Identity;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.Map;

final class UndoIdentityResolver {

    private UndoIdentityResolver() {
    }

    static Identity resolve(RollbackRequestSupport support, HttpServletRequest request, Map<String, Object> body) {
        String verified = JwtIdentityExtractor.extractEmail(request).orElse(null);
        if (verified != null) {
            return Identity.of(verified, JwtClaimUtils.extractIdentityClaims(request.getHeader("Authorization")));
        }
        if (JwtIdentityExtractor.signatureRequired()) {
            return null;
        }
        return Identity.of(support.actor(request, body).userId());
    }
}

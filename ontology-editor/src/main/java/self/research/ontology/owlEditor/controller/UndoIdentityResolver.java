package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import self.research.ontology.owlEditor.config.JwtClaimUtils;
import self.research.ontology.owlEditor.service.UserUndoService.Identity;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.Map;

final class UndoIdentityResolver {

    private UndoIdentityResolver() {
    }

    private static String[] withDesktopUser(String[] claims) {
        String[] all = java.util.Arrays.copyOf(claims, claims.length + 1);
        all[claims.length] = JwtIdentityExtractor.DESKTOP_USER;
        return all;
    }

    static Identity resolve(RollbackRequestSupport support, HttpServletRequest request, Map<String, Object> body) {
        String verified = JwtIdentityExtractor.extractEmail(request).orElse(null);
        if (verified != null) {
            String[] claims = JwtClaimUtils.extractIdentityClaims(request.getHeader("Authorization"));
            return support.isDesktop() ? Identity.of(verified, withDesktopUser(claims)) : Identity.of(verified, claims);
        }
        if (JwtIdentityExtractor.signatureRequired()) {
            return null;
        }
        return Identity.of(support.actor(request, body).userId());
    }
}

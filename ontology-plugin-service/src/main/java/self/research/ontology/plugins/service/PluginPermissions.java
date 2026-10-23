package self.research.ontology.plugins.service;

import org.springframework.security.core.Authentication;

public final class PluginPermissions {

    private PluginPermissions() {
    }

    public static String authorEmail(Authentication authentication) {
        return authentication != null ? authentication.getName() : "admin@ontocode.dev";
    }

    public static boolean isAdmin(Authentication authentication) {
        return authentication == null || authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}

package self.research.ontology.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for OIDC (OpenID Connect) providers.
 * Supports Keycloak as the sole OIDC provider.
 */
@Configuration
@ConfigurationProperties(prefix = "oidc")
@Data
public class OidcConfig {
    
    /**
     * Enable/disable OIDC authentication
     */
    private boolean enabled = false;
    
    /**
     * Base URL for redirect URIs
     */
    private String baseUrl = "http://localhost:8086";
    
    /**
     * Default roles assigned to new OIDC users
     */
    private String defaultRoles = "ROLE_USER";
    
    /**
     * Auto-provision users from OIDC (create if not exists)
     */
    private boolean autoProvision = true;

    /**
     * Allowed redirect_uri origin prefixes (comma-separated string from env).
     * VS Code custom-scheme URIs (vscode://) are always allowed regardless.
     * Example: "http://localhost:3001,https://ontocode.selfresearch.org"
     */
    private String allowedRedirectOrigins = "http://localhost:3001,http://localhost:3000,https://ontocode.selfresearch.org";

    /**
     * Returns true when the given redirect_uri is permitted by the allowlist.
     * Always permits VS Code custom-scheme URIs (vscode://) and localhost.
     */
    public boolean isAllowedRedirectUri(String redirectUri) {
        if (redirectUri == null || redirectUri.isBlank()) return false;
        // Always allow VS Code custom-scheme URIs
        if (!redirectUri.startsWith("http://") && !redirectUri.startsWith("https://")) return true;
        // Always allow any localhost / 127.0.0.1 URL (development)
        if (redirectUri.startsWith("http://localhost") || redirectUri.startsWith("https://localhost")
                || redirectUri.startsWith("http://127.0.0.1") || redirectUri.startsWith("https://127.0.0.1")) {
            return true;
        }
        // Check configured allowed origins
        if (allowedRedirectOrigins != null && !allowedRedirectOrigins.isBlank()) {
            return Arrays.stream(allowedRedirectOrigins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .anyMatch(redirectUri::startsWith);
        }
        return false;
    }
    
    /**
     * Map of provider configurations
     * Key: provider name (e.g., "google", "azure", "okta")
     */
    private Map<String, OidcProviderConfig> providers = new HashMap<>();

    @Data
    public static class OidcProviderConfig {
        /**
         * Enable/disable this specific provider
         */
        private boolean enabled = true;
        
        /**
         * OAuth2 Client ID
         */
        private String clientId;
        
        /**
         * OAuth2 Client Secret
         */
        private String clientSecret;
        
        /**
         * Authorization endpoint URL (if not using Spring's defaults)
         */
        private String authorizationUri;
        
        /**
         * Token endpoint URL (if not using Spring's defaults)
         */
        private String tokenUri;
        
        /**
         * UserInfo endpoint URL (if not using Spring's defaults)
         */
        private String userInfoUri;
        
        /**
         * JWK Set URI for token validation (if not using Spring's defaults)
         */
        private String jwkSetUri;
        
        /**
         * Issuer URI (for well-known configuration discovery)
         */
        private String issuerUri;
        
        /**
         * OAuth2 scopes to request
         */
        private String scope = "openid profile email";
        
        /**
         * User name attribute in the OIDC token (default varies by provider)
         */
        private String userNameAttribute;
        
        /**
         * Provider display name for UI
         */
        private String displayName;
    }
    
    /**
     * Get enabled providers
     */
    public Map<String, OidcProviderConfig> getEnabledProviders() {
        Map<String, OidcProviderConfig> enabledProviders = new HashMap<>();
        if (enabled) {
            providers.forEach((name, config) -> {
                boolean hasClient = config.getClientId() != null && !config.getClientId().isBlank();
                boolean hasIssuer = config.getIssuerUri() != null && !config.getIssuerUri().isBlank();

                // Keycloak flow requires issuer URI; avoid advertising unusable providers.
                if (config.isEnabled() && hasClient && (!"keycloak".equalsIgnoreCase(name) || hasIssuer)) {
                    enabledProviders.put(name, config);
                }
            });
        }
        return enabledProviders;
    }
}

package self.research.ontology.auth.controller;

import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.auth.config.OidcConfig;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.OidcUserService;
import org.springframework.http.MediaType;
import self.research.ontology.auth.util.JwtUtil;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controller for OIDC authentication flow.
 * Handles OAuth2 login callbacks and JWT token generation.
 */
@RestController
@RequestMapping("/api/auth/oidc")
@Slf4j
public class OidcController {

    public static final String SESSION_REDIRECT_URI = "OIDC_REDIRECT_URI";
    public static final String SESSION_EMBEDDED_VIEW = "OIDC_EMBEDDED_VIEW";

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final OidcConfig oidcConfig;
    private final OidcUserService oidcUserService;

    public OidcController(UserRepository userRepository, JwtUtil jwtUtil, OidcConfig oidcConfig, OidcUserService oidcUserService) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.oidcConfig = oidcConfig;
        this.oidcUserService = oidcUserService;
    }

    /**
     * Get list of enabled OIDC providers
     * This endpoint is public and used by clients to show available login options
     */
    @GetMapping("/providers")
    public ResponseEntity<?> getProviders() {
        if (!oidcConfig.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                "enabled", false,
                "providers", List.of()
            ));
        }

        List<Map<String, String>> providers = oidcConfig.getEnabledProviders().entrySet().stream()
                .map(entry -> {
                    Map<String, String> provider = new HashMap<>();
                    provider.put("id", entry.getKey());
                    provider.put("displayName", entry.getValue().getDisplayName() != null 
                        ? entry.getValue().getDisplayName() 
                        : capitalize(entry.getKey()));
                    // Use a gateway-safe URL so browser clients can start OIDC via /api/auth/** routing.
                    provider.put("authUrl", "/api/auth/oidc/authorize/" + entry.getKey());
                    return provider;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
            "enabled", true,
            "providers", providers
        ));
    }

    /**
     * Gateway-safe entrypoint for OIDC authorization.
     *
     * Browser clients call /api/auth/oidc/authorize/{providerId} via gateway.
     * This endpoint redirects to Spring Security's OAuth2 authorization endpoint
     * at /oauth2/authorization/{providerId} inside the auth service.
     */
    @GetMapping("/authorize/{providerId}")
    public ResponseEntity<Void> authorize(
            @PathVariable String providerId,
            @RequestParam(required = false) String redirect_uri,
            @RequestParam(required = false) String embedded_view,
            @RequestParam(required = false) String kc_action,
            HttpServletRequest request,
            HttpServletResponse servletResponse) {
        if (!oidcConfig.isEnabled() || !oidcConfig.getEnabledProviders().containsKey(providerId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // Security: validate redirect_uri against the configured allowlist.
        // Rejects open-redirect attempts (e.g. ?redirect_uri=https://attacker.com).
        // VS Code custom-scheme URIs and localhost are always allowed by isAllowedRedirectUri().
        if (redirect_uri != null && !redirect_uri.isBlank() && !oidcConfig.isAllowedRedirectUri(redirect_uri)) {
            log.warn("Rejected disallowed redirect_uri: {}", redirect_uri);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if (redirect_uri != null && !redirect_uri.isBlank()) {
            request.getSession(true).setAttribute(SESSION_REDIRECT_URI, redirect_uri);
            // Also store in a short-lived cookie so the value survives Spring Security's
            // session-fixation protection (which may swap the session ID between the
            // authorize request and the OAuth2 callback request).
            jakarta.servlet.http.Cookie oidcCookie = new jakarta.servlet.http.Cookie(
                    "OIDC_REDIRECT_URI",
                    java.net.URLEncoder.encode(redirect_uri, java.nio.charset.StandardCharsets.UTF_8));
            oidcCookie.setPath("/");
            oidcCookie.setMaxAge(600); // 10 minutes
            oidcCookie.setHttpOnly(true);
            servletResponse.addCookie(oidcCookie);
        }
        if (embedded_view != null && !embedded_view.isBlank()) {
            request.getSession(true).setAttribute(SESSION_EMBEDDED_VIEW, embedded_view);
            // Same cookie treatment as redirect_uri so it survives session-fixation rotation.
            jakarta.servlet.http.Cookie evCookie = new jakarta.servlet.http.Cookie(
                    "OIDC_EMBEDDED_VIEW", embedded_view);
            evCookie.setPath("/");
            evCookie.setMaxAge(600);
            evCookie.setHttpOnly(true);
            servletResponse.addCookie(evCookie);
        }

        String location = "/oauth2/authorization/" + providerId;
        if (kc_action != null && !kc_action.isBlank()) {
            location = location + "?kc_action=" + java.net.URLEncoder.encode(kc_action, java.nio.charset.StandardCharsets.UTF_8);
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }

    /**
     * OAuth2 login success callback
     * After successful OIDC authentication, this endpoint generates a JWT token
     * 
     * This is called automatically by Spring Security after OAuth2 authentication
     * The OAuth2User is populated by the OidcUserService
     * 
     * Supports redirect_uri parameter for VS Code extension integration
     * and embedded_view parameter for iframe integration
     */
    @GetMapping(value = "/success", produces = {"text/html", "application/json"})
    public Object loginSuccess(
            @AuthenticationPrincipal OAuth2User oauth2User,
            @RequestParam(required = false) String redirect_uri,
            @RequestParam(required = false, defaultValue = "false") boolean embedded_view,
            HttpServletResponse servletResponse) throws java.io.IOException {
        try {
            if (oauth2User == null) {
                log.error("OAuth2User is null in success callback");
                
                if (embedded_view) {
                    return getEmbeddedErrorPage("Authentication failed");
                }
                
                Map<String, Object> authFailBody = new HashMap<>();
                authFailBody.put("error", "Authentication failed");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(authFailBody);
            }

            // Extract email from OAuth2User
            String email = extractEmail(oauth2User);
            log.info("OIDC login success for: {}", email);

            // Find user in database; provision on-demand if auto-provision didn't run
            // (e.g. first-time registration where provisionUser hadn't persisted yet)
            User user = userRepository.findByEmail(email).orElseGet(() -> {
                log.warn("User not found in DB after OIDC auth for {}; provisioning now", email);
                String regId = (oauth2User instanceof org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken token)
                        ? token.getAuthorizedClientRegistrationId()
                        : "keycloak";
                oidcUserService.provisionUser(oauth2User, regId);
                return userRepository.findByEmail(email)
                        .orElseThrow(() -> new RuntimeException("Failed to provision OIDC user: " + email));
            });

            // Update last login
            user.setLastLogin(LocalDateTime.now());
            userRepository.save(user);

            // Generate JWT token with claims
            Map<String, Object> claims = new HashMap<>();
            claims.put("email", user.getEmail());
            claims.put("roles", user.getRoles());
            claims.put("isAdmin", user.getRoles().contains("ROLE_ADMIN"));
            if (user.getName() != null) {
                claims.put("name", user.getName());
            }
            if (user.getOidcProvider() != null) {
                claims.put("oidcProvider", user.getOidcProvider());
            }
            String token = jwtUtil.generateToken(user.getUsername(), claims);

            log.info("JWT token generated for OIDC user: {}", email);

            // If embedded_view is true, return HTML page that posts message to parent
            if (embedded_view) {
                return getEmbeddedSuccessPage(token, user);
            }

            // If redirect_uri is provided (e.g., for VS Code extension), redirect with token
            if (redirect_uri != null && !redirect_uri.isBlank()) {
                String separator = redirect_uri.contains("?") ? "&" : "?";
                String redirectUrl = redirect_uri + separator + "token=" + java.net.URLEncoder.encode(token, "UTF-8");

                log.info("Redirecting to: {}", redirect_uri);

                // Chrome/Edge block automatic HTTP 302 redirects to custom-protocol URIs
                // (such as vscode://) for security reasons. Use an HTML page with a
                // JavaScript window.location assignment, which browsers do allow.
                if (!redirect_uri.startsWith("http://") && !redirect_uri.startsWith("https://")) {
                    String displayName = user.getName() != null ? user.getName() : user.getEmail();
                    return buildCustomSchemeRedirectPage(redirectUrl, displayName);
                }

                servletResponse.sendRedirect(redirectUrl);
                return null;
            }

            // Otherwise, return JSON response
            Map<String, Object> response = new HashMap<>();
            response.put("token", token);
            response.put("username", user.getUsername());
            response.put("email", user.getEmail());
            response.put("name", user.getName());
            response.put("roles", user.getRoles());
            response.put("oidcProvider", user.getOidcProvider());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error in OIDC login success callback", e);
            
            if (embedded_view) {
                return getEmbeddedErrorPage("Authentication processing failed: " + e.getMessage());
            }
            
            Map<String, Object> errBody = new HashMap<>();
            errBody.put("error", "Authentication processing failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errBody);
        }
    }
    
    /**
     * HTML page for custom-scheme redirect URIs (e.g. vscode://).
     * Uses window.location so the browser's protocol-handler policy is satisfied;
     * also shows a manual fallback link in case the automatic navigation is blocked.
     */
    private String buildCustomSchemeRedirectPage(String redirectUrl, String userName) {
        // Safely escape values embedded in HTML/JS
        String safeUrl   = redirectUrl.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
        String safeUrlJs = redirectUrl.replace("\\", "\\\\").replace("'", "\\'");
        String safeName  = (userName != null ? userName : "")
                .replace("&", "&amp;").replace("<", "&lt;");
        return "<!DOCTYPE html>\n"
            + "<html lang=\"en\">\n<head>\n"
            + "  <meta charset=\"UTF-8\">\n"
            + "  <title>Login Successful</title>\n"
            + "  <style>\n"
            + "    body{margin:0;display:flex;align-items:center;justify-content:center;"
            + "height:100vh;font-family:sans-serif;background:#1e1e2e;color:#cdd6f4;}\n"
            + "    .box{text-align:center;padding:40px;}\n"
            + "    h1{font-size:1.6rem;margin-bottom:8px;}\n"
            + "    p{color:#a6adc8;margin:4px 0 20px;}\n"
            + "    a{display:inline-block;padding:10px 24px;background:#89b4fa;color:#1e1e2e;"
            + "border-radius:6px;text-decoration:none;font-weight:600;}\n"
            + "  </style>\n"
            + "</head>\n<body>\n"
            + "  <div class=\"box\">\n"
            + "    <h1>&#10003; Login successful!</h1>\n"
            + "    <p>Welcome, " + safeName + ". Returning to VS Code&hellip;</p>\n"
            + "    <a href=\"" + safeUrl + "\">Click here if VS Code doesn&rsquo;t open</a>\n"
            + "  </div>\n"
            + "  <script>\n"
            + "    // Attempt JS-initiated navigation; browsers allow this for custom protocols.\n"
            + "    try { window.location.href = '" + safeUrlJs + "'; } catch(e) {}\n"
            + "  </script>\n"
            + "</body>\n</html>\n";
    }

    /**
     * Generate HTML success page for embedded view
     */
    private String getEmbeddedSuccessPage(String token, User user) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Login Successful</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        height: 100vh;
                        margin: 0;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        color: white;
                    }
                    .container {
                        text-align: center;
                        background: rgba(255, 255, 255, 0.1);
                        padding: 40px;
                        border-radius: 10px;
                        backdrop-filter: blur(10px);
                    }
                    .checkmark {
                        font-size: 80px;
                        animation: scaleIn 0.5s ease-in-out;
                    }
                    h1 {
                        margin: 20px 0 10px;
                        font-size: 32px;
                    }
                    p {
                        font-size: 16px;
                        opacity: 0.9;
                    }
                    @keyframes scaleIn {
                        from {
                            transform: scale(0);
                        }
                        to {
                            transform: scale(1);
                        }
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="checkmark">✓</div>
                    <h1>Login Successful!</h1>
                    <p>Welcome, """ + (user.getName() != null ? user.getName() : user.getEmail()) + """
</p>
                    <p>Returning to OntoCode...</p>
                </div>
                <script>
                    // Post message back to parent (iframe in VS Code) or opener (popup in browser)
                    var target = (window.opener) ||
                                 (window.parent !== window ? window.parent : null);
                    if (target) {
                        target.postMessage({
                            type: 'oidc-token',
                            token: '""" + token + """
'
                        }, '*');
                    }
                    
                    // Close window/popup after a short delay
                    setTimeout(() => {
                        window.close();
                    }, 2000);
                </script>
            </body>
            </html>
            """;
    }
    
    /**
     * Generate HTML error page for embedded view
     */
    private String getEmbeddedErrorPage(String errorMessage) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Login Failed</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        height: 100vh;
                        margin: 0;
                        background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
                        color: white;
                    }
                    .container {
                        text-align: center;
                        background: rgba(255, 255, 255, 0.1);
                        padding: 40px;
                        border-radius: 10px;
                        backdrop-filter: blur(10px);
                    }
                    .error-icon {
                        font-size: 80px;
                    }
                    h1 {
                        margin: 20px 0 10px;
                        font-size: 32px;
                    }
                    p {
                        font-size: 16px;
                        opacity: 0.9;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="error-icon">✗</div>
                    <h1>Login Failed</h1>
                    <p>""" + errorMessage + """
</p>
                </div>
                <script>
                    // Post error to parent window
                    if (window.parent && window.parent !== window) {
                        window.parent.postMessage({
                            type: 'oidc-error',
                            error: '""" + errorMessage + """
'
                        }, '*');
                    }
                </script>
            </body>
            </html>
            """;
    }

    /**
     * OAuth2 login failure callback
     */
    @GetMapping("/failure")
    public ResponseEntity<?> loginFailure(
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String error_description) {
        
        log.error("OIDC login failed - error: {}, description: {}", error, error_description);
        
        Map<String, String> response = new HashMap<>();
        response.put("error", error != null ? error : "Authentication failed");
        response.put("message", error_description != null ? error_description : "OIDC login failed");
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    /**
     * Check OIDC configuration status
     */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("oidcEnabled", oidcConfig.isEnabled());
        status.put("autoProvision", oidcConfig.isAutoProvision());
        status.put("enabledProviders", oidcConfig.getEnabledProviders().keySet());
        status.put("providersCount", oidcConfig.getEnabledProviders().size());
        
        return ResponseEntity.ok(status);
    }

    /**
     * Extract email from OAuth2User attributes
     */
    private String extractEmail(OAuth2User oauth2User) {
        String email = oauth2User.getAttribute("email");
        if (email == null) {
            email = oauth2User.getAttribute("mail");
        }
        if (email == null) {
            email = oauth2User.getAttribute("upn");
        }
        if (email == null) {
            email = oauth2User.getAttribute("preferred_username");
        }
        
        if (email == null || email.isBlank()) {
            throw new RuntimeException("Email not found in OIDC user attributes");
        }
        
        return email.toLowerCase().trim();
    }

    /**
     * Capitalize first letter
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }
}

package self.research.ontology.owlEditor.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;
import self.research.ontology.owlEditor.service.ProjectAccessService;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.Map;

@Component
public class EditorApiAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(EditorApiAuthInterceptor.class);
    private static final AntPathMatcher PATH = new AntPathMatcher();

    private static final List<String> PUBLIC_PATTERNS = List.of(
            "/actuator/**",
            "/api/auth/**",
            "/ws/**",
            "/api/v1/issues/report"
    );

    private static final String ASSISTANT_SESSION_PATTERN = "/api/v1/code-assistant/sessions/{sessionId}/**";
    public static final String VERIFIED_EMAIL_ATTRIBUTE = "jwtEmail";

    private final ProjectAccessService projectAccessService;
    private final AssistantSessionRepository assistantSessionRepository;

    public EditorApiAuthInterceptor(ProjectAccessService projectAccessService,
                                    AssistantSessionRepository assistantSessionRepository) {
        this.projectAccessService = projectAccessService;
        this.assistantSessionRepository = assistantSessionRepository;
    }

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${ontocode.editor.require-jwt:false}")
    private boolean requireJwt;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            return true;
        }

        if (desktopMode && isLoopback(request.getRemoteAddr())) {
            return true;
        }

        String method = request.getMethod();

        if (HttpMethod.OPTIONS.matches(method)) {
            return true;
        }

        String path = request.getRequestURI();
        if (isPublicPath(path) || isOwnerUpload(request, method, path)) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            if (!requireJwt) {
                return true;
            }
            log.debug("Editor auth required: {} {}", method, path);
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"Authentication required\"}");
            return false;
        }

        try {
            String jwtEmail = verifiedSubject(authHeader);
            request.setAttribute(VERIFIED_EMAIL_ATTRIBUTE, jwtEmail);

            String projectId = pathVariable(request, "projectId");
            if (projectId == null) {
                projectId = assistantSessionProjectId(path);
            }
            if (projectId != null && !projectAccessService.hasProjectAccess(projectId, jwtEmail)
                    && (requireJwt || projectAccessService.projectExists(projectId))) {
                log.warn("Denied {} {} — {} has no access to project {}", method, path, jwtEmail, projectId);
                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                        "{\"error\":\"You do not have access to this project\"}");
                return false;
            }

            return true;
        } catch (Exception e) {
            log.debug("Invalid JWT for {} {}: {}", method, path, e.getMessage());
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"Invalid or expired token\"}");
            return false;
        }
    }

    private static void writeError(HttpServletResponse response, int status, String body) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(body);
    }

    private static boolean isLoopback(String remote) {
        return "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote);
    }

    private static boolean isPublicPath(String path) {
        for (String pattern : PUBLIC_PATTERNS) {
            if (PATH.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOwnerUpload(HttpServletRequest request, String method, String path) {
        return HttpMethod.POST.matches(method) && path.contains("/api/ontology/upload/")
                && request.getParameter("ownerEmail") != null && !request.getParameter("ownerEmail").isBlank();
    }

    private String verifiedSubject(String authHeader) {
        byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
        SecretKey key = Keys.hmacShaKeyFor(keyBytes);
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(authHeader.substring(7).trim())
                .getPayload();
        if (claims.getSubject() == null || claims.getSubject().isBlank()) {
            throw new IllegalArgumentException("missing subject");
        }
        return claims.getSubject();
    }

    private String assistantSessionProjectId(String path) {
        if (!PATH.match(ASSISTANT_SESSION_PATTERN, path)) {
            return null;
        }
        String sessionId = PATH.extractUriTemplateVariables(ASSISTANT_SESSION_PATTERN, path).get("sessionId");
        return assistantSessionRepository.findById(sessionId)
                .map(AssistantSessionDocument::getProjectId)
                .filter(id -> !id.isBlank())
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private String pathVariable(HttpServletRequest request, String name) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attr instanceof Map)) return null;
        String value = ((Map<String, String>) attr).get(name);
        return value == null || value.isBlank() ? null : value;
    }
}

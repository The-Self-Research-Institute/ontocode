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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.repository.ProjectRepository;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private final ProjectRepository projectRepository;
    private final MongoTemplate mongoTemplate;

    public EditorApiAuthInterceptor(ProjectRepository projectRepository, MongoTemplate mongoTemplate) {
        this.projectRepository = projectRepository;
        this.mongoTemplate = mongoTemplate;
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
        if (!requireJwt || jwtSecret == null || jwtSecret.isBlank()) {
            return true;
        }

        if (desktopMode) {
            String remote = request.getRemoteAddr();
            if ("127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote)) {
                return true;
            }
        }

        String method = request.getMethod();

        if (HttpMethod.OPTIONS.matches(method)) {
            return true;
        }

        String path = request.getRequestURI();
        for (String pattern : PUBLIC_PATTERNS) {
            if (PATH.match(pattern, path)) {
                return true;
            }
        }

        if (HttpMethod.POST.matches(method) && path.contains("/api/ontology/upload/")
                && request.getParameter("ownerEmail") != null && !request.getParameter("ownerEmail").isBlank()) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("Editor auth required: {} {}", method, path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Authentication required\"}");
            return false;
        }

        try {
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
            String jwtEmail = claims.getSubject();
            request.setAttribute("jwtEmail", jwtEmail);

            String projectId = pathVariable(request, "projectId");
            if (projectId != null && !hasProjectAccess(projectId, jwtEmail)) {
                log.warn("Denied {} {} — {} has no access to project {}", method, path, jwtEmail, projectId);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"You do not have access to this project\"}");
                return false;
            }

            return true;
        } catch (Exception e) {
            log.debug("Invalid JWT for {} {}: {}", method, path, e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid or expired token\"}");
            return false;
        }
    }

    // Composite "proj-xxx--fileId" keys are polled before the import worker writes that file's
    // tracking doc, so findById can legitimately miss — fall back to the parent project's members.
    private boolean hasProjectAccess(String projectId, String email) {
        Optional<ProjectDocument> direct = projectRepository.findById(projectId);
        if (direct.isPresent()) {
            return direct.get().isAccessibleBy(email);
        }
        int compositeSep = projectId.indexOf("--");
        if (compositeSep <= 0) {
            return false;
        }
        String parentProjectId = projectId.substring(0, compositeSep);
        Query query = new Query(Criteria.where("projectId").is(parentProjectId).orOperator(
                Criteria.where("ownerEmail").is(email),
                Criteria.where("members").elemMatch(Criteria.where("email").is(email))));
        return mongoTemplate.exists(query, ProjectDocument.class, "projects");
    }

    @SuppressWarnings("unchecked")
    private String pathVariable(HttpServletRequest request, String name) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attr instanceof Map)) return null;
        String value = ((Map<String, String>) attr).get(name);
        return value == null || value.isBlank() ? null : value;
    }
}

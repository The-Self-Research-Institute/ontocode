package self.research.ontology.owlEditor.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.document.WorkspaceDocument;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.repository.WorkspaceRepository;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Blocks write operations for FREE plan non-owner users.
 * Plan is read from the JWT claim (fast path). For FREE plan users only,
 * workspace ownership is verified via DB to allow owners to edit their own content.
 */
@Component
public class FreeViewOnlyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FreeViewOnlyInterceptor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AntPathMatcher PATH = new AntPathMatcher();

    // POST paths that are read-only — always allowed regardless of plan
    private static final List<String> READ_ONLY_POST_PATTERNS = List.of(
        "/**/dl-query",
        "/api/sparql/query/**",   // SELECT/CONSTRUCT queries (read-only)
        "/api/sparql/*/queries",  // save/list query templates (not ontology mutations)
        "/api/sqwrl/**",
        "/**/reasoner/**",
        "/**/validate",
        "/**/reload/**",
        "/**/code-view-cache"
    );

    // PUT/DELETE paths allowed for FREE plan (non-ontology operations)
    private static final List<String> FREE_PUT_DELETE_ALLOW_PATTERNS = List.of(
        "/api/sparql/*/queries/**"  // manage saved SPARQL query templates
    );

    private final ProjectRepository projectRepository;
    private final WorkspaceRepository workspaceRepository;

    public FreeViewOnlyInterceptor(ProjectRepository projectRepository,
                                   WorkspaceRepository workspaceRepository) {
        this.projectRepository = projectRepository;
        this.workspaceRepository = workspaceRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String method = request.getMethod();

        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }

        String path = request.getRequestURI();

        if ("POST".equals(method)) {
            for (String pattern : READ_ONLY_POST_PATTERNS) {
                if (PATH.match(pattern, path)) return true;
            }
        }

        if ("PUT".equals(method) || "DELETE".equals(method)) {
            for (String pattern : FREE_PUT_DELETE_ALLOW_PATTERNS) {
                if (PATH.match(pattern, path)) return true;
            }
        }

        String[] jwtClaims = extractJwtClaims(request.getHeader("Authorization"));
        if (jwtClaims == null) return true; // unauthenticated — let security filters handle it

        String plan = jwtClaims[0];
        String userId = jwtClaims[1];

        // PRO/ENTERPRISE users always allowed
        if (!"FREE".equalsIgnoreCase(plan)) return true;

        // FREE plan: workspace owners can edit their own content
        if (userId != null && isWorkspaceOwner(userId, path, request.getParameter("workspaceId"))) {
            return true;
        }

        log.debug("FREE plan write block: userId={} path={}", userId, path);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(
            "{\"error\":\"Your current plan is Free. Upgrade to Pro to edit ontologies.\"," +
            "\"requiresUpgrade\":true}"
        );
        return false;
    }

    private boolean isWorkspaceOwner(String userId, String path, String workspaceIdParam) {
        String workspaceId = workspaceIdParam;
        if (workspaceId == null || workspaceId.isBlank()) {
            workspaceId = resolveWorkspaceFromPath(path);
        }
        if (workspaceId == null) return false; // Can't determine workspace — deny
        Optional<WorkspaceDocument> wsOpt = workspaceRepository.findByWorkspaceId(workspaceId);
        return wsOpt.isPresent() && userId.equals(wsOpt.get().getOwnerId());
    }

    private String[] extractJwtClaims(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String[] parts = authHeader.substring(7).split("\\.");
        if (parts.length != 3) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claims = MAPPER.readTree(decoded);
            String plan = claims.has("plan") ? claims.get("plan").asText() : "FREE";
            String userId = claims.has("userId") ? claims.get("userId").asText() : null;
            return new String[]{plan, userId};
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveWorkspaceFromPath(String uri) {
        if (uri == null) return null;
        try {
            String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8);
            for (String segment : decoded.split("/")) {
                if (!segment.startsWith("proj-")) continue;
                Optional<ProjectDocument> doc = projectRepository.findById(segment);
                if (doc.isPresent() && doc.get().getWorkspaceId() != null) {
                    return doc.get().getWorkspaceId();
                }
                if (segment.contains("--")) {
                    String parentId = segment.substring(0, segment.indexOf("--"));
                    doc = projectRepository.findById(parentId);
                    if (doc.isPresent() && doc.get().getWorkspaceId() != null) {
                        return doc.get().getWorkspaceId();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve workspaceId from path {}: {}", uri, e.getMessage());
        }
        return null;
    }
}

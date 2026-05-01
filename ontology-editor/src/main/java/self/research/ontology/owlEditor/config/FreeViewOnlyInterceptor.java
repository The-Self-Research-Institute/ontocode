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
 * Blocks write operations for FREE plan workspace members.
 * FREE plan members get view-only access — they can browse and query
 * ontologies but cannot create, modify, or delete any content.
 *
 * Read-only POST operations (queries, reasoning, validation) are whitelisted.
 */
@Component
public class FreeViewOnlyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FreeViewOnlyInterceptor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AntPathMatcher PATH = new AntPathMatcher();

    // POST paths that are purely read-only — always allowed for FREE members
    private static final List<String> ALLOWED_WRITE_PATTERNS = List.of(
        "/**/dl-query",
        "/api/sparql/**",
        "/api/sqwrl/**",
        "/**/reasoner/**",
        "/**/validate",
        "/**/reload/**",
        "/**/code-view-cache"   // cache update, not persisted ontology change
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

        // GET/HEAD/OPTIONS are always safe
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }

        String path = request.getRequestURI();

        // Whitelisted read-only POST operations
        if ("POST".equals(method)) {
            for (String pattern : ALLOWED_WRITE_PATTERNS) {
                if (PATH.match(pattern, path)) return true;
            }
        }

        // Extract userId from JWT — no token means unauthenticated, let other filters handle it
        String userId = extractUserId(request.getHeader("Authorization"));
        if (userId == null) return true;

        // Resolve workspaceId: prefer request param (faster), fall back to project lookup
        String workspaceId = request.getParameter("workspaceId");
        if (workspaceId == null || workspaceId.isBlank()) {
            workspaceId = resolveWorkspaceFromPath(path);
        }
        if (workspaceId == null) return true; // Can't determine workspace — allow

        Optional<WorkspaceDocument> wsOpt = workspaceRepository.findByWorkspaceId(workspaceId);
        if (wsOpt.isEmpty()) return true;

        WorkspaceDocument ws = wsOpt.get();
        String plan = ws.getSubscriptionPlan() != null ? ws.getSubscriptionPlan() : "FREE";

        if ("FREE".equalsIgnoreCase(plan) && !userId.equals(ws.getOwnerId())) {
            log.debug("FREE view-only block: userId={} workspaceId={} path={}", userId, workspaceId, path);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"error\":\"Members have view-only access on the Free plan. " +
                "The workspace owner must upgrade to Pro to allow members to edit.\"," +
                "\"requiresUpgrade\":true}"
            );
            return false;
        }

        return true;
    }

    private String extractUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String[] parts = authHeader.substring(7).split("\\.");
        if (parts.length != 3) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claims = MAPPER.readTree(decoded);
            return claims.has("userId") ? claims.get("userId").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Finds the workspaceId by extracting the projectId segment from the URL path
     * and looking up the corresponding ProjectDocument.
     * Project IDs use the "proj-" prefix; hierarchical file IDs use "--" separator.
     */
    private String resolveWorkspaceFromPath(String uri) {
        if (uri == null) return null;
        try {
            String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8);
            for (String segment : decoded.split("/")) {
                if (!segment.startsWith("proj-")) continue;

                // Try the full segment first (could be "proj-abc--file-xyz" for a file)
                Optional<ProjectDocument> doc = projectRepository.findById(segment);
                if (doc.isPresent() && doc.get().getWorkspaceId() != null) {
                    return doc.get().getWorkspaceId();
                }

                // Try the parent project part (before "--")
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

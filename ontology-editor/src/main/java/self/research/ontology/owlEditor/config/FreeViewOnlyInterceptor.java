package self.research.ontology.owlEditor.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;

import java.util.List;

/**
 * Blocks write operations for FREE plan non-owner users.
 * Plan is read from the JWT claim (fast path). For FREE plan users only,
 * workspace ownership is verified via DB to allow owners to edit their own content.
 */
@Component
public class FreeViewOnlyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FreeViewOnlyInterceptor.class);
    private static final AntPathMatcher PATH = new AntPathMatcher();

    // POST paths that are read-only — always allowed regardless of plan
    private static final List<String> READ_ONLY_POST_PATTERNS = List.of(
        "/**/dl-query",
        "/api/sparql/query/**",   // SELECT/CONSTRUCT queries (read-only)
        "/api/sparql/*/queries",  // save/list query templates (not ontology mutations)
        "/api/v1/issues/report",  // support submissions are open to FREE users
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

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    private final WorkspaceOwnershipService workspaceOwnershipService;

    public FreeViewOnlyInterceptor(WorkspaceOwnershipService workspaceOwnershipService) {
        this.workspaceOwnershipService = workspaceOwnershipService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        // Desktop mode: all requests from localhost are fully trusted — no plan checks needed.
        if (desktopMode) {
            String remote = request.getRemoteAddr();
            if ("127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote)) {
                return true;
            }
        }

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

        String[] jwtClaims = JwtClaimUtils.extractPlanAndUserId(request.getHeader("Authorization"));
        if (jwtClaims == null) {
            // Unauthenticated writes are blocked when editor JWT enforcement is on (see EditorApiAuthInterceptor).
            return true;
        }

        String plan = jwtClaims[0];
        String userId = jwtClaims[1];

        // Check VIEWER role — blocked regardless of subscription plan
        String projectId = request.getParameter("projectId");
        if (projectId == null || projectId.isBlank()) {
            projectId = workspaceOwnershipService.resolveProjectIdFromRequestPath(path).orElse(null);
        }
        if (projectId != null && workspaceOwnershipService.isViewerInProject(userId, projectId)) {
            log.debug("VIEWER write block: userId={} projectId={} path={}", userId, projectId, path);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"error\":\"You have view-only access to this project. " +
                "Contact the project owner to request edit permissions.\"," +
                "\"viewOnly\":true}"
            );
            return false;
        }

        // PRO/ENTERPRISE users always allowed
        if (!"FREE".equalsIgnoreCase(plan)) return true;

        // FREE plan: workspace owners can edit their own content
        if (workspaceOwnershipService.isFreePlanUserOwner(userId, path, request.getParameter("workspaceId"))) {
            return true;
        }

        log.debug("FREE plan write block: userId={} path={}", userId, path);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(
            "{\"error\":\"Your current plan is Free. Upgrade to Pro to import or edit ontologies. " +
            "Opening a file imports it into the graph first (same permission gate). " +
            "Workspace owners: open files from your Project Library so your project id is recognized.\"," +
            "\"requiresUpgrade\":true}"
        );
        return false;
    }
}

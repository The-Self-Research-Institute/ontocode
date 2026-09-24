package self.research.ontology.owlEditor.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;
import self.research.ontology.owlEditor.service.ProjectRecoveryLockService;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class FreeViewOnlyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FreeViewOnlyInterceptor.class);
    private static final AntPathMatcher PATH = new AntPathMatcher();

    private static final List<String> READ_ONLY_POST_PATTERNS = List.of(
        "/**/dl-query",
        "/api/sparql/query/**",
        "/api/sparql/*/queries",
        "/api/v1/issues/report",
        "/api/sqwrl/**",
        "/**/reasoner/**",
        "/**/validate",
        "/**/reload/**",
        "/**/code-view-cache",
        "/**/upload-by-file-ref/**",
        "/api/v1/code-assistant/sessions",
        "/api/v1/code-assistant/sessions/*/tools/read_context",
        "/api/v1/code-assistant/sessions/*/tools/run_sparql",
        "/api/v1/code-assistant/sessions/*/provider-call",
        "/api/v1/code-assistant/sessions/*/usage"
    );

    private static final List<String> FREE_PUT_DELETE_ALLOW_PATTERNS = List.of(
        "/api/sparql/*/queries/**",
        "/api/preferences/**"
    );

    private static final String PROPOSE_PATTERN = "/api/v1/code-assistant/sessions/{sessionId}/propose";
    private static final String APPLY_PATTERN = "/api/v1/code-assistant/sessions/{sessionId}/groups/{serverGroupId}/apply";
    private static final String RECOVERY_PATTERN = "/api/v1/code-assistant/projects/{projectId}/recovery/**";
    private static final String RECOVERY_LOCKED_MESSAGE = "This project is locked after a failed assistant apply. "
            + "Restore it or clear the lock before making more changes.";
    private static final String RECOVERY_LOCKED_BODY = "{\"ok\":false,"
            + "\"error\":\"" + RECOVERY_LOCKED_MESSAGE + "\","
            + "\"message\":\"" + RECOVERY_LOCKED_MESSAGE + "\","
            + "\"errorCode\":\"PROJECT_RECOVERY_LOCKED\",\"recoveryLocked\":true}";

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    private final WorkspaceOwnershipService workspaceOwnershipService;
    private final AssistantSessionRepository assistantSessionRepository;
    private final AssistantEditGroupRepository assistantEditGroupRepository;
    private final ProjectRecoveryLockService recoveryLockService;

    public FreeViewOnlyInterceptor(WorkspaceOwnershipService workspaceOwnershipService,
                                    AssistantSessionRepository assistantSessionRepository,
                                    AssistantEditGroupRepository assistantEditGroupRepository,
                                    ProjectRecoveryLockService recoveryLockService) {
        this.workspaceOwnershipService = workspaceOwnershipService;
        this.assistantSessionRepository = assistantSessionRepository;
        this.assistantEditGroupRepository = assistantEditGroupRepository;
        this.recoveryLockService = recoveryLockService;
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

        String projectId = resolveProjectId(request, path);

        if (projectId != null && !PATH.match(RECOVERY_PATTERN, path) && recoveryLockService.isLocked(projectId)) {
            log.warn("Recovery lock write block: projectId={} method={} path={}", projectId, method, path);
            response.setStatus(423);
            response.setContentType("application/json");
            response.getWriter().write(RECOVERY_LOCKED_BODY);
            return false;
        }

        if (desktopMode) {
            String remote = request.getRemoteAddr();
            if ("127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote)) {
                return true;
            }
        }

        String[] jwtClaims = JwtClaimUtils.extractPlanAndUserId(request.getHeader("Authorization"));
        if (jwtClaims == null) {
            return true;
        }

        String plan = jwtClaims[0];
        String userId = jwtClaims[1];

        if (projectId != null) {
            if (workspaceOwnershipService.isViewerInProject(userId, projectId)) {
                log.debug("VIEWER write block: userId={} projectId={} path={}", userId, projectId, path);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"error\":\"You have view-only access to this project.\"," +
                    "\"viewOnly\":true}"
                );
                return false;
            }
            if (workspaceOwnershipService.isDraftEditorInProject(userId, projectId)) {
                boolean isDraftMutation = "true".equalsIgnoreCase(request.getParameter("draft"))
                        || "true".equalsIgnoreCase(request.getParameter("useDraft"));
                boolean isDraftEndpoint = path.contains("/draft") || path.contains("/pull-from-public/");
                if ((isDraftMutation || isDraftEndpoint) && !isCodeAssistantWrite(path)) {
                    return true;
                }
                log.debug("DRAFT_EDITOR direct-write block: userId={} projectId={} path={}", userId, projectId, path);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"error\":\"You can view this project and edit via draft mode. " +
                    "Make changes in your draft copy and raise a pull request for review.\"," +
                    "\"viewOnly\":true,\"draftAllowed\":true}"
                );
                return false;
            }
        }

        if (!"FREE".equalsIgnoreCase(plan)) return true;

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

    private String resolveProjectId(HttpServletRequest request, String path) {
        String projectId = resolveCodeAssistantProjectId(path).orElse(null);
        if (projectId == null || projectId.isBlank()) {
            projectId = request.getParameter("projectId");
        }
        if (projectId == null || projectId.isBlank()) {
            projectId = workspaceOwnershipService.resolveProjectIdFromRequestPath(path).orElse(null);
        }
        return projectId == null || projectId.isBlank() ? null : projectId;
    }

    private static boolean isCodeAssistantWrite(String path) {
        return PATH.match(RECOVERY_PATTERN, path) || PATH.match(APPLY_PATTERN, path)
                || PATH.match(PROPOSE_PATTERN, path);
    }

    private static String decodeSegment(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private Optional<String> resolveCodeAssistantProjectId(String path) {
        if (PATH.match(RECOVERY_PATTERN, path)) {
            String raw = PATH.extractUriTemplateVariables(RECOVERY_PATTERN, path).get("projectId");
            return Optional.ofNullable(raw).map(FreeViewOnlyInterceptor::decodeSegment);
        }
        if (PATH.match(APPLY_PATTERN, path)) {
            Map<String, String> vars = PATH.extractUriTemplateVariables(APPLY_PATTERN, path);
            return assistantEditGroupRepository.findById(vars.get("serverGroupId"))
                    .map(AssistantEditGroupDocument::getProjectId);
        }
        if (PATH.match(PROPOSE_PATTERN, path)) {
            Map<String, String> vars = PATH.extractUriTemplateVariables(PROPOSE_PATTERN, path);
            return assistantSessionRepository.findById(vars.get("sessionId")).map(AssistantSessionDocument::getProjectId);
        }
        return Optional.empty();
    }
}

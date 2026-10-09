package self.research.ontology.owlEditor.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class AssistantSwrlToolService {

    private static final String SWRL_PLUGIN_ID = "swrl-editor-plugin";

    private final AssistantSessionService sessionService;
    private final AssistantAdmissionLimiter admissionLimiter;
    private final AssistantPluginInstallChecker pluginInstallChecker;
    private final RestTemplate restTemplate;

    @Value("${ontology.swrl-service.url:http://127.0.0.1:18084}")
    private String swrlServiceUrl;

    public AssistantSwrlToolService(AssistantSessionService sessionService,
                                     AssistantAdmissionLimiter admissionLimiter,
                                     AssistantPluginInstallChecker pluginInstallChecker) {
        this.sessionService = sessionService;
        this.admissionLimiter = admissionLimiter;
        this.pluginInstallChecker = pluginInstallChecker;
        this.restTemplate = buildRestTemplate();
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(55_000);
        return new RestTemplate(f);
    }

    public SwrlToolResult addRule(String sessionId, String userEmail, String authorizationHeader,
                                   String ruleName, String ruleText) {
        if (ruleName == null || ruleName.isBlank() || ruleText == null || ruleText.isBlank()) {
            return SwrlToolResult.builder().ok(false).errorCode("INVALID_RULE")
                    .message("ruleName and ruleText must both be non-empty.").build();
        }
        return runTool(sessionId, userEmail, authorizationHeader, session -> {
            Map<String, String> validateBody = Map.of("ruleText", ruleText);
            Map<String, Object> validation = postToSwrl(
                    "/api/swrl/" + session.getProjectId() + "/validate", validateBody, authorizationHeader);
            if (Boolean.FALSE.equals(validation.get("valid"))) {
                throw new SwrlCallException("INVALID_RULE",
                        String.valueOf(validation.getOrDefault("errorMessage", "The rule text is not valid SWRL.")));
            }

            Map<String, String> createBody = Map.of("ruleName", ruleName, "ruleText", ruleText);
            return postToSwrl("/api/swrl/" + session.getProjectId() + "/rules", createBody, authorizationHeader);
        });
    }

    public SwrlToolResult runRule(String sessionId, String userEmail, String authorizationHeader) {
        return runTool(sessionId, userEmail, authorizationHeader, session ->
                postToSwrl("/api/swrl/" + session.getProjectId() + "/execute", Map.of(), authorizationHeader));
    }

    private interface SwrlCall {
        Map<String, Object> call(AssistantSessionDocument session) throws SwrlCallException;
    }

    private SwrlToolResult runTool(String sessionId, String userEmail, String authorizationHeader, SwrlCall call) {
        Optional<AssistantSessionDocument> sessionOpt = sessionService.getActiveSession(sessionId, userEmail);
        if (sessionOpt.isEmpty()) {
            return SwrlToolResult.builder().ok(false).errorCode("SESSION_NOT_FOUND")
                    .message("Session not found, expired, or not yours").build();
        }
        AssistantSessionDocument session = sessionOpt.get();

        if (sessionService.isRevisionStale(session)) {
            return SwrlToolResult.builder().ok(false).errorCode("REVISION_STALE")
                    .message("The project has changed since this session's snapshot was pinned. "
                            + "Start a new request to get a fresh snapshot before reading further.")
                    .build();
        }

        AssistantAdmissionLimiter.ToolAdmission admission =
                admissionLimiter.tryAcquireTool(userEmail, session.getProjectId());
        if (admission instanceof AssistantAdmissionLimiter.Rejected rejected) {
            return SwrlToolResult.builder().ok(false).errorCode("RATE_LIMITED")
                    .message("Too many assistant tool calls are running (" + rejected.limit()
                            + " limit). Retry in " + rejected.retryAfterSeconds() + "s.")
                    .retryAfterSeconds(rejected.retryAfterSeconds())
                    .build();
        }
        try (AssistantAdmissionLimiter.Admitted ignored = (AssistantAdmissionLimiter.Admitted) admission) {
            AssistantPluginInstallChecker.InstallStatus installStatus =
                    pluginInstallChecker.checkInstalled(SWRL_PLUGIN_ID, authorizationHeader);
            if (installStatus == AssistantPluginInstallChecker.InstallStatus.NOT_INSTALLED) {
                return SwrlToolResult.builder().ok(false).errorCode("PLUGIN_NOT_INSTALLED")
                        .message("The SWRL plugin isn't installed — install it from the Extensions panel first.")
                        .build();
            }
            if (installStatus == AssistantPluginInstallChecker.InstallStatus.NOT_AUTHORIZED) {
                return SwrlToolResult.builder().ok(false).errorCode("SWRL_PLUGIN_CHECK_UNAUTHORIZED")
                        .message("The plugin service didn't accept your login, so I couldn't confirm the SWRL plugin "
                                + "is installed. Sign in again and retry.")
                        .build();
            }
            if (installStatus == AssistantPluginInstallChecker.InstallStatus.CHECK_FAILED) {
                return SwrlToolResult.builder().ok(false).errorCode("SWRL_SERVICE_CHECK_FAILED")
                        .message("Couldn't reach the plugin service to confirm the SWRL plugin is installed. Try again shortly.")
                        .build();
            }
            return runAdmitted(sessionId, session, call);
        }
    }

    private SwrlToolResult runAdmitted(String sessionId, AssistantSessionDocument session, SwrlCall call) {
        if (!sessionService.tryConsumeRetrievalAttempt(sessionId)) {
            return SwrlToolResult.builder().ok(false).errorCode("BUDGET_EXHAUSTED")
                    .message("Retrieval budget exhausted for this session").build();
        }

        try {
            Map<String, Object> data = call.call(session);
            int estimatedTokens = self.research.ontology.owlEditor.util.AssistantTokenEstimator.estimate(String.valueOf(data));
            if (!sessionService.tryConsumeTokenBudget(sessionId, estimatedTokens)) {
                return SwrlToolResult.builder().ok(false).errorCode("BUDGET_EXHAUSTED")
                        .message("Retrieval token budget exhausted for this session").build();
            }
            AssistantSessionService.BudgetSnapshot budget =
                    sessionService.currentBudgetSnapshot(sessionId, session.getUserEmail()).orElse(null);
            return SwrlToolResult.builder().ok(true).data(data).revision(session.getPinnedRevision())
                    .retrievalAttemptsRemaining(budget == null ? null : budget.retrievalAttemptsRemaining())
                    .tokenBudgetRemaining(budget == null ? null : budget.tokenBudgetRemaining())
                    .build();
        } catch (SwrlCallException e) {
            log.warn("[Assistant] swrl tool call failed for session {}: {}", sessionId, e.getMessage());
            return SwrlToolResult.builder().ok(false).errorCode(e.errorCode).message(e.getMessage()).build();
        }
    }

    private Map<String, Object> postToSwrl(String path, Map<String, String> body, String authorizationHeader)
            throws SwrlCallException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response;
        try {
            response = restTemplate.exchange(swrlServiceUrl + path, HttpMethod.POST, entity, Map.class);
        } catch (ResourceAccessException e) {
            throw new SwrlCallException("SWRL_UNAVAILABLE",
                    "SWRL didn't respond in time — this rule set may be too large for a quick check. "
                            + "Try the SWRL tab directly, which allows more time.");
        } catch (RestClientException e) {
            throw new SwrlCallException("SWRL_UNAVAILABLE", "Could not reach the SWRL service: " + e.getMessage());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null) {
            throw new SwrlCallException("SWRL_UNAVAILABLE", "SWRL returned an empty response.");
        }
        if (response.getStatusCode().is5xxServerError() || Boolean.TRUE.equals(responseBody.get("error"))) {
            throw new SwrlCallException("SWRL_ERROR",
                    String.valueOf(responseBody.getOrDefault("message",
                            responseBody.getOrDefault("errorMessage", "SWRL reported a failure."))));
        }
        return responseBody;
    }

    private static final class SwrlCallException extends Exception {
        private final String errorCode;

        private SwrlCallException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SwrlToolResult {
        private boolean ok;
        private Map<String, Object> data;
        private Long revision;
        private Integer retrievalAttemptsRemaining;
        private Integer tokenBudgetRemaining;
        private String errorCode;
        private String message;
        private Integer retryAfterSeconds;
    }
}

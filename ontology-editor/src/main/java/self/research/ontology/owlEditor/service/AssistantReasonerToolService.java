package self.research.ontology.owlEditor.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.util.AssistantTokenEstimator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Slf4j
@Service
public class AssistantReasonerToolService {

    private static final String DEFAULT_REASONER_TYPE = "HERMIT";
    private static final int MAX_JUSTIFICATIONS = 3;

    private final AssistantSessionService sessionService;
    private final AssistantAdmissionLimiter admissionLimiter;
    private final RestTemplate restTemplate;

    @Value("${ontology.plugin-service.url:http://localhost:8087}")
    private String pluginServiceUrl;

    @Value("${assistant.reasoner.max-unsatisfiable-classes:50}")
    private int maxUnsatisfiableClasses;

    @Value("${assistant.reasoner.max-explanation-bytes:50000}")
    private int maxExplanationBytes;

    @Value("${assistant.reasoner.async-wait-ms:45000}")
    private long asyncWaitMs;

    @Value("${assistant.reasoner.async-poll-ms:1500}")
    private long asyncPollMs;

    @Autowired(required = false)
    private ReasonerWorkerClient reasonerWorkerClient;

    public AssistantReasonerToolService(AssistantSessionService sessionService,
                                         AssistantAdmissionLimiter admissionLimiter) {
        this.sessionService = sessionService;
        this.admissionLimiter = admissionLimiter;
        this.restTemplate = buildRestTemplate();
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(30_000);
        return new RestTemplate(f);
    }

    public ReasonerToolResult checkConsistency(String sessionId, String userEmail, String authorizationHeader) {
        return runTool(sessionId, userEmail, session -> {
            Map<String, String> body = Map.of("reasonerType", DEFAULT_REASONER_TYPE);
            Map<String, Object> response = postToPluginService(
                    "/api/reasoner/" + session.getProjectId() + "/consistency", body, authorizationHeader, 20_000);
            return truncateConsistency(response);
        });
    }

    public ReasonerToolResult explainInconsistency(String sessionId, String userEmail, String authorizationHeader) {
        return runTool(sessionId, userEmail, session -> {
            Map<String, String> body = Map.of(
                    "reasonerType", DEFAULT_REASONER_TYPE,
                    "maxJustifications", String.valueOf(MAX_JUSTIFICATIONS));
            Map<String, Object> response = postToPluginService(
                    "/api/reasoner/" + session.getProjectId() + "/explain-inconsistency", body, authorizationHeader, 30_000);
            return truncateExplanation(response);
        });
    }

    private interface ReasonerCall {
        TruncatableResult call(AssistantSessionDocument session) throws ReasonerCallException;
    }

    private ReasonerToolResult runTool(String sessionId, String userEmail, ReasonerCall call) {
        Optional<AssistantSessionDocument> sessionOpt = sessionService.getActiveSession(sessionId, userEmail);
        if (sessionOpt.isEmpty()) {
            return ReasonerToolResult.builder().ok(false).errorCode("SESSION_NOT_FOUND")
                    .message("Session not found, expired, or not yours").build();
        }
        AssistantSessionDocument session = sessionOpt.get();

        if (sessionService.isRevisionStale(session)) {
            return ReasonerToolResult.builder().ok(false).errorCode("REVISION_STALE")
                    .message("The project has changed since this session's snapshot was pinned. "
                            + "Start a new request to get a fresh snapshot before reading further.")
                    .build();
        }

        AssistantAdmissionLimiter.ToolAdmission admission =
                admissionLimiter.tryAcquireTool(userEmail, session.getProjectId());
        if (admission instanceof AssistantAdmissionLimiter.Rejected rejected) {
            return ReasonerToolResult.builder().ok(false).errorCode("RATE_LIMITED")
                    .message("Too many assistant tool calls are running (" + rejected.limit()
                            + " limit). Retry in " + rejected.retryAfterSeconds() + "s.")
                    .retryAfterSeconds(rejected.retryAfterSeconds())
                    .build();
        }
        try (AssistantAdmissionLimiter.Admitted ignored = (AssistantAdmissionLimiter.Admitted) admission) {
            return runAdmitted(sessionId, session, call);
        }
    }

    private ReasonerToolResult runAdmitted(String sessionId, AssistantSessionDocument session, ReasonerCall call) {
        if (!sessionService.tryConsumeRetrievalAttempt(sessionId)) {
            return ReasonerToolResult.builder().ok(false).errorCode("BUDGET_EXHAUSTED")
                    .message("Retrieval budget exhausted for this session").build();
        }

        try {
            TruncatableResult result = call.call(session);
            int estimatedTokens = AssistantTokenEstimator.estimate(result.estimationText());
            if (!sessionService.tryConsumeTokenBudget(sessionId, estimatedTokens)) {
                return ReasonerToolResult.builder().ok(false).errorCode("BUDGET_EXHAUSTED")
                        .message("Retrieval token budget exhausted for this session").build();
            }
            AssistantSessionService.BudgetSnapshot budget =
                    sessionService.currentBudgetSnapshot(sessionId, session.getUserEmail()).orElse(null);
            return ReasonerToolResult.builder()
                    .ok(true)
                    .data(result.data())
                    .truncated(result.truncated())
                    .revision(session.getPinnedRevision())
                    .retrievalAttemptsRemaining(budget == null ? null : budget.retrievalAttemptsRemaining())
                    .tokenBudgetRemaining(budget == null ? null : budget.tokenBudgetRemaining())
                    .build();
        } catch (ReasonerCallException e) {
            log.warn("[Assistant] reasoner tool call failed for session {}: {}", sessionId, e.getMessage());
            return ReasonerToolResult.builder().ok(false).errorCode(e.errorCode).message(e.getMessage()).build();
        }
    }

    private Map<String, Object> postToPluginService(String path, Map<String, String> body,
                                                      String authorizationHeader, int readTimeoutMs) throws ReasonerCallException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response;
        try {
            response = restTemplate.exchange(pluginServiceUrl + path, HttpMethod.POST, entity, Map.class);
        } catch (ResourceAccessException e) {
            throw new ReasonerCallException("REASONER_UNAVAILABLE",
                    "The reasoner didn't respond in time — it may be busy with a larger operation. "
                            + "A retry shortly after often succeeds faster, since the reasoner keeps working "
                            + "in the background even after this call gives up waiting.");
        } catch (RestClientException e) {
            throw new ReasonerCallException("REASONER_UNAVAILABLE",
                    "Could not reach the reasoner service: " + e.getMessage());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null) {
            throw new ReasonerCallException("REASONER_UNAVAILABLE", "The reasoner returned an empty response.");
        }

        if (Boolean.FALSE.equals(responseBody.get("success"))) {
            throw new ReasonerCallException(
                    String.valueOf(responseBody.getOrDefault("errorType", "REASONER_ERROR")),
                    describeFailure(responseBody));
        }
        if (response.getStatusCode() == HttpStatus.ACCEPTED) {
            return awaitJob(responseBody);
        }
        return responseBody;
    }

    private Map<String, Object> awaitJob(Map<String, Object> accepted) throws ReasonerCallException {
        Object jobId = accepted.getOrDefault("jobId", accepted.get("taskId"));
        if (jobId == null || reasonerWorkerClient == null) {
            throw new ReasonerCallException("REASONER_UNAVAILABLE",
                    "The reasoner started this check in the background but the assistant can't follow it here. "
                            + "Run it from the Reasoner tab instead.");
        }
        long deadline = System.currentTimeMillis() + asyncWaitMs;
        while (true) {
            Map<String, Object> job = reasonerWorkerClient.getJob(String.valueOf(jobId));
            String status = job == null ? "" : String.valueOf(job.get("status")).toUpperCase(java.util.Locale.ROOT);
            if ("COMPLETED".equals(status)) {
                return job;
            }
            if ("FAILED".equals(status) || (job != null && Boolean.FALSE.equals(job.get("success")))) {
                throw new ReasonerCallException("REASONER_ERROR", describeFailure(job));
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new ReasonerCallException("REASONER_UNAVAILABLE",
                        "The reasoner is still working on this. Try again in a few seconds; it keeps running in the background.");
            }
            try {
                Thread.sleep(asyncPollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ReasonerCallException("REASONER_UNAVAILABLE", "Interrupted while waiting for the reasoner.");
            }
        }
    }

    private static String describeFailure(Map<String, Object> body) {
        String error = String.valueOf(body.getOrDefault("error", "The reasoner reported a failure."));
        Object suggestion = body.get("suggestion");
        return suggestion != null ? error + " " + suggestion : error;
    }

    private TruncatableResult truncateConsistency(Map<String, Object> response) {
        Map<String, Object> data = new LinkedHashMap<>(response);
        Object rawList = data.get("unsatisfiableClasses");
        boolean truncated = false;
        if (rawList instanceof List<?> list && list.size() > maxUnsatisfiableClasses) {
            List<Object> capped = new ArrayList<>(list.subList(0, maxUnsatisfiableClasses));
            data.put("unsatisfiableClasses", capped);
            data.put("unsatisfiableClassesTotalCount", list.size());
            truncated = true;
        }
        return new TruncatableResult(data, truncated, String.valueOf(data));
    }

    private TruncatableResult truncateExplanation(Map<String, Object> response) {
        Map<String, Object> data = new LinkedHashMap<>(response);
        String serialized = String.valueOf(data);
        boolean truncated = false;
        if (serialized.length() > maxExplanationBytes) {
            Object causes = data.get("causes");
            if (causes instanceof List<?> list && !list.isEmpty()) {
                int totalCauses = list.size();
                List<Object> trimmed = new ArrayList<>(list);
                while (!trimmed.isEmpty() && String.valueOf(data).length() > maxExplanationBytes) {
                    trimmed.remove(trimmed.size() - 1);
                    data.put("causes", trimmed);
                }
                data.put("causesTotalCount", totalCauses);
                truncated = true;
            }
        }
        return new TruncatableResult(data, truncated, String.valueOf(data));
    }

    private record TruncatableResult(Map<String, Object> data, boolean truncated, String estimationTextValue) {
        String estimationText() {
            return estimationTextValue;
        }
    }

    private static final class ReasonerCallException extends Exception {
        private final String errorCode;

        private ReasonerCallException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReasonerToolResult {
        private boolean ok;
        private Map<String, Object> data;
        private boolean truncated;
        private Long revision;
        private Integer retrievalAttemptsRemaining;
        private Integer tokenBudgetRemaining;
        private String errorCode;
        private String message;
        private Integer retryAfterSeconds;
    }
}

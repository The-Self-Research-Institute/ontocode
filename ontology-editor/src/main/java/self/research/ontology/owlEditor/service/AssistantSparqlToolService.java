package self.research.ontology.owlEditor.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.util.AssistantTokenEstimator;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AssistantSparqlToolService {

    private static final Pattern SELECT_ONLY =
            Pattern.compile("(?is)^\\s*(PREFIX\\s+\\S*\\s+<[^>]*>\\s*)*SELECT\\b");

    private final AssistantSessionService sessionService;
    private final SparqlDatasetService datasetService;
    private final ProjectWriteLockRegistry lockRegistry;
    private final AssistantAdmissionLimiter admissionLimiter;

    @Value("${assistant.sparql.max-rows:200}")
    private int maxRows;

    @Value("${assistant.sparql.max-bytes:200000}")
    private long maxBytes;

    @Value("${assistant.sparql.timeout-seconds:15}")
    private int timeoutSeconds;

    public AssistantSparqlToolService(AssistantSessionService sessionService, SparqlDatasetService datasetService,
                                       ProjectWriteLockRegistry lockRegistry,
                                       AssistantAdmissionLimiter admissionLimiter) {
        this.sessionService = sessionService;
        this.datasetService = datasetService;
        this.lockRegistry = lockRegistry;
        this.admissionLimiter = admissionLimiter;
    }

    public SparqlToolResult runSparql(String sessionId, String userEmail, String query) {
        Optional<AssistantSessionDocument> sessionOpt = sessionService.getActiveSession(sessionId, userEmail);
        if (sessionOpt.isEmpty()) {
            return SparqlToolResult.builder().ok(false).errorCode("SESSION_NOT_FOUND")
                    .message("Session not found, expired, or not yours").build();
        }
        AssistantSessionDocument session = sessionOpt.get();

        if (sessionService.isRevisionStale(session)) {
            return revisionStale();
        }

        if (query == null || !SELECT_ONLY.matcher(query).find()) {
            return SparqlToolResult.builder().ok(false).errorCode("NOT_SELECT_ONLY")
                    .message("Only SELECT queries are allowed").build();
        }

        AssistantAdmissionLimiter.ToolAdmission admission =
                admissionLimiter.tryAcquireTool(userEmail, session.getProjectId());
        if (admission instanceof AssistantAdmissionLimiter.Rejected rejected) {
            return SparqlToolResult.builder().ok(false).errorCode("RATE_LIMITED")
                    .message("Too many assistant tool calls are running (" + rejected.limit()
                            + " limit). Retry in " + rejected.retryAfterSeconds() + "s.")
                    .retryAfterSeconds(rejected.retryAfterSeconds())
                    .build();
        }
        try (AssistantAdmissionLimiter.Admitted ignored = (AssistantAdmissionLimiter.Admitted) admission) {
            return runAdmitted(sessionId, session, query);
        }
    }

    private SparqlToolResult runAdmitted(String sessionId, AssistantSessionDocument session, String query) {
        if (!sessionService.tryConsumeRetrievalAttempt(sessionId)) {
            return SparqlToolResult.builder().ok(false).errorCode("BUDGET_EXHAUSTED")
                    .message("Retrieval budget exhausted for this session")
                    .retrievalCallsRemaining(0).build();
        }

        try {
            Optional<SparqlDatasetService.CappedSparqlResult> readResult = lockRegistry.runShared(session.getProjectId(), () -> {
                SparqlDatasetService.CappedSparqlResult read =
                        datasetService.execSelectCapped(session.getProjectId(), query, timeoutSeconds, maxRows, maxBytes);
                return sessionService.isRevisionStale(session) ? Optional.empty() : Optional.of(read);
            });
            if (readResult.isEmpty()) {
                return revisionStale();
            }
            SparqlDatasetService.CappedSparqlResult capped = readResult.get();
            if (capped.capExceeded() != null) {
                return SparqlToolResult.builder().ok(false).errorCode(capped.capExceeded())
                        .message("Query matched more rows/bytes than the assistant's cap allows ("
                                + capped.rows().size() + " rows collected before the cap). Narrow the query and try again.")
                        .build();
            }
            int estimatedTokens = AssistantTokenEstimator.estimate(resultText(capped.rows()));
            if (!sessionService.tryConsumeTokenBudget(sessionId, estimatedTokens)) {
                return SparqlToolResult.builder().ok(false).errorCode("BUDGET_EXHAUSTED")
                        .message("Retrieval token budget exhausted for this session").build();
            }

            return SparqlToolResult.builder()
                    .ok(true)
                    .rows(capped.rows())
                    .truncated(capped.truncated())
                    .rowCount(capped.rows().size())
                    .revision(session.getPinnedRevision())
                    .build();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            String lower = msg.toLowerCase();
            String errorCode = (lower.contains("interrupt") || lower.contains("timeout")) ? "TIMEOUT" : "QUERY_ERROR";
            log.warn("[Assistant] run_sparql failed for session {}: {}", sessionId, msg);
            return SparqlToolResult.builder().ok(false).errorCode(errorCode).message(msg).build();
        }
    }

    private SparqlToolResult revisionStale() {
        return SparqlToolResult.builder().ok(false).errorCode("REVISION_STALE")
                .message("The project has changed since this session's snapshot was pinned. "
                        + "Start a new request to get a fresh snapshot before reading further.").build();
    }

    private String resultText(List<Map<String, String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> row : rows) {
            for (String value : row.values()) {
                if (value != null) {
                    sb.append(value);
                }
            }
        }
        return sb.toString();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SparqlToolResult {
        private boolean ok;
        private List<Map<String, String>> rows;
        private boolean truncated;
        private int rowCount;
        private Long revision;
        private Integer retrievalCallsRemaining;
        private String errorCode;
        private String message;
        private Integer retryAfterSeconds;
    }
}

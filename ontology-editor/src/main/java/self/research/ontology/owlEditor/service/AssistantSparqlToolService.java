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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AssistantSparqlToolService {

    private static final Pattern DECLARED_PREFIX_PATTERN =
            Pattern.compile("(?im)^\\s*PREFIX\\s+([A-Za-z_][\\w.-]*)?:\\s*<");

    private final AssistantSessionService sessionService;
    private final SparqlDatasetService datasetService;
    private final ProjectWriteLockRegistry lockRegistry;
    private final AssistantAdmissionLimiter admissionLimiter;
    private final OntologyMetadataService ontologyMetadataService;

    @Value("${assistant.sparql.max-rows:200}")
    private int maxRows;

    @Value("${assistant.sparql.max-bytes:200000}")
    private long maxBytes;

    @Value("${assistant.sparql.max-query-chars:20000}")
    private int maxQueryChars = 20_000;

    @Value("${assistant.sparql.timeout-seconds:15}")
    private int timeoutSeconds;

    public AssistantSparqlToolService(AssistantSessionService sessionService, SparqlDatasetService datasetService,
                                       ProjectWriteLockRegistry lockRegistry,
                                       AssistantAdmissionLimiter admissionLimiter,
                                       OntologyMetadataService ontologyMetadataService) {
        this.sessionService = sessionService;
        this.datasetService = datasetService;
        this.lockRegistry = lockRegistry;
        this.admissionLimiter = admissionLimiter;
        this.ontologyMetadataService = ontologyMetadataService;
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

        AssistantSparqlGuard.Verdict verdict = AssistantSparqlGuard.check(query, maxQueryChars);
        if (!verdict.allowed()) {
            return SparqlToolResult.builder().ok(false).errorCode(verdict.errorCode()).message(verdict.message()).build();
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
                    .retrievalAttemptsRemaining(0).build();
        }

        try {
            String effectiveQuery = injectPrefixes(query, session.getProjectId());
            Optional<SparqlDatasetService.CappedSparqlResult> readResult = lockRegistry.runShared(session.getProjectId(), () -> {
                SparqlDatasetService.CappedSparqlResult read =
                        datasetService.execSelectCapped(session.getProjectId(), effectiveQuery, timeoutSeconds, maxRows, maxBytes);
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

            AssistantSessionService.BudgetSnapshot budget =
                    sessionService.currentBudgetSnapshot(sessionId, session.getUserEmail()).orElse(null);
            return SparqlToolResult.builder()
                    .ok(true)
                    .rows(capped.rows())
                    .truncated(capped.truncated())
                    .rowCount(capped.rows().size())
                    .revision(session.getPinnedRevision())
                    .retrievalAttemptsRemaining(budget == null ? null : budget.retrievalAttemptsRemaining())
                    .tokenBudgetRemaining(budget == null ? null : budget.tokenBudgetRemaining())
                    .build();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            String lower = msg.toLowerCase();
            String errorCode = (lower.contains("interrupt") || lower.contains("timeout")) ? "TIMEOUT" : "QUERY_ERROR";
            log.warn("[Assistant] run_sparql failed for session {}: {}", sessionId, msg);
            String shown = "TIMEOUT".equals(errorCode)
                    ? "The query timed out. Narrow it and try again."
                    : "The query could not be executed. Check its syntax and try a simpler query.";
            return SparqlToolResult.builder().ok(false).errorCode(errorCode).message(shown).build();
        }
    }

    private String injectPrefixes(String query, String projectId) {
        Set<String> declared = new HashSet<>();
        Matcher matcher = DECLARED_PREFIX_PATTERN.matcher(query);
        while (matcher.find()) {
            declared.add(matcher.group(1) == null ? "" : matcher.group(1));
        }

        StringBuilder header = new StringBuilder();
        for (Map<String, String> entry : ontologyMetadataService.getPrefixes(projectId)) {
            String prefix = entry.get("prefix");
            String namespace = entry.get("namespace");
            if (prefix == null || namespace == null || declared.contains(prefix)) {
                continue;
            }
            header.append("PREFIX ").append(prefix).append(": <").append(namespace).append(">\n");
        }
        return header.length() == 0 ? query : header + query;
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
        private Integer retrievalAttemptsRemaining;
        private Integer tokenBudgetRemaining;
        private String errorCode;
        private String message;
        private Integer retryAfterSeconds;
    }
}
